-- LIUSHANMEN: 把四张旧 ACTIVE 配方的调料行复制到一张族图(工艺 197, family a07e9883…)的四份新草稿里,
-- 节点键换成各线自己的「搅拌」节点 p:<line>:4。owner 2026-09-02 授权直接改库(工作区 API 对无价物料拒绝绑定)。
-- ⛔ 只复制旧行, 不编任何数; 已由 API 插过的物料跳过; price_source 留 null(物料档案无价, 诚实空着)。
BEGIN;
CREATE TEMP TABLE m (line text, old_recipe text, new_prefix text) ON COMMIT DROP;
INSERT INTO m VALUES
 ('ms',  'e085deab-3c22-4dec-8704-06b4c1c580a5', '59047b23'),
 ('xcb', '65958c0c-721d-4dc8-8f5b-2aad97ddc88c', 'fcf9deb4'),
 ('srb', 'f3b2cf8d-a523-43aa-991a-2e6248e88a56', 'd252fbc9'),
 ('hbp', '52d227ab-adaf-4a4a-bee3-4bfb80997efc', 'a67fe69f');
CREATE TEMP TABLE tgt ON COMMIT DROP AS
  SELECT m.line, m.old_recipe, r.id AS new_recipe
    FROM m JOIN bom_recipes r ON r.factory_id='LIUSHANMEN' AND r.status='DRAFT' AND r.id LIKE m.new_prefix||'%';
\echo === 目标草稿(必须 4 行) ===
SELECT line, left(new_recipe,8) FROM tgt ORDER BY line;
INSERT INTO bom_seasoning_items
  (recipe_id, factory_id, section, seq, name, dosage_per_kg_g, price_source1, price_source2, count_in_seasoning, remark,
   work_process_id, material_type_id, subsequent_pot_ratio, workflow_process_node_id, cost_scope, cost_scope_key)
SELECT t.new_recipe, 'LIUSHANMEN', o.section,
       (SELECT coalesce(max(seq),-1)+1 FROM bom_seasoning_items x WHERE x.recipe_id=t.new_recipe AND x.deleted_at IS NULL)
         + row_number() OVER (PARTITION BY t.new_recipe ORDER BY o.seq) - 1,
       o.name, o.dosage_per_kg_g, NULL, NULL, o.count_in_seasoning, o.remark,
       '88656240-ab38-4617-b7f0-b55474b70603', o.material_type_id, o.subsequent_pot_ratio,
       'p:'||t.line||':4', 'OUTPUT_EXCLUSIVE', 'm:'||t.line||':fin'
  FROM tgt t JOIN bom_seasoning_items o ON o.recipe_id=t.old_recipe AND o.deleted_at IS NULL
 WHERE NOT EXISTS (SELECT 1 FROM bom_seasoning_items n WHERE n.recipe_id=t.new_recipe AND n.deleted_at IS NULL AND n.material_type_id=o.material_type_id);
\echo === 复制后每份新草稿的调料行数 (预期 美式1 / 小吃吧5 / 熟肉饼7 / 汉堡排11) ===
SELECT t.line, count(n.*) FROM tgt t LEFT JOIN bom_seasoning_items n ON n.recipe_id=t.new_recipe AND n.deleted_at IS NULL GROUP BY t.line ORDER BY t.line;
COMMIT;
