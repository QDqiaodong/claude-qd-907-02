package com.casting.shop.repository;

import com.casting.shop.domain.Mold;
import com.casting.shop.dto.MoldLifeDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
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
     * 仅为模具行加锁，不做实体映射。排产建卡必须先拿到这把锁，
     * 让同一模具的两笔并发请求串行检查 used_count / life_count，避免最后一次寿命被重复占用。
     */
    @Query(value = "select id from mold where id = :id for update", nativeQuery = true)
    Long lockRowById(@Param("id") Long id);

    /**
     * 手写 JPQL ④：按型壳类型统计套数 / 平均寿命消耗率 / 已用寿命合计。
     */
    @Query("select new com.casting.shop.dto.MoldLifeDTO("
            + "m.moldType, count(m), avg(m.usedCount * 100.0 / m.lifeCount), sum(m.usedCount)) "
            + "from Mold m group by m.moldType order by count(m) desc")
    List<MoldLifeDTO> statByMoldType();
}
