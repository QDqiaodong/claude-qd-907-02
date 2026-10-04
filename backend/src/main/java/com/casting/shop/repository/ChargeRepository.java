package com.casting.shop.repository;

import com.casting.shop.domain.Charge;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeRepository extends JpaRepository<Charge, Long> {

    List<Charge> findAllByOrderByIdDesc();

    List<Charge> findByHeatIdOrderByIdDesc(Long heatId);

    boolean existsByChargeNo(String chargeNo);
}
