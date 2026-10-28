-- S6-11: 单次运行的 Token 用量, 取自 LLM 调用响应里的 usage。
-- token_usage_state:
--   KNOWN   本次运行内的每一次 LLM 调用都带了 usage, 三个 token 列是合计
--   UNKNOWN 至少一次 LLM 调用没有 usage (provider 没给 / 流式拿不到), 合计不可信, token 列留空 —— 不写 0
--   NONE    本次运行没有发生 LLM 调用 (token 列留空)

ALTER TABLE scheduler_run_results ADD COLUMN IF NOT EXISTS prompt_tokens INTEGER NULL;
ALTER TABLE scheduler_run_results ADD COLUMN IF NOT EXISTS completion_tokens INTEGER NULL;
ALTER TABLE scheduler_run_results ADD COLUMN IF NOT EXISTS total_tokens INTEGER NULL;
ALTER TABLE scheduler_run_results ADD COLUMN IF NOT EXISTS llm_call_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE scheduler_run_results ADD COLUMN IF NOT EXISTS token_usage_state VARCHAR(10) NOT NULL DEFAULT 'NONE';
ALTER TABLE scheduler_run_results DROP CONSTRAINT IF EXISTS ck_scheduler_run_results_token_state;
ALTER TABLE scheduler_run_results ADD CONSTRAINT ck_scheduler_run_results_token_state
    CHECK (token_usage_state IN ('KNOWN', 'UNKNOWN', 'NONE'));
