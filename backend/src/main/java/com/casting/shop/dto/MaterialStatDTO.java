package com.casting.shop.dto;

import com.casting.shop.common.Dict;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ③：按材质统计铸件数量与理论重量合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MaterialStatDTO {

    private String material;
    private String materialName;
    private String color;
    private long count;
    private BigDecimal weightSum;
    private double avgWeight;

    public MaterialStatDTO(String material, Long count, BigDecimal weightSum) {
        this(material, Dict.materialName(material), Dict.materialColor(material),
                count == null ? 0L : count,
                weightSum == null ? BigDecimal.ZERO : weightSum,
                count == null || weightSum == null || count == 0
                        ? 0.0
                        : Math.round(weightSum.doubleValue() / count * 100.0) / 100.0);
    }
}
