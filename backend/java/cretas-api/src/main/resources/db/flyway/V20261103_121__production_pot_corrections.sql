-- V20261103_121__production_pot_corrections.sql
--
-- S3-16 锅次: 更正保留 (判据「设备/锅次事件和人工工时是独立事实且更正保留」)。
-- 每次更正 / 确认 / 作废写一行: 改了哪个字段、原值、新值、原因、谁、何时。只追加, 不改不删。

CREATE TABLE IF NOT EXISTS production_pot_corrections (
    id            BIGSERIAL PRIMARY KEY,
    factory_id    VARCHAR(50)   NOT NULL,
    pot_id        BIGINT        NOT NULL REFERENCES production_pots (id),
    action        VARCHAR(16)   NOT NULL,
    field_name    VARCHAR(64)   NOT NULL,
    old_value     TEXT,
    new_value     TEXT,
    reason        VARCHAR(500)  NOT NULL,
    corrected_by  BIGINT,
    corrected_by_name VARCHAR(100),
    corrected_at  TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at    TIMESTAMP,
    CONSTRAINT ck_production_pot_corrections_action CHECK (action IN ('CORRECT', 'CONFIRM', 'VOID')),
    CONSTRAINT ck_production_pot_corrections_reason CHECK (length(btrim(reason)) > 0)
);

CREATE INDEX IF NOT EXISTS idx_production_pot_corrections_pot
    ON production_pot_corrections (factory_id, pot_id, corrected_at);
