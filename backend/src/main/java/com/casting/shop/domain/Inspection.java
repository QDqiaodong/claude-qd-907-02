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
 * 检验单：一张铸件卡的一次检验结论（合格 / 返修 / 报废）与缺陷条数。
 *
 * <p>判返修或报废必须至少一条缺陷记录；判报废后铸件不可再入库。</p>
 */
@Entity
@Table(name = "inspection")
@Getter
@Setter
public class Inspection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 检验单号，唯一 */
    @Column(name = "inspect_no", nullable = false, unique = true, length = 24)
    private String inspectNo;

    @Column(name = "casting_id")
    private Long castingId;

    @Column(name = "casting_no", length = 24)
    private String castingNo;

    /** 已记录的缺陷条数 */
    @Column(name = "defect_count")
    private Integer defectCount;

    /** 结论：PASS / REWORK / SCRAP */
    @Column(name = "verdict", length = 16)
    private String verdict;

    @Column(name = "hardness_hb")
    private BigDecimal hardnessHb;

    @Column(name = "inspector", length = 32)
    private String inspector;

    @Column(name = "remark", length = 128)
    private String remark;

    @Column(name = "inspect_time")
    private LocalDateTime inspectTime;

    @Transient
    public String getVerdictName() {
        return Dict.verdictName(verdict);
    }

    @Transient
    public String getVerdictColor() {
        return Dict.verdictColor(verdict);
    }
}
