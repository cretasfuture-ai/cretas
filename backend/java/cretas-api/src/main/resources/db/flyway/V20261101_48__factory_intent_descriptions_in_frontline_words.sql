-- 工厂意图目录清理: 把 6 个高错误意图的描述从开发者写法换成一线人话, 并把常见说法放到关键词最前面。
--
-- 依据 (2026-10-02, 模拟流量 history 段, 第②层未命中精确表的 1,645 次请求, 未使用验收段):
--   错得最多的金标意图: 使用原料 178 / 完成工序 156 / 开始工序 129 / 缺产订单 41 / 查询工序任务 16;
--   旧描述如「将工序任务从 PENDING 推进到 IN_PROGRESS」, 车间说的是「开工 / 搞起 / 弄好了」。
-- 离线同一份请求: 候选召回 62.3% -> 74.0%, 首选正确 39.3% -> 48.0% (配合粗选目录带常见说法, 同一 PR)。
--
-- 关键词: 新词放最前、去重并保留首次出现的顺序 (粗选目录只展示前 3 个)。
-- ⛔ 不加与其它在用意图撞名的词: 「出库」属成品发货 SHIPMENT_CONFIRM_CREATE、「入库」属 RECEIVE_WITH_LIMIT、
--    「该生产什么」属 PRODUCTION_DEMAND_ANALYSIS (2026-10-02 prod 查重)。
-- 只改描述与关键词, 不动 tool_name / 权限 / 分类。config_version 递增, 便于各侧缓存识别变化。

CREATE OR REPLACE FUNCTION pg_temp.prepend_keywords(old jsonb, add jsonb) RETURNS jsonb AS $$
    SELECT COALESCE(jsonb_agg(v ORDER BY first_pos), '[]'::jsonb)
    FROM (
        SELECT v, MIN(pos) AS first_pos
        FROM jsonb_array_elements_text(add || COALESCE(old, '[]'::jsonb)) WITH ORDINALITY AS t(v, pos)
        GROUP BY v
    ) s
$$ LANGUAGE sql IMMUTABLE;

UPDATE ai_intent_configs SET
    description = '车间操作工开始做某道工序或某个产品（开工）。只是开始干活，不是新建生产计划或生产批次。',
    keywords = pg_temp.prepend_keywords(keywords, '["开工","开干","开始做","开始干","搞起","上线做"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WORK_PROCESS_TASK_START' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '车间操作工报工：某道工序或某个产品做完了，并报产量（做了多少箱、多少斤）。',
    keywords = pg_temp.prepend_keywords(keywords, '["报工","做完了","弄好了","搞定了","报个数","报产量"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'WORK_PROCESS_TASK_COMPLETE' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '领用原料出库、投料：把原料从仓库拿去车间用，说物料和数量。领用出库不是到货入库。',
    keywords = pg_temp.prepend_keywords(keywords, '["领料","投料","发料","拿去用","领了"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_BATCH_USE' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '新到货的原料登记入库，生成原料批次。到货入库不是领用出库或投料。',
    keywords = pg_temp.prepend_keywords(keywords, '["到货了","进货","送来了"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_BATCH_CREATE' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '查哪些订单还缺产、还要生产多少（订单量减已发货减在产），给出建议起产量。只查不改。',
    keywords = pg_temp.prepend_keywords(keywords, '["还缺哪些单","哪些订单还没做","还要生产多少","缺产"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_DEMAND_QUERY' AND deleted_at IS NULL;

UPDATE ai_intent_configs SET
    description = '操作工查自己今天或当前要做的工序任务列表和状态。',
    keywords = pg_temp.prepend_keywords(keywords, '["今天做啥","我的任务","还有哪些活","今天的活"]'::jsonb),
    config_version = COALESCE(config_version, 0) + 1,
    updated_at = NOW()
WHERE intent_code = 'PROCESS_TASK_QUERY' AND deleted_at IS NULL;
