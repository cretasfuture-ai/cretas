-- V20261103_160__cold_chain_equipment_locations.sql
--
-- 食品 DAG 缺口 15「温度关联批次」(Steve 2026-11-03: 先做软件侧, 硬件以后接)。
--
-- 冷链读数 / 告警原来只挂到设备 (cold_chain_equipment), 不知道设备在哪个库、库里有哪些批次。
--   1. cold_chain_equipment_locations  设备 ↔ 仓库 (factory_warehouses) 的对应关系, 带生效时段。
--      设备换库 = 关掉旧时段、开一条新时段; 旧时段不改写, 历史告警仍按当时的库位算受影响批次。
--   2. cold_chain_excursion_holds      某条超温告警把哪些批次转了质检 (转之前是什么状态、暴露多久、谁转的)。
--      待检状态本身沿用缺口 11 的机制 (原料/在制 INSPECTING, 成品 PENDING_INSPECTION), 这里只留痕, 不造新状态。
--
-- 存量为空: F006 / 其他工厂当前都没有冷链设备 (prod 实测 cold_chain_equipment 0 行), 不需要回填。

CREATE TABLE cold_chain_equipment_locations (
    id              BIGSERIAL     PRIMARY KEY,
    factory_id      VARCHAR(64)   NOT NULL,
    equipment_id    BIGINT        NOT NULL REFERENCES cold_chain_equipment (id),
    warehouse_id    VARCHAR(64)   NOT NULL REFERENCES factory_warehouses (id),
    effective_from  TIMESTAMP     NOT NULL,
    effective_to    TIMESTAMP,
    remark          VARCHAR(500),
    created_by      BIGINT,
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at      TIMESTAMP,
    CONSTRAINT ck_ccel_period CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- 一台设备同一时刻最多一条「还在生效」的库位 (时段之间不重叠由服务层在设备行锁内校验)
CREATE UNIQUE INDEX uk_ccel_open_per_equipment
    ON cold_chain_equipment_locations (equipment_id)
    WHERE effective_to IS NULL AND deleted_at IS NULL;

CREATE INDEX idx_ccel_factory_equipment
    ON cold_chain_equipment_locations (factory_id, equipment_id, effective_from)
    WHERE deleted_at IS NULL;

CREATE TABLE cold_chain_excursion_holds (
    id               BIGSERIAL     PRIMARY KEY,
    factory_id       VARCHAR(64)   NOT NULL,
    alert_id         BIGINT        NOT NULL REFERENCES cold_chain_alerts (id),
    target_type      VARCHAR(32)   NOT NULL CHECK (target_type IN (
                         'RAW_MATERIAL_BATCH', 'WIP_MATERIAL_BATCH', 'FINISHED_GOODS')),
    target_id        VARCHAR(191)  NOT NULL,
    batch_number     VARCHAR(64),
    warehouse_id     VARCHAR(64),
    exposure_minutes INTEGER       CHECK (exposure_minutes IS NULL OR exposure_minutes >= 0),
    previous_status  VARCHAR(32)   NOT NULL,
    held_status      VARCHAR(32)   NOT NULL,
    held_by          BIGINT        NOT NULL,
    held_by_name     VARCHAR(100),
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);

-- 同一告警同一批次只转一次 (重复点击幂等)
CREATE UNIQUE INDEX uk_cceh_alert_target
    ON cold_chain_excursion_holds (alert_id, target_type, target_id)
    WHERE deleted_at IS NULL;

CREATE INDEX idx_cceh_factory_alert
    ON cold_chain_excursion_holds (factory_id, alert_id)
    WHERE deleted_at IS NULL;
