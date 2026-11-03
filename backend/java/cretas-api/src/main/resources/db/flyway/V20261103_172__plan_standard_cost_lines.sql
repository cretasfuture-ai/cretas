-- 食品 DAG 缺口 09 (Steve 2026-11-03): 开计划时的标准成本快照。
-- 标准成本 = 计划锁定的配方/工艺版本算标准用量 × 开计划那一刻的物料单价; 写一次, 之后不随价格变。
-- unit_price 为空 = 开计划时物料档案没有单价 → 该项「无法比较」, 不猜。不可变记录, 无软删除列。
CREATE TABLE IF NOT EXISTS plan_standard_cost_lines (
    id                       BIGSERIAL PRIMARY KEY,
    factory_id               VARCHAR(50)  NOT NULL,
    plan_id                  VARCHAR(191) NOT NULL,
    line_type                VARCHAR(24)  NOT NULL,
    material_type_id         VARCHAR(191),
    material_name            VARCHAR(200),
    material_category        VARCHAR(32),
    parent_material_type_id  VARCHAR(191),
    product_type_id          VARCHAR(191),
    bom_recipe_id            VARCHAR(191),
    bom_version              INTEGER,
    workflow_input_port_id   VARCHAR(128),
    standard_basis           VARCHAR(24)  NOT NULL,
    standard_rate            NUMERIC(24,8),
    standard_unit            VARCHAR(20),
    raw_yield_rate           NUMERIC(9,4),
    unit_price               NUMERIC(15,4),
    price_unit               VARCHAR(20),
    price_source             VARCHAR(40),
    captured_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_pscl_line_type CHECK (line_type IN ('MATERIAL', 'SUBSTITUTE', 'LABOR')),
    CONSTRAINT chk_pscl_basis CHECK (standard_basis IN
        ('PER_OUTPUT_UNIT', 'PER_RAW_INPUT_KG', 'RAW_INPUT_SHARE', 'PER_OUTPUT_KG', 'NONE'))
);
CREATE INDEX IF NOT EXISTS idx_pscl_plan ON plan_standard_cost_lines (factory_id, plan_id);
COMMENT ON TABLE plan_standard_cost_lines IS '计划标准成本快照: 开计划时锁定配方的标准用量规则 + 当时物料单价, 之后不随价格变';
COMMENT ON COLUMN plan_standard_cost_lines.unit_price IS '开计划时物料档案未税单价; NULL=当时无单价, 该项无法比较';
