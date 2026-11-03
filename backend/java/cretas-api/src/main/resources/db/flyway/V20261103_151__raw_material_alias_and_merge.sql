-- V20261103_151__raw_material_alias_and_merge.sql
--
-- 物料别名 + 重复物料合并 (新工厂复盘: 库房「配方名称和系统名称对不上」「同类品牌多, 要整合」)。
--
-- 1. raw_material_type_aliases: 一个物料可以有多个别名 (配方单 / 供应商单据上的叫法)。
--    同一工厂内别名唯一 (按归一化后的写法), 搜索物料时别名也能搜到主物料。
-- 2. raw_material_types.merged_into_id: 重复物料合并到主物料后记下去向; 被合并的物料停用、
--    不再出现在新单据的下拉里, 它的名字自动变成主物料的别名。
--    Owner 2026-11-03 裁定: 还在配方 / 工艺图 / 供应商供货 / 在途采购里用着, 或手上还有库存,
--    一律不让合并 (列出位置让人先改); 历史单据和成本一行不动。
--
-- 多租户: 别名表带 factory_id, 唯一约束按 (factory_id, normalized_alias)。
-- 幂等: IF NOT EXISTS。

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS merged_into_id VARCHAR(191),
    ADD COLUMN IF NOT EXISTS merged_at TIMESTAMP,
    ADD COLUMN IF NOT EXISTS merged_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS merge_reason VARCHAR(500);

COMMENT ON COLUMN raw_material_types.merged_into_id IS
    '重复物料合并去向 (主物料 id). 非空 = 已合并, 停用且不可再启用; 历史单据仍指向本行.';

CREATE TABLE IF NOT EXISTS raw_material_type_aliases (
    id VARCHAR(50) PRIMARY KEY,
    factory_id VARCHAR(50) NOT NULL,
    material_type_id VARCHAR(191) NOT NULL REFERENCES raw_material_types(id),
    alias VARCHAR(100) NOT NULL,
    normalized_alias VARCHAR(100) NOT NULL,
    source VARCHAR(16) NOT NULL DEFAULT 'MANUAL',
    created_by VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rmt_alias_factory_normalized UNIQUE (factory_id, normalized_alias),
    CONSTRAINT chk_rmt_alias_source CHECK (source IN ('MANUAL', 'MERGE'))
);

CREATE INDEX IF NOT EXISTS idx_rmt_alias_material
    ON raw_material_type_aliases(factory_id, material_type_id);

COMMENT ON TABLE raw_material_type_aliases IS
    '物料别名: 配方单/供应商单据上的叫法. source=MERGE 表示由合并重复物料自动生成 (原物料名).';
