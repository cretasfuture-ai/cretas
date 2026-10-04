package com.cretas.aims.service.execution;

import com.cretas.aims.entity.config.AIIntentConfig;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 「上一步做了什么」({@code lastAction}) 的唯一契约 (2026-10-04)。
 *
 * <p>服务端在工具执行成功后产出它 ({@link ToolDispatchService} 写进 {@code metadata.lastAction}),
 * App 把它原样带回下一次请求的 {@code context.lastAction}, {@link FollowUpResolver} 读它判断
 * 「好了 / 下一个 / 再加十公斤 / 批了吧」指的是什么。产出与回收读的是<b>同一张白名单</b> ——
 * 两份会漂 (形态 D)。
 *
 * <p>只收「标识类」和「数量类」参数: 不收主体身份 (factoryId/userId/role 由服务端绑定,
 * 见 {@link TrustedExecutionContext}), 不收自由文本 (备注、原话)。App 带回来的是不可信输入,
 * 所以回收时再按同一张表清洗一遍; 它最多只能让系统多问一句「您是要 X 吗」, 不能让任何东西执行。
 */
final class LastActionContract {

    static final String CONTEXT_KEY = "lastAction";
    /** 超过这个年龄的 lastAction 不再用来解读追问 (App 侧也按同一时长过滤)。 */
    static final long MAX_AGE_MS = 10 * 60 * 1000L;
    /** 允许的时钟偏差: {@code at} 比服务端现在还晚这么多以上就当伪造。 */
    static final long MAX_CLOCK_SKEW_MS = 60 * 1000L;
    static final int MAX_LIST_IDS = 20;

    /** 标识类参数 (真实键名取自对应工具的 parameters schema)。 */
    static final Set<String> ID_KEYS = Set.of(
            "taskId",              // work_process_task_start / work_process_task_complete
            "orderId",             // purchase_order_approve
            "purchaseOrderId",
            "requestId",
            "leaveRequestId",      // hr_leave_approve
            "batchId",             // material_batch_use / consume / reserve / release
            "materialBatchId",
            "materialTypeId",
            "productionPlanId",    // material_batch_use
            "productionBatchId",
            "customerId",
            "supplierId");
    /** 数量类参数。 */
    static final Set<String> QUANTITY_KEYS = Set.of("quantity", "actualQuantity");
    static final String UNIT_KEY = "unit";
    /** 列表查询结果里多于一条时的 id 列表键。 */
    static final Set<String> ID_LIST_KEYS = Set.of("orderIds", "leaveRequestIds");

    private static final Pattern ID_VALUE = Pattern.compile("[A-Za-z0-9_.:\\-]{1,64}");
    private static final Pattern UNIT_VALUE = Pattern.compile("[A-Za-z\\u4e00-\\u9fa5]{1,8}");
    private static final Pattern NUMERIC = Pattern.compile("-?[0-9]{1,12}(\\.[0-9]{1,6})?");
    private static final Pattern INTENT_CODE = Pattern.compile("[A-Z][A-Z0-9_]{1,63}");
    private static final Pattern TOOL_NAME = Pattern.compile("[a-z][a-z0-9_]{1,63}");

    /** 列表/查询工具 → 从结果里取哪一列当 id、取到一条/多条时写成哪个键。 */
    private record ListSource(String listKey, String idField, String singleKey, String multiKey,
                              String requiredModuleCode) { }

    private static final Map<String, ListSource> LIST_SOURCES = Map.of(
            // PurchaseOrderListTool.buildPageResult → {content:[{id,...}]}
            "purchase_order_list", new ListSource("content", "id", "orderId", "orderIds", null),
            // LeaveRequestQueryTool.buildSimpleResult → {data:{rows:[{id,...}]}}
            "leave_request_query", new ListSource("rows", "id", "leaveRequestId", "leaveRequestIds", null),
            // ApprovalPendingQueryTool → {data:{items:[{moduleCode,businessEntityId,...}]}};
            // 只有整页都是采购单时才当成采购单列表 (混合模块时「批了」指哪一类说不清)
            "approval_pending_query", new ListSource("items", "businessEntityId", "orderId", "orderIds",
                    "PURCHASE_ORDER"));

    private LastActionContract() {
    }

    /**
     * 工具执行成功之后产出 lastAction。intentCode 不合规时返回 null (不产出)。
     *
     * @param executedParams 工具实际执行时用的参数 (含服务端绑定的主体键, 这里会被白名单滤掉)
     * @param resultData     工具结果 (列表查询从这里取 id)
     */
    static Map<String, Object> build(AIIntentConfig intent, String toolName,
                                     Map<String, ?> executedParams, Object resultData, long nowMs) {
        if (intent == null || intent.getIntentCode() == null
                || !INTENT_CODE.matcher(intent.getIntentCode()).matches()) {
            return null;
        }
        Map<String, Object> params = allowlistedParams(executedParams);
        String safeTool = toolName != null && TOOL_NAME.matcher(toolName).matches() ? toolName : null;
        if (safeTool != null) {
            params.putAll(listedIds(safeTool, resultData));
        }
        Map<String, Object> lastAction = new LinkedHashMap<>();
        lastAction.put("intentCode", intent.getIntentCode());
        if (intent.getIntentName() != null && intent.getIntentName().length() <= 40) {
            lastAction.put("intentName", intent.getIntentName());
        }
        if (safeTool != null) {
            lastAction.put("toolName", safeTool);
        }
        lastAction.put("params", params);
        lastAction.put("at", nowMs);
        return lastAction;
    }

    /**
     * 清洗 App 带回来的 lastAction。不合规 (不是对象 / intentCode 缺失或格式不对 / at 不是数字 /
     * 过期 / 来自未来) 一律返回 empty —— 宁可不解读, 也不按一个可疑的上下文解读。
     */
    static Optional<Sanitized> sanitize(Object raw, long nowMs) {
        if (!(raw instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        if (!(map.get("intentCode") instanceof String code) || !INTENT_CODE.matcher(code).matches()) {
            return Optional.empty();
        }
        Object at = map.get("at");
        if (at != null) {
            if (!(at instanceof Number n)) {
                return Optional.empty();
            }
            long atMs = n.longValue();
            if (nowMs - atMs > MAX_AGE_MS || atMs - nowMs > MAX_CLOCK_SKEW_MS) {
                return Optional.empty();
            }
        }
        String toolName = map.get("toolName") instanceof String t && TOOL_NAME.matcher(t).matches() ? t : null;
        Object rawParams = map.get("params");
        Map<String, Object> params = rawParams instanceof Map<?, ?> p ? allowlistedParams(p) : new LinkedHashMap<>();
        return Optional.of(new Sanitized(code, toolName, params));
    }

    /** 清洗后的 lastAction。params 只含白名单键与安全值。 */
    record Sanitized(String intentCode, String toolName, Map<String, Object> params) {
        boolean is(String code, String tool) {
            return code.equals(intentCode) || (tool != null && tool.equals(toolName));
        }
    }

    /** 白名单过滤: 只留标识 / 数量 / 单位 / id 列表, 值必须是数字或短的安全字符串。 */
    static Map<String, Object> allowlistedParams(Map<?, ?> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<?, ?> e : raw.entrySet()) {
            if (!(e.getKey() instanceof String key)) {
                continue;
            }
            Object safe = safeValue(key, e.getValue());
            if (safe != null) {
                out.put(key, safe);
            }
        }
        return out;
    }

    static Object safeValue(String key, Object value) {
        if (value == null) {
            return null;
        }
        if (ID_KEYS.contains(key)) {
            return safeId(value);
        }
        if (QUANTITY_KEYS.contains(key)) {
            BigDecimal q = toDecimal(value);
            return q == null ? null : normalize(q);
        }
        if (UNIT_KEY.equals(key)) {
            return value instanceof String s && UNIT_VALUE.matcher(s).matches() ? s : null;
        }
        if (ID_LIST_KEYS.contains(key) && value instanceof Collection<?> items) {
            List<Object> ids = new ArrayList<>();
            for (Object item : items) {
                Object id = safeId(item);
                if (id == null) {
                    return null;       // 列表里混进一个不合规的值 → 整列不要
                }
                if (ids.size() < MAX_LIST_IDS) {
                    ids.add(id);
                }
            }
            return ids.isEmpty() ? null : ids;
        }
        return null;
    }

    private static Object safeId(Object value) {
        if (value instanceof Integer || value instanceof Long || value instanceof Short) {
            return ((Number) value).longValue();
        }
        if (value instanceof String s && ID_VALUE.matcher(s).matches()) {
            return s;
        }
        return null;
    }

    static BigDecimal toDecimal(Object value) {
        if (value instanceof Double d && (d.isNaN() || d.isInfinite())) {
            return null;
        }
        if (value instanceof Float f && (f.isNaN() || f.isInfinite())) {
            return null;
        }
        if (value instanceof BigDecimal b) {
            return b;
        }
        if (value instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (value instanceof String s && NUMERIC.matcher(s.strip()).matches()) {
            return new BigDecimal(s.strip());
        }
        return null;
    }

    /** 整数值落成 Long (JSON 里是 30 而不是 30.0), 其余去掉尾零。 */
    static Number normalize(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        if (stripped.scale() <= 0) {
            try {
                return stripped.longValueExact();
            } catch (ArithmeticException tooBig) {
                return stripped;
            }
        }
        return stripped;
    }

    private static Map<String, Object> listedIds(String toolName, Object resultData) {
        ListSource source = LIST_SOURCES.get(toolName);
        Map<String, Object> out = new LinkedHashMap<>();
        if (source == null) {
            return out;
        }
        List<?> rows = findList(resultData, source.listKey());
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        List<Object> ids = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?, ?> r)) {
                return out;
            }
            if (source.requiredModuleCode() != null && !source.requiredModuleCode().equals(r.get("moduleCode"))) {
                return out;
            }
            Object id = safeId(r.get(source.idField()));
            if (id == null) {
                return out;
            }
            if (ids.size() < MAX_LIST_IDS) {
                ids.add(id);
            }
        }
        if (ids.size() == 1 && rows.size() == 1) {
            out.put(source.singleKey(), ids.get(0));
        } else {
            out.put(source.multiKey(), ids);
        }
        return out;
    }

    private static List<?> findList(Object resultData, String listKey) {
        if (!(resultData instanceof Map<?, ?> map)) {
            return null;
        }
        if (map.get(listKey) instanceof List<?> direct) {
            return direct;
        }
        if (map.get("data") instanceof Map<?, ?> nested && nested.get(listKey) instanceof List<?> inner) {
            return inner;
        }
        return null;
    }
}
