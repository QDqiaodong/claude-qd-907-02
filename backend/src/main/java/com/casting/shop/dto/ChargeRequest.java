package com.casting.shop.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 配料单新增入参（含明细行）。 */
@Data
public class ChargeRequest {

    private String chargeNo;
    private Long heatId;
    private String operator;
    private String remark;
    private List<ChargeItemRequest> items = new ArrayList<>();
}
