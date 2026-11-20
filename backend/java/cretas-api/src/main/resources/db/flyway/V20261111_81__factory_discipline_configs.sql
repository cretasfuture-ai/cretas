-- V20261111_81: 工厂纪律口径改为按工厂配置 (纪律排查 A3 / A7 / A11 / A13 / A14)
--
-- Steve 2026-11-10 总原则: 是否拦、怎么拦都由工厂自己配置, 不写死; 配置放进现有的
-- factory_thresholds (阈值中心: 工厂行 > 全局 '*' 行 > 代码兜底), ⛔ 不新建注册表。
--
-- 本迁移只插【全局默认行】(factory_id = '*'), 不给具体工厂插行 —— 「没有工厂行」就是
-- 「跟随全局默认」(同 V20261101_03 的理由)。
--
-- 默认值 = Steve 裁定的默认:
--   A3  quality.batch_release.mode                    QC_ONLY   只能走质检放行
--       quality.batch_release.manual_roles            ''        (改为 QC_OR_MANUAL 时) 谁能手动放行; 空 = 不另限角色
--       quality.batch_release.require_reason          1         手动放行必须填原因
--   A7  production.completion.require_settlement      1         完工必须先结单 (开, 所有工厂含存量)
--   A11 production.receipt.over_receive_mode          BLOCK     入库多收硬拦 (= 现状)
--       production.receipt.over_receive_rate          0.05      WITHIN_RATE 模式下的比例
--   A13 production.time_limit.thaw_backfill_max_minutes 480     解冻开始最多往前补登 8 小时
--   A14 production.backdate.max_days                  2         补录窗口 T-2 (= 现状)
--
-- 字符串配置写错 (认不出的值) 一律按最严处理 (A3 → QC_ONLY, A11 → BLOCK), 并在拦截文案里
-- 说出当前配置值, 不静默按宽处理。

-- 1. 全局默认行 ---------------------------------------------------------------
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'quality.batch_release.mode', 'QUALITY', 'STRING', 'QC_ONLY', 'QC_ONLY',
   '批次改为可用(放行)的规则: QC_ONLY = 只能走质检放行; QC_OR_MANUAL = 质检放行之外, 允许指定角色在仓库页/AI 手动放行。不论怎么配, 每次放行都记录是谁放的。',
   NULL, NULL, NULL),
  ('*', 'quality.batch_release.manual_roles', 'QUALITY', 'STRING', '', '',
   '手动放行 (QC_OR_MANUAL 时) 允许的角色代码, 英文逗号分隔, 如 quality_manager,factory_super_admin; 留空 = 有仓库读写权限的人都可以。',
   NULL, NULL, NULL),
  ('*', 'quality.batch_release.require_reason', 'QUALITY', 'INTEGER', '1', '1',
   '手动放行是否必须填原因: 1 = 必须; 0 = 可不填 (仍记录放行人)。',
   NULL, '0', '1'),
  ('*', 'production.completion.require_settlement', 'PRODUCTION', 'INTEGER', '1', '1',
   '计划完工是否必须先核对结单: 1 = 必须结单 (不能直接点完成); 0 = 允许直接完工 (超产须填原因)。',
   NULL, '0', '1'),
  ('*', 'production.receipt.over_receive_mode', 'PRODUCTION', 'STRING', 'BLOCK', 'BLOCK',
   '仓库实收多于生产报产时: BLOCK = 硬拦; WITHIN_RATE = 不超过 over_receive_rate 比例时放行, 超出仍拦; ALWAYS_WITH_REASON = 不设上限一律放行。放行的多收与少收同口径: 差异在称重容差内 (kg 为 10 公斤) 不要原因、记为称重误差; 超出容差必须填差异原因并选责任方, 进中转挂账。',
   NULL, NULL, NULL),
  ('*', 'production.receipt.over_receive_rate', 'PRODUCTION', 'DECIMAL', '0.05', '0.05',
   '仓库多收比例上限 (仅 WITHIN_RATE 模式使用): 实收最多为报产的 (1 + 比例) 倍。0.05 = 5%。',
   '比率', '0', '1'),
  ('*', 'production.time_limit.thaw_backfill_max_minutes', 'PRODUCTION', 'INTEGER', '480', '480',
   '解冻开始最多可往前补登多少分钟 (补登必须填原因并留痕); 0 = 不允许补登, 只能登记当前时刻。',
   '分钟', '0', '10080'),
  ('*', 'production.backdate.max_days', 'PRODUCTION', 'INTEGER', '2', '2',
   '补录窗口: 报工/领料/入库的业务日期最多可往前补几天。2 = 允许到前天 (T-2); 0 = 只能当天。',
   '天', '0', '31')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;

-- 2. A3 放行台账: 每次批次从「不可投产」(质检中/过期/报废/不良) 变为可投产都记一行 ---------
-- ⛔ 不继承软删: 审计行不能被软删, 也不能在计数查询里悄悄少几行 (同 material_batch_date_corrections)。
CREATE TABLE IF NOT EXISTS material_batch_releases (
    id                 VARCHAR(191) PRIMARY KEY,
    factory_id         VARCHAR(50)  NOT NULL,
    material_batch_id  VARCHAR(191) NOT NULL,
    from_status        VARCHAR(32)  NOT NULL,
    to_status          VARCHAR(32)  NOT NULL,
    channel            VARCHAR(32)  NOT NULL
        CHECK (channel IN ('WAREHOUSE_STATUS_CHANGE', 'AI_BATCH_UPDATE', 'QC_RELEASE_DECISION', 'QC_DISPOSITION', 'QC_RELEASE')),
    reason             VARCHAR(500),
    inspection_id      VARCHAR(191),
    released_by        BIGINT,
    released_by_role   VARCHAR(64),
    released_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_mbr_batch ON material_batch_releases (material_batch_id, released_at);
CREATE INDEX IF NOT EXISTS idx_mbr_factory ON material_batch_releases (factory_id, released_at);
COMMENT ON TABLE material_batch_releases IS
  '批次放行台账 (纪律排查 A3): 谁、从什么状态、经哪条路、因为什么把批次改成可投产。released_by 为空仅当调用方确实没有登录人 (系统任务)。';

-- 3. A7 默认开对所有工厂生效 (Steve 2026-11-10 定口径, 含存量工厂与 LIUSHANMEN) ----------
-- 不逐厂写配置行: 全局行 = 1 即所有工厂默认要求先结单; 工厂想允许直接完工, 自己在阈值中心配一行 0。
-- RN 没有结单入口, 手机上直接完工会收到 409 PRODUCTION_SETTLEMENT_REQUIRED, App 专门提示去网页端结单。

-- 4. A7 直接完工的超产原因 ------------------------------------------------------
ALTER TABLE production_plans ADD COLUMN IF NOT EXISTS over_plan_reason VARCHAR(500);
COMMENT ON COLUMN production_plans.over_plan_reason IS
  '直接完工 (不经结单) 时实际产量超过计划产量的原因 (纪律排查 A7)。结单路径的超产原因在 production_settlements。';

-- 5. A13 解冻开始补登原因 -------------------------------------------------------
-- 登记发生的时刻 = created_at (BaseEntity), 事实时刻 = started_at; 两者不同即为补登。
ALTER TABLE process_time_marks ADD COLUMN IF NOT EXISTS backfill_reason VARCHAR(500);
COMMENT ON COLUMN process_time_marks.backfill_reason IS
  '补登原因: started_at 早于登记时刻 (created_at) 时必填 (纪律排查 A13)。';
