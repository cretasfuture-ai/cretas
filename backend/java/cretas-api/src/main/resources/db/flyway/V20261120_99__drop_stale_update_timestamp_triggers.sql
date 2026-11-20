-- [PROTO-STALE-TRIGGERS] 摘掉 10 张「没有 updated_at 列却挂着 update_timestamp() 触发器」的表上的触发器。
--
-- 根因同 V20261120_97 (scheduling_alerts): 旧的 migration-pg/V0002__create_update_triggers.sql 按「每张表都有
-- updated_at」批量挂了 BEFORE UPDATE 触发器 update_timestamp() (函数体只有 NEW.updated_at = CURRENT_TIMESTAMP)。
-- 下面这些表没有 updated_at 列, 于是对它们的任何 UPDATE 都在触发器里报
--   record "new" has no field "updated_at"
-- 其中 worker_assignments 在工人签到 / 签退路径上 (WorkerAssignmentServiceImpl.workerCheckIn / workerCheckOut)。
--
-- 2026-11-19 prod 只读核对 (pg_trigger ⋈ pg_proc, 函数 = update_timestamp, 表上无 updated_at):
-- 共 11 张, 除去已由 V20261120_97 处理的 scheduling_alerts 正好是下面 10 张; 触发器名均为 <表名>_update_timestamp。
--
-- 修法逐表判定: 10 张表对应的 JPA 实体都没有 updatedAt 字段 (也没有继承 BaseEntity, 没有 @Transient 或映射到别列的
-- updatedAt), 补列没有任何代码读写它 ⇒ 一律只摘触发器, 不补列。共用函数 update_timestamp() 不动 (另有 154 张表在用)。
--   ai_intent_config_history  -> AIIntentConfigHistory  (无 updatedAt)
--   ai_usage_log              -> AIUsageLog             (无 updatedAt)
--   form_template_versions    -> FormTemplateVersion    (无 updatedAt)
--   iot_device_data           -> IotDeviceData          (无 updatedAt)
--   isapi_event_logs          -> IsapiEventLog          (无 updatedAt)
--   model_performance_log     -> ModelPerformanceLog    (无 updatedAt)
--   scheduling_predictions    -> SchedulingPrediction   (无 updatedAt)
--   system_logs               -> SystemLog              (无 updatedAt)
--   tool_call_cache           -> ToolCallCache          (无 updatedAt)
--   worker_assignments        -> WorkerAssignment       (无 updatedAt)
--
-- 幂等: 表不存在 (全新库, 表由别处建) 或触发器已摘, 都什么也不做; 重复执行无副作用。
-- 拿不到表锁 5 秒即失败, 不让迁移排队卡住签到/日志等高频表 (Opus 终审 M1, 同 V20261120_97)
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY[
        'ai_intent_config_history',
        'ai_usage_log',
        'form_template_versions',
        'iot_device_data',
        'isapi_event_logs',
        'model_performance_log',
        'scheduling_predictions',
        'system_logs',
        'tool_call_cache',
        'worker_assignments'
    ]
    LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('DROP TRIGGER IF EXISTS %I ON public.%I', t || '_update_timestamp', t);
        END IF;
    END LOOP;
END
$$;
