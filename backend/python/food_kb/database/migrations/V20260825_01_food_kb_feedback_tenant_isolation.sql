-- V20260825_01_food_kb_feedback_tenant_isolation.sql
--
-- P0 SECURITY — food_kb_feedback / food_kb_query_log 完全没有租户维度。
--
-- 症状 (2026-08-25 复核确认, backend/python/food_kb/api/feedback.py):
--   * GET  /api/food-kb/feedback/stats   把**所有**租户 rating<=2 的用户原话
--                                        与 AI 回答印在管理页上 (recent_low_ratings)
--   * GET  /api/food-kb/feedback/export  一次导出全平台 1000 行 (query / answer /
--                                        feedback_detail.comment / session_id / user_id)
--   * POST /api/food-kb/feedback/log-query 只按调用方自己传的 session_id 反查历史
--                                        问句, 猜中/重放别家 session 即可读回原话
--   两个读端点是 GET, 且 /api/food-kb/feedback* 不在 PUBLIC_PREFIXES 却**在**
--   DEMO_WRITE_ALLOW_SUFFIXES 里 —— 公开 /mobile-ai/rest/ 页面发给任意匿名访客的
--   演示租户 JWT 完全够得着 (auth_middleware.py:106-113 的 demo containment 只挡
--   「query 里带了别家 factory_id」和非 GET 写, 这两条都绕不住它)。
--
-- 这两张表的建表 DDL **不在本仓**(代码里指向的 migration_20260213_food_kb_feedback.sql
-- 从未入库, 表是在 prod 上带外创建的), 所以本文件用 to_regclass 守着,
-- 表不存在时整段跳过而不是报错。
--
-- 配套代码: backend/python/food_kb/api/feedback.py —— 四个 handler 全部改为
-- 从 JWT (request.state.factory_id) 取租户、fail-closed 401、每条 SQL 显式
-- `WHERE factory_id = $n`。⚠️ 代码里的显式条件才是真正把住边界的那一道;
-- 下面的 RLS 是纵深防御 (策略沿用本仓 V20260502_03/04 的 permissive-when-unset
-- 形状, GUC 未设时不拦, 所以单靠它挡不住 —— handler 里的 _set_tenant_guc 才让它生效)。
--
-- 回填: 存量行没有任何租户信息可推断, 一律留 NULL。
-- `WHERE factory_id = $n` 下 NULL 行对**所有**租户不可见 —— 这是有意的 fail-closed:
-- 不知道属于谁的用户原话, 宁可谁都读不到, 也不要继续给所有人读。

BEGIN;

-- ============================================================
-- food_kb_feedback
-- ============================================================
DO $$
BEGIN
  IF to_regclass('public.food_kb_feedback') IS NULL THEN
    RAISE NOTICE 'food_kb_feedback does not exist here — skipping';
    RETURN;
  END IF;

  ALTER TABLE food_kb_feedback ADD COLUMN IF NOT EXISTS factory_id VARCHAR(64);

  CREATE INDEX IF NOT EXISTS idx_food_kb_feedback_factory_created
    ON food_kb_feedback (factory_id, created_at DESC);
  CREATE INDEX IF NOT EXISTS idx_food_kb_feedback_factory_session
    ON food_kb_feedback (factory_id, session_id);

  ALTER TABLE food_kb_feedback ENABLE ROW LEVEL SECURITY;
  ALTER TABLE food_kb_feedback FORCE ROW LEVEL SECURITY;

  DROP POLICY IF EXISTS tenant_select ON food_kb_feedback;
  DROP POLICY IF EXISTS tenant_insert ON food_kb_feedback;
  DROP POLICY IF EXISTS tenant_update ON food_kb_feedback;
  DROP POLICY IF EXISTS tenant_delete ON food_kb_feedback;

  CREATE POLICY tenant_select ON food_kb_feedback FOR SELECT
    USING (factory_id = current_setting('app.factory_id', true)
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) IS NULL);
  CREATE POLICY tenant_insert ON food_kb_feedback FOR INSERT
    WITH CHECK (factory_id IS NOT NULL);
  CREATE POLICY tenant_update ON food_kb_feedback FOR UPDATE
    USING (factory_id = current_setting('app.factory_id', true)
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) IS NULL)
    WITH CHECK (factory_id IS NOT NULL);
  CREATE POLICY tenant_delete ON food_kb_feedback FOR DELETE
    USING (factory_id = current_setting('app.factory_id', true));
END
$$;

-- ============================================================
-- food_kb_query_log
-- ============================================================
DO $$
BEGIN
  IF to_regclass('public.food_kb_query_log') IS NULL THEN
    RAISE NOTICE 'food_kb_query_log does not exist here — skipping';
    RETURN;
  END IF;

  ALTER TABLE food_kb_query_log ADD COLUMN IF NOT EXISTS factory_id VARCHAR(64);

  -- log-query 的反查是 (factory_id, session_id, created_at) 三元组
  CREATE INDEX IF NOT EXISTS idx_food_kb_query_log_factory_session_created
    ON food_kb_query_log (factory_id, session_id, created_at DESC);

  ALTER TABLE food_kb_query_log ENABLE ROW LEVEL SECURITY;
  ALTER TABLE food_kb_query_log FORCE ROW LEVEL SECURITY;

  DROP POLICY IF EXISTS tenant_select ON food_kb_query_log;
  DROP POLICY IF EXISTS tenant_insert ON food_kb_query_log;
  DROP POLICY IF EXISTS tenant_update ON food_kb_query_log;
  DROP POLICY IF EXISTS tenant_delete ON food_kb_query_log;

  CREATE POLICY tenant_select ON food_kb_query_log FOR SELECT
    USING (factory_id = current_setting('app.factory_id', true)
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) IS NULL);
  CREATE POLICY tenant_insert ON food_kb_query_log FOR INSERT
    WITH CHECK (factory_id IS NOT NULL);
  CREATE POLICY tenant_update ON food_kb_query_log FOR UPDATE
    USING (factory_id = current_setting('app.factory_id', true)
           OR current_setting('app.factory_id', true) = ''
           OR current_setting('app.factory_id', true) IS NULL)
    WITH CHECK (factory_id IS NOT NULL);
  CREATE POLICY tenant_delete ON food_kb_query_log FOR DELETE
    USING (factory_id = current_setting('app.factory_id', true));
END
$$;

COMMIT;
