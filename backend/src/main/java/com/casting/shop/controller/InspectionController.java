package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Inspection;
import com.casting.shop.dto.InspectionRequest;
import com.casting.shop.service.InspectionService;
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
 * 模块五：检验与缺陷。
 *
 * <p>页面形态为「缺陷类型徽章多选 + 结论三选一大按钮」：判返修或报废必须先选至少一类缺陷。</p>
 */
@RestController
@RequestMapping("/api/inspection")
public class InspectionController {

    private final InspectionService service;

    public InspectionController(InspectionService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Inspection>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Inspection> create(@RequestBody InspectionRequest request) {
        return ApiResponse.success("检验单已录入", service.create(request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("检验单已删除", null);
    }
}
