package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 缺陷记录入参。 */
@Data
public class DefectRequest {

    private String defectType;
    private String severity;
    private String position;
    private BigDecimal sizeMm;
    private Integer quantity;
    private String remark;
}
