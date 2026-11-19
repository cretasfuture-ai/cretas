-- V20261120_21__cost_composition_intent_descriptions.sql
--
-- 2026-11-19 F006 数字成本会计实测的退步:
--   A. 「酱卤牛肉成本怎么构成」→ 答「需要先查一下真实数据，请您说明想查哪一项」(闲聊兜底话术)。
--      根因在代码: ChatBusinessFactGuard 把「怎么 + 构成」当成「怎么做」类咨询, 不算业务问法, 句子进了闲聊模型
--      (本迁移的配套代码修复)。
--   B. 「演示·酱卤牛肉250g 的成本是怎么构成的？」→ 决策模型判成 BOM_RECIPE_QUERY (描述里写着「+ 成本」),
--      该工具只收编号, 回「请提供 productTypeId 或 recipeId」。成本构成 (原料/人工/设备/其他占比) 是 COST_QUERY
--      (report_cost_query) 的能力, 它已支持按产品名筛选。
--
-- 决策模型的选项文字 = 意图名 + 描述 + 前 3 个常见说法 (backend/python/ai/matcher/decision.py)。
-- 本迁移只改描述与常见说法 (新说法放在最前), 不改分类、工具绑定、权限与澄清策略, 不动目录 JSON / CATALOG_VERSION。
-- 事后核对: 库里有这两个意图时, 每条都已写入新描述 (空库不报错)。

UPDATE ai_intent_configs
SET description = '只读查询生产成本：期间内已完成批次的成本构成（原料、人工、设备、其他各占多少）、各产品总成本与单位成本、成本涨跌对比；可按产品名筛选。'
                  || '如「酱卤牛肉成本怎么构成」「演示·酱卤牛肉250g 的成本是怎么构成的」「酱卤牛肉的成本构成」「酱卤牛肉成本是怎么算的」「单位成本多少」。',
    keywords = '["成本构成","成本怎么构成","成本是怎么算的"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["成本构成","成本怎么构成","成本是怎么算的"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'COST_QUERY' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '查询某产品当前生效的 BOM 配方明细：配料表、各物料用量、出成率。'
                  || '问成本构成、成本占比、成本怎么算的不用这个意图，用成本查询。',
    updated_at = NOW()
WHERE intent_code = 'BOM_RECIPE_QUERY' AND deleted_at IS NULL;

DO $$
DECLARE
    total int;
    done int;
BEGIN
    -- 有这个意图的库 (prod) 必须改到; 没有这个意图的空库 (迁移测试) 不改也不报错
    SELECT count(*) INTO total FROM ai_intent_configs WHERE intent_code = 'COST_QUERY' AND deleted_at IS NULL;
    SELECT count(*) INTO done FROM ai_intent_configs
     WHERE intent_code = 'COST_QUERY' AND deleted_at IS NULL
       AND description LIKE '只读查询生产成本%' AND keywords @> '["成本构成"]'::jsonb;
    IF done <> total THEN
        RAISE EXCEPTION 'COST_QUERY rows=% updated=% (expected equal)', total, done;
    END IF;
    SELECT count(*) INTO total FROM ai_intent_configs WHERE intent_code = 'BOM_RECIPE_QUERY' AND deleted_at IS NULL;
    SELECT count(*) INTO done FROM ai_intent_configs
     WHERE intent_code = 'BOM_RECIPE_QUERY' AND deleted_at IS NULL AND description LIKE '%用成本查询%';
    IF done <> total THEN
        RAISE EXCEPTION 'BOM_RECIPE_QUERY rows=% updated=% (expected equal)', total, done;
    END IF;
END $$;
