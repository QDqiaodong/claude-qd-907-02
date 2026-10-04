package com.casting.shop.dto;

import java.math.BigDecimal;
import java.util.Map;
import lombok.Data;

/**
 * 双方案推演入参：同一份输入版本（炉次 + 目标重量 + 逐项上限 + 是否允许新料）
 * 一次算出两套方案，页面改任一参数都整体重算。
 */
@Data
public class ChargeSimulateRequest {

    /** 未出炉炉次 id，必选 */
    private Long heatId;

    /** 目标重量 kg，可空 → 缺省取炉次计划重量 */
    private BigDecimal targetWeight;

    /** 是否允许引入当前明细之外的新料，可空 → 缺省允许 */
    private Boolean allowNewMaterial;

    /** 逐项单品种上限 kg（品种 → 上限），可空项缺省取系统硬上限 {@code Dict.MAX_ITEM_WEIGHT} */
    private Map<String, BigDecimal> itemCaps;
}
