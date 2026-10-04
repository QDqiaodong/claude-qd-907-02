package com.casting.shop.dto;

import com.casting.shop.common.Dict;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ②：按铸件状态统计卡片数与理论重量合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CastingStatusDTO {

    private String status;
    private String statusName;
    private String color;
    private long count;
    private BigDecimal weightSum;

    public CastingStatusDTO(String status, Long count, BigDecimal weightSum) {
        this(status, Dict.castingStatusName(status), Dict.castingStatusColor(status),
                count == null ? 0L : count,
                weightSum == null ? BigDecimal.ZERO : weightSum);
    }
}
