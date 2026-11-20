-- V20261111_91: 两条写死的规则改为工厂开关 (Steve 2026-11-11 拍板「做成开关」, 交接单建议项只当默认值)
--
-- 配置放进现有的 factory_thresholds (阈值中心: 工厂行 > 全局 '*' 行 > 代码兜底), ⛔ 不新建注册表。
-- 本迁移只插【全局默认行】, 不给任何具体工厂插行 (没有工厂行 = 跟随全局默认)。默认值 = 原行为:
--
--   production.capacity.package_unit_mode  NONE    「袋」「盒」计划换线: 产线产能只认 件/kg/批, 包装单位计划只能同线顺延
--                                                   NET_CONTENT  = 按产品净含量折成 kg 核对 kg 产能
--                                                   FACTORY_UNIT = 产线产能可按本厂自己的包装单位配置 (袋/小时)
--   shortage.case.close_mode               MANUAL  缺口补到 0 后停在「缺口已解决」, 生产主管手工关闭
--                                                   AUTO = 系统自动关闭并留痕
--
-- 认不出的值一律按默认 (= 原行为) 处理; 保存时按 ThresholdKeys.validateSemantics 校验取值。

-- 1. 全局默认行 ---------------------------------------------------------------
INSERT INTO factory_thresholds
  (factory_id, threshold_key, category, value_type, threshold_value, default_value,
   description, unit, min_value, max_value)
VALUES
  ('*', 'production.capacity.package_unit_mode', 'PRODUCTION', 'STRING', 'NONE', 'NONE',
   '按「袋」「盒」等包装单位下单的计划, 缺料重排换线时怎么核对目标线产能: NONE = 产线产能只认 件/kg/批, 包装单位计划判「产能未验证」只能同线顺延; NET_CONTENT = 按产品净含量 (须填, 且是 kg/g) 折成 kg 后核对 kg 产能; FACTORY_UNIT = 产线产能可以按本厂自己的包装单位配置 (如 袋/小时), 只与同一单位的计划核对。',
   NULL, NULL, NULL),
  ('*', 'shortage.case.close_mode', 'SHORTAGE', 'STRING', 'MANUAL', 'MANUAL',
   '缺料实例缺口补到 0 之后: MANUAL = 停在「缺口已解决」, 由生产主管手工关闭; AUTO = 系统直接关闭并在实例留痕里记「自动关闭」。',
   NULL, NULL, NULL)
ON CONFLICT (factory_id, threshold_key) WHERE deleted_at IS NULL DO NOTHING;

-- 2. 产线产能可以带工厂自己的包装单位 (仅 FACTORY_UNIT 模式下可写) -------------------
ALTER TABLE production_line_capacity_configs ADD COLUMN IF NOT EXISTS package_unit VARCHAR(20) NULL;

-- 原列内联 CHECK 只允许 PIECE/KG/BATCH; 找到它 (不依赖自动生成的名字) 换成带 PACKAGE 的版本
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT con.conname
      FROM pg_constraint con
      JOIN pg_class rel ON rel.oid = con.conrelid
     WHERE rel.relname = 'production_line_capacity_configs'
       AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) ILIKE '%capacity_unit%'
  LOOP
    EXECUTE format('ALTER TABLE production_line_capacity_configs DROP CONSTRAINT %I', r.conname);
  END LOOP;
END $$;

ALTER TABLE production_line_capacity_configs
  ADD CONSTRAINT chk_line_capacity_unit_and_package CHECK (
    (capacity_unit IN ('PIECE', 'KG', 'BATCH') AND package_unit IS NULL)
    OR (capacity_unit = 'PACKAGE' AND package_unit IS NOT NULL AND btrim(package_unit) <> '')
  );
