package com.cretas.aims.service.execution;

import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只读查询一轮的参数来源与优先级 (INTENT-DISTILL D1)。
 *
 * <p>固定顺序: <b>本轮用户明确说的 &gt; 本会话已确认的同一目标 &gt; 系统默认值</b>。
 * <ul>
 *   <li>默认值从不在这里写进参数: 工具自己在缺参时补 (合并之后补缺), 这里只把它记成 DEFAULT 来源,
 *       所以默认值不可能盖掉本轮或沿用的值。</li>
 *   <li>沿用只发生在<b>同一目标</b> (同意图 + 同工具) 且本轮至少明确改了一个条件时;
 *       换目标不沿用任何条件; 本轮什么条件都没说就是一次新的查询, 也不沿用。</li>
 *   <li>页码只有一个来源: 本轮原话里的「第 N 页」, 或服务端翻页 (上一页的服务端页码 ± 1);
 *       模型 / 学习规则抽出来的 page 一律丢弃。「每页 N 条」同理只由本轮原话绑定。
 *       工具不声明分页参数时明确拒绝, 不悄悄按默认分页执行。</li>
 * </ul>
 * 每个最终参数的来源写进响应 {@code metadata.readQuery.sources}, 并随助手消息存进现有会话记忆
 * (不新建状态表)。
 */
final class ReadTurnParameters {

    static final String METADATA_KEY = "readQuery";
    /** 上一轮已确认的查询在这段时间内可以被续句沿用 (与识别用最近对话的窗口一致)。 */
    static final Duration MAX_AGE = Duration.ofMinutes(30);

    enum Source { CURRENT_TURN, INHERITED, DEFAULT, SERVER_PAGE }

    private static final List<String> PAGE_KEYS = List.of("page", "pageNum", "pageNo");
    private static final List<String> SIZE_KEYS = List.of("size", "pageSize");
    private static final Set<String> DATE_GROUP = Set.of("startDate", "endDate");

    private static final String NUM = "([0-9]{1,4}|[零一二两三四五六七八九十百]{1,4})";
    private static final Pattern ABSOLUTE_PAGE = Pattern.compile("第\\s*" + NUM + "\\s*页");
    private static final Pattern PAGE_SIZE = Pattern.compile("每页\\s*(?:显示|只看|看|只显示|放)?\\s*" + NUM
            + "\\s*(?:条|个|行|笔|项|张|单|批|份)?");

    private ReadTurnParameters() {
    }

    /** 本轮原话里的分页说法。值为 null 表示没说。 */
    record PageWords(Integer page, Integer size) {
        boolean mentioned() {
            return page != null || size != null;
        }
    }

    static PageWords pageWords(String sentence) {
        if (sentence == null || sentence.isBlank()) return new PageWords(null, null);
        Integer page = null;
        Integer size = null;
        Matcher sizeMatcher = PAGE_SIZE.matcher(sentence);
        String rest = sentence;
        if (sizeMatcher.find()) {
            size = number(sizeMatcher.group(1));
            rest = sentence.substring(0, sizeMatcher.start()) + " " + sentence.substring(sizeMatcher.end());
        }
        Matcher pageMatcher = ABSOLUTE_PAGE.matcher(rest);
        if (pageMatcher.find()) page = number(pageMatcher.group(1));
        return new PageWords(page, size);
    }

    static String pageKey(Map<?, ?> properties) {
        for (String key : PAGE_KEYS) if (properties.containsKey(key)) return key;
        return null;
    }

    static String sizeKey(Map<?, ?> properties) {
        for (String key : SIZE_KEYS) if (properties.containsKey(key)) return key;
        return null;
    }

    static boolean isPagingKey(String key) {
        return PAGE_KEYS.contains(key) || SIZE_KEYS.contains(key);
    }

    /** 上一轮成功执行、可被续句沿用的只读查询。 */
    record Snapshot(String intentCode, String toolName, Map<String, Object> params) {
    }

    /**
     * 本人会话里<b>最近一条</b>助手消息上的 readQuery。最近一条助手消息不是只读查询 (闲聊、写操作、失败)
     * 时不往更早处找: 中间隔了别的事, 旧条件不再是「本会话已确认的同一目标」。
     */
    @SuppressWarnings("unchecked")
    static Optional<Snapshot> latest(ConversationContext context, String factoryId, Long userId, LocalDateTime now) {
        if (context == null || factoryId == null || userId == null
                || !factoryId.equals(context.getFactoryId()) || !userId.equals(context.getUserId())
                || context.getRecentMessages() == null) {
            return Optional.empty();
        }
        List<ConversationMessage> messages = context.getRecentMessages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if (message.getRole() != ConversationMessage.Role.ASSISTANT) continue;
            if (message.getTimestamp() != null && message.getTimestamp().isBefore(now.minus(MAX_AGE))) {
                return Optional.empty();
            }
            if (message.getMetadata() == null || !(message.getMetadata().get(METADATA_KEY) instanceof Map<?, ?> raw)) {
                return Optional.empty();
            }
            Object intent = raw.get("intentCode");
            Object tool = raw.get("toolName");
            if (!(intent instanceof String intentCode) || !(tool instanceof String toolName)
                    || !(raw.get("params") instanceof Map<?, ?> params)) {
                return Optional.empty();
            }
            Map<String, Object> copy = new LinkedHashMap<>();
            params.forEach((k, v) -> {
                if (k instanceof String key && isScalar(v)) copy.put(key, v);
            });
            return Optional.of(new Snapshot(intentCode, toolName, copy));
        }
        return Optional.empty();
    }

    /**
     * 合并: {@code params} 进来时只含本轮来源 (请求 context、本轮原话的时间 / 状态 / 分页、模型抽参) 与服务端
     * 绑定的主体键; {@code sources} 已标好本轮各键的来源。这里只按同一目标补「本轮没说」的已确认条件。
     *
     * @return 本次沿用的键
     */
    static Set<String> inherit(Map<String, Object> params, Map<String, Source> sources, Map<?, ?> properties,
                               Snapshot previous, String intentCode, String toolName,
                               Set<String> doNotInherit) {
        if (previous == null || intentCode == null || toolName == null
                || !intentCode.equals(previous.intentCode()) || !toolName.equals(previous.toolName())) {
            return Set.of();
        }
        boolean changedSomething = sources.entrySet().stream()
                .anyMatch(e -> e.getValue() == Source.CURRENT_TURN && properties.containsKey(e.getKey()));
        if (!changedSomething) return Set.of();
        boolean currentSetsDate = DATE_GROUP.stream().anyMatch(key -> sources.get(key) == Source.CURRENT_TURN);
        Set<String> inherited = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Object> entry : previous.params().entrySet()) {
            String key = entry.getKey();
            if (!properties.containsKey(key) || PAGE_KEYS.contains(key) || doNotInherit.contains(key)) continue;
            if (sources.containsKey(key)) continue;
            // 时间是一组: 本轮改了任一端, 旧的另一端不再成立 (「改成本月」不能留着上个月的结束日)
            if (DATE_GROUP.contains(key) && currentSetsDate) continue;
            params.put(key, entry.getValue());
            sources.put(key, Source.INHERITED);
            inherited.add(key);
        }
        return inherited;
    }

    /** 声明了默认值、合并后仍缺的参数 → DEFAULT (由工具补, 这里不写值)。 */
    static void markDefaults(Map<String, Object> params, Map<String, Source> sources, Map<?, ?> properties) {
        properties.forEach((k, v) -> {
            if (k instanceof String key && v instanceof Map<?, ?> definition && definition.containsKey("default")
                    && (params.get(key) == null || (params.get(key) instanceof String s && s.isBlank()))) {
                sources.put(key, Source.DEFAULT);
            }
        });
    }

    /** 响应 metadata.readQuery: 工具声明过的标量业务参数 + 每个参数的来源。 */
    static Map<String, Object> snapshot(String intentCode, String toolName, String queryInput,
                                        Map<String, Object> params,
                                        Map<String, Source> sources, Map<?, ?> properties) {
        Map<String, Object> business = new LinkedHashMap<>();
        Map<String, String> sourceNames = new LinkedHashMap<>();
        properties.forEach((k, v) -> {
            if (!(k instanceof String key)) return;
            Object value = params.get(key);
            if (isScalar(value) && !(value instanceof String s && s.isBlank())) business.put(key, value);
            if (sources.containsKey(key)) sourceNames.put(key, sources.get(key).name());
        });
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("intentCode", intentCode);
        snapshot.put("toolName", toolName);
        if (queryInput != null && !queryInput.isBlank()) snapshot.put("queryInput", queryInput);
        snapshot.put("params", business);
        snapshot.put("sources", sourceNames);
        return snapshot;
    }

    static boolean isScalar(Object value) {
        return value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    static Map<?, ?> properties(Map<String, Object> schema) {
        return schema != null && schema.get("properties") instanceof Map<?, ?> p ? p : Map.of();
    }

    static boolean declaresAny(Map<?, ?> properties, Collection<String> keys) {
        return keys.stream().anyMatch(properties::containsKey);
    }

    private static Integer number(String raw) {
        if (raw == null) return null;
        if (raw.chars().allMatch(Character::isDigit)) {
            try {
                return Integer.parseInt(raw);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // 小写中文数字 (≤ 999): 「第三页」「每页二十条」
        int total = 0;
        int digit = 0;
        for (char c : raw.toCharArray()) {
            int d = "零一二三四五六七八九".indexOf(c);
            if (c == '两') d = 2;
            if (d >= 0) {
                digit = d;
            } else if (c == '十') {
                total += (digit == 0 ? 1 : digit) * 10;
                digit = 0;
            } else if (c == '百') {
                total += (digit == 0 ? 1 : digit) * 100;
                digit = 0;
            } else {
                return null;
            }
        }
        return total + digit;
    }
}
