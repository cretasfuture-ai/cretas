-- 应收发票唯一性: 从「每销售订单一条」改成「每子发运单一条」
--
-- ## 为什么必须改 (2026-08-28 prod 实操, F006)
--
-- 仓管在「确认发货」时可以覆盖【实际发货数量】(SalesServiceImpl#warehouseConfirmDelivery,
-- 客户原话「车装不下 / 现场少装」)。覆盖后重算的是**发货单**的 total_amount, 而应收挂账
-- 取的是**订单**金额 —— 两个数从此分家。实测两笔:
--
--   SO-20260828-0012  订 2 盒 → 仓管填 5 → 库存真扣 5, 子发运单 110.62,
--                     **应收只挂 44.25**(订单额), 订单 COMPLETED ⇒ 3 盒没人收钱
--   SO-20260828-0013  订 4 盒 → 仓管填 2 → 库存扣 2, 子发运单 44.25,
--                     **应收挂 88.50**(订单额) ⇒ 客户被多收
--
-- 本迁移只做会计正确性: **应收反映实发**。⛔ 不表态「超发允不允许、允许多少」——
-- 那是产品决定 (采购侧有 over_receive_rate + purchase_exceptions 的先例, 销售侧两样都没有)。
--
-- ## 为什么必须动约束, 不能只改代码
--
-- 要让应收反映实发, 就得**按子发运单分笔挂账**(一次发货挂一次它自己的实发额),
-- 累计 = 实发总额。而「一个 SO 多条 AR_INVOICE」正是旧约束 uk_aat_ar_invoice_per_so 禁止的。
--
-- 这一步在**采购侧已经踩过一次**: 2026-07-02 把应付幂等键改成「每张入库单」时
-- 只改了代码没改约束, 那次修复从未真正生效, 直到 2026-08-03 分批到货的第二批
-- 永远入不了库才被发现 (V20261029_49 补的约束)。本迁移与代码改动**同一个 PR**,
-- 就是为了不重演那次「代码改了约束没改」。
--
-- ## 新口径
--
-- (factory_id, source_type, source_id) 唯一 —— 与代码幂等键 ('SALES_DELIVERY', deliveryId) 一致。
--   · 同一张子发运单重复确认 → 命中唯一键 → 仍然不重复挂账 (幂等保持, 防双击)
--   · 分批发货的不同子发运单  → source_id 不同 → 各挂各的实发额 ✓
--   · source_id IS NULL 的历史行不纳入本约束, 保持现状不受影响
--
-- ⚠️ 只有子发运单 (record_role='SHIPMENT') 会走到挂账: shipDelivery 对 record_role='MASTER'
--    直接抛 409「母发货单不能直接确认发货」。所以「每子发运单一条」= 每次真实发货一条,
--    母单不会重复挂一笔。
--
-- ## 存量影响 (prod 实查 2026-08-28)
--
--   全库 AR_INVOICE 12 条; 其中 source_id 非空 0 条; 同一 SO 多条的现存 0 条
--   ⇒ 新约束不撞任何历史数据, 12 条 legacy 行全部因 source_id IS NULL 而不纳入。
--
-- ## 放弃了什么 (显式登记)
--
-- 旧约束 uk_aat_ar_invoice_per_so 是 V20260426_02 为**手工挂账**入口 (ArApController /receivable)
-- 的并发双写加的 DB 兜底。删掉后手工入口只剩应用层 409 (existsByFactoryIdAndSalesOrderId...)。
-- 自动挂账那条路不受影响 —— 它另有 SO 行级 PESSIMISTIC_WRITE 锁 (lockSalesOrderForDelivery)
-- 串行化, 外加本迁移的新唯一键兜底。这与采购侧 V20261029_49 接受的是同一个取舍。

-- 1) 先建新约束 —— 若历史数据违反会在此失败, 不会留下「旧的删了新的没建上」的中间态
CREATE UNIQUE INDEX IF NOT EXISTS uk_aat_ar_invoice_per_shipment
    ON ar_ap_transactions (factory_id, source_type, source_id)
    WHERE transaction_type = 'AR_INVOICE'
      AND deleted_at IS NULL
      AND source_id IS NOT NULL;

-- 2) 再删旧的「每 SO 一条」约束 —— 不删则第二次发货照样撞唯一键, 整事务回滚, 货发不出去
DROP INDEX IF EXISTS uk_aat_ar_invoice_per_so;
