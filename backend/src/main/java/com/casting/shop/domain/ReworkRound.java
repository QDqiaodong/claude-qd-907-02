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
 * 返修轮次：返修单下的一轮「缺陷快照 → 返修处置 → 复检结论」。
 *
 * <p>第 1 轮在首次检验判返修时随返修单一起生成，source_inspect_id 指向首次检验单；
 * 上一轮复检判「返修」时保留旧轮、生成下一轮，新一轮的 source_inspect_id 指向上一轮
 * 复检单——即<b>复检只能基于上一轮缺陷发起</b>，缺陷快照随检验单永久留存。</p>
 *
 * <p>状态流转：
 * <ul>
 *   <li>PENDING 待返修 → COMPLETED 返修完成待复检：登记责任人 / 处置方式 / 完成时间 / 处置说明</li>
 *   <li>COMPLETED 在复检单创建前允许撤回（→ PENDING，清空本轮处置字段，事件留痕）；
 *       一旦 reinspect_id 已写入，处置字段永久冻结，只能追加更正说明</li>
 *   <li>复检结论 PASS / REWORK / SCRAP 写入 round_status：合格则整单闭环入库，
 *       报废永久终止，再返修则保留本轮并由下一轮继续</li>
 * </ul>
 * </p>
 */
@Entity
@Table(name = "rework_round")
@Getter
@Setter
public class ReworkRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_id", nullable = false)
    private Long reworkId;

    /** 轮次序号，从 1 开始；(rework_id, round_no) 唯一 */
    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    /** 本轮缺陷快照来源检验单：首轮=首次检验单，后续轮=上一轮复检单 */
    @Column(name = "source_inspect_id", nullable = false)
    private Long sourceInspectId;

    /**
     * 轮次状态：PENDING / COMPLETED / PASS / REWORK / SCRAP。
     * 撤回不留状态：撤回后回到 PENDING 并清空处置字段，撤回动作由 rework_event 留痕。
     */
    @Column(name = "round_status", length = 16)
    private String roundStatus;

    // ---------------- 返修处置登记（COMPLETED 时写入；撤回时清空；复检创建后冻结） ----------------

    /** 处置方式：WELD / GRIND / IMPREGNATION / RE_MACHINE / SALVAGE */
    @Column(name = "rework_method", length = 16)
    private String reworkMethod;

    /** 返修责任人 */
    @Column(name = "responsible_person", length = 32)
    private String responsiblePerson;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** 处置说明 */
    @Column(name = "rework_note", length = 255)
    private String reworkNote;

    @Column(name = "completed_by", length = 32)
    private String completedBy;

    // ---------------- 复检（只能在处置完成后创建；创建后本轮永久冻结） ----------------

    /** 本轮复检检验单 id；为空表示还没发起复检，仍可撤回 */
    @Column(name = "reinspect_id")
    private Long reinspectId;

    /** 复检结论：PASS / REWORK / SCRAP */
    @Column(name = "reinspect_verdict", length = 16)
    private String reinspectVerdict;

    @Column(name = "reinspect_time")
    private LocalDateTime reinspectTime;

    @Column(name = "reinspector", length = 32)
    private String reinspector;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getRoundStatusName() {
        return Dict.roundStatusName(roundStatus);
    }

    @Transient
    public String getRoundStatusColor() {
        return Dict.roundStatusColor(roundStatus);
    }

    @Transient
    public String getReworkMethodName() {
        return Dict.reworkMethodName(reworkMethod);
    }

    @Transient
    public String getReinspectVerdictName() {
        return Dict.verdictName(reinspectVerdict);
    }

    /** 复检是否已经创建——创建后处置结果不可撤回、不可改写 */
    @Transient
    public boolean isReinspectCreated() {
        return reinspectId != null;
    }
}
