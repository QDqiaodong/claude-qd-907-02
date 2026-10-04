package com.casting.shop.common;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 精密铸造车间词典与业务规则参数表：材质（五种）及其熔炼/出炉温度窗口、炉型、
 * 炉次阶段流转、炉料品种、模具型壳类型与状态、铸件状态流转、缺陷类型与严重度、
 * 检验判定结论，以及配料偏差允许比例等。
 *
 * <p>规则集中在此，便于出题时被要求调整或扩展。</p>
 */
public final class Dict {

    private Dict() {
    }

    // ---------------- 材质（5 选 1） ----------------
    public static final List<String> MATERIALS = List.of(
            "CAST_IRON", "DUCTILE_IRON", "CARBON_STEEL", "STAINLESS", "ALUMINUM");

    private static final Map<String, String> MATERIAL_NAME = Map.of(
            "CAST_IRON", "灰铸铁 HT250",
            "DUCTILE_IRON", "球墨铸铁 QT450-10",
            "CARBON_STEEL", "铸造碳钢 ZG270-500",
            "STAINLESS", "不锈钢 CF8C",
            "ALUMINUM", "铸造铝合金 ZL104");

    /** 材质熔炼温度窗口下限 ℃ */
    private static final Map<String, Integer> MATERIAL_MELT_LOW = Map.of(
            "CAST_IRON", 1420,
            "DUCTILE_IRON", 1450,
            "CARBON_STEEL", 1540,
            "STAINLESS", 1580,
            "ALUMINUM", 680);

    /** 材质熔炼温度窗口上限 ℃ */
    private static final Map<String, Integer> MATERIAL_MELT_HIGH = Map.of(
            "CAST_IRON", 1520,
            "DUCTILE_IRON", 1560,
            "CARBON_STEEL", 1640,
            "STAINLESS", 1690,
            "ALUMINUM", 780);

    /** 材质出炉温度窗口下限 ℃ */
    private static final Map<String, Integer> MATERIAL_TAP_LOW = Map.of(
            "CAST_IRON", 1380,
            "DUCTILE_IRON", 1420,
            "CARBON_STEEL", 1520,
            "STAINLESS", 1560,
            "ALUMINUM", 700);

    /** 材质出炉温度窗口上限 ℃ */
    private static final Map<String, Integer> MATERIAL_TAP_HIGH = Map.of(
            "CAST_IRON", 1480,
            "DUCTILE_IRON", 1500,
            "CARBON_STEEL", 1600,
            "STAINLESS", 1640,
            "ALUMINUM", 750);

    private static final Map<String, String> MATERIAL_COLOR = Map.of(
            "CAST_IRON", "#8d6e63",
            "DUCTILE_IRON", "#5c6bc0",
            "CARBON_STEEL", "#26a69a",
            "STAINLESS", "#78909c",
            "ALUMINUM", "#ffca28");

    // ---------------- 炉型 ----------------
    public static final List<String> FURNACE_TYPES = List.of(
            "INDUCTION", "ARC", "VACUUM", "RESISTANCE", "CRUCIBLE");

    private static final Map<String, String> FURNACE_TYPE_NAME = Map.of(
            "INDUCTION", "中频感应炉",
            "ARC", "电弧炉",
            "VACUUM", "真空感应炉",
            "RESISTANCE", "电阻炉",
            "CRUCIBLE", "坩埚保温炉");

    // ---------------- 熔炼炉状态 ----------------
    private static final Map<String, String> FURNACE_STATUS = Map.of(
            "IDLE", "待用",
            "BUSY", "作业中",
            "MAINTENANCE", "检修",
            "FAULT", "故障");

    // ---------------- 炉次阶段流转：装料 → 熔炼 → 精炼 → 出炉 → 已浇完 ----------------
    public static final List<String> HEAT_FLOW = List.of(
            "CHARGING", "MELTING", "REFINING", "TAPPING", "POURED");

    private static final Map<String, String> HEAT_STAGE_NAME = Map.of(
            "CHARGING", "装料",
            "MELTING", "熔炼",
            "REFINING", "精炼",
            "TAPPING", "出炉",
            "POURED", "已浇完");

    /** 各阶段在温度带上的默认温度（用于页面竖条的高度示意） */
    private static final Map<String, Integer> HEAT_STAGE_TEMP = Map.of(
            "CHARGING", 200,
            "MELTING", 900,
            "REFINING", 1250,
            "TAPPING", 1450,
            "POURED", 1400);

    private static final Map<String, String> HEAT_STAGE_COLOR = Map.of(
            "CHARGING", "#6fa8dc",
            "MELTING", "#f4b942",
            "REFINING", "#ef7d4e",
            "TAPPING", "#e4573d",
            "POURED", "#8e24aa");

    // ---------------- 炉料品种（6 选） ----------------
    public static final List<String> CHARGE_MATERIALS = List.of(
            "PIG_IRON", "SCRAP", "FERRO_SI", "FERRO_MN", "CARBURANT", "FLUX");

    private static final Map<String, String> CHARGE_MATERIAL_NAME = Map.of(
            "PIG_IRON", "铸造生铁 Q10",
            "SCRAP", "回炉料（浇冒口）",
            "FERRO_SI", "硅铁 FeSi75",
            "FERRO_MN", "锰铁 FeMn65",
            "CARBURANT", "增碳剂",
            "FLUX", "石灰造渣剂");

    private static final Map<String, String> CHARGE_MATERIAL_COLOR = Map.of(
            "PIG_IRON", "#8e24aa",
            "SCRAP", "#5c6bc0",
            "FERRO_SI", "#26a69a",
            "FERRO_MN", "#2f9e5f",
            "CARBURANT", "#e08a2b",
            "FLUX", "#9e7b3c");

    // ---------------- 双方案推演工艺参数 ----------------
    /** 炉料参考单价 元/kg：回炉料最便宜、合金料最贵，成本方案据此选料 */
    private static final Map<String, BigDecimal> CHARGE_MATERIAL_COST = Map.of(
            "PIG_IRON", BigDecimal.valueOf(3.20),
            "SCRAP", BigDecimal.valueOf(0.80),
            "FERRO_SI", BigDecimal.valueOf(6.50),
            "FERRO_MN", BigDecimal.valueOf(7.80),
            "CARBURANT", BigDecimal.valueOf(4.50),
            "FLUX", BigDecimal.valueOf(1.20));

    /** 各材质回炉料占比工艺上限 %：回炉料过多会累积微量元素、恶化材质性能 */
    private static final Map<String, BigDecimal> SCRAP_RATIO_MAX = Map.of(
            "CAST_IRON", BigDecimal.valueOf(65),
            "DUCTILE_IRON", BigDecimal.valueOf(60),
            "CARBON_STEEL", BigDecimal.valueOf(55),
            "STAINLESS", BigDecimal.valueOf(60),
            "ALUMINUM", BigDecimal.valueOf(85));

    /**
     * 各材质标准配料比 %（六个炉料品种，合计 100）：成分偏离方案的目标配方。
     * 配方本身的回炉料占比不超过对应 {@link #SCRAP_RATIO_MAX}，保证配方在工艺上可达。
     */
    private static final Map<String, Map<String, BigDecimal>> CHARGE_RECIPE = Map.of(
            "CAST_IRON", Map.of(
                    "PIG_IRON", BigDecimal.valueOf(45), "SCRAP", BigDecimal.valueOf(45),
                    "FERRO_SI", BigDecimal.valueOf(2.5), "FERRO_MN", BigDecimal.valueOf(1),
                    "CARBURANT", BigDecimal.valueOf(3.5), "FLUX", BigDecimal.valueOf(3)),
            "DUCTILE_IRON", Map.of(
                    "PIG_IRON", BigDecimal.valueOf(55), "SCRAP", BigDecimal.valueOf(35),
                    "FERRO_SI", BigDecimal.valueOf(3.5), "FERRO_MN", BigDecimal.valueOf(0.8),
                    "CARBURANT", BigDecimal.valueOf(2.7), "FLUX", BigDecimal.valueOf(3)),
            "CARBON_STEEL", Map.of(
                    "PIG_IRON", BigDecimal.valueOf(38), "SCRAP", BigDecimal.valueOf(50),
                    "FERRO_SI", BigDecimal.valueOf(1.5), "FERRO_MN", BigDecimal.valueOf(2.5),
                    "CARBURANT", BigDecimal.valueOf(1), "FLUX", BigDecimal.valueOf(7)),
            "STAINLESS", Map.of(
                    "PIG_IRON", BigDecimal.valueOf(35), "SCRAP", BigDecimal.valueOf(55),
                    "FERRO_SI", BigDecimal.valueOf(3), "FERRO_MN", BigDecimal.valueOf(3),
                    "CARBURANT", BigDecimal.valueOf(0.5), "FLUX", BigDecimal.valueOf(3.5)),
            "ALUMINUM", Map.of(
                    "PIG_IRON", BigDecimal.valueOf(0), "SCRAP", BigDecimal.valueOf(80),
                    "FERRO_SI", BigDecimal.valueOf(12), "FERRO_MN", BigDecimal.valueOf(1),
                    "CARBURANT", BigDecimal.valueOf(0), "FLUX", BigDecimal.valueOf(7)));

    // ---------------- 模具型壳 ----------------
    public static final List<String> MOLD_TYPES = List.of(
            "WAX", "RESIN_SAND", "CERAMIC", "METAL");

    private static final Map<String, String> MOLD_TYPE_NAME = Map.of(
            "WAX", "熔模蜡型壳",
            "RESIN_SAND", "树脂砂型",
            "CERAMIC", "陶瓷型壳",
            "METAL", "金属型");

    private static final Map<String, String> MOLD_STATUS = Map.of(
            "AVAILABLE", "可排产",
            "IN_USE", "在产",
            "SCRAPPED", "已报废");

    // ---------------- 铸件状态流转：待浇 → 已浇注 → 清理 → 检验 → 入库 ----------------
    // 注意：REWORK（返修中）/ REINSPECT（待复检）不在这条线性流里，它们是检验判返修后
    // 走出的「返修闭环」支线状态，只能由返修流程写入，铸件卡手工流转/编辑一律拦掉。
    public static final List<String> CASTING_FLOW = List.of(
            "WAIT_POUR", "POURED", "CLEANING", "INSPECTING", "STORED");

    private static final Map<String, String> CASTING_STATUS = Map.of(
            "WAIT_POUR", "待浇",
            "POURED", "已浇注",
            "CLEANING", "清理中",
            "INSPECTING", "待检验",
            "STORED", "已入库",
            "SCRAPPED", "已报废",
            "REWORK", "返修中",
            "REINSPECT", "待复检");

    private static final Map<String, String> CASTING_STATUS_COLOR = Map.of(
            "WAIT_POUR", "#78909c",
            "POURED", "#5c6bc0",
            "CLEANING", "#26a69a",
            "INSPECTING", "#e08a2b",
            "STORED", "#2f9e5f",
            "SCRAPPED", "#e4573d",
            "REWORK", "#ef6c00",
            "REINSPECT", "#00897b");

    // ---------------- 缺陷类型（6 选 1） ----------------
    public static final List<String> DEFECT_TYPES = List.of(
            "SAND_HOLE", "POROSITY", "SHRINKAGE", "CRACK", "INCLUSION", "MISRUN");

    private static final Map<String, String> DEFECT_TYPE_NAME = Map.of(
            "SAND_HOLE", "砂眼",
            "POROSITY", "气孔",
            "SHRINKAGE", "缩松",
            "CRACK", "裂纹",
            "INCLUSION", "夹渣",
            "MISRUN", "浇不足");

    private static final Map<String, String> DEFECT_TYPE_COLOR = Map.of(
            "SAND_HOLE", "#8e24aa",
            "POROSITY", "#5c6bc0",
            "SHRINKAGE", "#26a69a",
            "CRACK", "#e4573d",
            "INCLUSION", "#e08a2b",
            "MISRUN", "#2f9e5f");

    private static final Map<String, String> SEVERITY_NAME = Map.of(
            "MINOR", "轻微",
            "MAJOR", "一般",
            "CRITICAL", "严重");

    // ---------------- 检验判定（3 选 1） ----------------
    public static final List<String> VERDICTS = List.of("PASS", "REWORK", "SCRAP");

    private static final Map<String, String> VERDICT_NAME = Map.of(
            "PASS", "合格",
            "REWORK", "返修",
            "SCRAP", "报废");

    private static final Map<String, String> VERDICT_COLOR = Map.of(
            "PASS", "#2f9e5f",
            "REWORK", "#e08a2b",
            "SCRAP", "#e4573d");

    // ---------------- 返修处置方式（返修完成登记时必选） ----------------
    public static final List<String> REWORK_METHODS = List.of(
            "WELD", "GRIND", "IMPREGNATION", "RE_MACHINE", "SALVAGE");

    private static final Map<String, String> REWORK_METHOD_NAME = Map.of(
            "WELD", "补焊",
            "GRIND", "打磨修磨",
            "IMPREGNATION", "负压浸渗",
            "RE_MACHINE", "重新机加工",
            "SALVAGE", "校正回用");

    // ---------------- 返修单状态（返修阶段，列表据此定位积压） ----------------
    public static final List<String> REWORK_STAGES = List.of(
            "REWORKING", "REINSPECTING", "STORED", "SCRAPPED");

    private static final Map<String, String> REWORK_STAGE_NAME = Map.of(
            "REWORKING", "返修中",
            "REINSPECTING", "待复检",
            "STORED", "复检合格已入库",
            "SCRAPPED", "复检判废已终止");

    private static final Map<String, String> REWORK_STAGE_COLOR = Map.of(
            "REWORKING", "#ef6c00",
            "REINSPECTING", "#00897b",
            "STORED", "#2f9e5f",
            "SCRAPPED", "#e4573d");

    // ---------------- 返修轮次当前状态（细粒度，用于按钮与时间线） ----------------
    // 撤回不保留为状态值：撤回后状态回到 PENDING、处置字段清空，撤回动作本身由 rework_event 留痕。
    private static final Map<String, String> ROUND_STATUS_NAME = Map.of(
            "PENDING", "待返修",
            "COMPLETED", "返修完成待复检",
            "PASS", "复检合格",
            "REWORK", "复检再返修",
            "SCRAP", "复检判废");

    private static final Map<String, String> ROUND_STATUS_COLOR = Map.of(
            "PENDING", "#e08a2b",
            "COMPLETED", "#00897b",
            "PASS", "#2f9e5f",
            "REWORK", "#ef6c00",
            "SCRAP", "#e4573d");

    // ---------------- 更正说明类型（证据不可改写，只能追加） ----------------
    private static final Map<String, String> CORRECTION_TARGET_NAME = Map.of(
            "REWORK", "返修处置",
            "REINSPECT", "复检结论");

    // ---------------- 返修事件类型（审计时间线） ----------------
    private static final Map<String, String> REWORK_EVENT_NAME = Map.of(
            "CREATED", "返修单建立",
            "COMPLETED", "登记返修完成",
            "WITHDRAWN", "撤回完成结果",
            "REINSPECTED", "登记复检结论",
            "CORRECTED", "追加更正说明",
            "CLOSED_STORED", "闭环：合格入库",
            "CLOSED_SCRAP", "闭环：判废终止");

    private static final Map<String, String> REWORK_EVENT_COLOR = Map.of(
            "CREATED", "#8e24aa",
            "COMPLETED", "#00897b",
            "WITHDRAWN", "#8d8399",
            "REINSPECTED", "#1565c0",
            "CORRECTED", "#f9a825",
            "CLOSED_STORED", "#2f9e5f",
            "CLOSED_SCRAP", "#e4573d");

    // ---------------- 业务参数 ----------------
    /** 配料合计重量与炉次出炉重量的允许偏差比例（%） */
    public static final BigDecimal MAX_CHARGE_DEVIATION_RATE = BigDecimal.valueOf(3.0);

    /** 单次配料允许的最大单品种重量 kg */
    public static final BigDecimal MAX_ITEM_WEIGHT = BigDecimal.valueOf(2000);

    /** 单炉最大出炉重量 kg */
    public static final BigDecimal MAX_TAP_WEIGHT = BigDecimal.valueOf(5000);

    /** 模具剩余寿命低于该比例时高亮预警（%） */
    public static final BigDecimal MOLD_WARN_REMAIN_RATE = BigDecimal.valueOf(20.0);

    // ---------------- 取值方法 ----------------

    public static boolean isValidMaterial(String material) {
        return material != null && MATERIAL_NAME.containsKey(material);
    }

    public static String materialName(String material) {
        return material == null ? "" : MATERIAL_NAME.getOrDefault(material, material);
    }

    public static String materialColor(String material) {
        return material == null ? "#8e24aa" : MATERIAL_COLOR.getOrDefault(material, "#8e24aa");
    }

    public static int meltLow(String material) {
        return MATERIAL_MELT_LOW.getOrDefault(material, 1000);
    }

    public static int meltHigh(String material) {
        return MATERIAL_MELT_HIGH.getOrDefault(material, 1800);
    }

    public static int tapLow(String material) {
        return MATERIAL_TAP_LOW.getOrDefault(material, 600);
    }

    public static int tapHigh(String material) {
        return MATERIAL_TAP_HIGH.getOrDefault(material, 1800);
    }

    public static boolean isValidFurnaceType(String type) {
        return type != null && FURNACE_TYPE_NAME.containsKey(type);
    }

    public static String furnaceTypeName(String type) {
        return type == null ? "" : FURNACE_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidFurnaceStatus(String status) {
        return status != null && FURNACE_STATUS.containsKey(status);
    }

    public static String furnaceStatusName(String status) {
        return status == null ? "" : FURNACE_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidStage(String stage) {
        return stage != null && HEAT_STAGE_NAME.containsKey(stage);
    }

    public static String stageName(String stage) {
        return stage == null ? "" : HEAT_STAGE_NAME.getOrDefault(stage, stage);
    }

    public static String stageColor(String stage) {
        return stage == null ? "#8e24aa" : HEAT_STAGE_COLOR.getOrDefault(stage, "#8e24aa");
    }

    public static Integer stageTemp(String stage) {
        return HEAT_STAGE_TEMP.getOrDefault(stage, 0);
    }

    public static int stageIndex(String stage) {
        int idx = HEAT_FLOW.indexOf(stage);
        return idx < 0 ? 0 : idx;
    }

    public static boolean isValidChargeMaterial(String material) {
        return material != null && CHARGE_MATERIAL_NAME.containsKey(material);
    }

    public static String chargeMaterialName(String material) {
        return material == null ? "" : CHARGE_MATERIAL_NAME.getOrDefault(material, material);
    }

    public static String chargeMaterialColor(String material) {
        return material == null ? "#8e24aa" : CHARGE_MATERIAL_COLOR.getOrDefault(material, "#8e24aa");
    }

    /** 炉料参考单价 元/kg，未知品种按 0 计（不影响可行性，只影响成本排序） */
    public static BigDecimal chargeMaterialCost(String material) {
        return material == null ? BigDecimal.ZERO : CHARGE_MATERIAL_COST.getOrDefault(material, BigDecimal.ZERO);
    }

    /** 材质回炉料占比工艺上限 %，未知材质按 60% 保守取值 */
    public static BigDecimal scrapRatioMax(String alloy) {
        return alloy == null ? BigDecimal.valueOf(60) : SCRAP_RATIO_MAX.getOrDefault(alloy, BigDecimal.valueOf(60));
    }

    /** 材质标准配料比（六个品种 → 占比 %，合计 100），未知材质回退为灰铁配方 */
    public static Map<String, BigDecimal> chargeRecipe(String alloy) {
        Map<String, BigDecimal> recipe = alloy == null ? null : CHARGE_RECIPE.get(alloy);
        return recipe == null ? CHARGE_RECIPE.get("CAST_IRON") : recipe;
    }

    public static boolean isValidMoldType(String type) {
        return type != null && MOLD_TYPE_NAME.containsKey(type);
    }

    public static String moldTypeName(String type) {
        return type == null ? "" : MOLD_TYPE_NAME.getOrDefault(type, type);
    }

    public static boolean isValidMoldStatus(String status) {
        return status != null && MOLD_STATUS.containsKey(status);
    }

    public static String moldStatusName(String status) {
        return status == null ? "" : MOLD_STATUS.getOrDefault(status, status);
    }

    public static boolean isValidCastingStatus(String status) {
        return status != null && CASTING_STATUS.containsKey(status);
    }

    public static String castingStatusName(String status) {
        return status == null ? "" : CASTING_STATUS.getOrDefault(status, status);
    }

    public static String castingStatusColor(String status) {
        return status == null ? "#8e24aa" : CASTING_STATUS_COLOR.getOrDefault(status, "#8e24aa");
    }

    public static int castingStageIndex(String status) {
        int idx = CASTING_FLOW.indexOf(status);
        return idx < 0 ? 0 : idx;
    }

    public static boolean isValidDefectType(String type) {
        return type != null && DEFECT_TYPE_NAME.containsKey(type);
    }

    public static String defectTypeName(String type) {
        return type == null ? "" : DEFECT_TYPE_NAME.getOrDefault(type, type);
    }

    public static String defectTypeColor(String type) {
        return type == null ? "#8e24aa" : DEFECT_TYPE_COLOR.getOrDefault(type, "#8e24aa");
    }

    public static String severityName(String severity) {
        return severity == null ? "" : SEVERITY_NAME.getOrDefault(severity, severity);
    }

    public static boolean isValidSeverity(String severity) {
        return severity != null && SEVERITY_NAME.containsKey(severity);
    }

    public static boolean isValidVerdict(String verdict) {
        return verdict != null && VERDICT_NAME.containsKey(verdict);
    }

    public static String verdictName(String verdict) {
        return verdict == null ? "" : VERDICT_NAME.getOrDefault(verdict, verdict);
    }

    public static String verdictColor(String verdict) {
        return verdict == null ? "#8e24aa" : VERDICT_COLOR.getOrDefault(verdict, "#8e24aa");
    }

    public static boolean isValidReworkMethod(String method) {
        return method != null && REWORK_METHOD_NAME.containsKey(method);
    }

    public static String reworkMethodName(String method) {
        return method == null ? "" : REWORK_METHOD_NAME.getOrDefault(method, method);
    }

    public static boolean isValidReworkStage(String stage) {
        return stage != null && REWORK_STAGE_NAME.containsKey(stage);
    }

    public static String reworkStageName(String stage) {
        return stage == null ? "" : REWORK_STAGE_NAME.getOrDefault(stage, stage);
    }

    public static String reworkStageColor(String stage) {
        return stage == null ? "#8e24aa" : REWORK_STAGE_COLOR.getOrDefault(stage, "#8e24aa");
    }

    public static String roundStatusName(String status) {
        return status == null ? "" : ROUND_STATUS_NAME.getOrDefault(status, status);
    }

    public static String roundStatusColor(String status) {
        return status == null ? "#8e24aa" : ROUND_STATUS_COLOR.getOrDefault(status, "#8e24aa");
    }

    public static String correctionTargetName(String target) {
        return target == null ? "" : CORRECTION_TARGET_NAME.getOrDefault(target, target);
    }

    public static String reworkEventName(String type) {
        return type == null ? "" : REWORK_EVENT_NAME.getOrDefault(type, type);
    }

    public static String reworkEventColor(String type) {
        return type == null ? "#8e24aa" : REWORK_EVENT_COLOR.getOrDefault(type, "#8e24aa");
    }
}
