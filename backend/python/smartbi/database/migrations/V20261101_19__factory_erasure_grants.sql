-- 整厂数据彻底删除 (手册 docs/security/factory-data-export-and-erasure-runbook.md §6) 在 SmartBI 库所需的权限。
--
-- Java 后端用 smartbi_user 连这个库。2026-10-26 prod 实测: smartbi_user 对 152 张表里的 14 张没有 DELETE,
-- 其中带 factory_id、属于工厂数据的是下面三张业务表 + LLM 外发审计表。
--
-- 1) 三张业务表直接补 DELETE (删除时连接的 app.factory_id 置为本厂, 行级安全的 DELETE 策略照常生效)。
-- 2) smart_bi_llm_egress_audit 是刻意只追加的 (V20260531_01 只给了 SELECT, INSERT)。⛔ 不给 smartbi_user
--    DELETE —— 那会让应用代码能删任意审计行。改为一个 SECURITY DEFINER 函数: 只能按一个非空工厂编号删、
--    且本事务的 app.factory_id 必须就是这个工厂 (剩余风险: 任何用 smartbi_user 的代码先设变量再调用仍能删,
--    单独的删除角色要改连接配置, 本次不做),
--    整厂删除在调用它之前先把这些行的「谁 / 何时 / 做了什么 / 对象编号」写进主库留存区 (口径 Q3, 保留 6 个月)。
-- 3) restaurant_* / smart_bi_agent_* 属于已废弃的餐饮线 (2026-10-08 裁定), 不在这里放开; 工厂若在这些表里有行,
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
    -- 只在「本事务已把行级安全变量设成这个工厂」时允许 —— 整厂删除正是这样调用的;
    -- 别的代码路径想删审计, 必须先显式切到那个工厂, 不会顺手误删。
    IF current_setting('app.factory_id', true) IS DISTINCT FROM p_factory_id THEN
        RAISE EXCEPTION 'factory_erase_llm_egress_audit: app.factory_id 必须等于要删除的工厂 %', p_factory_id;
    END IF;
    IF to_regclass('public.smart_bi_llm_egress_audit') IS NULL THEN
        RETURN 0;
    END IF;
    DELETE FROM public.smart_bi_llm_egress_audit WHERE factory_id = p_factory_id;
    GET DIAGNOSTICS n = ROW_COUNT;
    RETURN n;
END $$;

REVOKE ALL ON FUNCTION public.factory_erase_llm_egress_audit(text) FROM PUBLIC;
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smartbi_user') THEN
        GRANT EXECUTE ON FUNCTION public.factory_erase_llm_egress_audit(text) TO smartbi_user;
    END IF;
END $$;
