-- V20261117_83__crew_recognition_intent_descriptions.sql
--
-- 2026-11-17 F006 数字班组实测 (BP 截图) 的识别错:
--   1. 数字计划员「帮我建个生产计划：明天做 2 吨演示·酱卤牛肉250g，周五交演示·上海连锁餐饮」→ 决策模型判 BATCH_UPDATE。
--      决策模型的选项文字 = 意图名 + 描述 + 前几个常见说法 (backend/python/ai/matcher/decision.py intent_text / _catalog_line)。
--      PRODUCTION_PLAN_CREATE 描述为空、常见说法里没有「建个生产计划」; BATCH_UPDATE 描述写着「更新生产批次信息，如计划产量」,
--      「生产计划 + 产量」的句子词面上更像后者。补 PRODUCTION_PLAN_CREATE 的描述与说法, 并在 BATCH_UPDATE 描述里写明
--      「修改已有批次, 不用于新建生产计划」。
--   2. 数字仓管「哪些原料批次快过期了？」落到 MATERIAL_BATCH_QUERY (它的状态词表故意不认「快过期」)。
--      MATERIAL_EXPIRING_ALERT 的常见说法前置「快过期 / 临期 / 即将过期」。
--   3. 数字成本会计「酱牛肉这个月的出成率怎么样？」没有意图接 (NO_RECOGNITION)。REPORT_BATCH_YIELD_RANK 的工具
--      report_batch_yield_rank 已支持 productName + 期间, 补「出成率怎么样 / 成品率 / 得率」说法和描述。
--
-- 只改描述与常见说法 (新说法放在最前, 选项里只带前 3 个), 不改分类、工具绑定、权限与澄清策略。
-- 事后核对: 库里有 PRODUCTION_PLAN_CREATE / BATCH_UPDATE 时, 每条都已写入新描述 (空库不报错)。

UPDATE ai_intent_configs
SET description = '新建一份生产计划（还没有的计划）：说明做什么产品、做多少、哪天生产，可带交期和客户。'
                  || '如「帮我建个生产计划，明天做2吨酱卤牛肉，周五交某客户」「排个产」。',
    keywords = '["建个生产计划","建生产计划","新建生产计划"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["建个生产计划","建生产计划","新建生产计划"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_PLAN_CREATE' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '修改已经存在的生产批次（要指明是哪个批次）的计划产量、状态等；不用于新建生产计划。',
    updated_at = NOW()
WHERE intent_code = 'BATCH_UPDATE' AND deleted_at IS NULL;

UPDATE ai_intent_configs
SET keywords = '["快过期","临期","即将过期"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["快过期","临期","即将过期"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_EXPIRING_ALERT' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '只读查询出成率（良品数量÷实际产出数量）：某产品或全部产品在某期间的批次出成率，按低到高或高到低排行并出柱图；'
                  || '如「酱牛肉这个月的出成率怎么样」「哪个批次出成率最低」。缺数量的批次不进排行并说明个数。',
    keywords = '["出成率怎么样","出成率","成品率","得率"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["出成率怎么样","出成率","成品率","得率"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'REPORT_BATCH_YIELD_RANK' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

DO $$
DECLARE
    total int;
    done int;
BEGIN
    -- 有这个意图的库 (prod) 必须改到; 没有这个意图的空库 (迁移测试) 不改也不报错
    SELECT count(*) INTO total FROM ai_intent_configs WHERE intent_code = 'PRODUCTION_PLAN_CREATE' AND deleted_at IS NULL;
    SELECT count(*) INTO done FROM ai_intent_configs
     WHERE intent_code = 'PRODUCTION_PLAN_CREATE' AND deleted_at IS NULL
       AND description LIKE '新建一份生产计划%' AND keywords @> '["建个生产计划"]'::jsonb;
    IF total > 1 OR done <> total THEN
        RAISE EXCEPTION 'PRODUCTION_PLAN_CREATE rows=% updated=% (expected equal, at most 1)', total, done;
    END IF;
    SELECT count(*) INTO total FROM ai_intent_configs WHERE intent_code = 'BATCH_UPDATE' AND deleted_at IS NULL;
    SELECT count(*) INTO done FROM ai_intent_configs
     WHERE intent_code = 'BATCH_UPDATE' AND deleted_at IS NULL AND description LIKE '%不用于新建生产计划%';
    IF total > 1 OR done <> total THEN
        RAISE EXCEPTION 'BATCH_UPDATE rows=% updated=% (expected equal, at most 1)', total, done;
    END IF;
END $$;
