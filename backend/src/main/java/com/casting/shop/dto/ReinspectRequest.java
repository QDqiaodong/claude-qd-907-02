package com.casting.shop.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 复检入参：只能对上一轮返修完成的轮次发起。复检结论三选一
 * （合格 / 再返修 / 报废），判再返修或报废必须至少录一条本轮缺陷；
 * 复检单是一张<b>新建的检验单</b>（旧检验单照旧可查），其缺陷即下一轮的缺陷快照。
 */
@Data
public class ReinspectRequest {

    /** 结论：PASS / REWORK / SCRAP */
    private String verdict;

    private String inspector;

    private String remark;

    /** 本轮复检发现的缺陷（再返修 / 报废时必填至少一条） */
    private List<DefectRequest> defects = new ArrayList<>();
}
