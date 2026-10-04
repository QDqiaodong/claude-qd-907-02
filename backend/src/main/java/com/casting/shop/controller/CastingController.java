package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Casting;
import com.casting.shop.dto.CastingRequest;
import com.casting.shop.service.CastingService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块四：铸件卡。
 *
 * <p>页面形态为「铸件编号大卡片 + 只读派生字段」：卡面上的炉次号 / 炉次材质 / 模具编号
 * 都不是 casting 表里的列，而是 Casting 实体上 {@code @SQLSelect} 那条 join SQL 查出来的。</p>
 */
@RestController
@RequestMapping("/api/casting")
public class CastingController {

    private final CastingService service;

    public CastingController(CastingService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Casting>> list() {
        return ApiResponse.success(service.list());
    }

    /** findById 走 @SQLSelect，返回的实体自带 join 出来的只读派生字段 */
    @GetMapping("/{id}")
    public ApiResponse<Casting> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @GetMapping("/{id}/detail")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id) {
        return ApiResponse.success(service.detail(id));
    }

    @PostMapping
    public ApiResponse<Casting> create(@RequestBody CastingRequest request) {
        return ApiResponse.success("铸件卡已建立", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Casting> update(@PathVariable Long id, @RequestBody CastingRequest request) {
        return ApiResponse.success("铸件卡已更新", service.update(id, request));
    }

    @PostMapping("/{id}/status")
    public ApiResponse<Casting> transition(@PathVariable Long id, @RequestParam String status) {
        return ApiResponse.success("铸件状态已流转", service.transition(id, status));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("铸件卡已删除", null);
    }
}
