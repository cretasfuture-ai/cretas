-- V20261103_119__production_pots.sql
--
-- S3-16 锅次模型 (owner 2026-10-28 裁定: 开发, 不豁免)
--
-- 一锅 = 一个生产批次 (production_batches, 逐工序报工时每道工序各物化一个) 里的一次下锅 → 出锅。
-- 本表只记「锅」这件事本身: 锅号、下锅/出锅时间 (设备 / 锅次事件时间)、操作员、出锅量、关键工艺参数。
--   ⛔ 不记人工工时 —— 人工工时仍由报工的 laborSegments 独立记录, 两者是独立事实 (S3-16 判据)。
--   ⛔ 时间不确定时不硬填: started_at / ended_at 可空; time_source = CAMERA_CANDIDATE 的时间只是候选,
--      time_confirmed 恒为 false, 必须主管确认后才算精确时间 (CHECK 钉住)。
--   锅与批次的关联不明 (binding_status = PENDING_CONFIRMATION) 时照样记录、照样进召回视图,
--   但标「待确认」, 不当作精确事实。
-- 锅从不物理删除: 作废 = status VOIDED + 原因; 字段更正写 production_pot_corrections (V20261103_121)。
-- 全部 IF NOT EXISTS: 可重放。

CREATE TABLE IF NOT EXISTS production_pots (
    id                    BIGSERIAL PRIMARY KEY,
    factory_id            VARCHAR(50)    NOT NULL,
    production_batch_id   BIGINT         NOT NULL,
    production_plan_id    VARCHAR(191),
    process_sheet_row_id  BIGINT,
    process_code          VARCHAR(64),
    process_name          VARCHAR(100),
    pot_number            INTEGER        NOT NULL,
    equipment_label       VARCHAR(100),
    started_at            TIMESTAMP,
    ended_at              TIMESTAMP,
    time_source           VARCHAR(32)    NOT NULL DEFAULT 'MANUAL',
    time_confirmed        BOOLEAN        NOT NULL DEFAULT FALSE,
    binding_status        VARCHAR(32)    NOT NULL DEFAULT 'CONFIRMED',
    operator_id           BIGINT,
    operator_name         VARCHAR(100),
    output_quantity       NUMERIC(18,6),
    output_unit           VARCHAR(20),
    process_params        JSONB          NOT NULL DEFAULT '{}'::jsonb,
    source                VARCHAR(32)    NOT NULL DEFAULT 'POT_API',
    status                VARCHAR(16)    NOT NULL DEFAULT 'RECORDED',
    void_reason           VARCHAR(500),
    confirmed_by          BIGINT,
    confirmed_at          TIMESTAMP,
    remark                VARCHAR(500),
    created_by            BIGINT,
    created_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at            TIMESTAMP,
    CONSTRAINT ck_production_pots_number CHECK (pot_number > 0),
    CONSTRAINT ck_production_pots_time_source
        CHECK (time_source IN ('MANUAL', 'DEVICE', 'CAMERA_CANDIDATE', 'UNKNOWN')),
    CONSTRAINT ck_production_pots_candidate_unconfirmed
        CHECK (time_source <> 'CAMERA_CANDIDATE' OR time_confirmed = FALSE),
    CONSTRAINT ck_production_pots_binding
        CHECK (binding_status IN ('CONFIRMED', 'PENDING_CONFIRMATION')),
    CONSTRAINT ck_production_pots_status CHECK (status IN ('RECORDED', 'VOIDED')),
    CONSTRAINT ck_production_pots_void_reason
        CHECK (status <> 'VOIDED' OR (void_reason IS NOT NULL AND length(btrim(void_reason)) > 0)),
    CONSTRAINT ck_production_pots_source CHECK (source IN ('POT_API', 'PROCESS_SHEET')),
    CONSTRAINT ck_production_pots_window CHECK (started_at IS NULL OR ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT ck_production_pots_output CHECK (output_quantity IS NULL OR output_quantity >= 0)
);

-- 同一生产批次同一工序的锅号唯一 (作废的锅让出号)
CREATE UNIQUE INDEX IF NOT EXISTS uq_production_pots_active_number
    ON production_pots (factory_id, production_batch_id, COALESCE(process_code, ''), pot_number)
    WHERE deleted_at IS NULL AND status = 'RECORDED';

CREATE INDEX IF NOT EXISTS idx_production_pots_batch
    ON production_pots (factory_id, production_batch_id)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_production_pots_sheet_row
    ON production_pots (factory_id, process_sheet_row_id)
    WHERE deleted_at IS NULL AND process_sheet_row_id IS NOT NULL;

COMMENT ON TABLE production_pots IS
    'S3-16 锅次: 生产批次内的一次下锅/出锅; 设备/锅次时间与人工工时分开记; 作废不删, 更正留痕';
COMMENT ON COLUMN production_pots.time_source IS
    'MANUAL 人工录入 / DEVICE 设备事件 / CAMERA_CANDIDATE 摄像候选(永不自动成为精确时间) / UNKNOWN 未知';
COMMENT ON COLUMN production_pots.binding_status IS
    'CONFIRMED 锅批绑定明确 / PENDING_CONFIRMATION 关联不明, 保留待主管确认';
