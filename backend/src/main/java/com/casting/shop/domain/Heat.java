package com.casting.shop.domain;

import com.casting.shop.common.Dict;
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
 * 炉次：一台炉在某一段时间里熔炼一炉金属水的完整过程。
 *
 * <p>阶段流转 装料 → 熔炼 → 精炼 → 出炉 → 已浇完，由 HeatService 的 advance 控制，
 * 跳阶段或回退都会被 service 拦截。</p>
 */
@Entity
@Table(name = "heat")
@Getter
@Setter
public class Heat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 炉次号，唯一 */
    @Column(name = "heat_no", nullable = false, unique = true, length = 24)
    private String heatNo;

    @Column(name = "furnace_id")
    private Long furnaceId;

    /** 冗余炉号，列表展示与 @SQLSelect join 都用它 */
    @Column(name = "furnace_no", length = 16)
    private String furnaceNo;

    /** 材质（5 选 1）：CAST_IRON / DUCTILE_IRON / CARBON_STEEL / STAINLESS / ALUMINUM */
    @Column(name = "alloy", length = 16)
    private String alloy;

    /** 计划装炉重量 kg */
    @Column(name = "plan_weight", precision = 10, scale = 2)
    private BigDecimal planWeight;

    /** 熔炼温度 ℃，必须落在材质允许区间 */
    @Column(name = "melt_temp")
    private Integer meltTemp;

    /** 精炼温度 ℃ */
    @Column(name = "refine_temp")
    private Integer refineTemp;

    /** 出炉温度 ℃ */
    @Column(name = "tap_temp")
    private Integer tapTemp;

    /** 出炉重量 kg，必须 > 0 */
    @Column(name = "tap_weight", precision = 10, scale = 2)
    private BigDecimal tapWeight;

    /** 阶段：CHARGING / MELTING / REFINING / TAPPING / POURED */
    @Column(name = "stage", length = 16)
    private String stage;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "tap_time")
    private LocalDateTime tapTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getAlloyName() {
        return Dict.materialName(alloy);
    }

    @Transient
    public String getAlloyColor() {
        return Dict.materialColor(alloy);
    }

    @Transient
    public String getStageName() {
        return Dict.stageName(stage);
    }

    @Transient
    public String getStageColor() {
        return Dict.stageColor(stage);
    }

    /** 阶段推进度（0~100），竖条高度示意用 */
    @Transient
    public int getStageProgress() {
        return Dict.stageIndex(stage) * 25;
    }

    /** 熔炼温度是否越界：null 视为未记录 */
    @Transient
    public boolean isTempValid() {
        return meltTemp != null && meltTemp >= Dict.meltLow(alloy) && meltTemp <= Dict.meltHigh(alloy);
    }

    @Transient
    public boolean isTapped() {
        return tapWeight != null && tapWeight.compareTo(BigDecimal.ZERO) > 0;
    }
}
