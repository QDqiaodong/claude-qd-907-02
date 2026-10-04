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
import org.hibernate.annotations.SQLSelect;

/**
 * 铸件卡：一件铸件的身份卡片，挂到某个已出炉的炉次与某套模具上。
 *
 * <p>本类的加载走 Hibernate 自定义 SQL（{@link SQLSelect}）：一条 left join 的原生 SQL
 * 把炉次表的 heat_no / alloy 与模具表的 mold_no / mold_name 一起查出来，
 * 映射到下面四个 {@code insertable = false, updatable = false} 的只读字段上。
 * 也就是说 findById / getReference（懒加载触发）时不发 Hibernate 默认的那条
 * {@code select * from casting where id=?}，而是发这条带 join 的 SQL。</p>
 *
 * <p>这四个字段在表里并不存在（它们是从关联表投影出来的只读派生列），
 * 因此不能写库，只能读。</p>
 */
@Entity
@Table(name = "casting")
@SQLSelect(sql = "select c.*, "
        + "h.heat_no as heat_no, h.alloy as heat_alloy, "
        + "m.mold_no as mold_no, m.mold_name as mold_name "
        + "from casting c "
        + "left join heat h on h.id = c.heat_id "
        + "left join mold m on m.id = c.mold_id "
        + "where c.id = ?")
@Getter
@Setter
public class Casting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 铸件编号，唯一 */
    @Column(name = "casting_no", nullable = false, unique = true, length = 24)
    private String castingNo;

    @Column(name = "casting_name", length = 48)
    private String castingName;

    /** 材质（5 选 1） */
    @Column(name = "material", length = 16)
    private String material;

    @Column(name = "heat_id")
    private Long heatId;

    @Column(name = "mold_id")
    private Long moldId;

    /** 型腔号 */
    @Column(name = "cavity_no", length = 16)
    private String cavityNo;

    /** 理论重量 kg，必须 > 0 */
    @Column(name = "theory_weight", precision = 10, scale = 2)
    private BigDecimal theoryWeight;

    /** 实测重量 kg */
    @Column(name = "actual_weight", precision = 10, scale = 2)
    private BigDecimal actualWeight;

    /** 状态：WAIT_POUR / POURED / CLEANING / INSPECTING / STORED / SCRAPPED */
    @Column(name = "status", length = 16)
    private String status;

    @Column(name = "customer", length = 48)
    private String customer;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "pour_time")
    private LocalDateTime pourTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // ---------------- 下面四个只读字段由 @SQLSelect 的 join SQL 投影出来 ----------------

    /** 所属炉次号（来自 heat.heat_no，只读） */
    @Column(name = "heat_no", insertable = false, updatable = false, length = 24)
    private String heatNo;

    /** 所属炉次的材质（来自 heat.alloy，只读） */
    @Column(name = "heat_alloy", insertable = false, updatable = false, length = 16)
    private String heatAlloy;

    /** 使用模具编号（来自 mold.mold_no，只读） */
    @Column(name = "mold_no", insertable = false, updatable = false, length = 24)
    private String moldNo;

    /** 使用模具名称（来自 mold.mold_name，只读） */
    @Column(name = "mold_name", insertable = false, updatable = false, length = 48)
    private String moldName;

    @Transient
    public String getMaterialName() {
        return Dict.materialName(material);
    }

    @Transient
    public String getMaterialColor() {
        return Dict.materialColor(material);
    }

    @Transient
    public String getStatusName() {
        return Dict.castingStatusName(status);
    }

    @Transient
    public String getStatusColor() {
        return Dict.castingStatusColor(status);
    }

    /** 只读派生：所属炉次的材质名 */
    @Transient
    public String getHeatAlloyName() {
        return Dict.materialName(heatAlloy);
    }

    /** 状态推进度（0~100） */
    @Transient
    public int getFlowProgress() {
        return Dict.castingStageIndex(status) * 20;
    }

    /** 是否有从 join 出来的只读派生值（页面据此标注“派生字段”） */
    @Transient
    public boolean isDerivedLoaded() {
        return heatNo != null || moldNo != null;
    }
}
