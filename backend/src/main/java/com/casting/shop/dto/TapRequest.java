package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 出炉登记：记录出炉温度与出炉重量。 */
@Data
public class TapRequest {

    private Integer tapTemp;
    private BigDecimal tapWeight;
}
