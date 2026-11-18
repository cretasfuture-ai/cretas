-- V20261118_41: 工序时限超时改为工厂开关, 默认只警告 (PROTO-Q41, Steve 2026-11-18 拍板「默认只警告」)
--
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行 > 代码兜底), ⛔ 不新建注册表; 只插全局默认行。
-- 读取处唯一: FactoryDisciplinePolicy.timeLimitBlockOnExceed (只有显式 1 才拦, 写错按关)。
--
--   production.time_limit.block_on_exceed   0   0 = 只警告: 超时 / 未登记开始解冻照常投料, 超时写一条待处置记录
--                                                 (process_time_limit_events, 状态 OPEN) 供复盘, 质检仍可复检放行或判不合格;
--                                               1 = 超时拦截 (原行为): 超时的料不能领用, 要质检员复检放行或判不合格。
--
-- ⚠️ 默认 0 对所有工厂生效, 含存量工厂: 之前的「超时就拦」改为只警告, 想继续拦的工厂自己配一行 1。
-- 不改表结构: 警告档的留痕复用现有的 OPEN 记录。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'production.time_limit.block_on_exceed', 'PRODUCTION', 'INTEGER', '0', '0',
   '工序时限超时拦不拦领用: 0 = 只警告 (照常投料, 超时记一条待处置记录供复盘, 还没登记开始解冻也只提醒); 1 = 超时拦截 (超时的料不能领用, 要质检员复检放行或判不合格; 没登记开始解冻不能投料)。',
   NULL, '0', '1')
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
