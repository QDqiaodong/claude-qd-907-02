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
 * 缺陷记录：一张检验单下的一条缺陷，缺陷类型 6 选 1。
 */
@Entity
@Table(name = "defect")
@Getter
@Setter
public class Defect {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "inspect_id", nullable = false)
    private Long inspectId;

    /** 缺陷类型（6 选 1）：SAND_HOLE / POROSITY / SHRINKAGE / CRACK / INCLUSION / MISRUN */
    @Column(name = "defect_type", length = 16)
    private String defectType;

    /** 严重度：MINOR / MAJOR / CRITICAL */
    @Column(name = "severity", length = 16)
    private String severity;

    /** 缺陷所在部位 */
    @Column(name = "defect_position", length = 32)
    private String position;

    /** 缺陷尺寸 mm */
    @Column(name = "size_mm", precision = 6, scale = 2)
    private BigDecimal sizeMm;

    /** 同部位同类缺陷个数 */
    @Column(name = "quantity")
    private Integer quantity;

    @Column(name = "remark", length = 64)
    private String remark;

    @Transient
    public String getDefectTypeName() {
        return Dict.defectTypeName(defectType);
    }

    @Transient
    public String getDefectTypeColor() {
        return Dict.defectTypeColor(defectType);
    }

    @Transient
    public String getSeverityName() {
        return Dict.severityName(severity);
    }
}
