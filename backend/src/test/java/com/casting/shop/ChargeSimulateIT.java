package com.casting.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casting.shop.common.BizException;
import com.casting.shop.domain.Charge;
import com.casting.shop.domain.ChargeItem;
import com.casting.shop.domain.Furnace;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.ChargeSimulateRequest;
import com.casting.shop.repository.ChargeItemRepository;
import com.casting.shop.repository.ChargeRepository;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.repository.HeatRepository;
import com.casting.shop.service.ChargeSimulateService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 配料偏差双方案推演冒烟（H2 MySQL 兼容模式）：
 * 同一基准出两套方案、硬约束不被总重顶替、冲突如实上报、推演只读、大量明细耗时可控。
 */
@SpringBootTest
class ChargeSimulateIT {

    @Autowired private ChargeSimulateService simulateService;
    @Autowired private FurnaceRepository furnaceRepository;
    @Autowired private HeatRepository heatRepository;
    @Autowired private ChargeRepository chargeRepository;
    @Autowired private ChargeItemRepository itemRepository;

    @BeforeEach
    void clean() {
        itemRepository.deleteAll();
        chargeRepository.deleteAll();
        heatRepository.deleteAll();
        furnaceRepository.deleteAll();
    }

    // ---------------- 造数工具 ----------------

    private Furnace newFurnace(String no, double capacityKg) {
        Furnace f = new Furnace();
        f.setFurnaceNo(no);
        f.setFurnaceName(no + " 感应炉");
        f.setFurnaceType("INDUCTION");
        f.setCapacityKg(BigDecimal.valueOf(capacityKg));
        f.setStatus("BUSY");
        f.setCreatedAt(LocalDateTime.now());
        return furnaceRepository.save(f);
    }

    private Heat newHeat(String no, Furnace furnace, String alloy, double planKg) {
        Heat h = new Heat();
        h.setHeatNo(no);
        h.setFurnaceId(furnace == null ? null : furnace.getId());
        h.setFurnaceNo(furnace == null ? null : furnace.getFurnaceNo());
        h.setAlloy(alloy);
        h.setPlanWeight(BigDecimal.valueOf(planKg));
        h.setStage("CHARGING");
        h.setStartTime(LocalDateTime.now());
        h.setCreatedAt(LocalDateTime.now());
        return heatRepository.save(h);
    }

    /** 直接落一张配料单 + 明细（绕过 ChargeService 的偏差校验，便于构造各种基准） */
    private Charge newCharge(Heat heat, String no, Object[][] items) {
        Charge c = new Charge();
        c.setChargeNo(no);
        c.setHeatId(heat.getId());
        c.setHeatNo(heat.getHeatNo());
        c.setOperator("测试员");
        c.setChargeTime(LocalDateTime.now());
        double total = 0;
        for (Object[] it : items) {
            total += ((Number) it[1]).doubleValue();
        }
        c.setItemCount(items.length);
        c.setTotalWeight(BigDecimal.valueOf(total));
        c.setTargetWeight(heat.getPlanWeight());
        c.setDeviationRate(BigDecimal.ZERO);
        Charge saved = chargeRepository.save(c);
        for (Object[] it : items) {
            ChargeItem row = new ChargeItem();
            row.setChargeId(saved.getId());
            row.setMaterialCode((String) it[0]);
            row.setMaterialName((String) it[0]);
            row.setWeightKg(BigDecimal.valueOf(((Number) it[1]).doubleValue()));
            row.setRatio(BigDecimal.valueOf(((Number) it[1]).doubleValue() / total * 100));
            itemRepository.save(row);
        }
        return saved;
    }

    private ChargeSimulateRequest req(Long heatId, Double target, Boolean allowNew, Map<String, Double> caps) {
        ChargeSimulateRequest r = new ChargeSimulateRequest();
        r.setHeatId(heatId);
        r.setTargetWeight(target == null ? null : BigDecimal.valueOf(target));
        r.setAllowNewMaterial(allowNew);
        if (caps != null) {
            Map<String, BigDecimal> m = new HashMap<>();
            caps.forEach((k, v) -> m.put(k, BigDecimal.valueOf(v)));
            r.setItemCaps(m);
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> scheme(Map<String, Object> result, String key) {
        return (Map<String, Object>) result.get(key);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> adjustments(Map<String, Object> scheme) {
        return (List<Map<String, Object>>) scheme.get("adjustments");
    }

    private static double num(Map<String, Object> scheme, String key) {
        return ((Number) scheme.get(key)).doubleValue();
    }

    /** 种子同款基准：灰铁目标 860、容量 1000、现有 847（生铁380/回炉400/硅铁25/增碳30/造渣12） */
    private Object[][] seedItems() {
        return new Object[][]{
                {"PIG_IRON", 380.0}, {"SCRAP", 400.0}, {"FERRO_SI", 25.0},
                {"CARBURANT", 30.0}, {"FLUX", 12.0}};
    }

    // ---------------- 1. 已出炉炉次不许推演 ----------------

    @Test
    void tappedHeatRejected() {
        Furnace f = newFurnace("F-T-01", 1000);
        Heat h = newHeat("H-T-01", f, "CAST_IRON", 860);
        h.setTapWeight(BigDecimal.valueOf(850));
        heatRepository.save(h);
        final Long heatId = h.getId();
        BizException e = assertThrows(BizException.class, () -> simulateService.simulate(req(heatId, null, true, null)));
        assertTrue(e.getMessage().contains("未出炉"), e.getMessage());
    }

    // ---------------- 2. 同一基准出两套方案，且都不是只凑总重 ----------------

    @Test
    void twoSchemesShareBaselineAndRespectProcessLimits() {
        Furnace f = newFurnace("F-T-02", 1000);
        Heat h = newHeat("H-T-02", f, "CAST_IRON", 860);
        newCharge(h, "CH-T-02", seedItems());

        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, true, null));
        assertTrue((Boolean) r.get("feasible"));

        Map<String, Object> cost = scheme(r, "minCost");
        Map<String, Object> dev = scheme(r, "minDeviation");
        // 成本方案：现有料够用 → 零补料成本，等比缩减到窗口下限
        assertEquals(0.0, num(cost, "supplementCost"), 0.01);
        assertTrue(Math.abs(num(cost, "deviationRate")) <= 3.0, "偏差率不得越界");
        assertTrue(num(cost, "totalWeight") <= 1000.0, "不得超炉体容量");
        assertTrue(num(cost, "scrapRatio") <= 65.0, "回炉料占比不得破工艺上限");
        // 偏离方案：成分偏离压到近 0，但需要花钱买合金料
        assertTrue(num(dev, "compositionDeviation") <= 0.01);
        assertTrue(num(dev, "supplementCost") > 0);
        // 成本方案不是「只凑总重」：它的成分偏离明显大于偏离方案
        assertTrue(num(cost, "compositionDeviation") > num(dev, "compositionDeviation"));
        // 推荐：偏离差 5.44 点 > 1 且差价 106.49 元 ≤ 200 → 推荐最小成分偏离
        Map<String, Object> reco = scheme(r, "recommendation");
        assertEquals("MIN_DEVIATION", reco.get("choice"));
        assertNotNull(reco.get("reason"));
        assertNotNull(reco.get("sacrifice"));
        // 基准回显：两套方案共享的输入版本
        Map<String, Object> baseline = scheme(r, "baseline");
        assertEquals(860.0, num(baseline, "targetWeight"), 0.01);
        assertEquals("计划重量", baseline.get("targetSource"));
        assertEquals(847.0, num(baseline, "currentTotalKg"), 0.01);
        assertEquals(1000.0, num(baseline, "capacityKg"), 0.01);
    }

    // ---------------- 3. 炉体容量与目标重量矛盾 → 具体冲突，不给方案 ----------------

    @Test
    void capacityConflictReportedWithoutMisleadingSchemes() {
        Furnace f = newFurnace("F-T-03", 800);   // 容量 800 < 窗口下限 834.2
        Heat h = newHeat("H-T-03", f, "CAST_IRON", 860);
        newCharge(h, "CH-T-03", seedItems());

        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, true, null));
        assertFalse((Boolean) r.get("feasible"));
        @SuppressWarnings("unchecked")
        List<String> conflicts = (List<String>) r.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("容量") && c.contains("800")), conflicts.toString());
        assertNull(r.get("recommendation"), "冲突时不得给出推荐");
        for (String key : List.of("minCost", "minDeviation")) {
            Map<String, Object> s = scheme(r, key);
            assertFalse((Boolean) s.get("feasible"));
            assertNull(s.get("totalWeight"), "不可行方案不得给出试算总重");
            assertFalse(((List<?>) s.get("infeasibleReasons")).isEmpty(), "不可行方案必须列出原因");
        }
    }

    // ---------------- 4. 原料上限与目标重量矛盾 ----------------

    @Test
    void itemCapsConflictReported() {
        Furnace f = newFurnace("F-T-04", 2000);
        Heat h = newHeat("H-T-04", f, "CAST_IRON", 860);
        newCharge(h, "CH-T-04", new Object[][]{{"PIG_IRON", 90.0}, {"SCRAP", 90.0}});
        Map<String, Double> caps = new HashMap<>();
        for (String code : List.of("PIG_IRON", "SCRAP", "FERRO_SI", "FERRO_MN", "CARBURANT", "FLUX")) {
            caps.put(code, 100.0);   // 六项上限合计 600 < 窗口下限 834.2
        }
        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, true, caps));
        assertFalse((Boolean) r.get("feasible"));
        @SuppressWarnings("unchecked")
        List<String> conflicts = (List<String>) r.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("上限")), conflicts.toString());
    }

    // ---------------- 5. 空明细 + 不允许新料 ----------------

    @Test
    void emptyItemsWithoutNewMaterialReported() {
        Furnace f = newFurnace("F-T-05", 2000);
        Heat h = newHeat("H-T-05", f, "CAST_IRON", 860);
        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, false, null));
        assertFalse((Boolean) r.get("feasible"));
        @SuppressWarnings("unchecked")
        List<String> conflicts = (List<String>) r.get("conflicts");
        assertTrue(conflicts.stream().anyMatch(c -> c.contains("没有任何可用料项")), conflicts.toString());
    }

    // ---------------- 6. 两套结果等价 → 明确标为无偏好 ----------------

    @Test
    void equivalentSchemesMarkedNoPreference() {
        Furnace f = newFurnace("F-T-06", 970);   // 容量 970 = 窗口下限，两方案只能取同一总重
        Heat h = newHeat("H-T-06", f, "CAST_IRON", 1000);
        // 当前明细 = 灰铁标准配方 × 970：两方案都只能原样保留
        newCharge(h, "CH-T-06", new Object[][]{
                {"PIG_IRON", 436.5}, {"SCRAP", 436.5}, {"FERRO_SI", 24.25},
                {"FERRO_MN", 9.7}, {"CARBURANT", 33.95}, {"FLUX", 29.1}});
        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, true, null));
        assertTrue((Boolean) r.get("feasible"));
        assertEquals(0.0, num(scheme(r, "minCost"), "supplementCost"), 0.01);
        assertEquals(0.0, num(scheme(r, "minDeviation"), "compositionDeviation"), 0.01);
        assertEquals("NONE", scheme(r, "recommendation").get("choice"));
    }

    // ---------------- 7. 推演只读：炉次 / 配料明细 / 炉体状态全程不变 ----------------

    @Test
    void simulationNeverMutatesData() {
        Furnace f = newFurnace("F-T-07", 1000);
        Heat h = newHeat("H-T-07", f, "CAST_IRON", 860);
        Charge c = newCharge(h, "CH-T-07", seedItems());

        simulateService.simulate(req(h.getId(), 900.0, false, null));
        simulateService.simulate(req(h.getId(), null, true, null));

        Heat after = heatRepository.findById(h.getId()).orElseThrow();
        assertEquals("CHARGING", after.getStage());
        assertNull(after.getTapWeight());
        assertEquals(0, BigDecimal.valueOf(860).compareTo(after.getPlanWeight()));
        List<ChargeItem> items = itemRepository.findByChargeIdOrderByIdAsc(c.getId());
        assertEquals(5, items.size());
        assertEquals(0, BigDecimal.valueOf(380).compareTo(items.get(0).getWeightKg()));
        assertEquals(0, BigDecimal.valueOf(847).compareTo(
                chargeRepository.findById(c.getId()).orElseThrow().getTotalWeight()));
        assertEquals("BUSY", furnaceRepository.findById(f.getId()).orElseThrow().getStatus());
    }

    // ---------------- 8. 不允许新料 → 建议里不出现新料项 ----------------

    @Test
    void noNewMaterialWhenNotAllowed() {
        Furnace f = newFurnace("F-T-08", 1000);
        Heat h = newHeat("H-T-08", f, "CAST_IRON", 860);
        newCharge(h, "CH-T-08", new Object[][]{{"PIG_IRON", 380.0}, {"SCRAP", 400.0}, {"FLUX", 12.0}});
        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, false, null));
        assertTrue((Boolean) r.get("feasible"));
        for (String key : List.of("minCost", "minDeviation")) {
            for (Map<String, Object> a : adjustments(scheme(r, key))) {
                assertFalse((Boolean) a.get("isNew"), "未允许新料时不得建议引入新料项");
            }
        }
    }

    // ---------------- 9. 页面指定目标重量覆盖计划重量 ----------------

    @Test
    void targetWeightOverrideUsesSameInputVersion() {
        Furnace f = newFurnace("F-T-09", 1000);
        Heat h = newHeat("H-T-09", f, "CAST_IRON", 860);
        newCharge(h, "CH-T-09", seedItems());
        Map<String, Object> r = simulateService.simulate(req(h.getId(), 700.0, true, null));
        Map<String, Object> baseline = scheme(r, "baseline");
        assertEquals(700.0, num(baseline, "targetWeight"), 0.01);
        assertEquals("页面指定", baseline.get("targetSource"));
        // 两套方案都按 700 的窗口试算（同一输入版本）
        assertTrue(Math.abs(num(scheme(r, "minCost"), "deviationRate")) <= 3.0);
        assertTrue(Math.abs(num(scheme(r, "minDeviation"), "deviationRate")) <= 3.0);
    }

    // ---------------- 10. 大量料项下试算耗时保持可接受 ----------------

    @Test
    void largeItemCountStillFast() {
        Furnace f = newFurnace("F-T-10", 20000);
        Heat h = newHeat("H-T-10", f, "CAST_IRON", 860);
        Charge c = new Charge();
        c.setChargeNo("CH-T-10");
        c.setHeatId(h.getId());
        c.setHeatNo(h.getHeatNo());
        c.setOperator("测试员");
        c.setChargeTime(LocalDateTime.now());
        c.setItemCount(6000);
        c.setTotalWeight(BigDecimal.valueOf(19980));
        c.setTargetWeight(BigDecimal.valueOf(860));
        c.setDeviationRate(BigDecimal.ZERO);
        Charge saved = chargeRepository.save(c);
        String[] codes = {"PIG_IRON", "SCRAP", "FERRO_SI", "FERRO_MN", "CARBURANT", "FLUX"};
        for (int i = 0; i < 6000; i++) {
            ChargeItem row = new ChargeItem();
            row.setChargeId(saved.getId());
            row.setMaterialCode(codes[i % codes.length]);
            row.setMaterialName(codes[i % codes.length]);
            row.setWeightKg(BigDecimal.valueOf(3.33));
            row.setRatio(BigDecimal.valueOf(16.67));
            itemRepository.save(row);
        }
        long t0 = System.currentTimeMillis();
        Map<String, Object> r = simulateService.simulate(req(h.getId(), null, true, null));
        long elapsed = System.currentTimeMillis() - t0;
        assertTrue((Boolean) r.get("feasible"));
        assertTrue(elapsed < 3000, "6000 条明细推演耗时 " + elapsed + "ms，超过 3000ms");
    }
}
