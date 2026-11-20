package com.cretas.aims.service.execution;

import com.cretas.aims.entity.conversation.ConversationSession;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 给识别层 (Python 决策请求体 {@code taskState}) 的任务状态摘要。<b>字段白名单写死</b>:
 *
 * <pre>
 * { "tasks": [ { "level": 0, "intentCode": "PROCESSING_BATCH_COMPLETE", "params": { "batchId": "B-1827" } },
 *              { "level": 1, ... } ] }
 * </pre>
 * level 0 = 正在做的 (正在问参数的任务, 没有时是最近一次只读查询, 含分页位置 page/size);
 * level ≥ 1 = 被打断挂起的, 越大越早。
 *
 * <h3>不外发的东西</h3>
 * 助手正文、用户原话、参数标签与提示语、参数来源、会话 id、工厂 / 用户 id 一律不进摘要。
 * 参数值只发「编号 / 数字 / 真假」三种 (人名、客户名、备注这类中文自由文本一律写成「已填」);
 * 工厂开了工艺保密 (a) 时数字也写成「已填」(配比、温度、出成率都是数字)。剩下的值在外发前
 * 与用户原话一起过同一道 LlmEgressGuard 脱敏 (AIIntentServiceImpl), 出外脱敏 (c) 开关照常生效。
 * 摘要只来自本人本厂的会话行 (查询带 factory_id + user_id), 不含跨租户信息。
 */
public final class TaskStateSummary {

    /** 顶层字段白名单。改这里必须同时改测试与 Python {@code TASK_STATE_*_KEYS}。 */
    public static final Set<String> TOP_KEYS = Set.of("tasks");
    /** 每层任务的字段白名单。 */
    public static final Set<String> TASK_KEYS = Set.of("level", "intentCode", "params");

    /** 每层最多带几个参数 / 栈最多几层 (含当前)。 */
    static final int MAX_PARAMS = 8;
    static final int MAX_TASKS = 4;
    static final String FILLED = "已填";

    private static final Pattern PARAM_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,39}");
    private static final Pattern INTENT_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,79}");
    private static final Pattern NUMERIC = Pattern.compile("-?[0-9]{1,12}(?:\\.[0-9]{1,6})?");

    private TaskStateSummary() {
    }

    /** level 0 的来源: 正在问参数的任务, 或最近一次只读查询。 */
    record Current(String intentCode, Map<String, ?> params) {
    }

    /**
     * @param current         正在做的; null = 没有
     * @param suspended       被挂起的, 栈顶在前
     * @param withholdNumbers 工艺保密开着: 数字也不发
     * @return 空 map = 没有任务状态 (调用方照旧不带这个字段)
     */
    static Map<String, Object> build(Current current, List<ConversationSession> suspended, boolean withholdNumbers) {
        List<Map<String, Object>> tasks = new ArrayList<>();
        if (current != null) {
            addTask(tasks, 0, current.intentCode(), current.params(), withholdNumbers);
        }
        int level = 1;
        for (ConversationSession s : suspended == null ? List.<ConversationSession>of() : suspended) {
            if (tasks.size() >= MAX_TASKS) break;
            if (addTask(tasks, level, s.getKnownIntentCode(), s.getCollectedParameters(), withholdNumbers)) level++;
        }
        if (tasks.isEmpty()) return Map.of();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tasks", tasks);
        return summary;
    }

    private static boolean addTask(List<Map<String, Object>> tasks, int level, String intentCode,
                                   Map<String, ?> params, boolean withholdNumbers) {
        if (intentCode == null || !INTENT_CODE.matcher(intentCode).matches()) return false;
        Map<String, Object> safe = new LinkedHashMap<>();
        if (params != null) {
            for (Map.Entry<String, ?> e : params.entrySet()) {
                if (safe.size() >= MAX_PARAMS) break;
                if (e.getKey() == null || !PARAM_KEY.matcher(e.getKey()).matches() || e.getValue() == null) continue;
                safe.put(e.getKey(), safeValue(e.getValue(), withholdNumbers));
            }
        }
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("level", level);
        task.put("intentCode", intentCode);
        task.put("params", safe);
        tasks.add(task);
        return true;
    }

    /** 编号原样 (还要过外发脱敏), 数字 / 真假原样 (工艺保密时不发), 其余一律「已填」。 */
    static Object safeValue(Object value, boolean withholdNumbers) {
        if (value instanceof Boolean b) return b;
        if (value instanceof Number n) {
            if (withholdNumbers) return FILLED;
            try {
                return new BigDecimal(n.toString()).stripTrailingZeros().toPlainString();
            } catch (NumberFormatException e) {
                return FILLED;
            }
        }
        String text = String.valueOf(value).strip();
        if (text.isEmpty()) return FILLED;
        if (NUMERIC.matcher(text).matches()) return withholdNumbers ? FILLED : text;
        return SlotAnswerRule.codeToken(text).isPresent() ? text : FILLED;
    }
}
