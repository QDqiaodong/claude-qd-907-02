package com.casting.shop.dto;

import com.casting.shop.common.Dict;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ④：按型壳类型统计模具套数、平均寿命消耗率与已用寿命合计。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MoldLifeDTO {

    private String moldType;
    private String moldTypeName;
    private long count;
    private double avgUsedRate;
    private long usedSum;

    public MoldLifeDTO(String moldType, Long count, Double avgUsedRate, Long usedSum) {
        this(moldType, Dict.moldTypeName(moldType),
                count == null ? 0L : count,
                avgUsedRate == null ? 0.0 : Math.round(avgUsedRate * 10.0) / 10.0,
                usedSum == null ? 0L : usedSum);
    }
}
