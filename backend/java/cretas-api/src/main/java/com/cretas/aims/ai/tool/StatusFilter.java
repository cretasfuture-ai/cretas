package com.cretas.aims.ai.tool;

import com.cretas.aims.exception.BusinessException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 工具的可选「状态」筛选: 用户在对话里说的状态 (「已发货的出货单」「不合格的检验」) 落到 status 参数上。
 *
 * <p>ERP-S6-10 的同因扫描: 对话入口 ({@code ToolDispatchService}) 对「只有可选参数」的读工具
 * 不做参数抽取, 句子里的状态到不了工具, 于是「查询已用完状态的原料批次」返回全部批次 ——
 * 同样形状的读工具有 20 多个。这里是唯一一份机制, 由 {@link AbstractBusinessTool} 在执行 / 预览前统一调用;
 * 工具只需在 status 属性里声明自己的状态词表 ({@link #property}), 不各写一份解析。
 *
 * <p>为什么是确定性词表而不是给这些工具打开 {@code x-llm-extract-optional}:
 * <ul>
 *   <li>每次读查询多一次大模型往返 (延迟 + 费用), 而状态是一个封闭的小集合;</li>
 *   <li>模型可能替用户「补」一个默认状态 (枚举值能直接通过 grounding), 那是比「不筛」更隐蔽的错;</li>
 *   <li>说了状态却认不出时, 模型通常只是不填 —— 正是要避免的「静默当成不限」; 词表能确定地说「没认出」;</li>
 *   <li>CI 里能完整验证, 不依赖真实模型。</li>
 * </ul>
 *
 * <p>规则 (与 ERP-S6-10 相同):
 * <ul>
 *   <li>显式传入的 status: 中文词 / 代码 (不分大小写) 归一成工具自己的代码; 认不出原样交给工具;</li>
 *   <li>没传 status: 从用户原话 ({@code userInput}) 认, 恰好一个 → 用它;</li>
 *   <li>说了状态却认不出、说了「请换个问法」的词、或一句里有两个不同状态 → 400, 列出能查的状态,
 *       ⛔ 不静默当成「不限状态」;</li>
 *   <li>根本没提状态 → 不加筛选。</li>
 * </ul>
 */
public final class StatusFilter {

    /** status 属性上的状态词表: 代码 → 用户会说的中文词。键集合就是可查的状态。 */
    public static final String WORDS = "x-status-words";
    /** 与某个状态词重叠但意思不同的说法 (「即将过期」不是「已过期」): 命中就请用户换个问法。 */
    public static final String ASK_INSTEAD = "x-status-ask-instead";
    /** 不是在说状态的固定说法 (「过期时间」是日期字段): 匹配前先遮掉。 */
    public static final String NEUTRAL = "x-status-neutral";

    private static final Pattern STATUS_IS = Pattern.compile("状态\\s*(?:为|是|=|:|：)\\s*([\\p{IsHan}A-Za-z_]{1,8})");
    private static final Pattern BEFORE_STATUS = Pattern.compile("([\\p{IsHan}]{1,10})状态");
    private static final Pattern LEADING_VERB = Pattern.compile(
            "^(?:帮我|请|麻烦)?(?:查询|查看|查一下|查查|查|看看|看一下|看|找出|找|列出|显示|统计|所有|全部|我的|我)+");
    /** 「X状态」里 X 以这些词结尾时, 说的是「状态这个维度」(「订单状态」), 不是某个具体状态。 */
    private static final List<String> GENERIC = List.of(
            "批次", "原料", "原材料", "材料", "物料", "库存", "订单", "采购单", "退货单", "出货单", "发货单", "单据",
            "告警", "事件", "设备", "秤", "客户", "供应商", "产品", "任务", "工序", "申请", "请假", "加班", "报销",
            "通知", "提醒", "检验", "质检", "缺陷", "退货", "记录", "当前", "目前", "现在", "各", "每个", "所有",
            "全部", "什么", "哪些", "哪种", "哪个");

    private StatusFilter() {
    }

    // ------------------------------------------------------------------ schema side

    /** status 属性: {@code enum} = 词表的键, 带上词表。 */
    public static Map<String, Object> property(String description, Map<String, List<String>> words) {
        return property(description, words, List.of(), List.of());
    }

    public static Map<String, Object> property(String description, Map<String, List<String>> words,
                                               List<String> askInstead, List<String> neutral) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", "string");
        p.put("description", description);
        p.put("enum", new ArrayList<>(words.keySet()));
        p.put(WORDS, words);
        if (!askInstead.isEmpty()) {
            p.put(ASK_INSTEAD, askInstead);
        }
        if (!neutral.isEmpty()) {
            p.put(NEUTRAL, neutral);
        }
        return p;
    }

    /**
     * 去掉 {@code enum}: 给接受逗号分隔多个状态的工具 (函数调用方仍可传 "PENDING,SNOOZED"),
     * 对话里认状态照样按词表来。
     */
    public static Map<String, Object> withoutEnum(Map<String, Object> property) {
        property.remove("enum");
        return property;
    }

    /** 枚举 → 词表: 每个值都在, 词取 {@code label} (可为 null) 加上 {@code extra} 里的同义词。 */
    public static <E extends Enum<E>> Map<String, List<String>> wordsOf(
            Class<E> type, Function<E, String> label, Map<E, List<String>> extra) {
        Map<String, List<String>> words = new LinkedHashMap<>();
        for (E e : type.getEnumConstants()) {
            List<String> list = new ArrayList<>();
            String l = label == null ? null : label.apply(e);
            if (l != null && !l.isBlank()) {
                list.add(l);
            }
            list.addAll(extra.getOrDefault(e, List.of()));
            words.put(e.name(), List.copyOf(new LinkedHashSet<>(list)));
        }
        return words;
    }

    /** 词表构造的小工具: words("ACTIVE", "未处理", "待处理") */
    public static Map.Entry<String, List<String>> words(String code, String... said) {
        return Map.entry(code, List.of(said));
    }

    @SafeVarargs
    public static Map<String, List<String>> table(Map.Entry<String, List<String>>... entries) {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : entries) {
            m.put(e.getKey(), e.getValue());
        }
        return m;
    }

    // ------------------------------------------------------------------ execution side

    /**
     * 执行 / 预览前调用: 工具的 status 属性声明了词表时, 归一显式 status, 或从 userInput 认出 status。
     * 没声明词表的工具原样不动。
     */
    @SuppressWarnings("unchecked")
    public static void apply(Map<String, Object> parametersSchema, Map<String, Object> params) {
        Map<String, Object> property = statusProperty(parametersSchema);
        if (property == null || params == null) {
            return;
        }
        Object explicit = params.get("status");
        if (explicit != null && !String.valueOf(explicit).isBlank()) {
            params.put("status", normalize(property, String.valueOf(explicit)));
            return;
        }
        Object said = params.get("userInput");
        if (said instanceof String sentence) {
            fromSentence(property, sentence).ifPresent(code -> params.put("status", code));
        }
    }

    /** 结果上记下这次按哪个状态筛的: {code, label}。供回复措辞用 (见 {@link #emptyFilteredReply})。 */
    public static final String APPLIED = "appliedStatusFilter";

    /**
     * 执行后调用: 这次按状态筛了 (声明了词表且 params 里有 status), 就在结果上记下状态代码与中文名。
     * 不改原结果对象, 返回带标记的副本。
     */
    public static Map<String, Object> annotate(Map<String, Object> parametersSchema, Map<String, Object> params,
                                               Map<String, Object> result) {
        Map<String, Object> property = statusProperty(parametersSchema);
        if (property == null || params == null || result == null || params.get("status") == null) {
            return result;
        }
        String code = String.valueOf(params.get("status"));
        List<String> said = words(property).get(code);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put(APPLIED, Map.of("code", code, "label", said == null || said.isEmpty() ? code : said.get(0)));
        return out;
    }

    /**
     * 按状态筛过且<b>一条都没有</b>时的回复 —— 必须点名这个筛选条件。
     * 以前「已过期状态」查 0 条回的是「当前暂无原料批次记录。可通过「入库」录入新原料」, 读起来像整个库是空的。
     *
     * @return 点名筛选条件的回复; 不是「按状态筛过且为空」时为 null (照常走各意图的格式化)
     */
    public static String emptyFilteredReply(Object resultData) {
        if (!(resultData instanceof Map<?, ?> data)) {
            return null;
        }
        Object applied = data.get(APPLIED);
        String time = TimeRangeFilter.appliedLabel(data);
        if (!(applied instanceof Map<?, ?>) && time == null) {
            return null;
        }
        if (!Boolean.TRUE.equals(isEmptyResult(data))) {
            return null;
        }
        if (time == null) {
            return "没有状态为「" + ((Map<?, ?>) applied).get("label") + "」的记录。这是按状态筛选后的结果，不代表没有任何记录；"
                    + "去掉状态条件可以查看全部。";
        }
        // 同样按时间筛过: 两个条件都点名 (「上个月」查 0 条不是「暂无记录」)
        String status = applied instanceof Map<?, ?> a ? "、状态为「" + a.get("label") + "」" : "";
        return "没有时间在 " + time + status + " 的记录。这是按条件筛选后的结果，不代表没有任何记录；"
                + "去掉条件可以查看全部。";
    }

    /** 总数字段为 0, 或 (没有总数字段时) 所有列表字段都为空; 看不出来时为 null。 */
    private static Boolean isEmptyResult(Map<?, ?> data) {
        for (String key : List.of("totalElements", "total", "totalCount")) {
            if (data.get(key) instanceof Number n) {
                return n.longValue() == 0;
            }
        }
        boolean sawList = false;
        for (Object v : data.values()) {
            if (v instanceof Collection<?> c) {
                sawList = true;
                if (!c.isEmpty()) {
                    return false;
                }
            }
        }
        return sawList ? Boolean.TRUE : null;
    }

    /** schema 里声明了词表的 status 属性; 没有则 null。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> statusProperty(Map<String, Object> parametersSchema) {
        if (parametersSchema == null || !(parametersSchema.get("properties") instanceof Map<?, ?> props)) {
            return null;
        }
        Object status = props.get("status");
        if (status instanceof Map<?, ?> p && p.get(WORDS) instanceof Map<?, ?>) {
            return (Map<String, Object>) p;
        }
        return null;
    }

    /** 显式参数: 中文词 / 代码 (不分大小写) → 工具自己的代码; 认不出原样返回, 由工具按它的规则拒绝。 */
    public static String normalize(Map<String, Object> property, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim();
        for (Map.Entry<String, List<String>> e : words(property).entrySet()) {
            if (e.getKey().equalsIgnoreCase(value) || e.getValue().contains(value)) {
                return e.getKey();
            }
        }
        return value;
    }

    /**
     * 从用户原话认状态。
     *
     * @return 认出的唯一状态代码; 没提状态时为空
     * @throws BusinessException 400 —— 提了状态却认不出 / 要换个问法 / 一句里不止一个状态
     */
    public static Optional<String> fromSentence(Map<String, Object> property, String sentence) {
        if (sentence == null || sentence.isBlank()) {
            return Optional.empty();
        }
        Map<String, List<String>> words = words(property);
        String text = sentence.trim();
        for (String phrase : strings(property.get(ASK_INSTEAD))) {
            if (text.contains(phrase)) {
                throw unrecognized(phrase, words);
            }
        }
        StringBuilder masked = new StringBuilder(text);
        for (String neutral : strings(property.get(NEUTRAL))) {
            maskAll(masked, neutral);
        }

        Set<String> found = new LinkedHashSet<>();
        // 代码: 前后不是字母/下划线 (「状态为USED_UP」「expired 的批次」)
        List<String> codes = new ArrayList<>(words.keySet());
        codes.sort((a, b) -> b.length() - a.length());
        for (String code : codes) {
            Matcher m = Pattern.compile("(?<![A-Za-z_])" + Pattern.quote(code) + "(?![A-Za-z_])",
                    Pattern.CASE_INSENSITIVE).matcher(masked);
            while (m.find()) {
                found.add(code);
                mask(masked, m.start(), m.end());
            }
        }
        // 中文词: 从长到短, 「不合格」先于「合格」, 「生产预留」先于「预留」
        List<Map.Entry<String, String>> byLength = new ArrayList<>();
        words.forEach((code, said) -> said.forEach(w -> byLength.add(Map.entry(w, code))));
        byLength.sort((a, b) -> b.getKey().length() - a.getKey().length());
        for (Map.Entry<String, String> w : byLength) {
            if (maskAll(masked, w.getKey())) {
                found.add(w.getValue());
            }
        }

        if (found.size() > 1) {
            throw new BusinessException(400, "一次只能按一个状态查询，这句话里有："
                    + found.stream().map(c -> label(words, c)).collect(Collectors.joining("、"))
                    + "。请分开问。")
                    .withHint("请分开问，每次只说一个状态");
        }
        if (found.size() == 1) {
            return Optional.of(found.iterator().next());
        }
        String named = namedButUnknown(masked.toString());
        if (named != null) {
            throw unrecognized(named, words);
        }
        return Optional.empty();
    }

    private static String namedButUnknown(String text) {
        Matcher is = STATUS_IS.matcher(text);
        if (is.find()) {
            return is.group(1);
        }
        Matcher before = BEFORE_STATUS.matcher(text);
        while (before.find()) {
            String token = LEADING_VERB.matcher(before.group(1)).replaceFirst("");
            if (token.isEmpty() || token.endsWith("的")) {
                continue;
            }
            if (GENERIC.stream().noneMatch(token::endsWith)) {
                return token;
            }
        }
        return null;
    }

    private static BusinessException unrecognized(String said, Map<String, List<String>> words) {
        String choices = words.keySet().stream().map(c -> label(words, c)).collect(Collectors.joining("、"));
        return new BusinessException(400, "没认出你说的状态「" + said + "」，没有按状态查询。能查的状态：" + choices)
                .withHint("请换成其中一个状态再问");
    }

    private static String label(Map<String, List<String>> words, String code) {
        List<String> said = words.get(code);
        return said == null || said.isEmpty() ? code : said.get(0) + "(" + code + ")";
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> words(Map<String, Object> property) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        ((Map<String, Object>) property.get(WORDS)).forEach((k, v) -> out.put(k, strings(v)));
        return out;
    }

    private static List<String> strings(Object v) {
        if (v instanceof Collection<?> c) {
            return c.stream().map(String::valueOf).collect(Collectors.toList());
        }
        if (v instanceof String[] a) {
            return Arrays.asList(a);
        }
        return List.of();
    }

    private static boolean maskAll(StringBuilder text, String word) {
        if (word.isEmpty()) {
            return false;
        }
        boolean hit = false;
        int at = text.indexOf(word);
        while (at >= 0) {
            hit = true;
            mask(text, at, at + word.length());
            at = text.indexOf(word, at + word.length());
        }
        return hit;
    }

    private static void mask(StringBuilder text, int from, int to) {
        for (int i = from; i < to; i++) {
            text.setCharAt(i, '\u0000');
        }
    }
}
