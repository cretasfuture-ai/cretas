-- S5-05/06/07 逐行三单匹配: 发票行 → 采购单行 (+ 可选的有效收货行)
--
-- 发票此前只在表头记一个总额, 自动对账只比较「发票总额 == 订单总额」:
-- 同金额但错料、少收、分票超量都会被判成相符。本表把每一张发票拆成行,
-- 每行指向它所开的采购单行 (必填) 和收货行 (可选), 累计开票量由这些行算出,
-- 服务层据此拒绝超开 (409) 并逐行判定物料/金额差异。
--
-- quantity 的单位 = 采购单行的 unit (与采购单行 quantity 同一口径)。
-- match_status: MATCHED / MATERIAL_MISMATCH / PRICE_MISMATCH / PRICE_UNKNOWN

CREATE TABLE IF NOT EXISTS purchase_invoice_lines (
    id                      BIGSERIAL      PRIMARY KEY,
    factory_id              VARCHAR(191)   NOT NULL,
    invoice_id              VARCHAR(191)   NOT NULL REFERENCES purchase_invoices(id),
    purchase_order_id       VARCHAR(191)   NOT NULL,
    purchase_order_item_id  BIGINT         NOT NULL REFERENCES purchase_order_items(id),
    -- 无外键: 全新库重放时 purchase_receive_items 由 Hibernate 建, 此刻可能不存在
    -- (见 V20261024_16 的 to_regclass 守卫); 归属由服务层按 PO + 已确认收货单校验。
    receive_item_id         BIGINT,
    material_type_id        VARCHAR(191),
    quantity                NUMERIC(15,4)  NOT NULL,
    unit                    VARCHAR(20)    NOT NULL,
    unit_price              NUMERIC(15,4),
    amount                  NUMERIC(12,2)  NOT NULL,
    expected_amount         NUMERIC(12,2),
    match_status            VARCHAR(32)    NOT NULL,
    match_note              VARCHAR(500),
    created_at              TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP      NOT NULL DEFAULT NOW(),
    deleted_at              TIMESTAMP,
    CONSTRAINT ck_pil_quantity_positive CHECK (quantity > 0),
    CONSTRAINT ck_pil_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT ck_pil_match_status CHECK (match_status IN
        ('MATCHED', 'MATERIAL_MISMATCH', 'PRICE_MISMATCH', 'PRICE_UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS idx_pil_invoice ON purchase_invoice_lines (invoice_id);
CREATE INDEX IF NOT EXISTS idx_pil_factory_po ON purchase_invoice_lines (factory_id, purchase_order_id);
CREATE INDEX IF NOT EXISTS idx_pil_po_item ON purchase_invoice_lines (purchase_order_item_id);
CREATE INDEX IF NOT EXISTS idx_pil_receive_item ON purchase_invoice_lines (receive_item_id)
    WHERE receive_item_id IS NOT NULL;
