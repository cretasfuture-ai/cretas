-- 回料(半成品)可以作为一行进标准配方 BOM  (2026-08-29)
--
-- 【问题】不良品在下一批当回料重投 (六膳门熟肉饼 19.51% / 小吃吧 5.67% / 美式 5%),
-- 但标准配方里**没有位置**记录「这个产品用 19.51% 回料」:
--   bom_recipe_items.material_type_id 是 NOT NULL ⇒ 每一行必须指向一个**原料档案**
--   (raw_material_types), 而回料是**半成品** (semi_finished_inventory / PTSEMI-* SKU)。
--
-- 【方案】material_type_id 改为可空 + CHECK 异或: 它与 semi_finished_ref_code 恰有一个非空。
--
-- ⛔ 没有走另一个方案 (给回料再建一份 raw_material_types 档案): 那会在原料仓里长出一个
--    假的「可领物料」, 而回料实际在半成品库 —— 操作员会看到两个入口, 领错就对不上账。
--
-- 【为什么不是新建一套列】semi_finished_ref_code / sub_product_type_id **早就在这张表上**
-- (V20261002_04 加的), 而且是活的: NestedBomCostService 用 semiFinishedRefCode 做
-- 「先做后用」(优先从 WIP 取移动均价, 取不到才降级到子 BOM 设计成本),
-- FactoryMaterialRequisitionItem.MaterialCategory.SEMI_FINISHED 的注释写着
-- 「N5: 从 BOM semiFinishedRefCode / subProductTypeId 识别」。
-- 这套机制就是为这件事建的, 只是 material_type_id 的 NOT NULL 让它一次都用不上
-- (全库 semi_finished_ref_code 非空行数 = 0)。
--
-- ⚠️ 加约束前**先断言存量全部满足**。不满足就让本迁移失败, ⛔ 不放宽约束 ——
--    「把闸调宽到刚好放过存量」等于这道闸从来没有守过任何东西。

-- ── ① 存量前置断言: 每一行都必须已经满足新的异或 ────────────────────────────────
DO $$
DECLARE
    violating_rows BIGINT;
    total_rows     BIGINT;
BEGIN
    SELECT COUNT(*) INTO total_rows FROM bom_recipe_items;

    SELECT COUNT(*) INTO violating_rows
      FROM bom_recipe_items
     WHERE (material_type_id IS NOT NULL) = (semi_finished_ref_code IS NOT NULL);

    RAISE NOTICE 'bom_recipe_items 存量 % 行, 违反异或 % 行', total_rows, violating_rows;

    IF violating_rows > 0 THEN
        RAISE EXCEPTION
            'bom_recipe_items 有 % 行同时/都不 持有 material_type_id 与 semi_finished_ref_code, '
            '不能加异或约束。请先人工核对这些行 (SELECT id, recipe_id, material_type_id, '
            'semi_finished_ref_code FROM bom_recipe_items WHERE (material_type_id IS NOT NULL) '
            '= (semi_finished_ref_code IS NOT NULL)) —— ⛔ 不要把约束改宽来放过它们。',
            violating_rows;
    END IF;
END $$;

-- ── ② material_type_id 允许为空 (半成品行不指向原料档案) ────────────────────────
ALTER TABLE bom_recipe_items ALTER COLUMN material_type_id DROP NOT NULL;

-- ── ③ 异或: 一行要么是原料行, 要么是半成品行, 不能既是又是 / 既不是也不是 ────────
ALTER TABLE bom_recipe_items DROP CONSTRAINT IF EXISTS chk_bri_material_ref_xor;

ALTER TABLE bom_recipe_items ADD CONSTRAINT chk_bri_material_ref_xor
    CHECK ((material_type_id IS NOT NULL) <> (semi_finished_ref_code IS NOT NULL));

-- ── ④ 分类白名单加 SEMI_FINISHED ───────────────────────────────────────────────
-- 与 V20261029_37 (加 BYPRODUCT) 同一处白名单。⛔ 那次的教训是「枚举加了值, DB CHECK
-- 白名单没跟上」—— 照做就是: 加值的同一个迁移里把白名单一起放开, 不留到下一次。
-- 存量影响 = 0: 放宽前不可能存在 SEMI_FINISHED 行 (旧约束就是这么禁的)。
ALTER TABLE bom_recipe_items DROP CONSTRAINT IF EXISTS chk_bri_category;

ALTER TABLE bom_recipe_items ADD CONSTRAINT chk_bri_category
    CHECK (material_category IN ('RAW', 'AUXILIARY', 'PACKAGING', 'BYPRODUCT', 'SEMI_FINISHED'));

COMMENT ON COLUMN bom_recipe_items.material_type_id IS
    '原料档案外键 raw_material_types.id; 半成品(回料)行为 NULL, 身份改由 semi_finished_ref_code 承担 (chk_bri_material_ref_xor 保证恰有一个非空)';

COMMENT ON COLUMN bom_recipe_items.semi_finished_ref_code IS
    '半成品(回料)引用编码 = semi_finished_inventory.intermediate_batch_no; 非空即代表本行是半成品行, 此时 material_type_id 必须为 NULL';

COMMENT ON COLUMN bom_recipe_items.material_category IS
    'RAW / AUXILIARY / PACKAGING / SEMI_FINISHED = 投入; BYPRODUCT = 产出的副产声明(SKU + 预计产出量), 不计入成本池';
