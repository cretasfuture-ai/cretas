package com.cretas.aims.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 口径规则 8 (Steve 2026-11-12, INTENT-DISTILL-20261111): 只读查询里名字命中多个人/物时,
 * ⛔ 不先问、不只取第一个, 全部列出来并标出区分字段 (车间/部门、工号、规格…); 超过
 * {@link #LIST_LIMIT} 个时列前 {@value #LIST_LIMIT} 个, 说共几个、请说得更具体。
 *
 * <p>工具把匹配情况写进结果顶层的 {@link #KEY} (与 StatusFilter/TimeRangeFilter 的标记同一层),
 * {@link #reply} 由结果格式化在两个入口共用 —— 不靠各意图的模板, 否则同名列表会被
 * 「查询成功」之类的模板吞掉。
 *
 * <p>⚠️ 只给只读工具用。写工具遇到同名仍须让用户选, 不能自动多选。
 */
public final class SameNameMatches {

    public static final String KEY = "_sameName";
    public static final int LIST_LIMIT = 5;

    private SameNameMatches() {
    }

    /** 一个命中: 显示名、区分字段 (已拼好, 如「部门：加工车间，工号 012」)、该对象的查询摘要。 */
    public record Match(Object id, String name, String distinguishing, String summary) {
        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("distinguishing", distinguishing);
            m.put("summary", summary);
            return m;
        }
    }

    /**
     * 把匹配情况记到工具结果上。
     *
     * @param kind     「员工」「物料」…, 用于回复措辞
     * @param name     用户说的名字
     * @param total    命中总数 (不是列出来的数)
     * @param listed   实际列出的 (≤ {@link #LIST_LIMIT})
     */
    public static Map<String, Object> annotate(Map<String, Object> result, String kind, String name,
                                               int total, List<Match> listed) {
        Map<String, Object> marker = new LinkedHashMap<>();
        marker.put("kind", kind);
        marker.put("name", name);
        marker.put("total", total);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Match m : listed) {
            rows.add(m.toMap());
        }
        marker.put("listed", rows);
        result.put(KEY, marker);
        return result;
    }

    /** 回复文本: 0 个或 ≥2 个命中时给出; 恰好 1 个 (或没有标记) 时 null, 走原来的格式化。 */
    public static String reply(Object resultData) {
        resultData = TimeRangeFilter.toolResultOf(resultData);
        if (!(resultData instanceof Map<?, ?> data) || !(data.get(KEY) instanceof Map<?, ?> marker)) {
            return null;
        }
        int total = marker.get("total") instanceof Number n ? n.intValue() : -1;
        String kind = String.valueOf(marker.get("kind"));
        String name = String.valueOf(marker.get("name"));
        if (total == 0) {
            return "本厂没有找到叫「" + name + "」的" + kind + "，请核对名称，或改用编号/工号。";
        }
        if (total < 2) {
            return null;
        }
        List<?> listed = marker.get("listed") instanceof List<?> l ? l : List.of();
        // 人: 「2 位叫「张三」的员工」; 物/单位按名称搜索 (可能是包含匹配): 「2 个名称匹配「华联」的客户」
        boolean person = kind.contains("员工");
        String unit = person ? "位" : "个";
        StringBuilder sb = new StringBuilder();
        sb.append("本厂有 ").append(total).append(" ").append(unit)
                .append(person ? "叫「" : "名称匹配「").append(name).append("」的").append(kind);
        if (total > listed.size()) {
            sb.append("，这里列出前 ").append(listed.size()).append(" ").append(unit)
                    .append("；想看其他的请说得更具体一些（").append(person ? "比如车间或工号" : "比如完整名称或编码").append("）");
        } else {
            sb.append("，都列出来了");
        }
        sb.append("：");
        int i = 1;
        for (Object o : listed) {
            Map<?, ?> row = (Map<?, ?>) o;
            sb.append("\n").append(i++).append(". ").append(row.get("name"));
            Object dist = row.get("distinguishing");
            if (dist != null && !String.valueOf(dist).isBlank()) {
                sb.append("（").append(dist).append("）");
            }
            Object summary = row.get("summary");
            if (summary != null && !String.valueOf(summary).isBlank()) {
                sb.append("：").append(summary);
            }
        }
        return sb.toString();
    }
}
