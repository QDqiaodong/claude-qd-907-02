-- H2（MySQL 兼容模式）冒烟测试用最小结构，只保留返修闭环涉及的表，与 MySQL schema.sql 列定义一致
SET MODE MySQL;

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
);

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
    created_at DATETIME
);

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
);

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
    remark VARCHAR(128)
);

CREATE TABLE charge_item (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    charge_id BIGINT NOT NULL,
    material_code VARCHAR(16),
    material_name VARCHAR(48),
    weight_kg DECIMAL(10,2),
    ratio DECIMAL(6,2),
    remark VARCHAR(64)
);

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
    status VARCHAR(16),
    customer VARCHAR(48),
    operator VARCHAR(32),
    pour_time DATETIME,
    created_at DATETIME
);

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
    inspect_time DATETIME
);

CREATE TABLE defect (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    inspect_id BIGINT NOT NULL,
    defect_type VARCHAR(16),
    severity VARCHAR(16),
    defect_position VARCHAR(32),
    size_mm DECIMAL(6,2),
    quantity INT,
    remark VARCHAR(64)
);

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
    UNIQUE KEY uk_rework_casting (casting_id)
);

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
    UNIQUE KEY uk_round_no (rework_id, round_no)
);

CREATE TABLE rework_correction (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rework_id BIGINT NOT NULL,
    round_id BIGINT NOT NULL,
    target_type VARCHAR(16),
    content VARCHAR(255),
    operator VARCHAR(32),
    created_at DATETIME
);

CREATE TABLE rework_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    rework_id BIGINT NOT NULL,
    round_no INT,
    event_type VARCHAR(16),
    operator VARCHAR(32),
    detail VARCHAR(255),
    created_at DATETIME
);

ALTER TABLE casting ADD CONSTRAINT fk_c_heat FOREIGN KEY (heat_id) REFERENCES heat(id);
ALTER TABLE casting ADD CONSTRAINT fk_c_mold FOREIGN KEY (mold_id) REFERENCES mold(id);
