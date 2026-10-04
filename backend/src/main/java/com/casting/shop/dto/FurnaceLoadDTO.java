package com.casting.shop.dto;

import com.casting.shop.common.Dict;
import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 手写 JPQL ①：按熔炼炉统计炉次数、出炉重量合计与平均熔炼温度。
 *
 * <p>注意：JPQL 的 {@code select new} 要求 DTO 有参数个数完全匹配的显式构造器，
 * 这里显式给出 JPQL 用到的那个（furnaceNo, furnaceName, status, count, sum, avg）。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FurnaceLoadDTO {

    private String furnaceNo;
    private String furnaceName;
    private String status;
    private String statusName;
    private long heatCount;
    private BigDecimal tapWeightSum;
    private double avgMeltTemp;

    public FurnaceLoadDTO(String furnaceNo, String furnaceName, String status,
                          Long heatCount, BigDecimal tapWeightSum, Double avgMeltTemp) {
        this(furnaceNo, furnaceName, status, Dict.furnaceStatusName(status),
                heatCount == null ? 0L : heatCount,
                tapWeightSum == null ? BigDecimal.ZERO : tapWeightSum,
                avgMeltTemp == null ? 0.0 : Math.round(avgMeltTemp * 10.0) / 10.0);
    }
}
