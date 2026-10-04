package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Charge;
import com.casting.shop.domain.ChargeItem;
import com.casting.shop.domain.Furnace;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.ChargeSimulateRequest;
import com.casting.shop.repository.ChargeItemRepository;
import com.casting.shop.repository.ChargeRepository;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.repository.HeatRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 配料偏差双方案推演（只读试算：全程不改炉次、配料明细与炉体状态）。
 *
 * <p>选中未出炉炉次后，以「当前配料明细 + 目标重量 + 炉体容量 + 合金材质 + 回炉料比例上限」
 * 为同一份基准，一次算出两套调整方案：</p>
 * <ul>
 *   <li>最低补料成本：先用足现有料项（免费段），总重超出窗口下限就等比缩减（不动配比、零成本），
 *       仍有缺口再按炉料单价升序补料（购买段，段成本凸 → 贪心即最优）；</li>
 *   <li>最小成分偏离：在偏差窗口内均匀扫描试算总重，每个总重下把各品种重量向材质标准配料比投影
 *       （撞上限的品种钉在上界、余量按配方比例再分），取成分偏离最小者。</li>
 * </ul>
 *
 * <p>两套方案共同遵守的硬约束（不允许用总重接近目标来顶替）：总重 ∈ 目标±3% 且 ≤ 炉体容量、
 * 单品种 ≤ 单项上限、回炉料占比 ≤ 材质工艺上限。约束互相矛盾时返回具体冲突，不给出方案。
 * 计算只做一次聚合 + 常数次扫描，耗时与配料明细行数基本无关，大量料项下保持可接受。</p>
 */
@Service
public class ChargeSimulateService {

    private static final String SCRAP = "SCRAP";
    private static final double EPS = 1e-6;
    /** 成分偏离方案在偏差窗口内均匀扫描的总重取点数（含两端） */
    private static final int SCAN_STEPS = 240;
    /** 偏差窗口向内收缩的安全边距 kg：建议重量保留两位小数后仍稳在窗口内 */
    private static final double WINDOW_MARGIN_KG = 0.05;
    /** 推荐阈值：成分偏离差不超过该值（百分点）视为相当，倾向省钱 */
    private static final double RECO_DEV_TOLERANCE = 1.0;
    /** 推荐阈值：偏离方案多花的补料成本不超过 max(该定额, 成本方案×10%) 时倾向压低偏离 */
    private static final double RECO_COST_TOLERANCE = 200.0;
    private static final double RECO_COST_TOLERANCE_RATE = 0.10;
    /** 成本/偏离差小于该值视为两套结果等价（无偏好） */
    private static final double EQUIV_TOLERANCE = 0.01;

    private final HeatRepository heatRepository;
    private final FurnaceRepository furnaceRepository;
    private final ChargeRepository chargeRepository;
    private final ChargeItemRepository itemRepository;

    public ChargeSimulateService(HeatRepository heatRepository, FurnaceRepository furnaceRepository,
                                 ChargeRepository chargeRepository, ChargeItemRepository itemRepository) {
        this.heatRepository = heatRepository;
        this.furnaceRepository = furnaceRepository;
        this.chargeRepository = chargeRepository;
        this.itemRepository = itemRepository;
    }

    /** 一次推演的共享基准：两套方案吃的是同一份输入版本 */
    private static final class Ctx {
        private Map<String, Double> current;    // 六品种当前重量 kg
        private Map<String, Double> caps;       // 六品种单项上限 kg
        private Map<String, Double> recipe;     // 六品种标准配比（小数，合计 1）
        private List<String> allowed;           // 允许参与的品种（现有品种 ∪ 新料）
        private double target;                  // 目标重量 kg
        private double lo;                      // 偏差窗口下限 kg（目标 −3%）
        private double hi;                      // 偏差窗口上限 kg（目标 +3%）
        private double hiEff;                   // min(hi, 炉体容量)
        private double scrapMax;                // 回炉料占比工艺上限 %
        private Double capacity;                // 炉体容量 kg，未关联炉体为 null
        private boolean allowNew;
    }

    // ---------------- 推演看板：未出炉炉次 + 品种单价 + 默认参数 ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> openHeats() {
        Map<Long, Furnace> furnaces = new LinkedHashMap<>();
        for (Furnace f : furnaceRepository.findAll()) {
            furnaces.put(f.getId(), f);
        }
        List<Map<String, Object>> heats = new ArrayList<>();
        for (Heat h : heatRepository.findAllByOrderByIdAsc()) {
            if (h.isTapped()) {
                continue;   // 已出炉的炉次不进入推演
            }
            Furnace f = h.getFurnaceId() == null ? null : furnaces.get(h.getFurnaceId());
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", h.getId());
            item.put("heatNo", h.getHeatNo());
            item.put("alloy", h.getAlloy());
            item.put("alloyName", h.getAlloyName());
            item.put("alloyColor", h.getAlloyColor());
            item.put("stage", h.getStage());
            item.put("stageName", h.getStageName());
            item.put("planWeight", h.getPlanWeight());
            item.put("furnaceNo", f == null ? null : f.getFurnaceNo());
            item.put("furnaceName", f == null ? null : f.getFurnaceName());
            item.put("capacityKg", f == null ? null : f.getCapacityKg());
            heats.add(item);
        }
        List<Map<String, Object>> materials = new ArrayList<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", Dict.chargeMaterialName(code));
            m.put("color", Dict.chargeMaterialColor(code));
            m.put("costPerKg", Dict.chargeMaterialCost(code));
            materials.add(m);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("heats", heats);
        data.put("materials", materials);
        data.put("maxItemWeight", Dict.MAX_ITEM_WEIGHT);
        data.put("maxDeviationRate", Dict.MAX_CHARGE_DEVIATION_RATE);
        return data;
    }

    // ---------------- 双方案推演主流程 ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> simulate(ChargeSimulateRequest request) {
        if (request == null || request.getHeatId() == null) {
            throw new BizException("请先选择要推演的未出炉炉次");
        }
        Heat heat = heatRepository.findById(request.getHeatId())
                .orElseThrow(() -> new BizException("炉次不存在：" + request.getHeatId()));
        if (heat.isTapped()) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 已登记出炉重量 "
                    + heat.getTapWeight() + " kg，双方案推演只面向未出炉炉次");
        }

        // ---- 同一份基准：当前配料明细（该炉次最新一张配料单；没有则空基准） ----
        Charge baseCharge = chargeRepository.findByHeatIdOrderByIdDesc(heat.getId())
                .stream().findFirst().orElse(null);
        Map<String, Double> current = zeroWeights();
        if (baseCharge != null) {
            for (ChargeItem item : itemRepository.findByChargeIdOrderByIdAsc(baseCharge.getId())) {
                if (Dict.isValidChargeMaterial(item.getMaterialCode()) && item.getWeightKg() != null) {
                    current.merge(item.getMaterialCode(), item.getWeightKg().doubleValue(), Double::sum);
                }
            }
        }

        // ---- 目标重量：页面可改，缺省取炉次计划重量 ----
        Double target = request.getTargetWeight() == null ? null : request.getTargetWeight().doubleValue();
        String targetSource = "页面指定";
        if (target == null || target <= 0) {
            target = heat.getPlanWeight() == null ? null : heat.getPlanWeight().doubleValue();
            targetSource = "计划重量";
        }
        if (target == null || target <= 0) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 没有计划重量，请在推演参数里指定目标重量");
        }

        // ---- 炉体容量 ----
        Furnace furnace = heat.getFurnaceId() == null ? null
                : furnaceRepository.findById(heat.getFurnaceId()).orElse(null);
        Double capacity = furnace != null && furnace.getCapacityKg() != null
                ? furnace.getCapacityKg().doubleValue() : null;

        // ---- 单项上限：页面可逐项调，缺省系统硬上限，且不允许超过硬上限 ----
        Map<String, Double> caps = new LinkedHashMap<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            BigDecimal cap = request.getItemCaps() == null ? null : request.getItemCaps().get(code);
            double c = cap == null ? Dict.MAX_ITEM_WEIGHT.doubleValue() : cap.doubleValue();
            if (c <= 0) {
                throw new BizException("料项「" + Dict.chargeMaterialName(code) + "」上限必须大于 0 kg");
            }
            if (c > Dict.MAX_ITEM_WEIGHT.doubleValue()) {
                throw new BizException("料项「" + Dict.chargeMaterialName(code) + "」上限 "
                        + fmt(c) + " kg 超过单次配料硬上限 " + Dict.MAX_ITEM_WEIGHT + " kg");
            }
            caps.put(code, c);
        }

        boolean allowNew = request.getAllowNewMaterial() == null || request.getAllowNewMaterial();

        Ctx ctx = new Ctx();
        ctx.current = current;
        ctx.caps = caps;
        ctx.recipe = recipeOf(heat.getAlloy());
        ctx.allowed = new ArrayList<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            if (allowNew || current.get(code) > 0) {
                ctx.allowed.add(code);
            }
        }
        ctx.target = target;
        double rate = Dict.MAX_CHARGE_DEVIATION_RATE.doubleValue() / 100.0;
        ctx.lo = target * (1 - rate);
        ctx.hi = target * (1 + rate);
        ctx.capacity = capacity;
        ctx.hiEff = capacity == null ? ctx.hi : Math.min(ctx.hi, capacity);
        ctx.scrapMax = Dict.scrapRatioMax(heat.getAlloy()).doubleValue();
        ctx.allowNew = allowNew;

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("baseline", baseline(heat, furnace, baseCharge, ctx, targetSource));

        // ---- 冲突检测：容量 / 原料上限 / 回炉料比例与目标重量互相矛盾时，指出具体冲突，不出误导性方案 ----
        List<String> conflicts = conflicts(ctx);
        if (!conflicts.isEmpty()) {
            result.put("feasible", false);
            result.put("conflicts", conflicts);
            result.put("minCost", infeasibleScheme("MIN_COST", "最低补料成本", conflicts));
            result.put("minDeviation", infeasibleScheme("MIN_DEVIATION", "最小成分偏离", conflicts));
            result.put("recommendation", null);
            return result;
        }

        // 安全边距：建议重量保留两位小数后仍稳在窗口内
        double margin = Math.min(WINDOW_MARGIN_KG, (ctx.hiEff - ctx.lo) / 4);
        double loS = ctx.lo + margin;
        double hiS = ctx.hiEff - margin;

        Map<String, Double> costWeights = repair(rounded(solveMinCost(ctx, loS)), ctx);
        Map<String, Double> devWeights = repair(rounded(solveMinDeviation(ctx, loS, hiS)), ctx);

        Map<String, Object> minCost = scheme("MIN_COST", "最低补料成本", costWeights, ctx);
        Map<String, Object> minDeviation = scheme("MIN_DEVIATION", "最小成分偏离", devWeights, ctx);
        result.put("feasible", true);
        result.put("conflicts", List.of());
        result.put("minCost", minCost);
        result.put("minDeviation", minDeviation);
        result.put("recommendation", recommend(minCost, minDeviation));
        return result;
    }

    // ---------------- 冲突检测 ----------------

    private List<String> conflicts(Ctx ctx) {
        List<String> conflicts = new ArrayList<>();
        if (ctx.lo > ctx.hiEff + EPS) {
            conflicts.add("炉体容量 " + fmt(ctx.capacity) + " kg 装不下偏差窗口下限 " + fmt(ctx.lo)
                    + " kg（目标 " + fmt(ctx.target) + " kg ±" + Dict.MAX_CHARGE_DEVIATION_RATE
                    + "%）：炉体容量与目标重量矛盾，请下调目标重量或更换大容量炉体");
        }
        double nonScrapCap = ctx.allowed.stream().filter(c -> !SCRAP.equals(c))
                .mapToDouble(c -> ctx.caps.get(c)).sum();
        double scrapBoundLo = ctx.allowed.contains(SCRAP)
                ? Math.min(ctx.caps.get(SCRAP), ctx.scrapMax / 100.0 * ctx.lo) : 0;
        if (nonScrapCap + scrapBoundLo < ctx.lo - EPS) {
            if (ctx.allowed.isEmpty()) {
                conflicts.add("当前配料明细为空且未允许新料：没有任何可用料项，无法组成合法方案，请允许新料或先建立配料明细");
            } else {
                conflicts.add("允许料项（" + (ctx.allowNew ? "全部六个品种" : "仅现有 " + ctx.allowed.size()
                        + " 个品种，未允许新料") + "）在回炉料占比 ≤ " + fmt(ctx.scrapMax)
                        + "% 的约束下最多只能凑到 " + fmt(nonScrapCap + scrapBoundLo)
                        + " kg，低于偏差窗口下限 " + fmt(ctx.lo)
                        + " kg：原料上限与目标重量矛盾，请提高单项上限或允许新料");
            }
        }
        return conflicts;
    }

    // ---------------- 方案一：最低补料成本 ----------------

    /**
     * 先用足现有料（免费段，不超过单项上限）；现有料总重超出窗口下限就等比缩减到下限
     * （不动配比、零补料成本）；仍有缺口再按单价升序买（购买段成本凸，贪心即最优）。
     * 目标函数 min Σ 单价×max(0, 建议−现有)，减下的料退库不计收益。
     */
    private Map<String, Double> solveMinCost(Ctx ctx, double lo) {
        Map<String, Double> w = zeroWeights();
        double scrapBound = ctx.allowed.contains(SCRAP)
                ? Math.min(ctx.caps.get(SCRAP), ctx.scrapMax / 100.0 * lo) : 0;
        for (String code : ctx.allowed) {
            w.put(code, Math.min(ctx.current.get(code), ctx.caps.get(code)));
        }
        if (w.get(SCRAP) > scrapBound) {
            w.put(SCRAP, scrapBound);
        }
        double total = sum(w);
        if (total > lo) {
            double scale = lo / total;
            for (String code : ctx.allowed) {
                w.put(code, w.get(code) * scale);
            }
        } else if (total < lo - EPS) {
            List<String> byCost = new ArrayList<>(ctx.allowed);
            byCost.sort(Comparator.comparingDouble(c -> Dict.chargeMaterialCost(c).doubleValue()));
            double need = lo - total;
            for (String code : byCost) {
                if (need <= EPS) {
                    break;
                }
                double room = (SCRAP.equals(code)
                        ? Math.min(ctx.caps.get(code), scrapBound) : ctx.caps.get(code)) - w.get(code);
                if (room <= EPS) {
                    continue;
                }
                double take = Math.min(room, need);
                w.put(code, w.get(code) + take);
                need -= take;
            }
            // 可行性已在 conflicts 里校验过，到这里必然补足
        }
        return w;
    }

    // ---------------- 方案二：最小成分偏离 ----------------

    /**
     * 在偏差窗口 [loS, hiS] 内均匀扫描试算总重，每个总重下把各品种重量向标准配料比投影，
     * 取成分偏离最小者；并列时取离目标重量最近的。扫描 241 点 × 六品种投影，
     * 耗时与配料明细行数无关。
     */
    private Map<String, Double> solveMinDeviation(Ctx ctx, double loS, double hiS) {
        Map<String, Double> best = null;
        double bestDev = Double.MAX_VALUE;
        double bestT = 0;
        for (int i = 0; i <= SCAN_STEPS; i++) {
            double t = loS + (hiS - loS) * i / SCAN_STEPS;
            Map<String, Double> w = project(ctx, t);
            if (w == null) {
                continue;
            }
            double dev = deviation(w, t, ctx.recipe);
            if (best == null || dev < bestDev - 1e-9
                    || (Math.abs(dev - bestDev) <= 1e-9
                            && Math.abs(t - ctx.target) < Math.abs(bestT - ctx.target) - 1e-9)) {
                best = w;
                bestDev = dev;
                bestT = t;
            }
        }
        if (best == null) {
            // 理论上 conflicts 已拦住，防御性兜底
            throw new BizException("偏差窗口内找不到满足单项上限与回炉料比例的总重，无法形成合法方案");
        }
        return best;
    }

    /** 固定总重 t 下按配方分配：不允许的品种恒为 0，撞上限的钉住再分，配方外品种最后兜底 */
    private Map<String, Double> project(Ctx ctx, double t) {
        Map<String, Double> bound = new LinkedHashMap<>();
        double boundSum = 0;
        for (String code : ctx.allowed) {
            double b = SCRAP.equals(code)
                    ? Math.min(ctx.caps.get(code), ctx.scrapMax / 100.0 * t) : ctx.caps.get(code);
            bound.put(code, b);
            boundSum += b;
        }
        if (boundSum < t - EPS) {
            return null;    // 该总重下单项上限凑不够
        }
        Map<String, Double> w = zeroWeights();
        List<String> free = new ArrayList<>();
        for (String code : ctx.allowed) {
            if (ctx.recipe.get(code) > EPS) {
                free.add(code);
            }
        }
        Map<String, Double> share = new LinkedHashMap<>();
        if (free.isEmpty()) {
            // 配方对允许品种全为 0（极端）：允许品种均分兜底
            free.addAll(ctx.allowed);
            for (String code : free) {
                share.put(code, 1.0 / free.size());
            }
        } else {
            double pSum = free.stream().mapToDouble(c -> ctx.recipe.get(c)).sum();
            for (String code : free) {
                share.put(code, ctx.recipe.get(code) / pSum);
            }
        }
        Set<String> fixed = new LinkedHashSet<>();
        for (int iter = 0; iter <= ctx.allowed.size() + 1; iter++) {
            double fixedSum = fixed.stream().mapToDouble(w::get).sum();
            double remT = t - fixedSum;
            double pSum = free.stream().mapToDouble(c -> share.get(c)).sum();
            for (String code : free) {
                w.put(code, remT * share.get(code) / pSum);
            }
            List<String> violators = new ArrayList<>();
            for (String code : free) {
                if (w.get(code) > bound.get(code) + EPS) {
                    violators.add(code);
                }
            }
            if (violators.isEmpty()) {
                break;
            }
            for (String v : violators) {
                w.put(v, bound.get(v));
                fixed.add(v);
            }
            free.removeAll(violators);
            if (free.isEmpty()) {
                break;
            }
        }
        // 配方内品种撞满上界仍凑不够 t：溢出到允许但配方为 0 的品种（偏离等量增加，取谁都一样）
        double rest = t - sum(w);
        if (rest > EPS) {
            for (String code : ctx.allowed) {
                if (rest <= EPS) {
                    break;
                }
                double room = bound.get(code) - w.get(code);
                if (room > EPS) {
                    double take = Math.min(room, rest);
                    w.put(code, w.get(code) + take);
                    rest -= take;
                }
            }
        }
        return rest > 0.01 ? null : w;
    }

    // ---------------- 舍入修复 ----------------

    /**
     * 建议重量保留两位小数后总重可能贴边，逐项微调回 [lo, hiEff]、回炉料占比不破上限。
     * 正常窗口（数十 kg）下不会触发，只在窗口被炉体容量压到极窄时兜底。
     */
    private Map<String, Double> repair(Map<String, Double> w, Ctx ctx) {
        for (int iter = 0; iter < 8; iter++) {
            double total = sum(w);
            if (total < ctx.lo - EPS) {
                addDelta(w, ctx.lo - total, ctx);
                continue;
            }
            if (total > ctx.hiEff + EPS) {
                addDelta(w, ctx.hiEff - total, ctx);
                continue;
            }
            if (ctx.allowed.contains(SCRAP)) {
                double bound = Math.min(ctx.caps.get(SCRAP), ctx.scrapMax / 100.0 * total);
                if (w.get(SCRAP) > bound + EPS) {
                    w.put(SCRAP, r2(bound));
                    continue;
                }
            }
            break;
        }
        return w;
    }

    private void addDelta(Map<String, Double> w, double delta, Ctx ctx) {
        if (delta > 0) {
            String pick = null;
            double room = 0;
            for (String code : ctx.allowed) {
                double r = ctx.caps.get(code) - w.get(code);
                if (r > room) {
                    room = r;
                    pick = code;
                }
            }
            if (pick != null) {
                w.put(pick, r2(w.get(pick) + Math.min(delta, room)));
            }
        } else {
            String pick = null;
            double most = 0;
            for (String code : ctx.allowed) {
                if (w.get(code) > most) {
                    most = w.get(code);
                    pick = code;
                }
            }
            if (pick != null) {
                w.put(pick, r2(Math.max(0, w.get(pick) + delta)));
            }
        }
    }

    // ---------------- 方案指标与建议明细 ----------------

    private Map<String, Object> scheme(String code, String name, Map<String, Double> w, Ctx ctx) {
        double total = sum(w);
        double cost = 0;
        List<Map<String, Object>> adjustments = new ArrayList<>();
        for (String m : Dict.CHARGE_MATERIALS) {
            double cur = ctx.current.get(m);
            double delta = r2(w.get(m) - cur);
            cost += Dict.chargeMaterialCost(m).doubleValue() * Math.max(0, delta);
            if (Math.abs(delta) > 0.005) {
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("materialCode", m);
                a.put("materialName", Dict.chargeMaterialName(m));
                a.put("color", Dict.chargeMaterialColor(m));
                a.put("currentKg", r2(cur));
                a.put("suggestKg", w.get(m));
                a.put("deltaKg", delta);
                a.put("isNew", cur <= 0.005 && w.get(m) > 0.005);
                a.put("costDelta", r2(Dict.chargeMaterialCost(m).doubleValue() * Math.max(0, delta)));
                adjustments.add(a);
            }
        }
        double dev = deviation(w, total, ctx.recipe);
        double scrapRatio = total > 0 ? w.get(SCRAP) / total * 100 : 0;
        double devRate = (total - ctx.target) / ctx.target * 100;

        List<String> notes = new ArrayList<>();
        if (ctx.capacity != null && total >= ctx.capacity - 0.05) {
            notes.add("试算总重已顶到炉体容量 " + fmt(ctx.capacity) + " kg，再加料受炉体限制");
        } else if (total >= ctx.hi - 0.05) {
            notes.add("试算总重已贴到偏差窗口上限（+" + Dict.MAX_CHARGE_DEVIATION_RATE + "%）");
        }
        if (total <= ctx.lo + 0.1) {
            notes.add("试算总重已压到偏差窗口下限（−" + Dict.MAX_CHARGE_DEVIATION_RATE + "%），继续减料将越界");
        }
        if (w.get(SCRAP) > 0.005 && scrapRatio >= ctx.scrapMax - 0.05) {
            notes.add("回炉料占比触及材质工艺上限 " + fmt(ctx.scrapMax) + "%，继续多用回炉料会累积微量元素");
        }
        for (String m : ctx.allowed) {
            if (w.get(m) > 0.005 && w.get(m) >= ctx.caps.get(m) - 0.005) {
                notes.add("「" + Dict.chargeMaterialName(m) + "」已用足单项上限 " + fmt(ctx.caps.get(m)) + " kg");
            }
        }
        if (!ctx.allowNew) {
            notes.add("未允许新料：仅在现有 " + ctx.allowed.size() + " 个品种内调整");
        }
        if ("MIN_DEVIATION".equals(code) && dev > 0.01) {
            notes.add("受单项上限/回炉料比例约束，配比无法完全贴合标准配方，残余偏离 " + fmt(dev) + " 点");
        }
        if (adjustments.isEmpty()) {
            notes.add("当前配料已满足全部约束，无需调整");
        }

        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("code", code);
        scheme.put("name", name);
        scheme.put("feasible", true);
        scheme.put("infeasibleReasons", List.of());
        scheme.put("adjustments", adjustments);
        scheme.put("totalWeight", r2(total));
        scheme.put("deviationRate", r2(devRate));
        scheme.put("scrapRatio", r2(scrapRatio));
        scheme.put("scrapLimit", r2(ctx.scrapMax));
        scheme.put("supplementCost", r2(cost));
        scheme.put("compositionDeviation", r2(dev));
        scheme.put("notes", notes);
        return scheme;
    }

    /** 约束互相矛盾时的方案占位：只列不可行原因，不给任何误导性数字 */
    private Map<String, Object> infeasibleScheme(String code, String name, List<String> conflicts) {
        Map<String, Object> scheme = new LinkedHashMap<>();
        scheme.put("code", code);
        scheme.put("name", name);
        scheme.put("feasible", false);
        scheme.put("infeasibleReasons", conflicts);
        scheme.put("adjustments", List.of());
        scheme.put("totalWeight", null);
        scheme.put("deviationRate", null);
        scheme.put("scrapRatio", null);
        scheme.put("scrapLimit", null);
        scheme.put("supplementCost", null);
        scheme.put("compositionDeviation", null);
        scheme.put("notes", List.of());
        return scheme;
    }

    // ---------------- 推荐取舍 ----------------

    private Map<String, Object> recommend(Map<String, Object> minCost, Map<String, Object> minDev) {
        double costA = (double) minCost.get("supplementCost");
        double devA = (double) minCost.get("compositionDeviation");
        double costB = (double) minDev.get("supplementCost");
        double devB = (double) minDev.get("compositionDeviation");
        double dCost = costB - costA;   // 偏离方案比成本方案多花的补料成本
        double dDev = devA - devB;      // 成本方案比偏离方案多出的成分偏离

        Map<String, Object> rec = new LinkedHashMap<>();
        if (dCost <= EQUIV_TOLERANCE && dDev <= EQUIV_TOLERANCE) {
            rec.put("choice", "NONE");
            rec.put("choiceName", "无偏好");
            rec.put("reason", "两套方案的关键指标基本一致（补料成本差 " + fmt(Math.abs(dCost))
                    + " 元、成分偏离差 " + fmt(Math.abs(dDev)) + " 点），任选其一即可。");
            rec.put("sacrifice", "无：两套结果等价，不存在取舍。");
            return rec;
        }
        if (dDev <= RECO_DEV_TOLERANCE) {
            rec.put("choice", "MIN_COST");
            rec.put("choiceName", "最低补料成本");
            rec.put("reason", "两方案成分偏离仅差 " + fmt(dDev) + " 点（≤" + fmt(RECO_DEV_TOLERANCE)
                    + "），优先利用已有料项、少花 " + fmt(dCost) + " 元补料成本更划算。");
            rec.put("sacrifice", "未选「最小成分偏离」：为不足 " + fmt(RECO_DEV_TOLERANCE)
                    + " 点的偏离改善要多付 " + fmt(dCost) + " 元补料费，牺牲了成本优势，投入产出不成比例。");
            return rec;
        }
        if (dCost <= Math.max(RECO_COST_TOLERANCE, RECO_COST_TOLERANCE_RATE * costA)) {
            rec.put("choice", "MIN_DEVIATION");
            rec.put("choiceName", "最小成分偏离");
            rec.put("reason", "成本方案的成分偏离多出 " + fmt(dDev) + " 点（>" + fmt(RECO_DEV_TOLERANCE)
                    + "），而偏离方案只需多花 " + fmt(dCost)
                    + " 元补料费；成分偏离过大带来的炉前二次调整与材质风险高于这笔差价。");
            rec.put("sacrifice", "未选「最低补料成本」：虽能省下 " + fmt(dCost) + " 元补料费，但要多承担 "
                    + fmt(dDev) + " 点成分偏离，后续炉前调成分的时间与判废风险随之上升。");
            return rec;
        }
        rec.put("choice", "MIN_COST");
        rec.put("choiceName", "最低补料成本");
        rec.put("reason", "偏离方案虽能再压低 " + fmt(dDev) + " 点成分偏离，但补料成本要多花 " + fmt(dCost)
                + " 元（超过成本方案 10% 与 " + fmt(RECO_COST_TOLERANCE)
                + " 元定额），代价过高；当前偏离差仍可靠炉前微调管控。");
        rec.put("sacrifice", "未选「最小成分偏离」：成分更稳、炉前调整量更小，但为此多付 " + fmt(dCost)
                + " 元补料成本，牺牲了明显的成本空间。");
        return rec;
    }

    // ---------------- 基准回显（两套方案共享的输入版本） ----------------

    private Map<String, Object> baseline(Heat heat, Furnace furnace, Charge baseCharge,
                                         Ctx ctx, String targetSource) {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("heatId", heat.getId());
        base.put("heatNo", heat.getHeatNo());
        base.put("stage", heat.getStage());
        base.put("stageName", heat.getStageName());
        base.put("alloy", heat.getAlloy());
        base.put("alloyName", heat.getAlloyName());
        base.put("furnaceNo", furnace == null ? null : furnace.getFurnaceNo());
        base.put("furnaceName", furnace == null ? null : furnace.getFurnaceName());
        base.put("capacityKg", ctx.capacity == null ? null : r2(ctx.capacity));
        base.put("chargeId", baseCharge == null ? null : baseCharge.getId());
        base.put("chargeNo", baseCharge == null ? null : baseCharge.getChargeNo());
        base.put("targetWeight", r2(ctx.target));
        base.put("targetSource", targetSource);
        base.put("deviationLimitRate", Dict.MAX_CHARGE_DEVIATION_RATE);
        base.put("scrapRatioMax", r2(ctx.scrapMax));
        base.put("allowNewMaterial", ctx.allowNew);
        base.put("maxItemWeight", Dict.MAX_ITEM_WEIGHT);
        double currentTotal = sum(ctx.current);
        base.put("currentTotalKg", r2(currentTotal));
        base.put("currentScrapRatio", currentTotal > 0 ? r2(ctx.current.get(SCRAP) / currentTotal * 100) : null);
        base.put("currentDeviationRate", r2((currentTotal - ctx.target) / ctx.target * 100));
        List<Map<String, Object>> items = new ArrayList<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("materialCode", code);
            item.put("materialName", Dict.chargeMaterialName(code));
            item.put("color", Dict.chargeMaterialColor(code));
            item.put("currentKg", r2(ctx.current.get(code)));
            item.put("capKg", r2(ctx.caps.get(code)));
            item.put("recipeRatio", r2(ctx.recipe.get(code) * 100));
            item.put("costPerKg", Dict.chargeMaterialCost(code));
            items.add(item);
        }
        base.put("items", items);
        return base;
    }

    // ---------------- 数值工具 ----------------

    private static Map<String, Double> zeroWeights() {
        Map<String, Double> w = new LinkedHashMap<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            w.put(code, 0.0);
        }
        return w;
    }

    private static Map<String, Double> recipeOf(String alloy) {
        Map<String, Double> recipe = new LinkedHashMap<>();
        Map<String, BigDecimal> std = Dict.chargeRecipe(alloy);
        for (String code : Dict.CHARGE_MATERIALS) {
            recipe.put(code, std.getOrDefault(code, BigDecimal.ZERO).doubleValue() / 100.0);
        }
        return recipe;
    }

    /** 成分偏离（百分点）：Σ|实际占比 − 标准配比|，六个品种全计 */
    private double deviation(Map<String, Double> w, double total, Map<String, Double> recipe) {
        double dev = 0;
        for (String code : Dict.CHARGE_MATERIALS) {
            dev += Math.abs(w.get(code) / total - recipe.get(code));
        }
        return dev * 100;
    }

    private static Map<String, Double> rounded(Map<String, Double> w) {
        Map<String, Double> out = new LinkedHashMap<>();
        for (Map.Entry<String, Double> e : w.entrySet()) {
            out.put(e.getKey(), r2(e.getValue()));
        }
        return out;
    }

    private static double sum(Map<String, Double> w) {
        return w.values().stream().mapToDouble(Double::doubleValue).sum();
    }

    private static double r2(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** 消息里的 kg / 元 / % 数字：最多两位小数，去掉多余的 0 */
    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
