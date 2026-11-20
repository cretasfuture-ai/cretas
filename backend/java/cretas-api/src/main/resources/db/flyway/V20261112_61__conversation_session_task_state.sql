-- INTENT-DISTILL-20261111 第二批「任务状态」: 在现有参数收集会话表上扩, 不新建平行状态表。
--
-- task_state_json: 挂起时刻 + 每个已收集参数的来源 (USER / DEFAULT / INHERITED)。只给服务端用, 不外发。
-- 状态新增取值 SUSPENDED (varchar(20) 放得下, 表上没有 status 的 CHECK 约束, 见 ops/db-baseline)。
--
-- 回滚: 关开关 cretas.ai.task-state.enabled=false 即恢复旧行为 (列留着无害);
--       已挂起的行可一次性收掉: UPDATE conversation_sessions SET status = 'CANCELLED', completed_at = NOW()
--       WHERE status = 'SUSPENDED';
ALTER TABLE IF EXISTS conversation_sessions
    ADD COLUMN IF NOT EXISTS task_state_json TEXT;

-- 乐观锁版本号 (终审 S3): 同一会话的并发请求整行保存时, 后到的旧副本不能覆盖先到的结果。已有行补 0。
ALTER TABLE IF EXISTS conversation_sessions
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
