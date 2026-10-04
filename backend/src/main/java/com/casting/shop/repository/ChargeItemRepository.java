package com.casting.shop.repository;

import com.casting.shop.domain.ChargeItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChargeItemRepository extends JpaRepository<ChargeItem, Long> {

    List<ChargeItem> findByChargeIdOrderByIdAsc(Long chargeId);

    /** 同一炉次重复配料同一材料要合并，这里用来找已存在的那一行 */
    ChargeItem findFirstByChargeIdAndMaterialCode(Long chargeId, String materialCode);

    void deleteByChargeId(Long chargeId);
}
