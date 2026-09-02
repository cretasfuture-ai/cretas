-- 缺陷 16 phase 2 (2026-09-02): 采购规格层的 min_order_quantity / lead_time_days 收尾删列。
-- Phase 1 (#480, 已上线) 把这两列从实体/DTO/请求体/服务层/前端全部摘除, 仅保留 DB 列一个蓝绿窗口
-- (新旧实例并存期间, 同一 deploy DROP COLUMN 会让旧实例读列失败)。
-- owner 已确认: 两个 prod 实例现在都跑 post-#480 代码, 且实测存量 0 行非空 —— 现在删列是安全的。
-- ⛔ 只删这两列; supplier_material_purchase_specs 的 active / currency / is_default 不动;
--    supplier_materials 表(供应关系层)上的同名列是另一份数据, 不在本次范围内。
ALTER TABLE supplier_material_purchase_specs
    DROP COLUMN IF EXISTS min_order_quantity,
    DROP COLUMN IF EXISTS lead_time_days;
