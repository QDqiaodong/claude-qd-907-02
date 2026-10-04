package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Charge;
import com.casting.shop.dto.ChargeAdjustRequest;
import com.casting.shop.dto.ChargeRequest;
import com.casting.shop.dto.ChargeSimulateRequest;
import com.casting.shop.service.ChargeService;
import com.casting.shop.service.ChargeSimulateService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块二：炉料配料。
 *
 * <p>页面形态为「配比滑杆条」：每个品种一条横向占比条，合计 100% 标红提示，逐项 ± 微调。
 * 双方案推演挂在同一控制器下：只读试算，不落库。</p>
 */
@RestController
@RequestMapping("/api/charge")
public class ChargeController {

    private final ChargeService service;
    private final ChargeSimulateService simulateService;

    public ChargeController(ChargeService service, ChargeSimulateService simulateService) {
        this.service = service;
        this.simulateService = simulateService;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    /** 双方案推演看板：未出炉炉次（含炉体容量）+ 品种单价 + 默认参数 */
    @GetMapping("/simulate/heats")
    public ApiResponse<Map<String, Object>> simulateHeats() {
        return ApiResponse.success(simulateService.openHeats());
    }

    /** 双方案推演：同一份输入版本一次算出「最低补料成本 / 最小成分偏离」两套方案 */
    @PostMapping("/simulate")
    public ApiResponse<Map<String, Object>> simulate(@RequestBody ChargeSimulateRequest request) {
        return ApiResponse.success(simulateService.simulate(request));
    }

    @GetMapping("/list")
    public ApiResponse<List<Charge>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Charge> create(@RequestBody ChargeRequest request) {
        return ApiResponse.success("配料单已建立", service.create(request));
    }

    /** 逐项 ± 微调 */
    @PostMapping("/{id}/adjust")
    public ApiResponse<Charge> adjust(@PathVariable Long id, @RequestBody ChargeAdjustRequest request) {
        return ApiResponse.success("配比已微调", service.adjust(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("配料单已删除", null);
    }
}
