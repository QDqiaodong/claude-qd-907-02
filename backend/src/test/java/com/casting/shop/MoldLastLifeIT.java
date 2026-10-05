package com.casting.shop;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casting.shop.common.BizException;
import com.casting.shop.dto.CastingRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.MoldRepository;
import com.casting.shop.service.CastingService;
import com.casting.shop.service.MoldService;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 模具末次寿命与铸件建卡原子一致性验收（H2 MySQL 兼容模式）。
 *
 * <p>覆盖：
 * <ol>
 *   <li>剩余一次寿命的并发竞争：8 笔同时建卡只成功 1 张，其余明确业务失败；</li>
 *   <li>普通成功：寿命恰好扣一次、模具状态正确、铸件卡有效；</li>
 *   <li>校验失败：炉次未出炉 / 材质与炉次不一致 / 模具适用材质不符 / 型腔越界 / 报废与超寿命模具，
 *       模具次数与铸件卡都不变化；</li>
 *   <li>异常回滚：原子占用成功后铸件卡持久化抛异常，寿命占用随事务一起回退，无半成品；</li>
 *   <li>过期页面与重复请求：失效模具与重复铸件编号再提交一律被服务端挡回。</li>
 * </ol>
 *
 * <p>注：与 {@link ReworkLoopIT} 相同，铸件一律用 {@link JdbcTemplate} 直接造数 / 查数，
 * 绕开 Casting 实体上 MySQL 口径的 {@code @SQLSelect} join 投影（H2 不允许重复列名）。
 * 业务路径里的 mold 是普通实体加载，casting 的写入是纯表 insert，不受影响。</p>
 */
@SpringBootTest
class MoldLastLifeIT {

    @Autowired private CastingService castingService;
    @Autowired private MoldService moldService;
    @Autowired private MoldRepository moldRepository;
    @Autowired private CastingRepository castingRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        // casting 直接 SQL 清，避免 JpaRepository 经 @SQLSelect 加载实体（H2 不支持其 join 投影）
        jdbc.update("delete from casting");
        jdbc.update("delete from mold");
        jdbc.update("delete from heat");
    }

    // ---------------- 造数 / 查询辅助 ----------------

    /** 已出炉炉次（登记了出炉重量） */
    private Long newHeat(String no, String alloy, boolean tapped) {
        jdbc.update("insert into heat (heat_no, alloy, plan_weight, tap_weight, stage, created_at) "
                + "values (?, ?, 100.00, ?, ?, ?)",
                no, alloy, tapped ? new BigDecimal("96.50") : null,
                tapped ? "TAPPING" : "MELTING", LocalDateTime.now());
        return jdbc.queryForObject("select id from heat where heat_no = ?", Long.class, no);
    }

    private Long newMold(String no, int life, int used, String fitMaterial, String status) {
        jdbc.update("insert into mold (mold_no, mold_name, mold_type, cavity_count, life_count, used_count, "
                + "fit_material, status, created_at) values (?, '测试模具', 'WAX', 4, ?, ?, ?, ?, ?)",
                no, life, used, fitMaterial, status, LocalDateTime.now());
        return jdbc.queryForObject("select id from mold where mold_no = ?", Long.class, no);
    }

    private Map<String, Object> moldRow(Long id) {
        return jdbc.queryForMap("select used_count, life_count, status from mold where id = ?", id);
    }

    private long castingCountOfMold(Long moldId) {
        Long c = jdbc.queryForObject("select count(*) from casting where mold_id = ?", Long.class, moldId);
        return c == null ? 0 : c;
    }

    private long castingCountByNo(String no) {
        Long c = jdbc.queryForObject("select count(*) from casting where casting_no = ?", Long.class, no);
        return c == null ? 0 : c;
    }

    private CastingRequest request(String no, String material, Long heatId, Long moldId, String cavityNo) {
        CastingRequest r = new CastingRequest();
        r.setCastingNo(no);
        r.setCastingName("测试铸件 " + no);
        r.setMaterial(material);
        r.setHeatId(heatId);
        r.setMoldId(moldId);
        r.setCavityNo(cavityNo);
        r.setTheoryWeight(new BigDecimal("12.40"));
        r.setOperator("陈志远");
        return r;
    }

    // ---------------- 1. 末次寿命并发竞争：只剩一次，8 笔同时建卡 ----------------

    @Test
    void concurrentCreateOnLastLifeOnlyOneCardWins() throws Exception {
        Long heatId = newHeat("H-R-0001", "STAINLESS", true);
        final Long moldId = newMold("M-R-0001", 10, 9, "STAINLESS", "AVAILABLE");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger bizFail = new AtomicInteger();
        AtomicInteger otherFail = new AtomicInteger();
        List<String> messages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    // 型腔号统一用 1：同一型腔在不同浇注批次可反复产出铸件（种子数据即如此），
                    // 8 笔请求除铸件编号外完全同质，保证竞争只发生在末次寿命上，不被型腔校验干扰
                    castingService.create(request("C-R-100" + idx, "STAINLESS", heatId, moldId, "1"));
                    ok.incrementAndGet();
                } catch (BizException e) {
                    bizFail.incrementAndGet();
                    messages.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    otherFail.incrementAndGet();
                } catch (RuntimeException e) {
                    otherFail.incrementAndGet();
                    messages.add(e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        // 最终裁决：恰好一张卡，其余全部拿到明确业务失败（而不是 500 或静默成功）
        assertEquals(1, ok.get(), "末次寿命只能被一张铸件卡占用");
        assertEquals(threads - 1, bizFail.get(), "竞争失败方必须收到明确业务失败");
        assertEquals(0, otherFail.get(), "不应出现业务异常之外的失败：" + messages);
        assertTrue(messages.stream().allMatch(m -> m.contains("刷新")),
                "失败提示应引导刷新查看真实结果：" + messages);

        // 账实一致：寿命只扣一次、恰好到顶、模具立即报废不可再排产
        Map<String, Object> row = moldRow(moldId);
        assertEquals(10, ((Number) row.get("used_count")).intValue(), "已用次数恰好等于寿命上限");
        assertEquals(10, ((Number) row.get("life_count")).intValue());
        assertEquals("SCRAPPED", row.get("status"), "末次寿命占用成功后模具立即进入不可再排产状态");
        assertEquals(1, castingCountOfMold(moldId), "模具下最终只有一张有效铸件卡");

        // 失败方刷新后再建卡：按真实结果仍然失败，且不再产生任何变化
        BizException again = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-2001", "STAINLESS", heatId, moldId, "1")));
        assertTrue(again.getMessage().contains("寿命"));
        assertEquals(10, ((Number) moldRow(moldId).get("used_count")).intValue());
        assertEquals(1, castingCountOfMold(moldId));
    }

    // ---------------- 2. 普通成功：寿命富裕时建卡，扣一次、状态在产 ----------------

    @Test
    void normalCreateConsumesExactlyOnce() {
        Long heatId = newHeat("H-R-0002", "CAST_IRON", true);
        Long moldId = newMold("M-R-0002", 80, 32, "CAST_IRON", "IN_USE");

        Long castingId = castingService.create(request("C-R-0002", "CAST_IRON", heatId, moldId, "2")).getId();
        assertTrue(castingId != null && castingId > 0);

        Map<String, Object> row = moldRow(moldId);
        assertEquals(33, ((Number) row.get("used_count")).intValue(), "寿命恰好消耗一次");
        assertEquals("IN_USE", row.get("status"), "未到寿命上限，保持在产可继续排产");
        assertEquals("WAIT_POUR",
                jdbc.queryForObject("select status from casting where id = ?", String.class, castingId));
        assertEquals(1, castingCountOfMold(moldId));

        // 再排一张仍成功，次数线性 +1
        castingService.create(request("C-R-0003", "CAST_IRON", heatId, moldId, "3"));
        assertEquals(34, ((Number) moldRow(moldId).get("used_count")).intValue());
        assertEquals(2, castingCountOfMold(moldId));
    }

    // ---------------- 3. 校验失败：任何一条不满足，次数与卡片都不变 ----------------

    @Test
    void validationFailuresChangeNothing() {
        Long tappedStainless = newHeat("H-R-0010", "STAINLESS", true);
        Long untapped = newHeat("H-R-0011", "CAST_IRON", false);
        Long tappedIron = newHeat("H-R-0012", "CAST_IRON", true);
        Long moldId = newMold("M-R-0010", 60, 10, "STAINLESS", "AVAILABLE");

        // 3.1 炉次未出炉
        BizException e1 = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0010", "CAST_IRON", untapped, moldId, "1")));
        assertTrue(e1.getMessage().contains("尚未出炉"));

        // 3.2 铸件材质与炉次材质不一致
        BizException e2 = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0011", "CAST_IRON", tappedStainless, moldId, "1")));
        assertTrue(e2.getMessage().contains("不一致"));

        // 3.3 模具适用材质不符（炉次是灰铁、模具只适用不锈钢）
        BizException e3 = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0012", "CAST_IRON", tappedIron, moldId, "1")));
        assertTrue(e3.getMessage().contains("适用材质"));

        // 3.4 型腔号越界（模具只有 4 个型腔）
        BizException e4a = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0013", "STAINLESS", tappedStainless, moldId, "5")));
        assertTrue(e4a.getMessage().contains("型腔"));
        BizException e4b = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0014", "STAINLESS", tappedStainless, moldId, "甲")));
        assertTrue(e4b.getMessage().contains("型腔号必须"));
        BizException e4c = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0015", "STAINLESS", tappedStainless, moldId, " ")));
        assertTrue(e4c.getMessage().contains("型腔号"));

        // 3.5 模具不存在
        assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0016", "STAINLESS", tappedStainless, 999999L, "1")));

        // 统一核对：全部被拦后模具次数没变、没有落下任何铸件卡
        Map<String, Object> row = moldRow(moldId);
        assertEquals(10, ((Number) row.get("used_count")).intValue());
        assertEquals("AVAILABLE", row.get("status"));
        assertEquals(0, castingCountOfMold(moldId));
    }

    @Test
    void scrappedAndExhaustedMoldRejected() {
        Long heatId = newHeat("H-R-0020", "STAINLESS", true);
        Long scrappedId = newMold("M-R-0020", 40, 40, "STAINLESS", "SCRAPPED");

        // 已报废模具：过期页面仍带着它提交，服务端必须挡回
        BizException e = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0020", "STAINLESS", heatId, scrappedId, "1")));
        assertTrue(e.getMessage().contains("报废"));
        assertEquals(40, ((Number) moldRow(scrappedId).get("used_count")).intValue());
        assertEquals(0, castingCountOfMold(scrappedId));

        // 账实不一致的脏数据（次数已到顶但状态还挂 AVAILABLE）也要按真实寿命挡住，不能只信按钮/状态
        Long exhaustedId = newMold("M-R-0021", 50, 50, "STAINLESS", "AVAILABLE");
        BizException e2 = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0021", "STAINLESS", heatId, exhaustedId, "1")));
        assertTrue(e2.getMessage().contains("剩余寿命为 0"));
        assertEquals(0, castingCountOfMold(exhaustedId));
    }

    // ---------------- 4. 异常回滚：寿命占用后持久化失败，必须一起回退 ----------------

    @Test
    void persistenceFailureAfterClaimRollsBackLife() {
        Long heatId = newHeat("H-R-0030", "STAINLESS", true);
        Long moldId = newMold("M-R-0030", 5, 3, "STAINLESS", "AVAILABLE");

        // operator 列长 32，给 64 字符让铸件卡 insert 在数据库层失败（发生在原子占用寿命之后）。
        // 期望：异常抛出，且「寿命已扣但卡片不存在」的半成品被事务回退干净。
        CastingRequest bad = request("C-R-0030", "STAINLESS", heatId, moldId, "1");
        bad.setOperator("操".repeat(64));
        assertThrows(DataAccessException.class, () -> castingService.create(bad));

        Map<String, Object> row = moldRow(moldId);
        assertEquals(3, ((Number) row.get("used_count")).intValue(), "持久化异常后寿命占用必须回退");
        assertEquals("AVAILABLE", row.get("status"));
        assertEquals(0, castingCountOfMold(moldId), "不得留下孤立铸件卡");
        assertEquals(0, castingCountByNo("C-R-0030"));

        // 回退后模具仍可正常排产，证明寿命没有被凭空吃掉
        assertDoesNotThrow(() -> castingService.create(request("C-R-0031", "STAINLESS", heatId, moldId, "1")));
        assertEquals(4, ((Number) moldRow(moldId).get("used_count")).intValue());
        assertEquals(1, castingCountOfMold(moldId));
    }

    // ---------------- 5. 重复请求：同一铸件编号重复提交不能扣两次寿命 ----------------

    @Test
    void duplicateRequestDoesNotConsumeTwice() {
        Long heatId = newHeat("H-R-0040", "STAINLESS", true);
        Long moldId = newMold("M-R-0040", 10, 8, "STAINLESS", "AVAILABLE");

        castingService.create(request("C-R-0040", "STAINLESS", heatId, moldId, "1"));
        // 用户重复点击 / 重放同一请求
        BizException dup = assertThrows(BizException.class,
                () -> castingService.create(request("C-R-0040", "STAINLESS", heatId, moldId, "1")));
        assertTrue(dup.getMessage().contains("已存在"));

        Map<String, Object> row = moldRow(moldId);
        assertEquals(9, ((Number) row.get("used_count")).intValue(), "重复请求只能扣一次寿命");
        assertEquals(1, castingCountOfMold(moldId), "重复编号只能有一张卡");
    }

    // ---------------- 5b. 并发重复请求：同一编号、同一模具同时提交，仍只成一张 ----------------

    @Test
    void concurrentDuplicateRequestOnlyOneCard() throws Exception {
        Long heatId = newHeat("H-R-0041", "STAINLESS", true);
        final Long moldId = newMold("M-R-0041", 10, 8, "STAINLESS", "AVAILABLE");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger bizFail = new AtomicInteger();
        AtomicInteger otherFail = new AtomicInteger();
        List<String> messages = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    castingService.create(request("C-R-SAME", "STAINLESS", heatId, moldId, "1"));
                    ok.incrementAndGet();
                } catch (BizException e) {
                    bizFail.incrementAndGet();
                    messages.add(e.getMessage());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (RuntimeException e) {
                    otherFail.incrementAndGet();
                    messages.add(e.getClass().getSimpleName());
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(1, ok.get(), "重复请求最终只能建立一张卡");
        assertEquals(threads - 1, bizFail.get(), "重复请求必须是明确业务失败而不是系统异常");
        assertEquals(0, otherFail.get(), "不应出现业务异常之外的失败：" + messages);
        assertTrue(messages.stream().allMatch(m -> m.contains("刷新")),
                "重复请求的失败提示应引导刷新查看真实结果：" + messages);
        assertEquals(9, ((Number) moldRow(moldId).get("used_count")).intValue(), "重复请求只能扣一次寿命");
        assertEquals(1, castingCountByNo("C-R-SAME"));
        assertEquals(1, castingCountOfMold(moldId));
    }

    // ---------------- 6. 模具独立排产接口同样原子：末次寿命 +1 只成功一次 ----------------

    @Test
    void standaloneConsumeIsAlsoAtomic() throws Exception {
        final Long moldId = newMold("M-R-0050", 10, 9, "STAINLESS", "AVAILABLE");

        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger bizFail = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    moldService.consume(moldId);
                    ok.incrementAndGet();
                } catch (BizException e) {
                    bizFail.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS));

        assertEquals(1, ok.get());
        assertEquals(threads - 1, bizFail.get());
        Map<String, Object> row = moldRow(moldId);
        assertEquals(10, ((Number) row.get("used_count")).intValue());
        assertEquals("SCRAPPED", row.get("status"));
        assertFalse(moldRepository.findById(moldId).orElseThrow().isSchedulable(),
                "刷新后模具必须呈现不可再排产");
    }
}
