-- V20261031_04: 补回 system_enums 的物料字典种子 —— 生产上整表 0 行
--
-- ## 症状 (2026-08-28 客户报障, 已在 prod 复现)
--   「仓储管理 → 原料类型字典 → 新建原料类型」的**必填**字段「储存类型」
--   下拉显示「无数据」⇒ **任何工厂都建不出原料类型**。
--
-- ## 实测
--   select count(*) from system_enums;            -> 0        (整表, 不分租户)
--   flyway 里 V20260506_01 'seed material dictionary enums' 于 2026-05-08 03:33:18 success=t
--   ⇒ 种子当年写进去过, 行后来消失了。
--   ⛔ 我**没有查清**是谁删的: 全量 grep 过 db/flyway 没有任何
--     `DELETE FROM system_enums`; tenant_purge_68 / f006_clear_71 / legacy_retired
--     三个备份 schema 里都没有 system_enums 的副本。成因存疑, 但**缺行是事实**。
--
-- ## 为什么必须由迁移补 (不能只改前端)
--   1. 这张表**没有任何界面可以维护**: `platform/enum-dictionary-editor` 既没挂路由,
--      而且它编辑的是 canvas 用的 `enum_dictionary`, 不是 `system_enums`。
--      ⇒ 客户和我们都没法在界面上补, 只能靠迁移。
--   2. RN App 走同一个接口 (`dictionaryApiClient.getEnums('MATERIAL_STORAGE_TYPE')`,
--      见 rawMaterialType.schema.ts) —— 前端 web-admin 的兜底救不了 App,
--      **补数据才是同时修好两端的那一刀**。
--
-- ## 幂等
--   与 V20260506_01 同款: 显式 id + ON CONFLICT (factory_id, enum_group, enum_code) DO NOTHING。
--   重跑不会重复插入; 若某天那 4 行又回来了, 本迁移自然空跑。
--   created_at / updated_at 无 DEFAULT (BaseEntity 由 Hibernate 填, Flyway 走 raw SQL
--   不经过它), 必须显式 NOW() —— 这一点 V20260506_01 的注释已经踩过。
--
-- ## 数据缺席安全
--   不做 "影响行数 = 4" 的断言: 全新空库/已有行的库都要能跑过。

INSERT INTO system_enums (id, factory_id, enum_group, enum_code, enum_label, enum_description,
                          sort_order, is_active, is_system, created_at, updated_at)
VALUES
    ('enum-mst-001', '*', 'MATERIAL_STORAGE_TYPE', 'fresh',  '新鲜', '常温新鲜原料', 1, true, true, NOW(), NOW()),
    ('enum-mst-002', '*', 'MATERIAL_STORAGE_TYPE', 'frozen', '冻货', '冷冻储存',     2, true, true, NOW(), NOW()),
    ('enum-mst-003', '*', 'MATERIAL_STORAGE_TYPE', 'dry',    '干货', '干燥储存',     3, true, true, NOW(), NOW()),
    ('enum-mst-004', '*', 'MATERIAL_STORAGE_TYPE', 'normal', '常温', '常温储存',     4, true, true, NOW(), NOW())
ON CONFLICT (factory_id, enum_group, enum_code) DO NOTHING;

-- 类别字典一起补回。web-admin 的「类别」下拉因为并了平台枚举与存量值所以没受影响
-- (materialFamilyOptions), 但 App 侧与 `/enums/MATERIAL_CATEGORY` 的其它消费方仍然读它。
INSERT INTO system_enums (id, factory_id, enum_group, enum_code, enum_label, enum_description,
                          sort_order, is_active, is_system, created_at, updated_at)
VALUES
    ('enum-mc-001', '*', 'MATERIAL_CATEGORY', 'main',      '主材',   '主要食材原料',      1, true, true, NOW(), NOW()),
    ('enum-mc-002', '*', 'MATERIAL_CATEGORY', 'auxiliary', '辅材',   '辅助食材原料',      2, true, true, NOW(), NOW()),
    ('enum-mc-003', '*', 'MATERIAL_CATEGORY', 'seasoning', '调味料', '调味用原料',        3, true, true, NOW(), NOW()),
    ('enum-mc-004', '*', 'MATERIAL_CATEGORY', 'packaging', '包材',   '包装材料(箱/袋等)', 4, true, true, NOW(), NOW())
ON CONFLICT (factory_id, enum_group, enum_code) DO NOTHING;
