package com.cretas.aims.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户在对话里说的时间 (「上个月」「昨天」) 有没有真的用在查询上 —— 用了要说按什么时间查的, 没用要说出来。
 *
 * <p>S6 验收 2026-11-03: 「查询上个月的销售订单」返回全部 538 条, 「查询昨天的质检记录」返回全部 6 条 ——
 * 预处理把时间归一成 startDate/endDate 放进了参数, 可 order_list / quality_check_query 根本不读日期,
 * shipment_query 读的是 dateFrom/dateTo。时间被静默丢掉, 回复看起来像按时间查过。
 *
 * <p>机制 (与 {@link StatusFilter} 同一处调用):
 * <ul>
 *   <li>{@code ToolDispatchService} 把句子里的时间放进 startDate/endDate 时, 同时打上 {@link #SAID} 标记;</li>
 *   <li>工具在 schema 里声明 startDate/endDate 属性 = 它按这两个参数过滤 (声明了就必须真的过滤);</li>
 *   <li>执行后: 声明了 → 结果记 {@link #APPLIED}; 没声明 → 结果记 {@link #NOT_APPLIED},
 *       回复开头明说「不能按时间筛选, 以下是全部时间的结果」, ⛔ 不静默。</li>
 * </ul>
 */
public final class TimeRangeFilter {

    /** 参数标记: 这次的 startDate/endDate 来自用户原话 (值为 "from~to")。 */
    public static final String SAID = "timeRangeFromSentence";
    /** 结果标记: 按这个时间范围查的 {from, to}。 */
    public static final String APPLIED = "appliedTimeRange";
    /** 结果标记: 用户说了时间, 但这个工具不能按时间筛 {from, to}。 */
    public static final String NOT_APPLIED = "timeRangeNotApplied";
    /**
     * schema 顶层: {@code "none"} = 声明了 startDate/endDate 但并不按它们过滤 (如库存报表是当前时点快照)。
     * 这样的工具收到句子里的时间时, 时间参数被拿掉, 回复说明不能按时间查 —— ⛔ 不把快照标成「上个月」。
     */
    public static final String MODE = "x-time-range";
    /** schema 顶层: 不能按时间查时的说明 (替换通用措辞), 如「库存报表是当前时点的库存, 不能按过去的时间查」。 */
    public static final String NOTICE = "x-time-range-notice";
    /**
     * schema 顶层: 对话里没说时间时默认查最近 N 天 (Steve 2026-11-12 口径 1: 「看看待发货的订单」= 最近 7 天,
     * 回复写明)。只给列表型、真按 startDate/endDate 过滤的工具声明; 由 ToolDispatchService 的只读参数合并补上。
     */
    public static final String DEFAULT_RECENT_DAYS = "x-default-recent-days";

    private TimeRangeFilter() {
    }

    /** 工具声明的默认最近天数; 没声明、或工具不按时间过滤时为 null。 */
    public static Integer defaultRecentDays(Map<String, Object> parametersSchema) {
        if (!supported(parametersSchema) || !(parametersSchema.get(DEFAULT_RECENT_DAYS) instanceof Number n)
                || n.intValue() < 1) {
            return null;
        }
        return n.intValue();
    }

    /** schema 里的 startDate / endDate 属性 (yyyy-MM-dd, 含当天)。声明它们 = 工具真的按它们过滤。 */
    public static Map<String, Object> dateProperty(String description) {
        return Map.of("type", "string", "format", "date", "description", description);
    }

    /**
     * 解析日期参数; 空 → null。⛔ 认不出不静默当「不限时间」(那会把按时间问的问题答成全部): 400。
     */
    public static java.time.LocalDate parseDate(Object raw, String name) {
        if (raw == null || String.valueOf(raw).isBlank()) {
            return null;
        }
        String s = String.valueOf(raw).trim();
        try {
            return java.time.LocalDate.parse(s.length() > 10 ? s.substring(0, 10) : s);
        } catch (java.time.format.DateTimeParseException e) {
            throw new com.cretas.aims.exception.BusinessException(400, "无法识别的日期 " + name + ": " + s)
                    .withHint("日期请用 YYYY-MM-DD, 例如 2026-11-03");
        }
    }

    /** 工具 schema 里同时声明了 startDate 与 endDate 属性 = 它支持按时间过滤。 */
    public static boolean supported(Map<String, Object> parametersSchema) {
        if (parametersSchema == null || "none".equals(parametersSchema.get(MODE))) {
            return false;
        }
        return parametersSchema.get("properties") instanceof Map<?, ?> props
                && props.containsKey("startDate") && props.containsKey("endDate");
    }

    /**
     * 执行前调用: 句子里的时间遇到不按时间过滤的工具, 把这对时间参数拿掉 (只拿掉来自句子的那一对,
     * 显式传入的不动), 免得工具把它当成别的意思用 (库存报表会把 endDate 当「报表日期」标在当前快照上)。
     */
    public static void prepare(Map<String, Object> parametersSchema, Map<String, Object> params) {
        if (params == null || !(params.get(SAID) instanceof String said) || supported(parametersSchema)) {
            return;
        }
        String[] range = said.split("~", 2);
        if (range.length == 2 && range[0].equals(String.valueOf(params.get("startDate")))
                && range[1].equals(String.valueOf(params.get("endDate")))) {
            params.remove("startDate");
            params.remove("endDate");
        }
    }

    /** 执行后调用: 句子里说了时间时, 在结果上记下用没用上。返回带标记的副本。 */
    public static Map<String, Object> annotate(Map<String, Object> parametersSchema, Map<String, Object> params,
                                               Map<String, Object> result) {
        if (params == null || result == null || !(params.get(SAID) instanceof String said) || !said.contains("~")) {
            return result;
        }
        String[] range = said.split("~", 2);
        Map<String, Object> out = new LinkedHashMap<>(result);
        if (supported(parametersSchema)) {
            out.put(APPLIED, Map.of("from", range[0], "to", range[1]));
        } else {
            Map<String, Object> notApplied = new LinkedHashMap<>(Map.of("from", range[0], "to", range[1]));
            if (parametersSchema != null && parametersSchema.get(NOTICE) instanceof String custom) {
                notApplied.put("notice", custom);
            }
            out.put(NOT_APPLIED, notApplied);
        }
        return out;
    }

    /**
     * 工具结果本身: 走 Skill 的意图把单个工具的结果包成 {toolName: result, _toolCount: 1, ...},
     * 标记在里面一层 —— 取出那一层, 别的形状原样返回。
     */
    public static Object toolResultOf(Object resultData) {
        if (resultData instanceof Map<?, ?> data && data.get("_toolCount") instanceof Number n && n.intValue() == 1) {
            for (Map.Entry<?, ?> e : data.entrySet()) {
                if (!String.valueOf(e.getKey()).startsWith("_") && e.getValue() instanceof Map<?, ?> inner) {
                    return inner;
                }
            }
        }
        return resultData;
    }

    /** 回复前缀: 说了时间但没用上时必须说出来; 其余情况为 null。 */
    public static String notAppliedNotice(Object resultData) {
        resultData = toolResultOf(resultData);
        if (resultData instanceof Map<?, ?> data && data.get(NOT_APPLIED) instanceof Map<?, ?> r) {
            if (r.get("notice") instanceof String custom) {
                return "注意：" + custom + "（你说的是 " + r.get("from") + " 至 " + r.get("to") + "）。";
            }
            return "注意：这个查询不能按时间筛选（你说的是 " + r.get("from") + " 至 " + r.get("to")
                    + "），下面是不限时间的结果。";
        }
        return null;
    }

    /** 按时间筛过时的条件描述, 供「0 条」回复点名; 没筛时为 null。 */
    public static String appliedLabel(Object resultData) {
        resultData = toolResultOf(resultData);
        if (resultData instanceof Map<?, ?> data && data.get(APPLIED) instanceof Map<?, ?> r) {
            return r.get("from") + " 至 " + r.get("to");
        }
        return null;
    }
}
