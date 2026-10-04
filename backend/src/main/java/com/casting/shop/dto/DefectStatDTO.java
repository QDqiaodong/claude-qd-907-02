package com.casting.shop.dto;

import com.casting.shop.common.Dict;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ⑤：按缺陷类型统计出现次数、平均缺陷尺寸与缺陷个数合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DefectStatDTO {

    private String defectType;
    private String defectTypeName;
    private String color;
    private long count;
    private double avgSizeMm;
    private long quantitySum;

    public DefectStatDTO(String defectType, Long count, Double avgSizeMm, Long quantitySum) {
        this(defectType, Dict.defectTypeName(defectType), Dict.defectTypeColor(defectType),
                count == null ? 0L : count,
                avgSizeMm == null ? 0.0 : Math.round(avgSizeMm * 100.0) / 100.0,
                quantitySum == null ? 0L : quantitySum);
    }
}
