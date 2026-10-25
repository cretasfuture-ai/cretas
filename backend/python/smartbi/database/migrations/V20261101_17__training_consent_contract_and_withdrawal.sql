-- 训练授权: 合同编号必填 + 撤回标记 (训练条款第九、十条, Steve 2026-10-24)
--
-- 1) contract_no: 授权 (private / shared_anon) 必须注明合同编号 (第九条)。
--    数据库约束兜底, 不只靠 CLI。F006 / MOCK_REST 是团队自己的测试租户, 授权时没有
--    合同, 补记为 INTERNAL-TEST-TENANT (Steve 2026-10-23 选择保留其样本)。
-- 2) withdrawn_at / withdrawn_by / withdrawal_note: 客户撤回 (第十条)。设上之后:
--    采集不再写入该厂样本, 训练导出 / 外部打分 / 评测冻结 / 抽样全部跳过该厂
--    (backend/python/smartbi/services/training_consent.py 读到的范围是 'withdrawn')。
--    人工流程见 docs/security/training-withdrawal-runbook.md。

ALTER TABLE training_consent ADD COLUMN IF NOT EXISTS contract_no      VARCHAR(64);
ALTER TABLE training_consent ADD COLUMN IF NOT EXISTS withdrawn_at     TIMESTAMP;
ALTER TABLE training_consent ADD COLUMN IF NOT EXISTS withdrawn_by     VARCHAR(64);
ALTER TABLE training_consent ADD COLUMN IF NOT EXISTS withdrawal_note  TEXT;

UPDATE training_consent
   SET contract_no = 'INTERNAL-TEST-TENANT'
 WHERE factory_id IN ('F006', 'MOCK_REST')
   AND consent_scope <> 'none'
   AND (contract_no IS NULL OR btrim(contract_no) = '');

ALTER TABLE training_consent DROP CONSTRAINT IF EXISTS chk_training_consent_contract;
ALTER TABLE training_consent ADD CONSTRAINT chk_training_consent_contract
    CHECK (consent_scope = 'none' OR (contract_no IS NOT NULL AND btrim(contract_no) <> ''));

COMMENT ON COLUMN training_consent.contract_no IS
  '授权所依据的合同编号; consent_scope 为 private / shared_anon 时必填 (训练条款第九条)';
COMMENT ON COLUMN training_consent.withdrawn_at IS
  '客户撤回时间; 非空 = 该厂退出训练: 不采集、不导出、不打分 (训练条款第十条)';
