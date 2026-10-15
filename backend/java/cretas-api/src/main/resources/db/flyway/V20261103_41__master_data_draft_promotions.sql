-- 主数据草稿转正记录: 产品 / 物料 / 工序的 DRAFT -> 正式表 (product_types / raw_material_types /
-- work_processes) 的每一次转正, 在这里留一行。
--
-- ## 为什么另建一张表, 而不是给草稿行加一个 PROMOTED 状态
--
-- 草稿表的 status 被 CHECK 钉死为 'DRAFT' (V20261103_03 / _04 / _05), 而下游草稿 (包装 / BOM /
-- 过敏原 / 设备 / 供应商 / 危害) 校验上游草稿时要求 status = 'DRAFT', 并把 status、lock_version、
-- updated_at 算进自己的摘要。改草稿行任何一列, 下游已写好的草稿就会整批失配。
-- => 转正一行草稿都不改, 只在本表记一笔。
--
-- ## 状态
--
--   CLAIMED   执行事务开头先插入, 由 uk_master_data_draft_promotion_idempotency 让同一幂等键的
--             并发执行在写正式表之前就撞约束。它与正式表写入在同一个事务里: 事务回滚它也回滚,
--             所以正常路径上它不会以 CLAIMED 留在库里。
--   PROMOTED  正式表已写入并按字段回读一致。必须带 live_target_id 与 live_readback_digest。
--   FAILED    保留给日后「失败也留痕」的独立事务，首期执行失败是整体回滚, 不写 FAILED 行。
--
-- ## 约束写法
--
-- 条件 CHECK 一律写成 CASE WHEN <非空判别列> THEN <完整布尔> ELSE <完整布尔> END:
-- 分支 OR 的写法在形状不对时求值为 NULL, 而 PostgreSQL 对求值为 NULL 的 CHECK 是放行的
-- (见 V20261103_23)。空白串能过 NOT NULL, 所以身份列另加 btrim 守卫 (见 V20261103_28)。
-- digest 列用 VARCHAR(64) + 正则, 不用 CHAR(64) (见 V20261103_29)。
--
-- ## 没有外键的列, 以及为什么
--
-- draft_id 按 capability_id 指向三张不同的草稿表之一 (多态), 一个外键表达不了，而这三张草稿表
-- 也没有 (id, factory_id) 唯一约束可供复合外键引用 —— 补上它就是改草稿表结构, 不在本切片范围。
-- 租户归属由执行事务里「按 id AND factory_id 加锁读草稿」保证。本表自带 (id, factory_id) 唯一约束,
-- 供日后的子表做复合租户外键。
--
-- 只新建本表与它的索引，不读、不写、不改任何既有表的任何一行。

CREATE TABLE IF NOT EXISTS master_data_draft_promotions (
    id                          VARCHAR(36)  NOT NULL PRIMARY KEY,
    factory_id                  VARCHAR(50)  NOT NULL,
    capability_id               VARCHAR(20)  NOT NULL,
    draft_id                    VARCHAR(36)  NOT NULL,
    blueprint_id                VARCHAR(100) NOT NULL,
    blueprint_version           BIGINT       NOT NULL,
    source_digest               VARCHAR(64)  NOT NULL,
    draft_desired_digest        VARCHAR(64)  NOT NULL,
    draft_lock_version          BIGINT       NOT NULL,
    action                      VARCHAR(20)  NOT NULL,
    natural_key                 VARCHAR(255) NOT NULL,
    live_target_id              VARCHAR(191) NULL,
    promotion_preview_digest    VARCHAR(64)  NOT NULL,
    idempotency_key             VARCHAR(128) NOT NULL,
    operator_id                 BIGINT       NOT NULL,
    status                      VARCHAR(20)  NOT NULL,
    live_readback_digest        VARCHAR(64)  NULL,
    failure_code                VARCHAR(80)  NULL,
    lock_version                BIGINT       NOT NULL DEFAULT 0,
    created_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at                  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted_at                  TIMESTAMP    NULL,
    CONSTRAINT uk_master_data_draft_promotion_id_factory UNIQUE (id, factory_id),
    CONSTRAINT uk_master_data_draft_promotion_idempotency UNIQUE (factory_id, idempotency_key),
    CONSTRAINT ck_master_data_draft_promotion_capability
        CHECK (capability_id IN ('products', 'materials', 'processes')),
    -- 首期只做新建。更新意图 (草稿带 target 活表 id) 在预览里就被拦下, 永远到不了这里。
    CONSTRAINT ck_master_data_draft_promotion_action CHECK (action = 'CREATE'),
    CONSTRAINT ck_master_data_draft_promotion_status
        CHECK (status IN ('CLAIMED', 'PROMOTED', 'FAILED')),
    CONSTRAINT ck_master_data_draft_promotion_identity CHECK (
        btrim(id) <> '' AND btrim(factory_id) <> '' AND btrim(draft_id) <> ''
        AND btrim(blueprint_id) <> '' AND btrim(natural_key) <> ''
        AND btrim(idempotency_key) <> ''
        AND blueprint_version > 0 AND draft_lock_version >= 0 AND operator_id > 0),
    CONSTRAINT ck_master_data_draft_promotion_digests CHECK (
        source_digest ~ '^[0-9a-f]{64}$'
        AND draft_desired_digest ~ '^[0-9a-f]{64}$'
        AND promotion_preview_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_master_data_draft_promotion_readback_digest CHECK (
        CASE WHEN live_readback_digest IS NULL
             THEN TRUE
             ELSE live_readback_digest ~ '^[0-9a-f]{64}$' END),
    -- 一行 PROMOTED 必须说清楚它写了哪一行正式数据、回读到了什么，否则就是「报告成功却指不出
    -- 成果」—— 假绿的标准长相。失败必须说原因，未完成的认领两样都还没有。
    CONSTRAINT ck_master_data_draft_promotion_status_shape CHECK (
        CASE WHEN status = 'PROMOTED'
             THEN live_target_id IS NOT NULL AND btrim(live_target_id) <> ''
                  AND live_readback_digest IS NOT NULL AND failure_code IS NULL
             WHEN status = 'FAILED'
             THEN failure_code IS NOT NULL AND btrim(failure_code) <> ''
                  AND live_target_id IS NULL AND live_readback_digest IS NULL
             ELSE live_target_id IS NULL AND live_readback_digest IS NULL
                  AND failure_code IS NULL END)
);

-- 同一份草稿只能有一条有效的 PROMOTED。执行事务已经按草稿行加锁串行化, 这条是数据库自己的兜底:
-- 加锁顺序以外的写入路径 (回填脚本 / 直连 psql / 日后的新入口) 也不能把一份草稿转正两次。
CREATE UNIQUE INDEX IF NOT EXISTS uk_master_data_draft_promotion_promoted_draft
    ON master_data_draft_promotions (factory_id, capability_id, draft_id)
    WHERE status = 'PROMOTED' AND deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_master_data_draft_promotion_lineage
    ON master_data_draft_promotions (factory_id, blueprint_id, blueprint_version)
    WHERE deleted_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_master_data_draft_promotion_draft
    ON master_data_draft_promotions (factory_id, capability_id, draft_id, created_at DESC)
    WHERE deleted_at IS NULL;
