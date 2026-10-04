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
 * 熔炼炉：一台中频 / 电弧 / 真空感应炉，负责按需开炉次。
 *
 * <p>实体字段不带默认值，默认状态由 service 的 create 里下放。</p>
 */
@Entity
@Table(name = "furnace")
@Getter
@Setter
public class Furnace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 炉号，全厂唯一 */
    @Column(name = "furnace_no", nullable = false, unique = true, length = 16)
    private String furnaceNo;

    @Column(name = "furnace_name", length = 48)
    private String furnaceName;

    /** 炉型：INDUCTION / ARC / VACUUM / RESISTANCE / CRUCIBLE */
    @Column(name = "furnace_type", length = 16)
    private String furnaceType;

    /** 额定容量 kg */
    @Column(name = "capacity_kg", precision = 10, scale = 2)
    private BigDecimal capacityKg;

    /** 最高工作温度 ℃ */
    @Column(name = "max_temp")
    private Integer maxTemp;

    /** 额定功率 kW */
    @Column(name = "power_kw")
    private Integer powerKw;

    /** 状态：IDLE / BUSY / MAINTENANCE / FAULT */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getFurnaceTypeName() {
        return Dict.furnaceTypeName(furnaceType);
    }

    @Transient
    public String getStatusName() {
        return Dict.furnaceStatusName(status);
    }

    @Transient
    public boolean isUsable() {
        return "IDLE".equals(status) || "BUSY".equals(status);
    }
}
