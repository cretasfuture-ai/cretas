-- 2026-10-03 意图识别 90% 方案 第 1 步: SYSTEM_HELP 接上执行器。
--
-- prod 实测: SYSTEM_HELP (平台级, COMMON) 是 active 的, 识别得出来, 但 tool_name 为空 ——
-- 识别对了也执行不了。SystemHelpTool (tool_name = 'system_help') 早就注册着, 只是没绑上。
-- 只更新 tool_name 为空的那一行: 已被人工改绑过的不动。
UPDATE ai_intent_configs
   SET tool_name = 'system_help',
       updated_at = NOW()
 WHERE intent_code = 'SYSTEM_HELP'
   AND factory_id IS NULL
   AND deleted_at IS NULL
   AND (tool_name IS NULL OR tool_name = '');
