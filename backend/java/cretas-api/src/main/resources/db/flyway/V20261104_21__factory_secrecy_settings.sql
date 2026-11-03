-- =====================================================================
-- 工厂级保密配置 (Steve 2026-11-03 裁定: 每项保密都由平台按工厂开 / 关)
--
-- factory_secrecy_settings: 平台专属, 工厂端没有任何写入口 (写入只走
--   PUT /api/platform/factories/{factoryId}/secrecy/{itemCode}, 有效的平台超级管理员)。
--   某工厂某项「没有行」= 默认值: 一律关; AI_SECRECY_AUDIT 默认跟随 PROCESS_SECRET。
--   enabled 为 NULL 只允许出现在 AI_SECRECY_AUDIT 上, 表示「跟随工艺保密」。
--   ⛔ 迁移只建表, 不灌数据: 没有行就是默认关 (上线时 process_secret_policies 0 行, 不会让在生效的保密失效)。
--
-- factory_secrecy_setting_audit: 每次改动一行, 只追加 (触发器拒绝 UPDATE)。
--   「全开 / 全关」是逐项写入, 每项一行, 本身不存值。
--   不拒绝 DELETE: 平台删除工厂数据 (FactoryErasureService) 按 factory_id 删本厂全部行。
--
-- item_code 的取值与 Java 枚举 SecrecyItem 一致 (FactorySecrecyMigrationPostgresContractTest 逐项核对)。
-- =====================================================================
CREATE TABLE IF NOT EXISTS factory_secrecy_settings (
    factory_id  VARCHAR(50)  NOT NULL REFERENCES factories(id),
    item_code   VARCHAR(40)  NOT NULL,
    enabled     BOOLEAN,
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT       NOT NULL,
    CONSTRAINT pk_factory_secrecy_settings PRIMARY KEY (factory_id, item_code),
    CONSTRAINT ck_fss_item_code CHECK (item_code IN ('PROCESS_SECRET', 'PLATFORM_RECIPE_BLOCK', 'EGRESS_REDACTION',
                                                     'TRAINING_FILTER', 'CUSTOMER_HELD', 'AI_SECRECY_AUDIT')),
    CONSTRAINT ck_fss_enabled_follow CHECK (enabled IS NOT NULL OR item_code = 'AI_SECRECY_AUDIT')
);

CREATE TABLE IF NOT EXISTS factory_secrecy_setting_audit (
    id          BIGSERIAL    PRIMARY KEY,
    factory_id  VARCHAR(50)  NOT NULL REFERENCES factories(id),
    item_code   VARCHAR(40)  NOT NULL,
    old_value   VARCHAR(10)  NOT NULL,
    new_value   VARCHAR(10)  NOT NULL,
    actor_id    BIGINT       NOT NULL,
    actor_role  VARCHAR(50)  NOT NULL,
    reason      VARCHAR(500),
    confirmed   BOOLEAN      NOT NULL DEFAULT FALSE,
    bulk        BOOLEAN      NOT NULL DEFAULT FALSE,
    changed_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_fssa_item_code CHECK (item_code IN ('PROCESS_SECRET', 'PLATFORM_RECIPE_BLOCK', 'EGRESS_REDACTION',
                                                      'TRAINING_FILTER', 'CUSTOMER_HELD', 'AI_SECRECY_AUDIT')),
    -- UNSET = 改之前没有行 (默认值)
    CONSTRAINT ck_fssa_old_value CHECK (old_value IN ('UNSET', 'ON', 'OFF', 'FOLLOW')),
    CONSTRAINT ck_fssa_new_value CHECK (new_value IN ('ON', 'OFF', 'FOLLOW')),
    CONSTRAINT ck_fssa_changed CHECK (old_value <> new_value)
);

CREATE INDEX IF NOT EXISTS idx_fssa_factory_changed
    ON factory_secrecy_setting_audit (factory_id, changed_at DESC, id DESC);

CREATE OR REPLACE FUNCTION factory_secrecy_setting_audit_no_update()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'factory_secrecy_setting_audit is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_fssa_no_update ON factory_secrecy_setting_audit;
CREATE TRIGGER trg_fssa_no_update
    BEFORE UPDATE ON factory_secrecy_setting_audit
    FOR EACH ROW EXECUTE FUNCTION factory_secrecy_setting_audit_no_update();
