package com.casting.shop.repository;

import com.casting.shop.domain.Furnace;
import com.casting.shop.dto.FurnaceLoadDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface FurnaceRepository extends JpaRepository<Furnace, Long> {

    List<Furnace> findAllByOrderByIdAsc();

    boolean existsByFurnaceNo(String furnaceNo);

    long countByStatus(String status);

    /**
     * 手写 JPQL ①：熔炼炉负荷视图 —— 炉 + 该炉的炉次数 / 出炉重量合计 / 平均熔炼温度。
     * 注意 select new 的 DTO 必须有一个参数个数完全一致的显式构造器。
     */
    @Query("select new com.casting.shop.dto.FurnaceLoadDTO("
            + "f.furnaceNo, f.furnaceName, f.status, count(h), "
            + "coalesce(sum(h.tapWeight), 0), avg(h.meltTemp)) "
            + "from Furnace f left join Heat h on h.furnaceId = f.id "
            + "group by f.id, f.furnaceNo, f.furnaceName, f.status "
            + "order by f.furnaceNo")
    List<FurnaceLoadDTO> statFurnaceLoad();
}
