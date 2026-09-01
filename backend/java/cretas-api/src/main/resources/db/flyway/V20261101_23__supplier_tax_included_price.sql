-- 19②: 含税价成为供应侧价格录入的存储权威值 (owner 2026-09-01 裁定)
--
-- 形状照 raw_material_types 的两列制: 人填【含税】, 系统按物料税率派生【未税】。
-- ⛔ 刻意把新列加在【含税】这一侧, 既有的 default_purchase_price / quoted_price
--    语义**保持不变**(仍然是未税) —— 于是四个下游消费点
--      PurchaseServiceImpl:3999 / MaterialPackagingHierarchyServiceImpl:94
--      SupplierMaterialPurchaseSpecServiceImpl:58 / resolveEffectivePrice:268
--    一行都不用改。
--
-- 🔴 反面做法(没有采用): 把 default_purchase_price 直接改成"含税"。那会一次性
--    重新解释全部存量值, 并要求四个消费点同步改 —— 漏掉任何一个, 它就会用旧口径
--    去读一个已经换了含义的列, 得到一个**没有报错、和正确值长得一样的错数**。
--
-- 📌 存量基准未知的行不需要额外的 flag 列:
--    (default_purchase_price IS NOT NULL AND tax_included_default_purchase_price IS NULL)
--    这个组合本身就是「有价格但不知道是含税还是未税」。
--    多一个 flag 列就多一个会和它描述的两列漂开的东西。
--    LIUSHANMEN 实测(2026-09-01)命中 2 行。

ALTER TABLE supplier_materials
    ADD COLUMN IF NOT EXISTS tax_included_default_purchase_price NUMERIC(18,6);

COMMENT ON COLUMN supplier_materials.tax_included_default_purchase_price IS
    '含税默认采购价(人工录入的权威值)。未税值 default_purchase_price 由服务层按物料税率派生。'
    '两者同时为空=未配置; 未税有值而本列为空=存量基准未知, 不参与自动带价。';

ALTER TABLE supplier_material_purchase_specs
    ADD COLUMN IF NOT EXISTS tax_included_quoted_price NUMERIC(18,6);

COMMENT ON COLUMN supplier_material_purchase_specs.tax_included_quoted_price IS
    '含税报价(人工录入的权威值)。未税值 quoted_price 由服务层按物料税率派生。'
    '两者同时为空=未配置; 未税有值而本列为空=存量基准未知, 不参与自动带价。';

-- ⛔ 刻意【不】回填: 不知道那 2 行的存量值是含税还是未税, 猜一个方向就是把
--    「我们不知道」写成一个自信的数。它们保持 tax_included_* = NULL,
--    由上面那个组合判定为「基准未知」, 并被排除出自动带价链
--    (编排 2026-09-01 裁定 —— 这是编排的判断不是 owner 的, 可一行翻转)。
