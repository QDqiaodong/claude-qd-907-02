package com.casting.shop.repository;

import com.casting.shop.domain.Inspection;
import com.casting.shop.dto.VerdictStatDTO;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface InspectionRepository extends JpaRepository<Inspection, Long> {

    List<Inspection> findAllByOrderByIdDesc();

    List<Inspection> findByCastingIdOrderByIdDesc(Long castingId);

    boolean existsByInspectNo(String inspectNo);

    long countByVerdict(String verdict);

    /** 手写 JPQL ⑥：按判定结论统计检验单数 */
    @Query("select new com.casting.shop.dto.VerdictStatDTO("
            + "i.verdict, count(i)) from Inspection i group by i.verdict order by count(i) desc")
    List<VerdictStatDTO> statVerdict();
}
