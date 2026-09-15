-- V20261101_44__ai_lineage_by_batch_number_and_source_individual_query.sql
--
-- FEAT-AI-LINEAGE-INDIVIDUAL-TOOLS-20260915 -- 手机「AI 问答」答三类问题:
--   1. 「批次 SL-DEMO-BATCH-V1 来自哪几头牛」  -> LINEAGE_QUERY (lineage_query, 现在直接吃批次号)
--   2. 「SL-BULL-001 这头公牛有哪些后代」      -> SOURCE_INDIVIDUAL_QUERY (source_individual_query, 新)
--   3. 「哪一头牛的 DNA 比对不匹配」            -> SOURCE_INDIVIDUAL_QUERY
--
-- 实现: ai/tool/impl/lineage/LineageQueryTool.java (batch_number 参数)
--       ai/tool/impl/source/SourceIndividualQueryTool.java (新 Tool)
-- 两者都是 Spring @Component, 由 ToolRegistry 自动注册, IntentExecutor 走 Tool 直接执行分支.
--
-- 三条预检 (照 V20260823_05):
--   - string literal 里没有 ||  (jsonb 拼接的 || 在 literal 外面)
--   - keywords 是 JSONB 数组字面量
--   - tool_name 非 NULL, intent_category=QUERY
-- 另: 全文没有 "$" 后面紧跟 "{" 的写法 -- Flyway 会把它当占位符 (注释里也算).

-- =====================================================================
-- 1. LINEAGE_QUERY: 追加「来自哪几头牛」一族关键词 (去重合并, 不覆盖已有的),
--    description 改成说明可以直接给批次号.
-- =====================================================================
UPDATE ai_intent_configs
   SET keywords = (
         SELECT jsonb_agg(DISTINCT e)
           FROM jsonb_array_elements(
                  COALESCE(keywords::jsonb, '[]'::jsonb)
                  || '["来自哪几头牛","来自哪头牛","哪几头牛","这批肉来自","牛的来源","源头个体","这批来自","用了哪些原料","来自哪些牛"]'::jsonb
                ) e
       ),
       description = 'Sprint 11 D4 + 2026-09-15 -- 批次溯源 Tool. 直接给 batch_number (批次号, 如 SL-DEMO-BATCH-V1) 即可, batch_type 缺省 PRODUCTION_BATCH (也支持 MATERIAL_BATCH/FINISHED_BATCH/SHIPMENT_RECORD); 老用法 batch_type + batch_id 仍可用. 返回全部祖先 (上游/源批次, 含每头源头个体的耳标/品种/来源场/DNA 状态) + 后代 (下游/客户) + 直接边 metadata + 一段人话摘要 (来自几头牛). 触发: 这批来自哪几头牛 / 这批用了哪批原料 / 召回这批查哪些客户. Sensitivity MEDIUM 因含批次明细 + 客户信息.',
       updated_at = NOW()
 WHERE intent_code = 'LINEAGE_QUERY';

-- =====================================================================
-- 2. SOURCE_INDIVIDUAL_QUERY: 新意图 (QUERY / MEDIUM / priority 82, 略高于 LINEAGE_QUERY 的 80).
--    required_permission 与 SourceIndividualController 详情接口同口径 (任一即可, 逗号分隔).
-- =====================================================================
INSERT INTO ai_intent_configs (
    id, intent_code, intent_name, intent_category, tool_name,
    sensitivity_level, required_permission, keywords, description, priority, is_active,
    created_at, updated_at
)
VALUES
    (gen_random_uuid(), 'SOURCE_INDIVIDUAL_QUERY', '源头个体查询', 'QUERY',
     'source_individual_query', 'MEDIUM', 'warehouse:read,inventory:read',
     '["后代","有哪些后代","父本","母本","系谱","同父","兄弟姐妹","DNA 不匹配","DNA不匹配","DNA 比对","DNA比对","比对不匹配","不匹配","未采样","已比中","已采样","耳标","这头牛","这头公牛","这头母牛","个体档案","档案编码","去了哪","这头牛去了哪","做成了哪批","哪头牛的 DNA","种公牛","种牛"]',
     'FEAT-AI-LINEAGE-INDIVIDUAL-TOOLS-20260915 -- 源头个体 (一头牛) 查询 Tool. 参数 individual_code / tag_number 二选一定位到一头, ask=profile|pedigree|offspring|siblings|downstream 选要看的段; 只给 dna_status (UNSAMPLED/SAMPLED/MATCHED/MISMATCH, 也接受中文) 时列出该状态的个体 (上限 50). 找不到就说找不到, 不模糊匹配. 摘要不含基因库 ID / DNA 样本号. 触发: 这头公牛有哪些后代 / 哪一头牛的 DNA 比对不匹配 / 这头牛去了哪.',
     82, true, NOW(), NOW())
ON CONFLICT (intent_code) DO UPDATE SET
    tool_name = EXCLUDED.tool_name,
    intent_name = EXCLUDED.intent_name,
    intent_category = EXCLUDED.intent_category,
    sensitivity_level = EXCLUDED.sensitivity_level,
    required_permission = EXCLUDED.required_permission,
    keywords = EXCLUDED.keywords,
    description = EXCLUDED.description,
    priority = EXCLUDED.priority,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

-- =====================================================================
-- 3. 事后核对: 两行都在, 且 LINEAGE_QUERY 的关键词确实长进去了.
--    (读数, 不是假设 -- V20260823_05 没跑过的库上面那条 UPDATE 会静默影响 0 行.)
-- =====================================================================
DO $$
DECLARE
    lineage_rows INT;
    lineage_kw   INT;
    si_rows      INT;
BEGIN
    SELECT COUNT(*) INTO lineage_rows FROM ai_intent_configs WHERE intent_code = 'LINEAGE_QUERY';
    IF lineage_rows <> 1 THEN
        RAISE EXCEPTION 'V20261101_44: LINEAGE_QUERY 应恰好 1 行, 实际 %', lineage_rows;
    END IF;
    SELECT COUNT(*) INTO lineage_kw
      FROM ai_intent_configs, jsonb_array_elements_text(keywords::jsonb) k
     WHERE intent_code = 'LINEAGE_QUERY' AND k = '来自哪几头牛';
    IF lineage_kw <> 1 THEN
        RAISE EXCEPTION 'V20261101_44: LINEAGE_QUERY 关键词「来自哪几头牛」应出现 1 次, 实际 %', lineage_kw;
    END IF;
    SELECT COUNT(*) INTO si_rows
      FROM ai_intent_configs
     WHERE intent_code = 'SOURCE_INDIVIDUAL_QUERY'
       AND tool_name = 'source_individual_query'
       AND is_active = true;
    IF si_rows <> 1 THEN
        RAISE EXCEPTION 'V20261101_44: SOURCE_INDIVIDUAL_QUERY 应恰好 1 行且绑定 source_individual_query, 实际 %', si_rows;
    END IF;
    RAISE NOTICE 'V20261101_44 ok: LINEAGE_QUERY keywords merged, SOURCE_INDIVIDUAL_QUERY registered';
END $$;
