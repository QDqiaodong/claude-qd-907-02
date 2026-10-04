package com.casting.shop.domain;

import com.casting.shop.common.Dict;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/**
 * 模具型壳：一套蜡型壳 / 树脂砂型 / 陶瓷型壳 / 金属型，按型腔数组织生产。
 *
 * <p>寿命管理：used_count 不得超过 life_count，超寿命或已报废的模具禁止排产。</p>
 */
@Entity
@Table(name = "mold")
@Getter
@Setter
public class Mold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 模具编号，唯一 */
    @Column(name = "mold_no", nullable = false, unique = true, length = 24)
    private String moldNo;

    @Column(name = "mold_name", length = 48)
    private String moldName;

    /** 型壳类型：WAX / RESIN_SAND / CERAMIC / METAL */
    @Column(name = "mold_type", length = 16)
    private String moldType;

    /** 型腔数，必须 > 0 */
    @Column(name = "cavity_count")
    private Integer cavityCount;

    /** 寿命次数 */
    @Column(name = "life_count")
    private Integer lifeCount;

    /** 已用次数，不得超过寿命次数 */
    @Column(name = "used_count")
    private Integer usedCount;

    /** 适用材质 */
    @Column(name = "fit_material", length = 16)
    private String fitMaterial;

    /** 状态：AVAILABLE / IN_USE / SCRAPPED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "storage_slot", length = 32)
    private String storageSlot;

    @Column(name = "note", length = 64)
    private String note;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getMoldTypeName() {
        return Dict.moldTypeName(moldType);
    }

    @Transient
    public String getStatusName() {
        return Dict.moldStatusName(status);
    }

    @Transient
    public String getFitMaterialName() {
        return Dict.materialName(fitMaterial);
    }

    /** 剩余可用次数 */
    @Transient
    public int getRemainCount() {
        int life = lifeCount == null ? 0 : lifeCount;
        int used = usedCount == null ? 0 : usedCount;
        return Math.max(0, life - used);
    }

    /** 寿命消耗率 % */
    @Transient
    public double getUsedRate() {
        int life = lifeCount == null || lifeCount <= 0 ? 0 : lifeCount;
        int used = usedCount == null ? 0 : usedCount;
        if (life == 0) {
            return 100.0;
        }
        return Math.round(used * 1000.0 / life) / 10.0;
    }

    /** 剩余寿命率 % */
    @Transient
    public double getRemainRate() {
        return Math.round((100.0 - getUsedRate()) * 10.0) / 10.0;
    }

    /** 低寿命预警（剩余低于 20%） */
    @Transient
    public boolean isWarn() {
        return getRemainRate() <= Dict.MOLD_WARN_REMAIN_RATE.doubleValue();
    }

    /** 超寿命或已报废：不可再排产 */
    @Transient
    public boolean isOverLife() {
        int life = lifeCount == null ? 0 : lifeCount;
        int used = usedCount == null ? 0 : usedCount;
        return used >= life;
    }

    @Transient
    public boolean isSchedulable() {
        return !"SCRAPPED".equals(status) && !isOverLife();
    }
}
