package com.casting.shop.repository;

import com.casting.shop.domain.ReworkEvent;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkEventRepository extends JpaRepository<ReworkEvent, Long> {

    List<ReworkEvent> findByReworkIdOrderByIdAsc(Long reworkId);
}
