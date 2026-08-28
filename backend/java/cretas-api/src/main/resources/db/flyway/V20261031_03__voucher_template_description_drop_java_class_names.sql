-- 凭证模板的「说明」里写着 Java 类名, 会计人员在屏上直接看到。
--
-- 🔴 2026-08-28 逐屏视觉走查:「财务管理 / 凭证模板」的「说明」列 **7 行全部**长这样:
--
--   借: 6602.02 管理费用-折旧; 贷: 1602 累计折旧. mirror DepreciationVoucherGenerator (Map input).
--   借: 6602.01 管理费用-损耗; 贷: 1405 库存商品. mirror ExpenseVoucherGenerator.
--   价税分离三行: 借 1405 …; 贷 2202 应付账款(含税). mirror PurchasePaymentVoucherGenerator SP11.
--
-- 前半段(会计分录)对用户有用; 后半段是**给开发者的实现备注** ——
-- `mirror` 是英文动词, `XxxVoucherGenerator` 是 Java 类名, `SP11` 是内部迭代编号。
--
-- ⚠️ 与同日 #261 修的「审批链配置屏写着 AccountingPeriodServiceImpl.requestClose」
--   是**完全同一形态**, 只是这一份的产出端是**数据库种子数据**(V20260605_03),
--   而不是 Java 源码字符串。⇒ 面向用户的文案可以从三个端产出:
--   前端源码 / 后端 Java 字面量 / **数据库种子**。前两个各有闸了, 这是第三个。
--
-- ⛔ **不改历史迁移 V20260605_03** —— 它已经在 prod 跑过, 改它会破坏 Flyway checksum,
--    下次启动直接失败。只用本迁移就地 UPDATE。
--
--    ⚠️ 写这一条时我自己差点违反它: 上一版本迁移的注释写着「V20260605_03 的种子文本同步改掉」,
--    然后我就去改那个文件了。是脚本里的断言拦住的。**写下纪律不等于会遵守它。**
--
-- ⚠️ 待查(未在本迁移解决): **新建工厂**的凭证模板从哪来?
--    V20260605_03 是迁移时对当时 active 的工厂建的一次性种子, 不是建厂流程的一部分。
--    如果建厂代码里另有一份同样的文案, 那是形态 D 的第四份。⛔ 不猜, 登记。
--
-- 判据: 执行后 `SELECT count(*) FROM voucher_templates WHERE description ~ '\smirror\s'` 为 0。

-- ⚠️ 正则要覆盖两种写法(实测种子里都有):
--     '… . mirror ExpenseVoucherGenerator.'
--     '… . mirror Sprint 3 E SalesReceiptVoucherGenerator.'   ← 中间夹着内部迭代编号
--    ⇒ 从 ` mirror ` 起一直吃到行尾。
UPDATE voucher_templates
SET description = btrim(regexp_replace(description, '\s*\.?\s*mirror\s.*$', '')),
    updated_at = now()
WHERE description ~ '\smirror\s';
