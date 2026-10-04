package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Furnace;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.FurnaceLoadDTO;
import com.casting.shop.dto.FurnaceRequest;
import com.casting.shop.dto.HeatRequest;
import com.casting.shop.dto.TapRequest;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.service.SmeltService;
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
 * 模块一：熔炼炉与炉次。
 *
 * <p>页面形态为「炉次竖条温度带」：点竖条改阶段（一次一级），在精炼/出炉阶段登记出炉温度与重量。</p>
 */
@RestController
@RequestMapping("/api/smelt")
public class SmeltController {

    private final SmeltService service;
    private final FurnaceRepository furnaceRepository;

    public SmeltController(SmeltService service, FurnaceRepository furnaceRepository) {
        this.service = service;
        this.furnaceRepository = furnaceRepository;
    }

    /** 熔炼看板：炉 + 炉次 + 五个阶段的元信息 */
    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/furnaces")
    public ApiResponse<List<Furnace>> furnaces() {
        return ApiResponse.success(service.furnaces());
    }

    @GetMapping("/furnaces/{id}")
    public ApiResponse<Furnace> furnace(@PathVariable Long id) {
        return ApiResponse.success(service.furnace(id));
    }

    @PostMapping("/furnaces")
    public ApiResponse<Furnace> createFurnace(@RequestBody FurnaceRequest request) {
        return ApiResponse.success("熔炼炉已建档", service.createFurnace(request));
    }

    @PutMapping("/furnaces/{id}")
    public ApiResponse<Furnace> updateFurnace(@PathVariable Long id, @RequestBody FurnaceRequest request) {
        return ApiResponse.success("熔炼炉已更新", service.updateFurnace(id, request));
    }

    @DeleteMapping("/furnaces/{id}")
    public ApiResponse<Void> deleteFurnace(@PathVariable Long id) {
        service.deleteFurnace(id);
        return ApiResponse.success("熔炼炉已删除", null);
    }

    @GetMapping("/heats")
    public ApiResponse<List<Heat>> heats() {
        return ApiResponse.success(service.heats());
    }

    @GetMapping("/heats/{id}")
    public ApiResponse<Heat> heat(@PathVariable Long id) {
        return ApiResponse.success(service.heat(id));
    }

    @GetMapping("/heats/furnace/{furnaceId}")
    public ApiResponse<List<Heat>> heatsOfFurnace(@PathVariable Long furnaceId) {
        service.furnace(furnaceId);
        return ApiResponse.success(service.heats().stream()
                .filter(h -> furnaceId.equals(h.getFurnaceId())).toList());
    }

    @PostMapping("/heats")
    public ApiResponse<Heat> createHeat(@RequestBody HeatRequest request) {
        return ApiResponse.success("炉次已开", service.createHeat(request));
    }

    @PutMapping("/heats/{id}")
    public ApiResponse<Heat> updateHeat(@PathVariable Long id, @RequestBody HeatRequest request) {
        return ApiResponse.success("炉次已更新", service.updateHeat(id, request));
    }

    @DeleteMapping("/heats/{id}")
    public ApiResponse<Void> deleteHeat(@PathVariable Long id) {
        service.deleteHeat(id);
        return ApiResponse.success("炉次已删除", null);
    }

    /** 点竖条改阶段：一次只能推进一级 */
    @PostMapping("/heats/{id}/stage")
    public ApiResponse<Heat> advance(@PathVariable Long id, @RequestParam String targetStage) {
        return ApiResponse.success("阶段已推进", service.advance(id, targetStage));
    }

    /** 记录出炉温度 / 出炉重量 */
    @PostMapping("/heats/{id}/tap")
    public ApiResponse<Heat> tap(@PathVariable Long id, @RequestBody TapRequest request) {
        return ApiResponse.success("出炉信息已登记", service.tap(id, request));
    }

    /** 熔炼炉负荷统计（JPQL ①） */
    @GetMapping("/furnace-load")
    public ApiResponse<List<FurnaceLoadDTO>> furnaceLoad() {
        return ApiResponse.success(furnaceRepository.statFurnaceLoad());
    }
}
