-- V20261103_140__factory_material_balance_thresholds.sql
--
-- 缺口清单 #07 物料平衡: 「未解释差额占投入」的黄/红阈值, 工厂级一个值 (先不分工序)。
-- 默认 5% / 15%, 即沿用原有色标与 App/web 报工 15% 提示, 零行为变化。
-- 幂等: ADD COLUMN IF NOT EXISTS; 不 seed 任何工厂。

ALTER TABLE factory_settings
    ADD COLUMN IF NOT EXISTS material_balance_warn_pct NUMERIC(5,2) NOT NULL DEFAULT 5.00,
    ADD COLUMN IF NOT EXISTS material_balance_red_pct  NUMERIC(5,2) NOT NULL DEFAULT 15.00;

COMMENT ON COLUMN factory_settings.material_balance_warn_pct IS
    '物料平衡黄色阈值 (%): 未解释差额占投入超过此值标黄. 默认 5.';
COMMENT ON COLUMN factory_settings.material_balance_red_pct IS
    '物料平衡红色阈值 (%): 未解释差额占投入超过此值标红, 同时是报工当场提示的触发线. 默认 15.';
