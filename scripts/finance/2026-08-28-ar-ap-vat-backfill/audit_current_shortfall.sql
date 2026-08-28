-- ============================================================================
-- AR/AP 价税口径存量补差 —— 只读审计查询
-- ============================================================================
-- 背景: SalesServiceImpl.shipDelivery / PurchaseServiceImpl.confirmReceive 挂账时
--       传的是未税额 (order.getTotalAmount() / record.getTotalAmount()), 没加税额,
--       导致每张单的应收/应付都少记一个税额。修复在 PR #292 (尚未合入/部署)。
--
-- ⛔ 本文件只包含 SELECT —— 禁止在此文件里加任何 UPDATE/INSERT/DELETE。
-- 用法: bash pq.sh audit_current_shortfall.sql   (scp 到服务器用 psql 跑, 只读)
--
-- ⚠️ 这份清单会随时间变化: 只要 PR #292 没有合入部署, 每一笔新发货/新入库都会
--    继续按未税额挂账、继续产生新的缺口。执行补差前必须重新跑一遍本文件,
--    不要用旧的一次性输出。
-- ============================================================================

\pset format aligned
\pset border 2
\pset pager off

\echo '=== [1] AR_INVOICE 全量口径核对 (未税 vs order.getPayableAmount()=未税-折扣+税) ==='
SELECT
  t.factory_id, t.transaction_number, t.counterparty_id, t.counterparty_name,
  so.order_number,
  t.amount                                                                        AS ar_amount_current,
  so.total_amount                                                                 AS so_total_untaxed,
  COALESCE(so.discount_amount,0)                                                  AS so_discount,
  COALESCE(so.tax_amount,0)                                                       AS so_tax,
  (so.total_amount - COALESCE(so.discount_amount,0) + COALESCE(so.tax_amount,0))  AS expected_payable_amount,
  ROUND((so.total_amount - COALESCE(so.discount_amount,0) + COALESCE(so.tax_amount,0)) - t.amount, 2) AS shortfall,
  t.approval_status, t.transaction_date, t.created_at
FROM ar_ap_transactions t
JOIN sales_orders so ON so.id = t.sales_order_id
WHERE t.transaction_type = 'AR_INVOICE' AND t.deleted_at IS NULL
ORDER BY shortfall DESC, t.transaction_number;

\echo '=== [2] AR_INVOICE 里 sales_order_id 打不到 sales_orders 的孤儿行 (应为 0 行) ==='
SELECT t.factory_id, t.transaction_number, t.amount, t.sales_order_id, t.remark, t.created_at
FROM ar_ap_transactions t
LEFT JOIN sales_orders so ON so.id = t.sales_order_id
WHERE t.transaction_type = 'AR_INVOICE' AND t.deleted_at IS NULL AND so.id IS NULL;

\echo '=== [3] AR_INVOICE 对应订单是否有已完成的 SALES_RETURN (有则不能直接套用整单口径补差, 见方案文档 §4) ==='
SELECT ro.return_number, ro.status, ro.total_amount AS return_amount_untaxed,
       so.order_number, so.total_amount AS so_total_untaxed, so.tax_amount AS so_tax,
       (so.total_amount - COALESCE(so.discount_amount,0) + COALESCE(so.tax_amount,0)) AS so_payable_amount,
       t.transaction_number AS ar_txn, t.amount AS ar_amount_current
FROM return_orders ro
JOIN sales_orders so ON so.id = ro.source_order_id
LEFT JOIN ar_ap_transactions t ON t.sales_order_id = so.id AND t.transaction_type = 'AR_INVOICE' AND t.deleted_at IS NULL
WHERE ro.deleted_at IS NULL AND ro.return_type = 'SALES_RETURN'
  AND ro.source_order_id IN (SELECT sales_order_id FROM ar_ap_transactions WHERE transaction_type='AR_INVOICE' AND deleted_at IS NULL);

\echo '=== [4] AP_INVOICE (source_type=PURCHASE_RECEIVE) 按入库单逐行重算价税合计, 与 PurchaseServiceImpl.taxInclusiveReceivedValue 同口径 ==='
WITH receive_line AS (
  SELECT pri.receive_record_id,
         CASE WHEN pri.unit_price IS NULL THEN 0
              ELSE ROUND(pri.received_quantity * pri.unit_price, 2) END AS net_line,
         CASE WHEN pri.unit_price IS NULL THEN 0
              WHEN poi.tax_rate IS NULL OR poi.tax_rate = 0 THEN ROUND(pri.received_quantity * pri.unit_price, 2)
              ELSE ROUND(ROUND(pri.received_quantity * pri.unit_price, 2) * (1 + poi.tax_rate/100.0), 2)
         END AS tax_inclusive_line
  FROM purchase_receive_items pri
  LEFT JOIN purchase_order_items poi ON poi.id = pri.purchase_order_item_id
),
receive_totals AS (
  SELECT receive_record_id, SUM(net_line) AS net_total, SUM(tax_inclusive_line) AS tax_inclusive_total
  FROM receive_line GROUP BY receive_record_id
)
SELECT
  t.factory_id, t.transaction_number, t.counterparty_id, t.counterparty_name,
  prr.receive_number, prr.purchase_order_id,
  t.amount AS ap_amount_current,
  rt.net_total AS receive_net_total,
  rt.tax_inclusive_total AS receive_tax_inclusive_total,
  ROUND(rt.tax_inclusive_total - t.amount, 2) AS shortfall,
  t.approval_status, t.transaction_date, t.created_at
FROM ar_ap_transactions t
JOIN purchase_receive_records prr ON prr.id = t.source_id AND t.source_type = 'PURCHASE_RECEIVE'
LEFT JOIN receive_totals rt ON rt.receive_record_id = prr.id
WHERE t.transaction_type = 'AP_INVOICE' AND t.deleted_at IS NULL
ORDER BY shortfall DESC NULLS LAST, t.transaction_number;

\echo '=== [5] AP_INVOICE 里 source_type=PURCHASE_RECEIVE 但 source_id 打不到 purchase_receive_records 的孤儿行 (数据完整性问题, 不在本次补差范围内, 需单独立案) ==='
SELECT t.factory_id, t.transaction_number, t.counterparty_id, t.counterparty_name,
       t.amount, t.purchase_order_id, t.source_id, t.remark, t.created_at
FROM ar_ap_transactions t
LEFT JOIN purchase_receive_records prr ON prr.id = t.source_id
WHERE t.transaction_type = 'AP_INVOICE' AND t.deleted_at IS NULL
  AND t.source_type = 'PURCHASE_RECEIVE' AND prr.id IS NULL;

\echo '=== [6] AP_INVOICE 对应采购单是否有已完成的 PURCHASE_RETURN (影响同 §3, 目前实测 0 条重叠) ==='
SELECT ro.return_number, ro.status, ro.total_amount AS return_amount,
       po.order_number, po.total_amount AS po_total_untaxed, po.tax_amount AS po_tax,
       t.transaction_number AS ap_txn, t.amount AS ap_amount_current
FROM return_orders ro
JOIN purchase_orders po ON po.id = ro.source_order_id
LEFT JOIN ar_ap_transactions t ON t.purchase_order_id = po.id AND t.transaction_type = 'AP_INVOICE' AND t.deleted_at IS NULL
WHERE ro.deleted_at IS NULL AND ro.return_type = 'PURCHASE_RETURN'
  AND ro.source_order_id IN (SELECT purchase_order_id FROM ar_ap_transactions WHERE transaction_type='AP_INVOICE' AND deleted_at IS NULL);

\echo '=== [7] 是否已有人提交过 AR_ADJUSTMENT/AP_ADJUSTMENT (防重复补差 —— 执行前务必看一眼) ==='
SELECT transaction_number, transaction_type, counterparty_name, amount, approval_status,
       source_type, source_id, remark, created_at
FROM ar_ap_transactions
WHERE transaction_type IN ('AR_ADJUSTMENT','AP_ADJUSTMENT') AND deleted_at IS NULL
ORDER BY created_at DESC;

\echo '=== [8] 最终补差目标清单 (剔除: 有 SALES_RETURN 重叠的 AR 单 / 孤儿 AP 单 / shortfall=0 的行) 供逐条生成 /adjustment 调用 ==='
SELECT
  t.factory_id, 'CUSTOMER' AS counterparty_type, t.counterparty_id, t.counterparty_name,
  t.transaction_number AS original_txn, so.order_number AS order_number,
  t.amount AS current_amount,
  (so.total_amount - COALESCE(so.discount_amount,0) + COALESCE(so.tax_amount,0)) AS correct_amount,
  ROUND((so.total_amount - COALESCE(so.discount_amount,0) + COALESCE(so.tax_amount,0)) - t.amount, 2) AS backfill_amount
FROM ar_ap_transactions t
JOIN sales_orders so ON so.id = t.sales_order_id
WHERE t.transaction_type = 'AR_INVOICE' AND t.deleted_at IS NULL
  AND so.id NOT IN (SELECT source_order_id FROM return_orders WHERE deleted_at IS NULL AND return_type='SALES_RETURN')
  AND ROUND((so.total_amount - COALESCE(so.discount_amount,0) + COALESCE(so.tax_amount,0)) - t.amount, 2) <> 0
UNION ALL
SELECT
  t.factory_id, 'SUPPLIER' AS counterparty_type, t.counterparty_id, t.counterparty_name,
  t.transaction_number AS original_txn, prr.receive_number AS order_number,
  t.amount AS current_amount,
  rt.tax_inclusive_total AS correct_amount,
  ROUND(rt.tax_inclusive_total - t.amount, 2) AS backfill_amount
FROM ar_ap_transactions t
JOIN purchase_receive_records prr ON prr.id = t.source_id AND t.source_type = 'PURCHASE_RECEIVE'
JOIN (
  SELECT pri.receive_record_id,
         SUM(CASE WHEN pri.unit_price IS NULL THEN 0
                  WHEN poi.tax_rate IS NULL OR poi.tax_rate = 0 THEN ROUND(pri.received_quantity * pri.unit_price, 2)
                  ELSE ROUND(ROUND(pri.received_quantity * pri.unit_price, 2) * (1 + poi.tax_rate/100.0), 2)
             END) AS tax_inclusive_total
  FROM purchase_receive_items pri
  LEFT JOIN purchase_order_items poi ON poi.id = pri.purchase_order_item_id
  GROUP BY pri.receive_record_id
) rt ON rt.receive_record_id = prr.id
WHERE t.transaction_type = 'AP_INVOICE' AND t.deleted_at IS NULL
  AND ROUND(rt.tax_inclusive_total - t.amount, 2) <> 0
ORDER BY 1, 2, 8 DESC;
