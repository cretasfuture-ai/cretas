-- V20261119_51__yield_loss_process_intent_description.sql
--
-- BP 问题 28 (2026-11-18, LSMDEMO/F006): 「最近有哪些损耗异常」「熟制杀菌最近出成率怎么样」「这个月出成率为什么掉了、掉在哪道工序」
-- 以前答「暂不支持」或被判成质检 (答「质检不合格率 0%」)。工具 report_batch_yield_rank 现已支持按工序视图
-- (view=loss_anomaly / process / process_compare, 参数 processName), 决策模型的选项文字 = 意图名 + 描述 + 前几个常见说法,
-- 这里把新能力写进 REPORT_BATCH_YIELD_RANK 的描述和常见说法 (新说法放最前)。
-- 决策模型选项只取前 3 个常见说法 (decision.py _catalog_line): 前 3 = 损耗异常 / 出成率怎么样 / 出成率为什么掉了;
-- 「出成率怎么样」(V20261117_83 修 NO_RECOGNITION) 必须留在前 3, 酱牛肉例句放回描述。
--
-- 只改描述与常见说法, 不改分类、工具绑定、权限与澄清策略。
-- 事后核对: 库里有该意图时, 必须已写入新描述 (空库 / 迁移测试不报错)。

UPDATE ai_intent_configs
SET description = '只读查询出成率与损耗：(1) 批次出成率排行（良品÷实际产出，哪个批次最低）；'
                  || '(2) 损耗异常：最近哪些批次的哪道工序物料平衡偏差超标，列批次号、产品、工序、标准与实际偏差；'
                  || '(3) 某工序近期出成率、趋势与异常批次数（如熟制杀菌）；(4) 本月对比上月各工序出成率，出成率为什么掉了、掉在哪道工序。'
                  || '如「酱牛肉这个月的出成率怎么样」「哪个批次出成率最低」。不是质检不合格率。',
    keywords = '["损耗异常","出成率怎么样","出成率为什么掉了","最近有哪些损耗异常","掉在哪道工序","工序出成率"]'::jsonb
               || COALESCE((SELECT jsonb_agg(e) FROM jsonb_array_elements(ai_intent_configs.keywords) e
                            WHERE NOT ('["损耗异常","出成率怎么样","出成率为什么掉了","最近有哪些损耗异常","掉在哪道工序","工序出成率"]'::jsonb
                                       @> jsonb_build_array(e))),
                           '[]'::jsonb),
    updated_at = NOW()
WHERE intent_code = 'REPORT_BATCH_YIELD_RANK' AND deleted_at IS NULL
  AND jsonb_typeof(COALESCE(keywords, '[]'::jsonb)) = 'array';

DO $$
DECLARE
    total int;
    done int;
BEGIN
    SELECT count(*) INTO total FROM ai_intent_configs WHERE intent_code = 'REPORT_BATCH_YIELD_RANK' AND deleted_at IS NULL;
    SELECT count(*) INTO done FROM ai_intent_configs
     WHERE intent_code = 'REPORT_BATCH_YIELD_RANK' AND deleted_at IS NULL
       AND description LIKE '%损耗异常%' AND keywords @> '["损耗异常","出成率怎么样"]'::jsonb;
    IF total > 1 OR done <> total THEN
        RAISE EXCEPTION 'REPORT_BATCH_YIELD_RANK rows=% updated=% (expected equal, at most 1)', total, done;
    END IF;
END $$;
