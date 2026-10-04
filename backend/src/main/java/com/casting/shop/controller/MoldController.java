package com.casting.shop.controller;

import com.casting.shop.common.ApiResponse;
import com.casting.shop.domain.Mold;
import com.casting.shop.dto.MoldRequest;
import com.casting.shop.service.MoldService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 模块三：模具型壳。
 *
 * <p>页面形态为「寿命进度环阵列」：每套模具一个环形进度，剩余次数低变红，超限禁用。</p>
 */
@RestController
@RequestMapping("/api/mold")
public class MoldController {

    private final MoldService service;

    public MoldController(MoldService service) {
        this.service = service;
    }

    @GetMapping("/board")
    public ApiResponse<Map<String, Object>> board() {
        return ApiResponse.success(service.board());
    }

    @GetMapping("/list")
    public ApiResponse<List<Mold>> list() {
        return ApiResponse.success(service.list());
    }

    @GetMapping("/schedulable")
    public ApiResponse<List<Mold>> schedulable() {
        return ApiResponse.success(service.list().stream().filter(Mold::isSchedulable).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Mold> get(@PathVariable Long id) {
        return ApiResponse.success(service.get(id));
    }

    @PostMapping
    public ApiResponse<Mold> create(@RequestBody MoldRequest request) {
        return ApiResponse.success("模具已建档", service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<Mold> update(@PathVariable Long id, @RequestBody MoldRequest request) {
        return ApiResponse.success("模具已更新", service.update(id, request));
    }

    /** 排产一次：已用次数 +1（超寿命 / 报废会被拦） */
    @PostMapping("/{id}/consume")
    public ApiResponse<Mold> consume(@PathVariable Long id) {
        return ApiResponse.success("模具排产次数已累加", service.consume(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ApiResponse.success("模具已删除", null);
    }
}
