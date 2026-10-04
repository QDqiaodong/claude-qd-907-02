package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Furnace;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.FurnaceRequest;
import com.casting.shop.dto.HeatRequest;
import com.casting.shop.dto.TapRequest;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.repository.HeatRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 熔炼炉与炉次。
 *
 * <p>规则：炉号唯一、炉次号唯一；熔炼温度必须落在材质允许区间；出炉重量必须为正；
 * 炉次阶段按 装料→熔炼→精炼→出炉→已浇完 逐级流转，跳阶段或越过未登记出炉重量都拦 400。</p>
 */
@Service
public class SmeltService {

    private final FurnaceRepository furnaceRepository;
    private final HeatRepository heatRepository;

    public SmeltService(FurnaceRepository furnaceRepository, HeatRepository heatRepository) {
        this.furnaceRepository = furnaceRepository;
        this.heatRepository = heatRepository;
    }

    // ---------------- 熔炼炉 ----------------

    public List<Furnace> furnaces() {
        return furnaceRepository.findAllByOrderByIdAsc();
    }

    public Furnace furnace(Long id) {
        return furnaceRepository.findById(id)
                .orElseThrow(() -> new BizException("熔炼炉不存在：" + id));
    }

    @Transactional
    public Furnace createFurnace(FurnaceRequest request) {
        if (!StringUtils.hasText(request.getFurnaceNo())) {
            throw new BizException("炉号不能为空");
        }
        if (furnaceRepository.existsByFurnaceNo(request.getFurnaceNo())) {
            throw new BizException("炉号已存在：" + request.getFurnaceNo());
        }
        if (!Dict.isValidFurnaceType(request.getFurnaceType())) {
            throw new BizException("炉型非法：" + request.getFurnaceType());
        }
        if (request.getCapacityKg() == null || request.getCapacityKg().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("额定容量必须大于 0 kg");
        }
        Furnace furnace = new Furnace();
        applyFurnace(furnace, request);
        // 默认值放在这里，实体字段不带默认值，避免 PUT 局部更新被刷回
        if (furnace.getStatus() == null) {
            furnace.setStatus("IDLE");
        }
        furnace.setCreatedAt(LocalDateTime.now());
        return furnaceRepository.save(furnace);
    }

    @Transactional
    public Furnace updateFurnace(Long id, FurnaceRequest request) {
        Furnace furnace = furnace(id);
        if (StringUtils.hasText(request.getFurnaceNo())
                && !request.getFurnaceNo().equals(furnace.getFurnaceNo())
                && furnaceRepository.existsByFurnaceNo(request.getFurnaceNo())) {
            throw new BizException("炉号已存在：" + request.getFurnaceNo());
        }
        if (request.getFurnaceType() != null && !Dict.isValidFurnaceType(request.getFurnaceType())) {
            throw new BizException("炉型非法：" + request.getFurnaceType());
        }
        if (request.getStatus() != null && !Dict.isValidFurnaceStatus(request.getStatus())) {
            throw new BizException("熔炼炉状态非法：" + request.getStatus());
        }
        if (request.getCapacityKg() != null && request.getCapacityKg().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("额定容量必须大于 0 kg");
        }
        applyFurnacePartial(furnace, request);
        return furnaceRepository.save(furnace);
    }

    @Transactional
    public void deleteFurnace(Long id) {
        Furnace furnace = furnace(id);
        long running = heatRepository.findByFurnaceIdOrderByIdAsc(id).stream()
                .filter(h -> h.getStage() != null && !"POURED".equals(h.getStage()))
                .count();
        if (running > 0) {
            throw new BizException("熔炼炉 " + furnace.getFurnaceNo() + " 尚有 " + running + " 个未浇完的炉次，不能删除");
        }
        furnaceRepository.deleteById(id);
    }

    // ---------------- 炉次 ----------------

    public List<Heat> heats() {
        return heatRepository.findAllByOrderByIdAsc();
    }

    public Heat heat(Long id) {
        return heatRepository.findById(id)
                .orElseThrow(() -> new BizException("炉次不存在：" + id));
    }

    @Transactional
    public Heat createHeat(HeatRequest request) {
        if (!StringUtils.hasText(request.getHeatNo())) {
            throw new BizException("炉次号不能为空");
        }
        if (heatRepository.existsByHeatNo(request.getHeatNo())) {
            throw new BizException("炉次号已存在：" + request.getHeatNo());
        }
        if (!Dict.isValidMaterial(request.getAlloy())) {
            throw new BizException("材质非法，只能五选一：" + request.getAlloy());
        }
        checkMeltTemp(request.getAlloy(), request.getMeltTemp());
        if (request.getTapWeight() != null) {
            checkTapWeight(request.getAlloy(), request.getTapWeight());
        }
        Furnace furnace = request.getFurnaceId() == null ? null : furnace(request.getFurnaceId());
        if (furnace != null && !furnace.isUsable()) {
            throw new BizException("熔炼炉 " + furnace.getFurnaceNo() + " 当前为「" + furnace.getStatusName() + "」，不能开新炉次");
        }
        Heat heat = new Heat();
        applyHeat(heat, request);
        if (furnace != null) {
            heat.setFurnaceNo(furnace.getFurnaceNo());
            if ("IDLE".equals(furnace.getStatus())) {
                furnace.setStatus("BUSY");
                furnaceRepository.save(furnace);
            }
        }
        if (heat.getStage() == null) {
            heat.setStage("CHARGING");
        }
        if (!Dict.isValidStage(heat.getStage())) {
            throw new BizException("炉次阶段非法：" + heat.getStage());
        }
        heat.setStartTime(LocalDateTime.now());
        heat.setCreatedAt(LocalDateTime.now());
        return heatRepository.save(heat);
    }

    @Transactional
    public Heat updateHeat(Long id, HeatRequest request) {
        Heat heat = heat(id);
        String alloy = StringUtils.hasText(request.getAlloy()) ? request.getAlloy() : heat.getAlloy();
        if (StringUtils.hasText(request.getAlloy()) && !Dict.isValidMaterial(request.getAlloy())) {
            throw new BizException("材质非法，只能五选一：" + request.getAlloy());
        }
        if (StringUtils.hasText(request.getHeatNo()) && !request.getHeatNo().equals(heat.getHeatNo())
                && heatRepository.existsByHeatNo(request.getHeatNo())) {
            throw new BizException("炉次号已存在：" + request.getHeatNo());
        }
        checkMeltTemp(alloy, request.getMeltTemp());
        if (request.getTapWeight() != null) {
            checkTapWeight(alloy, request.getTapWeight());
        }
        if (StringUtils.hasText(request.getStage()) && !Dict.isValidStage(request.getStage())) {
            throw new BizException("炉次阶段非法：" + request.getStage());
        }
        if (request.getFurnaceId() != null) {
            Furnace furnace = furnace(request.getFurnaceId());
            heat.setFurnaceId(furnace.getId());
            heat.setFurnaceNo(furnace.getFurnaceNo());
        }
        applyHeatPartial(heat, request);
        return heatRepository.save(heat);
    }

    /** 记录出炉温度与出炉重量（只在精炼 / 出炉阶段允许登记） */
    @Transactional
    public Heat tap(Long id, TapRequest request) {
        Heat heat = heat(id);
        String stage = heat.getStage() == null ? "CHARGING" : heat.getStage();
        if (!"REFINING".equals(stage) && !"TAPPING".equals(stage)) {
            throw new BizException("「" + Dict.stageName(stage) + "」阶段不能登记出炉，请先推进到精炼或出炉阶段");
        }
        int tapTemp = request.getTapTemp() == null ? 0 : request.getTapTemp();
        int low = Dict.tapLow(heat.getAlloy());
        int high = Dict.tapHigh(heat.getAlloy());
        if (tapTemp <= 0) {
            throw new BizException("出炉温度必须大于 0 ℃");
        }
        if (tapTemp < low || tapTemp > high) {
            throw new BizException("出炉温度 " + tapTemp + "℃ 超出材质「" + Dict.materialName(heat.getAlloy())
                    + "」允许区间 " + low + "~" + high + "℃");
        }
        BigDecimal tapWeight = request.getTapWeight();
        checkTapWeight(heat.getAlloy(), tapWeight);
        if (heat.getFurnaceId() != null) {
            Furnace furnace = furnace(heat.getFurnaceId());
            if (furnace.getCapacityKg() != null && tapWeight.compareTo(furnace.getCapacityKg()) > 0) {
                throw new BizException("出炉重量 " + tapWeight + "kg 超出熔炼炉 " + furnace.getFurnaceNo()
                        + " 的额定容量 " + furnace.getCapacityKg() + "kg");
            }
        }
        heat.setTapTemp(tapTemp);
        heat.setTapWeight(tapWeight);
        heat.setTapTime(LocalDateTime.now());
        return heatRepository.save(heat);
    }

    /** 阶段推进：一次只能推进一级，跳阶段直接拦截 */
    @Transactional
    public Heat advance(Long id, String targetStage) {
        Heat heat = heat(id);
        if (!Dict.isValidStage(targetStage)) {
            throw new BizException("目标阶段非法：" + targetStage);
        }
        String current = heat.getStage() == null ? "CHARGING" : heat.getStage();
        if (current.equals(targetStage)) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 已经处于「" + Dict.stageName(targetStage) + "」阶段");
        }
        int currentIndex = Dict.stageIndex(current);
        int targetIndex = Dict.stageIndex(targetStage);
        if (targetIndex != currentIndex + 1) {
            throw new BizException("不能跳阶段：当前「" + Dict.stageName(current)
                    + "」，下一阶段只能是「" + Dict.stageName(Dict.HEAT_FLOW.get(Math.min(currentIndex + 1, 4))) + "」");
        }
        if ("REFINING".equals(targetStage) && heat.getMeltTemp() == null) {
            throw new BizException("进入精炼前必须记录熔炼温度");
        }
        if ("POURED".equals(targetStage)) {
            if (heat.getTapWeight() == null || heat.getTapWeight().compareTo(BigDecimal.ZERO) <= 0) {
                throw new BizException("炉次 " + heat.getHeatNo() + " 尚未登记出炉重量，不能置为已浇完");
            }
        }
        heat.setStage(targetStage);
        Heat saved = heatRepository.save(heat);
        if ("POURED".equals(targetStage) && saved.getFurnaceId() != null) {
            Furnace furnace = furnace(saved.getFurnaceId());
            long open = heatRepository.findByFurnaceIdOrderByIdAsc(furnace.getId()).stream()
                    .filter(h -> h.getStage() != null && !"POURED".equals(h.getStage()))
                    .count();
            if (open == 0) {
                furnace.setStatus("IDLE");
                furnaceRepository.save(furnace);
            }
        }
        return saved;
    }

    @Transactional
    public void deleteHeat(Long id) {
        Heat heat = heat(id);
        if ("MELTING".equals(heat.getStage()) || "REFINING".equals(heat.getStage())
                || "TAPPING".equals(heat.getStage())) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 正在「" + Dict.stageName(heat.getStage()) + "」，不能删除");
        }
        heatRepository.deleteById(id);
    }

    /** 熔炼看板：炉 → 该炉下面的炉次（前端做竖条温度带） */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("furnaces", furnaceRepository.findAllByOrderByIdAsc());
        data.put("heats", heatRepository.findAllByOrderByIdAsc());
        List<Map<String, Object>> stages = new ArrayList<>();
        for (String stage : Dict.HEAT_FLOW) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", stage);
            item.put("name", Dict.stageName(stage));
            item.put("temp", Dict.stageTemp(stage));
            item.put("color", Dict.stageColor(stage));
            item.put("count", heatRepository.countByStage(stage));
            stages.add(item);
        }
        data.put("stages", stages);
        return data;
    }

    // ---------------- 校验工具 ----------------

    private void checkMeltTemp(String alloy, Integer meltTemp) {
        if (meltTemp == null) {
            return;
        }
        int low = Dict.meltLow(alloy);
        int high = Dict.meltHigh(alloy);
        if (meltTemp < low || meltTemp > high) {
            throw new BizException("熔炼温度 " + meltTemp + "℃ 超出材质「" + Dict.materialName(alloy)
                    + "」允许区间 " + low + "~" + high + "℃");
        }
    }

    private void checkTapWeight(String alloy, BigDecimal tapWeight) {
        if (tapWeight == null) {
            return;
        }
        if (tapWeight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("出炉重量必须大于 0 kg");
        }
        if (tapWeight.compareTo(Dict.MAX_TAP_WEIGHT) > 0) {
            throw new BizException("出炉重量不得超过 " + Dict.MAX_TAP_WEIGHT + " kg");
        }
    }

    private void applyFurnace(Furnace furnace, FurnaceRequest request) {
        furnace.setFurnaceNo(request.getFurnaceNo());
        furnace.setFurnaceName(request.getFurnaceName());
        furnace.setFurnaceType(request.getFurnaceType());
        furnace.setCapacityKg(request.getCapacityKg());
        furnace.setMaxTemp(request.getMaxTemp());
        furnace.setPowerKw(request.getPowerKw());
        furnace.setStatus(request.getStatus());
        furnace.setNote(request.getNote());
    }

    private void applyFurnacePartial(Furnace furnace, FurnaceRequest request) {
        ifNotNull(request.getFurnaceNo(), furnace::setFurnaceNo);
        ifNotNull(request.getFurnaceName(), furnace::setFurnaceName);
        ifNotNull(request.getFurnaceType(), furnace::setFurnaceType);
        ifNotNull(request.getCapacityKg(), furnace::setCapacityKg);
        ifNotNull(request.getMaxTemp(), furnace::setMaxTemp);
        ifNotNull(request.getPowerKw(), furnace::setPowerKw);
        ifNotNull(request.getStatus(), furnace::setStatus);
        ifNotNull(request.getNote(), furnace::setNote);
    }

    private void applyHeat(Heat heat, HeatRequest request) {
        heat.setHeatNo(request.getHeatNo());
        heat.setFurnaceId(request.getFurnaceId());
        heat.setAlloy(request.getAlloy());
        heat.setPlanWeight(request.getPlanWeight());
        heat.setMeltTemp(request.getMeltTemp());
        heat.setRefineTemp(request.getRefineTemp());
        heat.setTapTemp(request.getTapTemp());
        heat.setTapWeight(request.getTapWeight());
        heat.setStage(request.getStage());
        heat.setOperator(request.getOperator());
    }

    private void applyHeatPartial(Heat heat, HeatRequest request) {
        ifNotNull(request.getHeatNo(), heat::setHeatNo);
        ifNotNull(request.getAlloy(), heat::setAlloy);
        ifNotNull(request.getPlanWeight(), heat::setPlanWeight);
        ifNotNull(request.getMeltTemp(), heat::setMeltTemp);
        ifNotNull(request.getRefineTemp(), heat::setRefineTemp);
        ifNotNull(request.getTapTemp(), heat::setTapTemp);
        ifNotNull(request.getTapWeight(), heat::setTapWeight);
        ifNotNull(request.getStage(), heat::setStage);
        ifNotNull(request.getOperator(), heat::setOperator);
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
