-- 缺陷 47 (owner 2026-09-02 裁定): 冰水 / 水 / 蒸汽 / 现场自制高汤 等「自备料」——
-- 「它没有所谓的库存一说」。
--
-- 标记挂在【物料类型本身】, 不是某一条 BOM 配方行 —— 「没有库存一说」描述的是这个物料
-- (与 raw_material_types.category 描述"材质"是同一个道理: 属性挂在恰当的那一层), 不是
-- 它出现在哪张配方里。
--
-- 效果 (owner 原话「appear on the requisition sheet as a recipe reminder, never deduct
-- stock and never block」):
--   领料单生成时该物料仍作为提醒行出现 (不会消失), 但不强制填数量、确认领料/调拨
--   不做批次查找也不因缺库存被拦; 报工 planNative 自动投料同理跳过库存查找与短缺判定,
--   不产生批次消耗。
--
-- 🔴 之前两个维度是焊死的: 绑定物料档案 ⇒ 出现 AND 扣库存 AND 缺货即拦;
--    不绑定 ⇒ 不扣库存但也【不出现】(那是"消失", 不是"备忘录")。今晚冰水在⑤搅拌
--    撞的墙正是这个焊死状态。
--
-- factory_material_requisition_items.self_supplied 是【生成需求单那一刻】的快照 ——
-- 与既有 material_name / material_category 同一套「BOM 展开时定格」做法, ⛔ 不做实时
-- join: 避免每次读需求单都重新查物料档案, 也让"这条单当初是不是按自备料展开的"这件事
-- 本身可追溯 (物料档案后续被改回非自备料, 老单的行为不应该跟着变)。

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS self_supplied BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN raw_material_types.self_supplied IS
    '自备料(owner 原话:"它没有所谓的库存一说")。冰水/水/蒸汽/现场自制高汤等现场取用、'
    '不走采购入库的物料。true 时: 领料单/报工投料仍列出该物料作配方提醒, 但不要求填写'
    '数量、不做库存查找、不因库存不足被拦、不产生批次消耗。';

ALTER TABLE factory_material_requisition_items
    ADD COLUMN IF NOT EXISTS self_supplied BOOLEAN NOT NULL DEFAULT false;

COMMENT ON COLUMN factory_material_requisition_items.self_supplied IS
    '生成需求单那一刻 raw_material_types.self_supplied 的快照(与 material_name/'
    'material_category 同一套"展开时定格"做法, 不做实时 join)。true 时确认领料/调拨'
    '跳过该行的批次查找与库存不足拦截, 允许留空不填数量。';
