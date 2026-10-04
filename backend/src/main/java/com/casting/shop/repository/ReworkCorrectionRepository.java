package com.casting.shop.repository;

import com.casting.shop.domain.ReworkCorrection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReworkCorrectionRepository extends JpaRepository<ReworkCorrection, Long> {

    /** 更正说明只追加；详情时间线按写入先后展示 */
    List<ReworkCorrection> findByReworkIdOrderByIdAsc(Long reworkId);
}
