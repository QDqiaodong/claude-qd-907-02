package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 炉次新增/修改入参。 */
@Data
public class HeatRequest {

    private String heatNo;
    private Long furnaceId;
    private String alloy;
    private BigDecimal planWeight;
    private Integer meltTemp;
    private Integer refineTemp;
    private Integer tapTemp;
    private BigDecimal tapWeight;
    private String stage;
    private String operator;
}
