package com.casting.shop.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 返修完成登记入参：责任人、处置方式、完成时间由现场填写（完成时间缺省取当前时间），
 * 外加处置说明。复检单创建之前可以撤回本轮完成结果后重新登记。
 */
@Data
public class ReworkCompleteRequest {

    /** 处置方式 5 选 1：WELD / GRIND / IMPREGNATION / RE_MACHINE / SALVAGE */
    @NotBlank(message = "处置方式不能为空")
    private String method;

    /** 返修责任人 */
    @NotBlank(message = "返修责任人不能为空")
    private String responsiblePerson;

    /** 处置说明 */
    @NotBlank(message = "处置说明不能为空")
    private String note;

    /** 登记操作人（提交账号），用于并发冲突时提示是谁先提交成功 */
    private String operator;
}
