-- 2026-10-05 一线角色 AI 助手入口的权限配置 (owner: 一线角色给 AI 入口, 不限制只读)。
--
-- 原则: **AI 能做的 = 这个角色在 App 里本来就能做的**, 不多不少。
-- 意图级 required_roles 是手写名单, 一线角色从来没被系统地配过 ⇒ 名单与 App 权限漂了:
--   * yield_operator 与 operator 共用同一个首页, 但 10 个列了 operator 的意图没列它 (打卡/考勤等)
--   * warehouse_worker 在 App 里能查库存/预警/出货、能领用原料, AI 里这些意图全拒
--
-- 只加不减: 本迁移只往名单里追加角色, 不移除任何角色、不动 required_permission。
-- ⛔ 故意不给 warehouse_worker 的 (App 里它也做不了):
--   REPORT_INVENTORY        —— App 的 /reports/inventory 要 finance:read / procurement:price:view (含金额)
--   SHIPMENT_CREATE         —— App 的 POST /sales/deliveries 要 sales:read_write
--   MATERIAL_ADJUST_QUANTITY / MATERIAL_UPDATE / MATERIAL_BATCH_CONSUME
--                           —— 2026-08-20 owner 内控裁定「仓管纯操作员无库存自主权, 库存变动必须单据+审批」
-- 只改平台级行 (factory_id IS NULL): 角色名单的鉴权按意图码查, 不看工厂 (见 AIIntentConfigRepository)。

-- ① yield_operator 与 operator 同权: 凡列了 operator 的意图都补上 yield_operator。
UPDATE ai_intent_configs
   SET required_roles = required_roles || '["yield_operator"]'::jsonb,
       updated_at = NOW()
 WHERE factory_id IS NULL
   AND deleted_at IS NULL
   AND jsonb_typeof(required_roles) = 'array'
   AND required_roles ? 'operator'
   AND NOT required_roles ? 'yield_operator';

-- ② warehouse_worker: App 里本来就能做的库存查询 / 预警 / 出货查询 / 领用原料。
UPDATE ai_intent_configs
   SET required_roles = required_roles || '["warehouse_worker"]'::jsonb,
       updated_at = NOW()
 WHERE factory_id IS NULL
   AND deleted_at IS NULL
   AND jsonb_typeof(required_roles) = 'array'
   AND NOT required_roles ? 'warehouse_worker'
   AND intent_code IN (
       'MATERIAL_BATCH_QUERY',      -- App: 原料批次列表
       'MATERIAL_LOW_STOCK_ALERT',  -- App: GET /material-batches/low-stock (warehouse/inventory read)
       'MATERIAL_EXPIRING_ALERT',   -- App: GET /material-batches/expiring
       'MATERIAL_EXPIRED_QUERY',    -- App: GET /material-batches/expired
       'MATERIAL_FIFO_RECOMMEND',   -- App: GET /material-batches/fifo/{id}
       'SHIPMENT_QUERY',            -- App: GET /shipments (读不设权限)
       'SHIPMENT_STATS',            -- App: GET /shipments/stats
       'MATERIAL_BATCH_USE'         -- App: POST /material-batches/{id}/use (warehouse:read_write)
   );
