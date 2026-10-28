-- V20261103_93: 客户自有成品处置的客户确认 (Steve 2026-10-28 裁定)
--
-- 召回/退回的代加工客户货有两种去处, 都要客户确认:
--   退还客户 (disposal_type = RETURN_TO_CUSTOMER): 签收人 / 签收日期 / 签收单照片
--   经客户同意报废 (SCRAP / DESTROY):              同意人 / 同意日期 / 同意凭证
-- 公司自有货不填这三列。disposal_type 无 CHECK 约束, 新值不需改约束。

ALTER TABLE disposal_records ADD COLUMN IF NOT EXISTS customer_confirmed_by VARCHAR(100);
ALTER TABLE disposal_records ADD COLUMN IF NOT EXISTS customer_confirmed_at DATE;
ALTER TABLE disposal_records ADD COLUMN IF NOT EXISTS customer_confirmation_evidence TEXT;

COMMENT ON COLUMN disposal_records.customer_confirmed_by IS '客户确认人: 退还客户=签收人, 报废/销毁=同意人 (仅客户自有货)';
COMMENT ON COLUMN disposal_records.customer_confirmed_at IS '客户确认日期 (仅客户自有货)';
COMMENT ON COLUMN disposal_records.customer_confirmation_evidence IS '客户确认凭证 URL, 逗号分隔 (仅客户自有货)';
