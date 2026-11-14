-- 迁移 P5「数据疑点与录入校验」(DECISIONS R34/R35/R36, MIGRATION-PLAN 第七节 P5)。
--
-- 1) production_reports.tare_weight: 称重报工的皮重 (kg)。
--    口径同原型: 良品净重 = 良品毛重 − 皮重; output_quantity 存的仍是良品净重 (出成率 = 良品 ÷ 投入, 不变),
--    皮重单独留痕, 回读时能还原毛重。为空 = 没录皮重 (旧报工 / 不带容器称), ⛔ 不回填成 0。
-- 2) material_batches.expired_on_create_reason: R35 建批次时到期日已早于今天, 仍要建时必填的原因。
--    为空 = 建批次时没过期 (或是本列上线前建的); 服务端校验必填 (2–200 字), 列宽 VARCHAR(200) 兜住长度上限 (没有另加 CHECK)。
-- 3) factory_thresholds 两条全局默认行: 数据疑点「单位明显不符」的上下限 (kg), 按厂可改 (工厂行 > '*' 行 > 代码默认)。
--      production.data_suspect.max_weight_kg  5000   整合 / 称重报工投入或良品 ≥ 它 ⇒ 像是把克当成了千克
--      production.data_suspect.min_input_kg   0.1    整合 / 称重报工投入 < 它 ⇒ 像是把吨当成了千克
--    只标疑点, 不挡报工。
-- 只加可空列 / 只插全局默认行, 不回填, 不改已有数据。IF NOT EXISTS / ON CONFLICT DO NOTHING: 可重复执行。

ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS tare_weight NUMERIC(12, 2);

ALTER TABLE production_reports DROP CONSTRAINT IF EXISTS chk_production_reports_tare_weight_nonneg;
ALTER TABLE production_reports ADD CONSTRAINT chk_production_reports_tare_weight_nonneg
    CHECK (tare_weight IS NULL OR tare_weight >= 0);

ALTER TABLE material_batches ADD COLUMN IF NOT EXISTS expired_on_create_reason VARCHAR(200);

INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'production.data_suspect.max_weight_kg', 'PRODUCTION', 'DECIMAL', '5000', '5000',
   '数据疑点「单位明显不符」上限: 整合报工 / 称重报工的投入或良品换成千克后达到这个数, 标为「像是把克当成了千克」, 由车间核对。只标疑点, 不挡报工。单笔真有这么重的工厂请调大。',
   'kg', '1', NULL),
  ('*', 'production.data_suspect.min_input_kg', 'PRODUCTION', 'DECIMAL', '0.1', '0.1',
   '数据疑点「单位明显不符」下限: 整合报工 / 称重报工的投入换成千克后不到这个数, 标为「像是把吨当成了千克」, 由车间核对。只标疑点, 不挡报工。三段报工的投入行 (如少量盐、香料) 不判。',
   'kg', '0', NULL)
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;
