-- 回滚 V20261103_80: 重建旧唯一索引。
-- ⚠️ 先查跨节点重复, 有重复则建索引会失败 —— 需要先人工决定保留哪一行:
--   SELECT recipe_id, work_process_id, material_type_id, count(*)
--     FROM bom_seasoning_items
--    WHERE deleted_at IS NULL AND work_process_id IS NOT NULL AND material_type_id IS NOT NULL
--    GROUP BY 1, 2, 3 HAVING count(*) > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uq_bsi_recipe_wp_material
    ON bom_seasoning_items(recipe_id, work_process_id, material_type_id)
    WHERE deleted_at IS NULL
      AND work_process_id IS NOT NULL
      AND material_type_id IS NOT NULL;
