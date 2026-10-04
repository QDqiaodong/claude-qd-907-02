package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 配料微调：某一品种按 ±delta kg 增减。 */
@Data
public class ChargeAdjustRequest {

    private String materialCode;
    private BigDecimal delta;
}
