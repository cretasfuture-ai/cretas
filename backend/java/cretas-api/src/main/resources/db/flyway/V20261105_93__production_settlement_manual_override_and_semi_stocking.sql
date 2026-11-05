-- 结单审计与半成品入库明细 (PR #1446 审查 M3 / H1)
--   manual_override / manual_override_reason: 这张结单是不是人工补录的、为什么; 操作人是已有的 settled_by。
--   semi_stocking_detail: 结单转入半成品库的切片 (JSON)。撤回只撤一道, 结单却整单还原, 要靠它把半成品库
--   和在制批次两边都还回去, 这些信息在撤回时已经没有别处可查。
ALTER TABLE production_settlements
    ADD COLUMN IF NOT EXISTS manual_override BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS manual_override_reason VARCHAR(255),
    ADD COLUMN IF NOT EXISTS semi_stocking_detail TEXT;
