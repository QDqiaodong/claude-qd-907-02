package com.casting.shop.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/** 检验单录入入参（含缺陷清单与结论三选一）。 */
@Data
public class InspectionRequest {

    private String inspectNo;
    private Long castingId;
    private String inspector;
    private BigDecimal hardnessHb;
    private String verdict;
    private String remark;
    private List<DefectRequest> defects = new ArrayList<>();
}
