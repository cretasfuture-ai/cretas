-- [PROTO-SCHED-DASH] 调度预警「处理」接口对所有工厂报 500 的根因修复。
--
-- 根因: 旧的 migration-pg/V0002__create_update_triggers.sql 按「每张表都有 updated_at」批量给
-- 173 张表挂了 BEFORE UPDATE 触发器 update_timestamp() (函数体只有 NEW.updated_at = CURRENT_TIMESTAMP)。
-- scheduling_alerts 没有 updated_at 列 (实体 SchedulingAlert 也没有 updatedAt 字段), 于是对这张表的
-- 任何 UPDATE (确认 / 处理预警) 都在触发器里报
--   record "new" has no field "updated_at"
-- 2026-11-19 prod 只读核对 (\d scheduling_alerts + pg_trigger): 列里没有 updated_at,
-- 触发器 scheduling_alerts_update_timestamp 仍挂着。
--
-- 取最小改动: 实体没有 updatedAt ⇒ 不补列 (补了也没有代码读写它), 只摘掉这一张表上的触发器。
-- 共用函数 update_timestamp() 不动 (还有一百多张表在用)。
--
-- 幂等: 表不存在 (全新库, 表由别处建) 或触发器已摘, 都什么也不做; 重复执行无副作用。
-- DROP TRIGGER 要拿 AccessExclusiveLock: 撞上长事务时 5 秒内拿不到就失败重试, 不长时间挡住后续读写。
-- SET LOCAL 只作用于本迁移事务 (Flyway 对 PG 迁移默认开事务), 不会带到连接池里的其它会话。
SET LOCAL lock_timeout = '5s';

DO $$
BEGIN
    IF to_regclass('public.scheduling_alerts') IS NOT NULL THEN
        EXECUTE 'DROP TRIGGER IF EXISTS scheduling_alerts_update_timestamp ON public.scheduling_alerts';
    END IF;
END
$$;
