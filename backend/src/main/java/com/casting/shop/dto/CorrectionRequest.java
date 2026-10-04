package com.casting.shop.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 追加更正说明入参。历史证据不可改写：复检创建后处置/结论都被冻结，
 * 误填只能用本入参追加一条更正（target 区分更正返修处置 REWORK 还是复检结论 REINSPECT）。
 */
@Data
public class CorrectionRequest {

    /** 更正对象：REWORK / REINSPECT */
    @NotBlank(message = "更正对象不能为空")
    private String target;

    @NotBlank(message = "更正说明内容不能为空")
    private String content;

    private String operator;
}
