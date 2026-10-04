package com.casting.shop.dto;

import java.math.BigDecimal;
import lombok.Data;

/** 熔炼炉新增/修改入参。 */
@Data
public class FurnaceRequest {

    private String furnaceNo;
    private String furnaceName;
    private String furnaceType;
    private BigDecimal capacityKg;
    private Integer maxTemp;
    private Integer powerKw;
    private String status;
    private String note;
}
