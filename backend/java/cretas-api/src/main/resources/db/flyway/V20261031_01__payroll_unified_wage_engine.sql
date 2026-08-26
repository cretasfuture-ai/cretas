-- 两套工资引擎收敛成一个算法 (逐工序计件 + WageMode 三态).
--
-- 背景: generatePayroll 过去用「全期总件数 × 出现最多那道工序的单价」只套一档,
-- calculateMonthly 用「逐工序分别套各自规则再求和」。同一个工人同一个月两个数。
-- 现在两边都走 WageCalculationService.computeWageBreakdown()。
--
-- 新增列分两组:
--   (1) 算法列 —— PayrollRecord 过去只有 piece_rate_wage / overtime_wage,
--       HOURLY / MIXED 模式的工时工资没有落脚点, 收敛后必须有 hourly_wage,
--       否则按时工人的钱在 total_wage 里凭空消失。
--   (2) 重算审计列 —— PENDING 草稿允许重算, 但金额变化必须 HR 看得见:
--       previous_total_wage 留「变更前」, total_wage 是「变更后」,
--       recalc_log 逐次追加一行人可读记录。
--       APPROVED / PAID 冻结不重算 (服务层拒绝), 这些列对它们恒为 NULL/0。
--
-- 存量行: 全部新列可空 (recalc_count 有 DEFAULT 0), 不回填、不倒推历史金额。

ALTER TABLE payroll_records
    ADD COLUMN IF NOT EXISTS wage_mode           VARCHAR(20),
    ADD COLUMN IF NOT EXISTS hourly_wage         NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS hourly_rule_id      BIGINT,
    ADD COLUMN IF NOT EXISTS previous_total_wage NUMERIC(12, 2),
    ADD COLUMN IF NOT EXISTS recalculated_at     TIMESTAMP,
    ADD COLUMN IF NOT EXISTS recalculated_by     BIGINT,
    ADD COLUMN IF NOT EXISTS recalc_count        INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS recalc_log          TEXT;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'chk_payroll_wage_mode'
    ) THEN
        ALTER TABLE payroll_records
            ADD CONSTRAINT chk_payroll_wage_mode
            CHECK (wage_mode IS NULL OR wage_mode IN ('PIECE_RATE', 'HOURLY', 'MIXED'));
    END IF;
END $$;

COMMENT ON COLUMN payroll_records.wage_mode IS
    '本次计算采用的 WageMode (PIECE_RATE / HOURLY / MIXED); NULL = 收敛前生成的历史行';
COMMENT ON COLUMN payroll_records.hourly_wage IS
    '工时工资 (常规工时 × 时薪); PIECE_RATE 模式恒为 0';
COMMENT ON COLUMN payroll_records.previous_total_wage IS
    '最近一次重算之前的 total_wage —— 与 total_wage 并排看即「变更前 / 变更后」';
COMMENT ON COLUMN payroll_records.recalc_log IS
    '重算审计: 每次重算追加一行 [重算] 时间 by 操作人: 总额 旧 → 新 (差额)';
