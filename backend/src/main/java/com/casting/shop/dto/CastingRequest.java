package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 铸件卡新增/修改入参。 */
@Data
public class CastingRequest {

    private String castingNo;
    private String castingName;
    private String material;
    private Long heatId;
    private Long moldId;
    private String cavityNo;
    private BigDecimal theoryWeight;
    private BigDecimal actualWeight;
    private String status;
    private String customer;
    private String operator;
}
