-- 批次日期更正台账 —— 带新旧值的字段级留痕。
--
-- 为什么需要它 (2026-08-20 owner 裁定「入库日期也要管」):
--   把 receiptDate(inbound_date) 纳入账实红线之后, 全仓**没有任何**能更正它的正路:
--     · PUT /material-batches/{id}      —— 本轮起被账实闸拦掉
--     · POST /material-batches/{id}/adjust —— javadoc 白纸黑字「调整批次数量」, 只动数量
--     · 盘点 (/warehouse/stocktakes)     —— apply() 写的也只是数量差异
--   只锁不给路 = 造一个死路。⇒ 锁与正路必须同一次落地。
--
-- ⛔ 为什么不复用 material_batch_adjustments:
--   那张表的 quantity_before / adjustment_quantity / quantity_after 三列都是 NOT NULL。
--   拿它记一次日期更正, 只能写三个 0 进去 —— 数量台账里从此躺着一批「0 → 0」的调整行,
--   而真正变了的东西(日期)只能塞进 reason 自由文本。本仓形态 A¹⁰:
--   「兜底的默认值会把『我不知道』翻译成『是 0』」。这里是同一个坑的写入侧。
--
-- ⚠️ 为什么是 date 列而不是通用的 varchar old_value/new_value:
--   通用字符串台账既查不了日期区间, 也拦不住有人哪天把数量塞进来绕开盘点。
--   本表用 DATE 类型 + field_name 的 CHECK 白名单 —— 从物理上装不下数量。
--   将来要纳入 production_date / expire_date, 改 CHECK 即可, 类型不用动。
--
-- ⛔ 无 deleted_at / 不继承 BaseEntity: 审计行不该能被软删。
--   (BaseEntity 的类级 @Where(deleted_at IS NULL) 会被 Hibernate 拼进每一条 JPQL,
--    见 memory/project_soft_delete_makes_every_jpql_lie_about_occupancy)

CREATE TABLE IF NOT EXISTS material_batch_date_corrections (
    id                 VARCHAR(191) PRIMARY KEY,

    -- 冗余存一份工厂号: 台账要能按工厂独立审计, 不必回 join material_batches
    -- (批次真被删了, 这条痕迹仍然要在)。
    factory_id         VARCHAR(50)  NOT NULL,
    material_batch_id  VARCHAR(191) NOT NULL,

    -- 被更正的字段 (DTO 属性名)。CHECK 白名单 = 这张表永远只装日期。
    field_name         VARCHAR(32)  NOT NULL,

    -- ⚠️ old_value 可空, 且 NULL 的含义是「原来这一格就是空的」, 不是「没记下来」。
    --    inbound_date 在实体上标了 NOT NULL, 所以 receiptDate 这一支实际不会出现 NULL;
    --    留空的余地是给将来纳入的可空日期列 (expire_date / production_date) 的。
    old_value          DATE,
    new_value          DATE         NOT NULL,

    -- 必填。长度与 material_batch_adjustments.reason 对齐(varchar 255), 让两条留痕路径
    -- 对用户是同一个限长, 前端只需要记一个数。
    reason             VARCHAR(255) NOT NULL,

    corrected_by       BIGINT       NOT NULL,
    -- 当时的角色码 (JWT role)。留痕要能回答「谁按什么身份改的」——
    -- 用户的角色事后会变, 只存 user_id 就答不了这个问题。
    corrected_by_role  VARCHAR(50),
    corrected_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    created_at         TIMESTAMP    NOT NULL DEFAULT NOW(),

    CONSTRAINT chk_mbdc_field CHECK (field_name IN ('receiptDate')),
    -- 没变就不该留痕: 一条 old = new 的审计行是噪声, 会稀释真正的更正。
    CONSTRAINT chk_mbdc_changed CHECK (old_value IS DISTINCT FROM new_value)
);

-- 唯一的读法: 「这个批次的日期被改过几次、谁改的、为什么」。
CREATE INDEX IF NOT EXISTS idx_mbdc_batch
    ON material_batch_date_corrections (material_batch_id, corrected_at DESC);

-- 按工厂审计。
CREATE INDEX IF NOT EXISTS idx_mbdc_factory
    ON material_batch_date_corrections (factory_id, corrected_at DESC);

COMMENT ON TABLE material_batch_date_corrections IS
    '批次日期更正的字段级留痕(含新旧值)。写入方: POST /material-batches/{id}/receipt-date-correction。'
    '⛔ 数量/成本类更正不走这里 —— 那些走盘点或 /adjust, 写 material_batch_adjustments。';
COMMENT ON COLUMN material_batch_date_corrections.old_value IS
    'NULL = 原来这一格就是空的, 不是「没记下来」。';
COMMENT ON COLUMN material_batch_date_corrections.field_name IS
    'DTO 属性名。CHECK 白名单限定, 当前只有 receiptDate —— 扩它之前先确认那一列的可空性。';
COMMENT ON COLUMN material_batch_date_corrections.corrected_by_role IS
    '更正当时的 JWT 角色码。角色事后会变, 只存 user_id 答不了「他当时凭什么能改」。';
