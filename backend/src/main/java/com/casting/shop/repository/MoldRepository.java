package com.casting.shop.repository;

import com.casting.shop.domain.Mold;
import com.casting.shop.dto.MoldLifeDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MoldRepository extends JpaRepository<Mold, Long> {

    List<Mold> findAllByOrderByIdAsc();

    boolean existsByMoldNo(String moldNo);

    List<Mold> findByStatusOrderByIdAsc(String status);

    /** 可排产的模具：未报废 且 已用次数未到寿命次数 */
    @Query("select m from Mold m where m.status <> 'SCRAPPED' and m.usedCount < m.lifeCount order by m.moldNo")
    List<Mold> findSchedulable();

    /**
     * 手写 JPQL ④：按型壳类型统计套数 / 平均寿命消耗率 / 已用寿命合计。
     */
    @Query("select new com.casting.shop.dto.MoldLifeDTO("
            + "m.moldType, count(m), avg(m.usedCount * 100.0 / m.lifeCount), sum(m.usedCount)) "
            + "from Mold m group by m.moldType order by count(m) desc")
    List<MoldLifeDTO> statByMoldType();
}
