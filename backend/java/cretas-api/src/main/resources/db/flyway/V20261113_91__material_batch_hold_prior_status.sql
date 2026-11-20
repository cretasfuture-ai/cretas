-- #1590 遗留 M5: 扣留 (冻结 / CCP 偏离扣留 / 冷链转待检 / 仓库页改成质检中) 前的批次状态, 解除扣留 (放行) 时还原。
-- 为空 = 不是扣留 (收货 / 产出后的首检), 放行照旧成「可用」。只在 status = 'INSPECTING' 时可能非空 (实体 setStatus 维护)。
-- 缺料实例的「质检中供应」只数为空的那部分: 被扣留的货是本来就在库里、被拦下来的, 不是新来的供应 (M3)。
ALTER TABLE material_batches ADD COLUMN IF NOT EXISTS hold_prior_status VARCHAR(32);

ALTER TABLE material_batches DROP CONSTRAINT IF EXISTS chk_material_batches_hold_prior_status;
ALTER TABLE material_batches ADD CONSTRAINT chk_material_batches_hold_prior_status
    CHECK (hold_prior_status IS NULL
        OR (status = 'INSPECTING'
            AND hold_prior_status IN ('AVAILABLE', 'IN_STOCK', 'FRESH', 'FROZEN', 'RESERVED',
                                      'PRODUCING_RESERVED', 'DEPLETED', 'USED_UP')));
