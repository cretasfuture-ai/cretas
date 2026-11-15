package com.cretas.aims.ai.tool;

import com.cretas.aims.service.entity.FactoryEntityResolver;
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
    /** 全名零命中时「你是不是要找」的候选 (在 {@link #KEY} 标记里): 只列出让人选, ⛔ 不自动选, 哪怕只有一个。 */
    static final String SUGGESTED = "suggested";
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

    /**
     * 同名列表里要列的那几个 (前 {@link #LIST_LIMIT} 个)。命中本身只来自 {@link FactoryEntityResolver}
     * (本厂主数据规范化后精确匹配) —— 工具 ⛔ 不自己按名字搜。
     */
    public static List<FactoryEntityResolver.Candidate> listedCandidates(FactoryEntityResolver.Resolution resolution) {
        List<FactoryEntityResolver.Candidate> all = resolution.matches();
        return all.subList(0, Math.min(LIST_LIMIT, all.size()));
    }

    /** 解析出的一个对象 -> 同名列表的一行 (区分字段用解析器拼好的那份)。 */
    public static Match matchOf(FactoryEntityResolver.Candidate candidate, String summary) {
        return new Match(candidate.id(), candidate.name(), candidate.distinguishing(), summary);
    }

    /** 按解析结果记标记: 总数 = 本厂全部命中数, 名字 = 用户说的。 */
    public static Map<String, Object> annotate(Map<String, Object> result, String kind,
                                               FactoryEntityResolver.Resolution resolution, List<Match> listed) {
        return annotate(result, kind, resolution.query(), resolution.matches().size(), listed);
    }

    /**
     * 全名零命中、但有候选 (称呼核心 / 包含): 记 total=0 + 候选 (前 {@link #LIST_LIMIT} 个, 带区分字段)。
     * 没有候选时返回 false, 结果不动 (工具走它原来的「没找到」)。
     */
    public static boolean annotateSuggestions(Map<String, Object> result, String kind,
                                              FactoryEntityResolver.Resolution resolution) {
        if (!resolution.matches().isEmpty() || resolution.suggestions().isEmpty()) {
            return false;
        }
        annotate(result, kind, resolution.query(), 0, List.of());
        @SuppressWarnings("unchecked")
        Map<String, Object> marker = (Map<String, Object>) result.get(KEY);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<FactoryEntityResolver.Candidate> all = resolution.suggestions();
        for (FactoryEntityResolver.Candidate c : all.subList(0, Math.min(LIST_LIMIT, all.size()))) {
            rows.add(matchOf(c, null).toMap());
        }
        marker.put(SUGGESTED, rows);
        marker.put("suggestedTotal", all.size());
        marker.put("suggestionBasis", resolution.basis().name());
        return true;
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
        if (total == 0 && marker.get(SUGGESTED) instanceof List<?> suggested && !suggested.isEmpty()) {
            int all = marker.get("suggestedTotal") instanceof Number n ? n.intValue() : suggested.size();
            StringBuilder sb = new StringBuilder();
            sb.append("本厂没有名称正好是「").append(name).append("」的").append(kind).append("。你是不是要找");
            sb.append(all > suggested.size() ? "（共 " + all + " 个，列出前 " + suggested.size() + " 个）" : "");
            sb.append("：");
            appendRows(sb, suggested);
            sb.append("\n没有替你选，请说完整名称或编码。");
            return sb.toString();
        }
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
        appendRows(sb, listed);
        return sb.toString();
    }

    private static void appendRows(StringBuilder sb, List<?> listed) {
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
    }
}
