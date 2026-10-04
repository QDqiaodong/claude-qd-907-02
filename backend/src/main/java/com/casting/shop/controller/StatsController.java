package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.service.StatsService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 模块六：车间报表统计与工艺字典。 */
@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final StatsService service;

    public StatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    public ApiResponse<Map<String, Object>> overview() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/dict")
    public ApiResponse<Map<String, Object>> dict() {
        return ApiResponse.success(service.dict());
    }

    @GetMapping("/furnace")
    public ApiResponse<Object> furnace() {
        return ApiResponse.success(service.furnaceLoad());
    }

    @GetMapping("/casting-status")
    public ApiResponse<Object> castingStatus() {
        return ApiResponse.success(service.castingStatus());
    }

    @GetMapping("/material")
    public ApiResponse<Object> material() {
        return ApiResponse.success(service.material());
    }

    @GetMapping("/mold-life")
    public ApiResponse<Object> moldLife() {
        return ApiResponse.success(service.moldLife());
    }

    @GetMapping("/defect")
    public ApiResponse<Object> defect() {
        return ApiResponse.success(service.defect());
    }

    @GetMapping("/verdict")
    public ApiResponse<Object> verdict() {
        return ApiResponse.success(service.verdict());
    }
}
