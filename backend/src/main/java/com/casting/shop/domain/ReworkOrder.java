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
 * 返修单：一件铸件一旦首次检验判「返修」，就建立一张终身唯一的返修单，之后所有
 * 返修轮次、复检结论都挂在这张单下，直到「复检合格入库」或「复检判废终止」。
 *
 * <p>阶段 {@code stage} 只可能由返修流程推进：REWORKING 返修中 → REINSPECTING 待复检
 * （二者会随多轮返修来回切换）→ STORED / SCRAPPED 终态。</p>
 *
 * <p>snapshot_inspect_id 指向首次检验单——首次缺陷快照，永远不可改写；
 * casting_id 上有唯一约束：一件铸件至多一张返修单（并发建单的第二张会被数据库顶掉）。</p>
 */
@Entity
@Table(name = "rework_order")
@Getter
@Setter
public class ReworkOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 返修单号，唯一（默认沿用首次检验单号 RW- 前缀） */
    @Column(name = "rework_no", nullable = false, unique = true, length = 24)
    private String reworkNo;

    @Column(name = "casting_id", nullable = false)
    private Long castingId;

    @Column(name = "casting_no", length = 24)
    private String castingNo;

    /** 首次检验单 id（首次缺陷快照，不可删、不可改） */
    @Column(name = "snapshot_inspect_id", nullable = false)
    private Long snapshotInspectId;

    /** 当前返修阶段：REWORKING / REINSPECTING / STORED / SCRAPPED */
    @Column(name = "stage", length = 16)
    private String stage;

    /** 已进行到第几轮（第 1 轮由首次判返修生成） */
    @Column(name = "current_round")
    private Integer currentRound;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    /** 进入终态的时间（合格入库 / 判废终止） */
    @Column(name = "closed_at")
    private LocalDateTime closedAt;

    @Transient
    public String getStageName() {
        return Dict.reworkStageName(stage);
    }

    @Transient
    public String getStageColor() {
        return Dict.reworkStageColor(stage);
    }

    /** 是否仍在返修闭环中（还能登记处置 / 发起复检） */
    @Transient
    public boolean isOpen() {
        return "REWORKING".equals(stage) || "REINSPECTING".equals(stage);
    }
}
