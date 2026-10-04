SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;
SET character_set_client = utf8mb4;
SET character_set_connection = utf8mb4;
SET character_set_results = utf8mb4;

-- ---------------------------------------------------------------------
-- 精密铸造车间 · 熔炼炉次与铸件质量管理
-- 库名 casting_shop；application.yml 里 ddl-auto: none，表结构全部由本文件在 MySQL 镜像构建期导入。
-- 关键：镜像构建期跑 initdb 时 mysql 客户端默认 latin1，会把 UTF-8 中文存成双编码，
--       因此本文件第一行必须是 SET NAMES utf8mb4（下面几句是同一件事的加强版）。

CREATE DATABASE IF NOT EXISTS casting_shop
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_unicode_ci;

USE casting_shop;

SET FOREIGN_KEY_CHECKS = 0;
DROP TABLE IF EXISTS rework_event;
DROP TABLE IF EXISTS rework_correction;
DROP TABLE IF EXISTS rework_round;
DROP TABLE IF EXISTS rework_order;
DROP TABLE IF EXISTS defect;
DROP TABLE IF EXISTS inspection;
DROP TABLE IF EXISTS casting;
DROP TABLE IF EXISTS mold;
DROP TABLE IF EXISTS charge_item;
DROP TABLE IF EXISTS charge;
DROP TABLE IF EXISTS heat;
DROP TABLE IF EXISTS furnace;
SET FOREIGN_KEY_CHECKS = 1;

-- ---------------- 熔炼炉 ----------------
CREATE TABLE furnace (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    furnace_no VARCHAR(16) NOT NULL UNIQUE,
    furnace_name VARCHAR(48),
    furnace_type VARCHAR(16),
    capacity_kg DECIMAL(10,2),
    max_temp INT,
    power_kw INT,
    status VARCHAR(16),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 炉次 ----------------
-- 阶段流转：CHARGING 装料 → MELTING 熔炼 → REFINING 精炼 → TAPPING 出炉 → POURED 已浇完
CREATE TABLE heat (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    heat_no VARCHAR(24) NOT NULL UNIQUE,
    furnace_id BIGINT,
    furnace_no VARCHAR(16),
    alloy VARCHAR(16),
    plan_weight DECIMAL(10,2),
    melt_temp INT,
    refine_temp INT,
    tap_temp INT,
    tap_weight DECIMAL(10,2),
    stage VARCHAR(16),
    operator VARCHAR(32),
    start_time DATETIME,
    tap_time DATETIME,
    created_at DATETIME,
    KEY idx_heat_furnace (furnace_id),
    KEY idx_heat_stage (stage)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 配料单 ----------------
-- deviation_rate 是派生列：由 total_weight 与 target_weight 算出，(total - target) / target × 100。
CREATE TABLE charge (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    charge_no VARCHAR(24) NOT NULL UNIQUE,
    heat_id BIGINT,
    heat_no VARCHAR(24),
    item_count INT,
    total_weight DECIMAL(12,2),
    target_weight DECIMAL(12,2),
    deviation_rate DECIMAL(6,2),
    operator VARCHAR(32),
    charge_time DATETIME,
    remark VARCHAR(128),
    KEY idx_charge_heat (heat_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 配料明细 ----------------
-- ratio 是派生列：本品种重量占配料合计重量的百分比。
CREATE TABLE charge_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    charge_id BIGINT NOT NULL,
    material_code VARCHAR(16),
    material_name VARCHAR(48),
    weight_kg DECIMAL(10,2),
    ratio DECIMAL(6,2),
    remark VARCHAR(64),
    KEY idx_item_charge (charge_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 模具型壳 ----------------
CREATE TABLE mold (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    mold_no VARCHAR(24) NOT NULL UNIQUE,
    mold_name VARCHAR(48),
    mold_type VARCHAR(16),
    cavity_count INT,
    life_count INT,
    used_count INT,
    fit_material VARCHAR(16),
    status VARCHAR(16),
    storage_slot VARCHAR(32),
    note VARCHAR(64),
    created_at DATETIME
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 铸件卡 ----------------
-- 注意：heat_no / heat_alloy / mold_no / mold_name 这四个只读派生列【不在本表里】，
--       它们由 Casting 实体上的 @SQLSelect 那条 left join heat / mold 的自定义加载 SQL
--       投影出来，实体里标了 insertable=false / updatable=false。
CREATE TABLE casting (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    casting_no VARCHAR(24) NOT NULL UNIQUE,
    casting_name VARCHAR(48),
    material VARCHAR(16),
    heat_id BIGINT,
    mold_id BIGINT,
    cavity_no VARCHAR(16),
    theory_weight DECIMAL(10,2),
    actual_weight DECIMAL(10,2),
    -- 除原有 待浇/已浇注/清理/待检验/入库/报废 外，新增返修支线：
    -- REWORK 返修中（独立返修队列）、REINSPECT 待复检；二者只能由返修流程写入
    status VARCHAR(16),
    customer VARCHAR(48),
    operator VARCHAR(32),
    pour_time DATETIME,
    created_at DATETIME,
    KEY idx_casting_heat (heat_id),
    KEY idx_casting_mold (mold_id),
    KEY idx_casting_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 检验单 ----------------
CREATE TABLE inspection (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspect_no VARCHAR(24) NOT NULL UNIQUE,
    casting_id BIGINT,
    casting_no VARCHAR(24),
    defect_count INT,
    verdict VARCHAR(16),
    hardness_hb DECIMAL(6,2),
    inspector VARCHAR(32),
    remark VARCHAR(128),
    inspect_time DATETIME,
    KEY idx_inspect_casting (casting_id),
    KEY idx_inspect_verdict (verdict)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 缺陷记录 ----------------
CREATE TABLE defect (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspect_id BIGINT NOT NULL,
    defect_type VARCHAR(16),
    severity VARCHAR(16),
    defect_position VARCHAR(32),
    size_mm DECIMAL(6,2),
    quantity INT,
    remark VARCHAR(64),
    KEY idx_defect_inspect (inspect_id),
    KEY idx_defect_type (defect_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 返修单 ----------------
-- 一件铸件首次检验判返修即建立一张终身唯一返修单（uk_rework_casting 兜底并发建单）。
-- stage 为返修阶段：REWORKING 返修中 → REINSPECTING 待复检（多轮会来回切）→ STORED/SCRAPPED 终态。
-- snapshot_inspect_id 引用首次检验单（首次缺陷快照），检验单被引用后不可删除。
CREATE TABLE rework_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rework_no VARCHAR(24) NOT NULL UNIQUE,
    casting_id BIGINT NOT NULL,
    casting_no VARCHAR(24),
    snapshot_inspect_id BIGINT NOT NULL,
    stage VARCHAR(16),
    current_round INT DEFAULT 1,
    created_at DATETIME,
    closed_at DATETIME,
    UNIQUE KEY uk_rework_casting (casting_id),
    KEY idx_rework_stage (stage),
    KEY idx_rework_snapshot (snapshot_inspect_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 返修轮次 ----------------
-- 一轮 = 一次「上轮缺陷快照(source_inspect_id) → 返修处置 → 复检(reinspect_id)」。
-- (rework_id, round_no) 唯一：同一轮并发完成/并发复检在数据库层只可能成功一次。
-- reinspect_id 为空时处置结果可撤回重填；写入后本轮所有字段冻结，只能追加更正说明。
CREATE TABLE rework_round (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rework_id BIGINT NOT NULL,
    round_no INT NOT NULL,
    source_inspect_id BIGINT NOT NULL,
    round_status VARCHAR(16),
    rework_method VARCHAR(16),
    responsible_person VARCHAR(32),
    completed_at DATETIME,
    rework_note VARCHAR(255),
    completed_by VARCHAR(32),
    reinspect_id BIGINT,
    reinspect_verdict VARCHAR(16),
    reinspect_time DATETIME,
    reinspector VARCHAR(32),
    created_at DATETIME,
    UNIQUE KEY uk_round_no (rework_id, round_no),
    KEY idx_round_rework (rework_id),
    KEY idx_round_source (source_inspect_id),
    KEY idx_round_reinspect (reinspect_id),
    KEY idx_round_status (round_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 返修更正说明（只追加） ----------------
-- 复检创建后历史证据冻结，误填只能往这里追加；原值与时间戳原样保留。
CREATE TABLE rework_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rework_id BIGINT NOT NULL,
    round_id BIGINT NOT NULL,
    target_type VARCHAR(16),
    content VARCHAR(255),
    operator VARCHAR(32),
    created_at DATETIME,
    KEY idx_correction_rework (rework_id),
    KEY idx_correction_round (round_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- ---------------- 返修事件流（只增不改不删） ----------------
CREATE TABLE rework_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rework_id BIGINT NOT NULL,
    round_no INT,
    event_type VARCHAR(16),
    operator VARCHAR(32),
    detail VARCHAR(255),
    created_at DATETIME,
    KEY idx_event_rework (rework_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- =====================================================================
-- 种子数据
-- 注意：构建期 mysqld 的 NOW() 是 UTC，运行时是东八区，
--       所以时间统一写 UTC_TIMESTAMP() + INTERVAL 8 HOUR。
-- =====================================================================

INSERT INTO furnace (furnace_no, furnace_name, furnace_type, capacity_kg, max_temp, power_kw, status, note, created_at) VALUES
('F-01', '1#中频感应炉',   'INDUCTION',  1500.00, 1750, 800,  'BUSY',        '不锈钢主力炉，两班倒',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
('F-02', '2#中频感应炉',   'INDUCTION',  1000.00, 1700, 600,  'BUSY',        '灰铁/球铁两用',          UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 DAY),
('F-03', '3#电弧炉',       'ARC',        3000.00, 1800, 1250, 'BUSY',        '大件铸钢专用',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 DAY),
('F-04', '4#真空感应炉',   'VACUUM',      800.00, 1750, 500,  'BUSY',        '耐热件小批量',           UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 24 DAY),
('F-05', '5#坩埚保温炉',   'CRUCIBLE',    600.00,  900, 200,  'IDLE',        '铝合金专线',             UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 22 DAY),
('F-06', '6#中频感应炉',   'INDUCTION',  1200.00, 1700, 750,  'MAINTENANCE', '感应线圈检修中，停用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 20 DAY);

INSERT INTO heat (heat_no, furnace_id, furnace_no, alloy, plan_weight, melt_temp, refine_temp, tap_temp, tap_weight, stage, operator, start_time, tap_time, created_at) VALUES
('H-2603-0301', 1, 'F-01', 'STAINLESS',    1200.00, 1620, 1600, 1600, 1180.50, 'POURED',   '张宏伟', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR),
('H-2603-0302', 1, 'F-01', 'STAINLESS',    1250.00, 1635, 1610, 1615, 1210.00, 'POURED',   '张宏伟', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
('H-2603-0303', 2, 'F-02', 'CAST_IRON',     900.00, 1470, 1450, 1430,  880.00, 'POURED',   '李建平', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR),
('H-2603-0304', 2, 'F-02', 'DUCTILE_IRON',  950.00, 1500, 1480, 1455,  930.50, 'TAPPING',  '李建平', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
('H-2603-0305', 3, 'F-03', 'CARBON_STEEL', 2600.00, 1590, 1570, 1550, 2540.00, 'TAPPING',  '王振国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR),
('H-2603-0306', 3, 'F-03', 'CARBON_STEEL', 2700.00, 1600, 1580, 1560, 2620.80, 'REFINING', '王振国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR),
('H-2603-0307', 4, 'F-04', 'STAINLESS',     600.00, 1640, NULL, NULL,   580.00, 'MELTING',  '赵立新', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
('H-2603-0308', 5, 'F-05', 'ALUMINUM',      400.00,  730,  720,  715,   385.40, 'TAPPING',  '孙德明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('H-2603-0309', 5, 'F-05', 'ALUMINUM',      420.00,  740, NULL,  725,   401.20, 'MELTING',  '孙德明', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('H-2603-0310', 2, 'F-02', 'CAST_IRON',     860.00, 1475, NULL, NULL,     NULL, 'CHARGING', '李建平', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
('H-2603-0311', 4, 'F-04', 'DUCTILE_IRON',  700.00, NULL, NULL, NULL,     NULL, 'CHARGING', '赵立新', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
('H-2603-0312', 3, 'F-03', 'CARBON_STEEL', 2800.00, 1610, 1595, 1570, 2735.60, 'POURED',   '王振国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR);

INSERT INTO charge (charge_no, heat_id, heat_no, item_count, total_weight, target_weight, deviation_rate, operator, charge_time, remark) VALUES
('CH-2603-0101', 1,  'H-2603-0301', 4, 1183.00, 1180.50,  0.21, '吴桂芳', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR, '不锈钢泵体订单，回炉料比例偏高'),
('CH-2603-0102', 2,  'H-2603-0302', 4, 1204.00, 1210.00, -0.50, '吴桂芳', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, '与上一炉同钢种，配料微调'),
('CH-2603-0103', 3,  'H-2603-0303', 5,  884.00,  880.00,  0.45, '郑海燕', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR, ' HT250 箱体，增碳剂偏多'),
('CH-2603-0104', 4,  'H-2603-0304', 5,  928.00,  930.50, -0.27, '郑海燕', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, 'QT450 球铁，孕育处理前配料'),
('CH-2603-0105', 5,  'H-2603-0305', 4, 2544.00, 2540.00,  0.16, '刘保国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, 'ZG270 大件，回炉料为浇冒口料'),
('CH-2603-0106', 6,  'H-2603-0306', 5, 2634.00, 2620.80,  0.50, '刘保国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, '含高锰铁，注意 Mn 上限'),
('CH-2603-0107', 8,  'H-2603-0308', 3,  384.00,  385.40, -0.36, '马春林', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, 'ZL104 铝锭 + 回炉料'),
('CH-2603-0108', 12, 'H-2603-0312', 4, 2737.00, 2735.60,  0.05, '刘保国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR, '横梁主料，偏差控制最好'),
-- 未出炉炉次的在配配料单：双方案推演以它们为「当前配料明细」基准
('CH-2603-0109', 10, 'H-2603-0310', 5,  847.00,  860.00, -1.51, '郑海燕', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 50 MINUTE, 'HT250 待装料，回炉料比例偏高'),
('CH-2603-0110', 11, 'H-2603-0311', 6,  695.00,  700.00, -0.71, '赵立新', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 MINUTE, 'QT450 待装料，待推演微调');

INSERT INTO charge_item (charge_id, material_code, material_name, weight_kg, ratio, remark) VALUES
(1, 'PIG_IRON',  '铸造生铁 Q10',     420.00, 35.50, '低磷生铁'),
(1, 'SCRAP',     '回炉料（浇冒口）', 640.00, 54.10, '同钢种回炉'),
(1, 'FERRO_SI',  '硅铁 FeSi75',       35.00,  2.96, '终脱氧用'),
(1, 'FLUX',      '石灰造渣剂',        88.00,  7.44, '覆盖剂'),
(2, 'PIG_IRON',  '铸造生铁 Q10',     300.00, 24.92, '低磷生铁'),
(2, 'SCRAP',     '回炉料（浇冒口）', 850.00, 70.60, '占总料七成'),
(2, 'FERRO_MN',  '锰铁 FeMn65',       42.00,  3.49, '补 Mn 用'),
(2, 'CARBURANT', '增碳剂',            12.00,  1.00, '微调含碳量'),
(3, 'PIG_IRON',  '铸造生铁 Q10',     260.00, 29.41, '新生铁'),
(3, 'SCRAP',     '回炉料（浇冒口）', 560.00, 63.35, '灰铁回炉料'),
(3, 'FERRO_SI',  '硅铁 FeSi75',       28.00,  3.17, '孕育前调硅'),
(3, 'CARBURANT', '增碳剂',            26.00,  2.94, '合成铸铁增碳'),
(3, 'FLUX',      '石灰造渣剂',        10.00,  1.13, '打渣用'),
(4, 'PIG_IRON',  '铸造生铁 Q10',     240.00, 25.86, '低钛生铁'),
(4, 'SCRAP',     '回炉料（浇冒口）', 610.00, 65.73, '球铁回炉料'),
(4, 'FERRO_SI',  '硅铁 FeSi75',       32.00,  3.45, '球化后孕育'),
(4, 'FERRO_MN',  '锰铁 FeMn65',       22.00,  2.37, '调 Mn'),
(4, 'FLUX',      '石灰造渣剂',        24.00,  2.59, '脱硫打渣'),
(5, 'PIG_IRON',  '铸造生铁 Q10',     700.00, 27.52, '批量备料'),
(5, 'SCRAP',     '回炉料（浇冒口）', 1700.00, 66.82, '浇冒口切割料'),
(5, 'FERRO_MN',  '锰铁 FeMn65',       88.00,  3.46, 'ZG270 调 Mn'),
(5, 'FLUX',      '石灰造渣剂',        56.00,  2.20, '氧化期打渣'),
(6, 'PIG_IRON',  '铸造生铁 Q10',     820.00, 31.13, '批量备料'),
(6, 'SCRAP',     '回炉料（浇冒口）', 1680.00, 63.78, '上批次浇冒口'),
(6, 'FERRO_MN',  '锰铁 FeMn65',       76.00,  2.89, '调 Mn'),
(6, 'CARBURANT', '增碳剂',            18.00,  0.68, '补碳'),
(6, 'FLUX',      '石灰造渣剂',        40.00,  1.52, '精炼期造渣'),
(7, 'SCRAP',     '回炉料（浇冒口）', 320.00, 83.33, '铝料头回炉'),
(7, 'FERRO_SI',  '硅铁 FeSi75',       42.00, 10.94, '调 Si'),
(7, 'FLUX',      '石灰造渣剂',        22.00,  5.73, '铝合金精炼剂'),
(8, 'PIG_IRON',  '铸造生铁 Q10',     760.00, 27.77, '批量备料'),
(8, 'SCRAP',     '回炉料（浇冒口）', 1820.00, 66.50, '横梁浇冒口'),
(8, 'FERRO_MN',  '锰铁 FeMn65',       95.00,  3.47, '调 Mn'),
(8, 'FLUX',      '石灰造渣剂',        62.00,  2.27, '打渣覆盖'),
-- 未出炉炉次 H-2603-0310 / H-2603-0311 的在配明细（双方案推演基准）
(9, 'PIG_IRON',  '铸造生铁 Q10',     380.00, 44.86, '新生铁'),
(9, 'SCRAP',     '回炉料（浇冒口）', 400.00, 47.23, '灰铁回炉料'),
(9, 'FERRO_SI',  '硅铁 FeSi75',       25.00,  2.95, '孕育前调硅'),
(9, 'CARBURANT', '增碳剂',            30.00,  3.54, '合成铸铁增碳'),
(9, 'FLUX',      '石灰造渣剂',        12.00,  1.42, '打渣用'),
(10, 'PIG_IRON', '铸造生铁 Q10',     300.00, 43.17, '低钛生铁'),
(10, 'SCRAP',    '回炉料（浇冒口）', 330.00, 47.48, '球铁回炉料'),
(10, 'FERRO_SI', '硅铁 FeSi75',       28.00,  4.03, '球化后孕育'),
(10, 'FERRO_MN', '锰铁 FeMn65',       12.00,  1.73, '调 Mn'),
(10, 'CARBURANT','增碳剂',            15.00,  2.16, '补碳'),
(10, 'FLUX',     '石灰造渣剂',        10.00,  1.44, '脱硫打渣');

INSERT INTO mold (mold_no, mold_name, mold_type, cavity_count, life_count, used_count, fit_material, status, storage_slot, note, created_at) VALUES
('M-WAX-011',  '离心叶轮蜡型壳',     'WAX',         4,   60,   18, 'DUCTILE_IRON', 'AVAILABLE', '蜡模区 A-01', '型腔完好，可继续排产', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 40 DAY),
('M-WAX-012',  '双吸泵体蜡型壳',     'WAX',         2,   50,   46, 'CARBON_STEEL', 'AVAILABLE', '蜡模区 A-02', '剩余不多，准备补充新壳', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 38 DAY),
('M-WAX-013',  '三通阀体蜡型壳',     'WAX',         6,   40,   40, 'STAINLESS',    'SCRAPPED',  '报废区 X-03', '已到寿命上限，停止使用', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 36 DAY),
('M-SAND-021', '减速机箱体树脂砂型', 'RESIN_SAND',  2,   80,   32, 'CAST_IRON',    'IN_USE',    '砂型区 B-01', '在用，每班翻砂一次', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 34 DAY),
('M-SAND-022', '机床底座树脂砂型',   'RESIN_SAND',  1,  100,   88, 'CAST_IRON',    'AVAILABLE', '砂型区 B-02', '接近寿命，注意监控', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 32 DAY),
('M-CER-031',  '增压器陶瓷型壳',     'CERAMIC',     4,   30,    7, 'STAINLESS',    'AVAILABLE', '陶瓷区 C-01', '新壳，尺寸精度最好', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 30 DAY),
('M-CER-032',  '航空接头陶瓷型壳',   'CERAMIC',     8,   25,   12, 'STAINLESS',    'AVAILABLE', '陶瓷区 C-02', '八型腔，一次出八件', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 28 DAY),
('M-MET-041',  '铝合金压铸金属型',   'METAL',       4, 5000, 3120, 'ALUMINUM',     'IN_USE',    '金属型区 D-01', '每班喷涂脱模剂', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 26 DAY),
('M-MET-042',  '铸铝端盖金属型',     'METAL',       2, 3000, 2880, 'ALUMINUM',     'AVAILABLE', '金属型区 D-02', '剩余寿命不足一成', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 24 DAY),
('M-MET-043',  '铸钢法兰金属外型',   'METAL',       1, 1200, 1200, 'CARBON_STEEL', 'SCRAPPED',  '报废区 X-04', '型腔龟裂，已报废', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 22 DAY);

INSERT INTO casting (casting_no, casting_name, material, heat_id, mold_id, cavity_no, theory_weight, actual_weight, status, customer, operator, pour_time, created_at) VALUES
('C-2601-0101', '离心泵叶轮 φ180',    'STAINLESS',    1,  6, '1',  12.40,  12.86, 'STORED',     '江苏恒力泵业',   '陈志远', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR),
('C-2601-0102', '离心泵叶轮 φ180',    'STAINLESS',    1,  6, '2',  12.40,  12.92, 'STORED',     '江苏恒力泵业',   '陈志远', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 9 HOUR),
('C-2601-0103', '不锈钢泵盖法兰',      'STAINLESS',    2,  7, '1',   8.60,   8.71, 'REWORK',     '江苏恒力泵业',   '陈志远', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
('C-2601-0104', '不锈钢阀体毛坯',      'STAINLESS',    2,  7, '2',   9.20,   NULL, 'INSPECTING', '浙江瓯海阀门',   '陈志远', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR),
('C-2602-0201', '减速机箱体 HJ-320',   'CAST_IRON',    3,  4, '1',  86.50,  87.30, 'STORED',     '山东临工传动',   '范国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR),
('C-2602-0202', '机床底座 800×1200',   'CAST_IRON',    3,  5, '1', 210.00, 212.40, 'STORED',     '沈阳机床配套',   '范国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR),
('C-2602-0203', '灰铁轴承端盖',        'CAST_IRON',    3,  4, '2',  18.40,  18.62, 'POURED',     '山东临工传动',   '范国栋', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 7 HOUR),
('C-2603-0301', '球铁托轮 φ240',       'DUCTILE_IRON', 4,  1, '1',  32.60,  33.05, 'REINSPECT',  '河南卫华起重',   '黄玉良', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 6 HOUR),
('C-2603-0302', '航空管路接头',        'STAINLESS',    7,  7, '1',   3.25,   3.31, 'INSPECTING', '航宇机附件厂',   '许建国', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
('C-2603-0303', '高压管路三通',        'STAINLESS',    7,  7, '2',   4.10,   NULL, 'SCRAPPED',   '航宇机附件厂',   '许建国', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
('C-2604-0401', '铝合金压铸端盖',      'ALUMINUM',    8,  8, '1',   2.85,   2.90, 'STORED',     '宁波旭升汽配',   '林俊宏', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('C-2604-0402', '铝合金散热壳体 φ240', 'ALUMINUM',    9,  8, '2',   5.40,   NULL, 'REWORK',     '宁波旭升汽配',   '林俊宏', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('C-2604-0403', '铝合金支架总成',      'ALUMINUM',    9,  9, '1',   1.95,   1.99, 'INSPECTING', '苏州明志科技',   '林俊宏', NULL, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('C-2605-0501', '铸钢齿轮箱上盖',      'CARBON_STEEL', 5, 10, '1', 128.60, 130.20, 'STORED',     '太原重工配套',   '文国庆', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR),
('C-2605-0502', '铸钢机架横梁',        'CARBON_STEEL', 12, 10, '1', 320.00,  NULL, 'POURED',     '太重融资租赁',   '文国庆', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 8 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 12 HOUR);

INSERT INTO inspection (inspect_no, casting_id, casting_no, defect_count, verdict, hardness_hb, inspector, remark, inspect_time) VALUES
('I-26030101-01', 1,  'C-2601-0101', 0, 'PASS',   187.50, '钱广安', '尺寸与外观型式检验均合格，允许入库', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR),
('I-26030101-02', 2,  'C-2601-0102', 1, 'PASS',   192.30, '钱广安', '一处轻微砂眼，打磨后复检合格',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 5 HOUR),
('I-26030201-01', 5,  'C-2602-0201', 0, 'PASS',   214.60, '周立群', '箱体壁厚均匀，硬度符合 HT250 要求',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 4 HOUR),
('I-26030301-01', 6,  'C-2602-0202', 2, 'REWORK', 208.40, '周立群', '结合面群孔 + 侧壁夹渣，需补焊后重加工', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
('I-26030401-01', 10, 'C-2603-0303', 3, 'SCRAP',  176.20, '钱广安', '拐角贯穿裂纹，直接判废不允许回用',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
('I-26030501-01', 11, 'C-2604-0401', 0, 'PASS',    82.40, '孙佩林', '压铸件致密度良好，无明显缺陷',       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('I-26030502-01', 12, 'C-2604-0402', 1, 'REWORK',  78.60, '孙佩林', '顶面气孔群，需重新负压浸渗处理',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
('I-26030601-01', 14, 'C-2605-0501', 1, 'PASS',   235.80, '周立群', '腹板一处小砂眼，按标准允许补焊',     UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
-- 返修闭环示例（9、10 为复检单，IR- 前缀；旧的 4、7 号首次检验单即首次缺陷快照，仍在本表里照常查看）
('IR-0001-R1',   6,  'C-2602-0202', 0, 'PASS',   216.20, '周立群', '补焊重加工后结合面与侧壁复检合格',   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
('IR-0002-R1',  12,  'C-2604-0402', 1, 'REWORK',  79.10, '孙佩林', '浸渗后仍有一处残余气孔，安排二次浸渗', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR);

INSERT INTO defect (inspect_id, defect_type, severity, defect_position, size_mm, quantity, remark) VALUES
(2, 'SAND_HOLE', 'MINOR',    '叶轮前盖板',   1.20, 2, '直径 1.2mm，打磨可消除'),
(4, 'POROSITY',  'MAJOR',    '箱体结合面',   3.50, 5, '群孔，需补焊重加工'),
(4, 'INCLUSION', 'MINOR',    '箱体侧壁',     2.10, 3, '夹渣需修磨'),
(5, 'CRACK',     'CRITICAL', '三通拐角',    18.60, 1, '贯穿性裂纹，判废'),
(5, 'SHRINKAGE', 'MAJOR',    '三通厚壁处',   8.40, 4, '缩松面积超标'),
(5, 'POROSITY',  'MINOR',    '法兰端面',     2.60, 6, '密集性小气孔'),
(7, 'POROSITY',  'MAJOR',    '端盖顶面',     4.20, 3, '需负压浸渗返修'),
(8, 'SAND_HOLE', 'MINOR',    '齿轮箱腹板',   1.80, 2, '允许补焊后使用');
-- 注：复检单 9(合格无缺陷) / 10(再返修,1 条缺陷) 的缺陷记录随返修闭环种子一起插入，见文件末尾。

-- =====================================================================
-- 返修处置与复检闭环种子数据
-- 单1 铸件6：首次判返修(检验4) → 补焊重加工 → 复检合格(检验9) 入库（完整闭环）
-- 单2 铸件12：首次判返修(检验7) → 负压浸渗 → 复检仍返修(检验10) → 第2轮待返修（积压）
-- 单3 铸件3：首次判返修(检验11)后尚未登记处置（返修中积压，可演示完成/撤回）
-- 单4 铸件9：首次判返修(检验12)并已登记完成待复检（可演示并发复检、撤回、判废终止）
-- =====================================================================

-- 单3、单4 的首次检验单（判返修）必须先于返修数据插入：它俩是 rework_order.snapshot_inspect_id
-- 与 rework_round.source_inspect_id 指向的缺陷快照
INSERT INTO inspection (id, inspect_no, casting_id, casting_no, defect_count, verdict, hardness_hb, inspector, remark, inspect_time) VALUES
(11, 'I-26030103-01', 3,  'C-2601-0103', 1, 'REWORK', 190.10, '钱广安', '法兰密封面一处轻微缩松，补焊后复检',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(12, 'I-26030301-02', 9,  'C-2603-0301', 1, 'REWORK', 183.50, '钱广安', '接头密封面微裂纹，限局部补焊返修',    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR);

INSERT INTO defect (inspect_id, defect_type, severity, defect_position, size_mm, quantity, remark) VALUES
(11, 'SHRINKAGE', 'MINOR',  '泵盖法兰密封面', 1.60, 1, '轻微缩松，补焊可消除'),
(12, 'CRACK',     'MINOR',  '接头密封面',     2.20, 1, '浅表微裂纹，未贯穿，允许一次补焊'),
-- 返修闭环复检缺陷：10=IR-0002-R1 复检再返修发现的残余气孔，即第2轮的缺陷快照
(10, 'POROSITY', 'MINOR',    '端盖顶面边缘', 1.40, 1, '残余单个气孔，二次浸渗可消除');

INSERT INTO rework_order (id, rework_no, casting_id, casting_no, snapshot_inspect_id, stage, current_round, created_at, closed_at) VALUES
(1, 'RW-26030301-01', 6,  'C-2602-0202', 4,  'STORED',       1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(2, 'RW-26030502-01', 12, 'C-2604-0402', 7,  'REWORKING',    2, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL),
(3, 'RW-26030103-01', 3,  'C-2601-0103', 11, 'REWORKING',    1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR, NULL),
(4, 'RW-26030301-02', 9,  'C-2603-0301', 12, 'REINSPECTING', 1, UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, NULL);

INSERT INTO rework_round (rework_id, round_no, source_inspect_id, round_status, rework_method, responsible_person, completed_at, rework_note, completed_by, reinspect_id, reinspect_verdict, reinspect_time, reinspector, created_at) VALUES
-- 单1 第1轮：补焊 + 重加工，复检合格
(1, 1, 4,  'PASS', 'WELD', '周大伟', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR,
   '结合面孔群清根后分层补焊，焊后重新铣结合面并探伤；侧壁夹渣打磨见光。', '范国栋',
   9, 'PASS', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, '周立群',
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
-- 单2 第1轮：负压浸渗，复检仍返修
(2, 1, 7,  'REWORK', 'IMPREGNATION', '郑海峰', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR,
   '整壳 0.6MPa 负压浸渗 20 分钟后烘干固化。', '范国栋',
   10, 'REWORK', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR, '孙佩林',
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
-- 单2 第2轮：基于复检单10 的残余气孔生成的新待办（尚未处置）
(2, 2, 10, 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
-- 单3 第1轮：待返修
(3, 1, 11, 'PENDING', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL,
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
-- 单4 第1轮：已登记完成待复检（尚未创建复检单，可撤回）
(4, 1, 12, 'COMPLETED', 'WELD', '周大伟', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR,
   '接头密封面微裂纹清根补焊后抛修，等待复检判定。', '黄玉良',
   NULL, NULL, NULL, NULL,
   UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR);

-- 更正说明示例：单1 复检合格后补焊责任人署名误填，只能追加更正（原始记录保留）
INSERT INTO rework_correction (rework_id, round_id, target_type, content, operator, created_at)
SELECT 1, r.id, 'REWORK', '返修责任人实际为「吴大伟」，登记时误写为「周大伟」，以此条为准，原登记记录保留。', '范国栋',
       UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR
FROM rework_round r WHERE r.rework_id = 1 AND r.round_no = 1;

-- 事件流（种子历史按时间顺序补齐）
INSERT INTO rework_event (rework_id, round_no, event_type, operator, detail, created_at) VALUES
(1, 1, 'CREATED',     '周立群', '首次检验单 I-26030301-01 判返修，进入返修队列（缺陷快照已留存）', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 3 HOUR),
(1, 1, 'COMPLETED',   '范国栋', '责任人 周大伟 · 补焊 · 结合面孔群清根补焊重加工，侧壁夹渣打磨',      UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(1, 1, 'REINSPECTED', '周立群', '复检合格（复检单 IR-0001-R1）',                                    UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(1, 1, 'CLOSED_STORED','周立群', '返修闭环结束，铸件入库',                                          UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(1, 1, 'CORRECTED',   '范国栋', '追加「返修处置」更正：责任人实际为吴大伟，原登记记录保留',          UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(2, 1, 'CREATED',     '孙佩林', '首次检验单 I-26030502-01 判返修，进入返修队列（缺陷快照已留存）', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(2, 1, 'COMPLETED',   '范国栋', '责任人 郑海峰 · 负压浸渗 · 整壳 0.6MPa 浸渗 20 分钟',              UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(2, 1, 'REINSPECTED', '孙佩林', '复检仍需返修（复检单 IR-0002-R1，缺陷 1 条），第 2 轮待办已生成',  UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(3, 1, 'CREATED',     '钱广安', '首次检验单 I-26030103-01 判返修，进入返修队列（缺陷快照已留存）', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 2 HOUR),
(4, 1, 'CREATED',     '钱广安', '首次检验单 I-26030301-02 判返修，进入返修队列（缺陷快照已留存）', UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR),
(4, 1, 'COMPLETED',   '黄玉良', '责任人 周大伟 · 补焊 · 密封面微裂纹清根补焊后抛修',                UTC_TIMESTAMP() + INTERVAL 8 HOUR - INTERVAL 1 HOUR);


-- 种子数据使用了显式 id，把自增计数器拨到现有最大值之后，避免运行时新单据撞主键
ALTER TABLE inspection   AUTO_INCREMENT = 100;
ALTER TABLE rework_order AUTO_INCREMENT = 100;
ALTER TABLE rework_round AUTO_INCREMENT = 100;
