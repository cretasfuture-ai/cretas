-- V20261103_87__qc_release_inspection_hold.sql
--
-- 缺口 11 质检放行 (owner 2026-10-25)
--   1. product_types.inspection_required   产品/半成品「需检」开关, NOT NULL DEFAULT FALSE
--      ⇒ 存量全部 FALSE = 行为与以前完全一样 (零回归)。
--   2. semi_finished_inventory.inspection_status   半成品行的质检状态 (NULL = 不需检 / PENDING / RELEASED / REJECTED)。
--      不复用 status 列: status 每次入账/扣减都按余额重算 AVAILABLE/DEPLETED, 待检放在那里会被下一次入账抹掉。
--   3. qc_release_records   放行记录 (结论 + 放行人 + 放行时间), 只增不改。
--
-- 成品待检用 finished_goods_batches.status = 'PENDING_INSPECTION' (VARCHAR(32), 无 CHECK 约束, 不需要 DDL);
-- 逐道录入的在制半成品批次待检复用 material_batches.status = 'INSPECTING' (质检中, 既有枚举值)。
--
-- 全部 IF NOT EXISTS: 可重放。BaseEntity 审计列 (created_at / updated_at / deleted_at) 按 database-entity-sync 规则齐全。

ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS inspection_required BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN product_types.inspection_required IS
    '质检放行: 需检。TRUE = 生产产出默认待检, 放行后才可被下道领用/分配发货 (缺口 11)';

ALTER TABLE semi_finished_inventory
    ADD COLUMN IF NOT EXISTS inspection_status VARCHAR(20);

-- 待检数量 (审查高 2): 半成品行跨批次累加, 待检必须落到数量上 —— 放行/判不合格只处理这部分,
-- 已放行的存量不被新产出打回待检, 不合格量按数量转出 (QC_REJECT 出库流水), 不洗白。
ALTER TABLE semi_finished_inventory
    ADD COLUMN IF NOT EXISTS inspection_pending_quantity DECIMAL(12,2);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'ck_sfi_inspection_status') THEN
        ALTER TABLE semi_finished_inventory
            ADD CONSTRAINT ck_sfi_inspection_status
            CHECK (inspection_status IS NULL OR inspection_status IN ('PENDING', 'RELEASED', 'REJECTED'));
    END IF;
END $$;

-- 待检清单查询: 只索引待检行 (绝大多数行是 NULL)
CREATE INDEX IF NOT EXISTS idx_sfi_factory_inspection_pending
    ON semi_finished_inventory (factory_id)
    WHERE inspection_status = 'PENDING' AND deleted_at IS NULL;

COMMENT ON COLUMN semi_finished_inventory.inspection_status IS
    '质检放行状态: NULL=无待检 / PENDING=有待检数量 (inspection_pending_quantity 部分不可领) (缺口 11)';
COMMENT ON COLUMN semi_finished_inventory.inspection_pending_quantity IS
    '待检数量: 余额中尚未放行的部分; 可领 = available_quantity - 本列 (缺口 11)';

CREATE TABLE IF NOT EXISTS qc_release_records (
    id               BIGSERIAL PRIMARY KEY,
    factory_id       VARCHAR(64)   NOT NULL,
    target_type      VARCHAR(32)   NOT NULL
        CHECK (target_type IN ('SEMI_FINISHED', 'WIP_MATERIAL_BATCH', 'FINISHED_GOODS')),
    target_id        VARCHAR(191)  NOT NULL,
    batch_number     VARCHAR(100),
    product_type_id  VARCHAR(191),
    quantity         DECIMAL(15,4),
    unit             VARCHAR(20),
    decision         VARCHAR(16)   NOT NULL CHECK (decision IN ('RELEASED', 'REJECTED')),
    conclusion       TEXT          NOT NULL,
    released_by      BIGINT        NOT NULL,
    released_by_name VARCHAR(100),
    released_at      TIMESTAMP     NOT NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at       TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_qc_release_factory_target
    ON qc_release_records (factory_id, target_type, target_id);
CREATE INDEX IF NOT EXISTS idx_qc_release_factory_time
    ON qc_release_records (factory_id, released_at);

COMMENT ON TABLE qc_release_records IS '质检放行记录 (缺口 11): 每次放行/判不合格一行, 只增不改';

-- 待检分笔账 (第二轮审查 H2/H3/M1): 一行半成品累加多笔入账, 撤销/驳回/更正某一笔时只能解除
-- 「这一笔还没放行的待检量」, 不能把别人的待检抵消掉。每笔扣成待检的入账记一行:
--   report_id     App 报工入账 (撤销/驳回/数量更正按它找)
--   production_batch_id 这一笔入账的生产批次 (放行前查「当前未放行的笔」所在计划有没有质检 FAILED, 第三轮审查 H-1)
--   hold_ref      逐道录入入账的凭据 (PS:计划:行; 撤销按它找)
--                 两者都空 = 认不出是哪一笔, 撤销时一律不解除 (保守: 宁可多挡)
--   quantity_open 仍未放行的量; 质检放行/判不合格时整行清零
-- 不改流水表 (semi_finished_inventory_transactions 只增不改)。
CREATE TABLE IF NOT EXISTS semi_qc_hold_lots (
    id                 BIGSERIAL PRIMARY KEY,
    factory_id         VARCHAR(64)   NOT NULL,
    semi_finished_id   BIGINT        NOT NULL,
    report_id          BIGINT,
    production_batch_id BIGINT,
    hold_ref           VARCHAR(160),
    quantity_held      DECIMAL(12,2) NOT NULL,
    quantity_open      DECIMAL(12,2) NOT NULL,
    created_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP     NOT NULL DEFAULT NOW(),
    deleted_at         TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_semi_qc_hold_lots_semi
    ON semi_qc_hold_lots (factory_id, semi_finished_id);

COMMENT ON TABLE semi_qc_hold_lots IS
    '半成品待检分笔账 (缺口 11): 每笔扣成待检的入账一行; 撤销某笔只解除它自己未放行的量';
