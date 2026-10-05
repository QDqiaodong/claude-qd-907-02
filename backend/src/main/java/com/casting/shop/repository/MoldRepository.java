package com.casting.shop.repository;

import com.casting.shop.domain.Mold;
import com.casting.shop.dto.MoldLifeDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MoldRepository extends JpaRepository<Mold, Long> {

    List<Mold> findAllByOrderByIdAsc();

    boolean existsByMoldNo(String moldNo);

    List<Mold> findByStatusOrderByIdAsc(String status);

    /** 可排产的模具：未报废 且 已用次数未到寿命次数 */
    @Query("select m from Mold m where m.status <> 'SCRAPPED' and m.usedCount < m.lifeCount order by m.moldNo")
    List<Mold> findSchedulable();

    /**
     * 仅为模具行加锁，不做实体映射。建铸件卡排产前先锁模具行，
     * 让同一模具的并发排产在这里串行化：后到的事务等先到的提交后，
     * 再按最新已提交数据判断剩余寿命 / 状态，两笔建卡不可能同时读到「还剩一次」。
     * 与 CastingRepository.lockRowById 同一套路（实体上无自定义 join 投影，这里同样只取 id）。
     */
    @Query(value = "select id from mold where id = :id for update", nativeQuery = true)
    Long lockRowById(@Param("id") Long id);

    /**
     * 原子占用一次模具寿命（数据库层最终裁决）：
     * 只在「未报废 且 已用次数仍小于寿命次数」时把已用次数 +1；
     * 加到寿命上限的当次直接把状态置为 SCRAPPED，否则置为 IN_USE。
     *
     * <p>UPDATE 走的是当前读并持有行写锁，并发事务会在此语句上排队、
     * 按最新已提交值重新评估 WHERE，因此末次寿命最多只有一个事务能更新到行（返回 1），
     * 竞争失败方返回 0，由业务翻译成明确的失败提示。不依赖应用层先读后写。</p>
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "update mold set used_count = used_count + 1, "
            + "status = case when used_count + 1 >= life_count then 'SCRAPPED' else 'IN_USE' end "
            + "where id = :id and status <> 'SCRAPPED' and used_count < life_count",
            nativeQuery = true)
    int consumeOnce(@Param("id") Long id);

    /**
     * 手写 JPQL ④：按型壳类型统计套数 / 平均寿命消耗率 / 已用寿命合计。
     */
    @Query("select new com.casting.shop.dto.MoldLifeDTO("
            + "m.moldType, count(m), avg(m.usedCount * 100.0 / m.lifeCount), sum(m.usedCount)) "
            + "from Mold m group by m.moldType order by count(m) desc")
    List<MoldLifeDTO> statByMoldType();
}
