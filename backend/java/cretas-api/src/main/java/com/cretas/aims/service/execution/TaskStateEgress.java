package com.cretas.aims.service.execution;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 任务状态摘要外发前的最后一步 (AIIntentServiceImpl 调): 把参数值抽出来与原话一起过 LlmEgressGuard 脱敏,
 * 再按同样顺序填回; 同时<b>只按白名单重建</b>结构 —— 调用方塞进来的白名单外字段在这里被丢掉, 不会外发。
 */
public final class TaskStateEgress {

    private TaskStateEgress() {
    }

    /** 需要脱敏的字符串值, 按 tasks → params 的顺序。「已填」与真假值不需要。 */
    public static List<String> stringValues(Map<String, Object> summary) {
        List<String> values = new ArrayList<>();
        for (Map<?, ?> task : tasks(summary)) {
            if (task.get("params") instanceof Map<?, ?> params) {
                params.values().forEach(v -> {
                    if (v instanceof String s && !TaskStateSummary.FILLED.equals(s)) values.add(s);
                });
            }
        }
        return values;
    }

    /**
     * 白名单重建 + 值换成脱敏后的文本。
     *
     * @param redactedText 脱敏结果第 i 段文本
     * @param offset       {@link #stringValues} 的第一个值在脱敏输入里的下标
     * @return 没有任务时 null (请求体里不带这个字段, 与改动前相同)
     */
    public static Map<String, Object> withRedactedValues(Map<String, Object> summary, IntFunction<String> redactedText,
                                                         int offset) {
        List<Map<?, ?>> tasks = tasks(summary);
        if (tasks.isEmpty()) return null;
        int i = offset;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<?, ?> task : tasks) {
            Map<String, Object> copy = new LinkedHashMap<>();
            for (String key : List.of("level", "intentCode", "params")) {
                if (!TaskStateSummary.TASK_KEYS.contains(key)) continue;
                Object value = task.get(key);
                if ("params".equals(key)) {
                    Map<String, Object> params = new LinkedHashMap<>();
                    if (value instanceof Map<?, ?> raw) {
                        for (Map.Entry<?, ?> e : raw.entrySet()) {
                            Object v = e.getValue();
                            if (v instanceof String s && !TaskStateSummary.FILLED.equals(s)) {
                                v = redactedText.apply(i++);
                            }
                            if (e.getKey() instanceof String k) params.put(k, v);
                        }
                    }
                    copy.put(key, params);
                } else if (value != null) {
                    copy.put(key, value);
                }
            }
            out.add(copy);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tasks", out);
        return result;
    }

    private static List<Map<?, ?>> tasks(Map<String, Object> summary) {
        List<Map<?, ?>> tasks = new ArrayList<>();
        if (summary != null && summary.get("tasks") instanceof List<?> list) {
            for (Object t : list) if (t instanceof Map<?, ?> m) tasks.add(m);
        }
        return tasks;
    }
}
