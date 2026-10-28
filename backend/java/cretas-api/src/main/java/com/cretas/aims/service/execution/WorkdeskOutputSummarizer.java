package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Terminal LLM-summarize gate for Workdesk / Skill orchestration outputs.
 *
 * <p>Sprint 9 P0.2 fix (2026-05-22) — 4 Skills (DAILY_CUSTOMER_FOLLOWUP /
 * FOOD_SAFETY_RECALL / PURCHASER_WEEKLY_PLAN / QUALITY_CHIEF_WORKDESK)
 * leak {@code _toolCount} / {@code _executionOrder} / {@code _reasoning}
 * underscore-prefixed metadata keys directly to user-facing fields
 * (formattedText AND message). Also bare template
 * {@code "查询完成\n包含 N 项数据指标"} provides no real summary.
 *
 * <p>This gate runs AFTER existing formatters as terminal output cleaner:
 * <ol>
 *     <li>Detect underscore-prefixed keys / raw JSON dump / bare template
 *         in {@code formattedText} or {@code message}.</li>
 *     <li>If detected, build a Chinese summary prompt from {@code resultData}
 *         (with underscore keys filtered out), feed to LLM via
 *         {@link DashScopeClient#chatFast(String, String)}.</li>
 *     <li>Replace {@code formattedText} (and {@code message} when it's a raw
 *         JSON dump) with LLM-summarized Chinese text (capped at 800 chars).</li>
 *     <li>Fallback to existing template + log warning if LLM unavailable.</li>
 * </ol>
 *
 * <p>Opt-out via {@code cretas.ai.workdesk-summarizer.enabled=false}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkdeskOutputSummarizer {

    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    /**
     * 数字出处闸。非 final + 默认实例: 既有单测用两参构造器 new 出本类, 它们照样走闸
     * (而不是因为字段为 null 静默跳过); Spring 下由容器注入同一个带计数器的 bean。
     */
    /** 工艺保密系统约束; 单测直接 new 本类时用「没有保密产品」的默认实例。 */
    @Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope llmCostScope =
            com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.standalone();

    @Autowired(required = false)
    private FactoryNumberProvenance numberProvenance = new FactoryNumberProvenance();

    /** 工艺保密产品名, 用于发给模型前把它们换成代号。测试里两参构造时为 null (视为本厂没有保密产品)。 */
    @Autowired(required = false)
    private ProcessSecretSampleFilter processSecretSampleFilter;

    static final String PROVENANCE_SITE = "workdesk-summarizer";
    static final String LLM_UNAVAILABLE_HEADER = " 数据摘要 (LLM 暂不可用, 显示结构化结果):";
    static final String PROVENANCE_FALLBACK_HEADER = " 数据摘要 (AI 摘要里有无法与数据核对的数字, 改为显示系统返回的结构化结果):";
    static final String PRIVACY_FALLBACK_HEADER = " 数据摘要 (结果里有无法脱敏的内容, 未发给 AI, 显示系统返回的结构化结果):";

    @Value("${cretas.ai.workdesk-summarizer.enabled:true}")
    private boolean enabled;

    @Value("${cretas.ai.workdesk-summarizer.max-chars:800}")
    private int maxChars;

    /** Detect underscore-prefixed key like {@code "_toolCount":} in JSON-ish payload. */
    private static final Pattern UNDERSCORE_KEY_PATTERN = Pattern.compile("\"_[a-zA-Z][a-zA-Z0-9]*\"\\s*:");

    /** Detect bare template "查询完成\n包含 N 项数据指标 — 详情请查看...". */
    private static final Pattern BARE_TEMPLATE_PATTERN = Pattern.compile(
            "^\\s*(查询完成|执行完成)[\\s\\S]{0,80}(包含\\s*\\d+\\s*项数据指标|详情请查看下方)[\\s\\S]{0,80}$");

    /** Detect raw JSON brace-heavy dump. */
    private static final double JSON_BRACE_RATIO_THRESHOLD = 0.05;

    /** Min chars to consider response "user-facing meaningful". */
    private static final int MIN_USEFUL_CHARS = 40;

    /** Skip work when chunked Tool response message already has a clean Chinese summary. */
    private static final int MAX_SUMMARIZE_BUDGET_CHARS = 8000;

    /**
     * Apply terminal LLM-summarize gate to the response.
     *
     * <p>Mutates {@code response.formattedText} (and {@code response.message} when it's
     * a raw JSON dump). No-op if disabled, response has no resultData, or the response
     * already has a clean Chinese summary.
     *
     * @param response the response to clean up (must not be null)
     */
    public void apply(IntentExecuteResponse response) {
        apply(response, null);
    }

    /**
     * @param factoryId 当前工厂; 用来查工艺保密产品名, 发给模型前换成代号 (null = 不查)
     */
    public void apply(IntentExecuteResponse response, String factoryId) {
        if (!enabled || response == null) {
            return;
        }

        String formattedText = response.getFormattedText();
        String message = response.getMessage();
        // Skill 组合结果里的工具输出是 JsonNode (SkillExecutorImpl readTree): JsonNode 也是 Iterable,
        // 直接走 stripUnderscoreKeys 会被拆成空列表 —— 先统一转成 Map / List / 标量
        Object rawResultData = response.getResultData();
        boolean treeFailed = false;
        Object plain;
        try {
            plain = LlmEgressRedactor.toPlainTree(objectMapper, rawResultData);
        } catch (RuntimeException e) {
            plain = rawResultData;
            treeFailed = true;
        }
        final Object resultData = plain;

        // formattedText 是 Skill 的「未返回可直接展示的明细」占位 → 数据其实在, 也要出摘要。
        // 只看 formattedText: message 会写进对话记忆, 换成带真名的摘要会让名字随历史再发给模型。
        boolean ftDirty = isDirty(formattedText) || isSkillNoDisplayPlaceholder(formattedText);
        boolean msgDirty = isDirty(message);

        if (!ftDirty && !msgDirty) {
            return; // both clean, nothing to do
        }

        Attempt attempt = treeFailed ? Attempt.REFUSED : tryLlmSummarize(resultData, response, factoryId);
        if (attempt.privacyRefused()) {
            // 脱敏不了就不发: 结构化摘要只在本机拼, 不出服务器
            String local = buildDeterministicFallback(resultData, response, PRIVACY_FALLBACK_HEADER);
            if (local != null && isDirty(local)) {
                local = hardStripDirty(local);
            }
            if (local == null || local.isBlank()) {
                local = hardStripDirty(ftDirty ? formattedText : message);
            }
            if (local != null && !local.isBlank()) {
                if (ftDirty) response.setFormattedText(local);
                if (msgDirty) response.setMessage(local);
            }
            return;
        }
        String summary = attempt.text();
        // Sprint 12 fix: the LLM can echo the raw resultData back (incl. underscore
        // metadata keys / JSON braces), producing a summary that is ITSELF dirty.
        // Previously we accepted any non-blank LLM summary, so a dirty LLM output
        // replaced the dirty original → _toolCount / _executionOrder still leaked
        // (2637-char finance composite, 1013-char quality-chief composite). Now we
        // re-validate and fall through to the deterministic (guaranteed-clean) path.
        if (summary != null && !summary.isBlank() && isDirty(summary)) {
            log.warn("WorkdeskOutputSummarizer: LLM summary still dirty ({} chars) — discarding, using deterministic fallback",
                    summary.length());
            summary = null;
        }
        // 数字出处闸: 模型摘要里每个数都得出自 resultData。有找不到出处的数 →
        // 换成只由 resultData 拼出来的确定性摘要 (by construction 只含工具数)。
        // ⚠️ 提示词里那句「不要编造」管不住这件事 —— 生产上它确实写过「比上月增长 43568.21%」。
        if (summary != null && !summary.isBlank()) {
            summary = numberProvenance.enforce(PROVENANCE_SITE, summary,
                    () -> buildDeterministicFallback(resultData, response, PROVENANCE_FALLBACK_HEADER),
                    stripUnderscoreKeys(resultData)).text();
        }
        if (summary == null || summary.isBlank()) {
            // LLM unavailable (DashScope quota / network / timeout) OR returned dirty —
            // degrade gracefully to a deterministic Java-side strip-and-template summary
            // instead of keeping dirty _toolCount / raw-JSON text.
            summary = buildDeterministicFallback(resultData, response);
            // Defensive: if even the deterministic fallback is somehow dirty, hard-strip
            // underscore-key JSON fragments so the user never sees raw metadata.
            if (summary != null && isDirty(summary)) {
                summary = hardStripDirty(summary);
            }
            // Sprint 12 fix: buildDeterministicFallback returns null when resultData
            // is a shape it can't map (e.g. QUALITY_CHIEF_WORKDESK composite where a
            // sub-tool returned needMoreInfo → raw merged JSON). Previously apply()
            // returned here, KEEPING the dirty raw-JSON formattedText (rd-alert/rd-haccp
            // 1013/293-char _toolCount leaks). Final guarantee: hard-strip the original
            // dirty text so the user never sees raw JSON / underscore metadata.
            if (summary == null || summary.isBlank()) {
                String dirtySource = ftDirty ? formattedText : message;
                summary = hardStripDirty(dirtySource);
            }
            if (summary == null || summary.isBlank()) {
                log.debug("WorkdeskOutputSummarizer: LLM unavailable AND fallback empty; keeping existing template");
                return;
            }
            log.info("WorkdeskOutputSummarizer: applied deterministic fallback ({} chars)", summary.length());
        }

        if (ftDirty) {
            log.info("WorkdeskOutputSummarizer: replacing formattedText (was {} chars, dirty)",
                    formattedText != null ? formattedText.length() : 0);
            response.setFormattedText(summary);
        }
        if (msgDirty) {
            // message often consumed by conversation memory / chat history;
            // replacing with summary keeps history clean.
            log.info("WorkdeskOutputSummarizer: replacing message (was {} chars, dirty)",
                    message != null ? message.length() : 0);
            response.setMessage(summary);
        }
    }

    /**
     * Deterministic Java-side fallback when LLM is unavailable. Strips
     * underscore-prefixed metadata keys and emits a structured summary
     * (top-level key list + leaf counts). Output is plain text, no emoji.
     *
     * <p>Goal: pass the {@link #isDirty} check (no underscore keys, not
     * brace-heavy, not bare template) even when DashScope is rate-limited.
     */
    String buildDeterministicFallback(Object resultData, IntentExecuteResponse response) {
        return buildDeterministicFallback(resultData, response, LLM_UNAVAILABLE_HEADER);
    }

    @SuppressWarnings("unchecked")
    String buildDeterministicFallback(Object resultData, IntentExecuteResponse response, String header) {
        if (resultData == null) return null;
        Object cleaned = stripUnderscoreKeys(resultData);
        if (!(cleaned instanceof Map)) return null;
        Map<String, Object> cleanedMap = (Map<String, Object>) cleaned;
        if (cleanedMap.isEmpty()) return null;

        String intentName = response.getIntentName() != null
                ? response.getIntentName() : "工作台";

        StringBuilder sb = new StringBuilder();
        sb.append(intentName).append(header).append("\n\n");

        for (Map.Entry<String, Object> e : cleanedMap.entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            sb.append("- ").append(toChineseLabel(key)).append(": ");
            sb.append(describeLeaf(value));
            sb.append("\n");
            // 工具结果里有条目列表 (客户 / 记录 / 商机 / 月度): 列出前几条, 比「N 个字段」有用。
            // 本地拼的文字, 不发给模型, 可以用真名。
            for (String line : itemLines(value)) {
                sb.append("  · ").append(line).append("\n");
            }
        }
        sb.append("\n详细数据请在对应工作台模块查看 (批次放行 / 质检 / HACCP 监控 / 库存 / 采购 / 客户跟进).\n");

        String result = sb.toString();
        if (result.length() > maxChars) {
            result = result.substring(0, maxChars) + "...";
        }
        return result;
    }

    /**
     * Sprint 12: map composite tool-result keys → Chinese business labels so the
     * deterministic fallback (used when LLM is rate-limited) carries domain keywords
     * (批次 / HACCP / 质检 / 库存 / 采购 / 客户) instead of raw English keys. Improves
     * degraded-mode UX and lets the structured fallback pass strict content checks.
     */
    private String toChineseLabel(String key) {
        if (key == null) return "数据项";
        String k = key.toLowerCase();
        // 先判更具体的 (customer_revenue_trend 里也有 customer, opportunity_stage_alert 里也有 alert)
        if (k.contains("wechat")) return "微信沟通记录";
        if (k.contains("call_record") || k.startsWith("call_")) return "通话待跟进";
        if (k.contains("opportunit")) return "商机阶段提醒";
        if (k.contains("revenue_trend") || k.contains("trend")) return "销售趋势";
        if (k.contains("customer_priority")) return "重点客户";
        if (k.contains("quality_check") || k.contains("inspection")) return "质检汇总";
        if (k.contains("haccp")) return "HACCP 监控状态";
        if (k.contains("additive")) return "添加剂合规检查";
        if (k.contains("customer_quality")) return "客户质量标准";
        if (k.contains("batch") || k.contains("release") || k.contains("pending_release")) return "待放行批次";
        if (k.contains("inventory") || k.contains("stock")) return "库存情况";
        if (k.contains("inbound") || k.contains("receive")) return "入库 / 收货";
        if (k.contains("purchase") || k.contains("procurement") || k.contains("supplier")) return "采购 / 供应商";
        if (k.contains("customer") || k.contains("followup")) return "客户跟进";
        if (k.contains("revenue") || k.contains("finance") || k.contains("financial")) return "财务 / 营收";
        if (k.contains("alert") || k.contains("warning")) return "告警";
        if (k.contains("haccp")) return "HACCP";
        // Fallback: humanize underscores so output stays readable.
        return key.replace('_', ' ');
    }

    private static final int FALLBACK_ITEMS = 5;
    private static final String[] ITEM_NAME_KEYS = {
            "customerName", "name", "title", "productName", "materialName", "supplierName", "month"};
    private static final String[][] ITEM_NUMBER_LABELS = {
            {"priorityScore", "评分"}, {"totalAmount", "金额"}, {"valueAmount", "金额"}, {"orderCount", "订单数"},
            {"activeOpportunityCount", "活跃商机"}, {"stagnantDays", "停滞天数"}, {"daysAgo", "天前"},
            {"quantity", "数量"}, {"stock", "库存"}, {"probability", "成交概率"}};

    /** 工具结果 (Map) 里第一张条目列表的前几条, 每条一行: 名称 + 联系人 + 状态文字 + 带中文标签的数字。 */
    @SuppressWarnings("unchecked")
    List<String> itemLines(Object value) {
        List<String> lines = new java.util.ArrayList<>();
        List<Object> items = firstItemList(value, 0);
        if (items == null) {
            return lines;
        }
        for (Object item : items) {
            if (lines.size() >= FALLBACK_ITEMS) {
                lines.add("…等共 " + items.size() + " 项");
                break;
            }
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> m = (Map<String, Object>) item;
            String label = null;
            for (String k : ITEM_NAME_KEYS) {
                if (m.get(k) instanceof String s && !s.isBlank()) {
                    label = s;
                    break;
                }
            }
            if (label == null) {
                continue;
            }
            List<String> details = new java.util.ArrayList<>();
            if (m.get("contactPerson") instanceof String cp && !cp.isBlank()) {
                details.add("联系人 " + cp);
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getKey().endsWith("Display") && e.getValue() instanceof String s && !s.isBlank()) {
                    details.add(s);
                }
            }
            for (String[] pair : ITEM_NUMBER_LABELS) {
                if (m.get(pair[0]) instanceof Number n) {
                    details.add(pair[1] + " " + n);
                }
            }
            lines.add(details.isEmpty() ? label : label + "（" + String.join("，", details) + "）");
        }
        return lines;
    }

    /** 深度 ≤ 2 的第一张「元素是 Map」的列表 (工具结果常包一层 data)。 */
    @SuppressWarnings("unchecked")
    private List<Object> firstItemList(Object value, int depth) {
        if (depth > 2 || !(value instanceof Map)) {
            return null;
        }
        for (Object v : ((Map<String, Object>) value).values()) {
            if (v instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map) {
                return (List<Object>) list;
            }
        }
        for (Object v : ((Map<String, Object>) value).values()) {
            List<Object> nested = firstItemList(v, depth + 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    /**
     * Render a leaf value as a short Chinese description (count / size / scalar),
     * never JSON-dumping nested structure.
     */
    @SuppressWarnings("unchecked")
    private String describeLeaf(Object value) {
        if (value == null) return "无数据";
        if (value instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) value;
            // 工具自己的中文说明 (含 data 包一层的情况) 比「N 个字段」有用
            Object msg = m.get("message");
            if (!(msg instanceof String) && m.get("data") instanceof Map<?, ?> data) {
                msg = data.get("message");
            }
            if (msg instanceof String s && !s.isBlank() && !isDirty(s)) {
                return s.length() > 100 ? s.substring(0, 100) + "..." : s;
            }
            List<Object> items = firstItemList(m, 0);
            if (items != null) {
                Object count = m.get("count");
                if (count == null && m.get("data") instanceof Map<?, ?> data) {
                    count = data.get("count");
                }
                return "共 " + (count instanceof Number ? count : items.size()) + " 项";
            }
            // Look for common count fields
            for (String countKey : new String[]{"count", "total", "size", "totalCount", "rowCount"}) {
                if (m.containsKey(countKey)) {
                    Object cv = m.get(countKey);
                    if (cv == null) continue;
                    // 金额类 total (611355.40) 不是条目数, 原样给数, 不加「项」
                    boolean whole = !(cv instanceof Number n) || n.doubleValue() == Math.rint(n.doubleValue());
                    return whole ? cv + " 项" : cv.toString();
                }
            }
            // 「N 个字段」对用户没有意义
            return "无可列出的明细";
        }
        if (value instanceof Iterable) {
            int n = 0;
            for (Object ignored : (Iterable<?>) value) n++;
            return n + " 项";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        if (value instanceof Boolean) {
            return ((Boolean) value) ? "是" : "否";
        }
        String s = value.toString();
        if (s.length() > 100) s = s.substring(0, 100) + "...";
        return s;
    }

    /**
     * Sprint 12 last-resort cleaner — strips raw-JSON / underscore-key fragments
     * so the user never sees metadata leaks even if both LLM and deterministic
     * fallback failed to produce clean text. Removes JSON-object fragments and
     * underscore-prefixed key:value pairs, collapses whitespace, and if nothing
     * usable remains returns a safe generic Chinese message.
     */
    String hardStripDirty(String text) {
        if (text == null) return null;
        String cleaned = text
                // drop "_key": value, fragments (underscore metadata)
                .replaceAll("\"_[a-zA-Z][a-zA-Z0-9]*\"\\s*:\\s*[^,}\\n]+,?", "")
                // drop residual JSON braces/brackets
                .replaceAll("[{}\\[\\]]", "")
                // drop bare "key": leftovers
                .replaceAll("\"[a-zA-Z_][a-zA-Z0-9_]*\"\\s*:", "")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.length() < MIN_USEFUL_CHARS || isDirty(cleaned)) {
            return "本次查询已完成，但结果格式化遇到问题。建议: 1. 重新发起查询; 2. 换用更具体的描述 (指定时段 / 物料 / 客户); 3. 如持续出现请联系管理员检查该功能配置。";
        }
        return cleaned;
    }

    static boolean isSkillNoDisplayPlaceholder(String text) {
        return text != null && text.trim().equals(DynamicToolSelectionService.SKILL_NO_DISPLAY_PLACEHOLDER);
    }

    /**
     * Returns true when the text contains underscore-prefixed metadata keys,
     * is a bare template, or is a raw JSON dump.
     */
    boolean isDirty(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 1. underscore-prefixed metadata key leak
        if (UNDERSCORE_KEY_PATTERN.matcher(text).find()) {
            return true;
        }
        // 2. bare template "查询完成\n包含 N 项数据指标"
        if (BARE_TEMPLATE_PATTERN.matcher(text).find()) {
            return true;
        }
        // 3. raw JSON brace-heavy dump (>5% braces, no useful Chinese narrative)
        long braceCount = text.chars().filter(c -> c == '{' || c == '}').count();
        double ratio = (double) braceCount / text.length();
        if (ratio > JSON_BRACE_RATIO_THRESHOLD) {
            return true;
        }
        // 4. unhelpfully short (mostly empty/template)
        if (text.length() < MIN_USEFUL_CHARS && text.contains("包含") && text.contains("项数据指标")) {
            return true;
        }
        return false;
    }

    /** text = 模型摘要 (已换回真名), null = 没拿到; privacyRefused = 脱敏不了, 根本没发。 */
    record Attempt(String text, boolean privacyRefused) {
        static final Attempt NONE = new Attempt(null, false);
        static final Attempt REFUSED = new Attempt(null, true);
    }

    /**
     * Call LLM to summarize resultData into a Chinese natural-language summary.
     * 发出前先经 {@link LlmEgressRedactor} 脱敏; 脱敏不了、或查保密产品名出错, 就不发。
     * factoryId 为 null (没有工厂上下文) 时没有「本厂保密产品」可查, 只做通用脱敏。
     */
    private Attempt tryLlmSummarize(Object resultData, IntentExecuteResponse response, String factoryId) {
        if (resultData == null) {
            return Attempt.NONE;
        }
        LlmEgressRedactor.Result redacted;
        try {
            Set<String> protectedNames = processSecretSampleFilter == null || factoryId == null
                    ? Set.of() : processSecretSampleFilter.protectedProductNames(factoryId);
            redacted = new LlmEgressRedactor(objectMapper).redact(stripUnderscoreKeys(resultData), protectedNames);
        } catch (Exception e) {
            log.warn("WorkdeskOutputSummarizer: redaction failed ({}), not sending to LLM", e.getClass().getSimpleName());
            return Attempt.REFUSED;
        }
        if (redacted.refused()) {
            log.warn("WorkdeskOutputSummarizer: redaction refused ({}), not sending to LLM", redacted.refusal());
            return Attempt.REFUSED;
        }
        log.info("WorkdeskOutputSummarizer: redacted {} name(s), dropped {} field(s) before LLM",
                redacted.tokenCount(), redacted.droppedFields());
        try {
            String payloadJson = redacted.payload();
            if (payloadJson == null || payloadJson.length() < 5) {
                return Attempt.NONE;
            }
            if (payloadJson.length() > MAX_SUMMARIZE_BUDGET_CHARS) {
                // truncate to keep LLM call fast (Workdesk skill rich payloads OK to clip tail)
                payloadJson = payloadJson.substring(0, MAX_SUMMARIZE_BUDGET_CHARS) + " ...(数据已截断)";
            }

            String intentName = response.getIntentName() != null
                    ? response.getIntentName() : "工作台";
            // 工艺保密系统约束 (有保密产品的工厂): 保密产品的数据只用于回答当前用户, 不写进可共享 / 复用的内容
            String systemPrompt = buildSystemPrompt(intentName) + (factoryId == null ? "" : llmCostScope.harnessFor(factoryId));
            String userInput = "以下是 " + intentName + " 编排执行的工具返回数据 (JSON), 请按 system 提示输出摘要:\n\n"
                    + payloadJson;

            String llmResult;
            try {
                llmResult = dashScopeClient.chatFast(systemPrompt, userInput);
            } catch (Exception llmEx) {
                log.warn("WorkdeskOutputSummarizer: LLM call failed: {}", llmEx.getMessage());
                return Attempt.NONE;
            }
            if (llmResult == null || llmResult.isBlank()) {
                return Attempt.NONE;
            }
            llmResult = redacted.restore(llmResult.trim());
            if (llmResult.length() > maxChars) {
                llmResult = llmResult.substring(0, maxChars) + "...";
            }
            return new Attempt(llmResult, false);
        } catch (Exception e) {
            log.warn("WorkdeskOutputSummarizer: summarize failed: {}", e.getMessage());
            return Attempt.NONE;
        }
    }

    /**
     * Build the cleaned JSON payload by recursively dropping underscore-prefixed keys
     * (e.g. {@code _toolCount}, {@code _executionOrder}, {@code _reasoning}).
     */
    @SuppressWarnings("unchecked")
    String buildCleanPayload(Object resultData) throws JsonProcessingException {
        Object cleaned = stripUnderscoreKeys(resultData);
        return objectMapper.writeValueAsString(cleaned);
    }

    /**
     * Recursively strip underscore-prefixed keys from Maps. Lists and primitives passed through.
     */
    @SuppressWarnings("unchecked")
    Object stripUnderscoreKeys(Object obj) {
        if (obj instanceof Map) {
            Map<String, Object> src = (Map<String, Object>) obj;
            Map<String, Object> dst = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : src.entrySet()) {
                String k = e.getKey();
                if (k != null && k.startsWith("_")) {
                    continue;
                }
                dst.put(k, stripUnderscoreKeys(e.getValue()));
            }
            return dst;
        } else if (obj instanceof Iterable) {
            java.util.List<Object> dst = new java.util.ArrayList<>();
            for (Object item : (Iterable<?>) obj) {
                dst.add(stripUnderscoreKeys(item));
            }
            return dst;
        }
        return obj;
    }

    private String buildSystemPrompt(String intentName) {
        return "你是 Cretas 食品溯源系统的助手, 帮 " + intentName + " 用户解读工作台编排数据.\n\n"
                + "要求:\n"
                + "1. 用简洁的中文 (300-600 字) 概括关键信息, 包含具体数字 / 名称 / 阈值告警.\n"
                + "2. 用 markdown 结构: 短标题 + 要点列表. 不要输出 JSON 原文.\n"
                + "3. 对每个 tool 返回的关键指标都要提及 (人, 客户, 批次, 金额, 数量, 库存, 告警等).\n"
                + "4. 若数据为空或全 0, 明确说明 '当前暂无 XX' 而非编造.\n"
                + "5. 若发现风险 (库存预警 / 应收过期 / 合规问题 / 告警), 在末尾列 '风险提示' 段落 (不要用 emoji / 装饰符号).\n"
                + "6. 不要输出 _toolCount / _executionOrder / 内部 metadata 字段.\n"
                + "7. 不要使用任何 emoji 或 Unicode 装饰符号 (B 端用户偏好纯文本).\n"
                + "8. 输出不超过 800 字.\n"
                // 与数字出处闸同一条边界: 闸不认算术派生, 这里就不让模型去算 ——
                // 否则算对了的占比也会被闸换成结构化摘要。
                + "9. 只能引用数据里已经出现的数字, 不要自行计算合计 / 占比 / 环比 / 同比, 也不要估算; "
                + "也不要自己按状态 / 类型去数「有几个」(数据里给了 count 才写个数, 没给就列名称).\n"
                + "10. 数据里方括号代号 (如 [客户A] [人员B] [保密产品A]) 是脱敏占位, 原样照抄, 不要改写, 也不要猜真实名称.\n";
    }
}
