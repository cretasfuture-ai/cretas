-- V20261103_106: S6-01 保存的动态业务视图
--
-- 只存「查询定义」(实体 / 过滤 / 列 / 排序), 绝不存结果行; 每次打开由服务端按当前用户的权限和本厂范围重算。
-- definition_json 用 TEXT (不是 jsonb): 内容由服务端白名单校验后写入, 读出后再校验一次, 不依赖库侧 JSON 能力。

CREATE TABLE IF NOT EXISTS saved_views (
    id             VARCHAR(64)  PRIMARY KEY,
    factory_id     VARCHAR(191) NOT NULL,
    owner_user_id  BIGINT       NOT NULL,
    name           VARCHAR(100) NOT NULL,
    entity_key     VARCHAR(50)  NOT NULL,
    definition_json TEXT        NOT NULL,
    version        INTEGER      NOT NULL DEFAULT 1,
    shared         BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    deleted_at     TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_saved_views_factory_owner ON saved_views (factory_id, owner_user_id);
CREATE INDEX IF NOT EXISTS idx_saved_views_factory_shared ON saved_views (factory_id, shared);

COMMENT ON TABLE saved_views IS '保存的动态业务视图: 只存查询定义, 打开时按当前用户权限重算';
COMMENT ON COLUMN saved_views.definition_json IS '视图定义 JSON (entity/columns/filters/sort/limit), 服务端白名单校验';
COMMENT ON COLUMN saved_views.version IS '定义版本, 每次修改定义 +1';
COMMENT ON COLUMN saved_views.shared IS 'true=本厂有该实体读权限的同事也可打开(各自按自己的权限重算)';
