-- 工厂意图描述第三批: 目录清理 (#865) 合并/移出后暴露出的两处回退。
--
-- 依据 (2026-10-03, 模拟 history 段, 清理前后同一管线逐任务对比, 未使用验收段):
--   「今日待收货」63 → 17: 被合并/移出的 仓管员今日任务 / 今日PO待收清单 原本接住「XX到了没」这类问法,
--      留下的 今日待收货清单 描述是开发者写法 ("今日 + 明日 expected delivery date 的 PO 行项目…")。
--   「今日待发清单」13 → 0: 出货按日期并入查询出货后, 泛化的「查询出货记录」抢走「今天发哪几车」。
-- 只改描述与关键词 (新词放最前、去重保序), 不动 tool_name / 权限 / 分类。

CREATE OR REPLACE FUNCTION pg_temp.prepend_keywords(old jsonb, add jsonb) RETURNS jsonb AS $$
    SELECT COALESCE(jsonb_agg(v ORDER BY first_pos), '[]'::jsonb)
    FROM (
        SELECT v, MIN(pos) AS first_pos
        FROM jsonb_array_elements_text(add || COALESCE(old, '[]'::jsonb)) WITH ORDINALITY AS t(v, pos)
        GROUP BY v
    ) s
$$ LANGUAGE sql IMMUTABLE;

UPDATE ai_intent_configs SET
    description = '仓管查今天、明天要到哪些货，某个原料到了没有、还差多少没到（按采购单的待收清单）。只查不改。',
    keywords = pg_temp.prepend_keywords(keywords, '["到了没","到货没","货到了吗","还没到","今天到什么货"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_TODAY_RECEIVING_QUERY' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '今天要发哪些货：列出今天该发、还没发完的销售订单（哪几车、哪些客户、还差多少）。只查不改。',
    keywords = pg_temp.prepend_keywords(keywords, '["今天发哪几车","今天要发什么","该发货的订单","还有哪些没发"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SPRINT10_SHIPMENT_PENDING_TODAY' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '查已经发出去的出货记录，可按客户、日期、单号、状态筛选。查的是发过的，不是今天还要发的。',
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'SHIPMENT_QUERY' AND deleted_at IS NULL;
