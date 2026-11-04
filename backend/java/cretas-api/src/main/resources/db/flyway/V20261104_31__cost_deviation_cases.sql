-- V20261104_31__cost_deviation_cases.sql
--
-- 成本 / 损耗异常闭环: 成本差异与物料平衡偏离超阈值时, 生成与缺料同一种处理实例 (shortage_cases)。
--   1. shortage_cases.source_type 增加 COST_VARIANCE / MATERIAL_BALANCE, source_id = 生产计划 id。
--   2. factory_settings 两个告警阈值, 可空: NULL = 未配置, 不告警。
--      ⛔ 不沿用物料平衡表的 5/15 色标默认值: 色标是显示口径, 告警要工厂自己定。
--   3. cost_deviation_case_details 这类实例的读数与处理结果。金额只放这里, 不写进留痕文字
--      (留痕对能看实例的人都可见, 这里的金额读取时按采购价权限脱敏)。
-- 存量为空: 不回填, 工厂配置阈值后由扫描生成。

ALTER TABLE shortage_cases DROP CONSTRAINT IF EXISTS shortage_cases_source_type_check;
ALTER TABLE shortage_cases ADD CONSTRAINT shortage_cases_source_type_check
    CHECK (source_type IN ('SALES_ORDER', 'COST_VARIANCE', 'MATERIAL_BALANCE'));

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS cost_deviation_variance_pct NUMERIC(6,2),
    ADD COLUMN IF NOT EXISTS cost_deviation_balance_pct  NUMERIC(6,2);

COMMENT ON COLUMN factory_settings.cost_deviation_variance_pct IS
    '成本差异告警阈值 (%): 计划成本差异率绝对值超过此值生成异常. NULL = 未配置, 不告警.';
COMMENT ON COLUMN factory_settings.cost_deviation_balance_pct IS
    '物料平衡告警阈值 (%): 工序未解释差额占投入超过此值生成异常. NULL = 未配置, 不告警.';

CREATE TABLE cost_deviation_case_details (
    case_id             VARCHAR(36)   PRIMARY KEY REFERENCES shortage_cases (id),
    factory_id          VARCHAR(50)   NOT NULL,
    plan_id             VARCHAR(191)  NOT NULL,
    plan_number         VARCHAR(100),
    product_name        VARCHAR(200),
    metric_pct          NUMERIC(12,2) NOT NULL,
    threshold_pct       NUMERIC(6,2)  NOT NULL,
    amount_variance     NUMERIC(20,2),
    unknown_item_count  INTEGER       NOT NULL DEFAULT 0 CHECK (unknown_item_count >= 0),
    summary             TEXT          NOT NULL,
    below_threshold_at  TIMESTAMP,
    resolution_code     VARCHAR(32)   CHECK (resolution_code IN (
                            'REASONABLE', 'CORRECTED', 'STANDARD_UPDATE', 'DATA_ERROR')),
    resolution_note     TEXT,
    resolved_by         BIGINT,
    resolved_at         TIMESTAMP,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP
);

CREATE INDEX idx_cost_deviation_details_plan
    ON cost_deviation_case_details (factory_id, plan_id)
    WHERE deleted_at IS NULL;
