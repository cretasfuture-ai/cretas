-- 整厂数据彻底删除 (手册 docs/security/factory-data-export-and-erasure-runbook.md §6) 在 SmartBI 库所需的权限。
--
-- Java 后端用 smartbi_user 连这个库。2026-10-26 prod 实测: smartbi_user 对 152 张表里的 14 张没有 DELETE,
-- 其中带 factory_id、属于工厂数据的是下面三张业务表 + LLM 外发审计表。
--
-- 1) 三张业务表直接补 DELETE (删除时连接的 app.factory_id 置为本厂, 行级安全的 DELETE 策略照常生效)。
-- 2) smart_bi_llm_egress_audit 是刻意只追加的 (V20260531_01 只给了 SELECT, INSERT)。⛔ 不给 smartbi_user
--    DELETE —— 那会让应用代码能删任意审计行。改为一个 SECURITY DEFINER 函数, 只能按一个非空工厂编号删,
--    且本事务里 app.factory_id 与 app.factory_erasure 都必须等于这个工厂: 前者是行级安全变量 (Python 每个请求都会设),
--    后者只有整厂删除会设 —— 普通请求代码顺手调用会被拒。剩余风险: 有意先设这两个变量再调用的代码仍能删;
--    独立的删除账号要改连接配置, 本次不做。
--    整厂删除在调用它之前先把这些行的「谁 / 何时 / 做了什么 / 对象编号」写进主库留存区 (口径 Q3, 保留 6 个月)。
-- 3) factory_erasure_tenant_ids(): 列出库里出现过的全部工厂编号 (只有编号, 不含任何行内容)。
--    2026-10-26 prod 有 95 张表是严格行级安全 (只认 factory_id = app.factory_id), smartbi_user 在空变量下看不见
--    别的工厂在这些表里的行; 整厂删除逐个工厂数「其他工厂一行不少」时, 需要完整的工厂编号清单, 否则只在严格表里
--    出现的工厂不在对照之内。
-- 4) restaurant_* / smart_bi_agent_* 属于已废弃的餐饮线 (2026-10-08 裁定), 不在这里放开; 工厂若在这些表里有行,
--    删除预演会报「没有删除权限」并拒绝执行。
--
-- 表不存在 (新建测试库) 时跳过, 不报错。

DO $$
DECLARE
    t text;
BEGIN
    FOREACH t IN ARRAY ARRAY['smart_bi_factory_chart_selections', 'smart_bi_finance_data', 'ai_time_phrase_corpus'] LOOP
        IF to_regclass('public.' || t) IS NOT NULL THEN
            EXECUTE format('GRANT DELETE ON TABLE public.%I TO smartbi_user', t);
        END IF;
    END LOOP;
END $$;

CREATE OR REPLACE FUNCTION public.factory_erase_llm_egress_audit(p_factory_id text)
RETURNS bigint
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    n bigint;
BEGIN
    IF p_factory_id IS NULL OR btrim(p_factory_id) = '' THEN
        RAISE EXCEPTION 'factory_erase_llm_egress_audit: 工厂编号不能为空';
    END IF;
    IF current_setting('app.factory_id', true) IS DISTINCT FROM p_factory_id
       OR current_setting('app.factory_erasure', true) IS DISTINCT FROM p_factory_id THEN
        RAISE EXCEPTION 'factory_erase_llm_egress_audit: app.factory_id 与 app.factory_erasure 都必须等于要删除的工厂 %',
            p_factory_id;
    END IF;
    IF to_regclass('public.smart_bi_llm_egress_audit') IS NULL THEN
        RETURN 0;
    END IF;
    DELETE FROM public.smart_bi_llm_egress_audit WHERE factory_id = p_factory_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;

CREATE OR REPLACE FUNCTION public.factory_erasure_tenant_ids()
RETURNS SETOF text
LANGUAGE plpgsql
STABLE
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
DECLARE
    r record;
BEGIN
    FOR r IN
        SELECT c.relname
          FROM pg_attribute a
          JOIN pg_class c ON c.oid = a.attrelid
          JOIN pg_namespace ns ON ns.oid = c.relnamespace
         WHERE ns.nspname = current_schema() AND a.attname = 'factory_id' AND NOT a.attisdropped
           AND c.relkind IN ('r', 'p') AND NOT c.relispartition
    LOOP
        RETURN QUERY EXECUTE format('SELECT DISTINCT factory_id::text FROM %I.%I WHERE factory_id IS NOT NULL',
                                    current_schema(), r.relname);
    END LOOP;
END $$;

REVOKE ALL ON FUNCTION public.factory_erase_llm_egress_audit(text) FROM PUBLIC;
REVOKE ALL ON FUNCTION public.factory_erasure_tenant_ids() FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartbi_user') THEN
        GRANT EXECUTE ON FUNCTION public.factory_erase_llm_egress_audit(text) TO smartbi_user;
        GRANT EXECUTE ON FUNCTION public.factory_erasure_tenant_ids() TO smartbi_user;
    END IF;
END $$;
