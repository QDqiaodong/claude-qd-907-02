package com.casting.shop.service;

import com.casting.shop.common.Dict;
import com.casting.shop.repository.CastingRepository;
import com.casting.shop.repository.ChargeRepository;
import com.casting.shop.repository.DefectRepository;
import com.casting.shop.repository.FurnaceRepository;
import com.casting.shop.repository.HeatRepository;
import com.casting.shop.repository.InspectionRepository;
import com.casting.shop.repository.MoldRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** 车间报表统计：全部走手写 JPQL 的投影查询。 */
@Service
public class StatsService {

    private final FurnaceRepository furnaceRepository;
    private final HeatRepository heatRepository;
    private final ChargeRepository chargeRepository;
    private final MoldRepository moldRepository;
    private final CastingRepository castingRepository;
    private final InspectionRepository inspectionRepository;
    private final DefectRepository defectRepository;
    private final StringRedisTemplate redisTemplate;

    public StatsService(FurnaceRepository furnaceRepository,
                        HeatRepository heatRepository,
                        ChargeRepository chargeRepository,
                        MoldRepository moldRepository,
                        CastingRepository castingRepository,
                        InspectionRepository inspectionRepository,
                        DefectRepository defectRepository,
                        StringRedisTemplate redisTemplate) {
        this.furnaceRepository = furnaceRepository;
        this.heatRepository = heatRepository;
        this.chargeRepository = chargeRepository;
        this.moldRepository = moldRepository;
        this.castingRepository = castingRepository;
        this.inspectionRepository = inspectionRepository;
        this.defectRepository = defectRepository;
        this.redisTemplate = redisTemplate;
    }

    /** 手写 JPQL ①：熔炼炉负荷 */
    public Object furnaceLoad() {
        return furnaceRepository.statFurnaceLoad();
    }

    /** 手写 JPQL ②：铸件状态分布 */
    public Object castingStatus() {
        return castingRepository.statByStatus();
    }

    /** 手写 JPQL ③：材质分布 */
    public Object material() {
        return castingRepository.statByMaterial();
    }

    /** 手写 JPQL ④：模具寿命 */
    public Object moldLife() {
        return moldRepository.statByMoldType();
    }

    /** 手写 JPQL ⑤：缺陷类型分布 */
    public Object defect() {
        return defectRepository.statByDefectType();
    }

    /** 手写 JPQL ⑥：判定分布 */
    public Object verdict() {
        return inspectionRepository.statVerdict();
    }

    /** 首页概览：各模块台账数量与关键指标 */
    public Map<String, Object> overview() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("furnaceCount", furnaceRepository.count());
        data.put("heatCount", heatRepository.count());
        data.put("chargeCount", chargeRepository.count());
        data.put("moldCount", moldRepository.count());
        data.put("castingCount", castingRepository.count());
        data.put("inspectionCount", inspectionRepository.count());
        data.put("defectCount", defectRepository.count());
        data.put("meltingCount", heatRepository.countByStage("MELTING"));
        data.put("storedCount", castingRepository.countByStatus("STORED"));
        data.put("passCount", inspectionRepository.countByVerdict("PASS"));
        data.put("reworkCount", inspectionRepository.countByVerdict("REWORK"));
        data.put("scrapCount", inspectionRepository.countByVerdict("SCRAP"));
        data.put("furnaceLoad", furnaceLoad());
        data.put("castingStatus", castingStatus());
        data.put("materialStats", material());
        data.put("moldLife", moldLife());
        data.put("defectStats", defect());
        data.put("verdictStats", verdict());
        data.put("reportViews", bumpReportViews());
        return data;
    }

    /** 车间工艺字典（材质 / 阶段 / 缺陷类型等），前端下拉用 */
    public Map<String, Object> dict() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("materials", Dict.MATERIALS);
        data.put("furnaceTypes", Dict.FURNACE_TYPES);
        data.put("heatFlow", Dict.HEAT_FLOW);
        data.put("chargeMaterials", Dict.CHARGE_MATERIALS);
        data.put("moldTypes", Dict.MOLD_TYPES);
        data.put("castingFlow", Dict.CASTING_FLOW);
        data.put("defectTypes", Dict.DEFECT_TYPES);
        data.put("verdicts", Dict.VERDICTS);
        data.put("maxDeviationRate", Dict.MAX_CHARGE_DEVIATION_RATE);
        return data;
    }

    /** 报表查看次数记在 Redis 里（拿不到就返回 -1，不影响主流程） */
    private long bumpReportViews() {
        try {
            return redisTemplate.opsForValue().increment("casting-shop:report-views");
        } catch (Exception e) {
            return -1L;
        }
    }
}
