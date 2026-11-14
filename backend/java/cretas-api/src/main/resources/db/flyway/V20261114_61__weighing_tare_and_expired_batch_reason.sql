-- 迁移 P5「数据疑点与录入校验」(DECISIONS R34/R35/R36, MIGRATION-PLAN 第七节 P5)。
--
-- 1) production_reports.tare_weight: 称重报工的皮重 (kg)。
--    口径同原型: 良品净重 = 良品毛重 − 皮重; output_quantity 存的仍是良品净重 (出成率 = 良品 ÷ 投入, 不变),
--    皮重单独留痕, 回读时能还原毛重。为空 = 没录皮重 (旧报工 / 不带容器称), ⛔ 不回填成 0。
-- 2) material_batches.expired_on_create_reason: R35 建批次时到期日已早于今天, 仍要建时必填的原因。
--    为空 = 建批次时没过期 (或是本列上线前建的); 服务端校验必填, 这里再用 CHECK 兜底长度。
-- 只加可空列, 不回填, 不改已有数据。IF NOT EXISTS: 可重复执行。

ALTER TABLE production_reports ADD COLUMN IF NOT EXISTS tare_weight NUMERIC(12, 2);

ALTER TABLE production_reports DROP CONSTRAINT IF EXISTS chk_production_reports_tare_weight_nonneg;
ALTER TABLE production_reports ADD CONSTRAINT chk_production_reports_tare_weight_nonneg
    CHECK (tare_weight IS NULL OR tare_weight >= 0);

ALTER TABLE material_batches ADD COLUMN IF NOT EXISTS expired_on_create_reason VARCHAR(200);
