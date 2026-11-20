package com.cretas.aims.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具自己声明「我只支持哪种口径、哪些口径不支持」, 对话里点名了不支持的口径时回复开头明说
 * 「暂时只能按金额排」, 同时按支持的口径给出结果 (Steve 2026-11-12 口径 6; D1 曾整轮拒绝不给结果,
 * 更早则是不说就悄悄按金额返回)。被替换的口径记进响应 {@code metadata.readNotices}。
 *
 * <p>词表属于工具的参数定义 (和 {@link StatusFilter} 的状态词表一样), 不是识别前的句式白名单:
 * 它不选意图、不授权执行, 只在已经选定的工具上拦一个它做不到的口径。
 *
 * <pre>
 * "metric": {
 *   "type": "string", "enum": ["amount"], "default": "amount",
 *   "x-option-words": {"amount": ["金额", "销售额"]},
 *   "x-unsupported-options": {"quantity": {"label": "销售数量", "words": ["数量", "销量", "件数"]}}
 * }
 * </pre>
 *
 * 判定: 原话点名了某个不支持口径 (前面没有「不 / 非」否定), 且没有同时点名一个未被否定的支持口径
 * (「数量和金额排名」= 按金额排、顺带看数量, 照常执行; 「不是金额，是数量」= 要数量, 说明后按金额给)。
 */
public final class UnsupportedOptionGuard {

    public static final String OPTION_WORDS = "x-option-words";
    public static final String UNSUPPORTED = "x-unsupported-options";

    private UnsupportedOptionGuard() {
    }

    /**
     * 被替换的口径: 参数名、实际执行的支持口径 (值与中文名)、被要求的不支持口径 (值与中文名)。
     */
    public record Substitution(String parameter, String appliedValue, String appliedLabel,
                               String requestedValue, String requestedLabel) {
        /** 回复开头的那句话。 */
        public String notice(String capabilityName) {
            return "「" + capabilityName + "」暂时只能按" + appliedLabel + "排，不能按" + requestedLabel
                    + "排；下面先给出按" + appliedLabel + "的结果。";
        }
    }

    public static Optional<Substitution> check(Map<String, Object> parametersSchema, String sentence) {
        if (parametersSchema == null || sentence == null || sentence.isBlank()
                || !(parametersSchema.get("properties") instanceof Map<?, ?> properties)) {
            return Optional.empty();
        }
        for (Map.Entry<?, ?> entry : properties.entrySet()) {
            if (!(entry.getKey() instanceof String name) || !(entry.getValue() instanceof Map<?, ?> property)
                    || !(property.get(UNSUPPORTED) instanceof Map<?, ?> unsupported)) {
                continue;
            }
            boolean supportedNamed = false;
            String supportedLabel = null;
            String supportedValue = property.get("default") instanceof String d && !d.isBlank() ? d : null;
            if (property.get(OPTION_WORDS) instanceof Map<?, ?> supported) {
                for (Map.Entry<?, ?> option : supported.entrySet()) {
                    for (String word : strings(option.getValue())) {
                        if (supportedLabel == null) supportedLabel = word;
                        if (namedWithoutNegation(sentence, word)) supportedNamed = true;
                    }
                    if (supportedValue == null && option.getKey() instanceof String code) supportedValue = code;
                }
            }
            if (supportedNamed) continue;
            if (supportedValue == null) continue; // 没有可替代的支持口径: 不在这里决定, 交给工具按原样报错
            for (Map.Entry<?, ?> option : unsupported.entrySet()) {
                if (!(option.getValue() instanceof Map<?, ?> definition)) continue;
                for (String word : strings(definition.get("words"))) {
                    if (namedWithoutNegation(sentence, word)) {
                        String label = definition.get("label") instanceof String l && !l.isBlank() ? l : word;
                        return Optional.of(new Substitution(name, supportedValue,
                                supportedLabel == null ? supportedValue : supportedLabel,
                                String.valueOf(option.getKey()), label));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 声明了「不支持口径」词表的参数 (供测试与能力描述使用)。 */
    public static Map<String, List<String>> unsupportedWords(Map<String, Object> property) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (property != null && property.get(UNSUPPORTED) instanceof Map<?, ?> unsupported) {
            unsupported.forEach((k, v) -> {
                if (k instanceof String code && v instanceof Map<?, ?> definition) {
                    out.put(code, strings(definition.get("words")));
                }
            });
        }
        return out;
    }

    private static boolean namedWithoutNegation(String sentence, String word) {
        if (word == null || word.isBlank()) return false;
        int from = 0;
        while (true) {
            int at = sentence.indexOf(word, from);
            if (at < 0) return false;
            String before = sentence.substring(Math.max(0, at - 3), at);
            if (!before.contains("不") && !before.contains("非") && !before.contains("别")) return true;
            from = at + word.length();
        }
    }

    private static List<String> strings(Object raw) {
        List<String> out = new ArrayList<>();
        if (raw instanceof Iterable<?> values) {
            for (Object value : values) if (value instanceof String s && !s.isBlank()) out.add(s);
        }
        return out;
    }
}
