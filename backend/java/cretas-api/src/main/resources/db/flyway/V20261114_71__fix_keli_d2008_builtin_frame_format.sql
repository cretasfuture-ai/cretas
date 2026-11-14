-- 柯力 D2008 内置协议配置修正 (DAG 缺口 21 后续, #1705 现状文档 G11)
--
-- 背景: 内置柯力协议 (id=proto-keli-d2008-ascii) 的 frame_format 里
--   * stable 字段 start 写成 10 (指到空格; 样例帧 "+001240 kg S\r\n" 的 S 在下标 11)
--   * frameLength 写成 12 (样例帧实际 14 字节, 含 \r\n)
-- 加严解析后这一行读不了自己的样例帧 (稳定标志不认识 -> 整帧拒绝)。仓库里的两份历史种子 SQL 已在 #1705 改好,
-- 但它们不在 flyway locations 里 (只扫 db/flyway), 不会改线上库, 所以这里补一个迁移。
--
-- 2026-11-14 只读核对 47 cretas_prod_db: scale_protocol_configs.frame_format 是 jsonb; 柯力只有 1 行 (factory_id NULL,
-- frameLength=12, stable.start=10); factory_equipment / iot_devices 都没引用它; 3 条 scale_brand_models 默认指向它;
-- 5 条 scale_protocol_test_cases 引用它 (样例帧与期望值与新配置一致, 见 PR 描述, 本迁移不改用例)。
--
-- 只改这一行, 按 id 精确定位, 且带旧值条件 (frameLength=12 且 stable.start=10), 所以:
--   * 幂等: 改过之后再跑是 NOTICE 跳过, 不会重复改;
--   * 不误伤: 这一行被人手工改成别的值 (旧值条件不成立) 时只 NOTICE, 不覆盖。
-- 三态显式写出来, 不做静默 no-op:
--   表不存在 / 行不存在 / 列不是 jsonb -> RAISE NOTICE 说明跳过原因 (合法: 全新库、测试库没有这张表或没种过)
--   行已经是目标值                     -> RAISE NOTICE 已修正
--   行是旧值                           -> UPDATE, 然后读回校验, 校验不过 RAISE EXCEPTION 让迁移红着停下
--   行是其它值                         -> RAISE NOTICE 不覆盖 (人工改过)

DO $$
DECLARE
    v_col_type   text;
    v_format     jsonb;
    v_idx        int;
    v_len        text;
    v_start      text;
    v_updated    int;
BEGIN
    IF to_regclass('public.scale_protocol_configs') IS NULL THEN
        RAISE NOTICE 'scale_protocol_configs 不存在, 跳过柯力 frame_format 修正';
        RETURN;
    END IF;

    SELECT data_type INTO v_col_type
      FROM information_schema.columns
     WHERE table_schema = 'public' AND table_name = 'scale_protocol_configs' AND column_name = 'frame_format';
    IF v_col_type IS DISTINCT FROM 'jsonb' THEN
        RAISE NOTICE 'scale_protocol_configs.frame_format 类型是 %, 不是 jsonb, 跳过柯力 frame_format 修正', v_col_type;
        RETURN;
    END IF;

    SELECT frame_format INTO v_format
      FROM scale_protocol_configs
     WHERE id = 'proto-keli-d2008-ascii' AND protocol_code = 'KELI_D2008_ASCII';
    IF NOT FOUND THEN
        RAISE NOTICE '没有 id=proto-keli-d2008-ascii 的行, 跳过柯力 frame_format 修正';
        RETURN;
    END IF;

    SELECT (ord - 1)::int INTO v_idx
      FROM jsonb_array_elements(v_format -> 'fields') WITH ORDINALITY AS t(el, ord)
     WHERE el ->> 'name' = 'stable';
    v_len   := v_format ->> 'frameLength';
    v_start := CASE WHEN v_idx IS NULL THEN NULL ELSE v_format -> 'fields' -> v_idx ->> 'start' END;

    IF v_len = '14' AND v_start = '11' THEN
        RAISE NOTICE '柯力 frame_format 已是 frameLength=14 / stable.start=11, 无需修正';
        RETURN;
    END IF;

    IF v_len IS DISTINCT FROM '12' OR v_start IS DISTINCT FROM '10' THEN
        RAISE NOTICE '柯力 frame_format 不是预期的旧值 (frameLength=%, stable.start=%), 视为人工改过, 不覆盖', v_len, v_start;
        RETURN;
    END IF;

    UPDATE scale_protocol_configs
       SET frame_format = jsonb_set(
               jsonb_set(frame_format, '{frameLength}', '14'::jsonb),
               ARRAY['fields', v_idx::text, 'start'], '11'::jsonb)
     WHERE id = 'proto-keli-d2008-ascii'
       AND protocol_code = 'KELI_D2008_ASCII'
       AND frame_format ->> 'frameLength' = '12'
       AND frame_format -> 'fields' -> v_idx ->> 'name' = 'stable'
       AND frame_format -> 'fields' -> v_idx ->> 'start' = '10';
    GET DIAGNOSTICS v_updated = ROW_COUNT;
    IF v_updated <> 1 THEN
        RAISE EXCEPTION '柯力 frame_format 修正应恰好更新 1 行, 实际 % 行', v_updated;
    END IF;

    SELECT frame_format INTO v_format FROM scale_protocol_configs WHERE id = 'proto-keli-d2008-ascii';
    IF v_format ->> 'frameLength' <> '14' OR v_format -> 'fields' -> v_idx ->> 'start' <> '11' THEN
        RAISE EXCEPTION '柯力 frame_format 修正后读回不对: %', v_format;
    END IF;
    RAISE NOTICE '柯力 frame_format 已修正: frameLength 12->14, stable.start 10->11';
END
$$;
