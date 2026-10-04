package com.casting.shop.repository;

import com.casting.shop.domain.Defect;
import com.casting.shop.dto.DefectStatDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DefectRepository extends JpaRepository<Defect, Long> {

    List<Defect> findByInspectIdOrderByIdAsc(Long inspectId);

    void deleteByInspectId(Long inspectId);

    /**
     * 手写 JPQL ⑤：按缺陷类型统计出现次数、平均缺陷尺寸与缺陷个数合计。
     */
    @Query("select new com.casting.shop.dto.DefectStatDTO("
            + "d.defectType, count(d), avg(d.sizeMm), coalesce(sum(d.quantity), 0)) "
            + "from Defect d group by d.defectType order by count(d) desc")
    List<DefectStatDTO> statByDefectType();
}
