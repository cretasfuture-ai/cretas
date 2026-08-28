-- V20261101_04__raw_material_types_main_category.sql
-- 物料分两层: 大类 (main_category, 恰好 3 种) + 小类 (category, 现有 12 个值原样保留)。
--
-- 背景 (2026-08-28 实测 cretas_prod_db, raw_material_types 506 行, category 12 个平级取值):
--   辅料 149 / 原料 135 / 包材 87 / 调味料 68 / 主材 29 / 调料 12
--   添加剂 9 / 蔬菜 7 / 肉类 4 / 水产 2 / 米面 2 / 干货 2
-- 「角色」(原料/辅料/包材) 和「品类」(蔬菜/肉类/水产/米面/干货) 被塞进了同一列, 于是
-- 「这个物料能不能当辅料绑到工序上」只能靠一张会漏的字面值白名单 —— 六膳门 9 个「添加剂」
-- 物料 (保水剂 / 复合磷酸盐 / 小苏打 / 无磷保水剂 …) 因此全部绑不上, 而它们是肉制品核心辅料。
--
-- ⛔ 本迁移【不修改】category 的任何一行 —— 小类不销毁。添加剂仍然是添加剂 (GB 2760 按类别
--    单独管限量), 蔬菜仍然是蔬菜。改的只是「它属于哪个大类」这件之前没记的事。
-- ⇒ 因此也不需要备份列: 没有任何东西被覆盖。
--
-- 🔴 回填必须 100% 覆盖。认不出来的 category 让迁移【失败】, ⛔ 不许 COALESCE(..., '原料') ——
--    那会把「我不认识这个值」翻译成「它是原料」, 而下游会照着这个假读数往下走。
--
-- ⚠️ 这张映射表有两份 (这里 + MaterialMainCategory.java), 抽不成一份 (回填必须在 SQL 里,
--    运行时写入必须在 Java 里)。由 MaterialMainCategoryTest#migrationBackfillAgreesWithJavaMapping
--    钉住两份一致 —— 那道闸解析本文件的 CASE 分支逐条比对, 不是各自断言各自对。

ALTER TABLE raw_material_types
    ADD COLUMN IF NOT EXISTS main_category varchar(20);

-- 回填。分支覆盖 = 生产实测的 12 个值 + 本仓其它地方已在处理的历史自由文本别名
-- (RawMaterialTypeServiceImpl#materialFamily / #NUMERIC_PREFIX_MAP / web-admin materialCategory.ts,
--  以及建厂种子 DEFAULT_MATERIAL_TYPES 直接写入的 海水鱼/淡水鱼/虾类/贝类)。
UPDATE raw_material_types SET main_category = CASE btrim(category)
    -- ── 原料 ──
    WHEN '原料'     THEN '原料'
    WHEN '主材'     THEN '原料'
    WHEN '蔬菜'     THEN '原料'
    WHEN '肉类'     THEN '原料'
    WHEN '水产'     THEN '原料'
    WHEN '米面'     THEN '原料'
    WHEN '干货'     THEN '原料'
    WHEN '禽类'     THEN '原料'
    WHEN '海鲜'     THEN '原料'
    WHEN '水产类'   THEN '原料'
    WHEN '海水鱼'   THEN '原料'
    WHEN '淡水鱼'   THEN '原料'
    WHEN '虾类'     THEN '原料'
    WHEN '贝类'     THEN '原料'
    WHEN '水果'     THEN '原料'
    WHEN '粉类'     THEN '原料'
    WHEN '油类'     THEN '原料'
    WHEN 'RAW'          THEN '原料'
    WHEN 'RAW_MATERIAL' THEN '原料'
    -- ── 辅料 ──
    WHEN '辅料'     THEN '辅料'
    WHEN '调味料'   THEN '辅料'
    WHEN '调料'     THEN '辅料'
    WHEN '添加剂'   THEN '辅料'
    WHEN '辅材'     THEN '辅料'
    WHEN '调味品'   THEN '辅料'
    WHEN 'AUXILIARY' THEN '辅料'
    WHEN 'SEASONING' THEN '辅料'
    -- ── 包材 ──
    WHEN '包材'     THEN '包材'
    WHEN '包辅材'   THEN '包材'
    WHEN 'PACKAGING' THEN '包材'
    WHEN 'PACKAGE'   THEN '包材'
    ELSE NULL
END
WHERE main_category IS NULL;

-- 🔴 覆盖率闸: 有一行没归到大类就整个迁移回滚, 并把那些 category 值报出来。
--    ⛔ 不是警告 —— NOT NULL 那一步随后就会失败, 但那时的报错 ("column contains null values")
--    不会告诉任何人【是哪个 category 没登记】, 于是排查要从头做一遍。
DO $$
DECLARE
    unmapped_count bigint;
    unmapped_values text;
BEGIN
    SELECT count(*), coalesce(string_agg(DISTINCT coalesce(category, '<NULL>'), ', '), '')
      INTO unmapped_count, unmapped_values
      FROM raw_material_types
     WHERE main_category IS NULL;

    IF unmapped_count > 0 THEN
        RAISE EXCEPTION
            'main_category 回填未覆盖 % 行; 未登记的 category: [%]. 请在本迁移与 MaterialMainCategory.java 两处同时登记它属于哪个大类, ⛔ 不要兜底成原料',
            unmapped_count, unmapped_values;
    END IF;
END $$;

ALTER TABLE raw_material_types
    ALTER COLUMN main_category SET NOT NULL;

-- 大类只有三种 —— 让它在数据库层面就长不出第四个。
ALTER TABLE raw_material_types
    DROP CONSTRAINT IF EXISTS chk_raw_material_main_category;
ALTER TABLE raw_material_types
    ADD CONSTRAINT chk_raw_material_main_category
    CHECK (main_category IN ('原料', '辅料', '包材'));

CREATE INDEX IF NOT EXISTS idx_rmt_main_category
    ON raw_material_types (factory_id, main_category);

COMMENT ON COLUMN raw_material_types.main_category IS
  '物料大类, 恰好三种: 原料/辅料/包材 (CHECK 约束钉死)。业务判定查这一列; category 是它下面的小类(蔬菜/添加剂/调味料…), 保留不动';
COMMENT ON COLUMN raw_material_types.category IS
  '物料小类 (蔬菜/肉类/添加剂/调味料…)。⛔ 不要拿它做「是不是辅料」这种判断 —— 那是 main_category 的事';
