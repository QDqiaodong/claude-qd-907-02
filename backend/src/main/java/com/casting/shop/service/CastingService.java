package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Casting;
import com.casting.shop.domain.Heat;
import com.casting.shop.domain.Mold;
import com.casting.shop.domain.ReworkOrder;
import com.casting.shop.dto.CastingRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.HeatRepository;
import com.casting.shop.repository.InspectionRepository;
import com.casting.shop.repository.MoldRepository;
import com.casting.shop.repository.ReworkOrderRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 铸件卡。
 *
 * <p>实体的加载走 Casting 上的 {@code @SQLSelect}：findById / 懒加载触发时都会执行那条
 * left join heat / mold 的自定义 SQL，把炉次号、炉次材质、模具编号、模具名称一起查出来。</p>
 *
 * <p>规则：铸件编号唯一；材质五选一；理论重量必须为正；必须挂到已出炉的炉次且材质一致；
 * 状态按 待浇→已浇注→清理→检验→入库 逐级流转（报废为终态）。</p>
 */
@Service
public class CastingService {

    private final CastingRepository castingRepository;
    private final HeatRepository heatRepository;
    private final MoldRepository moldRepository;
    private final MoldService moldService;
    private final InspectionRepository inspectionRepository;
    private final ReworkOrderRepository reworkOrderRepository;

    public CastingService(CastingRepository castingRepository, HeatRepository heatRepository,
                          MoldRepository moldRepository, MoldService moldService,
                          InspectionRepository inspectionRepository,
                          ReworkOrderRepository reworkOrderRepository) {
        this.castingRepository = castingRepository;
        this.heatRepository = heatRepository;
        this.moldRepository = moldRepository;
        this.moldService = moldService;
        this.inspectionRepository = inspectionRepository;
        this.reworkOrderRepository = reworkOrderRepository;
    }

    /** 列表也走 join SQL，页面上每张卡片直接看得到派生字段 */
    public List<Casting> list() {
        return castingRepository.listDerived();
    }

    /** 详情：findById 走 @SQLSelect，返回的实体自带 join 出来的只读派生字段 */
    public Casting get(Long id) {
        return castingRepository.findById(id)
                .orElseThrow(() -> new BizException("铸件卡不存在：" + id));
    }

    public Map<String, Object> detail(Long id) {
        Casting casting = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("casting", casting);
        data.put("defects", List.of());
        data.put("heat", casting.getHeatId() == null ? null : heatRepository.findById(casting.getHeatId()).orElse(null));
        data.put("mold", casting.getMoldId() == null ? null : moldRepository.findById(casting.getMoldId()).orElse(null));
        return data;
    }

    @Transactional
    public Casting create(CastingRequest request) {
        if (!StringUtils.hasText(request.getCastingNo())) {
            throw new BizException("铸件编号不能为空");
        }
        if (castingRepository.existsByCastingNo(request.getCastingNo())) {
            throw new BizException("铸件编号已存在：" + request.getCastingNo());
        }
        if (!Dict.isValidMaterial(request.getMaterial())) {
            throw new BizException("材质非法，只能五选一：" + request.getMaterial());
        }
        if (request.getTheoryWeight() == null || request.getTheoryWeight().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("理论重量必须大于 0 kg");
        }
        Heat heat = request.getHeatId() == null ? null
                : heatRepository.findById(request.getHeatId())
                        .orElseThrow(() -> new BizException("炉次不存在：" + request.getHeatId()));
        if (heat == null) {
            throw new BizException("铸件卡必须挂到一个炉次上");
        }
        // 必须挂到已出炉的炉次
        if (heat.getTapWeight() == null || heat.getTapWeight().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("炉次 " + heat.getHeatNo() + " 尚未出炉（未登记出炉重量），不能排铸件");
        }
        if (!heat.getAlloy().equals(request.getMaterial())) {
            throw new BizException("铸件材质「" + Dict.materialName(request.getMaterial())
                    + "」与炉次 " + heat.getHeatNo() + " 的材质「" + Dict.materialName(heat.getAlloy()) + "」不一致");
        }
        Mold mold = request.getMoldId() == null ? null
                : moldRepository.findById(request.getMoldId())
                        .orElseThrow(() -> new BizException("模具不存在：" + request.getMoldId()));

        Casting casting = new Casting();
        apply(casting, request);
        casting.setStatus("WAIT_POUR");
        casting.setCreatedAt(LocalDateTime.now());
        Casting saved = castingRepository.save(casting);
        // 排产占用一次模具寿命（超寿命 / 报废模具在 consume 里拦）
        if (mold != null) {
            moldService.consume(mold.getId());
        }
        return saved;
    }

    @Transactional
    public Casting update(Long id, CastingRequest request) {
        Casting casting = get(id);
        if (request.getStatus() != null) {
            // 铸件状态只能按工艺链路由系统回写：普通编辑直接写 status 会绕过返修闭环，
            // 造成「铸件已入库/打回但返修证据缺失」，所以一律拒绝（状态流转走专门的 transition）。
            throw new BizException("铸件卡编辑接口不能直接修改状态：待浇→…→入库的流转走状态按钮，"
                    + "返修中的铸件只能在返修队列里登记处置/复检");
        }
        String material = StringUtils.hasText(request.getMaterial()) ? request.getMaterial() : casting.getMaterial();
        if (StringUtils.hasText(request.getMaterial()) && !Dict.isValidMaterial(request.getMaterial())) {
            throw new BizException("材质非法，只能五选一：" + request.getMaterial());
        }
        if (StringUtils.hasText(request.getCastingNo()) && !request.getCastingNo().equals(casting.getCastingNo())
                && castingRepository.existsByCastingNo(request.getCastingNo())) {
            throw new BizException("铸件编号已存在：" + request.getCastingNo());
        }
        if (request.getTheoryWeight() != null && request.getTheoryWeight().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("理论重量必须大于 0 kg");
        }
        if (casting.getHeatId() != null && !material.equals(casting.getHeatAlloy())) {
            throw new BizException("铸件材质「" + Dict.materialName(material)
                    + "」与所属炉次的材质「" + Dict.materialName(casting.getHeatAlloy()) + "」不一致");
        }
        applyPartial(casting, request);
        return castingRepository.save(casting);
    }

    /**
     * 状态流转：必须逐级推进；报废为终态且可以在任一阶段判定（已入库的除外）。
     * 返修支线状态 REWORK / REINSPECT 不能从这里手动进入或离开——它们只属于返修闭环。
     */
    @Transactional
    public Casting transition(Long id, String targetStatus) {
        // 先纯表行锁，再经实体加载器取铸件（见 CastingRepository.lockRowById 的说明）
        Long lockedId = castingRepository.lockRowById(id);
        if (lockedId == null) {
            throw new BizException("铸件卡不存在：" + id);
        }
        Casting casting = get(id);
        if (!Dict.isValidCastingStatus(targetStatus)) {
            throw new BizException("铸件状态非法：" + targetStatus);
        }
        if ("REWORK".equals(targetStatus) || "REINSPECT".equals(targetStatus)) {
            throw new BizException("「" + Dict.castingStatusName(targetStatus) + "」是返修闭环的支线状态，"
                    + "只能由返修处置/复检流程自动推进，不能手动流转");
        }
        String current = casting.getStatus() == null ? "WAIT_POUR" : casting.getStatus();
        if ("REWORK".equals(current) || "REINSPECT".equals(current)) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 正在返修闭环中（"
                    + Dict.castingStatusName(current) + "），必须在返修队列完成处置与复检：合格才能入库、"
                    + "判废由复检终止，不能从这里改状态");
        }
        if (current.equals(targetStatus)) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 已处于「" + Dict.castingStatusName(targetStatus) + "」");
        }
        if ("SCRAPPED".equals(current)) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 已报废，不能再流转");
        }
        if ("STORED".equals(current)) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 已入库，不能再流转");
        }
        if ("SCRAPPED".equals(targetStatus)) {
            if ("WAIT_POUR".equals(current)) {
                throw new BizException("未浇注的铸件不能直接报废，先推进到已浇注以后再判定");
            }
            casting.setStatus("SCRAPPED");
            return castingRepository.save(casting);
        }
        int currentIndex = Dict.castingStageIndex(current);
        int targetIndex = Dict.castingStageIndex(targetStatus);
        if (targetIndex != currentIndex + 1) {
            throw new BizException("不能跳阶段：当前「" + Dict.castingStatusName(current)
                    + "」，下一步只能是「" + Dict.castingStatusName(Dict.CASTING_FLOW.get(currentIndex + 1)) + "」");
        }
        casting.setStatus(targetStatus);
        if ("POURED".equals(targetStatus)) {
            casting.setPourTime(LocalDateTime.now());
        }
        return castingRepository.save(casting);
    }

    @Transactional
    public void delete(Long id) {
        Casting casting = get(id);
        ReworkOrder reworkOrder = reworkOrderRepository.findByCastingId(id).orElse(null);
        if (reworkOrder != null) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 已进入返修闭环（返修单 "
                    + reworkOrder.getReworkNo() + "），不能删除铸件卡，否则返修证据将失去归属");
        }
        if (!inspectionRepository.findByCastingIdOrderByIdDesc(id).isEmpty()) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 已有检验记录，不能删除");
        }
        castingRepository.deleteById(id);
    }

    /** 铸件卡大卡片视图：卡片列表 + 状态/材质字典元数据 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("castings", castingRepository.listDerived());
        data.put("statusStats", castingRepository.statByStatus());
        data.put("materialStats", castingRepository.statByMaterial());
        return data;
    }

    private void apply(Casting casting, CastingRequest request) {
        casting.setCastingNo(request.getCastingNo());
        casting.setCastingName(request.getCastingName());
        casting.setMaterial(request.getMaterial());
        casting.setHeatId(request.getHeatId());
        casting.setMoldId(request.getMoldId());
        casting.setCavityNo(request.getCavityNo());
        casting.setTheoryWeight(request.getTheoryWeight());
        casting.setActualWeight(request.getActualWeight());
        casting.setStatus(request.getStatus());
        casting.setCustomer(request.getCustomer());
        casting.setOperator(request.getOperator());
    }

    private void applyPartial(Casting casting, CastingRequest request) {
        ifNotNull(request.getCastingNo(), casting::setCastingNo);
        ifNotNull(request.getCastingName(), casting::setCastingName);
        ifNotNull(request.getMaterial(), casting::setMaterial);
        ifNotNull(request.getHeatId(), casting::setHeatId);
        ifNotNull(request.getMoldId(), casting::setMoldId);
        ifNotNull(request.getCavityNo(), casting::setCavityNo);
        ifNotNull(request.getTheoryWeight(), casting::setTheoryWeight);
        ifNotNull(request.getActualWeight(), casting::setActualWeight);
        ifNotNull(request.getStatus(), casting::setStatus);
        ifNotNull(request.getCustomer(), casting::setCustomer);
        ifNotNull(request.getOperator(), casting::setOperator);
    }

    private static <T> void ifNotNull(T value, Consumer<T> setter) {
        if (value != null) {
            setter.accept(value);
        }
    }
}
