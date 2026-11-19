-- V20261118_99: 工序时限超时改为工厂开关, 默认只警告 (PROTO-Q41, Steve 2026-11-18 拍板「默认只警告」)
--
-- 版本号: prod flyway out-of-order=false, 当天其它批次 (#1814 _55 / #1816 _53 / #1834 _61) 会先上线,
-- 所以取 _71 (高于它们)。
--
-- 1. 开关 (factory_thresholds 全局默认行, ⛔ 不新建注册表)。读取处唯一: FactoryDisciplinePolicy.timeLimitBlockOnExceed
--    (只认 0 / 1, 其它值按默认 0 并打 warn 日志)。
--
--   production.time_limit.block_on_exceed   0   0 = 只警告: 超时 / 未登记开始解冻照常投料, 记一条复盘记录;
--                                               1 = 超时拦截 (原行为): 超时的料不能领用, 要质检员复检放行或判不合格。
--
-- ⚠️ 默认 0 对所有工厂生效, 含存量工厂: 之前的「超时就拦」改为只警告, 想继续拦的工厂自己配一行 1。
--
-- 2. process_time_limit_events.record_kind: 区分「被拦」与「只警告的复盘记录」。
--    现有字段表达不了: 判不合格要据此决定是否自动报废 (复审 M1), 未登记解冻的复盘记录要和超时记录各自去重 (复审 M2),
--    质检放行判废闸也要据此不把复盘结论当成判废。所以加一列, 存量行默认 BLOCKED (= 原含义, 行为不变)。
--      BLOCKED             超时被拦 (拦截档; 原有行)。判不合格 = 按超时量转出 / 批次置不合格 (原行为)。
--      WARN_EXCEEDED       只警告档超时照常投料。判不合格只记结论, 不动库存; 实际报损走报损流程。
--      WARN_START_MISSING  只警告档未登记开始解冻照常投料。没有起算点: start/deadline/covered 记为发生时刻,
--                          不参与放行豁免; 同一批次同一工序 (workflow_port_id) 只留一条待复盘。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'production.time_limit.block_on_exceed', 'PRODUCTION', 'INTEGER', '0', '0',
   '工序时限超时拦不拦领用: 0 = 只警告 (照常投料, 超时或没登记开始解冻都记一条复盘记录); 1 = 超时拦截 (超时的料不能领用, 要质检员复检放行或判不合格; 没登记开始解冻不能投料)。只认 0 和 1。',
   NULL, '0', '1')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;

ALTER TABLE process_time_limit_events
    ADD COLUMN IF NOT EXISTS record_kind VARCHAR(24) NOT NULL DEFAULT 'BLOCKED';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_ptle_record_kind') THEN
        ALTER TABLE process_time_limit_events
            ADD CONSTRAINT ck_ptle_record_kind
            CHECK (record_kind IN ('BLOCKED', 'WARN_EXCEEDED', 'WARN_START_MISSING'));
    END IF;
END $$;

COMMENT ON COLUMN process_time_limit_events.record_kind IS
  'BLOCKED = 超时被拦 (判不合格自动转出); WARN_EXCEEDED / WARN_START_MISSING = 只警告档的复盘记录 (判不合格只记结论, 报损走报损流程)';

-- 去重: 超时记录 (被拦 / 只警告) 仍是同一对象一条待处置; 未登记解冻的复盘记录按 批次 + 工序端口 各一条, 不占超时那一格
DROP INDEX IF EXISTS uq_ptle_open;
CREATE UNIQUE INDEX IF NOT EXISTS uq_ptle_open
    ON process_time_limit_events (factory_id, target_type, target_id)
    WHERE status = 'OPEN' AND deleted_at IS NULL AND record_kind <> 'WARN_START_MISSING';
CREATE UNIQUE INDEX IF NOT EXISTS uq_ptle_open_start_missing
    ON process_time_limit_events (factory_id, target_type, target_id, workflow_port_id)
    WHERE status = 'OPEN' AND deleted_at IS NULL AND record_kind = 'WARN_START_MISSING';
