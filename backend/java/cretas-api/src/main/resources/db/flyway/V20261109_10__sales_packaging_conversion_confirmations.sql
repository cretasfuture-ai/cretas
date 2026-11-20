-- Q10 (Owner 2026-11-09): 成品批规格与发货行规格不一致、操作人确认换算后分配 —— 留痕。
-- 记: 谁 (登录身份 confirmed_by)、什么时候 (confirmed_at)、哪张发货行 (delivery_item_id / delivery_record_id)、
--     哪个成品批 (finished_goods_batch_id / batch_number)、从什么规格 (from_*, 成品批的规格) 确认成什么规格 (to_*, 发货行规格)。
-- 只增不删: 应用层只有插入 (实体 @Immutable + @PreRemove 拒绝, 仓储不暴露删除);
--           数据库层拒绝 UPDATE / TRUNCATE。DELETE 不在库里拦: 整厂数据删除 (FactoryErasureService, 用户依法要求的删除)
--           按 factory_id 逐表 DELETE, 拦了它整厂删除就做不完。
-- 幂等: 同一份分配重放 (SalesDeliveryBatchAllocationServiceImpl.sameAllocationPayload) 在写之前就返回, 不重复写。

CREATE TABLE IF NOT EXISTS sales_packaging_conversion_confirmations (
    id                       BIGSERIAL     PRIMARY KEY,
    factory_id               VARCHAR(64)   NOT NULL,
    delivery_record_id       VARCHAR(191),
    delivery_item_id         VARCHAR(64)   NOT NULL,
    finished_goods_batch_id  VARCHAR(191)  NOT NULL,
    batch_number             VARCHAR(100),
    allocated_qty            NUMERIC(18,4) NOT NULL,
    unit                     VARCHAR(20),
    from_packaging_unit      VARCHAR(20)   NOT NULL,
    from_packaging_base_unit VARCHAR(20)   NOT NULL,
    from_packaging_factor    NUMERIC(20,8) NOT NULL,
    to_packaging_unit        VARCHAR(20)   NOT NULL,
    to_packaging_base_unit   VARCHAR(20)   NOT NULL,
    to_packaging_factor      NUMERIC(20,8) NOT NULL,
    confirmed_by             BIGINT        NOT NULL,
    confirmed_at             TIMESTAMP     NOT NULL,
    created_at               TIMESTAMP     NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_spcc_qty_positive CHECK (allocated_qty > 0),
    CONSTRAINT ck_spcc_factor_positive CHECK (from_packaging_factor > 0 AND to_packaging_factor > 0)
);

CREATE INDEX IF NOT EXISTS idx_spcc_delivery_item
    ON sales_packaging_conversion_confirmations (factory_id, delivery_item_id);
CREATE INDEX IF NOT EXISTS idx_spcc_batch
    ON sales_packaging_conversion_confirmations (factory_id, finished_goods_batch_id);

CREATE OR REPLACE FUNCTION sales_packaging_conversion_confirmations_append_only()
RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'sales_packaging_conversion_confirmations is append-only (换算确认留痕只增不改)';
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_spcc_no_update ON sales_packaging_conversion_confirmations;
CREATE TRIGGER trg_spcc_no_update
    BEFORE UPDATE ON sales_packaging_conversion_confirmations
    FOR EACH ROW EXECUTE FUNCTION sales_packaging_conversion_confirmations_append_only();

DROP TRIGGER IF EXISTS trg_spcc_no_truncate ON sales_packaging_conversion_confirmations;
CREATE TRIGGER trg_spcc_no_truncate
    BEFORE TRUNCATE ON sales_packaging_conversion_confirmations
    FOR EACH STATEMENT EXECUTE FUNCTION sales_packaging_conversion_confirmations_append_only();
