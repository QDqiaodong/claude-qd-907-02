package com.casting.shop.service;

import com.casting.shop.common.BizException;
import com.casting.shop.common.Dict;
import com.casting.shop.domain.Defect;
import com.casting.shop.dto.DefectRequest;
import com.casting.shop.repository.DefectRepository;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 缺陷条目装配：首次检验与每一轮复检共用同一套缺陷校验与落库规则——
 * 类型六选一、严重度三选一、个数必须大于 0。
 *
 * <p>抽成独立组件是为了让检验与返修两个服务复用同一份规则，避免复检链路口径漂移。</p>
 */
@Component
public class DefectAssembler {

    private final DefectRepository defectRepository;

    public DefectAssembler(DefectRepository defectRepository) {
        this.defectRepository = defectRepository;
    }

    /** 校验并落库一张检验单下的全部缺陷，返回写入条数 */
    public int saveAll(Long inspectId, List<DefectRequest> defects) {
        int count = 0;
        if (defects != null) {
            for (DefectRequest item : defects) {
                defectRepository.save(toDefect(inspectId, item));
                count++;
            }
        }
        return count;
    }

    /** 非合格结论（返修 / 报废）必须有至少一条缺陷作为证据 */
    public void requireDefectsForVerdict(String verdict, List<DefectRequest> defects) {
        if (!"PASS".equals(verdict) && (defects == null || defects.isEmpty())) {
            throw new BizException("判「" + Dict.verdictName(verdict) + "」必须至少录入一条缺陷记录");
        }
    }

    private Defect toDefect(Long inspectId, DefectRequest request) {
        if (!Dict.isValidDefectType(request.getDefectType())) {
            throw new BizException("缺陷类型非法，只能六选一：" + request.getDefectType());
        }
        if (!Dict.isValidSeverity(request.getSeverity())) {
            throw new BizException("严重度非法（MINOR / MAJOR / CRITICAL）");
        }
        if (request.getQuantity() == null || request.getQuantity() <= 0) {
            throw new BizException("缺陷个数必须大于 0");
        }
        Defect defect = new Defect();
        defect.setInspectId(inspectId);
        defect.setDefectType(request.getDefectType());
        defect.setSeverity(request.getSeverity());
        defect.setPosition(request.getPosition());
        defect.setSizeMm(request.getSizeMm());
        defect.setQuantity(request.getQuantity());
        defect.setRemark(request.getRemark());
        return defect;
    }
}
