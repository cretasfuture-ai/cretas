-- V20261103_133__factory_block_start_on_open_shortage.sql
--
-- S4-08 缺料开工拦截 — 工厂级开关 (opt-in, 默认 OFF)。
--
-- 背景: S4 缺料处理链 (shortage_cases) 设计里, 计划关联着未解决的缺料实例时默认拦开工;
--   但六膳门 2026-06-12 现场走查定了 N1「开工无条件化」—— 缺料只预警、不卡开工, 是真实客户的要求。
--   Owner 2026-11-03 裁定: 做成按工厂的开关。
--
-- 行为:
--   • FALSE (默认, 所有工厂) → 开工照旧 (N1), ZERO 行为变化。
--   • TRUE  (逐工厂在「工厂配置」里打开) → 计划所属销售订单有未解决的缺料实例 (未关闭、缺口未解决、
--     仍有缺口行) 时拦开工; 可强制开工, 但必须写原因, 原因记进该缺料实例的处理记录。
--
-- 多租户安全: DEFAULT FALSE → 未显式配置的工厂 (现有 + 未来) 默认关闭; 本迁移不 seed 任何工厂为 TRUE。
-- 幂等: ADD COLUMN IF NOT EXISTS。

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS block_start_on_open_shortage BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN factory_settings.block_start_on_open_shortage IS
    '工厂级"缺料时拦开工"开关 (默认 false = 开工照旧, 缺料只预警, 即六膳门 N1). true = 计划所属销售订单有未解决的缺料实例时拦开工, 可强制开工但必须写原因并记入缺料实例的处理记录.';
