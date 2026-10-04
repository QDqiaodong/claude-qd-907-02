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
 * 返修更正说明：历史证据不可改写，误填只能追加更正。
 *
 * <p>返修完成结果在复检创建前可以直接撤回重填；一旦复检单已经创建，处置记录与复检
 * 结论都被冻结，任何修正只能往本表追加一条说明（target 区分是更正返修处置还是复检结论），
 * 原值与时间戳原样保留，时间线里同时展示「原始记录 + 后续更正」。</p>
 */
@Entity
@Table(name = "rework_correction")
@Getter
@Setter
public class ReworkCorrection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "rework_id", nullable = false)
    private Long reworkId;

    @Column(name = "round_id", nullable = false)
    private Long roundId;

    /** 更正对象：REWORK 返修处置 / REINSPECT 复检结论 */
    @Column(name = "target_type", length = 16)
    private String targetType;

    /** 更正说明内容（只追加，不提供修改/删除入口） */
    @Column(name = "content", length = 255)
    private String content;

    @Column(name = "operator", length = 32)
    private String operator;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Transient
    public String getTargetTypeName() {
        return Dict.correctionTargetName(targetType);
    }
}
