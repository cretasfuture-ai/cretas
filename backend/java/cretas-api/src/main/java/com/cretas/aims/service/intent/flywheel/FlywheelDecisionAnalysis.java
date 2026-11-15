package com.cretas.aims.service.intent.flywheel;

import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.intent.IntentFlywheelTurn.Action;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从一轮的识别痕迹 ({@link IntentFlywheelTurnContext.Turn}) 推出「谁定的、走了哪几跳、做了什么动作」。纯函数。
 *
 * <p>决策来源 (decision_source), 按优先级:
 * <ol>
 *   <li>{@code BUTTON} —— 请求自带 intentCode (用户点了按钮), 不经识别;</li>
 *   <li>{@code PHRASE_SHORTCUT} —— 旧出口计数里出现 java_phrase_shortcut / java_early_phrase (短语快捷);</li>
 *   <li>{@code CACHE} —— 识别阶段 PYTHON_CACHE_HIT (同一句话上次 Python 的结果);</li>
 *   <li>Python 决策 (阶段 PYTHON_MATCH / PYTHON_DECISION_NONE), 按 Python 带回的 decisionTrace:
 *       旧四级出口 → {@code PY_LEGACY}; 组合退路 hop=combo → {@code ALIYUN_COMBO}, qwen_only → {@code QWEN},
 *       intent_ask → {@code QWEN_ASK}, buttons → {@code BUTTONS_NO_SIGNAL}; Luna 给了概率 → {@code LUNA}; 说话意图判不了停下追问 (timingMs.requestPurposeUnavailable) → {@code PY_UNAVAILABLE};
 *       其余 (Luna 没开 / 失败后千问读概率链答的) → {@code QWEN}; Python 没带 trace (旧版本) → {@code PYTHON_UNTRACED};</li>
 *   <li>Java 旧管线: 匹配方式 LLM → {@code JAVA_LLM}, 其余 (EXACT / REGEX / KEYWORD / SEMANTIC …) → {@code RULE};</li>
 *   <li>本轮没有走识别 (续上一轮的追问、早退的拒绝) → {@code NO_RECOGNITION}。</li>
 * </ol>
 * decision_path 是按先后的全部痕迹 (阶段、出口、Python trace), 来源判错时可回查。</p>
 */
public final class FlywheelDecisionAnalysis {

    /** 平台意图目录 (resources/ai/factory-intent-catalog.json) 的内容摘要; 目录改了这个值就变。 */
    public static final String CATALOG_VERSION = catalogVersion();

    private FlywheelDecisionAnalysis() {
    }

    public record Decision(String source, Map<String, Object> path, Action action, String intentCode,
                           Double confidence, List<Map<String, Object>> candidates, String optionSetHash) {
    }

    public static Decision analyse(IntentFlywheelTurnContext.Turn turn, String requestedIntentCode) {
        List<String> stages = turn == null ? List.of() : turn.stages();
        List<String> exits = turn == null ? List.of() : turn.exits();
        IntentMatchResult r = turn == null ? null : turn.lastRecognition();
        Map<String, Object> trace = r == null || r.getDecisionTrace() == null ? null : r.getDecisionTrace();

        Map<String, Object> path = new LinkedHashMap<>();
        if (!stages.isEmpty()) path.put("stages", stages);
        if (!exits.isEmpty()) path.put("exits", exits);
        if (trace != null) path.put("python", trace);
        if (r != null && r.getMatchMethod() != null) path.put("matchMethod", r.getMatchMethod().name());
        if (r != null && r.getTimingMs() != null) {
            Map<String, Long> flags = new LinkedHashMap<>();
            r.getTimingMs().forEach((k, v) -> {
                if (k != null && !k.endsWith("Ms")) flags.put(k, v);   // 时间不进来源, 只留决策标记
            });
            if (!flags.isEmpty()) path.put("flags", flags);
        }

        String source = source(requestedIntentCode, stages, exits, r, trace);
        List<Map<String, Object>> candidates = new ArrayList<>();
        if (r != null && r.getTopCandidates() != null) {
            for (IntentMatchResult.CandidateIntent c : r.getTopCandidates()) {
                if (c == null || c.getIntentCode() == null) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("code", c.getIntentCode());
                m.put("name", c.getIntentName());
                m.put("confidence", c.getConfidence());
                candidates.add(m);
                if (candidates.size() >= 5) break;
            }
        }
        String intent = null;
        Double confidence = null;
        if (r != null) {
            if (r.getBestMatch() != null) intent = r.getBestMatch().getIntentCode();
            confidence = r.getConfidence();
        }
        if (requestedIntentCode != null && !requestedIntentCode.isBlank()) {
            intent = requestedIntentCode;
        }
        Object optionHash = trace == null ? null : trace.get("optionSetHash");
        return new Decision(source, path, action(requestedIntentCode, r), intent, confidence, candidates,
                optionHash == null ? null : String.valueOf(optionHash));
    }

    static String source(String requestedIntentCode, List<String> stages, List<String> exits,
                         IntentMatchResult r, Map<String, Object> trace) {
        if (requestedIntentCode != null && !requestedIntentCode.isBlank()) return "BUTTON";
        if (exits.contains("java_phrase_shortcut") || exits.contains("java_early_phrase")) return "PHRASE_SHORTCUT";
        String last = stages.isEmpty() ? null : stages.get(stages.size() - 1);
        if ("PYTHON_CACHE_HIT".equals(last)) return "CACHE";
        if ("PYTHON_MATCH".equals(last) || "PYTHON_DECISION_NONE".equals(last)) {
            if (r != null && r.getTimingMs() != null && Long.valueOf(1L).equals(r.getTimingMs().get("requestPurposeUnavailable"))) {
                return "PY_UNAVAILABLE";
            }
            return pythonSource(trace);
        }
        if (last != null && !last.startsWith("PYTHON_")) return "LLM".equals(last) ? "JAVA_LLM" : "RULE";
        if (r != null && r.getMatchMethod() != null) {
            return r.getMatchMethod() == IntentMatchResult.MatchMethod.LLM ? "JAVA_LLM" : "RULE";
        }
        return FlywheelReactionRules.NO_RECOGNITION;
    }

    static String pythonSource(Map<String, Object> trace) {
        if (trace == null || trace.isEmpty()) return "PYTHON_UNTRACED";
        if (trace.get("legacyExit") != null) return "PY_LEGACY";
        if (trace.get("fallback") instanceof Map<?, ?> fb) {
            return switch (String.valueOf(fb.get("hop"))) {
                case "combo" -> "ALIYUN_COMBO";
                case "qwen_only" -> "QWEN";
                case "intent_ask" -> "QWEN_ASK";
                case "buttons" -> "BUTTONS_NO_SIGNAL";
                default -> "PY_FALLBACK";
            };
        }
        if ("ok".equals(trace.get("luna"))) return "LUNA";
        return "QWEN";
    }

    static Action action(String requestedIntentCode, IntentMatchResult r) {
        if (requestedIntentCode != null && !requestedIntentCode.isBlank()) return Action.DIRECT;
        if (r == null) return Action.UNKNOWN;
        boolean match;
        try {
            match = r.hasMatch();
        } catch (RuntimeException e) {   // confidence 为空
            match = false;
        }
        boolean none = r.getTimingMs() != null && Long.valueOf(1L).equals(r.getTimingMs().get("decisionNone"));
        if (!match && none) return Action.NONE;
        if (Boolean.TRUE.equals(r.getRequiresConfirmation())
                && r.getTopCandidates() != null && !r.getTopCandidates().isEmpty()) return Action.SHOW;
        if (match) return Action.DIRECT;
        if (r.getClarificationQuestion() != null && !r.getClarificationQuestion().isBlank()) return Action.ASK;
        return Action.UNKNOWN;
    }

    private static String catalogVersion() {
        try (InputStream in = FlywheelDecisionAnalysis.class.getResourceAsStream("/ai/factory-intent-catalog.json")) {
            if (in == null) return "fic-missing";
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(in.readAllBytes());
            return "fic-sha256:" + HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (IOException | NoSuchAlgorithmException e) {
            return "fic-unreadable";
        }
    }
}
