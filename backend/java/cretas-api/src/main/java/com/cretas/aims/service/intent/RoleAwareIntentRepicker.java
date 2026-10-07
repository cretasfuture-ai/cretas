package com.cretas.aims.service.intent;

import com.cretas.aims.ai.tool.BusinessTypeScope;
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
 *   <li>意图配置里写着的说法 ({@code keywords}, ≥ 3 字) 原样出现在这句话里 —— 配置者亲手登记的证据, 长的优先</li>
 *   <li>语义近邻 (短语向量, 相似度 ≥ minScore)</li>
 * </ol>
 * 2、3 两类是本类自己找的证据, 必须带「话题」({@link #topicOf}: 原话与被拒意图共有的片段), 只像句式的近邻
 * (「查一下出货」之于「查一下库存」) 进不来; 换上来的意图还必须与本厂业态兼容 ({@link BusinessTypeScope})。
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

    /**
     * 说法证据的最短长度。两个字的词只说了话题、没说要干什么: 本机 F006 副本实测「智能指标分析」
     * (SMART_INDICATOR_QUERY, 所有角色可用) 登记了裸词「库存」, 按 2 字算时仓库员「看下库存」、操作员「查库存」
     * 全被换成它, 回一句「未返回可直接展示的明细数据」—— 比如实的「没有权限」更糟。三个字起才算一个说法 (「查库存」)。
     */
    static final int MIN_KEYWORD_LENGTH = 3;

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

        Set<String> topic = topicOf(userInput, best);
        Map<String, String> pool = candidatePool(result, userInput, factoryId, topic);
        log.info("[RoleRepick] role={} 不能用 {}, 话题 {}, 候选池 {} 个: {}", userRole, best.getIntentCode(), topic,
                pool.size(), pool.entrySet().stream().limit(8).map(e -> e.getKey() + "<" + e.getValue() + ">").toList());
        String input = userInput.toLowerCase(Locale.ROOT);
        String businessDomain = businessDomainOf(factoryId);
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
            // 业态隔离: 短语向量库里工厂与餐饮短语混在一起 (本机实测「看下库存」最近的是餐饮的「看看库存」)
            if (!BusinessTypeScope.isCompatible(candidate.getBusinessType(), businessDomain)
                    || accessModeFilter.isWriteIntent(candidate)
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

    /**
     * 「这句话问的是什么东西」: 用户原话与被拒意图 (名称 + 登记说法) 共有的二字片段。
     *
     * <p>为什么要它: 短语向量的近邻里混着<b>只像句式、不像话题</b>的 —— 本机实测「查一下库存」的近邻里
     * 「查一下出货」(出货查询) 0.85、「查一下供应商」0.84, 与真正同话题的「睇下库存」(原料批次查询) 0.86 挤在一起,
     * 按分数挑会把库存问题换成出货查询。要求换上来的证据 (说法 / 近邻短语) 至少带一个话题片段, 句式近邻就进不来。
     * 话题取「原话 ∩ 被拒意图」而不是写一张停用词表: 被拒意图之所以被选中, 就是因为它和原话说的是同一样东西。
     */
    static Set<String> topicOf(String userInput, AIIntentConfig denied) {
        StringBuilder text = new StringBuilder();
        if (denied.getIntentName() != null) {
            text.append(denied.getIntentName()).append(' ');
        }
        for (String kw : safeKeywords(denied)) {
            if (kw != null) {
                text.append(kw).append(' ');
            }
        }
        Set<String> topic = new LinkedHashSet<>(bigrams(userInput));
        topic.retainAll(bigrams(text.toString()));
        return topic;
    }

    /** 去空白后的相邻二字片段 (小写)。中文按字, 英文按字母 —— 两边同一种切法即可。 */
    static Set<String> bigrams(String s) {
        Set<String> out = new LinkedHashSet<>();
        if (s == null) {
            return out;
        }
        String[] parts = s.toLowerCase(Locale.ROOT).split("\\s+");
        for (String p : parts) {
            for (int i = 0; i + 2 <= p.length(); i++) {
                out.add(p.substring(i, i + 2));
            }
        }
        return out;
    }

    private static boolean carriesTopic(String evidence, Set<String> topic) {
        if (topic.isEmpty() || evidence == null) {
            return false;
        }
        for (String b : bigrams(evidence)) {
            if (topic.contains(b)) {
                return true;
            }
        }
        return false;
    }

    private String businessDomainOf(String factoryId) {
        try {
            return configService.resolveBusinessDomain(factoryId);
        } catch (Exception e) {
            return null;  // isCompatible(null 业态) 按工厂处理: 只排除餐饮意图
        }
    }

    /**
     * 候选池: 意图码 → 证据来源 (按优先级插入, 先到先得)。
     * 识别候选是排序器的输出, 直接收; 说法与语义近邻是本类自己找的证据, 必须带话题 ({@link #topicOf})。
     */
    Map<String, String> candidatePool(IntentMatchResult result, String userInput, String factoryId,
                                      Set<String> topic) {
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

        if (topic.isEmpty()) {
            return pool;  // 说不清这句话问的是什么, 就不自己找证据
        }

        for (Map.Entry<String, String> hit : keywordHits(userInput, factoryId)) {
            if (carriesTopic(hit.getValue(), topic)) {
                pool.putIfAbsent(hit.getKey(), "keyword:" + hit.getValue());
            }
        }

        for (SemanticMatchResult s : semanticNeighbours(userInput)) {
            if (carriesTopic(s.getMatchedPhrase(), topic)) {
                pool.putIfAbsent(s.getIntentCode(),
                        "semantic:" + s.getMatchedPhrase() + "@" + String.format("%.2f", s.getSimilarity()));
            }
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
