-- V20261114_83: AI 助手「先问口径」做成工厂开关 (INTENT-DISTILL-20261111, Steve 2026-11-14 拍板: 按工厂选程度, 默认激进)
--
-- 配置放进现有的 factory_thresholds (工厂行 > 全局 '*' 行 > 代码兜底), ⛔ 不新建注册表; 只插全局默认行。
-- 工厂在「系统设置 → 业务阈值配置 → AI」里看和改; Java IntentAskPolicyResolver 读出后随识别请求交给 Python。
--
--   ai.intent.ask_policy  AGGRESSIVE
--     CONSERVATIVE  保守 = 多问: 用户话里缺对象、缺范围就先问一句 (史浩禾口径, #1711 的 v3)
--     AGGRESSIVE    激进 = 少问: 能给合理默认就先办、先展示, 写操作直接出确认单让用户补全核对 (Steve 300 题口径, 默认)
--   认不出 / 取不到的值按 AGGRESSIVE 并计数。两档都不放宽写操作: 写意图照旧先出确认卡、逐笔确认。
--   Python 全局回滚开关 AI_DECISION_LUNA_POLICY (v1 / v3 / v4) 优先于这里的按厂档位。

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'ai.intent.ask_policy', 'AI', 'STRING', 'AGGRESSIVE', 'AGGRESSIVE',
   'AI 助手什么时候先问一句再办: CONSERVATIVE = 保守, 话里缺了对象或范围就先问; AGGRESSIVE = 激进 (默认), 能按默认办的先办、先展示并写明默认, 要改数据的直接出确认单让用户补全核对。两种都不会不经确认就改数据。',
   NULL, NULL, NULL)
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
