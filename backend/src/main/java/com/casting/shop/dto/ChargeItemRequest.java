package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 配料明细入参。 */
@Data
public class ChargeItemRequest {

    private String materialCode;
    private BigDecimal weightKg;
    private String remark;
}
