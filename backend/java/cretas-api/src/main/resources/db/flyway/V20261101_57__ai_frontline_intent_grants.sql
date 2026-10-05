-- 2026-10-06 一线角色 AI 写操作开放名单 (owner: 先收窄, 管理端可开放)。
--
-- 一线角色 (operator / yield_operator / warehouse_worker / quality_inspector) 在 AI 里
-- 只能办理「已开放」的写操作: 日常工作的默认清单写在代码里 (FrontlineAiWriteGrantService.DEFAULT_OPEN),
-- 其余由工厂管理员在 web-admin「一线角色 AI 操作权限」逐个开放 —— 开放记录就是本表。
-- 读操作不受影响。
--
-- 按工厂、按角色、按意图一行。关闭 = 写 revoked_at/revoked_by (保留谁在什么时候开、谁关的), 不删行。
-- 同一 (工厂, 角色, 意图) 同时只能有一条有效记录: 部分唯一索引 (不是约束 —— 约束不能是部分的)。
-- 独立表, 不继承 BaseEntity (那套软删 @SQLDelete 在 MappedSuperclass 上不继承, 见仓库记忆)。

CREATE TABLE IF NOT EXISTS ai_frontline_intent_grants (
    id           BIGSERIAL    PRIMARY KEY,
    factory_id   VARCHAR(50)  NOT NULL,
    role_code    VARCHAR(50)  NOT NULL,
    intent_code  VARCHAR(50)  NOT NULL,
    granted_by   BIGINT,
    granted_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    revoked_by   BIGINT,
    revoked_at   TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_ai_frontline_grant_active
    ON ai_frontline_intent_grants (factory_id, role_code, intent_code)
    WHERE revoked_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_ai_frontline_grant_factory
    ON ai_frontline_intent_grants (factory_id);
