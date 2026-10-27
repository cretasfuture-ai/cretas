-- V20261103_90__line_capacity_and_equipment_requirements.sql
--
-- S4 产能与设备口径 (Owner 2026-10-27: 「看具体产线情况, 都需要支持, 按对话建厂或工厂配置时设置」)
--   1. production_line_capacity_configs  每条产线 (可再细到产品) 每小时能做多少, 单位由配置决定:
--      PIECE 件 / KG 千克 / BATCH 批。product_type_id 为 NULL = 该线通用。
--   2. product_equipment_requirements     做某个产品必须用到哪些设备 (显式设备 ID, 不靠字符串猜)。
--
-- 插单可行性只读这两张表: 找不到与订单同单位的产能配置、或产品没配所需设备 ⇒ MISSING (未验证),
-- 不换算单位、不套 500 kg 默认值。旧的 production_lines.hourly_capacity / equipment_ids 与
-- factory_capacity_configs.hourly_capacity 保留但不再当作已配置读数。
-- 存量为空 = 所有工厂的产能与设备都是 MISSING, 与改动前 (插单恒为未验证) 一致, 不放松。

CREATE TABLE production_line_capacity_configs (
    id                  BIGSERIAL     PRIMARY KEY,
    factory_id          VARCHAR(50)   NOT NULL,
    production_line_id  VARCHAR(36)   NOT NULL,
    product_type_id     VARCHAR(100),
    capacity_per_hour   NUMERIC(14,3) NOT NULL CHECK (capacity_per_hour > 0),
    capacity_unit       VARCHAR(16)   NOT NULL CHECK (capacity_unit IN ('PIECE', 'KG', 'BATCH')),
    source              VARCHAR(32)   NOT NULL CHECK (source IN ('CONVERSATIONAL_BUILD', 'FACTORY_CONFIG')),
    configured_by       BIGINT        NOT NULL,
    effective_from      TIMESTAMP     NOT NULL,
    created_at          TIMESTAMP     NOT NULL,
    updated_at          TIMESTAMP     NOT NULL,
    deleted_at          TIMESTAMP
);

-- 一条线 + 一个产品 (或通用) 只能有一条在用配置; 改配置 = 软删旧行再插新行, 旧行留作审计
CREATE UNIQUE INDEX uk_plcc_line_product_active
    ON production_line_capacity_configs (factory_id, production_line_id, COALESCE(product_type_id, ''))
    WHERE deleted_at IS NULL;

CREATE INDEX idx_plcc_factory_product
    ON production_line_capacity_configs (factory_id, product_type_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE production_line_capacity_configs IS
    'S4: 产线 (可细到产品) 每小时产能, 单位由对话建厂或工厂配置设定; 插单可行性只认同单位的行';

CREATE TABLE product_equipment_requirements (
    id               BIGSERIAL    PRIMARY KEY,
    factory_id       VARCHAR(50)  NOT NULL,
    product_type_id  VARCHAR(100) NOT NULL,
    equipment_id     BIGINT       NOT NULL,
    source           VARCHAR(32)  NOT NULL CHECK (source IN ('CONVERSATIONAL_BUILD', 'FACTORY_CONFIG')),
    configured_by    BIGINT       NOT NULL,
    created_at       TIMESTAMP    NOT NULL,
    updated_at       TIMESTAMP    NOT NULL,
    deleted_at       TIMESTAMP
);

CREATE UNIQUE INDEX uk_per_product_equipment_active
    ON product_equipment_requirements (factory_id, product_type_id, equipment_id)
    WHERE deleted_at IS NULL;

COMMENT ON TABLE product_equipment_requirements IS
    'S4: 做该产品必须用到的设备 (factory_equipment.id); 产品没有任何在用行 = 设备口径 MISSING';
