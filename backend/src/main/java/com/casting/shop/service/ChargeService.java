package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Charge;
import com.casting.shop.domain.ChargeItem;
import com.casting.shop.domain.Heat;
import com.casting.shop.dto.ChargeAdjustRequest;
import com.casting.shop.dto.ChargeItemRequest;
import com.casting.shop.dto.ChargeRequest;
import com.casting.shop.repository.ChargeItemRepository;
import com.casting.shop.repository.ChargeRepository;
import com.casting.shop.repository.HeatRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 炉料配料。
 *
 * <p>规则：配料单号唯一；每种炉料重量必须为正且与浮动上限比较；同一配料单内重复材料合并；
 * 配料合计重量与炉次出炉重量的偏差不得超过 {@link Dict#MAX_CHARGE_DEVIATION_RATE}%。</p>
 */
@Service
public class ChargeService {

    private final ChargeRepository chargeRepository;
    private final ChargeItemRepository itemRepository;
    private final HeatRepository heatRepository;

    public ChargeService(ChargeRepository chargeRepository, ChargeItemRepository itemRepository,
                         HeatRepository heatRepository) {
        this.chargeRepository = chargeRepository;
        this.itemRepository = itemRepository;
        this.heatRepository = heatRepository;
    }

    public List<Charge> list() {
        return chargeRepository.findAllByOrderByIdDesc();
    }

    public Charge get(Long id) {
        return chargeRepository.findById(id)
                .orElseThrow(() -> new BizException("配料单不存在：" + id));
    }

    /** 配料单 + 明细 + 目标重量/偏差校验需要的上下文 */
    public Map<String, Object> detail(Long id) {
        Charge charge = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("charge", charge);
        data.put("items", itemRepository.findByChargeIdOrderByIdAsc(id));
        data.put("heat", charge.getHeatId() == null ? null : heatRepository.findById(charge.getHeatId()).orElse(null));
        data.put("maxDeviationRate", Dict.MAX_CHARGE_DEVIATION_RATE);
        return data;
    }

    @Transactional
    public Charge create(ChargeRequest request) {
        if (!StringUtils.hasText(request.getChargeNo())) {
            throw new BizException("配料单号不能为空");
        }
        if (chargeRepository.existsByChargeNo(request.getChargeNo())) {
            throw new BizException("配料单号已存在：" + request.getChargeNo());
        }
        if (request.getItems() == null || request.getItems().isEmpty()) {
            throw new BizException("配料单至少要有一条明细");
        }
        Heat heat = heatRepository.findById(request.getHeatId())
                .orElseThrow(() -> new BizException("炉次不存在：" + request.getHeatId()));

        Charge charge = new Charge();
        charge.setChargeNo(request.getChargeNo());
        charge.setHeatId(heat.getId());
        charge.setHeatNo(heat.getHeatNo());
        charge.setOperator(request.getOperator());
        charge.setRemark(request.getRemark());
        charge.setChargeTime(LocalDateTime.now());
        Charge saved = chargeRepository.save(charge);

        for (ChargeItemRequest item : request.getItems()) {
            mergeInto(saved, heat, item);
        }
        // 重新读一遍：重复品种已经被合并到同一行，直接用入参列表会重复累加
        recalc(saved, heat, itemRepository.findByChargeIdOrderByIdAsc(saved.getId()));
        return saved;
    }

    /** 逐项 ± 微调：某一品种按 delta kg 增减后重算占比与合计，并重新校验偏差 */
    @Transactional
    public Charge adjust(Long id, ChargeAdjustRequest request) {
        Charge charge = get(id);
        Heat heat = heatRepository.findById(charge.getHeatId()).orElse(null);
        if (request == null || request.getDelta() == null || request.getDelta().compareTo(BigDecimal.ZERO) == 0) {
            throw new BizException("微调量不能为 0");
        }
        if (!Dict.isValidChargeMaterial(request.getMaterialCode())) {
            throw new BizException("炉料品种非法：" + request.getMaterialCode());
        }
        ChargeItem item = itemRepository.findFirstByChargeIdAndMaterialCode(id, request.getMaterialCode());
        if (item == null) {
            throw new BizException("配料单 " + charge.getChargeNo() + " 中没有「"
                    + Dict.chargeMaterialName(request.getMaterialCode()) + "」，无法微调");
        }
        BigDecimal weight = nvl(item.getWeightKg()).add(request.getDelta());
        checkItemWeight(request.getMaterialCode(), weight);
        item.setWeightKg(weight.setScale(2, RoundingMode.HALF_UP));
        itemRepository.save(item);
        recalc(charge, heat, itemRepository.findByChargeIdOrderByIdAsc(id));
        return charge;
    }

    @Transactional
    public void delete(Long id) {
        Charge charge = get(id);
        itemRepository.deleteByChargeId(id);
        chargeRepository.deleteById(id);
    }

    /** 配料看板：可配料的炉次（已登记出炉重量或计划重量）+ 六个炉料品种 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Heat> heats = heatRepository.findAllByOrderByIdAsc().stream()
                .filter(h -> h.getTapWeight() != null && h.getTapWeight().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        data.put("heats", heats);
        List<Map<String, Object>> materials = new ArrayList<>();
        for (String code : Dict.CHARGE_MATERIALS) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", code);
            m.put("name", Dict.chargeMaterialName(code));
            m.put("color", Dict.chargeMaterialColor(code));
            materials.add(m);
        }
        data.put("materials", materials);
        data.put("maxDeviationRate", Dict.MAX_CHARGE_DEVIATION_RATE);
        return data;
    }

    // ---------------- 内部：合并 / 重算 / 校验 ----------------

    /** 同一配料单重复配料同一材料要合并：已有该品种就在原行上累加 */
    private ChargeItem mergeInto(Charge charge, Heat heat, ChargeItemRequest item) {
        if (!Dict.isValidChargeMaterial(item.getMaterialCode())) {
            throw new BizException("炉料品种非法（六选一）：" + item.getMaterialCode());
        }
        BigDecimal weight = item.getWeightKg();
        checkItemWeight(item.getMaterialCode(), weight);
        ChargeItem exist = itemRepository.findFirstByChargeIdAndMaterialCode(charge.getId(), item.getMaterialCode());
        if (exist != null) {
            BigDecimal merged = nvl(exist.getWeightKg()).add(weight);
            checkItemWeight(item.getMaterialCode(), merged);
            exist.setWeightKg(merged.setScale(2, RoundingMode.HALF_UP));
            if (StringUtils.hasText(item.getRemark())) {
                exist.setRemark(item.getRemark());
            }
            return itemRepository.save(exist);
        }
        ChargeItem row = new ChargeItem();
        row.setChargeId(charge.getId());
        row.setMaterialCode(item.getMaterialCode());
        row.setMaterialName(Dict.chargeMaterialName(item.getMaterialCode()));
        row.setWeightKg(weight.setScale(2, RoundingMode.HALF_UP));
        row.setRemark(item.getRemark());
        return itemRepository.save(row);
    }

    /** 重算合计重量、各品种占比，并校验与炉次目标重量的偏差 */
    private void recalc(Charge charge, Heat heat, List<ChargeItem> items) {
        BigDecimal total = BigDecimal.ZERO;
        for (ChargeItem item : items) {
            total = total.add(nvl(item.getWeightKg()));
        }
        BigDecimal target = targetWeight(heat);
        if (total.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("配料合计重量必须大于 0 kg");
        }
        for (ChargeItem item : items) {
            item.setRatio(nvl(item.getWeightKg())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(total, 2, RoundingMode.HALF_UP));
            itemRepository.save(item);
        }
        BigDecimal rate = total.subtract(target).multiply(BigDecimal.valueOf(100))
                .divide(target, 2, RoundingMode.HALF_UP);
        if (rate.abs().compareTo(Dict.MAX_CHARGE_DEVIATION_RATE) > 0) {
            throw new BizException("配料合计 " + total.setScale(2, RoundingMode.HALF_UP)
                    + "kg 与炉次目标重量 " + target.setScale(2, RoundingMode.HALF_UP)
                    + "kg 偏差 " + rate.abs().setScale(2, RoundingMode.HALF_UP)
                    + "%，超过允许比例 " + Dict.MAX_CHARGE_DEVIATION_RATE + "%");
        }
        charge.setTotalWeight(total.setScale(2, RoundingMode.HALF_UP));
        charge.setTargetWeight(target.setScale(2, RoundingMode.HALF_UP));
        charge.setDeviationRate(rate);
        charge.setItemCount(items.size());
        chargeRepository.save(charge);
    }

    /** 目标重量：优先取炉次出炉重量，还没出炉就取计划重量 */
    private BigDecimal targetWeight(Heat heat) {
        if (heat == null) {
            throw new BizException("配料单未关联有效炉次");
        }
        BigDecimal target = heat.getTapWeight() != null && heat.getTapWeight().compareTo(BigDecimal.ZERO) > 0
                ? heat.getTapWeight()
                : heat.getPlanWeight();
        if (target == null || target.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 既没有出炉重量也没有计划重量，无法确定配料目标重量");
        }
        return target;
    }

    private void checkItemWeight(String materialCode, BigDecimal weight) {
        if (weight == null || weight.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("炉料「" + Dict.chargeMaterialName(materialCode) + "」重量必须大于 0 kg");
        }
        if (weight.compareTo(Dict.MAX_ITEM_WEIGHT) > 0) {
            throw new BizException("炉料「" + Dict.chargeMaterialName(materialCode) + "」单次不得超过 "
                    + Dict.MAX_ITEM_WEIGHT + " kg，当前 " + weight + "kg");
        }
    }

    private static BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
