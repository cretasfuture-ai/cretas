-- V20261101_02__semi_finished_inventory_is_rework.sql
-- 「这一行装的是回料」(金检/X光剔出的不良品, 回到下一批靠前工序重新投入)。
--
-- 为什么要落成一列: 回料必须能穿过投料下拉的阶段过滤 (process_order < 本道, 防同批内回锅),
-- 而那个过滤跑在工厂级行集上、拿不到画布 —— 判据必须在行上可读。
-- ⛔ 不用把 process_order 改成 0 表达: 那一列还被出成率卡片用来排序并串成本链 (上一行的
--    produced = 下一行的投入), 回料排最前会把良品那道的出成率算成 1900%。
--
-- 判据来源: 建行时由画布 nodes_json 的 data.isRework (WorkflowReworkNodes) + workflow_task_ports
-- 反查得出 —— ⛔ 不是客户端在 production_reports.semi_outputs 里带上来的那个展示值。
--
-- 存量行一律 FALSE: 回料链路本次才上线, 之前不存在回料行, 无需回填。
ALTER TABLE semi_finished_inventory
    ADD COLUMN IF NOT EXISTS is_rework boolean NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN semi_finished_inventory.is_rework IS
  '这一行装的是回料(不良品回投); true 时可穿过投料下拉的阶段过滤回到靠前工序。判据来自画布 data.isRework';
