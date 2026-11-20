-- V20261111_92: 动态质检表「哪些项不合格才判整张 FAIL」改为工厂开关 (Steve 2026-11-11 拍板做成开关, 默认取交接单的 B)
--
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行 > 代码兜底), ⛔ 不新建注册表; 只插全局默认行。
--
--   quality.inspection.fail_scope  MINOR_FOR_REVIEW
--     ANY_ITEM          任一项不合格整张 FAIL (原行为)
--     MINOR_FOR_REVIEW  关键 / 主要项不合格整张 FAIL; 只有次要项不合格 ⇒ 条件合格, 标出这些项交质量经理决定 (默认)
--     CRITICAL_ONLY     只有关键项不合格才整张 FAIL; 主要 / 次要项不合格 ⇒ 条件合格, 交质量经理决定
--   没标严重程度的项按关键项算; 认不出的值按最严 ANY_ITEM。
--
-- 不变的: 整张 FAIL 照旧隔离成品、走处置复核; 已判不合格的批次不会被后补的条件合格改回去 (Owner 2026-11-04)。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'quality.inspection.fail_scope', 'QUALITY', 'STRING', 'MINOR_FOR_REVIEW', 'MINOR_FOR_REVIEW',
   '动态质检表里哪些项不合格才判整张不合格: ANY_ITEM = 任一项不合格就整张不合格; MINOR_FOR_REVIEW = 关键/主要项不合格整张不合格, 只有次要项不合格时判条件合格并标出这些项, 交质量经理决定; CRITICAL_ONLY = 只有关键项不合格才整张不合格, 主要/次要项交质量经理决定。没标严重程度的项按关键项算。',
   NULL, NULL, NULL),
  ('*', 'quality.notify.inspection_review', 'QUALITY', 'STRING', 'quality_manager', 'quality_manager',
   '质检表有项目不合格、但按本厂设置不判整张不合格 (交质量经理决定) 时通知谁: 英文岗位码, 逗号分隔; 配的岗位没有人时改发厂长。',
   NULL, NULL, NULL)
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
