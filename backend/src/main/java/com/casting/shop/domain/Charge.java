package com.casting.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 配料单：为某个炉次配的一车炉料，明细在 charge_item。
 *
 * <p>target_weight 取自炉次出炉重量，total_weight 与它的偏差率超限时由 service 拦截。</p>
 */
@Entity
@Table(name = "charge")
@Getter
@Setter
public class Charge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 配料单号，唯一 */
    @Column(name = "charge_no", nullable = false, unique = true, length = 24)
    private String chargeNo;

    @Column(name = "heat_id")
    private Long heatId;

    @Column(name = "heat_no", length = 24)
    private String heatNo;

    /** 明细条数 */
    @Column(name = "item_count")
    private Integer itemCount;

    /** 配料合计重量 kg */
    @Column(name = "total_weight", precision = 12, scale = 2)
    private BigDecimal totalWeight;

    /** 目标重量 kg（= 对应炉次的出炉重量） */
    @Column(name = "target_weight", precision = 12, scale = 2)
    private BigDecimal targetWeight;

    /** 偏差率 %，(total - target) / target * 100 */
    @Column(name = "deviation_rate", precision = 6, scale = 2)
    private BigDecimal deviationRate;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "charge_time")
    private LocalDateTime chargeTime;

    @Column(name = "remark", length = 128)
    private String remark;

    @Transient
    public boolean isOverDeviation() {
        return deviationRate != null && deviationRate.abs().doubleValue() > 3.0;
    }
}
