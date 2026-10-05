package com.casting.shop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.casting.shop.common.BizException;
import com.casting.shop.domain.Heat;
import com.casting.shop.domain.Mold;
import com.casting.shop.dto.CastingRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.HeatRepository;
import com.casting.shop.repository.MoldRepository;
import com.casting.shop.service.CastingService;
import com.casting.shop.service.MoldService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 铸件建卡与模具寿命占用的原子一致性冒烟。
 *
 * <p>并发用例直接压同一模具的最后一次寿命：FOR UPDATE 行锁让两笔事务串行，
 * 成功方同时提交铸件卡与寿命扣减；失败方在锁内看到寿命为 0，业务异常与整个事务一起回滚。</p>
 */
@SpringBootTest
class CastingSchedulingIT {

    @Autowired private CastingService castingService;
    @Autowired private MoldRepository moldRepository;
    @Autowired private HeatRepository heatRepository;
    @Autowired private JdbcTemplate jdbc;
    @SpyBean private CastingRepository castingRepository;
    @SpyBean private MoldService moldService;

    @BeforeEach
    void clean() {
        jdbc.update("delete from casting");
        moldRepository.deleteAll();
        heatRepository.deleteAll();
    }

    private Heat newHeat(String no, String alloy, boolean tapped) {
        Heat heat = new Heat();
        heat.setHeatNo(no);
        heat.setAlloy(alloy);
        heat.setStage(tapped ? "TAPPING" : "CHARGING");
        heat.setTapWeight(tapped ? BigDecimal.valueOf(1000) : null);
        heat.setStartTime(LocalDateTime.now());
        heat.setCreatedAt(LocalDateTime.now());
        return heatRepository.save(heat);
    }

    private Mold newMold(String no, int life, int used, String fitMaterial, int cavityCount, String status) {
        Mold mold = new Mold();
        mold.setMoldNo(no);
        mold.setMoldName("测试模具 " + no);
        mold.setMoldType("WAX");
        mold.setLifeCount(life);
        mold.setUsedCount(used);
        mold.setFitMaterial(fitMaterial);
        mold.setCavityCount(cavityCount);
        mold.setStatus(status);
        mold.setCreatedAt(LocalDateTime.now());
        return moldRepository.save(mold);
    }

    private CastingRequest request(Heat heat, Mold mold, String no, String material, int cavityNo) {
        CastingRequest request = new CastingRequest();
        request.setCastingNo(no);
        request.setCastingName("测试铸件 " + no);
        request.setMaterial(material);
        request.setHeatId(heat.getId());
        request.setMoldId(mold.getId());
        request.setCavityNo(String.valueOf(cavityNo));
        request.setTheoryWeight(BigDecimal.valueOf(12.5));
        request.setOperator("测试员");
        return request;
    }

    private int castingCount(Long moldId) {
        return jdbc.queryForObject("select count(*) from casting where mold_id = ?", Integer.class, moldId);
    }

    private int usedCount(Long moldId) {
        return jdbc.queryForObject("select used_count from mold where id = ?", Integer.class, moldId);
    }

    private String moldStatus(Long moldId) {
        return jdbc.queryForObject("select status from mold where id = ?", String.class, moldId);
    }

    @Test
    void normalCreateConsumesOneLifeAndKeepsMoldAvailable() {
        Heat heat = newHeat("H-CAST-OK", "STAINLESS", true);
        Mold mold = newMold("M-CAST-OK", 3, 1, "STAINLESS", 4, "AVAILABLE");

        var casting = castingService.create(request(heat, mold, "C-CAST-OK", "STAINLESS", 2));

        assertEquals("WAIT_POUR", casting.getStatus());
        assertEquals(1, castingCount(mold.getId()));
        assertEquals(2, usedCount(mold.getId()));
        assertEquals("IN_USE", moldStatus(mold.getId()));
    }

    @Test
    void concurrentRequestsForLastLifeCreateOnlyOneCasting() throws Exception {
        Heat heat = newHeat("H-CAST-LAST", "STAINLESS", true);
        Mold mold = newMold("M-CAST-LAST", 1, 0, "STAINLESS", 2, "AVAILABLE");

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicReference<Throwable> unexpected = new AtomicReference<>();

        for (int i = 0; i < threads; i++) {
            final int index = i;
            pool.submit(() -> {
                try {
                    start.await();
                    castingService.create(request(heat, mold, "C-CAST-LAST-" + index, "STAINLESS", 1));
                    success.incrementAndGet();
                } catch (BizException e) {
                    rejected.incrementAndGet();
                    assertTrue(e.getMessage().contains("剩余寿命已为 0"), e.getMessage());
                } catch (Throwable e) {
                    unexpected.compareAndSet(null, e);
                }
            });
        }
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS));

        assertNull(unexpected.get(), unexpected.get() == null ? "" : unexpected.get().getMessage());
        assertEquals(1, success.get(), "最后一次寿命只能生成一张铸件卡");
        assertEquals(threads - 1, rejected.get(), "其余并发请求必须得到明确业务失败");
        assertEquals(1, castingCount(mold.getId()), "只允许一张有效铸件卡引用该模具");
        assertEquals(1, usedCount(mold.getId()), "寿命只能消耗一次");
        assertEquals("SCRAPPED", moldStatus(mold.getId()), "末次寿命用完后必须立即不可再排产");
    }

    @Test
    void duplicateRequestRejectsSecondSubmitWithoutExtraLifeConsumption() {
        Heat heat = newHeat("H-CAST-DUP", "STAINLESS", true);
        Mold mold = newMold("M-CAST-DUP", 2, 0, "STAINLESS", 2, "AVAILABLE");
        CastingRequest request = request(heat, mold, "C-CAST-DUP", "STAINLESS", 1);

        castingService.create(request);
        BizException duplicate = assertThrows(BizException.class, () -> castingService.create(request));

        assertTrue(duplicate.getMessage().contains("请刷新页面查看真实结果"), duplicate.getMessage());
        assertEquals(1, castingCount(mold.getId()));
        assertEquals(1, usedCount(mold.getId()));
        assertEquals("IN_USE", moldStatus(mold.getId()));
    }

    @Test
    void validationFailuresDoNotCreateCastingOrConsumeLife() {
        Heat tappedStainless = newHeat("H-CAST-VALID", "STAINLESS", true);
        Heat untapped = newHeat("H-CAST-UNTAPPED", "STAINLESS", false);
        Heat castIron = newHeat("H-CAST-IRON", "CAST_IRON", true);
        Mold stainlessMold = newMold("M-CAST-VALID", 1, 0, "STAINLESS", 2, "AVAILABLE");
        Mold ironMold = newMold("M-CAST-IRON", 1, 0, "CAST_IRON", 2, "AVAILABLE");
        Mold exhausted = newMold("M-CAST-EXHAUSTED", 1, 1, "STAINLESS", 2, "SCRAPPED");

        assertBiz("尚未出炉",
                () -> castingService.create(request(untapped, stainlessMold, "C-VALID-1", "STAINLESS", 1)));
        assertBiz("不一致",
                () -> castingService.create(request(tappedStainless, ironMold, "C-VALID-2", "CAST_IRON", 1)));
        assertBiz("不匹配",
                () -> castingService.create(request(tappedStainless, ironMold, "C-VALID-3", "STAINLESS", 1)));
        assertBiz("型腔号必须在 1~2",
                () -> castingService.create(request(tappedStainless, stainlessMold, "C-VALID-4", "STAINLESS", 3)));
        assertBiz("已报废或寿命已用完",
                () -> castingService.create(request(tappedStainless, exhausted, "C-VALID-5", "STAINLESS", 1)));
        // 炉次材质本身不合法时，即使选择了同材质模具，也必须在扣寿命前拦截。
        assertBiz("不一致",
                () -> castingService.create(request(castIron, ironMold, "C-VALID-6", "STAINLESS", 1)));

        for (Mold mold : java.util.List.of(stainlessMold, ironMold, exhausted)) {
            assertEquals(0, castingCount(mold.getId()), "校验失败不能留下铸件卡：" + mold.getMoldNo());
            assertEquals(mold.getUsedCount(), usedCount(mold.getId()), "校验失败不能扣模具寿命：" + mold.getMoldNo());
            assertEquals(mold.getStatus(), moldStatus(mold.getId()), "校验失败不能改模具状态：" + mold.getMoldNo());
        }
    }

    @Test
    void castingPersistenceFailureRollsBackBeforeLifeConsumption() {
        Heat heat = newHeat("H-CAST-SAVE-FAIL", "STAINLESS", true);
        Mold mold = newMold("M-CAST-SAVE-FAIL", 1, 0, "STAINLESS", 2, "AVAILABLE");
        Mockito.doThrow(new IllegalStateException("模拟铸件卡持久化异常"))
                .when(castingRepository)
                .save(Mockito.any(com.casting.shop.domain.Casting.class));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> castingService.create(request(heat, mold, "C-CAST-SAVE-FAIL", "STAINLESS", 1)));
        assertTrue(error.getMessage().contains("铸件卡持久化异常"));

        assertEquals(0, castingCount(mold.getId()));
        assertEquals(0, usedCount(mold.getId()));
        assertEquals("AVAILABLE", moldStatus(mold.getId()));
    }

    @Test
    void failureAfterLifeConsumptionRollsBackCardAndLifeTogether() {
        Heat heat = newHeat("H-CAST-ROLLBACK", "STAINLESS", true);
        Mold mold = newMold("M-CAST-ROLLBACK", 1, 0, "STAINLESS", 2, "AVAILABLE");
        Mockito.doAnswer(invocation -> {
            Mold updated = (Mold) invocation.callRealMethod();
            if (mold.getId().equals(updated.getId())) {
                // 触发模具 UPDATE 后再模拟后续失败：这正是「寿命已扣、卡还未提交」的危险点。
                moldRepository.findById(mold.getId());
                throw new IllegalStateException("模拟寿命占用后的后续持久化异常");
            }
            return updated;
        }).when(moldService).consume(Mockito.eq(mold.getId()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> castingService.create(request(heat, mold, "C-CAST-ROLLBACK", "STAINLESS", 1)));
        assertTrue(error.getMessage().contains("后续持久化异常"));

        assertEquals(0, castingCount(mold.getId()), "异常后不得留下孤立铸件卡");
        assertEquals(0, usedCount(mold.getId()), "铸件卡回滚时寿命扣减必须一起回滚");
        assertEquals("AVAILABLE", moldStatus(mold.getId()));
    }

    private static void assertBiz(String expectedMessage, ThrowingRunnable runnable) {
        BizException exception = assertThrows(BizException.class, runnable::run);
        assertTrue(exception.getMessage().contains(expectedMessage), exception.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }
}
