package com.casting.shop.repository;

import com.casting.shop.domain.Heat;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HeatRepository extends JpaRepository<Heat, Long> {

    List<Heat> findAllByOrderByIdAsc();

    List<Heat> findByFurnaceIdOrderByIdAsc(Long furnaceId);

    boolean existsByHeatNo(String heatNo);

    long countByStage(String stage);

    List<Heat> findByStageOrderByIdAsc(String stage);
}
