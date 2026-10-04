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
 * 返修事件流：返修单上的每一次动作都追加一条（只增不改不删），
 * 供详情页按时间展示「首次缺陷 → 每轮处置 → 复检结论 / 撤回 / 更正 → 闭环」。
 *
 * <p>典型事件：CREATED 建单（首次判返修）、COMPLETED 登记返修完成、
 * WITHDRAWN 撤回完成结果、REINSPECTED 登记复检结论、CORRECTED 追加更正说明、
 * CLOSED_STORED 合格入库、CLOSED_SCRAP 判废终止。</p>
 */
@Entity
@Table(name = "rework_event")
@Getter
@Setter
public class ReworkEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_id", nullable = false)
    private Long reworkId;

    /** 关联轮次（建单事件为首轮） */
    @Column(name = "round_no")
    private Integer roundNo;

    /** 事件类型，见类注释 */
    @Column(name = "event_type", length = 16)
    private String eventType;

    /** 操作者（返修人 / 检验员 / 撤回人） */
    @Column(name = "operator", length = 32)
    private String operator;

    /** 人类可读的事件细节，如「补焊 · 责任人 周大伟 · 完成 10:20」 */
    @Column(name = "detail", length = 255)
    private String detail;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getEventName() {
        return Dict.reworkEventName(eventType);
    }

    @Transient
    public String getEventColor() {
        return Dict.reworkEventColor(eventType);
    }
}
