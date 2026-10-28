-- V20261103_91_1: 销售退货补销项税额, 应收冲减与发货挂账同一含税口径 (S3-10 ERP-S3-RETURN-AR-TAX-MISMATCH-001)
--
-- 背景: 发货挂账按含税额 (SalesServiceImpl#taxInclusiveShippedValue), 退货单只有未税 total_amount,
--   应收冲减按未税额 ⇒ 同一单 16 盒发货挂 80.00, 全退冲 70.80, 客户残留 9.20 (F006 RT-SAL-20261027-0001)。
--
-- 1. return_orders.tax_amount: 销项税额, 默认 0 (采购退货与无税旧单恒 0)。
-- 2. RETURN 默认凭证模板升级为价税分离三行 (镜像 V20261019_02 对 SALES_RECEIPT 的升级):
--    借 6001 未税 / 借 2221.01 销项税 (零税省略) / 贷 1122 含税。仅升级仍是 seed 两行结构的模板。
-- 3. 不回填历史退货: 旧单的应收冲减、凭证 (含草稿) 都按未税额生成, 彼此一致; 补税额会让
--    已生成的调整/凭证全部判成不一致, 而作废的凭证不能按同一业务单重生成。新退货单起按含税口径。

ALTER TABLE return_orders ADD COLUMN IF NOT EXISTS tax_amount NUMERIC(15,2) NOT NULL DEFAULT 0;
COMMENT ON COLUMN return_orders.tax_amount IS '销项税额(销售退货, 回源销售单行税率); 应收冲减 = total_amount + tax_amount';

UPDATE voucher_templates
SET entries_json = '[
  {"sortOrder":1,"subjectCode":"6001","subjectName":"主营业务收入","direction":"DEBIT","amountExpression":"#entity.totalAmount ?: 0","description":"退货冲减收入(未税)"},
  {"sortOrder":2,"subjectCode":"2221.01","subjectName":"应交税费-应交增值税-销项税额","direction":"DEBIT","amountExpression":"#entity.taxAmount ?: 0","description":"退货冲减销项税额","omitWhenZero":true},
  {"sortOrder":3,"subjectCode":"1122","subjectName":"应收账款","direction":"CREDIT","amountExpression":"(#entity.totalAmount ?: 0) + (#entity.taxAmount ?: 0)","description":"客户应收冲减(含税)"}
]'::jsonb,
    description = '价税分离三行: 借 6001 主营业务收入(未税); 借 2221.01 销项税额(零税省略); 贷 1122 应收账款(含税)。',
    updated_at = NOW()
WHERE voucher_type = 'RETURN'
  AND name = '退货默认模板'
  AND deleted_at IS NULL
  AND jsonb_array_length(entries_json) = 2;
