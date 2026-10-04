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
import lombok.Getter;
import lombok.Setter;

/**
 * 配料明细：配料单里的一个品种（生铁 / 回炉料 / 硅铁 / 锰铁 / 增碳剂 / 造渣剂）。
 *
 * <p>ratio 为派生列：本项重量占配料合计重量的百分比，由 ChargeService 回写。</p>
 */
@Entity
@Table(name = "charge_item")
@Getter
@Setter
public class ChargeItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "charge_id", nullable = false)
    private Long chargeId;

    /** 炉料品种（6 选 1） */
    @Column(name = "material_code", length = 16)
    private String materialCode;

    @Column(name = "material_name", length = 48)
    private String materialName;

    /** 重量 kg，必须 > 0 */
    @Column(name = "weight_kg", precision = 10, scale = 2)
    private BigDecimal weightKg;

    /** 占比 %，派生列 */
    @Column(name = "ratio", precision = 6, scale = 2)
    private BigDecimal ratio;

    @Column(name = "remark", length = 64)
    private String remark;

    @Transient
    public String getMaterialRealName() {
        return Dict.chargeMaterialName(materialCode);
    }

    @Transient
    public String getMaterialColor() {
        return Dict.chargeMaterialColor(materialCode);
    }
}
