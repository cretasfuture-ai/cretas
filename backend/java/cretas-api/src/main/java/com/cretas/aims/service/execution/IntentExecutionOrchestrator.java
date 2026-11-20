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

    /**
     * 没有数据支撑的闲聊 / 兜底回复的业务事实护栏 (与 SSE 入口同一个类)。
     * 默认实例保证裸构造的单测和生产一样走闸: 字段不会是 null, 不存在「没注入就不拦」。
     */
    @Autowired(required = false)
    private com.cretas.aims.ai.grounding.ChatBusinessFactGuard chatBusinessFactGuard =
            new com.cretas.aims.ai.grounding.ChatBusinessFactGuard();

    static final String CHAT_FACT_GUARD_SITE = "conversational-no-data";

    /** 没有经营报告时的咨询提示词 (报告脱敏失败退回的也是它)。 */
    static final String NO_DATA_ADVICE_PROMPT = com.cretas.aims.ai.grounding.ChatBusinessFactGuard.PERSONA
            + com.cretas.aims.ai.grounding.ChatBusinessFactGuard.NO_DATA_RULES
            + "请提供专业、实用的生产管理建议，不超过300字，使用中文。";

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


    @Value("${cretas.ai.validation.enabled:true}")
    private boolean validationEnabled;

    // P1 读写分块 §4.5 demo 写闸: 与 DemoReadOnlyInterceptor 同一配置源 (cretas.demo.factory-ids,
    // 默认 DEMO_REST,DEMO_FACTORY)。AI 确认执行阶段拦截 demo 租户真实写入 — 封住 HTTP 层
    // 放行 /ai-intents/ POST 留下的缺口。名单内租户恒拦 (不随 cretas.demo.enabled 关闭, fail-closed)。
    // 2026-08-05: DEMO_REST 随租户收敛停用, 从 fallback 移除 (与 application.properties 同步)。
    @Value("${cretas.demo.factory-ids:DEMO_REST,DEMO_FACTORY2}")
    private String demoFactoryIdsCsv;

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
            applyUnselectedAnswerReadStamp(response);
            return response;
        }
    }

    /**
     * Only an unselected, non-executing answer can default to READ. Known intents must be stamped where
     * their declaration is available; neither a missing stamp nor caller-supplied text/code proves a read
     * operation. 普通入口经流式管线时 ({@link StreamTurnCollector}) 也调这一份。
     */
    public void applyUnselectedAnswerReadStamp(IntentExecuteResponse response) {
        if (response != null && response.getAiMode() == null
                && !Boolean.TRUE.equals(response.getIntentRecognized())
                && (response.getIntentCode() == null || response.getIntentCode().isBlank())
                && response.getConfirmableAction() == null && response.getResultData() == null
                && (response.getAffectedEntities() == null || response.getAffectedEntities().isEmpty())
                && !Boolean.TRUE.equals(response.getRequiresApproval())) {
            applyAiModeStamp(response, null, null);
        }
    }

    // ==================== 流式管线每一轮都调的步骤 (单一实现; PR-2b 抽出, PR-3 起两个入口都跑) ====================
    // 原普通入口独有的步骤。识别 / 执行收成流式一条管线后 (意图蒸馏③ PR-3, Steve 2026-11-16 口径),
    // App 流式与 /execute 每一轮都在同一位置调它们; 编排器自己的显式意图路径也调同一份。

    /** Drools 校验 (只读查询不进): 不通过返回校验失败卡, 通过返回 null。 */
    public IntentExecuteResponse droolsValidationBlock(String factoryId, AIIntentConfig intent,
                                                       IntentExecuteRequest request, Long userId, String userRole) {
        if (!shouldRunDroolsValidation(intent)) {
            return null;
        }
        ValidationResult validationResult = validateWithDrools(factoryId, intent, request, userId, userRole);
        return validationResult.isValid() ? null : buildDroolsFailureResponse(intent, validationResult);
    }

    /** NEED_MORE_INFO 补问 (追问问题 + 参数收集会话); 其它状态原样返回。 */
    public IntentExecuteResponse enrichNeedMoreInfo(IntentExecuteResponse response, IntentExecuteRequest request,
                                                    AIIntentConfig intent, String factoryId, Long userId) {
        if (response == null || !"NEED_MORE_INFO".equals(response.getStatus())) {
            return response;
        }
        return enrichWithClarificationQuestions(response, request, intent, factoryId, userId);
    }

    /** 8. 对话记忆: 执行过的一轮 (任何状态) 写进本人会话记忆 + 实体槽; 只给绑定的工厂账号。 */
    public void recordExecutedTurnMemory(String factoryId, IntentExecuteRequest request,
                                         IntentExecuteResponse response, IntentMatchResult matchResult, Long userId) {
        // D1-fix: Ensure ConversationMemory row exists before writing slots/messages.
        // ConversationMemoryServiceImpl.updateEntitySlot / addMessage silently bail out
        // when the session row does not yet exist (no auto-create). The explicit-intent
        // path (executeWithExplicitIntent → persistConversationMemoryForExplicitIntent)
        // already calls getOrCreateContext first; mirror that guarantee here so DISH /
        // STORE slots written by extractAndUpdateEntitySlots are actually persisted.
        if (request != null && hasBoundSupplierFactoryActor(factoryId, userId)
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
    }

    /** 个人工资明细: 整轮拒答 (识别之前), 见 PersonalPayRefusalGuard。 */
    public static IntentExecuteResponse personalPayRefusalResponse() {
        String message = com.cretas.aims.ai.tool.PersonalPayRefusalGuard.MESSAGE;
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status(com.cretas.aims.ai.tool.PersonalPayRefusalGuard.STATUS)
                .aiMode("READ")
                .message(message)
                .formattedText(message)
                .executedAt(java.time.LocalDateTime.now())
                .build();
    }

    /**
     * 问别家 (竞争对手 / 同行 / 行业平均) 的成本、价格、毛利: 整轮拒答 (识别和权限判定之前), 见
     * OwnFactoryDataOnlyGuard#refusesBeforeRouting。不执行任何查询, 也不拿本厂数代替。
     */
    public static IntentExecuteResponse externalDataRefusalResponse(String entry, String factoryId) {
        // 被拒的轮次留一条带标记的日志 (飞轮 / 评测能数到这类问题); ⛔ 不记原话等数据内容
        log.info("[PreRoutingRefusal] guard=externalData status={} entry={} factoryId={}",
                com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.STATUS, entry, factoryId);
        String message = com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.MESSAGE;
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status(com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.STATUS)
                .aiMode("READ")
                .message(message)
                .formattedText(message)
                .executedAt(java.time.LocalDateTime.now())
                .build();
    }

    private IntentExecuteResponse executeInScope(String factoryId, IntentExecuteRequest request,
                                          Long userId, String userRole) {
        // 意图蒸馏③ PR-3: 识别 / 执行只有流式管线一条 (StreamTurnCollector → SseStreamingService), 编排器的
        // 识别分支 (短语快捷、续轮、任务恢复、早期问题类型、识别、2b 直答、Skill / 动态选工具 / 执行) 已删。
        // 这里只剩显式意图码 (按钮点选、参数确认后执行、预览、公开演示、多意图子请求) 的路径。
        if (request == null || request.getIntentCode() == null || request.getIntentCode().isBlank()) {
            // 生产上门面 / 多意图回退都把无意图码的请求交给流式管线, 走到这里是接线错了: 如实失败, 不在这里另识别一遍
            log.error("编排器收到无意图码的请求 (应走流式管线): factoryId={}", factoryId);
            String message = "这次请求没有交给识别管线，未执行任何操作。请重试。";
            return IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("FAILED")
                    .message(message)
                    .formattedText(message)
                    .executedAt(LocalDateTime.now())
                    .build();
        }
        return executeWithExplicitIntentInScope(factoryId, request, userId, userRole);
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
        if (com.cretas.aims.ai.tool.PersonalPayRefusalGuard.refuses(request.getUserInput())) {
            return personalPayRefusalResponse();
        }
        if (com.cretas.aims.ai.tool.OwnFactoryDataOnlyGuard.refusesBeforeRouting(request.getUserInput())) {
            return externalDataRefusalResponse("explicit", factoryId);
        }
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

        // 咨询页拦写 → 审批 → 业态门 → 先列可选对象 → 一线未开放 → W0 写确认卡 → 预览: 两入口一份 (PR-3b)
        IntentExecuteResponse stopped = explicitIntentStopBeforeExecution(
                factoryId, request, intent, userId, userRole, factoryPackRoute);
        if (stopped != null) {
            return stopped;
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
            IntentExecuteResponse replay = replayedConfirmationResponse(claimResult.getExistingStatus());
            if (replay != null) {
                return replay;
            }
            return IntentExecuteResponse.builder()
                    .status("FAILED")
                    .message(claimResult.getMessage())
                    .executedAt(LocalDateTime.now())
                    .build();
        }

        var token = claimResult.getToken();
        String claimId = claimResult.getClaimId();
        String intentCode = token.getIntentCode();
        // 工具一旦被调用, 之后的任何异常都可能发生在写入提交之后 → 只能记「结果不明」, 不能记失败
        boolean toolInvoked = false;

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
                if (IntentTurnSharedSteps.stopsForApproval(intentConfig, null)) {
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

            // 确认时比对状态 (Fable 审阅 §8.2): 重读对象当前的改前值, 与预览时记下的比对;
            // 预览之后有人改过 / 前提已不成立 / 无法核对 → 本次不执行, 让用户重新预览。
            com.cretas.aims.ai.tool.gateway.PreviewStateRecheck.ConfirmStateCheck stateCheck = toolDispatchService.recheckPreviewState(
                    tool, intentConfig, token.getCurrentValues(), claimResult.getParameters(),
                    factoryId, userId, userRole);
            if (!stateCheck.proceed()) {
                previewTokenService.resolveClaim(confirmToken, claimId, false,
                        "确认前状态核对未通过(" + stateCheck.code() + "): " + stateCheck.message());
                Map<String, Object> stale = new HashMap<>();
                stale.put("reason", stateCheck.code());
                stale.put("changedFields", stateCheck.changedFields());
                stale.put("needsRepreview", stateCheck.repreviewHelps());
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intentCode)
                        // 重新预览能解决 → PREVIEW_STALE (前端给「重新生成预览」);
                        // 无法核对的工具重新预览还是一样 → CONFIRM_NOT_SUPPORTED (只给去业务页面), 不能原地打转
                        // (与执行网关同一口径: PreviewStateRecheck.ConfirmStateCheck#responseStatus)
                        .status(stateCheck.responseStatus())
                        .message(stateCheck.message())
                        .formattedText(stateCheck.message())
                        .resultData(stale)
                        .aiMode("WRITE")
                        .executedAt(LocalDateTime.now())
                        .build();
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

            toolInvoked = true;
            IntentExecuteResponse response = toolDispatchService.executeWithTool(
                    tool, factoryId, execRequest, intentConfig, userId, userRole, null);
            boolean toolSucceeded = response != null
                    && ("SUCCESS".equals(response.getStatus())
                        || "COMPLETED".equals(response.getStatus()));
            // 工具执行中抛异常 / 超时时 executeWithTool 不重试写操作, 回的是 OUTCOME_UNKNOWN:
            // 写入可能已提交, 凭据记「结果不明」而不是「失败」(失败会诱导用户重新预览再写一遍)。
            boolean outcomeUnknown = response == null || OUTCOME_UNKNOWN.equals(response.getStatus());
            String resolution = response == null
                    ? "工具未返回执行结果"
                    : (response.getMessage() != null ? response.getMessage() : response.getStatus());
            boolean resolved = outcomeUnknown
                    ? previewTokenService.resolveClaimOutcomeUnknown(confirmToken, claimId, resolution)
                    : previewTokenService.resolveClaim(confirmToken, claimId, toolSucceeded, resolution);
            if (outcomeUnknown) {
                // 「先核对、勿重复提交」永远在前; 派发层带来的出错原因 (如有) 接在后面, 用户要知道为什么停下
                String reason = failureReason(response);
                return outcomeUnknownResponse(intentCode,
                        reason == null ? OUTCOME_UNKNOWN_MESSAGE : OUTCOME_UNKNOWN_MESSAGE + "出错原因：" + reason);
            }
            if (!resolved) {
                log.error("确认状态完成失败: claimFingerprint={}",
                        com.cretas.aims.ai.tool.gateway.ToolCommandDigest.tokenFingerprint(claimId));
                return IntentExecuteResponse.builder()
                        .status(OUTCOME_UNKNOWN)
                        .aiMode("WRITE")
                        .message("执行结果状态记录失败，请先在业务页面核对结果，勿重复提交")
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
            if (toolInvoked) {
                try {
                    previewTokenService.resolveClaimOutcomeUnknown(confirmToken, claimId,
                            "执行异常, 结果不明: " + com.cretas.aims.util.ErrorSanitizer.sanitize(e));
                } catch (Exception resolutionError) {
                    log.error("确认结果不明状态写入异常: factoryId={}, error={}",
                            factoryId, resolutionError.getMessage());
                }
                log.error("确认执行异常, 结果不明: factoryId={}, error={}", factoryId, e.getMessage(), e);
                return outcomeUnknownResponse(intentCode);
            }
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

    static final String OUTCOME_UNKNOWN = "OUTCOME_UNKNOWN";
    /** 确认前状态核对没过: 什么都没执行, 需要重新预览。 */
    static final String PREVIEW_STALE = com.cretas.aims.ai.tool.gateway.PreviewStateRecheck.PREVIEW_STALE;
    /** 这个工具给不出可核对的改前值, 不能在助手里确认: 什么都没执行, 重新预览也没用, 去业务页面。 */
    static final String CONFIRM_NOT_SUPPORTED = com.cretas.aims.ai.tool.gateway.PreviewStateRecheck.CONFIRM_NOT_SUPPORTED;
    /** 同一张凭据已经执行成功过: 这次没有重复执行。 */
    static final String ALREADY_EXECUTED = "ALREADY_EXECUTED";

    static final String OUTCOME_UNKNOWN_MESSAGE =
            "操作结果暂时无法确认（可能已经写入）。请先在业务页面核对这条记录，确认没有写入再重新发起，勿直接重复提交。";

    private static final String FAILURE_REASON_MARK = "出错原因：";

    /** 派发层结果不明回执里「出错原因：」后面那段 (工具给的真实原因); 没有就 null。 */
    private static String failureReason(IntentExecuteResponse response) {
        String message = response == null ? null : response.getMessage();
        if (message == null) {
            return null;
        }
        int at = message.indexOf(FAILURE_REASON_MARK);
        if (at < 0) {
            return null;
        }
        String reason = message.substring(at + FAILURE_REASON_MARK.length()).trim();
        return reason.isEmpty() ? null : reason;
    }

    private static IntentExecuteResponse outcomeUnknownResponse(String intentCode) {
        return outcomeUnknownResponse(intentCode, OUTCOME_UNKNOWN_MESSAGE);
    }

    private static IntentExecuteResponse outcomeUnknownResponse(String intentCode, String message) {
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intentCode)
                .status(OUTCOME_UNKNOWN)
                .aiMode("WRITE")
                .message(message)
                .formattedText(message)
                .metadata(Map.of("retryable", false))
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 同一张凭据再次点确认 (重复点击 / 超时后重发): 凭据早已不是待确认, 认领被拒, 工具不会再执行。
     * 按凭据此刻的状态如实回答, 不笼统回「失败」: 执行中 / 结果不明 / 已成功 都不能诱导用户重做。
     */
    static IntentExecuteResponse replayedConfirmationResponse(
            com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus existing) {
        if (existing == null) {
            return null;
        }
        String message;
        String status;
        switch (existing) {
            case EXECUTING -> {
                status = OUTCOME_UNKNOWN;
                message = "这次确认已经在执行中，没有重复执行。请稍后在业务页面核对结果，勿重复提交。";
            }
            case OUTCOME_UNKNOWN -> {
                status = OUTCOME_UNKNOWN;
                message = "这次确认的结果待核对（可能已经写入），没有重复执行。请先在业务页面核对，勿重复提交。";
            }
            case CONFIRMED -> {
                status = ALREADY_EXECUTED;
                message = "这次确认已经执行过，没有重复执行。";
            }
            default -> {
                return null;
            }
        }
        return IntentExecuteResponse.builder()
                .status(status)
                .aiMode("WRITE")
                .message(message)
                .formattedText(message)
                .metadata(Map.of("retryable", false))
                .executedAt(LocalDateTime.now())
                .build();
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

    /**
     * 显式意图码 (Web 按钮点选 / 参数确认后执行、App 澄清按钮): 权限与能力包决定之后、执行之前的停步 ——
     * 唯一实现, 编排器显式意图路径 (SITE A) 与流式显式意图路径都调它 (意图蒸馏③ PR-3b, Steve 定「先判审批再出写确认」)。
     *
     * <p>顺序 (⛔ 不要调换, {@code StreamTurnCollectorEntryParityContractTest} 的显式意图用例守着):
     * <ol>
     *   <li>咨询页 (mode=READ) 拦写 —— READ 模式下不该出现确认卡;</li>
     *   <li>审批 —— 声明需要审批的请求 (含 previewOnly) 在这里就停, 返回 PENDING_APPROVAL, 不列可选对象、
     *       不出确认卡、不预览 (不铸令牌)、不执行 (PR-3b 前编排器是先出确认卡、确认时才判审批; 预览放行);</li>
     *   <li>业态门 (能力包受限路由不判);</li>
     *   <li>写操作 (非预览、未确认): 先列可选对象 → 一线未开放的写如实拒 (不出确认卡) → W0 写确认卡;</li>
     *   <li>预览 (previewOnly): 支持安全预览的工具只预览 (预览出口自己再判工具开关 / 身份 / 一线 / 旧角色检查);
     *       不支持 → PREVIEW_UNSUPPORTED, 不执行 (与工具层执行出口同一状态)。</li>
     * </ol>
     *
     * <p>W0 写确认 (intent-w0) SITE A: 不看 forceExecute (多意图子请求把它恒设 true, 那正是不能跳过的);
     * 确认凭据只来自原子单次占用令牌之后的进程内标记, 请求里的 confirmed=true 一律不认。工具层 SITE B 仍在, 是兜底。
     *
     * @return 这一轮到此为止的回答 (已补 aiMode); null = 照常执行
     */
    public IntentExecuteResponse explicitIntentStopBeforeExecution(
            String factoryId, IntentExecuteRequest request, AIIntentConfig intent,
            Long userId, String userRole, FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute) {
        IntentExecuteResponse readModeBlocked = IntentTurnSharedSteps.readModeWriteBlocked(
                request, intent, intentAccessModeFilter, writeGuardService);
        if (readModeBlocked != null) {
            return readModeBlocked;
        }

        if (IntentTurnSharedSteps.stopsForApproval(intent, request)) {
            log.info("显式意图需要审批, 在写确认之前停下: intentCode={}", intent.getIntentCode());
            return buildApprovalResponse(intent);
        }

        // 业态门控 (Sprint 13 #305): domain-exclusive intent on a mismatched factory.type → honest
        // "本厂非该业态" empty-state instead of a confirm card / a tool run with no data.
        if (factoryPackRoute == null || !factoryPackRoute.isConstrained()) {
            IntentExecuteResponse businessTypeGateResponse = checkBusinessTypeGate(factoryId, intent);
            if (businessTypeGateResponse != null) {
                return businessTypeGateResponse;
            }
        }

        boolean previewOnly = Boolean.TRUE.equals(request.getPreviewOnly());
        Optional<ToolExecutor> boundTool = intent.getToolName() == null || intent.getToolName().isBlank()
                ? Optional.empty() : toolRegistry.getExecutor(intent.getToolName());
        java.util.Map<String, Object> ctx = request.getContext() != null ? request.getContext() : java.util.Map.of();
        boolean writeOperation = writeGuardService.isWriteIntent(intent)
                || boundTool.map(writeGuardService::isWriteTool).orElse(false);
        if (writeOperation && !previewOnly && !writeGuardService.isConfirmed(ctx)) {
            // 缺「要操作哪一个」(「我要报工，产量100」): 先列本人可选的让他选, 不出一张不知道报哪一道的空确认卡
            // (prod 2026-11-14 F006)。只有一个时带出来, 仍出下面的确认卡。
            IntentExecuteResponse choiceAsked = askWhichOneBeforeWriteGuard(
                    factoryId, request, intent, userId, userRole);
            if (choiceAsked != null) {
                return choiceAsked;
            }
            // 一线角色 + 未开放的写: 如实拒, 不出确认卡 (原来 App 在工具层 SITE B 才拒, /execute 先出卡、预览时才拒)
            if (boundTool.isPresent()) {
                IntentExecuteResponse frontlineDenied = toolDispatchService.frontlineWriteDenied(
                        boundTool.get(), factoryId, intent, userRole);
                if (frontlineDenied != null) {
                    applyAiModeStamp(frontlineDenied, intent, intent.getToolName());
                    return frontlineDenied;
                }
            }
            log.info("W0 write-guard: blocked write intent {} (forceExecute={}, confirmed=false)",
                    intent.getIntentCode(), request.getForceExecute());
            IntentExecuteResponse confirmCard = IntentExecuteResponse.builder()
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
            // 确认卡带上已定下的工具参数 (形状同流式工具层那张卡), App / Web 拿它请求 /preview;
            // 预览照常重绑主体、过本人可报。
            boundTool.ifPresent(tool -> SseStreamingService.attachPreviewParams(confirmCard, request, tool));
            return confirmCard;
        }

        if (previewOnly && intent.getToolName() != null && !intent.getToolName().isEmpty()) {
            if (boundTool.isPresent() && boundTool.get().supportsPreview()) {
                IntentExecuteResponse preview = toolDispatchService.executeToolPreview(
                        boundTool.get(), factoryId, request, intent, userId, userRole);
                applyAiModeStamp(preview, intent, intent.getToolName());
                return preview;
            }
            IntentExecuteResponse unsupportedPreview = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    // 与工具层执行出口 (ToolDispatchService) 同一状态与文案; 前端 aiWriteConfirm 按 PREVIEW_UNSUPPORTED 映射
                    .status("PREVIEW_UNSUPPORTED")
                    .message("该工具不支持安全预览，未执行任何操作。")
                    .formattedText("该工具不支持安全预览，未执行任何操作。")
                    .executedAt(LocalDateTime.now())
                    .build();
            applyAiModeStamp(unsupportedPreview, intent, intent.getToolName());
            return unsupportedPreview;
        }
        return null;
    }

    /** 意图绑的工具是「先选要操作哪一个」那类 (ParameterChoiceResolver, 如报工) 时返回它。 */
    private Optional<ToolExecutor> choiceResolverTool(AIIntentConfig intent) {
        if (intent == null || intent.getToolName() == null || intent.getToolName().isBlank()) {
            return Optional.empty();
        }
        return toolRegistry.getExecutor(intent.getToolName())
                .filter(tool -> tool instanceof com.cretas.aims.ai.tool.ParameterChoiceResolver);
    }

    /**
     * 写意图缺「要操作哪一个」: 在通用缺参追问 (3.6 Slot Filling) 与 W0 写确认之前先列本人可选的
     * ({@link ToolDispatchService#askWhichOneBeforeWriteGuard}, 与流式 7a.6、预览同一个出口)。
     * 普通入口这时还没抽过参, 由那里抽 (产量保留)。返回 null = 不需要 / 只有一个已带出, 原流程继续。
     */
    private IntentExecuteResponse askWhichOneBeforeWriteGuard(String factoryId, IntentExecuteRequest request,
                                                             AIIntentConfig intent, Long userId, String userRole) {
        if (Boolean.TRUE.equals(request.getPreviewOnly())
                || writeGuardService.isConfirmed(request.getContext() != null ? request.getContext() : Map.of())) {
            return null;
        }
        Optional<ToolExecutor> tool = choiceResolverTool(intent);
        if (tool.isEmpty() || !(writeGuardService.isWriteIntent(intent) || writeGuardService.isWriteTool(tool.get()))) {
            return null;
        }
        IntentExecuteResponse asked;
        try {
            asked = toolDispatchService.askWhichOneBeforeWriteGuard(
                    tool.get(), factoryId, request, intent, userId, userRole, true);
        } catch (RuntimeException e) {
            log.warn("列可选对象失败, 照原流程: intentCode={}, error={}", intent.getIntentCode(), e.getMessage());
            return null;
        }
        if (asked != null) {
            applyAiModeStamp(asked, intent, intent.getToolName());
        }
        return asked;
    }

    /** (意图码, 参数名) → 绑定工具的参数说明, 恢复/重问时不把字段名说给用户 (两个入口同一个来源)。 */
    /**
     * 早期即判为通用咨询 / 闲聊的句子 (识别之前) 的路由 —— 唯一实现, 流式管线 (App 与 /execute 同一条) 调它
     * (意图蒸馏③ PR-3, 原先只有普通入口有; PR-2b C 类 #16):
     * 分析请求 → 分析流 (复杂度路由: 单代理 / 多代理协作); 食品知识 → FOOD_KNOWLEDGE_QUERY (本厂配了且在用);
     * RAG 路由 (知识检索 / 联网 / 溯源转意图); 短语表里有本厂配置的意图 → 显式意图路径。
     *
     * @return 这一轮的回答; null = 不归这里: 业务问法交给识别, 其余由调用方做闲聊回复 (流式模型)
     */
    public IntentExecuteResponse routeEarlyGeneralQuestion(String factoryId, String userInput,
                                                           QuestionType earlyQuestionType,
                                                           IntentExecuteRequest request,
                                                           Long userId, String userRole) {
        if (userInput == null || userInput.isBlank()
                || (earlyQuestionType != QuestionType.GENERAL_QUESTION
                && earlyQuestionType != QuestionType.CONVERSATIONAL)) {
            return null;
        }

        // Analysis request check
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION &&
            analysisRouterService.isAnalysisRequest(userInput, earlyQuestionType)) {
            return executeAnalysisFlow(factoryId, userInput, request, userId, userRole);
        }

        // Food knowledge intercept (本厂配了且在用才接, 否则交给识别 —— 原流式口径, 不回「未找到意图配置」)
        if (earlyQuestionType == QuestionType.GENERAL_QUESTION && foodKnowledgeIntentActive(factoryId)) {
            Optional<String> foodPhraseMatch = knowledgeBase.matchPhrase(userInput);
            if (foodPhraseMatch.isPresent() && "FOOD_KNOWLEDGE_QUERY".equals(foodPhraseMatch.get())) {
                IntentExecuteRequest foodRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode("FOOD_KNOWLEDGE_QUERY")
                        .mode(request.getMode())
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return executeWithExplicitIntent(factoryId, foodRequest, userId, userRole);
            }
            if (knowledgeBase.hasEntityIntentConflict(userInput, "PROCESSING_GENERIC")) {
                IntentExecuteRequest foodRequest = IntentExecuteRequest.builder()
                        .userInput(userInput)
                        .intentCode("FOOD_KNOWLEDGE_QUERY")
                        .mode(request.getMode())
                        .previewOnly(request.getPreviewOnly())
                        .build();
                return executeWithExplicitIntent(factoryId, foodRequest, userId, userRole);
            }
        }

        // Agentic RAG routing
        RAGRouteResult ragRouteResult = earlyQuestionType == QuestionType.GENERAL_QUESTION
                ? agenticRAGRouterService.route(userInput) : null;
        if (ragRouteResult != null && ragRouteResult.getConsultationType() != null) {
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
                        return executeWithExplicitIntent(factoryId, traceabilityRequest, userId, userRole);
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
                return executeWithExplicitIntent(factoryId, interceptRequest, userId, userRole);
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

        // 业务问法 (交给识别) 与闲聊 (流式模型回复) 由调用方决定 —— 闲聊回复只有流式那一份
        return null;
    }

    private boolean foodKnowledgeIntentActive(String factoryId) {
        try {
            return aiIntentService.getIntentByCode(factoryId, "FOOD_KNOWLEDGE_QUERY")
                    .filter(config -> Boolean.TRUE.equals(config.getIsActive())).isPresent();
        } catch (RuntimeException e) {
            log.warn("FOOD_KNOWLEDGE_QUERY 配置查询失败, 交给识别: {}", e.getClass().getSimpleName());
            return false;
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

    /**
     * 分析流 (早期分析问法: 复杂度路由 / 多代理, 失败时以本厂存档经营报告为上下文兜底) 的准入权限:
     * 与 App 经营分析 / 财务报表数据同一组码 ({@code ReportFinanceTool.FINANCE_REPORT_SURFACE}), 逗号 = 任一即可。
     * 2026-11-04 Steve 裁定「手机分析入口开放给有 analytics 的工厂角色」—— 没有 analytics 的操作员 / 仓管不进分析流。
     */
    static final String ANALYSIS_FLOW_PERMISSION = "analytics:read,analytics:read_write";

    /**
     * 分析流准入 (PR-3 复审 M2): App 与 /execute 共用这一处 (两入口的早期分析问法都从 {@link #routeEarlyGeneralQuestion} 进来)。
     * 走与意图权限同一个门 ({@link IntentPermissionGate}, fail-closed); 门没装配也按拒绝。
     *
     * @return null = 放行; 否则是标准的无权限回复 (不进分析、不碰存档报告、不调模型)
     */
    private IntentExecuteResponse analysisFlowPermissionDenied(Long userId, String userRole) {
        AIIntentConfig analysis = AIIntentConfig.builder()
                .intentCode("ANALYSIS_FLOW").intentName("经营分析")
                .requiredPermission(ANALYSIS_FLOW_PERMISSION).build();
        IntentPermissionGate.PermissionCheck check = intentPermissionGate == null
                ? IntentPermissionGate.PermissionCheck.denied(ANALYSIS_FLOW_PERMISSION)
                : intentPermissionGate.check(analysis, userId, userRole);
        if (check.isAllowed()) {
            return null;
        }
        String required = check.requiredPermission() != null ? check.requiredPermission() : ANALYSIS_FLOW_PERMISSION;
        log.info("分析流准入拒绝: userId={}, role={}, requiredPermission={}", userId, userRole, required);
        String msg = "您没有权限执行此操作。需要权限: " + required + "，请联系工厂管理员开通。";
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NO_PERMISSION")
                .requiredPermission(required)
                .message(msg).formattedText(msg)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private IntentExecuteResponse executeAnalysisFlow(String factoryId, String userInput,
                                                       IntentExecuteRequest request,
                                                       Long userId, String userRole) {
        IntentExecuteResponse analysisDenied = analysisFlowPermissionDenied(userId, userRole);
        if (analysisDenied != null) {
            return analysisDenied;
        }
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
                systemPrompt = com.cretas.aims.ai.grounding.ChatBusinessFactGuard.PERSONA + """

                    用户正在询问一个关于生产管理、质量控制或成本优化的咨询问题。
                    **重要**: 下面是该工厂的最新运营分析报告（报告之外的业务数字一律不得给出）：
                    ---
                    %s
                    ---
                    请基于数据提供针对性建议，不超过500字，使用中文。
                    """.formatted(factoryAnalysisContext) + llmCostScope.harnessFor(factoryId);
            } else {
                systemPrompt = NO_DATA_ADVICE_PROMPT;
            }
        } else {
            systemPrompt = com.cretas.aims.ai.grounding.ChatBusinessFactGuard.PERSONA
                    + com.cretas.aims.ai.grounding.ChatBusinessFactGuard.NO_DATA_RULES
                    + "友好回应，不超过100字，使用中文。";
        }

        // 经营报告 + 原话发给模型前脱敏 (报告里有客户 / 供应商 / 人名); 报告脱敏不了就不带报告, 原话也脱敏不了就不发
        // 报告存的是快照 JSON: 能解析的段当结构化记录, 先从里面认出客户 / 人员名与联系方式, 再脱敏整段提示词
        LlmEgressRedactor.Result redacted = llmEgressGuard.textsWithRecord(CONVERSATIONAL_EGRESS_SITE, factoryId,
                reportRecords(factoryAnalysisContext), Map.of(), Arrays.asList(systemPrompt, userInput));
        if (redacted.refused() && factoryAnalysisContext != null) {
            factoryAnalysisContext = null;
            redacted = llmEgressGuard.texts(CONVERSATIONAL_EGRESS_SITE, factoryId,
                    NO_DATA_ADVICE_PROMPT, userInput);
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
            } else {
                // 没有任何数据进模型: 回复里的业务数字 / 业务事实都没有出处, 不下发 (常识数字照常放行)
                response = chatBusinessFactGuard.enforce(CHAT_FACT_GUARD_SITE, userInput, response,
                        questionType == QuestionType.GENERAL_QUESTION);
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
        IntentTurnSharedSteps.applyAiModeStamp(response, intent, matchedToolName, writeGuardService, toolRegistry);
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
    /**
     * 同上, 但把两个判定器显式传进来 —— {@code SseStreamingService} 复用同一份实现。
     * 两个入口对同一个意图的写判定必须一致, 否则同一句话在流式和非流式下会走出不同的路
     * (card4 2026-07-28 已经踩过一次同形状的副本漂移)。
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
    private IntentExecuteResponse buildNoPermissionResponse(AIIntentConfig intent) {
        String msg = "您没有权限执行此操作。需要角色: " + com.cretas.aims.ai.tool.RoleDisplay.listOf(intent.getRequiredRoles());
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
        return IntentTurnSharedSteps.approvalResponse(intent, writeGuardService, toolRegistry);
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
        return IntentTurnSharedSteps.negationVetoClarification();
    }



    /** 澄清选项会话记的「原话」: 请求原话优先 (识别层可能改写过 matchResult 里的), 缺则回落识别结果。 */
    IntentExecuteResponse buildClarificationResponse(IntentMatchResult matchResult, String factoryId) {
        return buildClarificationResponse(matchResult, factoryId, null, null, null);
    }

    IntentExecuteResponse buildClarificationResponse(IntentMatchResult matchResult, String factoryId,
                                                     Long userId, String userRole, String mode) {
        AIIntentConfig matchedIntent = matchResult.getBestMatch();
        ClarificationCandidateService.Clarification clarification =
                clarify(matchResult, factoryId, userId, userRole, mode);
        List<IntentExecuteResponse.SuggestedAction> candidateActions = clarification.actions();
        // 与 SSE 同一份话术: 有业务候选就列出来; 全被权限滤掉就按标准拒答说明缺什么权限; 确实没有就给可问的示例。
        String clarificationMessage = clarification.message();

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
                .status(clarification.status())
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

    /**
     * 澄清候选: 与 SSE 同一个服务 ({@link ClarificationCandidateService}) —— 按当前工厂配置、业态、模式、
     * 意图权限与一线受众逐个核过才出; 业务候选为 0 时只有「重新描述」(明确失败), 不再回落未过滤的
     * 识别候选或默认业务按钮 (INTENT-DISTILL D2)。
     */
    private List<IntentExecuteResponse.SuggestedAction> buildCandidateActions(IntentMatchResult matchResult,
                                                                          String factoryId, Long userId,
                                                                          String userRole, String mode) {
        return clarify(matchResult, factoryId, userId, userRole, mode).actions();
    }

    private ClarificationCandidateService.Clarification clarify(IntentMatchResult matchResult,
                                                                String factoryId, Long userId,
                                                                String userRole, String mode) {
        if (clarificationCandidateService == null) {
            return ClarificationCandidateService.rephraseOnlyClarification();
        }
        return clarificationCandidateService.clarify(matchResult, factoryId, userId, userRole, mode);
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

    /** 客户可见文本内部泄漏消毒: 与流式入口同一个实现 ({@link IntentTurnSharedSteps#sanitizeInternalReasoningLeak})。 */
    private void sanitizeInternalReasoningLeak(IntentExecuteResponse response) {
        IntentTurnSharedSteps.sanitizeInternalReasoningLeak(response);
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
        return IntentTurnSharedSteps.applyCallerPriceView(priceView, response, userId);
    }

    private void applyFormattedTextFallback(String factoryId, IntentExecuteResponse response, boolean priceMasked) {
        IntentTurnSharedSteps.applyTextFallback(response, workdeskOutputSummarizer, factoryId, priceMasked);
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
            ConversationMessage assistant = intentResult != null && intentResult.getBestMatch() != null
                    ? ConversationMessage.assistant(assistantMessage, intentResult.getBestMatch().getIntentCode())
                    : ConversationMessage.assistant(assistantMessage);
            Map<String, Object> readMetadata = readTurnMemoryMetadata(request, response, intentResult);
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
    private Map<String, Object> readTurnMemoryMetadata(IntentExecuteRequest request, IntentExecuteResponse response,
                                                       IntentMatchResult intentResult) {
        if (response == null
                || !("SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus()))) {
            return Map.of();
        }
        Map<String, Object> readQuery = response.getMetadata() != null
                && response.getMetadata().get(ReadTurnParameters.METADATA_KEY) instanceof Map<?, ?> rq ? new LinkedHashMap<>() : null;
        if (readQuery != null) {
            ((Map<?, ?>) response.getMetadata().get(ReadTurnParameters.METADATA_KEY))
                    .forEach((k, v) -> { if (k != null) readQuery.put(String.valueOf(k), v); });
        }
        AIIntentConfig intent = intentResult != null ? intentResult.getBestMatch() : null;
        // readQuery 优先; 工具没给 readQuery 时按这一轮的意图与绑定工具 (两入口一份, PR-3: 原流式那一份就这样记翻页)
        String intentCode = readQuery != null && readQuery.get("intentCode") instanceof String code ? code
                : intent != null ? intent.getIntentCode() : null;
        String toolName = readQuery != null && readQuery.get("toolName") instanceof String tool ? tool
                : intent != null ? intent.getToolName() : null;
        if (intentCode == null || toolName == null || toolName.isBlank()) {
            return Map.of();
        }
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (readQuery != null) metadata.put(ReadTurnParameters.METADATA_KEY, readQuery);
        Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
        if (executor.isPresent() && executor.get().getAccessMode() == ToolExecutor.AccessMode.READ
                && !(writeGuardService != null && writeGuardService.isWriteTool(executor.get()))
                && !(intent != null && writeGuardService != null && writeGuardService.isWriteIntent(intent))) {
            Map<String, Object> pagination = ReadPaginationSupport.buildMetadata(request, intentCode, toolName,
                    executor.get(), response.getResultData(), readQuery);
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

}
