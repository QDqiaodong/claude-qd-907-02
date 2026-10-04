package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.ReworkCorrection;
import com.casting.shop.domain.ReworkRound;
import com.casting.shop.dto.CorrectionRequest;
import com.casting.shop.dto.ReinspectRequest;
import com.casting.shop.dto.ReworkCompleteRequest;
import com.casting.shop.service.ReworkService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块七：返修处置与复检闭环。
 *
 * <p>链路：首次检验判返修 → 独立返修队列（返修单 + 轮次待办，首次缺陷快照留存）→
 * 登记返修完成（责任人 / 处置方式 / 完成时间 / 处置说明）→ 基于上一轮缺陷发起复检
 * → 合格入库 / 判废永久终止 / 再返修保留旧轮生成下一轮。</p>
 *
 * <p>修正规则：复检创建前可撤回本轮完成结果；复检创建后只允许追加更正说明。
 * 所有写接口在数据库行锁内判定状态，同一轮并发提交只成功一次。</p>
 */
@RestController
@RequestMapping("/api/rework")
public class ReworkController {

    private final ReworkService service;

    public ReworkController(ReworkService service) {
        this.service = service;
    }

    /** 返修看板：按阶段（返修中 / 待复检 / 已入库 / 已终止）定位积压 */
    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board(@RequestParam(required = false) String stage) {
        return ApiResponse.success(service.board(stage));
    }

    /** 详情：首次缺陷快照、每轮处置与复检结论、更正说明、事件时间线 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    /** 登记返修完成（责任人 / 处置方式 / 处置说明；完成时间取服务端时间） */
    @PostMapping("/{id}/complete")
    public ApiResponse<ReworkRound> complete(@PathVariable Long id,
                                             @Valid @RequestBody ReworkCompleteRequest request) {
        return ApiResponse.success("返修完成已登记，铸件转入待复检", service.complete(id, request));
    }

    /** 撤回本轮完成结果（仅复检单创建前允许） */
    @PostMapping("/{id}/withdraw")
    public ApiResponse<ReworkRound> withdraw(@PathVariable Long id,
                                             @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        return ApiResponse.success("本轮完成结果已撤回，可重新登记", service.withdraw(id, operator));
    }

    /** 发起复检：合格 / 再返修 / 报废，复检单为新建检验单 */
    @PostMapping("/{id}/reinspect")
    public ApiResponse<Map<String, Object>> reinspect(@PathVariable Long id,
                                                      @RequestBody ReinspectRequest request) {
        Map<String, Object> result = service.reinspect(id, request);
        String verdict = request.getVerdict();
        String message = switch (verdict) {
            case "PASS" -> "复检合格，铸件已入库，返修闭环结束";
            case "SCRAP" -> "复检判废，返修链永久终止";
            case "REWORK" -> "复检仍需返修，已保留本轮记录并生成下一轮待办";
            default -> "复检已登记";
        };
        return ApiResponse.success(message, result);
    }

    /** 复检创建后的唯一修正方式：追加更正说明（不改写任何历史字段） */
    @PostMapping("/{id}/correct")
    public ApiResponse<ReworkCorrection> correct(@PathVariable Long id,
                                                 @Valid @RequestBody CorrectionRequest request) {
        return ApiResponse.success("更正说明已追加，原始记录保留不变", service.correct(id, request));
    }
}
