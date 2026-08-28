-- V20261101_05__product_types_rework_purity.sql
-- 回料纯度: 「哪些回料能投进哪个产品」在 product_types 上表达成一个全序 + 一个开关。
--
-- 客户实拍确认的规则 (肉饼线):
--   投入到 ↓ ／ 回料来自 →   美式   小吃吧   熟肉饼   汉堡排
--   美式汉堡饼                YES    no       no       no
--   小吃吧汉堡饼              YES    YES      no       no
--   熟肉饼                    YES    YES      YES      no
--   汉堡排                    no     no       no       no
-- 成因: 美式是纯肉无辅料, 所以最"干净", 能进任何产品; 小吃吧掺了辅料, 只能进比它更"脏"的;
-- 汉堡排一概不收回料。判据是「回料的辅料集合 ⊆ 目标产品的辅料集合」, 而这个包含关系在这条
-- 产线上是一个全序, 所以落成一个整数就够 (数越小越干净: 美式=1 / 小吃吧=2 / 熟肉饼=3)。
--
-- 在此之前四层都不守 (2026-08 逐层实测): 画布连线 (方案丙已移除接线) / 同族过滤 (粒度只有
-- 牛肉 vs 猪蹄, 且 planFamily 为 null 时整段跳过) / 阶段过滤 (它管的是回锅, 对回料本来就放行) /
-- RN 下拉 (列全部) ⇒ 操作员能把熟肉饼的回料领给美式汉堡饼, 系统不拦。
--
-- ⛔ 存量行一律不回填 (rework_purity_level 保持 NULL)。
--    「未配置的产品不参与这道闸」是显式裁定 (owner 拍板): 一律放行 = 闸不存在,
--    一律拒绝 = 上线当天所有租户的回料全部领不出来。选放行, 并在返回项上带
--    reworkPurityStatus=UNCONFIGURED, 让前端能说清「这一行没经过纯度判定」——
--    诚实标记而不是假装判过, 与本仓「禁止降级处理」同向。
--    ⇒ 这道闸只在租户【显式配了等级】之后才对那两个产品生效。
--
-- accepts_rework 与 level 独立, 且必须在 level 未配时也生效
--   (汉堡排 = 一概不收, 与来源是谁、来源配没配等级无关)。默认 TRUE = 维持现状。
--
-- ⚠️ 如果以后出现【不是全序】的产线 (两个产品各自有对方没有的辅料), 一个整数就表达不了,
--    那时要换成真正的集合比较。这里刻意留整数而不提前做集合, 是因为集合需要「辅料集合」
--    这份数据, 而它今天不存在 —— 提前做会做出一个只能靠假数据验收的东西。

ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS rework_purity_level integer;

ALTER TABLE product_types
    ADD COLUMN IF NOT EXISTS accepts_rework boolean NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN product_types.rework_purity_level IS
  '回料纯度等级(全序): 数越小辅料越少越"干净"。回料可投入 ⟺ 来源.level <= 目标.level。NULL=未配置, 不参与判定(放行并标记 UNCONFIGURED)';
COMMENT ON COLUMN product_types.accepts_rework IS
  '这个产品收不收回料。FALSE 时任何来源的回料都不可投入(与 level 是否配置无关)。默认 TRUE=维持现状';
