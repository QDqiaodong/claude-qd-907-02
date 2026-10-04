package com.casting.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casting.shop.common.BizException;
import com.casting.shop.domain.Defect;
import com.casting.shop.domain.Inspection;
import com.casting.shop.domain.ReworkCorrection;
import com.casting.shop.domain.ReworkEvent;
import com.casting.shop.domain.ReworkOrder;
import com.casting.shop.domain.ReworkRound;
import com.casting.shop.dto.CorrectionRequest;
import com.casting.shop.dto.DefectRequest;
import com.casting.shop.dto.ReinspectRequest;
import com.casting.shop.dto.ReworkCompleteRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.DefectRepository;
import com.casting.shop.repository.InspectionRepository;
import com.casting.shop.repository.ReworkOrderRepository;
import com.casting.shop.repository.ReworkRoundRepository;
import com.casting.shop.service.CastingService;
import com.casting.shop.service.InspectionService;
import com.casting.shop.service.ReworkService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 返修处置与复检闭环冒烟（H2 MySQL 兼容模式）：
 *
 * <p>注：Casting 实体上的 {@code @SQLSelect} 是 MySQL 口径的 join 投影（c.* + join 别名），
 * H2 不允许结果集出现重复列名，因此测试里铸件一律用 {@link JdbcTemplate} 直接造数、查状态，
 * 业务代码中「行锁 lockRowById + findById + save」走的是纯 casting 表 SQL，不经过该自定义加载器。</p>
 */
@SpringBootTest
class ReworkLoopIT {

    @Autowired private InspectionService inspectionService;
    @Autowired private ReworkService reworkService;
    @Autowired private CastingService castingService;
    @Autowired private CastingRepository castingRepository;
    @Autowired private ReworkOrderRepository orderRepository;
    @Autowired private ReworkRoundRepository roundRepository;
    @Autowired private InspectionRepository inspectionRepository;
    @Autowired private DefectRepository defectRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        roundRepository.deleteAll();
        orderRepository.deleteAll();
        defectRepository.deleteAll();
        inspectionRepository.deleteAll();
        // 直接 SQL 清铸件，避免 JpaRepository.deleteAll 经 @SQLSelect 加载实体（H2 不支持其 join 投影）
        jdbc.update("delete from casting");
    }

    /** 直接走 SQL 造铸件，避免触发 @SQLSelect 自定义加载器（H2 不支持其重复别名投影） */
    private Long newCasting(String no, String status) {
        jdbc.update("insert into casting (casting_no, casting_name, material, theory_weight, status, created_at) "
                + "values (?, ?, 'STAINLESS', 5.50, ?, ?)",
                no, "测试铸件 " + no, status, LocalDateTime.now());
        return jdbc.queryForObject("select id from casting where casting_no = ?", Long.class, no);
    }

    private String statusOf(Long castingId) {
        return castingRepository.findStatusById(castingId);
    }

    private DefectRequest defect(String type) {
        DefectRequest d = new DefectRequest();
        d.setDefectType(type);
        d.setSeverity("MAJOR");
        d.setPosition("结合面");
        d.setSizeMm(BigDecimal.valueOf(2.5));
        d.setQuantity(2);
        d.setRemark("测试缺陷");
        return d;
    }

    private Inspection firstRework(Long castingId, String no, String inspectNo) {
        com.casting.shop.dto.InspectionRequest req = new com.casting.shop.dto.InspectionRequest();
        req.setInspectNo(inspectNo);
        req.setCastingId(castingId);
        req.setVerdict("REWORK");
        req.setInspector("钱检验");
        req.setDefects(List.of(defect("POROSITY")));
        return inspectionService.create(req);
    }

    private ReworkCompleteRequest complete(String method, String who, String note, String operator) {
        ReworkCompleteRequest r = new ReworkCompleteRequest();
        r.setMethod(method);
        r.setResponsiblePerson(who);
        r.setNote(note);
        r.setOperator(operator);
        return r;
    }

    private ReinspectRequest reinspect(String verdict, String inspector) {
        ReinspectRequest r = new ReinspectRequest();
        r.setVerdict(verdict);
        r.setInspector(inspector);
        r.setRemark("复检备注 " + verdict);
        if (!"PASS".equals(verdict)) {
            r.setDefects(List.of(defect("SAND_HOLE")));
        }
        return r;
    }

    // ---------- 1. 首次判返修自动建独立队列并保留快照 ----------

    @Test
    void firstReworkOpensQueueAndSnapshot() {
        Long cid = newCasting("C-T-0001", "INSPECTING");
        Inspection first = firstRework(cid, "C-T-0001", "I-T-0001");

        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();
        assertEquals("REWORKING", order.getStage());
        assertEquals(1, order.getCurrentRound());
        assertEquals(first.getId(), order.getSnapshotInspectId());

        ReworkRound r1 = roundRepository.findByReworkIdAndRoundNo(order.getId(), 1).orElseThrow();
        assertEquals("PENDING", r1.getRoundStatus());
        assertEquals(first.getId(), r1.getSourceInspectId());
        assertNull(r1.getReinspectId());
        assertEquals("REWORK", statusOf(cid));

        // 缺陷快照仍挂在首次检验单下，按原方式可查
        Map<String, Object> detail = inspectionService.detail(first.getId());
        @SuppressWarnings("unchecked")
        List<Defect> defects = (List<Defect>) detail.get("defects");
        assertEquals(1, defects.size());
        assertEquals("POROSITY", defects.get(0).getDefectType());

        // 队列看板能按返修中阶段定位到积压
        Map<String, Object> board = reworkService.board("REWORKING");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cards = (List<Map<String, Object>>) board.get("cards");
        assertTrue(cards.stream().anyMatch(card -> ((ReworkOrder) card.get("order")).getId().equals(order.getId())));
    }

    // ---------- 2. 完成 → 撤回 → 再完成（复检创建前允许修正误填） ----------

    @Test
    void withdrawBeforeReinspectClearsRoundThenRecomplete() {
        Long cid = newCasting("C-T-0002", "INSPECTING");
        firstRework(cid, "C-T-0002", "I-T-0002");
        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();

        reworkService.complete(order.getId(), complete("WELD", "周大伟(误填)", "第一次说明", "范调度"));
        ReworkRound done = roundRepository.findByReworkIdAndRoundNo(order.getId(), 1).orElseThrow();
        assertEquals("COMPLETED", done.getRoundStatus());
        assertEquals("REINSPECTING", orderRepository.findById(order.getId()).orElseThrow().getStage());
        assertEquals("REINSPECT", statusOf(cid));

        // 撤回：处置字段清空、状态回待返修，事件留痕
        reworkService.withdraw(order.getId(), "范调度");
        ReworkRound back = roundRepository.findByReworkIdAndRoundNo(order.getId(), 1).orElseThrow();
        assertEquals("PENDING", back.getRoundStatus());
        assertNull(back.getReworkMethod());
        assertNull(back.getResponsiblePerson());
        assertNull(back.getCompletedAt());
        assertEquals("REWORK", statusOf(cid));

        // 重新登记
        reworkService.complete(order.getId(), complete("GRIND", "吴大伟", "更正后的说明", "范调度"));
        ReworkRound redone = roundRepository.findByReworkIdAndRoundNo(order.getId(), 1).orElseThrow();
        assertEquals("GRIND", redone.getReworkMethod());
        assertEquals("吴大伟", redone.getResponsiblePerson());
    }

    // ---------- 3. 再返修：旧轮保留 + 下一轮待办 + 合格入库闭环 ----------

    @Test
    void reworkAgainKeepsOldRoundsThenPassStores() {
        Long cid = newCasting("C-T-0003", "INSPECTING");
        Inspection first = firstRework(cid, "C-T-0003", "I-T-0003");
        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();

        // 第 1 轮完成 → 复检再返修
        reworkService.complete(order.getId(), complete("WELD", "周大伟", "一轮补焊", "范调度"));
        Map<String, Object> r1 = reworkService.reinspect(order.getId(), reinspect("REWORK", "钱检验"));
        assertNotNull(r1.get("nextRound"));

        order = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(2, order.getCurrentRound());
        assertEquals("REWORKING", order.getStage());
        assertEquals("REWORK", statusOf(cid));

        // 旧轮完整保留：处置 + 复检结论 + 复检单
        ReworkRound old1 = roundRepository.findByReworkIdAndRoundNo(order.getId(), 1).orElseThrow();
        assertEquals("REWORK", old1.getRoundStatus());
        assertNotNull(old1.getReinspectId());
        assertEquals("WELD", old1.getReworkMethod());

        // 第 2 轮快照必须是上一轮复检单（而非首次检验单）
        ReworkRound r2 = roundRepository.findByReworkIdAndRoundNo(order.getId(), 2).orElseThrow();
        assertEquals(old1.getReinspectId(), r2.getSourceInspectId());
        assertFalse(first.getId().equals(r2.getSourceInspectId()));

        // 复检单作为旧检验记录仍在检验列表里
        assertTrue(inspectionRepository.findAllByOrderByIdDesc().stream()
                .anyMatch(i -> i.getId().equals(old1.getReinspectId())));

        // 第 2 轮完成 → 复检合格入库
        reworkService.complete(order.getId(), complete("IMPREGNATION", "郑海峰", "二次浸渗", "范调度"));
        Map<String, Object> r2pass = reworkService.reinspect(order.getId(), reinspect("PASS", "钱检验"));
        assertEquals("STORED", r2pass.get("closed"));
        assertEquals("STORED", orderRepository.findById(order.getId()).orElseThrow().getStage());
        assertEquals("STORED", statusOf(cid));
        assertNotNull(orderRepository.findById(order.getId()).orElseThrow().getClosedAt());

        // 闭环后任何动作都被状态挡回
        final Long closedOrderId = order.getId();
        assertThrows(BizException.class,
                () -> reworkService.complete(closedOrderId, complete("WELD", "x", "x", "x")));
    }

    // ---------- 4. 复检判废永久终止 ----------

    @Test
    void scrapTerminatesForever() {
        Long cid = newCasting("C-T-0004", "INSPECTING");
        firstRework(cid, "C-T-0004", "I-T-0004");
        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();
        reworkService.complete(order.getId(), complete("WELD", "周大伟", "补焊尝试", "范调度"));
        Map<String, Object> result = reworkService.reinspect(order.getId(), reinspect("SCRAP", "钱检验"));
        assertEquals("SCRAPPED", result.get("closed"));
        assertEquals("SCRAPPED", statusOf(cid));
        assertThrows(BizException.class,
                () -> reworkService.reinspect(order.getId(), reinspect("PASS", "钱检验")));
        assertThrows(BizException.class,
                () -> reworkService.complete(order.getId(), complete("WELD", "x", "x", "x")));
    }

    // ---------- 5. 复检创建后不能撤回，只能追加更正（历史字段不变） ----------

    @Test
    void correctionOnlyAppendAfterReinspect() {
        Long cid = newCasting("C-T-0005", "INSPECTING");
        firstRework(cid, "C-T-0005", "I-T-0005");
        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();
        reworkService.complete(order.getId(), complete("WELD", "周大伟", "补焊", "范调度"));
        reworkService.reinspect(order.getId(), reinspect("PASS", "钱检验"));

        // 已建复检单，撤回被拒
        assertThrows(BizException.class, () -> reworkService.withdraw(order.getId(), "范调度"));

        // 追加更正：原责任人不变
        CorrectionRequest cr = new CorrectionRequest();
        cr.setTarget("REWORK");
        cr.setContent("责任人应为吴大伟");
        cr.setOperator("范调度");
        reworkService.correct(order.getId(), cr);

        ReworkRound frozen = roundRepository.findByReworkIdAndRoundNo(order.getId(), 1).orElseThrow();
        assertEquals("周大伟", frozen.getResponsiblePerson());   // 原值原样保留
        Map<String, Object> detail = reworkService.detail(order.getId());
        @SuppressWarnings("unchecked")
        List<ReworkCorrection> corrections = (List<ReworkCorrection>) detail.get("corrections");
        assertEquals(1, corrections.size());
        assertEquals("责任人应为吴大伟", corrections.get(0).getContent());

        // 更正对象非法
        CorrectionRequest bad = new CorrectionRequest();
        bad.setTarget("OTHER");
        bad.setContent("x");
        assertThrows(BizException.class, () -> reworkService.correct(order.getId(), bad));
    }

    // ---------- 6. 两人并发登记完成：同一轮只成功一次，失败信息带先操作者 ----------

    @Test
    void concurrentCompleteOnlyOneWins() throws Exception {
        Long cid = newCasting("C-T-0006", "INSPECTING");
        firstRework(cid, "C-T-0006", "I-T-0006");
        final Long orderId = orderRepository.findByCastingId(cid).orElseThrow().getId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    reworkService.complete(orderId,
                            complete("WELD", "责任人" + idx, "说明" + idx, "操作者" + idx));
                    ok.incrementAndGet();
                } catch (BizException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "同一轮完成登记只能成功一次");
        assertEquals(threads - 1, conflict.get());

        // 铸件只转一次待复检，不会产生两张待办
        ReworkRound r1 = roundRepository.findByReworkIdAndRoundNo(orderId, 1).orElseThrow();
        assertEquals("COMPLETED", r1.getRoundStatus());
        assertEquals("REINSPECT", statusOf(cid));
    }

    // ---------- 7. 两人并发发起复检：只成功一次，不产生两张复检单/两条下一轮待办 ----------

    @Test
    void concurrentReinspectOnlyOneWins() throws Exception {
        Long cid = newCasting("C-T-0007", "INSPECTING");
        firstRework(cid, "C-T-0007", "I-T-0007");
        final Long orderId = orderRepository.findByCastingId(cid).orElseThrow().getId();
        reworkService.complete(orderId, complete("WELD", "周大伟", "补焊", "范调度"));

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    reworkService.reinspect(orderId, reinspect("REWORK", "钱检验"));
                    ok.incrementAndGet();
                } catch (BizException e) {
                    conflict.incrementAndGet();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertEquals(1, ok.get());
        assertEquals(threads - 1, conflict.get());
        assertEquals(2, roundRepository.findByReworkIdOrderByRoundNoAsc(orderId).size(),
                "不能产生两张下一轮待办");
        long reinspectCount = inspectionRepository.findByCastingIdOrderByIdDesc(cid).stream()
                .filter(i -> "REWORK".equals(i.getVerdict()) && i.getInspectNo().startsWith("IR-"))
                .count();
        assertEquals(1, reinspectCount, "只能产生一张复检单");
    }

    // ---------- 8. 旁路全部堵死 ----------

    @Test
    void bypassesBlocked() {
        Long cid = newCasting("C-T-0008", "INSPECTING");
        Inspection first = firstRework(cid, "C-T-0008", "I-T-0008");
        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();

        // a. 不能删除作为快照的首次检验单
        BizException e1 = assertThrows(BizException.class, () -> inspectionService.delete(first.getId()));
        assertTrue(e1.getMessage().contains("缺陷快照"));
        assertTrue(inspectionRepository.findById(first.getId()).isPresent());

        // b. 返修中铸件不能手动流转状态（进/出支线都不行）
        assertThrows(BizException.class, () -> castingService.transition(cid, "STORED"));
        assertThrows(BizException.class, () -> castingService.transition(cid, "REWORK"));

        // c. 编辑接口不能直接写状态
        com.casting.shop.dto.CastingRequest upd = new com.casting.shop.dto.CastingRequest();
        upd.setStatus("STORED");
        assertThrows(BizException.class, () -> castingService.update(cid, upd));

        // d. 返修中的铸件不能删除
        assertThrows(BizException.class, () -> castingService.delete(cid));

        // e. 首次检验入口不能再给已在返修闭环的铸件开单（状态不是 INSPECTING）
        com.casting.shop.dto.InspectionRequest again = new com.casting.shop.dto.InspectionRequest();
        again.setInspectNo("I-T-0008-X");
        again.setCastingId(cid);
        again.setVerdict("PASS");
        assertThrows(BizException.class, () -> inspectionService.create(again));
        assertEquals("REWORK", statusOf(cid));

        // f. 未完成处置不能发起复检；第 2 轮判报废无缺陷被拒
        reworkService.complete(order.getId(), complete("WELD", "周大伟", "补焊", "范调度"));
        reworkService.reinspect(order.getId(), reinspect("REWORK", "钱检验"));
        final Long oid = order.getId();
        assertThrows(BizException.class,
                () -> reworkService.reinspect(oid, reinspect("PASS", "钱检验")));
        reworkService.complete(oid, complete("WELD", "周大伟", "二轮补焊", "范调度"));
        ReinspectRequest noDefect = new ReinspectRequest();
        noDefect.setVerdict("SCRAP");
        assertThrows(BizException.class, () -> reworkService.reinspect(oid, noDefect));
    }

    // ---------- 9. 详情时间线按轮次/事件可追溯 ----------

    @Test
    @SuppressWarnings("unchecked")
    void detailTimelineAssemblesSnapshotRoundsEvents() {
        Long cid = newCasting("C-T-0009", "INSPECTING");
        Inspection first = firstRework(cid, "C-T-0009", "I-T-0009");
        ReworkOrder order = orderRepository.findByCastingId(cid).orElseThrow();
        reworkService.complete(order.getId(), complete("GRIND", "吴大伟", "打磨", "范调度"));
        reworkService.reinspect(order.getId(), reinspect("SCRAP", "钱检验"));

        Map<String, Object> detail = reworkService.detail(order.getId());
        assertNotNull(detail.get("snapshot"));
        List<Map<String, Object>> rounds = (List<Map<String, Object>>) detail.get("rounds");
        assertEquals(1, rounds.size());
        assertNotNull(rounds.get(0).get("reinspection"));
        List<ReworkEvent> events = (List<ReworkEvent>) detail.get("events");
        assertTrue(events.stream().anyMatch(ev -> "CREATED".equals(ev.getEventType())));
        assertTrue(events.stream().anyMatch(ev -> "CLOSED_SCRAP".equals(ev.getEventType())));
        Map<String, Object> snapshot = (Map<String, Object>) detail.get("snapshot");
        assertEquals(first.getId(), ((Inspection) snapshot.get("inspection")).getId());
    }
}
