package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.service.intent.IntentAccessModeFilter;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.IntentValidationFact;
import com.cretas.aims.dto.intent.ValidationResult;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.intent.IntentMatchRecord;
import com.cretas.aims.exception.LlmSchemaValidationException;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.IntentMatchRecordRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.*;
import com.cretas.aims.service.calibration.BehaviorCalibrationService;
import com.cretas.aims.service.consent.LlmEgressRedactor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 意图执行编排器
 *
 * 核心 execute() 路由方法 -- 编排缓存检查、意图识别、
 * 权限/审批/规则验证、Tool/Skill/动态选择分发、
 * 结果格式化和缓存回写。
 *
 * 此类是 "指挥官"，调用其余 4 个子服务完成实际工作。
 */
@Slf4j
@Service
public class IntentExecutionOrchestrator {

    @Autowired
    private com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService controlledPurchase;
    @Autowired(required = false)
    private FactoryFollowUpService factoryFollowUpService;
    /** 识别用最近对话的唯一来源 (与 SSE 入口共用)。单测直接构造时缺席, 退回只读 context.recentTurns。 */
    @Autowired(required = false)
    private RecognitionHistoryResolver recognitionHistoryResolver;
    /**
     * AI 数据访问审计: 一次意图执行一条; 期间的工具调用 / 成本分析并进这一条 (同一线程, 请求外也合并)。
     * 单测直接构造时不记录。
     */
    @Autowired(required = false)
    private com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit aiAccessAudit =
            com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.disabled();

    private static final Pattern COMMAND_DIGEST_PATTERN = Pattern.compile("^[0-9a-f]{64}$");

    // ===== 依赖 =====
    private final AIIntentService aiIntentService;
    private final IntentSemanticsParser semanticsParser;
    private final SemanticCacheService semanticCacheService;
    private final RuleEngineService ruleEngineService;
    private final ConversationService conversationService;
    private final ConversationMemoryService conversationMemoryService;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
    private final DashScopeConfig dashScopeConfig;
    private final IntentKnowledgeBase knowledgeBase;
    private final AIAnalysisResultRepository analysisResultRepository;
    private final ToolRegistry toolRegistry;
    private final AnalysisRouterService analysisRouterService;
    private final ComplexityRouter complexityRouter;
    private final AgentOrchestrator agentOrchestrator;
    private final AgenticRAGRouterService agenticRAGRouterService;
    private final ResultValidatorService resultValidatorService;

    // Sub-services
    private final ToolDispatchService toolDispatchService;
    private final DynamicToolSelectionService dynamicToolSelectionService;

    // W1b negation veto (intent-w1b): early VETO gate in the execution layer. Low-level service
    // (QueryPreprocessorServiceImpl has no dependency back on the orchestrator), so safe to inject
    // via the constructor — no circular dependency.
    private final QueryPreprocessorService queryPreprocessorService;

    /** 发给模型前脱敏; 单测直接 new 本类时用默认实例 (不查保密产品名)。 */
    @Autowired
    private com.cretas.aims.service.consent.LlmEgressGuard llmEgressGuard =
            com.cretas.aims.service.consent.LlmEgressGuard.standalone();

    static final String CONVERSATIONAL_EGRESS_SITE = "conversational-reply";

    /**
     * 工艺保密: 存档的日 / 周 / 月报是全厂共享的产物 (经营报告快照含保密产品的毛利 / 成本; 旧文本也留 90 天),
     * 当前账号对本厂某个保密产品无完整查看权时不拼进咨询提示词; 负责人 / 有效授权照常带 (Steve 10-29)。
     * 单测直接 new 本类时用「没有保密产品」的默认实例。
     */
    @Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope llmCostScope = com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.standalone();

    @Autowired(required = false)
    private ResultFormatterService resultFormatterService;

    @Autowired(required = false)
    private WorkdeskOutputSummarizer workdeskOutputSummarizer;

    /** 数字出处闸 (带经营报告上下文的咨询回答)。默认实例保证裸构造的单测也走闸。 */
    @Autowired(required = false)
    private com.cretas.aims.ai.grounding.FactoryNumberProvenance numberProvenance =
            new com.cretas.aims.ai.grounding.FactoryNumberProvenance();

    static final String CONVERSATIONAL_PROVENANCE_SITE = "conversational-with-report";

    @Autowired(required = false)
    private PreviewTokenService previewTokenService;

    @Autowired(required = false)
    private ProductTypeRepository productTypeRepository;

    @Autowired(required = false)
    private IntentMatchRecordRepository intentMatchRecordRepository;

    @Autowired(required = false)
    private SlotFillingService slotFillingService;

    @Autowired(required = false)
    private com.cretas.aims.config.IntentSlotConfiguration intentSlotConfiguration;

    // Sprint 13 #305 业态门控: shared business-type gate (RESTAURANT vs FACTORY). Reused by the
    // explicit-intent flow + SseStreamingService so all paths gate identically.
    @Autowired
    private BusinessTypeGate businessTypeGate;

    @Autowired
    private ToolExecutionGateway toolExecutionGateway;

    @Autowired
    private AuthenticatedToolPrincipalFactory authenticatedToolPrincipalFactory;

    /** Default-off boundary for the version-controlled factory capability packs. */
    @Autowired
    private FactoryCapabilityPackRoutingPolicy factoryCapabilityPackRoutingPolicy;

    // W0 write-guard (intent-w0): confidence-independent write detection. Inserted at the
    // explicit-intent convergence point so a misroute to a write intent cannot silently execute,
    // INCLUDING via forceExecute=true (multi-intent / conversation-continuation hard-set true).
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // spec §8.2 消费点①: 识别层候选过滤 + 声明优先的写意图判定 (绑定 Tool 的 getAccessMode
    // 声明 → 意图启发式)。required=false: 裸构造的单测不提供时回落纯 writeGuardService 判定。
    @Autowired(required = false)
    private IntentAccessModeFilter intentAccessModeFilter;

    // 旧出口计数 (INTENT-DISTILL-20261111): 短语直达不经决策模型定意图, 这里只计数不改行为。
    // required=false: 裸构造的单测为 null = no-op。
    @Autowired(required = false)
    private com.cretas.aims.service.intent.LegacyIntentExitMetrics legacyExitMetrics;

    // C1 clarification business-type filter (restaurant-chat-qa): resolve the factory's business
    // domain + each candidate intent's business_type so the NEED_CLARIFICATION choice list never
    // shows manufacturing options (查询原料库存 / 查询生产批次) to a RESTAURANT tenant.
    // @Lazy mirrors BusinessTypeGate's injection pattern (avoids eager init / circular wiring).
    @Autowired
    @Lazy
    private com.cretas.aims.service.intent.IntentConfigManagementService configService;

    // P1 读写分块: 意图级权限门。required_permission 非空 → module:action 矩阵 (与 HTTP 层同源,
    // fail-closed); 空 → 回落 requiredRoles 旧逻辑 (兼容期)。见 checkIntentPermission()。
    @Autowired
    private IntentPermissionGate intentPermissionGate;

    // INTENT-DISTILL D2: 低置信澄清的候选与 SSE 同一个按权限过滤的服务。裸构造的单测里为 null ⇒ 只给「重新描述」
    // (fail closed), ⛔ 不回落旧的未过滤候选或默认业务按钮。
    @Autowired(required = false)
    private ClarificationCandidateService clarificationCandidateService;

    @Autowired
    private com.cretas.aims.repository.config.AIIntentConfigRepository intentConfigRepository;

    // ===== Route counters =====
    private final AtomicLong branchToolDirect = new AtomicLong();
    private final AtomicLong branchSkill = new AtomicLong();
    private final AtomicLong branchDynamic = new AtomicLong();
    private final AtomicLong branchNoMatch = new AtomicLong();

    @Value("${cretas.ai.validation.enabled:true}")
    private boolean validationEnabled;

    // P1 读写分块 §4.5 demo 写闸: 与 DemoReadOnlyInterceptor 同一配置源 (cretas.demo.factory-ids,
    // 默认 DEMO_REST,DEMO_FACTORY)。AI 确认执行阶段拦截 demo 租户真实写入 — 封住 HTTP 层
    // 放行 /ai-intents/ POST 留下的缺口。名单内租户恒拦 (不随 cretas.demo.enabled 关闭, fail-closed)。
    // 2026-08-05: DEMO_REST 随租户收敛停用, 从 fallback 移除 (与 application.properties 同步)。
    @Value("${cretas.demo.factory-ids:DEMO_REST,DEMO_FACTORY2}")
    private String demoFactoryIdsCsv;

    /** 通用短回复集合 */
    private static final Set<String> GENERIC_SHORT_REPLIES = Set.of(
            "查询完成，暂无数据", "操作完成", "查询完成", "查询成功", "执行成功",
            "查询完成,暂无数据", "处理完成", "请求成功",
            // 2026-07-23 工厂 AI 复盘: ToolDispatchService 对无 message 的工具
            // 结果默认填 "操作已完成。" — 该占位符掩盖 formattedText 里的真答案
            // (前端显示 message || formattedText, 占位符非空则真答案永不露出)。
            "操作已完成", "操作已完成。"
    );

    @Autowired
    public IntentExecutionOrchestrator(
            @Lazy AIIntentService aiIntentService,
            IntentSemanticsParser semanticsParser,
            SemanticCacheService semanticCacheService,
            RuleEngineService ruleEngineService,
            ConversationService conversationService,
            ConversationMemoryService conversationMemoryService,
            ObjectMapper objectMapper,
            DashScopeClient dashScopeClient,
            DashScopeConfig dashScopeConfig,
            IntentKnowledgeBase knowledgeBase,
            AIAnalysisResultRepository analysisResultRepository,
            ToolRegistry toolRegistry,
            AnalysisRouterService analysisRouterService,
            ComplexityRouter complexityRouter,
            AgentOrchestrator agentOrchestrator,
            AgenticRAGRouterService agenticRAGRouterService,
            ResultValidatorService resultValidatorService,
            ToolDispatchService toolDispatchService,
            DynamicToolSelectionService dynamicToolSelectionService,
            QueryPreprocessorService queryPreprocessorService) {
        this.aiIntentService = aiIntentService;
        this.semanticsParser = semanticsParser;
        this.semanticCacheService = semanticCacheService;
        this.ruleEngineService = ruleEngineService;
        this.conversationService = conversationService;
        this.conversationMemoryService = conversationMemoryService;
        this.objectMapper = objectMapper;
        this.dashScopeClient = dashScopeClient;
        this.dashScopeConfig = dashScopeConfig;
        this.knowledgeBase = knowledgeBase;
        this.analysisResultRepository = analysisResultRepository;
        this.toolRegistry = toolRegistry;
        this.analysisRouterService = analysisRouterService;
        this.complexityRouter = complexityRouter;
        this.agentOrchestrator = agentOrchestrator;
        this.agenticRAGRouterService = agenticRAGRouterService;
        this.resultValidatorService = resultValidatorService;
        this.toolDispatchService = toolDispatchService;
        this.dynamicToolSelectionService = dynamicToolSelectionService;
        this.queryPreprocessorService = queryPreprocessorService;
    }

    @PostConstruct
    public void init() {
        log.info("意图执行编排器初始化完成 (Facade-SubService 架构)");
    }

    // ==================== 核心执行方法 ====================

    /**
     * 主执行方法 -- 路由用户请求到 Tool / Skill / 动态选择 / LLM 对话
     */
    public IntentExecuteResponse execute(String factoryId, IntentExecuteRequest request,
                                          Long userId, String userRole) {
        // 深层外发 (抽参、纠错、选工具、消歧…) 不带工厂参数: 按本次路径上认定的工厂脱敏 —— 平台账号替某厂
        // 操作时也按那家工厂的保密产品名换代号 (Steve 2026-10-29, LlmEgressGuard.analysing)
        try (var egressFactory = com.cretas.aims.service.consent.LlmEgressGuard.analysing(factoryId);
             var aiAccess = com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.beginOrNoop(aiAccessAudit, factoryId,
                     com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.Entry.INTENT_EXECUTION)) {
            IntentExecuteResponse response = executeInScope(factoryId, request, userId, userRole);
            // Only an unselected, non-executing answer can default to READ. Known
            // intents must be stamped where their declaration is available; neither
            // a missing stamp nor caller-supplied text/code proves a read operation.
            if (response != null && response.getAiMode() == null
                    && !Boolean.TRUE.equals(response.getIntentRecognized())
                    && (response.getIntentCode() == null || response.getIntentCode().isBlank())
                    && response.getConfirmableAction() == null && response.getResultData() == null
                    && (response.getAffectedEntities() == null || response.getAffectedEntities().isEmpty())
                    && !Boolean.TRUE.equals(response.getRequiresApproval())) {
                applyAiModeStamp(response, null, null);
            }
            return response;
        }
    }

    private IntentExecuteResponse executeInScope(String factoryId, IntentExecuteRequest request,
                                          Long userId, String userRole) {
        IntentExecuteResponse tenantGate = analysisAdmissionResponse(factoryId, userRole);
        if (tenantGate != null) return tenantGate;
        if (com.cretas.aims.ai.catalog.FactoryIntentCatalog.isRetiredIntent(request.getIntentCode())) {
            return retiredCapabilityResponse();
        }

        log.info("执行意图: factoryId={}, userInput={}, intentCode={}, userId={}, role={}",
                factoryId,
                request.getUserInput() != null && request.getUserInput().length() > 50 ?
                        request.getUserInput().substring(0, 50) + "..." : request.getUserInput(),
                request.getIntentCode(), userId, userRole);

        FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute =
                evaluateFactoryPackRoute(factoryId, request, userRole);
        if (factoryPackRoute.shouldBlock()) {
            // 📊 [NarrowPath] 埋点 1/3 —— 只打 taken=true 一侧。
            // ⚠️ 这道闸有**两个**入口(本方法 + executeWithExplicitIntent), 两处都打。
            //    只打一处会得出「几乎没人走」的假读数。
            log.info("[NarrowPath] gate=factoryPack taken=true factoryId={} intentCode={} q={}",
                    factoryId, request.getIntentCode(),
                    truncateForTelemetry(request.getUserInput()));
            return buildFactoryPackNoMatch(factoryPackRoute, factoryPackRoute.reason());
        }
        boolean factoryPackConstrained = factoryPackRoute.isConstrained();

        if (hasNamedSession(request) && !hasTrustedConversationIdentity(
                factoryId, userId, request.getSessionId())) {
            return sessionAdmissionDeniedResponse();
        }

        {}

        // 0. 显式意图代码
        if (request.getIntentCode() != null && !request.getIntentCode().isEmpty()) {
            return factoryPackConstrained
                    ? executeWithExplicitIntent(
                            factoryId, request, userId, userRole, factoryPackRoute)
                    : executeWithExplicitIntent(factoryId, request, userId, userRole);
        }

        String userInput = request.getUserInput();
        {}

        {}
        // 📊 [NarrowPath] 埋点 2/3 —— 只打 taken=true 一侧。
        // ⚠️ 单独一个 if, 不合进上面的表达式: 那是短路求值, 走到 `isRestaurantTenant`
        //    为 false 时 `hasExplicitReadVeto` 根本不会被求值, 顺带记一笔就会漏。
        //    多调一次是纯字符串判断, 没有副作用。
        {}
        // 写动词是唯一让语义权威被跳过的那个原因时记下来 —— 后面识别层要靠它判「闸说改、
        // 解析器说看」的矛盾。两个布尔从同一个前提推导, 不各写一遍, 免得日后只改一处。


        // 0.1. W1b: early VETO gate in the execution layer. The orchestrator's own phrase shortcut
        // (#0.2 below) is contains-based and would execute "看订单" inside "别给我看订单" — bypassing
        // the recognition-layer veto gate. Mirror it here. Skipped for explicit intentCode (handled
        // above), so a user explicitly confirming a write is never vetoed.
        //   - VETO_READ  → return clarification immediately (no phrase shortcut, no execution).
        //   - VETO_WRITE → guard the short-circuits below so the request falls through to
        //                  recognizeIntentWithConfidence, which already handles VETO_WRITE safely
        //                  (OUT_OF_DOMAIN / read-twin, never an executed write).
        boolean negationVetoWrite = false;
        {}
        {}
        {}
        {}
        {}
        // The comprehensive synthesis engine is an existing deterministic
        // multi-dimension path (FactBook + optional narrative LLM). Route
        // explicit synthesis wording, or a genuine multi-dimension analysis
        // request, before the restaurant single-intent semantic planner.
        // Otherwise a dimension word such as "排班" can be intercepted by an
        // early single-intent phrase and the 21-dimension engine never runs.
        // The multi-dimension form needs at least three distinct business
        // dimensions plus analysis/cause/advice wording, so ordinary
        // revenue/dish/margin questions remain under the immutable QueryPlan.
        {}
        {}
        if (!factoryPackConstrained
                && userInput != null && !userInput.isEmpty()
                && !hasExplicitReadVeto(userInput)) {

            {}
        }
        String vInput = request.getUserInput();
        if (vInput != null && !vInput.isEmpty()) {
            QueryPreprocessorService.NegationKind vk;
            try {
                vk = queryPreprocessorService.detectNegationVeto(vInput, knowledgeBase);
            } catch (Exception e) {
                log.warn("[W1b] orchestrator detectNegationVeto failed, fail-open NONE for input='{}': {}", vInput, e.toString());
                vk = QueryPreprocessorService.NegationKind.NONE;
            }
            {}
            if (vk == QueryPreprocessorService.NegationKind.VETO_READ) {
                log.info("[W1b] orchestrator VETO_READ → clarification (no phrase shortcut, no execution): '{}'", vInput);
                return buildNegationVetoClarificationResponse(vInput);
            }
            negationVetoWrite = (vk == QueryPreprocessorService.NegationKind.VETO_WRITE);
        }

        // 0.15. 餐饮老板动作建议统一入口:
        // Web Admin 只调用 Java /ai-intents/execute；需要 Python 深度分析时由 Java 内部路由。
        // 放在普通餐饮 phrase shortcut 前面，避免“提高营收/排班/套餐/桌型”被普通查询工具抢走。
        // Owner-action is advisory/read-only. Keep routing restaurant老板 follow-ups like
        // "不要套餐，今天排班和备货怎么调？" through this path even when negation
        // pre-processing marks the wording as VETO_WRITE; otherwise it falls into a
        // low-level report intent and returns "missing month_summary" instead of a decision.
        {}

        // 0.2. Sprint 12 cache-fix Phase C — Phrase shortcut moved AHEAD of conversation
        //      continuation to defeat auto-mount inheritance bug.
        //
        // PRIOR STATE (Sprint 11 Round 7 + Sprint 12 PR #246):
        //   #0.3 conversation continuation ran FIRST. When a Vue Workdesk auto-mounted with
        //   intentCode=DAILY_CUSTOMER_FOLLOWUP, the response set conversation status=COMPLETED
        //   bound to that intent. When the user then typed a phrase ("帮我看上月损溢异常" etc.),
        //   handleConversationContinuation (line 576-580) detected isCompleted + non-null
        //   intentCode → setIntentCode(DAILY_CUSTOMER_FOLLOWUP) + forceExecute → routed via
        //   executeWithExplicitIntent. Phrase shortcut at the old #0.25 position never ran.
        //   This is the 9/12 misroute mechanism per AI 工厂 5/28 audit gh issue #277 H2 hypothesis.
        //
        // CURRENT FIX (Sprint 12 cache-fix Phase C):
        //   Phrase shortcut at #0.2 runs FIRST when userInput is non-empty. If the user's input
        //   matches a known phrase AND resolves to a configured (or platform-level) intent, the
        //   shortcut takes over — user-intent always wins over inherited conversation state.
        //   Conversation continuation still runs at #0.3 for the cases shortcut declines to handle
        //   (one-word answers, clarification responses, anything not in the phrase map).
        //
        // Safety: tryOrchestratorPhraseShortcut returns null when no phrase matches, so non-phrase
        // inputs fall through unchanged to continuation → identical behavior for clarification flows.
        // executeWithExplicitIntent does NOT route through continuation (sessionId is passed through
        // but conversationService is only consulted for in-progress sessions — explicit intent
        // bypasses the inheritance path).
        //
        // Phrase confidence is 0.96 (matching v33.1 EarlyPhrase tier so /recognize and /execute
        // produce consistent routing).
        if (!factoryPackConstrained
                && !negationVetoWrite && userInput != null && !userInput.isEmpty()) {

            {}
        }
        if (!negationVetoWrite && userInput != null && !userInput.isEmpty()) {
            IntentMatchResult earlyPhraseMatch = tryOrchestratorPhraseShortcut(
                    userInput,
                    factoryId,
                    factoryPackConstrained ? "FACTORY" : null);
            boolean phraseHit = earlyPhraseMatch != null && earlyPhraseMatch.hasMatch();
            if (legacyExitMetrics != null) {
                if (phraseHit) {
                    AIIntentConfig hitIntent = earlyPhraseMatch.getBestMatch();
                    legacyExitMetrics.recordExit(
                            com.cretas.aims.service.intent.LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE,
                            "java_phrase_shortcut", factoryId,
                            com.cretas.aims.service.intent.LegacyIntentExitMetrics.rw(hitIntent,
                                    () -> isWriteIntentDeclarationAware(hitIntent)),
                            "precedes_decision");
                } else {
                    legacyExitMetrics.recordPass(
                            com.cretas.aims.service.intent.LegacyIntentExitMetrics.SITE_EXECUTE_PHRASE);
                }
            }
            if (phraseHit) {
                AIIntentConfig phraseIntent = earlyPhraseMatch.getBestMatch();
                // R20c: 餐饮租户的 OUT_OF_DOMAIN 短语 ("今天天气怎么样") 不在此
                // 短路 — 放行到 tiered 反转, Python 给餐饮语境的域外诚实拒答;
                // 此处执行会落到工厂措辞的通用助手回复。
                IntentExecuteResponse phraseWriteReadAmbiguity =
                        null;
                if (phraseWriteReadAmbiguity != null) {
                    return phraseWriteReadAmbiguity;
                }
                {
                log.info("[Sprint12-CacheFix-EarlyPhrase] Pre-continuation phrase shortcut: input='{}', intentCode={}",
                        userInput, phraseIntent.getIntentCode());
                IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(phraseIntent.getIntentCode())
                        .sessionId(request.getSessionId())
                        .enableThinking(request.getEnableThinking())
                        .thinkingBudget(request.getThinkingBudget())
                        .context(request.getContext())
                        // P1 读写分块: mode 随短语短路透传, READ 模式在显式意图路径继续生效
                        .mode(request.getMode())
                        // P1.5b: previewOnly 是请求级硬契约, 内部重建必须携带 —
                        // 否则预览请求被 SITE A 误判为执行而拦成确认卡 (实测发现)
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return executeWithExplicitIntent(
                        factoryId, phraseRequest, userId, userRole, factoryPackRoute);
                }
            }
        }

        // Parameter-filling continuations stay in Java when the semantic-first
        // restaurant path is disabled or the request is an operational write.
        if (!factoryPackConstrained
                && request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            IntentExecuteResponse conversationResponse = handleConversationContinuation(
                    factoryId, request, userId, userRole);
            if (conversationResponse != null) {
                return conversationResponse;
            }
        }

        // 0.3. 早期问题类型检测
        if (!factoryPackConstrained
                && !negationVetoWrite && userInput != null && !userInput.isEmpty()) {
            IntentExecuteResponse earlyRouteResponse = handleEarlyQuestionTypeDetection(
                    factoryId, userInput, request, userId, userRole);
            if (earlyRouteResponse != null) {
                return earlyRouteResponse;
            }
        }

        // 0.45. 识别用最近对话 —— 与 SSE 入口同一个解析器、同一份结果 (INTENT-DISTILL-20261111 A 线)。
        //       Web 不发 recentTurns, 由本人会话记忆里的用户话补上; 只有身份已证明的调用者才读记忆。
        boolean trustedRecognizer = hasTrustedConversationIdentity(factoryId, userId, request.getSessionId());
        List<Map<String, String>> recognitionHistory = recognitionHistoryResolver != null
                ? recognitionHistoryResolver.materialize(factoryId, trustedRecognizer ? userId : null, request)
                : RecognitionHistoryResolver.effective(request);

        // 0.5. 语义缓存 —— 带上下文的轮次不读 (缓存键只有工厂 + 原话, 与 SSE 一致)
        if (recognitionHistory.isEmpty() && userInput != null && !userInput.isEmpty()) {
            handleSemanticCache(factoryId, userInput, request);
        }

        // 1. 识别意图 (former phrase shortcut moved up to position #0.25; this branch is now
        //    only the normal recognition pipeline)
        IntentMatchResult matchResult = null;

        if (matchResult == null) {
            try {
                // spec §8.2 洞②: 把 tab 模式带进识别层, 让写意图在咨询 tab 根本不进候选集,
                // 而不是靠下面 #4.4 的"识别后拦"。bestMatch 仍会保留写意图, 那是有意的 ——
                // 拦截卡需要它来渲染"请切换到【操作】页"的导航。
                Long recognizerUserId = trustedRecognizer ? userId : null;
                String recognizerSessionId = recognizerUserId == null ? null : request.getSessionId();
                java.util.Set<String> filterPermissions =
                        resolveUserPermissionsForFiltering(request.getMode(), userId, factoryId);
                // 无上下文时调用形状与改动前完全一致 (8 参版在实现里就是「历史为空」的 9 参版)
                matchResult = recognitionHistory.isEmpty()
                        ? aiIntentService.recognizeIntentWithConfidence(
                                request.getUserInput(), factoryId, 3, recognizerUserId, userRole, recognizerSessionId,
                                request.getMode(), filterPermissions)
                        : aiIntentService.recognizeIntentWithConfidence(
                                request.getUserInput(), factoryId, 3, recognizerUserId, userRole, recognizerSessionId,
                                request.getMode(), filterPermissions, recognitionHistory);
            } catch (LlmSchemaValidationException e) {
                log.warn("LLM Schema 验证失败: type={}, message={}", e.getFailureType(), e.getMessage());
                return buildValidationFailureResponse(factoryId, request.getUserInput(), e);
            }
        }

        if (matchResult != null && matchResult.hasMatch()
                && SupplierComparisonReadIntent.CODE.equals(matchResult.getBestMatch().getIntentCode())) {
            Optional<AIIntentConfig> canonical = SupplierComparisonReadIntent.resolve(
                    factoryId, toolRegistry, intentConfigRepository);
            if (canonical.isEmpty()) return buildNoPermissionResponse(matchResult.getBestMatch());
            matchResult.setBestMatch(canonical.get());
            IntentExecuteResponse denied = checkIntentPermission(canonical.get(), factoryId, userId, userRole);
            if (denied != null) return denied;
        }

        if (factoryPackConstrained
                && (matchResult.getQuestionType() == QuestionType.GENERAL_QUESTION
                || matchResult.getQuestionType() == QuestionType.CONVERSATIONAL)) {
            return buildFactoryPackNoMatch(factoryPackRoute, "general-analysis-not-allowed");
        }

        // 2. 二次确认
        if (!factoryPackConstrained
                && matchResult.hasMatch() && Boolean.TRUE.equals(matchResult.getRequiresConfirmation())
                && !Boolean.TRUE.equals(request.getForceExecute())) {
            return buildClarificationResponse(matchResult, factoryId, userId, userRole, request.getMode());
        }

        // 2b. 通用咨询/闲聊
        if (matchResult.getQuestionType() == QuestionType.GENERAL_QUESTION ||
            matchResult.getQuestionType() == QuestionType.CONVERSATIONAL) {
            String llmResponse = generateConversationalResponse(factoryId, request.getUserInput(),
                    matchResult.getQuestionType(), request.getEnableThinking(), request.getThinkingBudget());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("COMPLETED")
                    .message(llmResponse)
                    .formattedText(llmResponse)
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        // 3. 无匹配
        if (!matchResult.hasMatch()) {
            return factoryPackConstrained
                    ? buildFactoryPackNoMatch(factoryPackRoute, "recognizer-no-match")
                    : buildNoMatchResponse(matchResult, factoryId, request, userId, userRole);
        }

        AIIntentConfig intent = matchResult.getBestMatch();
        if (com.cretas.aims.ai.catalog.FactoryIntentCatalog.isRetiredIntent(intent.getIntentCode())) {
            return retiredCapabilityResponse();
        }
        log.info("识别到意图: code={}, category={}, sensitivity={}, matchMethod={}, confidence={}",
                intent.getIntentCode(), intent.getIntentCategory(), intent.getSensitivityLevel(),
                matchResult.getMatchMethod(), matchResult.getConfidence());

        IntentExecuteResponse writeReadAmbiguity =
                null;
        if (writeReadAmbiguity != null) {
            return writeReadAmbiguity;
        }

        if (factoryPackConstrained) {
            IntentExecuteResponse permissionDenied = checkIntentPermission(intent, factoryId, userId, userRole);
            if (permissionDenied != null) {
                return permissionDenied;
            }
            IntentExecuteResponse factoryPackDecision = applyFactoryPackDecision(
                    factoryPackRoute, intent);
            if (factoryPackDecision != null) {
                return factoryPackDecision;
            }
            if (Boolean.TRUE.equals(matchResult.getRequiresConfirmation())
                    && !Boolean.TRUE.equals(request.getForceExecute())) {
                IntentExecuteResponse clarification = buildFactoryPackNoMatch(
                        factoryPackRoute, "recognizer-confirmation-required");
                applyAiModeStamp(clarification, intent, intent.getToolName());
                return clarification;
            }
        }

        // 权限检查 (P1: required_permission 权限码优先, 空回落 requiredRoles 旧逻辑)
        if (!factoryPackConstrained) {
            IntentExecuteResponse permissionDenied = checkIntentPermission(intent, factoryId, userId, userRole);
            if (permissionDenied != null) {
                return permissionDenied;
            }
        }

        // P1 读写分块 §4.4: 咨询 tab (mode=READ) 强制只读 — 写意图不进审批/slot-filling/执行,
        // 返回跳转提示卡 (防呆 Rule 5: dead-end 改导航)。
        if ("READ".equalsIgnoreCase(request.getMode()) && isWriteIntentDeclarationAware(intent)) {
            return buildReadModeWriteBlockedResponse(intent);
        }

        // 审批检查
        if (Boolean.TRUE.equals(intent.getRequiresApproval())
                && !Boolean.TRUE.equals(request.getPreviewOnly())) {
            return buildApprovalResponse(intent);
        }

        // 业态门控 (Sprint 13 #305): a domain-exclusive intent (business_type RESTAURANT/FACTORY)
        // matched on a mismatched factory.type → honest "本厂非该业态" empty-state + appropriate
        // next-action, instead of executing a tool that returns a misleading half-broken "数据不可用".
        if (!factoryPackConstrained) {
            IntentExecuteResponse businessTypeGate = checkBusinessTypeGate(factoryId, intent);
            if (businessTypeGate != null) {
                return businessTypeGate;
            }
        }

        // Drools 验证
        // Read-only query intents should not enter the Drools validation engine:
        // the engine is meant to guard writes/critical actions, and repeated
        // compilation on high-volume chat queries can exhaust JVM Metaspace.
        if (shouldRunDroolsValidation(intent)) {
            ValidationResult validationResult = validateWithDrools(factoryId, intent, request, userId, userRole);
            if (!validationResult.isValid()) {
                return buildDroolsFailureResponse(intent, validationResult);
            }
        }

        // 3.5. Skill 优先检查
        String boundToolName = intent.getToolName();
        if (dynamicToolSelectionService.isSkillsEnabled()
                && (boundToolName == null || boundToolName.isBlank())) {
            IntentExecuteResponse skillResponse = dynamicToolSelectionService.trySkillRoute(
                    request.getUserInput(), factoryId, userId, userRole);
            if (skillResponse != null) {
                long count = branchSkill.incrementAndGet();
                log.info("[Branch:Skill] Skill 优先匹配成功: total={}", count);
                return finishSkillBranchResponse(factoryId, request, intent, userId, skillResponse);
            }

            // Sprint 9 P0.1 fix (2026-05-21): Sprint 8 WORKDESK intents (DAILY_CUSTOMER_FOLLOWUP /
            // MONTHLY_FINANCIAL_CLOSE / FOOD_SAFETY_RECALL 等) bind tool_name=NULL + 依赖 Skill 路由.
            // 但 trySkillRoute 用 Skill 自己 keywords 匹用户原文 — 若 LLM 已正确识别 intent
            // 但用户原文不含 Skill triggers (例如 "客户跟进" 在 intent keywords 但 Skill triggers
            // 是 "今天跟谁" / "今日跟进"), trySkillRoute 返 null → fall through 到
            // buildNoToolResponse → 报 "暂不支持此类型的意图执行: WORKDESK". P0 阻塞性 bug.
            //
            // Fix: 当 intent 已识别且 tool_name=NULL 时, 用 intent_code (UPPER_SNAKE) →
            // skill_name (lower-kebab) 直接 lookup Skill, 绕过 keyword 匹配.
            IntentExecuteResponse explicitSkillResponse = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId, userRole);
            if (explicitSkillResponse != null) {
                long count = branchSkill.incrementAndGet();
                log.info("[Branch:Skill] 显式 Skill 路由 (by intent_code) 成功: intentCode={}, total={}",
                        intent.getIntentCode(), count);
                return finishSkillBranchResponse(factoryId, request, intent, userId, explicitSkillResponse);
            }
        }

        // 3.6. Slot Filling
        if (canUseStatefulConversation(factoryId, userId, request)
                && !Boolean.TRUE.equals(request.getSkipSlotFilling()) && slotFillingService != null) {
            IntentExecuteResponse slotFillingResponse = slotFillingService.checkAndStartSlotFilling(
                    factoryId, userId, intent, request, matchResult);
            if (slotFillingResponse != null) {
                if (slotFillingResponse.getFormattedText() == null
                        && slotFillingResponse.getMessage() != null
                        && slotFillingResponse.getMessage().length() >= 5) {
                    slotFillingResponse.setFormattedText(slotFillingResponse.getMessage());
                }
                applyAiModeStamp(slotFillingResponse, intent, intent.getToolName());
                return slotFillingResponse;
            }
        }

        // 4. 路由到执行器
        String toolName = intent.getToolName();
        IntentExecuteResponse response;

        IntentExecuteResponse paginationReplay = executePaginationReplay(factoryId, request, intent, userId, userRole);
        if (paginationReplay != null) {
            response = paginationReplay;
        } else if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                long count = branchToolDirect.incrementAndGet();
                log.info("[Branch:ToolDirect] 使用 Tool 执行: intentCode={}, toolName={}, total={}",
                        intent.getIntentCode(), toolName, count);
                response = toolDispatchService.executeWithTool(toolOpt.get(), factoryId, request, intent, userId, userRole, matchResult);
            } else {
                log.warn("Tool 未找到: toolName={}, intentCode={}", toolName, intent.getIntentCode());
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        } else if (dynamicToolSelectionService.isSkillsEnabled()
                && dynamicToolSelectionService.requiresDynamicSelection(matchResult)) {
            long count = branchDynamic.incrementAndGet();
            log.info("[Branch:Dynamic] 触发动态工具选择: total={}", count);
            response = dynamicToolSelectionService.executeWithDynamicToolSelection(
                    factoryId, request, intent, matchResult, userId, userRole);
        } else if (dynamicToolSelectionService.requiresDynamicSelection(matchResult)) {
            long count = branchDynamic.incrementAndGet();
            log.info("[Branch:Dynamic] 触发动态工具选择(无Skill): total={}", count);
            response = dynamicToolSelectionService.executeWithDynamicToolSelection(
                    factoryId, request, intent, matchResult, userId, userRole);
        } else {
            // Sprint 9 P0.1 fix (2026-05-21): 最终 fallback 前再尝试一次显式 Skill 路由
            // (覆盖 dynamicToolSelectionService.isSkillsEnabled() = false 但 SkillRouter
            // 仍可用 / Skill 已注册但 keyword 没匹的 corner case).
            IntentExecuteResponse explicitSkillFallback = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId, userRole);
            if (explicitSkillFallback != null) {
                long count = branchSkill.incrementAndGet();
                log.info("[Branch:Skill] 显式 Skill 路由 (NoMatch fallback) 成功: intentCode={}, total={}",
                        intent.getIntentCode(), count);
                response = explicitSkillFallback;
            } else {
                long count = branchNoMatch.incrementAndGet();
                log.warn("[Branch:NoMatch] 无路由匹配: intentCode={}, total={}", intent.getIntentCode(), count);
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        }

        // 路由分支统计
        logBranchStats();

        // 6.5. NEED_MORE_INFO enrichment
        if ("NEED_MORE_INFO".equals(response.getStatus())) {
            response = enrichWithClarificationQuestions(response, request, intent, factoryId, userId);
        }

        // 6.8. 结果格式化 (先换成这个人看得到的那一份数据)
        boolean priceMasked = applyCallerPriceView(response, userId);
        applyResultFormatting(response, userId);

        // 6.9. formattedText 兜底
        applyFormattedTextFallback(factoryId, response, priceMasked);

        // 6.95. P1 读写分块: 终点统一补 aiMode (前端据此选卡片形态)
        applyAiModeStamp(response, intent, toolName);

        // 7. 缓存
        processResponseCaching(factoryId, request, matchResult, response);

        // 8. 对话记忆
        // D1-fix: Ensure ConversationMemory row exists before writing slots/messages.
        // ConversationMemoryServiceImpl.updateEntitySlot / addMessage silently bail out
        // when the session row does not yet exist (no auto-create). The explicit-intent
        // path (executeWithExplicitIntent → persistConversationMemoryForExplicitIntent)
        // already calls getOrCreateContext first; mirror that guarantee here so DISH /
        // STORE slots written by extractAndUpdateEntitySlots are actually persisted.
        if (hasBoundSupplierFactoryActor(factoryId, userId)
                && request.getSessionId() != null && !request.getSessionId().isEmpty()) {
            try {
                ConversationContext owner = conversationMemoryService.getOrCreateContext(factoryId, userId, request.getSessionId());
                if (ownsConversationMemory(owner, factoryId, userId, request.getSessionId())) {
                    updateConversationMemory(request.getSessionId(), request, response, matchResult, factoryId, userId);
                }
            } catch (Exception e) {
                log.warn("对话记忆 getOrCreateContext 失败 (非阻断): sessionId={}, error={}",
                        request.getSessionId(), e.getMessage());
            }
        }

        return response;
    }

    /**
     * 使用显式意图代码执行 (跳过意图识别)
     */
    public IntentExecuteResponse executeWithExplicitIntent(String factoryId, IntentExecuteRequest request,
                                                             Long userId, String userRole) {
        // 深层外发 (抽参、纠错、选工具、消歧…) 不带工厂参数: 按本次路径上认定的工厂脱敏 —— 平台账号替某厂
        // 操作时也按那家工厂的保密产品名换代号 (Steve 2026-10-29, LlmEgressGuard.analysing)
        try (var egressFactory = com.cretas.aims.service.consent.LlmEgressGuard.analysing(factoryId);
             var aiAccess = com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.beginOrNoop(aiAccessAudit, factoryId,
                     com.cretas.aims.service.processsecret.ProcessSecretAiAccessAudit.Entry.INTENT_EXECUTION)) {
            return executeWithExplicitIntentInScope(factoryId, request, userId, userRole);
        }
    }

    private IntentExecuteResponse executeWithExplicitIntentInScope(String factoryId, IntentExecuteRequest request,
                                                             Long userId, String userRole) {
        IntentExecuteResponse tenantGate = analysisAdmissionResponse(factoryId, userRole);
        if (tenantGate != null) return tenantGate;
        if (com.cretas.aims.ai.catalog.FactoryIntentCatalog.isRetiredIntent(request.getIntentCode())) {
            return retiredCapabilityResponse();
        }
        if (hasNamedSession(request) && !hasTrustedConversationIdentity(
                factoryId, userId, request.getSessionId())) {
            return sessionAdmissionDeniedResponse();
        }
        FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute =
                evaluateFactoryPackRoute(factoryId, request, userRole);
        if (factoryPackRoute.shouldBlock()) {
            // 📊 [NarrowPath] 埋点 1/3 —— 只打 taken=true 一侧。
            // ⚠️ 这道闸有**两个**入口(本方法 + executeWithExplicitIntent), 两处都打。
            //    只打一处会得出「几乎没人走」的假读数。
            log.info("[NarrowPath] gate=factoryPack taken=true factoryId={} intentCode={} q={}",
                    factoryId, request.getIntentCode(),
                    truncateForTelemetry(request.getUserInput()));
            return buildFactoryPackNoMatch(factoryPackRoute, factoryPackRoute.reason());
        }
        return executeWithExplicitIntent(
                factoryId, request, userId, userRole, factoryPackRoute);
    }

    private IntentExecuteResponse executeWithExplicitIntent(
            String factoryId,
            IntentExecuteRequest request,
            Long userId,
            String userRole,
            FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute) {
        String intentCode = request.getIntentCode();
        if (com.cretas.aims.ai.catalog.FactoryIntentCatalog.isRetiredIntent(intentCode)) {
            return retiredCapabilityResponse();
        }
        log.info("使用显式意图代码执行: intentCode={}, factoryId={}", intentCode, factoryId);

        Optional<AIIntentConfig> intentOpt = getIntentByCodeWithPlatformFallback(factoryId, intentCode);
        if (intentOpt.isEmpty()) {
            if (factoryPackRoute.isConstrained()) {
                return buildFactoryPackNoMatch(
                        factoryPackRoute, "explicit-intent-not-found");
            }
            return IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("FAILED")
                    .message("未找到意图配置: " + intentCode)
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        AIIntentConfig intent = intentOpt.get();

        IntentExecuteResponse permissionDenied = checkIntentPermission(intent, factoryId, userId, userRole);
        if (permissionDenied != null) {
            return permissionDenied;
        }

        IntentExecuteResponse factoryPackDecision = applyFactoryPackDecision(
                factoryPackRoute, intent);
        if (factoryPackDecision != null) {
            return factoryPackDecision;
        }

        // P1 读写分块 §4.4: 咨询 tab (mode=READ) 兜底 — 显式意图/短语短路路径命中写意图同样拦截,
        // 先于 W0 写确认门 (READ 模式下不该出现确认卡, 只给跳转提示)。
        if ("READ".equalsIgnoreCase(request.getMode()) && isWriteIntentDeclarationAware(intent)) {
            return buildReadModeWriteBlockedResponse(intent);
        }

        // W0 write-guard (intent-w0) — SITE A: the convergence point all explicit-code / forced /
        // multi-intent / phrase-shortcut / conversation-continuation paths funnel through. The guard
        // is deliberately NOT conditioned on request.getForceExecute() (which is hard-set true by the
        // multi-intent and conversation-continuation paths and is exactly what must NOT skip the
        // guard). previewOnly requests are allowed (they preview, not execute). Confirmation is
        // process-local authority issued only after an atomic single-use token claim; raw request
        // context values such as confirmed=true are never trusted.
        java.util.Map<String, Object> ctx = request.getContext() != null ? request.getContext() : java.util.Map.of();
        if (writeGuardService.isWriteIntent(intent)
                && !Boolean.TRUE.equals(request.getPreviewOnly())
                && !writeGuardService.isConfirmed(ctx)) {
            log.info("W0 write-guard: blocked write intent {} (forceExecute={}, confirmed=false)",
                    intent.getIntentCode(), request.getForceExecute());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("WRITE_CONFIRM_REQUIRED")
                    .message("「" + intent.getIntentName() + "」是写入/修改操作，执行前需要确认。")
                    .requiresApproval(true)
                    // P1 读写分块: 确认卡属写路径, 显式标记 (早退路径不经 6.95 统一 stamp)
                    .aiMode("WRITE")
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        }

        // Approval governs the mutation, not its read-only TCC preview. Keeping
        // previewOnly outside this gate lets the user inspect before/after and
        // impact details before deciding whether to request approval/confirm.
        if (Boolean.TRUE.equals(intent.getRequiresApproval())
                && !Boolean.TRUE.equals(request.getPreviewOnly())) {
            return buildApprovalResponse(intent);
        }

        // 业态门控 (Sprint 13 #305): this explicit-intent path is reached for an explicit
        // intentCode AND for phrase-shortcut matches (execute() #0 / #0.2 delegate here),
        // bypassing the gate in the main matching flow. Gate here too so a domain-exclusive
        // intent (e.g. RESTAURANT_ECONOMICS_ANALYSIS) on a mismatched factory.type returns the
        // honest "本厂非该业态" empty-state instead of executing a tool with no data.
        if (!factoryPackRoute.isConstrained()) {
            IntentExecuteResponse businessTypeGate = checkBusinessTypeGate(factoryId, intent);
            if (businessTypeGate != null) {
                return businessTypeGate;
            }
        }

        // Preview mode
        if (Boolean.TRUE.equals(request.getPreviewOnly()) && intent.getToolName() != null && !intent.getToolName().isEmpty()) {
            Optional<ToolExecutor> previewToolOpt = toolRegistry.getExecutor(intent.getToolName());
            if (previewToolOpt.isPresent() && previewToolOpt.get().supportsPreview()) {
                IntentExecuteResponse preview = toolDispatchService.executeToolPreview(
                        previewToolOpt.get(), factoryId, request, intent, userId, userRole);
                applyAiModeStamp(preview, intent, intent.getToolName());
                return preview;
            }
            IntentExecuteResponse unsupportedPreview = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("FAILED")
                    .message("预览模式暂不支持此意图类型")
                    .executedAt(LocalDateTime.now())
                    .build();
            applyAiModeStamp(unsupportedPreview, intent, intent.getToolName());
            return unsupportedPreview;
        }

        // Tool 执行
        String toolName = intent.getToolName();
        IntentExecuteResponse response;

        IntentExecuteResponse paginationReplay = executePaginationReplay(factoryId, request, intent, userId, userRole);
        if (paginationReplay != null) {
            response = paginationReplay;
        } else if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                response = toolDispatchService.executeWithTool(toolOpt.get(), factoryId, request, intent, userId, userRole, null);
            } else {
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        } else {
            // Sprint 9 P0.1 fix (2026-05-21): explicit intent path 同 execute() 一样需 Skill fallback.
            // 显式传 intent_code 走这分支 (e.g. Workdesk 前端按钮直接 POST intent_code).
            IntentExecuteResponse explicitSkillFallback = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId, userRole);
            if (explicitSkillFallback != null) {
                log.info("[Explicit-Intent] 显式 Skill 路由成功: intentCode={}", intent.getIntentCode());
                response = explicitSkillFallback;
            } else {
                response = toolDispatchService.buildNoToolResponse(intent);
            }
        }

        if ("NEED_MORE_INFO".equals(response.getStatus())) {
            response = enrichWithClarificationQuestions(response, request, intent, factoryId, userId);
        }

        boolean priceMasked = applyCallerPriceView(response, userId);
        applyResultFormatting(response, userId);
        applyFormattedTextFallback(factoryId, response, priceMasked);

        // P1 读写分块: 显式意图路径同样终点补 aiMode
        applyAiModeStamp(response, intent, toolName);

        // X1 Part B 修复:短路 / 显式意图路径也持久化对话记忆,供下一轮续接继承。
        persistConversationMemoryForExplicitIntent(factoryId, request, response, intent, userId);

        return response;
    }

    /**
     * 确认执行预览的操作
     */
    public IntentExecuteResponse confirm(String factoryId, ConfirmationProof confirmationProof,
                                          Long userId, String userRole) {
        // 深层外发 (抽参、纠错、选工具、消歧…) 不带工厂参数: 按本次路径上认定的工厂脱敏 —— 平台账号替某厂
        // 操作时也按那家工厂的保密产品名换代号 (Steve 2026-10-29, LlmEgressGuard.analysing)
        try (var egressFactory = com.cretas.aims.service.consent.LlmEgressGuard.analysing(factoryId)) {
            return confirmInScope(factoryId, confirmationProof, userId, userRole);
        }
    }

    private IntentExecuteResponse confirmInScope(String factoryId, ConfirmationProof confirmationProof,
                                          Long userId, String userRole) {
        IntentExecuteResponse tenantGate = analysisAdmissionResponse(factoryId, userRole);
        if (tenantGate != null) return tenantGate;
        if (previewTokenService == null) {
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("确认服务不可用")
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        if (confirmationProof == null
                || !COMMAND_DIGEST_PATTERN.matcher(confirmationProof.commandDigest()).matches()
                || !confirmationProof.expiresAt().isAfter(Instant.now())) {
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("Confirmation proof is invalid or expired")
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        String confirmToken = confirmationProof.proofToken();

        // A completed canonical purchase is recovered by its persisted preview identity, never a new key.
        try {
            if (controlledPurchase != null) {
                var replay = controlledPurchase.replay(factoryId, confirmationProof);
                if (replay.isPresent()) return controlledPurchaseResponse(replay.get(), "SUCCESS", "原采购草稿已创建，返回持久化结果");
            }
        } catch (RuntimeException recoveryFailure) {
            return IntentExecuteResponse.builder().status("OUTCOME_UNKNOWN")
                    .message("无法核对采购确认状态，请查看授权状态；禁止重新下单")
                    .aiMode("WRITE").build();
        }

        PreviewTokenService.ClaimResult claimResult =
                previewTokenService.claimToken(
                        confirmToken,
                        factoryId,
                        userId,
                        confirmationProof.commandDigest());
        if (!claimResult.isSuccess()) {
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message(claimResult.getMessage())
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        var token = claimResult.getToken();
        String claimId = claimResult.getClaimId();
        String intentCode = token.getIntentCode();

        try {
            if (com.cretas.aims.ai.catalog.FactoryIntentCatalog.isRetiredIntent(intentCode)) {
                previewTokenService.resolveClaim(confirmToken, claimId, false, "该业务能力已退役");
                return retiredCapabilityResponse();
            }
            // P1 读写分块 §4.5 demo 写闸: 演示租户预览照常 (演示价值), 确认执行阶段拦截真实写入。
            // confirm() 是唯一的 withServerConfirmation 注入点, 此处即全部 AI 写执行的收口。
            if (isDemoFactory(factoryId)) {
                previewTokenService.resolveClaim(confirmToken, claimId, false,
                        "演示环境拦截: 不执行真实写入");
                String demoMsg = "演示环境不执行真实写入。操作预览已展示——在正式环境中，点击确认后将实际执行该操作。";
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intentCode)
                        .status("DEMO_WRITE_BLOCKED")
                        .message(demoMsg)
                        .formattedText(demoMsg)
                        .aiMode("WRITE")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            Optional<AIIntentConfig> intentConfigOpt = "PURCHASE_ORDER_CREATE".equals(intentCode)
                    ? controlledPurchaseIntent(factoryId)
                    : aiIntentService.getIntentByCode(factoryId, intentCode);
            if (intentConfigOpt.isEmpty()) {
                previewTokenService.resolveClaim(
                        confirmToken, claimId, false, "意图配置不存在: " + intentCode);
                return IntentExecuteResponse.builder()
                        .status("FAILED")
                        .message("意图配置不存在: " + intentCode)
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            AIIntentConfig intentConfig = intentConfigOpt.get();
            String boundToolName = token.getToolName();
            if (!boundToolName.equals(intentConfig.getToolName())) {
                previewTokenService.resolveClaim(confirmToken, claimId, false,
                        "意图配置的工具绑定已变化");
                return IntentExecuteResponse.builder()
                        .status("FAILED")
                        .message("意图配置已变化，请重新发起预览")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            if (!com.cretas.aims.service.intent.PurchaseTaskGrantService.CANONICAL_TOOL.equals(boundToolName)) {
                // A preview is not an approval grant. Recheck current intent authorization before
                // reconstructing any server confirmation authority from the claimed command.
                IntentExecuteResponse denied = checkIntentPermission(intentConfig, factoryId, userId, userRole);
                if (denied != null) {
                    previewTokenService.resolveClaim(confirmToken, claimId, false, denied.getMessage());
                    return denied;
                }
                if (Boolean.TRUE.equals(intentConfig.getRequiresApproval())) {
                    IntentExecuteResponse approval = buildApprovalResponse(intentConfig);
                    previewTokenService.resolveClaim(confirmToken, claimId, false, approval.getMessage());
                    return approval;
                }

            }

            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(boundToolName);
            if (toolOpt.isEmpty()) {
                previewTokenService.resolveClaim(confirmToken, claimId, false,
                        "未找到绑定工具: " + boundToolName);
                return IntentExecuteResponse.builder()
                        .status("FAILED")
                        .message("绑定工具不可用，请重新发起预览")
                        .executedAt(LocalDateTime.now())
                        .build();
            }
            ToolExecutor tool = toolOpt.get();
            if (!token.getDescriptorVersion().equals(tool.getVersion())) {
                previewTokenService.resolveClaim(confirmToken, claimId, false,
                        "工具版本已变化");
                return IntentExecuteResponse.builder()
                        .status("FAILED")
                        .message("工具版本已变化，请重新发起预览")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            if (com.cretas.aims.service.intent.PurchaseTaskGrantService.CANONICAL_TOOL.equals(boundToolName)) {
                return confirmControlledPurchase(factoryId, token, claimId, confirmationProof, intentConfig, userId, userRole);
            }

            Map<String, Object> context = claimResult.getParameters() != null
                    ? com.cretas.aims.ai.tool.WriteGuardService.withoutCallerConfirmation(
                            claimResult.getParameters())
                    : new HashMap<>();
            // Identity and authorization are re-established from the trusted path/JWT. The token
            // deliberately does not persist the original role, so current JWT role is rechecked by
            // ToolDispatch/RBAC instead of accepting any client parameter as authority.
            for (String reservedKey : List.of(
                    "factoryId", "factory_id", "tenantId", "tenant_id",
                    "userId", "user_id", "userRole", "role",
                    "permissions", "scopes", "principal")) {
                context.remove(reservedKey);
            }
            context.put("factoryId", token.getFactoryId());
            context.put("factory_id", token.getFactoryId());
            context.put("userId", token.getUserId());
            context.put("user_id", token.getUserId());
            context.put("userRole", userRole);
            context.put("role", userRole);
            // Some legacy destructive tools still require a boolean business argument in addition
            // to the platform guard. It is safe here because the atomic token claim already bound
            // tenant, user, command digest, tool and descriptor version.
            context.put("confirmed", true);
            context = writeGuardService.withServerConfirmation(context);

            IntentExecuteRequest execRequest = IntentExecuteRequest.builder()
                    .userInput("确认执行: " + intentCode)
                    .intentCode(intentCode)
                    .context(context)
                    .build();

            IntentExecuteResponse response = toolDispatchService.executeWithTool(
                    tool, factoryId, execRequest, intentConfig, userId, userRole, null);
            boolean toolSucceeded = response != null
                    && ("SUCCESS".equals(response.getStatus())
                        || "COMPLETED".equals(response.getStatus()));
            String resolution = response == null
                    ? "工具未返回执行结果"
                    : (response.getMessage() != null ? response.getMessage() : response.getStatus());
            boolean resolved = previewTokenService.resolveClaim(
                    confirmToken, claimId, toolSucceeded, resolution);
            if (!resolved) {
                log.error("确认状态完成失败: claimFingerprint={}",
                        com.cretas.aims.ai.tool.gateway.ToolCommandDigest.tokenFingerprint(claimId));
                return IntentExecuteResponse.builder()
                        .status("FAILED")
                        .message("执行结果状态记录失败，请勿重复提交并联系管理员")
                        .executedAt(LocalDateTime.now())
                        .build();
            }
            if (toolSucceeded && factoryFollowUpService != null) {
                try {
                    response.setFollowUps(factoryFollowUpService.suggest(
                            factoryId, userId, userRole, intentConfig, response.getStatus()));
                } catch (Exception e) {
                    log.warn("[Confirm] Factory follow-up lookup unavailable: {}", e.getMessage());
                    response.setFollowUps(List.of());
                }
            }
            return response != null ? response : IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("工具未返回执行结果")
                    .executedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            try {
                previewTokenService.resolveClaim(confirmToken, claimId, false,
                        "执行异常: " + com.cretas.aims.util.ErrorSanitizer.sanitize(e));
            } catch (Exception resolutionError) {
                log.error("确认失败状态写入异常: factoryId={}, error={}",
                        factoryId, resolutionError.getMessage());
            }
            log.error("确认执行失败: factoryId={}, error={}",
                    factoryId, e.getMessage(), e);
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message("执行失败: " + com.cretas.aims.util.ErrorSanitizer.sanitize(e))
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ==================== 内部路由方法 ====================

    private IntentExecuteResponse confirmControlledPurchase(String factoryId,
            com.cretas.aims.entity.intent.IntentPreviewToken token, String claimId,
            ConfirmationProof proof, AIIntentConfig intent, Long userId, String userRole) {
        IntentExecuteResponse permissionDenied = checkIntentPermission(intent, factoryId, userId, userRole);
        if (permissionDenied != null) {
            previewTokenService.resolveClaim(proof.proofToken(), claimId, false, "当前意图权限不足");
            return permissionDenied;
        }
        try {
            var outcome = controlledPurchase.executeConfirmed(factoryId, token.getId(), claimId);
            if (!previewTokenService.resolveClaim(proof.proofToken(), claimId, true, "采购草稿已创建:" + outcome.purchaseOrderId())) {
                return controlledPurchaseResponse(outcome, "OUTCOME_UNKNOWN", "采购结果已记录，确认状态待核对；请查看授权状态，禁止重新下单");
            }
            return controlledPurchaseResponse(outcome, "SUCCESS", "采购草稿已创建；请在采购模块按独立OA流程提交审批");
        } catch (com.cretas.aims.exception.BusinessException | IllegalArgumentException
                 | com.cretas.aims.service.intent.PurchaseTaskGrantService.GrantRejectedException rejected) {
            previewTokenService.resolveClaim(proof.proofToken(), claimId, false, "受控采购被拒绝");
            return IntentExecuteResponse.builder().status("FAILED").aiMode("WRITE")
                    .message("受控采购已拒绝，事务未提交：" + rejected.getMessage())
                    .resultData(java.util.Map.of("grantId", com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService.grantId(token.getId())))
                    .build();
        } catch (RuntimeException uncertain) {
            // Do not mark a potentially committed purchase as failed or re-enter legacy retries.
            return IntentExecuteResponse.builder().status("OUTCOME_UNKNOWN").aiMode("WRITE")
                    .message("采购提交结果待核对，请查看授权状态；禁止重新下单")
                    .resultData(java.util.Map.of("grantId", com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService.grantId(token.getId())))
                    .build();
        }
    }

    private IntentExecuteResponse controlledPurchaseResponse(
            com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService.Outcome outcome, String status, String message) {
        return IntentExecuteResponse.builder().status(status).aiMode("WRITE")
                .intentCode("PURCHASE_ORDER_CREATE").message(message).formattedText(message)
                .resultData(outcome).executedAt(LocalDateTime.now()).build();
    }

    private IntentExecuteResponse handleConversationContinuation(String factoryId, IntentExecuteRequest request,
                                                                   Long userId, String userRole) {
        log.info("检测到会话延续: sessionId={}", request.getSessionId());
        // Session rows are keyed by factory-user numeric IDs, never by platform identity.
        if (!hasTrustedConversationIdentity(factoryId, userId, request.getSessionId())) {
            return sessionAdmissionDeniedResponse();
        }
        try {
            Optional<ConversationSession> pending = conversationService.getSession(
                    factoryId, userId, request.getSessionId());
            if (pending.isPresent()
                    && SupplierComparisonReadIntent.CODE.equals(pending.get().getKnownIntentCode())) {
                AIIntentConfig supplier = AIIntentConfig.builder()
                        .intentCode(SupplierComparisonReadIntent.CODE).intentName("供应商报价比较").build();
                if (!hasBoundSupplierFactoryActor(factoryId, userId)) return buildNoPermissionResponse(supplier);
                Optional<AIIntentConfig> canonical = SupplierComparisonReadIntent.resolve(
                        factoryId, toolRegistry, intentConfigRepository);
                if (canonical.isEmpty()) return buildNoPermissionResponse(supplier);
                IntentExecuteResponse denied = checkIntentPermission(canonical.get(), factoryId, userId, userRole);
                if (denied != null) return denied;
            }
            ConversationService.ConversationResponse conversationResp =
                    conversationService.continueConversation(
                            factoryId, userId, request.getSessionId(), request.getUserInput());

            if (conversationResp == null ||
                conversationResp.getStatus() == ConversationSession.SessionStatus.CANCELLED) {
                return null; // Continue normal flow
            }

            if (conversationResp.isCompleted() && conversationResp.getIntentCode() != null) {
                conversationService.endConversation(
                        factoryId, userId, request.getSessionId(), conversationResp.getIntentCode());
                request.setIntentCode(conversationResp.getIntentCode());
                request.setForceExecute(true);
                return executeWithExplicitIntent(factoryId, request, userId, userRole);
            }

            // Conversation continues
            IntentExecuteResponse.IntentExecuteResponseBuilder responseBuilder = IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("CONVERSATION_CONTINUE")
                    .message(conversationResp.getMessage())
                    .formattedText(conversationResp.getMessage())
                    .executedAt(LocalDateTime.now());

            Map<String, Object> metadata = new HashMap<>();
            metadata.put("sessionId", conversationResp.getSessionId());
            metadata.put("currentRound", conversationResp.getCurrentRound());
            metadata.put("maxRounds", conversationResp.getMaxRounds());
            metadata.put("status", conversationResp.getStatus() != null ? conversationResp.getStatus().name() : null);

            if (conversationResp.getCandidates() != null && !conversationResp.getCandidates().isEmpty()) {
                List<IntentExecuteResponse.SuggestedAction> candidateActions = new ArrayList<>();
                for (ConversationService.CandidateInfo candidate : conversationResp.getCandidates()) {
                    Map<String, Object> params = new HashMap<>();
                    params.put("intentCode", candidate.getIntentCode());
                    params.put("sessionId", conversationResp.getSessionId());
                    candidateActions.add(IntentExecuteResponse.SuggestedAction.builder()
                            .actionCode("SELECT_INTENT")
                            .actionName(candidate.getIntentName())
                            // 副标题只给人话, 不再用「置信度」兜底 (见 ClarificationButtonText)
                            .description(ClarificationButtonText.subtitle(candidate.getDescription()))
                            .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute")
                            .parameters(params)
                            .build());
                }
                metadata.put("candidates", conversationResp.getCandidates());
                responseBuilder.suggestedActions(candidateActions);
            }
            responseBuilder.metadata(metadata);
            return responseBuilder.build();
        } catch (Exception e) {
            log.error("会话延续失败: sessionId={}, error={}", request.getSessionId(), e.getMessage(), e);
            return null;
        }
    }

    private IntentExecuteResponse handleEarlyQuestionTypeDetection(String factoryId, String userInput,
                                                                     IntentExecuteRequest request,
                                                                     Long userId, String userRole) {
        QuestionType earlyQuestionType = knowledgeBase.detectQuestionType(userInput);

        if (earlyQuestionType != QuestionType.GENERAL_QUESTION &&
            earlyQuestionType != QuestionType.CONVERSATIONAL) {
            return null;
        }

        // Analysis request check
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION &&
            analysisRouterService.isAnalysisRequest(userInput, earlyQuestionType)) {
            return executeAnalysisFlow(factoryId, userInput, request, userId, userRole);
        }

        // Food knowledge intercept
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION) {
            Optional<String> foodPhraseMatch = knowledgeBase.matchPhrase(userInput);
            if (foodPhraseMatch.isPresent() && "FOOD_KNOWLEDGE_QUERY".equals(foodPhraseMatch.get())) {
                IntentExecuteRequest foodRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode("FOOD_KNOWLEDGE_QUERY")
                        .mode(request.getMode())
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return execute(factoryId, foodRequest, userId, userRole);
            }
            if (knowledgeBase.hasEntityIntentConflict(userInput, "PROCESSING_GENERIC")) {
                IntentExecuteRequest foodRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode("FOOD_KNOWLEDGE_QUERY")
                        .mode(request.getMode())
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return execute(factoryId, foodRequest, userId, userRole);
            }
        }

        // Agentic RAG routing
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION) {
            RAGRouteResult ragRouteResult = agenticRAGRouterService.route(userInput);

            switch (ragRouteResult.getConsultationType()) {
                case KNOWLEDGE_SEARCH:
                    String knowledgeResponse = agenticRAGRouterService.executeKnowledgeSearch(userInput, ragRouteResult);
                    return buildRAGResponse(knowledgeResponse, ragRouteResult, "KNOWLEDGE_SEARCH");
                case WEB_SEARCH:
                    String webSearchResponse = agenticRAGRouterService.executeWebSearch(userInput, ragRouteResult);
                    return buildRAGResponse(webSearchResponse, ragRouteResult, "WEB_SEARCH");
                case TRACEABILITY:
                    if (ragRouteResult.shouldConvertToIntent() && ragRouteResult.isHighConfidence()) {
                        if (ragRouteResult.isNeedsClarification()) {
                            return IntentExecuteResponse.builder()
                                    .intentRecognized(false)
                                    .status("NEED_CLARIFICATION")
                                    .message(ragRouteResult.getClarificationQuestion())
                                    .formattedText(ragRouteResult.getClarificationQuestion())
                                    .executedAt(LocalDateTime.now())
                                    .build();
                        }
                        Map<String, Object> traceabilityContext = new HashMap<>(ragRouteResult.getExtractedParams());
                        IntentExecuteRequest traceabilityRequest = IntentExecuteRequest.builder()
                                .userInput(userInput)
                                .intentCode(ragRouteResult.getSuggestedIntent())
                                .context(traceabilityContext)
                                .mode(request.getMode())
                                .previewOnly(request.getPreviewOnly())
                                .build();
                        return execute(factoryId, traceabilityRequest, userId, userRole);
                    }
                    break;
                case GENERAL:
                default:
                    break;
            }
        }

        // Sprint 12 P0 defense-in-depth — any phrase match WITH configured intent should win
        // over LLM conversational fallback. Without this, the LLM at line ~700 hijacks all
        // "帮我X / 怎么X / 哪个X" inputs that lack OUT_OF_DOMAIN/CONTEXT_CONTINUE wiring.
        // (Fix 1 at execute() #0.25 catches most cases; this is the safety net if a request
        // somehow bypasses the earlier check — e.g. via explicit GENERAL_QUESTION classification
        // path while phrase shortcut returned null due to factory-scoped intent absence.)
        // 2026-08-06: 这里曾自己写 factoryId.startsWith("RES_") —— 餐饮判定六处承载点里
        // 最后一处漏掉 domain 的。MOCK_REST 的 id 无 RES_/REST_ 前缀, 只能靠
        // factories.type='RESTAURANT' 认出来, 于是它在这条兜底路径上被标成 "FACTORY",
        // 匹到的是工厂短语表。改用同文件下方那条已有的、给 matchPhrase 专用的解析。
        String businessDomain = resolvePhraseBusinessDomain(factoryId);
        Optional<String> conversationalPhraseMatch = knowledgeBase.matchPhrase(userInput, businessDomain);
        if (conversationalPhraseMatch.isPresent()) {
            String matchedIntent = conversationalPhraseMatch.get();
            if ("OUT_OF_DOMAIN".equals(matchedIntent) || "CONTEXT_CONTINUE".equals(matchedIntent)) {
                IntentExecuteRequest interceptRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(matchedIntent)
                        .sessionId(request.getSessionId())
                        // P1.5b: 写意图可经此转发 — mode(READ 拦截)与 previewOnly(预览契约)必须随行
                        .mode(request.getMode())
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return execute(factoryId, interceptRequest, userId, userRole);
            }
            // Sprint 12 P0 — any other phrase-matched intent that exists in this factory
            // should route to executeWithExplicitIntent (NOT fall through to LLM conversational).
            Optional<AIIntentConfig> existingIntent = aiIntentService.getIntentByCode(factoryId, matchedIntent);
            if (existingIntent.isPresent()) {
                log.info("[Sprint12-DefenseInDepth] handleEarlyQuestionTypeDetection phrase route: input='{}', intentCode={}",
                        userInput, matchedIntent);
                IntentExecuteRequest phraseRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode(matchedIntent)
                        .sessionId(request.getSessionId())
                        // 卡1 修复: 与上面 interceptRequest 一致 — 写意图可经此转发,
                        // mode(READ 拦截)与 previewOnly(预览契约)必须随行, 否则 READ
                        // 模式下写意图会绕过 READ_MODE_WRITE_BLOCKED 拦截。
                        .mode(request.getMode())
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return executeWithExplicitIntent(factoryId, phraseRequest, userId, userRole);
            }
        }

        // Default: LLM conversational response
        String llmResponse = generateConversationalResponse(factoryId, userInput, earlyQuestionType,
                request.getEnableThinking(), request.getThinkingBudget());
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("COMPLETED")
                .message(llmResponse)
                .formattedText(llmResponse)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private void handleSemanticCache(String factoryId, String userInput, IntentExecuteRequest request) {
        try {
            SemanticCacheHit cacheHit = semanticCacheService.queryCache(factoryId, userInput);
            if (cacheHit.isHit()) {
                if (cacheHit.hasExecutionResult()) {
                    // Note: execute() 目前并不会为 execution-result 缓存命中做提前返回 —
                    // 无论 hasExecutionResult() 与否, 这个方法都只是把下面的意图匹配缓存
                    // 结果标记进 request context (__cacheHit/__cacheHitType), 流程仍会继续
                    // 走完整识别流水线。此分支目前是 no-op (曾设想的"直接返回缓存执行结果"
                    // 未实现)。
                    // ⛔ 工艺保密 (Steve 2026-10-29): 缓存按「工厂 + 原话」存完整执行结果, 不分调用者。
                    // 要在这里回放执行结果, 必须先按调用者的保密访问档位隔离缓存 (工厂 + 调用者能
                    // FULL 查看的保密产品集合), 否则未授权的人会拿到授权用户问出来的保密产品完整内容。
                    // 没有这层隔离不许实现回放 (见 SemanticCacheServiceImpl.cacheResult)。
                }
                IntentMatchResult cachedMatch = deserializeIntentResult(cacheHit.getIntentResult());
                if (cachedMatch != null && cachedMatch.hasMatch()) {
                    if (request.getContext() == null) {
                        request.setContext(new HashMap<>());
                    }
                    request.getContext().put("__cacheHit", true);
                    request.getContext().put("__cacheHitType",
                            cacheHit.getHitType() != null ? cacheHit.getHitType().name() : "SEMANTIC");
                }
            }
        } catch (Exception e) {
            log.warn("语义缓存查询失败: {}", e.getMessage());
        }
    }

    /**
     * Sprint 11 Round 7 (2026-05-23) — Pre-recognition phrase shortcut.
     *
     * <p>Returns a PHRASE_MATCH IntentMatchResult if userInput exactly matches a phrase in
     * {@link IntentKnowledgeBase}'s phrase mapping (business-domain aware). Returns null
     * if no phrase match — caller falls through to normal recognition pipeline.
     *
     * <p>This is a defense-in-depth safety net at the orchestrator layer. The inner pipeline
     * also has v33.1-EarlyPhrase (line 443 of IntentRecognitionPipelineServiceImpl) but prod
     * 2026-05-23 confirmed /execute can bypass it (root cause unclear). This orchestrator-
     * level check guarantees phrase-mapped intents always win.
     *
     * <p>Confidence 0.96 matches the inner EarlyPhrase confidence to avoid behavioral
     * divergence between /recognize and /execute endpoints.
     *
     * @param userInput user's raw input (will be normalized to lowercase + trimmed)
     * @param factoryId factory ID, used to resolve business domain (RESTAURANT vs FACTORY)
     * @return phrase-matched IntentMatchResult, or null if no phrase match
     */
    private IntentMatchResult tryOrchestratorPhraseShortcut(String userInput, String factoryId) {
        return tryOrchestratorPhraseShortcut(userInput, factoryId, null);
    }

    private IntentMatchResult tryOrchestratorPhraseShortcut(
            String userInput, String factoryId, String trustedBusinessDomain) {
        try {
            String normalized = userInput.toLowerCase().trim();
            String businessDomain = trustedBusinessDomain != null
                    ? trustedBusinessDomain
                    : resolvePhraseBusinessDomain(factoryId);


            {}

            Optional<String> phraseMatch = knowledgeBase.matchPhrase(normalized, businessDomain);
            if (phraseMatch.isEmpty()) {
                return null;
            }

            String matchedCode = phraseMatch.get();
            Optional<AIIntentConfig> intentOpt = getIntentByCodeWithPlatformFallback(factoryId, matchedCode);
            if (intentOpt.isEmpty()) {
                // Phrase mapped to a code that doesn't exist in this factory's intent config —
                // skip the shortcut and let the normal pipeline decide.
                log.debug("[Round7-EarlyPhrase] phrase matched {} but intent not configured for factory {} — falling through",
                        matchedCode, factoryId);
                return null;
            }

            AIIntentConfig phraseIntent = intentOpt.get();
            log.info("[Round7-EarlyPhrase] Orchestrator phrase shortcut: input='{}', intentCode={}, domain={}",
                    userInput, matchedCode, businessDomain);
            return IntentMatchResult.builder()
                    .userInput(userInput)
                    .bestMatch(phraseIntent)
                    .confidence(0.96)
                    .matchMethod(IntentMatchResult.MatchMethod.PHRASE_MATCH)
                    .build();
        } catch (Exception e) {
            log.debug("[Round7-EarlyPhrase] shortcut failed (fall through): {}", e.getMessage());
            return null;
        }
    }

    private String resolvePhraseBusinessDomain(String factoryId) {
        String businessDomain = resolveFactoryDomainSafe(factoryId);
        return businessDomain == null || businessDomain.isBlank() ? "FACTORY" : businessDomain;
    }

    private Optional<AIIntentConfig> getIntentByCodeWithPlatformFallback(String factoryId, String intentCode) {
        if (com.cretas.aims.ai.catalog.FactoryIntentCatalog.isRetiredIntent(intentCode)) {
            return Optional.empty();
        }
        intentCode = com.cretas.aims.ai.catalog.FactoryIntentCatalog.canonicalCode(intentCode);
        if (SupplierComparisonReadIntent.CODE.equals(intentCode)) {
            return SupplierComparisonReadIntent.resolve(factoryId, toolRegistry, intentConfigRepository);
        }
        if ("PURCHASE_ORDER_CREATE".equals(intentCode)) {
            return controlledPurchaseIntent(factoryId);
        }
        Optional<AIIntentConfig> intentOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
        if (intentOpt.isPresent() || factoryId == null || factoryId.isBlank()) {
            return intentOpt;
        }
        return aiIntentService.getIntentByCode(intentCode);
    }

    private IntentExecuteResponse retiredCapabilityResponse() {
        return IntentExecuteResponse.builder().intentRecognized(false).status("UNSUPPORTED_CAPABILITY")
                .message("餐饮专属能力已退役，请使用工厂业务功能。")
                .executedAt(LocalDateTime.now()).build();
    }

    /** Server-owned metadata, not an authorization grant or a persisted intent seed. */
    private Optional<AIIntentConfig> controlledPurchaseIntent(String factoryId) {
        if (factoryId == null || factoryId.isBlank() || intentConfigRepository == null) {
            return Optional.empty();
        }
        try {
            Optional<ToolExecutor> registered = toolRegistry.getExecutor("purchase_order_create");
            if (registered.isEmpty()) {
                return Optional.empty();
            }
            ToolExecutor tool = registered.get();
            if (!com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService.VERSION.equals(tool.getVersion())
                    || !"purchase_order_create".equals(tool.getToolName())
                    || tool.getActionType() != ToolExecutor.ActionType.WRITE
                    || tool.getAccessMode() != ToolExecutor.AccessMode.WRITE
                    || !tool.supportsPreview() || !tool.requiresPermission()
                    || !java.util.Set.of("procurement:read_write").equals(tool.getRequiredPermissions())) {
                return Optional.empty();
            }
            // Query includes inactive rows: an explicit restriction must never become an absent-row fallback.
            Optional<AIIntentConfig> configured = intentConfigRepository
                    .findByIntentCodeAndDeletedAtIsNull("PURCHASE_ORDER_CREATE");
            if (configured.isPresent()) {
                AIIntentConfig row = configured.get();
                if (!Boolean.TRUE.equals(row.getIsActive())
                        || (row.getFactoryId() != null && !factoryId.equals(row.getFactoryId()))
                        || !"purchase_order_create".equals(row.getToolName())
                        || !"procurement:read_write".equals(row.getRequiredPermission())
                        || !"DATA_OP".equals(row.getIntentCategory())
                        || !"FACTORY".equals(row.getBusinessType())
                        || !"HIGH".equals(row.getSensitivityLevel())
                        || Boolean.TRUE.equals(row.getRequiresApproval())) {
                    return Optional.empty();
                }
            }
            return Optional.of(AIIntentConfig.builder()
                    .intentCode("PURCHASE_ORDER_CREATE").intentName("创建受控采购草稿")
                    .toolName("purchase_order_create").intentCategory("DATA_OP")
                    .businessType("FACTORY").sensitivityLevel("HIGH")
                    .requiredPermission("procurement:read_write").requiresApproval(false)
                    .factoryId(factoryId).isActive(true).build());
        } catch (Exception e) {
            log.warn("受控采购入口元数据不可用，拒绝执行: {}", e.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /**
     * 餐饮租户判定的唯一语义: domain 是权威, ID 前缀只是 domain 拿不到时的兜底。
     * MOCK_REST 这类 id 不含 RES_/REST_ 前缀的租户只能靠 domain 认出来。
     */

    /**
     * Routes a ranking whose comparison object is a store, not a month.
     *
     * <p>A relative time such as “上个月” is only a filter. It must not turn
     * “上个月哪家门店营收最高” into the peak-month intent. Questions that ask
     * “哪个月/哪个月份” remain outside this route, as do causal/optimization
     * requests that need the tiered analysis path.
     */

    private static boolean containsAny(String input, String... terms) {
        if (input == null || terms == null) {
            return false;
        }
        for (String term : terms) {
            if (term != null && !term.isBlank() && input.contains(term)) {
                return true;
            }
        }
        return false;
    }

    // 16 \u4e2a\u663e\u5f0f\u53ea\u8bfb\u5426\u5b9a\u8bcd (\u4e0d\u8981\u67e5/\u4e0d\u8981\u770b/\u522b\u67e5/\u522b\u770b/\u4e0d\u7528\u67e5/\u4e0d\u7528\u770b/\u4e0d\u9700\u8981\u67e5/\u4e0d\u9700\u8981\u770b/
    // \u4e0d\u60f3\u67e5/\u4e0d\u60f3\u770b/\u5148\u522b\u67e5/\u5148\u522b\u770b/\u65e0\u9700\u67e5/\u65e0\u9700\u770b/\u4e0d\u67e5/\u4e0d\u770b)\u3002
    private static final String[] READ_VETO_TERMS = {
            "\u4e0d\u8981\u67e5",
            "\u4e0d\u8981\u770b",
            "\u522b\u67e5",
            "\u522b\u770b",
            "\u4e0d\u7528\u67e5",
            "\u4e0d\u7528\u770b",
            "\u4e0d\u9700\u8981\u67e5",
            "\u4e0d\u9700\u8981\u770b",
            "\u4e0d\u60f3\u67e5",
            "\u4e0d\u60f3\u770b",
            "\u5148\u522b\u67e5",
            "\u5148\u522b\u770b",
            "\u65e0\u9700\u67e5",
            "\u65e0\u9700\u770b",
            "\u4e0d\u67e5",
            "\u4e0d\u770b",
    };

    // \u7ef4\u5ea6\u7ea7\u5426\u5b9a\u653e\u884c\u6807\u8bb0 (\u53ea\u770b/\u53ea\u8981/\u53ea\u67e5/\u4f46\u770b/\u4f46\u8981/\u4f46\u67e5/\u800c\u770b/\u800c\u8981/\u800c\u67e5)\u3002\u5426\u5b9a\u8bcd\u540e\u9762
    // \u7d27\u8ddf\u8fd9\u4e9b\u5bf9\u6bd4\u5b50\u53e5 (\u5982 "\u4e0d\u770b\u5802\u98df\u53ea\u770b\u5916\u5356\u8425\u6536") \u8bf4\u660e\u53ea\u662f\u6392\u9664\u67d0\u4e00\u4e2a\u7ef4\u5ea6\u3001\u5f15\u51fa\u53e6\u4e00\u4e2a
    // \u5173\u6ce8\u5bf9\u8c61, \u5e76\u975e\u5426\u5b9a\u6574\u6761\u67e5\u8be2 \u2014 \u5e94\u653e\u884c\u8bed\u4e49\u89c4\u5212, \u4e0d\u7b97 veto\u3002
    private static final String[] READ_VETO_DIMENSION_CONTRAST_MARKERS = {
            "\u53ea\u770b",
            "\u53ea\u8981",
            "\u53ea\u67e5",
            "\u4f46\u770b",
            "\u4f46\u8981",
            "\u4f46\u67e5",
            "\u800c\u770b",
            "\u800c\u8981",
            "\u800c\u67e5",
    };

    // \u5426\u5b9a\u8bcd\u4e4b\u540e\u5f80\u540e\u770b\u591a\u5c11\u4e2a\u5b57\u7b26\u627e\u5bf9\u6bd4\u5b50\u53e5 \u2014 \u591f\u8986\u76d6 "\u4e0d\u770b\u5802\u98df\u53ea\u770b\u5916\u5356\u8425\u6536" \u8fd9\u7c7b\u77ed\u53e5,
    // \u53c8\u4e0d\u81f3\u4e8e\u628a\u5f88\u8fdc\u5904\u4e0d\u76f8\u5173\u7684 "\u53ea\u770b" \u8bef\u5224\u6210\u540c\u4e00\u5426\u5b9a\u7684\u5bf9\u6bd4\u5bf9\u8c61\u3002
    private static final int READ_VETO_DIMENSION_CONTRAST_WINDOW = 12;

    /**
     * R? (2026-07-28 \u98de\u8f6e\u56de\u63a5\u53611): \u5224\u65ad\u5426\u5b9a\u5bf9\u8c61, \u800c\u975e\u7eaf contains \u5339\u914d\u3002
     *
     * <p>\u65e7\u5b9e\u73b0\u5bf9 16 \u4e2a\u5426\u5b9a\u8bcd\u505a\u7eaf contains \u5339\u914d \u2014\u2014 "\u4e0d\u770b\u5802\u98df\u53ea\u770b\u5916\u5356\u8425\u6536" \u53ea\u662f\u6392\u9664
     * "\u5802\u98df" \u8fd9\u4e00\u4e2a\u7ef4\u5ea6, \u540e\u534a\u53e5 "\u53ea\u770b\u5916\u5356\u8425\u6536" \u4ecd\u7136\u662f\u4e00\u4e2a\u660e\u786e\u7684\u8bfb\u8bf7\u6c42, \u4f46\u65e7\u903b\u8f91\u4e00\u89c1
     * "\u4e0d\u770b" \u5b50\u4e32\u5c31\u6574\u6761 veto, \u628a\u8bf7\u6c42\u6253\u56de Java 8 \u5c42\u65e7\u94fe (\u77ed\u8bed\u76f4\u6267\u884c + Stage-8 \u76f4\u9009)\u3002
     *
     * <p>\u65b0\u903b\u8f91: \u5426\u5b9a\u8bcd\u547d\u4e2d\u540e, \u68c0\u67e5\u5176\u540e {@link #READ_VETO_DIMENSION_CONTRAST_WINDOW}
     * \u4e2a\u5b57\u7b26\u5185\u662f\u5426\u7d27\u8ddf "\u53ea\u770b/\u53ea\u8981/\u53ea\u67e5/\u4f46\u770b/\u4f46\u8981/\u4f46\u67e5/\u800c\u770b/\u800c\u8981/\u800c\u67e5" \u7c7b\u5bf9\u6bd4\u5b50\u53e5 \u2014
     * \u547d\u4e2d\u5219\u8bf4\u660e\u662f\u7ef4\u5ea6\u7ea7\u5426\u5b9a (\u6392\u9664\u5b50\u96c6, \u4fdd\u7559\u53e6\u4e00\u4e2a\u5173\u6ce8\u5bf9\u8c61), \u653e\u884c\u8bed\u4e49\u89c4\u5212; \u5426\u5219\u624d\u662f
     * \u5426\u5b9a\u6574\u6761\u67e5\u8be2\u7684 veto\u3002
     *
     * <p>Card4 (2026-07-28 \u5165\u53e3\u6536\u655b): package-private static, \u65e0\u5b9e\u4f8b\u72b6\u6001,
     * \u4e13\u95e8\u4e3a\u8ba9 {@code SseStreamingService} \u540c\u5305\u76f4\u63a5\u590d\u7528\u2014\u2014\u540c\u4e00\u4e2a\u5224\u65ad\u53ea\u80fd\u6709
     * \u4e00\u4efd\u5b9e\u73b0, \u5426\u5219 SSE \u548c /execute \u4e24\u4e2a\u5165\u53e3\u5bf9\u540c\u4e00\u53e5\u8bdd\u7684 veto \u5224\u65ad\u4f1a\u56e0\u526f\u672c
     * \u6f02\u79fb\u800c\u5206\u5c90 \u2014\u2014 \u6b63\u662f\u672c\u5361\u8981\u6d88\u706d\u7684\u75c5\u3002\u5207\u52ff\u518d\u590d\u5236\u4e00\u4efd\u3002
     */
    /**
     * [NarrowPath] 埋点用的问句截断。40 字 —— 够认出是哪一类问法, 又不至于把整句
     * 用户输入抄进日志。null 打成 "-" 而不是 "null", 免得取数时把它当成一个问句。
     */
    static String truncateForTelemetry(String q) {
        if (q == null || q.isEmpty()) {
            return "-";
        }
        String flat = q.replace('\n', ' ').replace('\r', ' ');
        return flat.length() <= 40 ? flat : flat.substring(0, 40);
    }

    static boolean hasExplicitReadVeto(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String q = input.replaceAll("\\s+", "");
        for (String term : READ_VETO_TERMS) {
            int idx = q.indexOf(term);
            if (idx < 0) {
                continue;
            }
            int remainderStart = idx + term.length();
            int windowEnd = Math.min(q.length(), remainderStart + READ_VETO_DIMENSION_CONTRAST_WINDOW);
            String window = q.substring(remainderStart, windowEnd);
            if (containsAny(window, READ_VETO_DIMENSION_CONTRAST_MARKERS)) {
                continue;
            }
            return true;
        }
        return false;
    }

    // ==================== 分析流程 ====================

    /**
     * R13e: 意图已识别但无执行器 (误匹配到未配置意图, 如「那招牌藤椒味(单人份)呢」
     * → RESTAURANT_DISH_CREATE) 是死胡同出口 — 餐饮租户先问 tiered 路由再放弃,
     * 避免用户拿到"请联系管理员配置"而实际数据可答。
     */


    // R15: 写操作动词 — 命中则不做 slot-filling 前置委托, 保留参数收集流程。

    /**
     * 征询 / 假设语气 —— 优先级高于写动词。
     *
     * <p>2026-08-11 实测缺陷: 「有没有哪个菜是在亏钱卖的，<b>要不要下架</b>」命中写动词
     * {@code 下架} → {@code requiresRestaurantSemanticPlan} 被压掉 → Python 语义权威
     * (唯一挂着 request_coverage 契约的那条路) 整个被跳过 → 落到 Java 识别层, LLM 猜成
     * {@code RESTAURANT_DISH_SLOW}, 用一份<b>销量</b>榜回答一个<b>毛利</b>问题。老板照这个
     * 可能去下架一道赚钱的菜。
     *
     * <p>⛔ 修法不是往 {@link #RESTAURANT_HISTORICAL_WRITE_READ} 那张过去时豁免表里继续加
     * 「要不要 / 该不该」—— 那还是逐条枚举说法, 漏一条就再犯一次同样的错。这里判的是<b>语气</b>:
     * 出现征询 (要不要 / 该不该 / 是否要 / 建议) 或假设 (如果 / 假如) 时, 无论句子里提到什么
     * 动词, 用户都是在<b>问</b>, 不是在<b>下令</b> —— 「提到」不等于「下令」。
     *
     * <p>安全方向: 判错成读只会让请求进入只读的语义规划器 (Python 没有写能力), 不会误改数据;
     * 判错成写才会绕开契约去猜答案。真正的写入口 (显式 intentCode / 具名下架语法 /
     * 排行批量操作澄清) 都排在本判据<b>之前</b>, 不受影响。
     */

    /**
     * 写动词把这句话判成写 → Python 语义权威被跳过。此时若 Java 解析出来的是一个<b>读</b>意图,
     * 两个判据互相矛盾: 闸说用户要改, 解析器说用户要看。
     *
     * <p>这条路上没有 Python 那套 {@code request_coverage} 契约 (答案必须覆盖用户问的指标),
     * 继续执行等于让关键词 / 向量 / LLM 去猜用户到底问的是什么。2026-08-11 实测:
     * 「有没有哪个菜是在亏钱卖的，要不要下架」被猜成慢销榜(按销量), 而用户问的是毛利。
     *
     * <p>所以: fail closed, 不猜 —— 与「禁止降级处理」是同一条纪律。
     * 语气判据 ({@link #RESTAURANT_DELIBERATIVE_MOOD}) 已经把征询 / 假设那一族拨回读路径,
     * 这里守的是它<b>覆盖不到</b>的剩余形态 (叙述式提及、名词化的写动词等)。
     *
     * @return 非 null 表示必须就地返回该澄清响应, null 表示放行
     */


    /** 面向店长的话术: 不出现意图码 / 置信度这类技术内容 (owner 2026-08-11 拍板)。 */

    // R28: 餐饮复合句 ("先说说A，再说说B") — 早期 phrase shortcut 的 contains
    // 匹配会把内嵌的预设短语抢走, 只答一半; 复合句放行到 tiered 反转由
    // python agent 拆解回答。与 python restaurant_agent._COMPOUND_HINT_RE 同款。

    private IntentExecuteResponse executeAnalysisFlow(String factoryId, String userInput,
                                                       IntentExecuteRequest request,
                                                       Long userId, String userRole) {
        try {
            AnalysisTopic topic = analysisRouterService.detectAnalysisTopic(userInput);
            AnalysisContext analysisContext = AnalysisContext.builder()
                    .userInput(userInput).topic(topic).factoryId(factoryId)
                    .userId(userId).userRole(userRole).sessionId(request.getSessionId())
                    .enableThinking(request.getEnableThinking()).thinkingBudget(request.getThinkingBudget())
                    .build();

            ProcessingMode processingMode = complexityRouter.route(userInput, analysisContext);
            AnalysisResult analysisResult;

            if (processingMode == ProcessingMode.MULTI_AGENT || processingMode == ProcessingMode.DEEP_REASONING) {
                analysisResult = agentOrchestrator.executeCollaborativeAnalysis(analysisContext);
            } else {
                analysisResult = analysisRouterService.executeAnalysis(analysisContext);
            }

            if (analysisResult.isSuccess()) {
                String status = analysisResult.isRequiresHumanReview() ? "ANALYSIS_PENDING_REVIEW" : "ANALYSIS_COMPLETED";
                return IntentExecuteResponse.builder()
                        .intentRecognized(false)
                        .status(status)
                        .message(analysisResult.getFormattedAnalysis())
                        .formattedText(analysisResult.getFormattedAnalysis())
                        .metadata(Map.of("analysisTopic", topic.name(), "processingMode", processingMode.name()))
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // Fallback
            String fallback = generateConversationalResponse(factoryId, userInput,
                    QuestionType.GENERAL_QUESTION, request.getEnableThinking(), request.getThinkingBudget());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false).status("COMPLETED")
                    .message(fallback).formattedText(fallback).executedAt(LocalDateTime.now()).build();

        } catch (Exception e) {
            log.error("分析流程异常: {}", e.getMessage(), e);
            String fallback = generateConversationalResponse(factoryId, userInput,
                    QuestionType.GENERAL_QUESTION, request.getEnableThinking(), request.getThinkingBudget());
            return IntentExecuteResponse.builder()
                    .intentRecognized(false).status("COMPLETED")
                    .message(fallback).formattedText(fallback).executedAt(LocalDateTime.now()).build();
        }
    }

    // ==================== LLM 对话 ====================

    String generateConversationalResponse(String factoryId, String userInput, QuestionType questionType,
                                                   Boolean enableThinking, Integer thinkingBudget) {
        String systemPrompt;
        // 只有「带了工厂经营报告当上下文」这一支是基于数据作答, 数字出处闸只挂它:
        // 另两支没有任何数据进模型, 源池为空, 挂闸等于删掉所有常识性数字 (登记为故意不挂)。
        String factoryAnalysisContext = null;
        if (questionType == QuestionType.GENERAL_QUESTION) {
            factoryAnalysisContext = getPrecomputedAnalysisContext(factoryId);
            if (factoryAnalysisContext != null && !factoryAnalysisContext.isEmpty()) {
                systemPrompt = """
                    你是白垩纪AI Agent的智能助手。用户正在询问一个关于生产管理、质量控制或成本优化的咨询问题。
                    **重要**: 下面是该工厂的最新运营分析报告：
                    ---
                    %s
                    ---
                    请基于数据提供针对性建议，不超过500字，使用中文。
                    """.formatted(factoryAnalysisContext) + llmCostScope.harnessFor(factoryId);
            } else {
                systemPrompt = "你是白垩纪AI Agent的智能助手。请提供专业、实用的生产管理建议，不超过300字，使用中文。";
            }
        } else {
            systemPrompt = "你是白垩纪AI Agent的智能助手。友好回应，不超过100字，使用中文。";
        }

        // 经营报告 + 原话发给模型前脱敏 (报告里有客户 / 供应商 / 人名); 报告脱敏不了就不带报告, 原话也脱敏不了就不发
        // 报告存的是快照 JSON: 能解析的段当结构化记录, 先从里面认出客户 / 人员名与联系方式, 再脱敏整段提示词
        LlmEgressRedactor.Result redacted = llmEgressGuard.textsWithRecord(CONVERSATIONAL_EGRESS_SITE, factoryId,
                reportRecords(factoryAnalysisContext), Map.of(), Arrays.asList(systemPrompt, userInput));
        if (redacted.refused() && factoryAnalysisContext != null) {
            factoryAnalysisContext = null;
            redacted = llmEgressGuard.texts(CONVERSATIONAL_EGRESS_SITE, factoryId,
                    "你是白垩纪AI Agent的智能助手。请提供专业、实用的生产管理建议，不超过300字，使用中文。", userInput);
        }
        if (redacted.refused()) {
            return "抱歉，这句话里有无法脱敏的内容，没有发给 AI。您可以换个说法，或直接使用系统的查询功能。";
        }
        String sentSystemPrompt = redacted.text(0);
        String sentUserInput = redacted.text(1);

        try {
            boolean useThinkingMode = Boolean.TRUE.equals(enableThinking)
                    && questionType == QuestionType.GENERAL_QUESTION
                    && dashScopeConfig.isThinkingEnabled();
            int budget = (thinkingBudget != null && thinkingBudget >= 10 && thinkingBudget <= 100) ? thinkingBudget : 30;

            String response;
            if (useThinkingMode) {
                ChatCompletionResponse thinkingResponse =
                        dashScopeClient.chatWithThinking(sentSystemPrompt, sentUserInput, budget);
                response = thinkingResponse.getContent();
                if (thinkingResponse.hasError()) {
                    response = dashScopeClient.chat(sentSystemPrompt, sentUserInput);
                }
            } else {
                response = dashScopeClient.chat(sentSystemPrompt, sentUserInput);
            }
            response = redacted.restore(response);
            if (factoryAnalysisContext != null && !factoryAnalysisContext.isEmpty()) {
                response = numberProvenance.enforceText(
                        CONVERSATIONAL_PROVENANCE_SITE, response, factoryAnalysisContext, userInput);
            }
            return response;
        } catch (Exception e) {
            log.error("LLM 对话回复生成失败: {}", e.getMessage(), e);
            return questionType == QuestionType.GENERAL_QUESTION
                    ? "抱歉，我暂时无法回答您的问题。您可以尝试询问具体的系统操作。"
                    : "您好！有什么可以帮您的吗？";
        }
    }

    /** 「## 日报\n{...}」这样的段落里能解析成 JSON 的部分; 解析不了的段只按文本脱敏。 */
    private List<Object> reportRecords(String reportContext) {
        List<Object> records = new ArrayList<>();
        if (reportContext == null) {
            return records;
        }
        for (String section : reportContext.split("(?m)^## [^\\n]*\\n")) {
            String body = section.trim();
            if (body.startsWith("{") || body.startsWith("[")) {
                try {
                    records.add(objectMapper.readValue(body, Object.class));
                } catch (JsonProcessingException ignored) {
                    // 不是 JSON 的报告正文: 交给文本脱敏
                }
            }
        }
        return records;
    }

    // ==================== 响应构建 ====================

    private FactoryCapabilityPackRoutingPolicy.Route evaluateFactoryPackRoute(
            String factoryId, IntentExecuteRequest request, String userRole) {
        if (factoryCapabilityPackRoutingPolicy == null) {
            return new FactoryCapabilityPackRoutingPolicy.Route(
                    FactoryCapabilityPackRoutingPolicy.RouteStatus.DISABLED,
                    null,
                    null,
                    null,
                    "feature-disabled");
        }
        return factoryCapabilityPackRoutingPolicy.evaluate(
                factoryId, userRole, request != null ? request.getUserInput() : null);
    }

    private IntentExecuteResponse applyFactoryPackDecision(
            FactoryCapabilityPackRoutingPolicy.Route route, AIIntentConfig intent) {
        if (route == null || !route.isConstrained()) {
            return null;
        }
        boolean writeIntent = writeGuardService.isWriteIntent(intent);
        FactoryCapabilityPackRoutingPolicy.ExecutionDecision decision =
                factoryCapabilityPackRoutingPolicy.authorize(
                        route, intent.getIntentCode(), intent.getToolName(), writeIntent);
        IntentExecuteResponse response = switch (decision.status()) {
            case ALLOW_READ, NOT_APPLICABLE -> null;
            case GUIDANCE -> buildFactoryPackWorkflowGuidance(
                    route, intent, decision.workflowReference());
            case NO_MATCH -> {
                IntentExecuteResponse rejected = buildFactoryPackNoMatch(route, decision.reason());
                // The policy already classified this candidate. Do not resolve a
                // rejected external binding merely to decorate its refusal.
                rejected.setAiMode(writeIntent ? "WRITE" : "READ");
                yield rejected;
            }
        };
        applyAiModeStamp(response, intent, intent.getToolName());
        return response;
    }

    private IntentExecuteResponse buildFactoryPackNoMatch(
            FactoryCapabilityPackRoutingPolicy.Route route, String reason) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeType", "FACTORY_CAPABILITY_PACK");
        metadata.put("policyResult", "NO_MATCH");
        metadata.put("reason", reason == null ? "not-allowed" : reason);
        if (route != null && route.pack() != null) {
            metadata.put("packId", route.pack().packId());
            metadata.put("packVersion", route.pack().version());
        }
        String message = "该请求不在当前工厂岗位能力包的受控范围内。"
                + "请改为询问本岗位已配置的查询、表单或固定导航事项。";
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("FACTORY_PACK_NO_MATCH")
                .message(message)
                .formattedText(message)
                .metadata(Map.copyOf(metadata))
                .executedAt(LocalDateTime.now())
                .build();
    }

    private IntentExecuteResponse buildFactoryPackWorkflowGuidance(
            FactoryCapabilityPackRoutingPolicy.Route route,
            AIIntentConfig intent,
            FactoryCapabilityPack.WorkflowReference reference) {
        boolean mutation = reference.mutation();
        String message = mutation
                ? "该请求涉及业务写入，AI 助手不会代为执行。请通过受控入口「"
                        + reference.referenceId() + "」核对并确认。"
                : "请通过能力包声明的固定入口「" + reference.referenceId() + "」继续。";

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("workflowReference", reference.referenceId());
        parameters.put("workflowType", reference.type().name());
        parameters.put("requiresUserConfirmation", mutation);
        parameters.put("approvalRequired", reference.approvalRequired());

        IntentExecuteResponse.SuggestedAction action =
                IntentExecuteResponse.SuggestedAction.builder()
                        .actionCode("OPEN_FACTORY_WORKFLOW")
                        .actionName(mutation ? "打开确认入口" : "打开固定入口")
                        .description(message)
                        .parameters(Map.copyOf(parameters))
                        .build();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("routeType", "FACTORY_CAPABILITY_PACK");
        metadata.put("policyResult", "WORKFLOW_GUIDANCE");
        metadata.put("packId", route.pack().packId());
        metadata.put("packVersion", route.pack().version());
        metadata.put("workflowReference", reference.referenceId());
        metadata.put("workflowType", reference.type().name());
        metadata.put("mutation", mutation);
        metadata.put("approvalRequired", reference.approvalRequired());

        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("FACTORY_PACK_WORKFLOW_GUIDANCE")
                .aiMode(mutation ? "WRITE" : "READ")
                .message(message)
                .formattedText(message)
                .requiresApproval(reference.approvalRequired())
                .suggestedActions(List.of(action))
                .metadata(Map.copyOf(metadata))
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 意图级权限检查 (P1 读写分块): required_permission 非空 → IntentPermissionGate
     * (module:action 矩阵, fail-closed, 拒绝时回带缺失权限码); 空 → 保留
     * aiIntentService.hasPermission 的 requiredRoles 旧逻辑 (兼容期)。
     *
     * @return null=放行; 非 null=拒绝响应
     */
    private IntentExecuteResponse checkIntentPermission(AIIntentConfig intent, String factoryId,
                                                        Long userId, String userRole) {
        if (SupplierComparisonReadIntent.CODE.equals(intent.getIntentCode())
                && !hasBoundSupplierFactoryActor(factoryId, userId)) {
            return buildNoPermissionResponse(intent);
        }
        if ("PURCHASE_ORDER_CREATE".equals(intent.getIntentCode()) && intentPermissionGate == null) {
            return buildNoPermissionResponse(intent);
        }
        String requiredPermission = intent.getRequiredPermission();
        // intentPermissionGate 为 Spring 常驻 @Component; null 仅出现在裸构造的单测, 回落旧逻辑
        if (requiredPermission != null && !requiredPermission.isBlank() && intentPermissionGate != null) {
            IntentPermissionGate.PermissionCheck check =
                    intentPermissionGate.check(intent, userId, userRole);
            if (check.isDenied()) {
                IntentExecuteResponse denied = buildNoPermissionResponse(intent);
                denied.setRequiredPermission(check.requiredPermission());
                // 权限码路径的拒绝文案按码提示 (旧文案基于 requiredRoles, 此路径下可能为 null)
                String deniedMsg = "您没有权限执行此操作。需要权限: " + check.requiredPermission()
                        + "，请联系工厂管理员开通。";
                denied.setMessage(deniedMsg);
                denied.setFormattedText(deniedMsg);
                return denied;
            }
            return null;
        }
        if (!(SupplierComparisonReadIntent.CODE.equals(intent.getIntentCode())
                ? aiIntentService.hasPermission(factoryId, intent.getIntentCode(), userRole)
                : aiIntentService.hasPermission(intent.getIntentCode(), userRole))) {
            return buildNoPermissionResponse(intent);
        }
        return null;
    }

    private boolean hasBoundSupplierFactoryActor(String factoryId, Long userId) {
        return AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == AuthenticatedActorType.FACTORY_USER)
                .filter(actor -> Objects.equals(actor.id(), userId))
                .filter(actor -> Objects.equals(actor.factoryId(), factoryId))
                .isPresent();
    }

    private boolean hasNamedSession(IntentExecuteRequest request) {
        return request != null && request.getSessionId() != null && !request.getSessionId().isBlank();
    }

    private boolean hasTrustedConversationIdentity(String factoryId, Long userId, String sessionId) {
        return hasBoundSupplierFactoryActor(factoryId, userId)
                || DemoConversationScope.allows(factoryId, userId, sessionId);
    }

    private boolean canUseStatefulConversation(String factoryId, Long userId, IntentExecuteRequest request) {
        return hasBoundSupplierFactoryActor(factoryId, userId)
                || (request != null && DemoConversationScope.allows(
                        factoryId, userId, request.getSessionId()));
    }

    private IntentExecuteResponse sessionAdmissionDeniedResponse() {
        String message = "当前会话身份未通过工厂/演示会话绑定校验，未执行操作。";
        return IntentExecuteResponse.builder()
                .intentRecognized(false).status("NO_PERMISSION")
                .message(message).formattedText(message)
                .executedAt(java.time.LocalDateTime.now()).build();
    }

    /**
     * P1 读写分块 §4.5: factoryId 是否 demo 租户 (cretas.demo.factory-ids 名单, 逗号分隔)。
     */
    private boolean isDemoFactory(String factoryId) {
        if (factoryId == null || demoFactoryIdsCsv == null || demoFactoryIdsCsv.isBlank()) {
            return false;
        }
        for (String demoId : demoFactoryIdsCsv.split(",")) {
            if (factoryId.equals(demoId.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * P1 读写分块: 终点统一补 aiMode (READ|WRITE)。已显式设置的响应 (拦截卡/确认卡/权限卡)
     * 保持不变; 其余按 WriteGuardService 意图判定, 意图未判写时以已匹配工具兜底。
     */
    private void applyAiModeStamp(IntentExecuteResponse response, AIIntentConfig intent, String matchedToolName) {
        if (response == null || response.getAiMode() != null) {
            return;
        }
        boolean write = intent != null && writeGuardService != null && writeGuardService.isWriteIntent(intent);
        if (!write && matchedToolName != null && !matchedToolName.isEmpty()) {
            try {
                write = toolRegistry.getExecutor(matchedToolName)
                        .map(writeGuardService::isWriteTool)
                        .orElse(false);
            } catch (Exception e) {
                // aiMode 是展示元数据, 判定失败不阻断响应
            }
        }
        response.setAiMode(write ? "WRITE" : "READ");
    }

    /**
     * spec §8.2 消费点②: 咨询 tab 的写拦截从"纯启发式"改成"声明优先 + 启发式兜底"。
     *
     * <p>纯 {@code writeGuardService.isWriteIntent} 只看意图的 sensitivityLevel 和 intentCode
     * 后缀, 对绑定了写 Tool 但意图码看不出写意味的组合无能为力 —— 实测有 39 个工具属于这种
     * (canvas_add_field / dictionary_add / create_new_intent / update_intent / scale_add_device
     * ...), 它们绑定的意图在咨询 tab 下会被直接放行执行。改走
     * {@link IntentAccessModeFilter#isWriteIntent(AIIntentConfig)} 后, 绑定 Tool 的
     * {@code getAccessMode()} 声明成为第一判据, 原启发式保留为第二判据 (任一判写即拦)。
     */
    private boolean isWriteIntentDeclarationAware(AIIntentConfig intent) {
        return isWriteIntentDeclarationAware(intent, intentAccessModeFilter, writeGuardService);
    }

    /**
     * 同上, 但把两个判定器显式传进来 —— {@code SseStreamingService} 复用同一份实现。
     * 两个入口对同一个意图的写判定必须一致, 否则同一句话在流式和非流式下会走出不同的路
     * (card4 2026-07-28 已经踩过一次同形状的副本漂移, 见 {@link #hasExplicitReadVeto})。
     */
    static boolean isWriteIntentDeclarationAware(AIIntentConfig intent,
                                                 IntentAccessModeFilter accessModeFilter,
                                                 com.cretas.aims.ai.tool.WriteGuardService writeGuard) {
        if (accessModeFilter != null) {
            try {
                return accessModeFilter.isWriteIntent(intent);
            } catch (Exception e) {
                log.warn("声明式写判定异常, 回落启发式: intentCode={}, err={}",
                        intent != null ? intent.getIntentCode() : null, e.getMessage());
            }
        }
        return writeGuard.isWriteIntent(intent);
    }

    /**
     * spec §8.2: 只在 OPERATE tab 才解析权限全集 —— READ tab 的过滤规则是"写意图一律剔除",
     * 与权限无关, 不必为它多打一次 User 查询; mode 为空 (旧调用方 / 非双 tab 入口) 同样不查。
     *
     * @return 权限码集合; null = 不按权限过滤 (真正的鉴权在 checkIntentPermission / ToolRbacEnforcer)
     */
    private java.util.Set<String> resolveUserPermissionsForFiltering(String mode, Long userId, String factoryId) {
        if (userId == null || intentPermissionGate == null
                || !IntentAccessModeFilter.MODE_OPERATE.equalsIgnoreCase(mode)) {
            return null;
        }
        try {
            return intentPermissionGate.resolveUserPermissions(userId, factoryId);
        } catch (Exception e) {
            log.warn("候选过滤权限集解析失败, 跳过权限过滤: userId={}, err={}", userId, e.getMessage());
            return null;
        }
    }

    /**
     * P1 读写分块 §4.4: 咨询 tab (mode=READ) 命中写意图/写工具时的拦截响应。
     * aiMode=WRITE 供前端渲染「前往操作页」跳转卡。
     */
    private IntentExecuteResponse buildReadModeWriteBlockedResponse(AIIntentConfig intent) {
        String msg = "这是操作类请求，请切换到【操作】页处理。";
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("READ_MODE_WRITE_BLOCKED").message(msg).formattedText(msg)
                .aiMode("WRITE")
                .executedAt(LocalDateTime.now()).build();
    }

    private IntentExecuteResponse buildNoPermissionResponse(AIIntentConfig intent) {
        String msg = "您没有权限执行此操作。需要角色: " + intent.getRequiredRoles();
        IntentExecuteResponse response = IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("NO_PERMISSION").message(msg).formattedText(msg)
                .executedAt(LocalDateTime.now()).build();
        applyAiModeStamp(response, intent, intent.getToolName());
        return response;
    }

    private IntentExecuteResponse buildApprovalResponse(AIIntentConfig intent) {
        String msg = "此操作需要审批，当前 AI 入口尚不支持提交和核验审批。未提交审批、未执行操作，请通过业务页面完成审批。";
        IntentExecuteResponse response = IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .sensitivityLevel(intent.getSensitivityLevel())
                .status("PENDING_APPROVAL").message(msg).formattedText(msg)
                .requiresApproval(true).approvalChainId(intent.getApprovalChainId())
                .executedAt(LocalDateTime.now()).build();
        applyAiModeStamp(response, intent, intent.getToolName());
        return response;
    }

    private IntentExecuteResponse buildDroolsFailureResponse(AIIntentConfig intent, ValidationResult validationResult) {
        String msg = "业务规则验证未通过: " + validationResult.getViolationsSummary();
        IntentExecuteResponse response = IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName()).intentCategory(intent.getIntentCategory())
                .status("VALIDATION_FAILED").message(msg).formattedText(msg)
                .validationViolations(validationResult.getViolations())
                .recommendations(validationResult.getRecommendations())
                .executedAt(LocalDateTime.now()).build();
        applyAiModeStamp(response, intent, intent.getToolName());
        return response;
    }

    /**
     * Sprint 13 #305 业态门控 — gate domain-exclusive intents to the matching factory.type.
     *
     * <p>Cretas serves 2 customer types: RESTAURANT (餐厅, e.g. RES_3101_009) and FACTORY
     * (制造厂, e.g. F006 六膳门卤味). A RESTAURANT-exclusive intent (business_type=RESTAURANT,
     * e.g. {@code RESTAURANT_ECONOMICS_ANALYSIS}) triggered on a FACTORY-type factory has no
     * data to operate on → previously returned a misleading half-broken "数据不可用". This gate
     * returns an honest "本厂非餐厅业态" message + a domain-appropriate next-action instead
     * (per {@code .claude/rules/fool-proof-design.md} Rule 5: dead-end → next action).
     *
     * <p>Only DOMAIN-EXCLUSIVE intents gate; {@code COMMON}/null business_type are universal
     * and always pass (anti-goal: 不挡通用 intent). Fail-soft: any domain-lookup error → pass.
     *
     * @return honest empty-state response on业态 mismatch; {@code null} to proceed with execution.
     */
    private IntentExecuteResponse checkBusinessTypeGate(String factoryId, AIIntentConfig intent) {
        // Delegates to the shared BusinessTypeGate so the main execute() flow, the explicit-intent
        // flow, and SseStreamingService all gate identically (Sprint 13 #305).
        IntentExecuteResponse response = businessTypeGate.check(factoryId, intent).orElse(null);
        applyAiModeStamp(response, intent, intent.getToolName());
        return response;
    }

    /**
     * W1b: clarification response for a negation VETO_READ in the execution layer.
     * Nothing is recognized or executed (intentRecognized=false) — the orchestrator's
     * contains-based phrase shortcut is NOT consulted, so a negated read like "别给我看订单"
     * can no longer execute the embedded "看订单" intent.
     */
    IntentExecuteResponse buildNegationVetoClarificationResponse(String userInput) {
        final String msg = "您是要取消这次操作吗?需要我帮您查询或处理什么?";
        log.info("[W1b] orchestrator negation veto clarification for input='{}'", userInput);
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NEED_CLARIFICATION")
                .message(msg)
                .formattedText(msg)
                .executedAt(LocalDateTime.now())
                .build();
    }



    IntentExecuteResponse buildClarificationResponse(IntentMatchResult matchResult, String factoryId) {
        return buildClarificationResponse(matchResult, factoryId, null, null, null);
    }

    IntentExecuteResponse buildClarificationResponse(IntentMatchResult matchResult, String factoryId,
                                                     Long userId, String userRole, String mode) {
        AIIntentConfig matchedIntent = matchResult.getBestMatch();
        List<IntentExecuteResponse.SuggestedAction> candidateActions =
                buildCandidateActions(matchResult, factoryId, userId, userRole, mode);
        // 与 SSE 同一份话术: 有业务候选就列出来; 一个都没有就明确说没有, 不用默认业务按钮凑数。
        String clarificationMessage = ClarificationCandidateService.clarificationMessage(candidateActions);

        Map<String, Object> metadata = new HashMap<>();
        if (matchResult.getSessionId() != null && !matchResult.getSessionId().isEmpty()) {
            metadata.put("sessionId", matchResult.getSessionId());
            metadata.put("needMoreInfo", true);
            if (matchResult.getConversationMessage() != null) {
                metadata.put("conversationMessage", matchResult.getConversationMessage());
            }
        }

        IntentExecuteResponse.IntentExecuteResponseBuilder builder = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(matchedIntent.getIntentCode())
                .intentName(matchedIntent.getIntentName())
                .intentCategory(matchedIntent.getIntentCategory())
                .status("NEED_CLARIFICATION")
                .message(clarificationMessage)
                .formattedText(clarificationMessage)
                .confidence(matchResult.getConfidence())
                .matchMethod(matchResult.getMatchMethod() != null ? matchResult.getMatchMethod().name() : null)
                .suggestedActions(candidateActions)
                .executedAt(LocalDateTime.now());

        if (!metadata.isEmpty()) builder.metadata(metadata);
        IntentExecuteResponse response = builder.build();
        applyAiModeStamp(response, matchedIntent, matchedIntent.getToolName());
        return response;
    }

    private IntentExecuteResponse buildNoMatchResponse(IntentMatchResult matchResult,
                                                       String factoryId,
                                                       IntentExecuteRequest request,
                                                       Long userId,
                                                       String userRole) {
        {}

        Map<String, Object> metadata = new HashMap<>();
        if (matchResult.getSessionId() != null && !matchResult.getSessionId().isEmpty()) {
            metadata.put("sessionId", matchResult.getSessionId());
            metadata.put("needMoreInfo", true);
            if (matchResult.getConversationMessage() != null) {
                metadata.put("conversationMessage", matchResult.getConversationMessage());
            }
        }

        if (matchResult.getTopCandidates() != null && !matchResult.getTopCandidates().isEmpty()) {
            List<IntentExecuteResponse.SuggestedAction> candidateActions = buildCandidateActions(
                    matchResult, factoryId, userId, userRole, request == null ? null : request.getMode());
            String weakSignalMsg = ClarificationCandidateService.clarificationMessage(candidateActions);
            IntentExecuteResponse.IntentExecuteResponseBuilder builder = IntentExecuteResponse.builder()
                    .intentRecognized(false).status("NEED_CLARIFICATION")
                    .message(weakSignalMsg).formattedText(weakSignalMsg)
                    .suggestedActions(candidateActions).executedAt(LocalDateTime.now());
            if (!metadata.isEmpty()) builder.metadata(metadata);
            return builder.build();
        }

        List<IntentExecuteResponse.SuggestedAction> defaultSuggestions = buildDefaultSuggestions(factoryId);
        // Sprint 12 Task 5: enrich default no-match message with inline choices + domain hint
        // so audit content_len ≥80 + chinese_run ≥20 + actionable for user.
        String message = matchResult.getConversationMessage() != null && !matchResult.getConversationMessage().isEmpty()
                ? matchResult.getConversationMessage()
                : "我没有理解您的意图。" + buildChoicesLine(defaultSuggestions)
                        + "请回复对应序号, 或更详细描述需求 (例如指定客户 / 物料 / 批次 / 月份 / 工序)。";

        IntentExecuteResponse.IntentExecuteResponseBuilder builder = IntentExecuteResponse.builder()
                .intentRecognized(false).status("NEED_CLARIFICATION")
                .message(message).formattedText(message)
                .executedAt(LocalDateTime.now()).suggestedActions(defaultSuggestions);
        if (!metadata.isEmpty()) builder.metadata(metadata);
        return builder.build();
    }

    private IntentExecuteResponse buildValidationFailureResponse(String factoryId, String userInput,
                                                                   LlmSchemaValidationException e) {
        String truncatedInput = userInput != null && userInput.length() > 30 ? userInput.substring(0, 30) + "..." : userInput;
        String clarificationMessage = switch (e.getFailureType()) {
            case PARSE_ERROR -> String.format("AI 无法正确理解您的请求「%s」，请重新描述您的需求。", truncatedInput);
            case UNKNOWN_INTENT_CODE -> "AI 识别的操作类型无法执行，请从常用操作中选择。";
            case INVALID_CONFIDENCE -> String.format("AI 对您的请求「%s」理解不够确定，请更详细地描述。", truncatedInput);
            default -> String.format("AI 无法准确理解您的意图「%s」，请重新描述。", truncatedInput);
        };

        List<IntentExecuteResponse.SuggestedAction> suggestedActions = new ArrayList<>();
        suggestedActions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述您的需求")
                .description("请尝试用不同的方式描述").build());
        suggestedActions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SHOW_INTENTS").actionName("从常用操作中选择")
                .endpoint("/api/mobile/" + factoryId + "/ai-intents").build());

        return IntentExecuteResponse.builder()
                .intentRecognized(false).status("VALIDATION_FAILED")
                .message(clarificationMessage).suggestedActions(suggestedActions)
                .metadata(Map.of("validationFailureType", e.getFailureType().name(),
                        "requiresDoubleCheck", true))
                .executedAt(LocalDateTime.now()).build();
    }

    private IntentExecuteResponse buildRAGResponse(String responseContent, RAGRouteResult ragRouteResult, String routeType) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("routeType", routeType);
        metadata.put("consultationType", ragRouteResult.getConsultationType().name());
        metadata.put("confidence", ragRouteResult.getConfidence());
        String status = ragRouteResult.isHighConfidence() ? "RAG_COMPLETED" : "RAG_COMPLETED_LOW_CONFIDENCE";
        return IntentExecuteResponse.builder()
                .intentRecognized(false).status(status).message(responseContent)
                .metadata(metadata).executedAt(LocalDateTime.now()).build();
    }

    // ==================== 工具方法 ====================

    /**
     * Sprint 12 Task 2 — build clarification message with inline choices so
     * formattedText carries the 2+ choice question (not just suggestedActions UI metadata).
     *
     * <p>Audit close-gate row "NEED_CLARIFICATION contains specific 2+ choices" requires
     * the user-facing text to enumerate the candidate intents inline.
     */
    /**
     * Sprint 12 close-gate row "NEED_CLARIFICATION returns specific 2-or-3-choice question"
     * requires ≥2 data choices in the user-visible text. When the candidate set has <2
     * data actions (filtering REPHRASE / SHOW_INTENTS), pad with default common queries.
     */
    List<IntentExecuteResponse.SuggestedAction> ensureMinChoices(
            List<IntentExecuteResponse.SuggestedAction> actions, int minCount, String factoryId) {
        List<IntentExecuteResponse.SuggestedAction> dataActions = new ArrayList<>();
        if (actions != null) {
            for (IntentExecuteResponse.SuggestedAction a : actions) {
                if (a == null) continue;
                String code = a.getActionCode();
                if ("REPHRASE".equals(code) || "SHOW_INTENTS".equals(code)) continue;
                if (a.getActionName() == null || a.getActionName().isBlank()) continue;
                dataActions.add(a);
            }
        }
        if (dataActions.size() >= minCount) return actions;
        // C1: pad with factory-shared capabilities only; retired restaurant intents must
        // never be suggested as a fallback choice.
        IntentExecuteResponse.SuggestedAction[] padCandidates = new IntentExecuteResponse.SuggestedAction[] {
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("MATERIAL_BATCH_QUERY").actionName("查询原料库存").build(),
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("PROCESSING_BATCH_LIST").actionName("查询生产批次").build(),
                        IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("DAILY_CUSTOMER_FOLLOWUP").actionName("查询今日待跟进客户").build(),
                };
        java.util.Set<String> existingNames = new java.util.HashSet<>();
        for (IntentExecuteResponse.SuggestedAction a : dataActions) existingNames.add(a.getActionName());
        List<IntentExecuteResponse.SuggestedAction> padded = new ArrayList<>(actions != null ? actions : new ArrayList<>());
        for (IntentExecuteResponse.SuggestedAction p : padCandidates) {
            if (dataActions.size() >= minCount) break;
            if (existingNames.contains(p.getActionName())) continue;
            padded.add(0, p);  // prepend padding before REPHRASE/SHOW_INTENTS sentinels
            dataActions.add(p);
        }
        return padded;
    }

    /**
     * Render top candidate intents as a "1. X 2. Y 3. Z" choice line.
     * Skips REPHRASE / SHOW_INTENTS placeholders (they're UI affordances, not data choices).
     */
    private String buildChoicesLine(List<IntentExecuteResponse.SuggestedAction> candidateActions) {
        if (candidateActions == null || candidateActions.isEmpty()) {
            return "\n暂无候选操作可建议。\n";
        }
        StringBuilder sb = new StringBuilder("\n");
        int idx = 1;
        for (IntentExecuteResponse.SuggestedAction action : candidateActions) {
            if (action == null) continue;
            String code = action.getActionCode();
            if ("REPHRASE".equals(code) || "SHOW_INTENTS".equals(code)) continue;
            String name = action.getActionName();
            if (name == null || name.isBlank()) continue;
            sb.append(idx++).append(". ").append(name);
            String desc = action.getDescription();
            if (desc != null && !desc.isBlank() && !desc.startsWith("置信度")) {
                sb.append(" — ").append(desc);
            }
            sb.append("\n");
            if (idx > 3) break;  // cap at top 3 data choices
        }
        if (idx == 1) {
            // No data choices found, only UI affordances
            sb.append("暂无候选操作可建议。\n");
        }
        return sb.toString();
    }

    // ==================== C1 业态过滤 helpers (restaurant-chat-qa) ====================

    /**
     * Resolve authoritative metadata; admission rejects null instead of assuming manufacturing.
     */
    String resolveFactoryDomainSafe(String factoryId) {
        try {
            return configService.resolveBusinessDomain(factoryId);
        } catch (Exception e) {
            log.warn("[C1业态过滤] resolveBusinessDomain failed for factoryId={} — metadata unavailable: {}",
                    factoryId, e.getMessage());
            return null;
        }
    }

    private IntentExecuteResponse analysisAdmissionResponse(String factoryId, String userRole) {
        if (com.cretas.aims.util.RetiredAnalysisContext.isRetiredIdentity(factoryId, userRole)) {
            return retiredCapabilityResponse();
        }
        String domain = resolveFactoryDomainSafe(factoryId);
        try {
            com.cretas.aims.util.RetiredAnalysisContext.requireFactory(factoryId, domain, userRole);
            return null;
        } catch (com.cretas.aims.exception.BusinessException unavailable) {
            if (unavailable.getCode() == 410) return retiredCapabilityResponse();
            return IntentExecuteResponse.builder().intentRecognized(false)
                    .status("TENANT_TYPE_UNAVAILABLE")
                    .message(unavailable.getMessage())
                    .metadata(java.util.Map.of("errorCode", "TENANT_TYPE_UNAVAILABLE", "httpStatus", 503))
                    .executedAt(LocalDateTime.now()).build();
        }
    }

    /**
     * Is a candidate intent (by code) compatible with the factory's business domain?
     * Looks up the intent's business_type via configService and delegates to the single
     * source of truth {@link com.cretas.aims.ai.tool.BusinessTypeScope#isCompatible}.
     * Fail-soft: any lookup error → {@code true} (keep the candidate, don't crash).
     *
     * @param factoryDomain resolved factory domain ("RESTAURANT"/"FACTORY"); when null the
     *                      method returns true (no filtering when domain is unknown).
     */
    boolean isCandidateCompatible(String factoryId, String factoryDomain, String intentCode) {
        if (factoryDomain == null || intentCode == null) {
            return true; // unknown domain or missing code → don't filter
        }
        try {
            String intentBiz = configService.getIntentByCode(factoryId, intentCode)
                    .map(AIIntentConfig::getBusinessType)
                    .orElse(null);
            return com.cretas.aims.ai.tool.BusinessTypeScope.isCompatible(intentBiz, factoryDomain);
        } catch (Exception e) {
            log.warn("[C1业态过滤] business_type lookup failed for intent={} factory={} — keep candidate: {}",
                    intentCode, factoryId, e.getMessage());
            return true; // fail-soft
        }
    }

    // R20: 域外闲聊词 (天气/新闻/股票/彩票) 与经营关联词。

    /**
     * Actual menu mutations belong to the governed WRITE intent flow, never to
     * the read-only owner-advice conversation.  In particular, a stale
     * ownerActionSessionId must not turn "删除/下架菜品" into advisory chat.
     */

    private void putIfPresent(Map<String, Object> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String stringValueOrDefault(Object value, String fallback) {
        String actual = stringValue(value);
        return actual == null || actual.isBlank() ? fallback : actual;
    }

    private String firstNonBlank(String first, String second, String fallback) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return fallback;
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private List<Map<String, Object>> normalizeOwnerActionFollowups(Object followUps, String scenario) {
        if (!(followUps instanceof List<?> items)) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> normalized = new ArrayList<>();
        for (Object item : items) {
            String question;
            String label;
            String itemScenario = scenario;
            if (item instanceof Map<?, ?> followupMap) {
                question = firstNonBlank(
                        stringValue(followupMap.get("question")),
                        stringValue(followupMap.get("text")),
                        stringValue(followupMap.get("label")));
                label = firstNonBlank(stringValue(followupMap.get("label")), question, question);
                itemScenario = firstNonBlank(stringValue(followupMap.get("ownerActionScenario")), scenario, scenario);
            } else {
                question = stringValue(item);
                label = question;
            }
            if (question == null || question.isBlank()) {
                continue;
            }
            Map<String, Object> followup = new LinkedHashMap<>();
            followup.put("label", label != null && label.length() > 18 ? label.substring(0, 18) + "..." : label);
            followup.put("question", question);
            if (itemScenario != null && !itemScenario.isBlank()) {
                followup.put("ownerActionScenario", itemScenario);
            }
            normalized.add(followup);
            if (normalized.size() >= 4) {
                break;
            }
        }
        return normalized;
    }

    /**
     * 澄清候选: 与 SSE 同一个服务 ({@link ClarificationCandidateService}) —— 按当前工厂配置、业态、模式、
     * 意图权限与一线受众逐个核过才出; 业务候选为 0 时只有「重新描述」(明确失败), 不再回落未过滤的
     * 识别候选或默认业务按钮 (INTENT-DISTILL D2)。
     */
    private List<IntentExecuteResponse.SuggestedAction> buildCandidateActions(IntentMatchResult matchResult,
                                                                          String factoryId, Long userId,
                                                                          String userRole, String mode) {
        if (clarificationCandidateService == null) {
            return ClarificationCandidateService.rephraseOnly();
        }
        return clarificationCandidateService.buildCandidateActions(matchResult, factoryId, userId, userRole, mode);
    }

    /**
     * Factory clarification choices: inventory and production batches.
     * Package-private for unit testing.
     */
    List<IntentExecuteResponse.SuggestedAction> buildDefaultSuggestions(String factoryId) {
        List<IntentExecuteResponse.SuggestedAction> actions = new ArrayList<>();
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("MATERIAL_BATCH_QUERY").actionName("查询原料库存")
                .endpoint("/api/mobile/" + factoryId + "/material-batches").build());
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("PROCESSING_BATCH_LIST").actionName("查询生产批次")
                .endpoint("/api/mobile/" + factoryId + "/processing/batches").build());
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("REPHRASE").actionName("重新描述").build());
        actions.add(IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SHOW_INTENTS").actionName("查看所有可用操作")
                .endpoint("/api/mobile/" + factoryId + "/ai-intents").build());
        return actions;
    }

    /**
     * 自由提问识别到工作台意图、走 Skill 分支时的收尾: 与显式意图路径 ({@link #executeWithExplicitIntent})
     * 同一套展示处理 —— 补问引导 → 结果格式化 → formattedText 兜底 (含 {@link WorkdeskOutputSummarizer}
     * 脱敏摘要) → aiMode。以前这里直接 return, 组合结果的子工具是 JsonNode 时用户只拿到
     * 「未返回可直接展示的明细数据」那句通用话 (2026-10-25 F006「今天要跟进哪些客户」线上实测)。
     *
     * <p>缓存与对话记忆刻意不在这里补: 对话记忆会把还原后的真名写进历史, 带历史的模型调用尚未接入脱敏。
     */
    private IntentExecuteResponse finishSkillBranchResponse(String factoryId, IntentExecuteRequest request,
                                                            AIIntentConfig intent, Long userId,
                                                            IntentExecuteResponse response) {
        IntentExecuteResponse finished = response;
        if ("NEED_MORE_INFO".equals(finished.getStatus())) {
            finished = enrichWithClarificationQuestions(finished, request, intent, factoryId, userId);
        }
        boolean priceMasked = applyCallerPriceView(finished, userId);
        applyResultFormatting(finished, userId);
        applyFormattedTextFallback(factoryId, finished, priceMasked);
        applyAiModeStamp(finished, intent, intent.getToolName());
        return finished;
    }

    private void applyResultFormatting(IntentExecuteResponse response, Long userId) {
        String status = response.getStatus();
        boolean isSuccessStatus = "SUCCESS".equals(status) || "COMPLETED".equals(status);
        if (resultFormatterService != null && isSuccessStatus && response.getResultData() != null) {
            try {
                resultFormatterService.formatAndSet(response, userId);
            } catch (Exception e) {
                log.debug("结果格式化失败（非致命）: {}", e.getMessage());
            }
        }
    }

    /** 客户可见文本内部泄漏消毒 (Sheet 7/22 V7): LLM 意图分类/rerank 的推理
     * 文本 ("在提供的可用意图列表中, 只有 PRODUCT_XXX…") 偶发被当回答返回。
     * 同时含意图元讨论词与 CODE 形状 token 的消息不可能是合法业务答案。 */
    private static final java.util.regex.Pattern INTERNAL_CODE_TOKEN =
            java.util.regex.Pattern.compile("[A-Z]{2,}_[A-Z0-9_]{2,}");

    private void sanitizeInternalReasoningLeak(IntentExecuteResponse response) {
        String msg = response.getMessage();
        if (msg == null) {
            return;
        }
        boolean metaTalk = msg.contains("意图列表") || msg.contains("可用意图")
                || msg.contains("候选意图") || msg.contains("intent_code")
                || msg.contains("该意图覆盖");
        if (metaTalk && INTERNAL_CODE_TOKEN.matcher(msg).find()) {
            log.warn("[sanitize] internal intent reasoning leaked to customer text, replaced. head={}",
                    msg.substring(0, Math.min(80, msg.length())));
            String safe = "这个问题我还没有把握直接回答。请换一种问法，"
                    + "或说明想看的指标（如营收/销量/毛利）和时间范围，我再帮您查。";
            response.setMessage(safe);
            response.setFormattedText(safe);
        }
    }

    /** 价格 / 财务可见性的唯一判定 (与 HTTP 响应层同一个); 没装配时不让模型看结果数据。 */
    @Autowired(required = false)
    private com.cretas.aims.security.PriceFieldResponseAdvice priceView;

    /**
     * 把 resultData 换成这个人看得到的那一份 (ERP-S6-LLM-PROMPT-PRICE-LEAK): 之后的格式化、交给模型写的摘要、无模型兜底、
     * 对话记忆、缓存都只碰得到遮过的数据 —— 模型看不到的数, 写不进回复。
     * 线上 (acc-s6, F006): 生产 / 销售 / 采购主管问「查看财务报表」, 字段已遮, 摘要模型却拿原始结果写出
     * 「总成本合计 3.00 元, 设备成本 2.00 元…总利润 -3.00 元」。
     *
     * @return true = 数据已按这个人遮过 (或本来就没有数据); false = 判定组件没装配, 后面不许把数据交给模型
     */
    private boolean applyCallerPriceView(IntentExecuteResponse response, Long userId) {
        if (response == null || response.getResultData() == null) {
            return true;
        }
        if (priceView == null) {
            log.warn("价格可见性组件未装配: 结果数据不交给摘要模型 (fail closed)");
            return false;
        }
        response.setResultData(priceView.maskedViewFor(response.getResultData(), userId));
        return true;
    }

    private void applyFormattedTextFallback(String factoryId, IntentExecuteResponse response, boolean priceMasked) {
        sanitizeInternalReasoningLeak(response);
        String currentFT = response.getFormattedText();
        String msg = response.getMessage();
        boolean ftMissing = currentFT == null;
        boolean ftGeneric = currentFT != null && GENERIC_SHORT_REPLIES.contains(currentFT.trim());
        boolean ftShort = currentFT != null && currentFT.length() < 15;
        if ((ftMissing || ftGeneric || ftShort) && msg != null && msg.length() >= 20
                && msg.length() > (currentFT != null ? currentFT.length() : 0)) {
            response.setFormattedText(msg);
        } else if (ftMissing && msg != null && msg.length() >= 5) {
            response.setFormattedText(msg);
        }
        // Ultimate fallback
        if (response.getFormattedText() == null && response.getMessage() != null && !response.getMessage().isEmpty()) {
            response.setFormattedText(response.getMessage());
        }

        // 反向兜底 (2026-07-23 工厂 AI 复盘): message 是占位符/空 而 formattedText
        // 有实质答案时, 用 formattedText 覆盖 message。此前只有 formattedText←message
        // 单向, 导致 PROCESSING_BATCH_LIST 等查询 message="操作已完成。" 掩盖了
        // formattedText 里 "查询到 487 个生产批次…" 的真答案 (Sheet 行15 工厂版)。
        String finalMsg = response.getMessage();
        String finalFT = response.getFormattedText();
        boolean msgMissing = finalMsg == null || finalMsg.isBlank();
        boolean msgGeneric = finalMsg != null && GENERIC_SHORT_REPLIES.contains(finalMsg.trim());
        if ((msgMissing || msgGeneric) && finalFT != null && finalFT.length() >= 20
                && !GENERIC_SHORT_REPLIES.contains(finalFT.trim())) {
            response.setMessage(finalFT);
        }

        // Sprint 9 P0.2 (2026-05-22) — Terminal LLM-summarize gate for Workdesk / Skill orchestration
        // outputs. Detects underscore-prefixed metadata leaks (_toolCount / _executionOrder),
        // raw JSON dumps (5989-char sales-owner case), and bare templates ("包含 N 项数据指标").
        // Pipes through DashScopeClient.chatFast() for Chinese natural-language summary.
        // Opt-out via cretas.ai.workdesk-summarizer.enabled=false.
        if (workdeskOutputSummarizer != null && priceMasked) {
            try {
                workdeskOutputSummarizer.apply(response, factoryId);
            } catch (Exception e) {
                log.warn("WorkdeskOutputSummarizer 应用失败 (non-blocking): {}", e.getMessage());
            }
        }
    }

    private void processResponseCaching(String factoryId, IntentExecuteRequest request,
                                         IntentMatchResult matchResult, IntentExecuteResponse response) {
        try {
            if (request.getContext() != null) {
                Object cacheHit = request.getContext().get("__cacheHit");
                if (Boolean.TRUE.equals(cacheHit)) {
                    response.setFromCache(true);
                    Object hitType = request.getContext().get("__cacheHitType");
                    response.setCacheHitType(hitType != null ? hitType.toString() : "SEMANTIC");
                    request.getContext().remove("__cacheHit");
                    request.getContext().remove("__cacheHitType");
                    return;
                }
            }
            // 带上下文的结果不写入按原话缓存 (SSE 现有行为, 普通入口一致)
            if ("COMPLETED".equals(response.getStatus()) && request.getUserInput() != null
                    && RecognitionHistoryResolver.effective(request).isEmpty()) {
                semanticCacheService.cacheResult(factoryId, request.getUserInput(), matchResult, response);
            }
        } catch (Exception e) {
            log.warn("处理响应缓存失败: {}", e.getMessage());
        }
    }

    // ==================== 对话记忆 ====================

    /**
     * X1 Part B 修复 —— 在短路 / 显式意图路径持久化对话记忆。供下一轮 X1 续接继承 lastIntentCode。
     * 仅当 sessionId 非空时生效(无 session 的独立查询 parity/golden 完全不受影响)。fail-soft。
     * 包级可见便于单测。
     */
    private boolean ownsConversationMemory(ConversationContext owner, String factoryId, Long userId, String sessionId) {
        return owner != null && Objects.equals(factoryId, owner.getFactoryId())
                && Objects.equals(userId, owner.getUserId()) && Objects.equals(sessionId, owner.getSessionId());
    }

    void persistConversationMemoryForExplicitIntent(String factoryId, IntentExecuteRequest request,
                                                    IntentExecuteResponse response,
                                                    AIIntentConfig intent, Long userId) {
        if (request == null || !hasTrustedConversationIdentity(factoryId, userId, request.getSessionId())
                || request.getSessionId() == null || request.getSessionId().isEmpty()) {
            return;
        }
        try {
            ConversationContext owner = conversationMemoryService.getOrCreateContext(factoryId, userId, request.getSessionId());
            if (!ownsConversationMemory(owner, factoryId, userId, request.getSessionId())) return;
            IntentMatchResult syntheticMatch = IntentMatchResult.builder()
                    .bestMatch(intent)
                    .build();
            updateConversationMemory(request.getSessionId(), request, response,
                    syntheticMatch, factoryId, userId);
        } catch (Exception e) {
            log.warn("X1 显式意图路径对话记忆持久化失败: sessionId={}, error={}", request.getSessionId(), e.getMessage());
        }
    }

    private void updateConversationMemory(String sessionId, IntentExecuteRequest request,
                                           IntentExecuteResponse response, IntentMatchResult intentResult,
                                           String factoryId, Long userId) {
        try {
            String userInput = request.getUserInput();
            String assistantMessage = response.getMessage() != null ? response.getMessage() : "执行完成";
            conversationMemoryService.addMessage(sessionId, ConversationMessage.user(userInput));
            ConversationMessage assistant = ConversationMessage.assistant(assistantMessage);
            Map<String, Object> readMetadata = readTurnMemoryMetadata(request, response);
            if (!readMetadata.isEmpty()) assistant.setMetadata(readMetadata);
            conversationMemoryService.addMessage(sessionId, assistant);
            // Extract entities from response data
            extractAndUpdateEntitySlots(sessionId, response, intentResult);
            if (intentResult != null && intentResult.getBestMatch() != null) {
                conversationMemoryService.updateLastIntent(sessionId, intentResult.getBestMatch().getIntentCode());
            }
        } catch (Exception e) {
            log.warn("Failed to update conversation memory: sessionId={}, error={}", sessionId, e.getMessage());
        }
    }

    /**
     * INTENT-DISTILL D1: 成功的只读查询把「本次实际执行的参数与来源」(readQuery) 和服务端分页元数据挂在助手消息上,
     * 与 SSE 入口写进同一份会话记忆 —— 下一轮续句沿用、「下一页」翻页都只从这里取。
     */
    private Map<String, Object> readTurnMemoryMetadata(IntentExecuteRequest request, IntentExecuteResponse response) {
        if (response == null || response.getMetadata() == null
                || !("SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus()))
                || !(response.getMetadata().get(ReadTurnParameters.METADATA_KEY) instanceof Map<?, ?> readQuery)
                || !(readQuery.get("intentCode") instanceof String intentCode)
                || !(readQuery.get("toolName") instanceof String toolName)) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        Map<String, Object> copy = new LinkedHashMap<>();
        readQuery.forEach((k, v) -> { if (k != null) copy.put(String.valueOf(k), v); });
        metadata.put(ReadTurnParameters.METADATA_KEY, copy);
        Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
        if (executor.isPresent() && executor.get().getAccessMode() == ToolExecutor.AccessMode.READ) {
            Map<String, Object> pagination = ReadPaginationSupport.buildMetadata(request, intentCode, toolName,
                    executor.get(), response.getResultData(), copy);
            if (pagination != null) metadata.put(ReadPaginationSupport.METADATA_KEY, pagination);
        }
        return metadata;
    }

    /**
     * 普通入口的「下一页 / 上一页」: 与 SSE 同一份翻页规划 ({@link ReadPaginationSupport}), 以前这里落到
     * PaginationNextTool, 只回一句「正在加载」而没有数据。目标意图照常过意图级权限, 工具级权限在派发里再判。
     *
     * @return null = 不是翻页意图, 照原路径执行
     */
    private IntentExecuteResponse executePaginationReplay(String factoryId, IntentExecuteRequest request,
                                                          AIIntentConfig intent, Long userId, String userRole) {
        if (intent == null || !ReadPaginationSupport.isPaginationIntent(intent.getIntentCode())) return null;
        ReadPaginationSupport.Plan plan = ReadPaginationSupport.plan(
                ReadPaginationSupport.isPreviousIntent(intent.getIntentCode()), conversationMemoryService,
                toolRegistry, writeGuardService, code -> getIntentByCodeWithPlatformFallback(factoryId, code),
                factoryId, userId, request);
        if (plan.refused()) return plan.refusal();
        IntentExecuteResponse denied = checkIntentPermission(plan.intent(), factoryId, userId, userRole);
        if (denied != null) return denied;
        return toolDispatchService.executeWithTool(plan.tool(), factoryId, plan.replay(), plan.intent(),
                userId, userRole, plan.match());
    }

    @SuppressWarnings("unchecked")
    private void extractAndUpdateEntitySlots(String sessionId, IntentExecuteResponse response,
                                              IntentMatchResult intentResult) {
        if (response.getAffectedEntities() != null) {
            for (IntentExecuteResponse.AffectedEntity entity : response.getAffectedEntities()) {
                try {
                    com.cretas.aims.dto.conversation.EntitySlot.SlotType slotType = mapEntityTypeToSlotType(entity.getEntityType());
                    if (slotType != null) {
                        var slot = com.cretas.aims.dto.conversation.EntitySlot.builder()
                                .type(slotType).id(entity.getEntityId()).name(entity.getEntityName()).build();
                        conversationMemoryService.updateEntitySlot(sessionId, slotType, slot);
                    }
                } catch (Exception e) { log.debug("Failed to update entity slot: {}", e.getMessage()); }
            }
        }
        // Fallback entity extraction from resultData
        Object data = response.getResultData();
        if (data == null) return;
        try {
            List<Map<String, Object>> items = null;
            if (data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                extractTopStoreSlot(sessionId, dataMap.get("top_store"));
                extractTopDishSlot(sessionId, dataMap.get("top_dish"));
                if (dataMap.containsKey("content") && dataMap.get("content") instanceof List)
                    items = (List<Map<String, Object>>) dataMap.get("content");
            } else if (data instanceof List) {
                items = (List<Map<String, Object>>) data;
            }
            if (items == null || items.isEmpty()) return;
            Object firstItemObj = items.get(0);
            if (firstItemObj == null) return;
            Map<String, Object> firstItem;
            if (firstItemObj instanceof Map) {
                firstItem = (Map<String, Object>) firstItemObj;
            } else {
                firstItem = objectMapper.convertValue(firstItemObj,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            extractSlot(sessionId, firstItem, "BATCH", "id", "batchId", "batch_id");
            extractSlot(sessionId, firstItem, "SUPPLIER", "supplierId", "supplier_id");
            extractSlot(sessionId, firstItem, "PRODUCT", "productTypeId", "productId", "materialTypeId");
        } catch (Exception e) { log.debug("Entity extraction failed: {}", e.getMessage()); }
    }

    @SuppressWarnings("unchecked")
    private void extractTopStoreSlot(String sessionId, Object topStoreObj) {
        if (topStoreObj == null) return;
        try {
            Map<String, Object> topStore;
            if (topStoreObj instanceof Map) {
                topStore = (Map<String, Object>) topStoreObj;
            } else {
                topStore = objectMapper.convertValue(topStoreObj,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            String id = getStringValue(topStore, "store_id", "id");
            String name = getStringValue(topStore, "门店", "store_name", "name");
            if (id == null || name == null) return;
            var slot = com.cretas.aims.dto.conversation.EntitySlot.store(id, name);
            conversationMemoryService.updateEntitySlot(
                    sessionId,
                    com.cretas.aims.dto.conversation.EntitySlot.SlotType.STORE,
                    slot);
        } catch (Exception e) {
            log.debug("STORE entity extraction failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void extractTopDishSlot(String sessionId, Object topDishObj) {
        if (topDishObj == null) return;
        try {
            Map<String, Object> topDish;
            if (topDishObj instanceof Map) {
                topDish = (Map<String, Object>) topDishObj;
            } else {
                topDish = objectMapper.convertValue(topDishObj,
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
            }
            String id = getStringValue(topDish, "dish_id", "product_id", "id");
            String name = getStringValue(topDish, "菜品", "dish_name", "product_name", "name");
            if (id == null || name == null) return;
            var slot = com.cretas.aims.dto.conversation.EntitySlot.dish(id, name);
            conversationMemoryService.updateEntitySlot(
                    sessionId,
                    com.cretas.aims.dto.conversation.EntitySlot.SlotType.DISH,
                    slot);
        } catch (Exception e) {
            log.debug("DISH entity extraction failed: {}", e.getMessage());
        }
    }

    private void extractSlot(String sessionId, Map<String, Object> item, String entityType, String... idKeys) {
        String id = getStringValue(item, idKeys);
        if (id != null) {
            var slotType = mapEntityTypeToSlotType(entityType);
            if (slotType != null) {
                var slot = com.cretas.aims.dto.conversation.EntitySlot.builder()
                        .type(slotType).id(id).build();
                conversationMemoryService.updateEntitySlot(sessionId, slotType, slot);
            }
        }
    }

    private String getStringValue(Map<String, Object> map, String... keys) {
        for (String key : keys) { Object v = map.get(key); if (v != null) return v.toString(); }
        return null;
    }

    private com.cretas.aims.dto.conversation.EntitySlot.SlotType mapEntityTypeToSlotType(String entityType) {
        if (entityType == null) return null;
        return switch (entityType.toUpperCase()) {
            case "BATCH", "MATERIAL_BATCH" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.BATCH;
            case "SUPPLIER" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.SUPPLIER;
            case "STORE" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.STORE;
            case "DISH" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.DISH;
            case "CUSTOMER" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.CUSTOMER;
            case "PRODUCT", "PRODUCT_TYPE" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.PRODUCT;
            case "WAREHOUSE", "LOCATION" -> com.cretas.aims.dto.conversation.EntitySlot.SlotType.WAREHOUSE;
            default -> null;
        };
    }

    // ==================== Drools ====================

    private ValidationResult validateWithDrools(String factoryId, AIIntentConfig intent,
                                                 IntentExecuteRequest request, Long userId, String userRole) {
        try {
            IntentValidationFact fact = IntentValidationFact.builder()
                    .intentCategory(intent.getIntentCategory())
                    .operation(extractOperationType(intent))
                    .timestamp(LocalDateTime.now())
                    .targetFactoryId(factoryId).currentFactoryId(factoryId)
                    .userRole(userRole)
                    .forceExecute(Boolean.TRUE.equals(request.getForceExecute()))
                    .batchSize(extractBatchSize(request))
                    .userId(userId).username(extractUsername(request))
                    .intentCode(intent.getIntentCode())
                    .build();

            ValidationResult result = ruleEngineService.executeRulesWithAudit(
                    factoryId, "intentValidation", "INTENT", intent.getIntentCode(),
                    userId, extractUsername(request), userRole, fact);

            return result != null ? result : ValidationResult.builder().valid(true).build();
        } catch (Exception e) {
            log.error("Drools规则验证异常: intentCode={}, error={}", intent.getIntentCode(), e.getMessage(), e);
            ValidationResult failedResult = ValidationResult.builder().valid(false).build();
            failedResult.addViolation("规则验证异常", "Drools规则引擎执行异常: " + e.getMessage(), "HIGH");
            return failedResult;
        }
    }

    private boolean shouldRunDroolsValidation(AIIntentConfig intent) {
        if (!validationEnabled || intent == null) {
            return false;
        }
        return !"QUERY".equals(extractOperationType(intent));
    }

    private String extractOperationType(AIIntentConfig intent) {
        String code = intent.getIntentCode();
        if (code.contains("CREATE") || code.contains("ADD")) return "CREATE";
        if (code.contains("UPDATE") || code.contains("MODIFY")) return "UPDATE";
        if (code.contains("DELETE") || code.contains("REMOVE")) return "DELETE";
        return "QUERY";
    }

    private int extractBatchSize(IntentExecuteRequest request) {
        if (request.getContext() == null) return 1;
        Object bs = request.getContext().get("batchSize");
        if (bs instanceof Integer) return (Integer) bs;
        if (bs instanceof String) { try { return Integer.parseInt((String) bs); } catch (NumberFormatException e) { return 1; } }
        return 1;
    }

    private String extractUsername(IntentExecuteRequest request) {
        if (request.getContext() == null) return "unknown";
        Object u = request.getContext().get("username");
        return u != null ? u.toString() : "unknown";
    }

    // ==================== NEED_MORE_INFO enrichment ====================

    private IntentExecuteResponse enrichWithClarificationQuestions(IntentExecuteResponse response,
                                                                    IntentExecuteRequest request,
                                                                    AIIntentConfig intent,
                                                                    String factoryId, Long userId) {
        try {
            List<String> missingParams = parseMissingParameters(response.getMessage());
            if (missingParams.isEmpty()) return response;

            List<String> clarificationQuestions = missingParams.stream()
                    .map(p -> generateQuestionForParameter(p))
                    .collect(Collectors.toList());
            if (missingParams.size() > 1) {
                clarificationQuestions.add("请提供以上所有必需信息，以便我能够帮您完成操作。");
            }

            List<ConversationService.RequiredParameter> requiredParameters = missingParams.stream()
                    .map(name -> ConversationService.RequiredParameter.builder()
                            .name(name).label(name).type("string").collected(false).build())
                    .collect(Collectors.toList());

            String sessionId = null;
            if (hasBoundSupplierFactoryActor(factoryId, userId)) {
                try {
                    var conversationResp = conversationService.startParameterCollection(
                            factoryId, userId, intent.getIntentCode(), intent.getIntentName(),
                            requiredParameters, clarificationQuestions);
                    if (conversationResp != null) sessionId = conversationResp.getSessionId();
                } catch (Exception e) { log.warn("Failed to create parameter collection session: {}", e.getMessage()); }
            }

            // Build parameter options
            List<IntentExecuteResponse.SuggestedAction> suggestedActions = new ArrayList<>();
            if (productTypeRepository != null) {
                for (String param : missingParams) {
                    if (param.toLowerCase().contains("productid")) {
                        try {
                            var products = productTypeRepository.findByFactoryId(factoryId);
                            if (products != null) {
                                int limit = Math.min(products.size(), 10);
                                for (int i = 0; i < limit; i++) {
                                    var p = products.get(i);
                                    suggestedActions.add(IntentExecuteResponse.SuggestedAction.builder()
                                            .actionCode("SELECT_PARAM_productId_" + p.getId())
                                            .actionName(p.getName())
                                            .description(p.getCode() != null ? "编码: " + p.getCode() : null)
                                            .build());
                                }
                            }
                        } catch (Exception e) { log.warn("查询产品列表失败: {}", e.getMessage()); }
                    }
                }
            }

            return IntentExecuteResponse.builder()
                    .intentRecognized(response.getIntentRecognized())
                    .intentCode(response.getIntentCode())
                    .intentName(response.getIntentName())
                    .intentCategory(response.getIntentCategory())
                    .status(response.getStatus())
                    .message("需要更多信息来完成此操作")
                    .clarificationQuestions(clarificationQuestions)
                    .suggestedActions(suggestedActions.isEmpty() ? null : suggestedActions)
                    .sessionId(sessionId)
                    .executedAt(response.getExecutedAt())
                    .build();
        } catch (Exception e) {
            log.error("Failed to enrich clarification questions: {}", e.getMessage(), e);
            return response;
        }
    }

    private List<String> parseMissingParameters(String message) {
        List<String> params = new ArrayList<>();
        if (message == null) return params;
        Pattern pattern = Pattern.compile("\\(([^)]+)\\)");
        Matcher matcher = pattern.matcher(message);
        while (matcher.find()) { String p = matcher.group(1).trim(); if (!p.isEmpty()) params.add(p); }
        return params;
    }

    private String generateQuestionForParameter(String paramName) {
        String lp = paramName.toLowerCase();
        if (lp.contains("batchid")) return "请问您要操作哪个批次？请提供批次ID。";
        if (lp.contains("quantity")) return "请问数量是多少？";
        if (lp.contains("date")) return "请问日期是？格式：yyyy-MM-dd";
        return "请提供 " + paramName + "。";
    }

    // ==================== Utility ====================

    private String getPrecomputedAnalysisContext(String factoryId) {
        if (factoryId == null || factoryId.isEmpty()) return null;
        // 存档的日报 / 周报是整段文字 (含营收、成本、利润), 逐字段遮不了: 只给看得到经营财务数字的人 (ERP-S6-LLM-PROMPT-PRICE-LEAK)
        Long caller = com.cretas.aims.utils.SecurityUtils.currentUserId();
        if (priceView == null || !priceView.seesFinanceFigures(caller)) {
            log.info("[price] 当前账号看不到经营财务数字, 存档报告不拼进咨询提示词: factoryId={}, userId={}", factoryId, caller);
            return null;
        }
        try {
            if (!llmCostScope.forCurrentCaller(factoryId).isEmpty()) {
                log.info("[process-secret] 当前账号无权查看本厂部分保密产品, 存档报告不拼进咨询提示词: factoryId={}", factoryId);
                return null;
            }
        } catch (RuntimeException e) {
            log.warn("[process-secret] 保密范围判定失败, 存档报告不拼进咨询提示词: factoryId={}", factoryId);
            return null;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            StringBuilder context = new StringBuilder();
            analysisResultRepository.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                    factoryId, "daily", now).ifPresent(r -> context.append("## 日报\n").append(r.getAnalysisText()).append("\n"));
            analysisResultRepository.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                    factoryId, "weekly", now).ifPresent(r -> context.append("## 周报\n").append(r.getAnalysisText()).append("\n"));
            if (context.length() == 0) {
                analysisResultRepository.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                        factoryId, "monthly", now).ifPresent(r -> context.append("## 月报\n").append(r.getAnalysisText()).append("\n"));
            }
            return context.length() > 0 ? context.toString() : null;
        } catch (Exception e) { return null; }
    }

    private IntentMatchResult deserializeIntentResult(String json) {
        if (json == null || json.isEmpty()) return null;
        try { return objectMapper.readValue(json, IntentMatchResult.class); }
        catch (JsonProcessingException e) { return null; }
    }

    private void logBranchStats() {
        long total = branchToolDirect.get() + branchSkill.get() + branchDynamic.get() + branchNoMatch.get();
        if (total > 0 && total % 50 == 0) {
            log.info("[Branch:Stats] total={}, ToolDirect={}%, Skill={}%, Dynamic={}%, NoMatch={}%",
                    total,
                    branchToolDirect.get() * 100 / total,
                    branchSkill.get() * 100 / total,
                    branchDynamic.get() * 100 / total,
                    branchNoMatch.get() * 100 / total);
        }
    }
}
