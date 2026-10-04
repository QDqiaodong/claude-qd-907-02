package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Casting;
import com.casting.shop.domain.Inspection;
import com.casting.shop.dto.DefectRequest;
import com.casting.shop.dto.InspectionRequest;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.DefectRepository;
import com.casting.shop.repository.InspectionRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 检验与缺陷。
 *
 * <p>规则：检验单号唯一；缺陷类型六选一；判定只能合格 / 返修 / 报废；
 * 判返修或报废必须至少一条缺陷记录；判报废的铸件不可再入库；只能对「待检验」的铸件开单。</p>
 *
 * <p>判「返修」不再只是把铸件打回清理：会同时通过 {@link ReworkService} 建立独立返修队列
 * （返修单 + 第 1 轮待办 + 首次缺陷快照引用），铸件进入「返修中」，后续处置/复检只能走返修链路。
 * 复检单是另一张新建的检验单（编号 IR- 前缀），本服务只负责它的查看，不负责创建。</p>
 */
@Service
public class InspectionService {

    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final CastingRepository castingRepository;
    private final DefectAssembler defectAssembler;
    private final ReworkService reworkService;

    public InspectionService(InspectionRepository inspectionRepository, DefectRepository defectRepository,
                             CastingRepository castingRepository, DefectAssembler defectAssembler,
                             ReworkService reworkService) {
        this.inspectionRepository = inspectionRepository;
        this.defectRepository = defectRepository;
        this.castingRepository = castingRepository;
        this.defectAssembler = defectAssembler;
        this.reworkService = reworkService;
    }

    public List<Inspection> list() {
        return inspectionRepository.findAllByOrderByIdDesc();
    }

    public Inspection get(Long id) {
        return inspectionRepository.findById(id)
                .orElseThrow(() -> new BizException("检验单不存在：" + id));
    }

    public Map<String, Object> detail(Long id) {
        Inspection inspection = get(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inspection", inspection);
        data.put("defects", defectRepository.findByInspectIdOrderByIdAsc(id));
        data.put("casting", inspection.getCastingId() == null ? null
                : castingRepository.findById(inspection.getCastingId()).orElse(null));
        return data;
    }

    @Transactional
    public Inspection create(InspectionRequest request) {
        if (!StringUtils.hasText(request.getInspectNo())) {
            throw new BizException("检验单号不能为空");
        }
        if (inspectionRepository.existsByInspectNo(request.getInspectNo())) {
            throw new BizException("检验单号已存在：" + request.getInspectNo());
        }
        if (!Dict.isValidVerdict(request.getVerdict())) {
            throw new BizException("判定结论只能三选一（PASS / REWORK / SCRAP）：" + request.getVerdict());
        }
        List<DefectRequest> defects = request.getDefects() == null ? List.of() : request.getDefects();
        if (!"PASS".equals(request.getVerdict()) && defects.isEmpty()) {
            throw new BizException("判「" + Dict.verdictName(request.getVerdict()) + "」必须至少录入一条缺陷记录");
        }

        if (request.getCastingId() == null) {
            throw new BizException("检验单必须挂到一张铸件卡上");
        }
        // 先对铸件行加锁（纯表 FOR UPDATE，不触发实体上的 join 投影加载器），再取实体
        Long lockedCastingId = castingRepository.lockRowById(request.getCastingId());
        if (lockedCastingId == null) {
            throw new BizException("铸件卡不存在：" + request.getCastingId());
        }
        Casting casting = castingRepository.findById(request.getCastingId())
                .orElseThrow(() -> new BizException("铸件卡不存在：" + request.getCastingId()));
        if (!"INSPECTING".equals(casting.getStatus())) {
            throw new BizException("铸件 " + casting.getCastingNo() + " 当前为「" + Dict.castingStatusName(casting.getStatus())
                    + "」，只有待检验的铸件可以开首次检验单；复检必须在返修队列里对已完成的轮次发起");
        }

        Inspection inspection = new Inspection();
        inspection.setInspectNo(request.getInspectNo());
        inspection.setCastingId(casting.getId());
        inspection.setCastingNo(casting.getCastingNo());
        inspection.setInspector(request.getInspector());
        inspection.setHardnessHb(request.getHardnessHb());
        inspection.setVerdict(request.getVerdict());
        inspection.setRemark(request.getRemark());
        inspection.setInspectTime(LocalDateTime.now());
        Inspection saved = inspectionRepository.save(inspection);

        int count = defectAssembler.saveAll(saved.getId(), defects);
        saved.setDefectCount(count);
        if ("REWORK".equals(saved.getVerdict())) {
            // 判返修：建立独立返修队列（返修单 + 第 1 轮待办），铸件进「返修中」
            reworkService.openFromInspection(casting, saved);
        } else {
            applyVerdictToCasting(casting, saved.getVerdict());
        }
        return inspectionRepository.save(saved);
    }

    @Transactional
    public void delete(Long id) {
        Inspection inspection = get(id);
        String referenced = reworkService.describeSnapshotReference(id);
        if (referenced != null) {
            throw new BizException("检验单 " + inspection.getInspectNo() + " 已作为" + referenced
                    + "被返修链路引用，属于不可删除的缺陷快照证据（如需更正请在返修单里追加说明）");
        }
        defectRepository.deleteByInspectId(id);
        inspectionRepository.deleteById(id);
    }

    /** 检验看板：缺陷类型统计、判定分布、待检验铸件、六类缺陷字典 */
    public Map<String, Object> board() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("inspections", inspectionRepository.findAllByOrderByIdDesc());
        data.put("defectStats", defectRepository.statByDefectType());
        data.put("verdictStats", inspectionRepository.statVerdict());
        List<Casting> waiting = castingRepository.listDerived().stream()
                .filter(c -> "INSPECTING".equals(c.getStatus()))
                .toList();
        data.put("waitingCastings", waiting);
        List<Map<String, Object>> defectTypes = new ArrayList<>();
        for (String type : Dict.DEFECT_TYPES) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("code", type);
            item.put("name", Dict.defectTypeName(type));
            item.put("color", Dict.defectTypeColor(type));
            defectTypes.add(item);
        }
        data.put("defectTypes", defectTypes);
        data.put("severities", List.of(
                Map.of("code", "MINOR", "name", Dict.severityName("MINOR")),
                Map.of("code", "MAJOR", "name", Dict.severityName("MAJOR")),
                Map.of("code", "CRITICAL", "name", Dict.severityName("CRITICAL"))));
        return data;
    }

    /**
     * 首次检验非返修结论回写到铸件卡：合格入库、报废终止。
     * 注意：REWORK 不在这里处理——判返修必须走 {@link ReworkService#openFromInspection} 建立返修队列，
     * 直接改铸件状态会造成「已入库/已回清理但返修证据缺失」，这里也故意不再保留 REWORK 分支。
     */
    private void applyVerdictToCasting(Casting casting, String verdict) {
        switch (verdict) {
            case "PASS" -> casting.setStatus("STORED");
            case "SCRAP" -> casting.setStatus("SCRAPPED");
            default -> throw new BizException("判定结论非法，或返修结论必须走返修队列：" + verdict);
        }
        castingRepository.save(casting);
    }
}
