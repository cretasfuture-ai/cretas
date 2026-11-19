-- V20261120_11__sales_growth_attribution_intent_description.sql
--
-- BP 问题 47 (2026-11-19 10:00, F006 销售数字专员): 追问「10月比9月涨了8.2%，主要是哪个客户、哪个产品带来的」
-- 答成 15 张销售订单列表, 没给涨因。产品销售排行工具已支持这类「涨因 / 贡献」问法 (按客户、产品各算两个月的差额,
-- 按增量排序并给占总增量的比例), 但订单列表 (ORDER_LIST) 的描述写了「按客户汇总、哪个客户下单最多」,
-- 决策模型把「哪个客户…带来的」判给了它。决策模型的选项文字 = 意图名 + 描述 + 前几个常见说法 (新说法放最前):
--   * PRODUCT_PRODUCT_SALES_RANKING (产品销售排行): 写明也答「X月比Y月涨了/降了, 主要是哪个客户、哪个产品带来的」;
--   * ORDER_LIST: 写明涨跌归因 (哪个客户、产品带来的变化) 不用它, 用产品销售排行。
-- 只改描述与常见说法, 不改分类、工具绑定、权限与澄清策略。

UPDATE ai_intent_configs
SET description = '按真实销售订单行汇总产品销售额与数量排行；也回答两个月之间销售额涨跌的来源：'
                  || '「10月比9月涨了8.2%，主要是哪个客户、哪个产品带来的」，按客户、产品分别算两个月的差额，'
                  || '按增量从大到小列前几名并给占总增量的比例。',
    keywords = '["10月比9月涨了主要是哪个客户哪个产品带来的","哪个客户带来的增长","哪个产品带来的增长","涨了主要是哪个客户"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["10月比9月涨了主要是哪个客户哪个产品带来的","哪个客户带来的增长","哪个产品带来的增长","涨了主要是哪个客户"]'::jsonb
                                       @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'PRODUCT_PRODUCT_SALES_RANKING' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = description
                  || '问两个月销售额涨跌是哪个客户、哪个产品带来的（如「10月比9月涨了，主要是哪个客户、哪个产品带来的」），'
                  || '不用这个，用产品销售排行（它按客户、产品拆两个月的增量）。',
    updated_at = NOW()
WHERE intent_code = 'ORDER_LIST' AND deleted_at IS NULL
  AND description IS NOT NULL
  AND description NOT LIKE '%涨跌是哪个客户%';

DO $$
DECLARE
    total int;
    done int;
BEGIN
    SELECT count(*) INTO total FROM ai_intent_configs WHERE intent_code = 'PRODUCT_PRODUCT_SALES_RANKING' AND deleted_at IS NULL;
    SELECT count(*) INTO done FROM ai_intent_configs
     WHERE intent_code = 'PRODUCT_PRODUCT_SALES_RANKING' AND deleted_at IS NULL
       AND description LIKE '%涨跌的来源%';
    IF total > 1 OR done <> total THEN
        RAISE EXCEPTION 'PRODUCT_PRODUCT_SALES_RANKING rows=% updated=% (expected equal, at most 1)', total, done;
    END IF;
END $$;
