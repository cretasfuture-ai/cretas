package com.cretas.aims.service.foodsafety.timelimit;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 工序间 / 投料时限配置 —— 食品 DAG 缺口 14 (Steve 2026-11-04: 「都有可能, 需要专门配置」)。
 *
 * <p>工艺图的边不带配置, 时限挂在<b>下游工序的 INPUT 端口</b>上 ({@code data.ports[].timeLimit}):
 * 原料端口 = 投料时限, 半成品端口 = 工序间时限。随工艺版本冻结到 {@code workflow_task_ports.time_limit_config}。
 * 端口没有这份配置 = 不限时。
 *
 * @param maxMinutes          时限
 * @param warnMinutes         剩余不足多少分钟算临近; null = 不提示
 * @param startEvent          起算点, 见 {@link StartEvent}
 * @param pauseCodes          哪些暂停算数; 空 = 不许暂停
 * @param maxPauseMinutes     累计最多暂停多久; null = 不封顶
 * @param mixRule             混批规则, 见 {@link MixRule}
 * @param splitRule           拆批规则, 见 {@link SplitRule}
 * @param reworkRule          回料规则, 见 {@link ReworkRule}
 * @param reworkMaxMinutes    回料单独的时限; null = 同 maxMinutes
 * @param recheckValidMinutes 复检放行后多久内有效; null = 放行后不再限时
 */
public record ProcessTimeLimitConfig(
        int maxMinutes,
        Integer warnMinutes,
        String startEvent,
        List<String> pauseCodes,
        Integer maxPauseMinutes,
        String mixRule,
        String splitRule,
        String reworkRule,
        Integer reworkMaxMinutes,
        Integer recheckValidMinutes) {

    /** 起算点。都是系统里真实存在的时刻, 解冻除外 (要人登记, 没登记就拦, 不猜)。 */
    public static final class StartEvent {
        public static final String OUTPUT_POSTED = "OUTPUT_POSTED";
        public static final String OUTPUT_REPORTED = "OUTPUT_REPORTED";
        public static final String UPSTREAM_COMPLETED = "UPSTREAM_COMPLETED";
        public static final String RAW_RECEIVED = "RAW_RECEIVED";
        public static final String RAW_OUTBOUND = "RAW_OUTBOUND";
        public static final String THAW_MARKED = "THAW_MARKED";

        private StartEvent() {
        }
    }

    public static final class MixRule {
        /** 一行混了多笔时整行按最早一笔算, 超时了整行不能领。 */
        public static final String EARLIEST = "EARLIEST";
        /** 按笔分别算, 只有超时的那部分不能领。 */
        public static final String PER_LOT = "PER_LOT";

        private MixRule() {
        }
    }

    public static final class SplitRule {
        /** 调拨拆出的子批继承母批的起算点。 */
        public static final String INHERIT = "INHERIT";
        /** 子批从拆出时刻重新起算 (只有明确配置才这样)。 */
        public static final String RESTART_AT_SPLIT = "RESTART_AT_SPLIT";

        private SplitRule() {
        }
    }

    public static final class ReworkRule {
        /** 回料继承原批次开工时刻。 */
        public static final String INHERIT_BATCH_START = "INHERIT_BATCH_START";
        /** 回料从它产出入账时起算。 */
        public static final String FROM_REWORK_OUTPUT = "FROM_REWORK_OUTPUT";

        private ReworkRule() {
        }
    }

    public static final String PAUSE_COLD_STORAGE = "COLD_STORAGE";
    public static final String MARK_THAW = "THAW";

    private static final Set<String> SEMI_STARTS = Set.of(
            StartEvent.OUTPUT_POSTED, StartEvent.OUTPUT_REPORTED, StartEvent.UPSTREAM_COMPLETED);
    private static final Set<String> RAW_STARTS = Set.of(
            StartEvent.RAW_RECEIVED, StartEvent.RAW_OUTBOUND, StartEvent.THAW_MARKED);
    private static final Set<String> PAUSE_CODES = Set.of(PAUSE_COLD_STORAGE);
    private static final Set<String> MIX_RULES = Set.of(MixRule.EARLIEST, MixRule.PER_LOT);
    private static final Set<String> SPLIT_RULES = Set.of(SplitRule.INHERIT, SplitRule.RESTART_AT_SPLIT);
    private static final Set<String> REWORK_RULES = Set.of(
            ReworkRule.INHERIT_BATCH_START, ReworkRule.FROM_REWORK_OUTPUT);

    /**
     * 解析并校验画布上的 {@code timeLimit}。不合法抛 {@link IllegalArgumentException}, 消息是字段名 + 原因。
     * 没写的规则取保守默认: 混批按最早、拆批继承、回料继承原批次。
     */
    public static ProcessTimeLimitConfig parse(Map<?, ?> raw) {
        int maxMinutes = positiveInt(raw.get("maxMinutes"), "maxMinutes");
        Integer warnMinutes = optionalPositiveInt(raw.get("warnMinutes"), "warnMinutes");
        if (warnMinutes != null && warnMinutes >= maxMinutes) {
            throw new IllegalArgumentException("warnMinutes must be less than maxMinutes");
        }
        Set<String> starts = new HashSet<>(SEMI_STARTS);
        starts.addAll(RAW_STARTS);
        String startEvent = oneOf(raw.get("startEvent"), "startEvent", null, starts);
        List<String> pauseCodes = new ArrayList<>();
        Object rawCodes = raw.get("pauseCodes");
        if (rawCodes != null) {
            if (!(rawCodes instanceof List<?> codes)) {
                throw new IllegalArgumentException("pauseCodes must be an array");
            }
            for (Object code : codes) {
                pauseCodes.add(oneOf(code, "pauseCodes", null, PAUSE_CODES));
            }
        }
        return new ProcessTimeLimitConfig(
                maxMinutes,
                warnMinutes,
                startEvent,
                List.copyOf(pauseCodes),
                optionalPositiveInt(raw.get("maxPauseMinutes"), "maxPauseMinutes"),
                oneOf(raw.get("mixRule"), "mixRule", MixRule.EARLIEST, MIX_RULES),
                oneOf(raw.get("splitRule"), "splitRule", SplitRule.INHERIT, SPLIT_RULES),
                oneOf(raw.get("reworkRule"), "reworkRule", ReworkRule.INHERIT_BATCH_START, REWORK_RULES),
                optionalPositiveInt(raw.get("reworkMaxMinutes"), "reworkMaxMinutes"),
                optionalPositiveInt(raw.get("recheckValidMinutes"), "recheckValidMinutes"));
    }

    /** 起算点要和料的种类对得上: 原料没有「上道完工」, 半成品没有「解冻」。 */
    public void assertFitsMaterialKind(String materialKind) {
        boolean raw = "RAW_MATERIAL".equals(materialKind);
        if (raw ? !RAW_STARTS.contains(startEvent) : !SEMI_STARTS.contains(startEvent)) {
            throw new IllegalArgumentException("startEvent " + startEvent + " does not fit " + materialKind);
        }
    }

    /** 冻结进端口的规范形态 (默认值已补齐, 运行时不再猜)。 */
    public Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("maxMinutes", maxMinutes);
        out.put("warnMinutes", warnMinutes);
        out.put("startEvent", startEvent);
        out.put("pauseCodes", pauseCodes);
        out.put("maxPauseMinutes", maxPauseMinutes);
        out.put("mixRule", mixRule);
        out.put("splitRule", splitRule);
        out.put("reworkRule", reworkRule);
        out.put("reworkMaxMinutes", reworkMaxMinutes);
        out.put("recheckValidMinutes", recheckValidMinutes);
        return out;
    }

    public boolean perLot() {
        return MixRule.PER_LOT.equals(mixRule);
    }

    public boolean inheritsSplit() {
        return SplitRule.INHERIT.equals(splitRule);
    }

    private static String oneOf(Object value, String field, String fallback, Set<String> allowed) {
        if (value == null && fallback != null) {
            return fallback;
        }
        if (!(value instanceof String text) || !allowed.contains(text)) {
            throw new IllegalArgumentException(field + " must be one of " + new TreeSet<>(allowed));
        }
        return text;
    }

    private static int positiveInt(Object value, String field) {
        Integer parsed = optionalPositiveInt(value, field);
        if (parsed == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return parsed;
    }

    private static Integer optionalPositiveInt(Object value, String field) {
        if (value == null) {
            return null;
        }
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())
                || number.longValue() <= 0 || number.longValue() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(field + " must be a positive whole number of minutes");
        }
        return number.intValue();
    }
}
