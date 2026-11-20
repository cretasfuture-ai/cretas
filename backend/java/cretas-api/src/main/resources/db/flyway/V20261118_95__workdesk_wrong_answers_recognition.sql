-- V20261118_95__workdesk_wrong_answers_recognition.sql
--
-- 2026-11-18 LSMDEMO 数字专员工作台实测的答非所问 (识别层, 只改描述与常见说法, 不改分类/工具绑定/权限):
--   A/B 销售: 「最近有哪些销售订单？按客户汇总一下」「本月销售订单按客户汇总，哪个客户下单最多？」
--       订单列表 (order_list) 现在能按客户汇总; 客户收入趋势只管按月走势, 不再抢「订单按客户汇总」。
--   C 生产: 「哪些订单可能交不了货？缺什么料？」 -> 销售订单缺料分析 (shortage_analyze 不给订单号 = 在途订单缺料总览);
--       订单缺产分析与排产建议 写明不用于缺原料。
--   D 库存: 「哪些原料库存快不够了？有没有快过期的？」 -> 低库存预警 (同句带出临期批次); 待收清单写明只管到货待收。
-- 决策模型的选项文字 = 意图名 + 描述 + 前几个常见说法 (backend/python/ai/matcher/decision.py intent_text), 新说法放最前。

UPDATE ai_intent_configs
SET description = '查询销售订单列表（客户下给我们的单）：最近有哪些销售订单、某段时间的订单、按状态或客户筛选；'
                  || '也能按客户汇总各客户的订单数和金额、看哪个客户下单最多。采购订单不在这里，请用采购订单查询。',
    keywords = '["按客户汇总","哪个客户下单最多","最近有哪些销售订单","销售订单按客户汇总"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["按客户汇总","哪个客户下单最多","最近有哪些销售订单","销售订单按客户汇总"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'ORDER_LIST' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '只看销售额按月的变化趋势（本月与上月对比、最近几个月走势）。'
                  || '要看订单清单、或把订单按客户汇总、问哪个客户下单最多，不用这个，用订单列表。',
    updated_at = NOW()
WHERE intent_code = 'CUSTOMER_REVENUE_TREND' AND deleted_at IS NULL;

UPDATE ai_intent_configs
SET description = '分析在途销售订单的原料缺口：哪些订单可能交不了货、缺什么料、缺多少；也可指定一个订单号只看这一单。'
                  || '只读，不创建采购单或生产计划。',
    keywords = '["哪些订单可能交不了货","订单缺什么料","哪些订单缺料","哪个订单缺原料"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["哪些订单可能交不了货","订单缺什么料","哪些订单缺料","哪个订单缺原料"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'SHORTAGE_ANALYSIS' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '生产经理看待产订单（订单总量减已发已产）还缺多少成品、建议先起产什么；只管成品缺口与排产。'
                  || '问订单交不了货、缺什么原料，不用这个，用销售订单缺料分析。',
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_DEMAND_ANALYSIS' AND deleted_at IS NULL;

UPDATE ai_intent_configs
SET description = '查询生产计划的完工交货预警（超期、紧急、预警、正常），默认看7天内；只管生产计划的交期，'
                  || '问销售订单缺什么原料、哪些订单因缺料交不了货，不用这个，用销售订单缺料分析。',
    updated_at = NOW()
WHERE intent_code = 'PRODUCTION_DELIVERY_WARN_QUERY' AND deleted_at IS NULL;

UPDATE ai_intent_configs
SET keywords = '["库存快不够了","哪些原料库存不够","原料库存不足","库存够不够"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["库存快不够了","哪些原料库存不够","原料库存不足","库存够不够"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    description = '查询库存低于安全库存的原料（哪些原料库存快不够了）；问句里同时问快过期的，会一并列出7天内将过期的批次。',
    updated_at = NOW()
WHERE intent_code = 'MATERIAL_LOW_STOCK_ALERT' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '仓管查今天、明天要到哪些采购货（采购单待收清单）、某原料到货了没有；只管到货待收，'
                  || '不回答库存够不够、哪些原料快不够用、哪些快过期（那些用低库存预警和即将过期预警）。',
    updated_at = NOW()
WHERE intent_code IN ('MATERIAL_TODAY_RECEIVING_QUERY', 'WAREHOUSE_TODAY_RECEIVING_PENDING') AND deleted_at IS NULL;

-- E 质检: 「最近有哪些质检不合格的批次？」 -> 质检记录查询 (按结果=不合格筛), 不再落到要批次号/客户的追溯类意图。
UPDATE ai_intent_configs
SET description = '查询质检记录（质检单列表）：最近有哪些质检不合格的批次、某批次的质检结果、按合格/不合格/待检筛选；'
                  || '不需要先给批次号，没说批次就列近期记录。',
    keywords = '["质检不合格的批次","哪些质检不合格","最近不合格的质检记录"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["质检不合格的批次","哪些质检不合格","最近不合格的质检记录"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'QUALITY_CHECK_QUERY' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

-- F 质量追溯: 「从成品批次往回追，用了哪些原料批次、哪些供应商？」 -> 批次完整追溯; 必须有批次号, 没有就追问 (不自己挑一个批次)。
UPDATE ai_intent_configs
SET description = '批次完整追溯：某个成品批次往回追，用了哪些原料批次、哪些供应商，以及出货影响了哪些客户。'
                  || '必须说明是哪个批次，没说批次号就先问批次号。只读。',
    keywords = '["成品批次往回追","用了哪些原料批次","哪些供应商","批次全追溯"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["成品批次往回追","用了哪些原料批次","哪些供应商","批次全追溯"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'BATCH_FULL_TRACE' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

-- G 财务: 「本月应收应付情况怎么样？哪些客户逾期？」 -> 应收账龄 (逾期客户); 整月经营复盘只在要看三表/整月经营时用。
UPDATE ai_intent_configs
SET description = '应收账龄：应收账款按账龄分桶（含逾期）、哪些客户逾期欠款、逾期多久、60天以上的高风险客户。只读。',
    keywords = '["哪些客户逾期","应收情况","客户逾期欠款","应收账龄"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["哪些客户逾期","应收情况","客户逾期欠款","应收账龄"]'::jsonb @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'ACCOUNTS_RECEIVABLE_AGING' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

UPDATE ai_intent_configs
SET description = '财务主管的整月经营复盘：期间状态、利润表、资产负债表、现金流、工资成本、商机漏斗等一次看完。'
                  || '只问应收、应付或哪些客户逾期，不用这个，用应收账龄。',
    updated_at = NOW()
WHERE intent_code = 'MONTHLY_FINANCIAL_CLOSE' AND deleted_at IS NULL;

-- 事后核对: 库里有这些意图时, 描述都已含新口径 (空库不报错)。
DO $$
DECLARE
    bad int;
BEGIN
    SELECT count(*) INTO bad FROM ai_intent_configs
     WHERE deleted_at IS NULL
       AND ((intent_code = 'ORDER_LIST' AND description NOT LIKE '%按客户汇总%')
         OR (intent_code = 'SHORTAGE_ANALYSIS' AND description NOT LIKE '%交不了货%')
         OR (intent_code = 'MATERIAL_LOW_STOCK_ALERT' AND description NOT LIKE '%快过期%'));
    IF bad <> 0 THEN
        RAISE EXCEPTION 'workdesk recognition descriptions not applied on % rows', bad;
    END IF;
END $$;
