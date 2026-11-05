-- =====================================================================
-- 平台 AI 分析入口的授权 (Steve 2026-11-05): 入口给平台角色用, 由有效的平台超级管理员开通;
-- 可以开给一个平台角色, 也可以开给一个平台账号。超级管理员本人不需要授权。
--
-- platform_ai_analysis_grants: 有行 = 已开通, 撤销就是删掉这一行 (判定每次现查, 撤销下一次调用就生效)。
--   不继承 BaseEntity、不软删: 历史都在下面的留痕表里。
--   grantee_ref: ROLE 时是 platform_admins.platform_role 的取值; ACCOUNT 时是 platform_admins.id 的字符串。
--   ⛔ 迁移只建表, 不灌数据: 上线时除超级管理员外没有人能用, 要开通就走授权接口。
--
-- platform_ai_analysis_grant_audit: 每次开通 / 撤销一行, 只追加 (触发器拒绝 UPDATE / DELETE / TRUNCATE)。
--   与 factory_secrecy_setting_audit 不同, 这里连 DELETE 也拒绝: 这张表不属于任何一家工厂,
--   不会被 FactoryErasureService 按工厂删。
-- =====================================================================
CREATE TABLE IF NOT EXISTS platform_ai_analysis_grants (
    grantee_type VARCHAR(10)  NOT NULL,
    grantee_ref  VARCHAR(50)  NOT NULL,
    granted_by   BIGINT       NOT NULL,
    granted_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    reason       VARCHAR(500) NOT NULL,
    CONSTRAINT pk_platform_ai_analysis_grants PRIMARY KEY (grantee_type, grantee_ref),
    CONSTRAINT ck_paag_type CHECK (grantee_type IN ('ROLE', 'ACCOUNT')),
    CONSTRAINT ck_paag_role CHECK (grantee_type <> 'ROLE'
                                   OR grantee_ref IN ('system_admin', 'operation_admin', 'auditor')),
    CONSTRAINT ck_paag_account CHECK (grantee_type <> 'ACCOUNT' OR grantee_ref ~ '^[1-9][0-9]*$'),
    CONSTRAINT ck_paag_reason CHECK (btrim(reason) <> '')
);

CREATE TABLE IF NOT EXISTS platform_ai_analysis_grant_audit (
    id           BIGSERIAL    PRIMARY KEY,
    action       VARCHAR(10)  NOT NULL,
    grantee_type VARCHAR(10)  NOT NULL,
    grantee_ref  VARCHAR(50)  NOT NULL,
    actor_id     BIGINT       NOT NULL,
    actor_role   VARCHAR(50)  NOT NULL,
    reason       VARCHAR(500) NOT NULL,
    changed_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_paaga_action CHECK (action IN ('GRANT', 'REVOKE')),
    CONSTRAINT ck_paaga_type CHECK (grantee_type IN ('ROLE', 'ACCOUNT')),
    CONSTRAINT ck_paaga_reason CHECK (btrim(reason) <> '')
);

CREATE INDEX IF NOT EXISTS idx_paaga_changed
    ON platform_ai_analysis_grant_audit (changed_at DESC, id DESC);

CREATE OR REPLACE FUNCTION platform_ai_analysis_grant_audit_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'platform_ai_analysis_grant_audit is append-only';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_paaga_no_change ON platform_ai_analysis_grant_audit;
CREATE TRIGGER trg_paaga_no_change
    BEFORE UPDATE OR DELETE ON platform_ai_analysis_grant_audit
    FOR EACH ROW EXECUTE FUNCTION platform_ai_analysis_grant_audit_append_only();

DROP TRIGGER IF EXISTS trg_paaga_no_truncate ON platform_ai_analysis_grant_audit;
CREATE TRIGGER trg_paaga_no_truncate
    BEFORE TRUNCATE ON platform_ai_analysis_grant_audit
    FOR EACH STATEMENT EXECUTE FUNCTION platform_ai_analysis_grant_audit_append_only();
