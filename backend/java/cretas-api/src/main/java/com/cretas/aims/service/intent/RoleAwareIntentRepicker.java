package com.cretas.aims.service.intent;

import com.cretas.aims.dto.SemanticMatchResult;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.SemanticIntentMatcher;
import com.cretas.aims.service.execution.IntentPermissionGate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 识别结果按「这个人能不能用」重选 (2026-10-07)。
 *
 * <p><b>缺陷</b>: 仓库员说「查库存」, 精确短语表 ({@code IntentKnowledgeBase} 的 {@code 查库存 / 库存情况 /
 * 库存 → REPORT_INVENTORY}) 直接选中库存报表, 而库存报表只开放给 factory_super_admin / department_admin
 * (App 里要财务权限, 2026-10-05 有意不给仓库员), 于是执行门回「您没有权限执行此操作」。同一句话他本来能办 ——
 * 原料批次查询 (MATERIAL_BATCH_QUERY) 对他开放, 它的意图配置里甚至就写着「查库存」这个说法。
 *
 * <p><b>做法</b>: 识别完 (Python 决策层 / 识别缓存 / Java 旧管线, 三个来源都过这里) 如果选中的意图
 * <b>执行门会拒绝这个人</b>, 就在<b>这个人能用的读意图</b>里按同一句话的证据重选一个:
 * <ol>
 *   <li>识别自己给出的候选 ({@code topCandidates}, 置信度 ≥ minScore) —— 识别层最完整的排序</li>
 *   <li>意图配置里写着的说法 ({@code keywords}) 原样出现在这句话里 —— 配置者亲手登记的证据, 长的优先</li>
 *   <li>语义近邻 (短语向量, 相似度 ≥ minScore)</li>
 * </ol>
 * 找不到就原样返回, 执行门照旧给出「没有权限」—— 本类从不把「拒绝」变成「放行」。
 *
 * <p><b>判「能不能用」= 执行门本身</b>: 与 {@code SseStreamingService#permissionDeniedMessage} /
 * {@code IntentExecutionOrchestrator#checkIntentPermission} 同一个判定 —— 配了权限码走
 * {@link IntentPermissionGate#check}, 没配走 {@link IntentConfigManagementService#hasPermission(String, String)}
 * 的 requiredRoles。权限原则「AI 能做的 = App 里本来就能做的」; 本类不新造权限来源。
 *
 * <p><b>只换读, 不换写</b>: 被拒的是写意图 (「调库存」) 时不换 —— 把「改库存」换成「查库存」等于替用户改了主意;
 * 换上来的也必须是读意图 (声明优先的 {@link IntentAccessModeFilter#isWriteIntent}), 写操作永远不会被这里「顺带」选中。
 *
 * <p><b>为什么不在识别管线里逐层过滤</b>: Java 管线有十来个各自短路返回的选择点 (早期短语、动宾消歧、尾句短语、
 * 合成短语、语义、融合、LLM …), 另有 Python 决策层与按角色缓存的识别结果; 逐层加过滤要改十几处且 Python 不在本次范围。
 * 在门面 ({@code AIIntentServiceImpl}) 的出口统一做一次, 三个来源都覆盖, 被守的行为只有一处。
 * 管理员等有权限的人<b>完全不经过重选</b> (选中的意图不被拒就原样返回)。
 */
@Slf4j
@Component
public class RoleAwareIntentRepicker {

    /** 写进 {@code timingMs} 的标记 (1 = 这次结果是重选出来的), 供日志/读数区分来源。 */
    public static final String REPICK_MARKER = "roleRepick";

    /** 语义近邻取多少个短语 (按短语算, 同一意图多个短语会去重)。 */
    static final int SEMANTIC_TOP_K = 20;

    /** 说法证据的最短长度: 单字 (「库」「查」) 不算证据。 */
    static final int MIN_KEYWORD_LENGTH = 2;

    private final IntentConfigManagementService configService;
    private final IntentAccessModeFilter accessModeFilter;

    /**
     * {@code @Lazy}: 权限门 / 语义匹配器都在 AI 装配图的环上 (见 ai-intent-tool-skill-architecture 规则第 2 条)。
     * 权限门缺失 ⇒ 配了权限码的意图一律按「拒绝」判 (fail-closed); 语义匹配器缺失 / embedding 不可用 ⇒
     * 按「没有语义证据」处理。
     */
    @Autowired(required = false)
    @Lazy
    private IntentPermissionGate permissionGate;

    @Autowired(required = false)
    @Lazy
    private SemanticIntentMatcher semanticMatcher;

    @Value("${cretas.ai.role-repick.enabled:true}")
    private boolean enabled = true;

    /** 候选置信度 / 语义相似度的下限; 与语义匹配默认阈值同口径。 */
    @Value("${cretas.ai.role-repick.min-score:0.75}")
    private double minScore = 0.75;

    @Autowired
    public RoleAwareIntentRepicker(IntentConfigManagementService configService,
                                   IntentAccessModeFilter accessModeFilter) {
        this.configService = configService;
        this.accessModeFilter = accessModeFilter;
    }

    /**
     * @return 原对象 (没换) 或一个换了 bestMatch 的<b>副本</b> —— 原对象可能正躺在识别缓存里, 不能就地改。
     */
    public IntentMatchResult repick(IntentMatchResult result, String userInput, String factoryId,
                                    Long userId, String userRole) {
        if (!enabled || result == null || result.getBestMatch() == null
                || userRole == null || userRole.isBlank() || userInput == null || userInput.isBlank()) {
            return result;
        }
        AIIntentConfig best = result.getBestMatch();
        if (!deniedByExecutionGate(best, userId, userRole)) {
            return result;
        }
        if (accessModeFilter.isWriteIntent(best)) {
            log.debug("[RoleRepick] 被拒的是写意图, 不换: role={}, intent={}", userRole, best.getIntentCode());
            return result;
        }

        Map<String, String> pool = candidatePool(result, userInput, factoryId);
        String input = userInput.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : pool.entrySet()) {
            String code = entry.getKey();
            if (code.equals(best.getIntentCode())) {
                continue;
            }
            Optional<AIIntentConfig> cfg = resolve(factoryId, code);
            if (cfg.isEmpty() || !Boolean.TRUE.equals(cfg.get().getIsActive())) {
                continue;
            }
            AIIntentConfig candidate = cfg.get();
            if (accessModeFilter.isWriteIntent(candidate)
                    || deniedByExecutionGate(candidate, userId, userRole)
                    || hitsNegativeKeyword(candidate, input)) {
                continue;
            }
            log.info("[RoleRepick] role={} 不能用 {} → 改选 {} (证据: {}), input='{}'",
                    userRole, best.getIntentCode(), code, entry.getValue(),
                    userInput.length() <= 40 ? userInput : userInput.substring(0, 40));
            Map<String, Long> timing = new LinkedHashMap<>();
            if (result.getTimingMs() != null) {
                timing.putAll(result.getTimingMs());
            }
            timing.put(REPICK_MARKER, 1L);
            return result.toBuilder().bestMatch(candidate).timingMs(timing).build();
        }
        log.info("[RoleRepick] role={} 不能用 {}, 也没有它能用的读意图可选 (候选 {} 个), 交给执行门拒绝",
                userRole, best.getIntentCode(), pool.size());
        return result;
    }

    /**
     * 执行门会不会拒绝这个人。与 SSE / {@code /execute} 两个入口的意图权限判定逐字同源:
     * 权限码 → {@link IntentPermissionGate#check} (fail-closed); 无权限码 → requiredRoles。
     * 判定本身出错按「拒绝」处理: 拿不准的意图既不该被换掉 (调用方再看一次), 也不该被换上来。
     */
    boolean deniedByExecutionGate(AIIntentConfig intent, Long userId, String userRole) {
        try {
            String requiredPermission = intent.getRequiredPermission();
            if (requiredPermission != null && !requiredPermission.isBlank()) {
                return permissionGate == null || permissionGate.check(intent, userId, userRole).isDenied();
            }
            return !configService.hasPermission(intent.getIntentCode(), userRole);
        } catch (Exception e) {
            log.warn("[RoleRepick] 权限判定异常, 按拒绝处理: intent={}, err={}", intent.getIntentCode(), e.getMessage());
            return true;
        }
    }

    /** 候选池: 意图码 → 证据来源 (按优先级插入, 先到先得)。 */
    Map<String, String> candidatePool(IntentMatchResult result, String userInput, String factoryId) {
        Map<String, String> pool = new LinkedHashMap<>();

        List<IntentMatchResult.CandidateIntent> candidates = result.getTopCandidates();
        if (candidates != null) {
            for (IntentMatchResult.CandidateIntent c : candidates) {
                if (c != null && c.getIntentCode() != null && c.getConfidence() != null
                        && c.getConfidence() >= minScore) {
                    pool.putIfAbsent(c.getIntentCode(), "candidate@" + String.format("%.2f", c.getConfidence()));
                }
            }
        }

        for (Map.Entry<String, String> hit : keywordHits(userInput, factoryId)) {
            pool.putIfAbsent(hit.getKey(), "keyword:" + hit.getValue());
        }

        for (SemanticMatchResult s : semanticNeighbours(userInput)) {
            pool.putIfAbsent(s.getIntentCode(),
                    "semantic:" + s.getMatchedPhrase() + "@" + String.format("%.2f", s.getSimilarity()));
        }
        return pool;
    }

    /** 意图配置里登记的说法原样出现在这句话里: (意图码, 命中的最长说法), 长说法优先、同长按优先级。 */
    private List<Map.Entry<String, String>> keywordHits(String userInput, String factoryId) {
        if (factoryId == null || factoryId.isBlank()) {
            return List.of();
        }
        String input = userInput.toLowerCase(Locale.ROOT);
        List<AIIntentConfig> intents;
        try {
            intents = configService.getAllIntents(factoryId);
        } catch (Exception e) {
            log.warn("[RoleRepick] 读意图目录失败, 跳过说法证据: {}", e.getMessage());
            return List.of();
        }
        record Hit(String code, String keyword, int priority) { }
        List<Hit> hits = new ArrayList<>();
        for (AIIntentConfig intent : intents) {
            if (intent == null || intent.getIntentCode() == null || !Boolean.TRUE.equals(intent.getIsActive())) {
                continue;
            }
            String longest = null;
            for (String kw : safeKeywords(intent)) {
                String k = kw == null ? "" : kw.trim().toLowerCase(Locale.ROOT);
                if (k.length() >= MIN_KEYWORD_LENGTH && input.contains(k)
                        && (longest == null || k.length() > longest.length())) {
                    longest = k;
                }
            }
            if (longest != null) {
                hits.add(new Hit(intent.getIntentCode(), longest,
                        intent.getPriority() == null ? 0 : intent.getPriority()));
            }
        }
        hits.sort(Comparator.comparingInt((Hit h) -> h.keyword().length()).reversed()
                .thenComparing(Comparator.comparingInt(Hit::priority).reversed()));
        List<Map.Entry<String, String>> out = new ArrayList<>(hits.size());
        for (Hit h : hits) {
            out.add(Map.entry(h.code(), h.keyword()));
        }
        return out;
    }

    private List<SemanticMatchResult> semanticNeighbours(String userInput) {
        if (semanticMatcher == null) {
            return List.of();
        }
        try {
            if (!semanticMatcher.isSemanticMatchingAvailable()) {
                return List.of();
            }
            List<SemanticMatchResult> top = semanticMatcher.findTopKMatches(userInput, SEMANTIC_TOP_K, minScore);
            if (top == null) {
                return List.of();
            }
            List<SemanticMatchResult> out = new ArrayList<>();
            Set<String> seen = new LinkedHashSet<>();
            for (SemanticMatchResult s : top) {
                if (s != null && s.getIntentCode() != null && seen.add(s.getIntentCode())) {
                    out.add(s);
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("[RoleRepick] 语义近邻失败, 按没有语义证据处理: {}", e.getMessage());
            return List.of();
        }
    }

    private Optional<AIIntentConfig> resolve(String factoryId, String code) {
        try {
            return factoryId != null && !factoryId.isBlank()
                    ? configService.getIntentByCode(factoryId, code)
                    : configService.getIntentByCode(code);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static boolean hitsNegativeKeyword(AIIntentConfig intent, String lowerInput) {
        try {
            for (String neg : intent.getNegativeKeywordsList()) {
                String n = neg == null ? "" : neg.trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty() && lowerInput.contains(n)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 解析不了反向词 ≠ 命中反向词
        }
        return false;
    }

    private static List<String> safeKeywords(AIIntentConfig intent) {
        try {
            List<String> kws = intent.getKeywordsList();
            return kws == null ? List.of() : kws;
        } catch (Exception e) {
            return List.of();
        }
    }
}
