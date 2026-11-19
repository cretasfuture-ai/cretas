package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 确认前业务状态核对 —— 两个执行入口共用的唯一一份规则 (Fable 审阅 §8.2 / 建议 5)。
 *
 * <p>老入口 {@code IntentExecutionOrchestrator.confirm} (经 {@code ToolDispatchService.recheckPreviewState})
 * 与新入口 {@link DefaultToolExecutionGateway} 都在「认领凭据之后、执行工具之前」调用 {@link #recheck}:
 * 用凭据里绑定的参数重跑一次<b>只读</b>预览, 拿这次的改前值与铸凭据时记下的改前值逐字段比对。
 * 铸凭据时记什么 ({@link #priorStateSnapshot}) 与确认时重算什么必须是同一条规则, 所以也放在这里。</p>
 *
 * <p>本类不调用工具: 重跑预览由调用方传入的 {@link PreviewReader} 完成, 各入口用自己的可信上下文
 * (直调 ToolExecutor 的位置受 {@code ToolExecutionArchitectureTest} 棘轮约束, 只许收不许增)。</p>
 */
public final class PreviewStateRecheck {

    /** 确认前状态核对没过且重新预览能解决: 什么都没执行, 需要重新预览。 */
    public static final String PREVIEW_STALE = "PREVIEW_STALE";
    /** 这个工具给不出可核对的改前值, 不能在助手里确认: 什么都没执行, 重新预览也没用, 去业务页面。 */
    public static final String CONFIRM_NOT_SUPPORTED = "CONFIRM_NOT_SUPPORTED";

    public static final String NOT_COMPARABLE_MESSAGE =
            "这个操作暂时不能在助手里确认：系统无法核对这条记录在预览之后有没有被别人改过，本次未执行。请到对应的业务页面办理。";

    static final String REREAD_FAILED_MESSAGE =
            "确认前无法重新读取对象当前状态，本次未执行。请重新预览后再确认。";

    private static final Pattern PRIOR_STATE_KEY = Pattern.compile("^current(ly)?[A-Z].*");

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private PreviewStateRecheck() {
    }

    /** 用绑定参数重跑一次只读预览, 返回预览原文 (JSON)。 */
    @FunctionalInterface
    public interface PreviewReader {
        String read(Map<String, Object> boundParameters) throws Exception;
    }

    /** 确认前状态核对的结论。{@code proceed=false} 时本次确认不得执行工具。 */
    public record ConfirmStateCheck(boolean proceed, String code, List<String> changedFields, String message) {
        public static final String STATE_CHANGED = "STATE_CHANGED";
        public static final String PRECONDITION_CHANGED = "PRECONDITION_CHANGED";
        public static final String NOT_COMPARABLE = "NOT_COMPARABLE";
        /** 确认时重读失败 (预览抛异常 / 记录的改前值读不出): 一时的, 重新预览可以。 */
        public static final String REREAD_FAILED = "REREAD_FAILED";

        /** 重新预览能解决的才让用户重新预览; 无法核对的不能 (会原地打转)。 */
        public boolean repreviewHelps() {
            return STATE_CHANGED.equals(code) || PRECONDITION_CHANGED.equals(code)
                    || REREAD_FAILED.equals(code);
        }

        /**
         * 拒绝时对外的状态码 —— 两个入口同一口径: 重新预览能解决 → {@link #PREVIEW_STALE}
         * (前端给「重新生成预览」); 否则 → {@link #CONFIRM_NOT_SUPPORTED} (只给去业务页面, 不能原地打转)。
         */
        public String responseStatus() {
            return repreviewHelps() ? PREVIEW_STALE : CONFIRM_NOT_SUPPORTED;
        }

        public static ConfirmStateCheck ok() {
            return new ConfirmStateCheck(true, "OK", List.of(), null);
        }

        public static ConfirmStateCheck reject(String code, List<String> fields, String message) {
            return new ConfirmStateCheck(false, code, fields == null ? List.of() : List.copyOf(fields), message);
        }
    }

    /**
     * 确认时重读对象当前状态并与铸凭据时记下的改前值比对。
     * <ul>
     *   <li>预览不再通过 (业务前提变了) → 拒绝, 带预览给的原因;</li>
     *   <li>有字段不一致 (预览之后有人改过) → 拒绝, 写明哪些字段变了;</li>
     *   <li>两次都给不出改前值: 只新建记录的工具 ({@link ToolExecutor#previewCreatesNewRecordOnly()}) 放行,
     *       其余一律按「无法核对」拒绝 (fail-closed);</li>
     *   <li>预览本身抛异常 → 拒绝 (什么都没执行, 不是「结果不明」)。</li>
     * </ul>
     * 本方法不执行任何写操作; 比对与执行之间的毫秒级窗口仍由各工具自己的业务校验兜底。
     */
    public static ConfirmStateCheck recheck(ToolExecutor tool,
                                            String storedCurrentValuesJson,
                                            Map<String, Object> claimedParameters,
                                            PreviewReader reader,
                                            ObjectMapper objectMapper) {
        Map<String, Object> stored;
        try {
            stored = storedCurrentValuesJson == null || storedCurrentValuesJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(storedCurrentValuesJson, MAP_TYPE);
        } catch (Exception e) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.REREAD_FAILED, List.of(),
                    "预览时记录的改前值无法读取，本次未执行。请重新预览后再确认。");
        }
        if (stored == null) {
            stored = Map.of();
        }
        if (!tool.supportsPreview()) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.NOT_COMPARABLE, List.of(),
                    NOT_COMPARABLE_MESSAGE);
        }

        Map<String, Object> previewData;
        try {
            Map<String, Object> params = new HashMap<>(
                    claimedParameters != null ? claimedParameters : Map.of());
            String resultJson = reader.read(params);
            previewData = objectMapper.readValue(resultJson, MAP_TYPE);
        } catch (Exception e) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.REREAD_FAILED, List.of(), REREAD_FAILED_MESSAGE);
        }
        if (previewData == null) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.REREAD_FAILED, List.of(), REREAD_FAILED_MESSAGE);
        }

        String blocked = blockedReason(previewData);
        if (blocked != null) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.PRECONDITION_CHANGED, List.of(),
                    "预览之后情况有变化，现在不能执行：" + blocked + " 本次未执行。");
        }

        Map<String, Object> fresh = priorStateSnapshot(tool, previewData);
        if (stored.isEmpty() && fresh.isEmpty()) {
            if (tool.previewCreatesNewRecordOnly()) {
                return ConfirmStateCheck.ok();
            }
            // ⛔ 不回「请重新预览」: 重新预览还是给不出改前值, 用户会在同一张卡上死循环
            return ConfirmStateCheck.reject(ConfirmStateCheck.NOT_COMPARABLE, List.of(),
                    NOT_COMPARABLE_MESSAGE);
        }

        List<String> changed = changedFields(stored, fresh, objectMapper);
        if (!changed.isEmpty()) {
            String fields = changed.stream().map(f -> "「" + f + "」").collect(Collectors.joining(""));
            return ConfirmStateCheck.reject(ConfirmStateCheck.STATE_CHANGED, changed,
                    "预览之后" + fields + "已被改动（与预览时的改前值不一致），本次未执行。请重新预览后再确认。");
        }
        return ConfirmStateCheck.ok();
    }

    /**
     * 预览给出的改前值快照, 铸凭据时存进凭据、确认时重算后比对, 两处必须用同一条规则。
     * <ol>
     *   <li>预览的 {@code currentValues} (或 currentValue / current) —— 工具明确给出的改前值;</li>
     *   <li>没有时, 改动已有记录的工具取预览里以 {@code current} 开头的字段 (currentStatus、currentEnabled、
     *       currentQuantity…): 状态流转类工具普遍这样报改前状态, 只是没包成 currentValues;</li>
     *   <li>都没有 → 空 (新建型放行, 其余无法核对)。</li>
     * </ol>
     * 新建型工具不取第 2 类: 那类字段在新建工具里多半是旁证 (如「当前已有 N 条」), 不是被改对象的状态。
     * 返回值不为 null; 只进凭据, 不改确认卡上展示的改前/改后。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> priorStateSnapshot(ToolExecutor tool, Map<String, Object> previewData) {
        Map<String, Object> explicit = previewSection(previewData, "currentValues", "currentValue", "current");
        if (explicit != null) {
            return explicit;
        }
        if (previewData == null || tool.previewCreatesNewRecordOnly()) {
            return Map.of();
        }
        Map<String, Object> data = previewData.get("data") instanceof Map<?, ?> nested
                ? (Map<String, Object>) nested : previewData;
        Map<String, Object> snapshot = new LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (key != null && PRIOR_STATE_KEY.matcher(key).matches()) {
                snapshot.put(key, value);
            }
        });
        return snapshot;
    }

    /**
     * 写操作影响契约 (2026-07-24): 从预览结果里按既有约定字段提取改前/改后小节。
     * 兼容三种形态: 顶层 map / data 嵌套 / 标量值 (包成 {"值": v})。任一 key 命中即返回; 全 miss 返回 null。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> previewSection(Map<String, Object> previewData, String... keys) {
        if (previewData == null) {
            return null;
        }
        Map<String, Object> data = previewData;
        Object nested = previewData.get("data");
        if (nested instanceof Map) {
            data = (Map<String, Object>) nested;
        }
        for (String key : keys) {
            Object v = data.get(key);
            if (v == null) {
                v = previewData.get(key);
            }
            if (v instanceof Map) {
                return (Map<String, Object>) v;
            }
            if (v != null) {
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put("值", v);
                return wrapped;
            }
        }
        return null;
    }

    /**
     * 预览自己说「不行」时的原因; 预览通过 (或判不了) 返回 {@code null}。
     * <ul>
     *   <li>{@code {success:false, error|message}} —— 工具预览失败 / 缺参数</li>
     *   <li>{@code {success:true, data:{canDo:false, message}}} —— 业务边界 (超收 / 状态不对 …)</li>
     * </ul>
     */
    @SuppressWarnings("unchecked")
    public static String blockedReason(Map<String, Object> previewData) {
        if (previewData == null) {
            return null;
        }
        if (Boolean.FALSE.equals(previewData.get("success"))) {
            String reason = firstText(previewData.get("message"), previewData.get("error"));
            return reason != null ? reason : "预览没有通过，未执行任何操作。";
        }
        Object nested = previewData.get("data");
        Map<String, Object> data = nested instanceof Map ? (Map<String, Object>) nested : previewData;
        if (Boolean.FALSE.equals(data.get("canDo"))) {
            String reason = firstText(data.get("message"));
            return reason != null ? reason : "当前条件下不能执行这个操作，未执行任何操作。";
        }
        return null;
    }

    private static String firstText(Object... candidates) {
        for (Object c : candidates) {
            if (c instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static List<String> changedFields(Map<String, Object> stored, Map<String, Object> fresh,
                                              ObjectMapper objectMapper) {
        LinkedHashSet<String> keys = new LinkedHashSet<>(stored.keySet());
        keys.addAll(fresh.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!stored.containsKey(key) || !fresh.containsKey(key)) {
                changed.add(key);
                continue;
            }
            JsonNode left = objectMapper.valueToTree(stored.get(key));
            JsonNode right = objectMapper.valueToTree(fresh.get(key));
            if (!sameValue(left, right)) {
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * 同一个值: 数字按数值比; 元素全是标量的数组按多重集合比 —— 「当前已订阅的设备」这类列表来自没有
     * ORDER BY 的查询, 顺序不是状态, 按顺序比会把没人动过的记录误判成「被改过」。元素是对象的数组仍按顺序比
     * (FIFO 推荐批次之类, 顺序就是内容)。
     */
    private static boolean sameValue(JsonNode left, JsonNode right) {
        if (left.isArray() && right.isArray() && left.size() == right.size()
                && allScalars(left) && allScalars(right)) {
            return sortedScalars(left).equals(sortedScalars(right));
        }
        return left.equals(NUMERIC_AWARE, right);
    }

    private static boolean allScalars(JsonNode array) {
        for (JsonNode item : array) {
            if (item.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> sortedScalars(JsonNode array) {
        List<String> values = new ArrayList<>();
        for (JsonNode item : array) {
            values.add(item.isNumber() ? item.decimalValue().stripTrailingZeros().toPlainString() : item.toString());
        }
        Collections.sort(values);
        return values;
    }

    /** 12.5 与 12.50、5 与 5.0 算同一个值: 序列化往返会改数字的表示, 不能让它被读成「改过」。 */
    private static final Comparator<JsonNode> NUMERIC_AWARE = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };
}
