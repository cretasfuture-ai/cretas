package com.cretas.aims.service.execution;

import com.cretas.aims.utils.SecurityUtils;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatMessage;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.exception.LlmSchemaValidationException;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ResultFormatterService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.SlotFillingService;
import com.cretas.aims.util.ErrorSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * SSE 流式执行服务
 *
 * 负责 SseEmitter 管理、流式意图执行、流式对话回复、
 * SSE 事件格式化与发送。拥有独占的 SSE 线程池。
 */
@Slf4j
@Service
public class SseStreamingService {

    // R16: 餐饮租户 SSE no-tool 死胡同出口先走 tiered 路由 (非反转路径的保险)。
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.ai.tool.impl.restaurant.TieredIntentDelegate sseTieredIntentDelegate;

    // Card4 (2026-07-28): SSE tiered-first parity flag — mirrors
    // IntentExecutionOrchestrator's cretas.restaurant.tiered-first.enabled binding so the
    // streaming channel can be toggled in lockstep with the synchronous /execute path.
    // Default true in a real Spring context; plain `new SseStreamingService(...)` unit tests
    // that don't go through Spring get Java's default `false` unless a test explicitly opts
    // in via ReflectionTestUtils, which keeps existing SSE tests unaffected.
    @org.springframework.beans.factory.annotation.Value("${cretas.restaurant.tiered-first.enabled:true}")
    private boolean tieredFirstEnabled;

    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final ExecutorService sseExecutor = java.util.concurrent.Executors.newFixedThreadPool(
            8, r -> { Thread t = new Thread(r, "intent-sse"); t.setDaemon(true); return t; });

    private final AIIntentService aiIntentService;
    private final SemanticCacheService semanticCacheService;
    private final IntentKnowledgeBase knowledgeBase;
    private final AnalysisRouterService analysisRouterService;
    private final ObjectMapper objectMapper;
    private final ToolRegistry toolRegistry;
    private final ToolDispatchService toolDispatchService;
    private final DynamicToolSelectionService dynamicToolSelectionService;
    private final DashScopeClient dashScopeClient;
    private final DashScopeConfig dashScopeConfig;

    @Autowired(required = false)
    private ResultFormatterService resultFormatterService;

    @Autowired(required = false)
    private SlotFillingService slotFillingService;

    // Sprint 13 #305 业态门控: shared gate so the SSE path gates identically to /execute.
    @Autowired
    private BusinessTypeGate businessTypeGate;

    /** Default-off boundary shared with the non-streaming execute path. */
    @Autowired
    private FactoryCapabilityPackRoutingPolicy factoryCapabilityPackRoutingPolicy;

    @Autowired
    private WriteGuardService writeGuardService;

    // 一线角色写操作开放名单 (#939)。入场 (7a.5) 就问: 没对这个角色开放的写操作, 不先抽参、不追问参数,
    // 直接告诉他 (2026-10-06 真机: 操作员说「创建生产批次」被追问产品和数量, 答完才被拒)。
    // required=true: 生产上由 Spring 保证接上; 只有裸构造的单测里为 null。
    @Autowired
    private com.cretas.aims.service.intent.FrontlineAiWriteGrantService frontlineWriteGrants;

    // 声明优先的写意图判定 (与 /execute 同一份实现)。required=false: 裸构造的单测不提供时
    // 回落纯 writeGuardService 启发式, 与 IntentExecutionOrchestrator 的处理一致。
    @Autowired(required = false)
    private com.cretas.aims.service.intent.IntentAccessModeFilter intentAccessModeFilter;

    @Autowired(required = false)
    private com.cretas.aims.service.restaurant.RestaurantGrossMarginChatRouteSelector
            restaurantGrossMarginChatRouteSelector;

    /**
     * 意图级权限码门 (与 /execute 的 checkIntentPermission 同一份实现)。
     * 2026-10-03 前流式路径只查 requiredRoles —— 意图声明的 required_permission 在 App 实际走的
     * 这条路上从未被检查。required=false: 裸构造的单测不提供时回落旧的角色判定。
     */
    @Autowired(required = false)
    private IntentPermissionGate intentPermissionGate;

    /** Canonical candidate builder shared with the non-stream clarification path. */
    @Autowired(required = false)
    private ClarificationCandidateService clarificationCandidateService;

    /** 澄清按钮点选回写到 Python (学习样本)。缺席时不记录, 不影响执行。 */
    @Autowired(required = false)
    private com.cretas.aims.client.PythonAiMatcherClient pythonAiMatcherClient;

    /** Optional for bare SSE tests; required in the real Spring path for server-owned sessions. */
    @Autowired(required = false)
    private com.cretas.aims.service.ConversationMemoryService conversationMemoryService;

    @Autowired(required = false)
    private com.cretas.aims.ai.client.F006BossQuestionClient f006BossQuestionClient;

    /** 追问解读规则表 (纯函数, 无依赖)。 */
    private final FollowUpResolver followUpResolver = new FollowUpResolver();

    /**
     * 参数收集会话 (缺参追问) 的读写。缺席时 (裸构造的单测) 不做缺参续轮, 行为同旧版。
     * 会话由 7b 的 SlotFillingService 建, 这里只负责「下一句是不是在回答它」。
     */
    @Autowired(required = false)
    private com.cretas.aims.service.ConversationService conversationService;

    @Autowired
    public SseStreamingService(@Lazy AIIntentService aiIntentService,
                               SemanticCacheService semanticCacheService,
                               IntentKnowledgeBase knowledgeBase,
                               AnalysisRouterService analysisRouterService,
                               ObjectMapper objectMapper,
                               ToolRegistry toolRegistry,
                               ToolDispatchService toolDispatchService,
                               DynamicToolSelectionService dynamicToolSelectionService,
                               DashScopeClient dashScopeClient,
                               DashScopeConfig dashScopeConfig) {
        this.aiIntentService = aiIntentService;
        this.semanticCacheService = semanticCacheService;
        this.knowledgeBase = knowledgeBase;
        this.analysisRouterService = analysisRouterService;
        this.objectMapper = objectMapper;
        this.toolRegistry = toolRegistry;
        this.toolDispatchService = toolDispatchService;
        this.dynamicToolSelectionService = dynamicToolSelectionService;
        this.dashScopeClient = dashScopeClient;
        this.dashScopeConfig = dashScopeConfig;
    }

    @PreDestroy
    public void destroy() {
        log.info("关闭 SSE 执行器线程池...");
        sseExecutor.shutdown();
    }

    /**
     * SSE 线程池 — 暴露给 MultiIntentExecutionService 用于并行执行
     */
    public ExecutorService getSseExecutor() {
        return sseExecutor;
    }

    // ==================== 公开方法 ====================

    /**
     * SSE 流式执行意图
     */
    public SseEmitter executeStream(String factoryId, IntentExecuteRequest request,
                                     Long userId, String userRole) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        emitter.onCompletion(() -> log.debug("SSE connection completed: factoryId={}", factoryId));
        emitter.onTimeout(() -> log.warn("SSE connection timeout: factoryId={}", factoryId));
        emitter.onError(e -> log.error("SSE connection error: factoryId={}", factoryId, e));

        var miniCancellation = registerMiniCancellation(emitter);
        // 流在 intent-sse 工作线程上跑, 没有 Servlet 请求, SecurityUtils.currentUserId() 读不到人 ——
        // 库存等「必须有登录上下文」的服务 (#921) 会抛 "Authenticated inventory context is required"
        // (2026-10-06 prod: 仓库员「查库存」每次失败; #944 用 SecurityContext 传递没生效, 因为身份读的是这里)。
        // userId 来自控制器已认证的请求, 在工作线程上绑成「可信用户」作用域, 结束时还原 (线程池复用线程)。
        sseExecutor.execute(() -> {
            try (SecurityUtils.Scope ignored = SecurityUtils.trustedUserIdScope(userId)) {
                executeStreamAsync(emitter, factoryId, request, userId, userRole, miniCancellation);
            }
        });

        return emitter;
    }

    // ==================== 内部方法 ====================

    /**
     * 异步执行流式意图处理
     */
    private void executeStreamAsync(SseEmitter emitter, String factoryId,
                                     IntentExecuteRequest request, Long userId, String userRole) {
        executeStreamAsync(emitter, factoryId, request, userId, userRole, registerMiniCancellation(emitter));
    }

    private com.cretas.aims.ai.client.F006BossQuestionClient.Cancellation registerMiniCancellation(SseEmitter emitter) {
        var cancellation = new com.cretas.aims.ai.client.F006BossQuestionClient.Cancellation();
        emitter.onCompletion(cancellation::cancel);
        emitter.onTimeout(cancellation::cancel);
        emitter.onError(error -> cancellation.cancel());
        return cancellation;
    }

    private void executeStreamAsync(SseEmitter emitter, String factoryId,
                                     IntentExecuteRequest request, Long userId, String userRole,
                                     com.cretas.aims.ai.client.F006BossQuestionClient.Cancellation miniCancellation) {
        try {
            long startTime = System.currentTimeMillis();

            ConversationContext sessionContext = establishOwnedSession(factoryId, request, userId);
            if (conversationMemoryService != null && (sessionContext == null
                    || !java.util.Objects.equals(factoryId, sessionContext.getFactoryId())
                    || !java.util.Objects.equals(userId, sessionContext.getUserId())
                    || sessionContext.getSessionId() == null || sessionContext.getSessionId().isBlank())) {
                streamTerminalResponse(emitter, sessionOwnershipFailureResponse(), startTime);
                return;
            }

            // 1. 开始事件
            Map<String, Object> startEvent = new LinkedHashMap<>();
            startEvent.put("message", "开始处理...");
            startEvent.put("timestamp", LocalDateTime.now().toString());
            if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
                startEvent.put("sessionId", request.getSessionId());
            }
            sendSseEvent(emitter, "start", startEvent);

            String userInput = request.getUserInput();

            FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute =
                    evaluateFactoryPackRoute(factoryId, request, userRole);
            if (factoryPackRoute.shouldBlock()) {
                streamTerminalResponse(
                        emitter,
                        buildFactoryPackNoMatch(factoryPackRoute, factoryPackRoute.reason()),
                        startTime);
                return;
            }
            boolean factoryPackConstrained = factoryPackRoute.isConstrained();

            if (isPaginationRequest(request)) {
                executePaginationRequest(emitter, factoryId, request, userId, userRole,
                        startTime, factoryPackRoute);
                return;
            }

            // The stream historically ignored explicit intent codes. A constrained Factory Pack
            // must converge explicit and recognized intents before any Restaurant/general/skill
            // route can run, without adding an LLM call.
            // 2026-10-03: 非能力包工厂也认显式意图码 —— 澄清按钮点选后 App 带着「意图码 + 原话」
            // 回来 (此前 App 把按钮上的名字当一句新话再识别一遍, 原话里的数量等信息就丢了)。
            // 显式码只换掉「识别」这一步: 权限码 / 审批 / 业态门 / W0 写确认 / 咨询页拦写
            // 全部照常在 executeAndStreamResult 及其下游执行。查意图只认在用未删的。
            Optional<AIIntentConfig> explicitIntent =
                    request.getIntentCode() == null || request.getIntentCode().isBlank()
                            ? Optional.empty()
                            : aiIntentService.getIntentByCode(factoryId, request.getIntentCode());
            if (!factoryPackConstrained && explicitIntent.isPresent()) {
                AIIntentConfig intent = explicitIntent.get();
                sendSseEvent(emitter, "intent_recognized", Map.of(
                        "intentCode", intent.getIntentCode(),
                        "intentName", intent.getIntentName(),
                        "intentCategory", intent.getIntentCategory(),
                        "confidence", 1.0d,
                        "matchMethod", "EXPLICIT"));
                recordClarificationSelection(factoryId, userId, userRole, request, intent);
                // 点了按钮 = 用户自己选了要做什么; 之前没答完的缺参追问作废, 免得下一个数字被它截走
                closeOpenSlotSession(factoryId, userId, request, "explicit-intent");
                executeAndStreamResult(
                        emitter, factoryId, request, intent, userId, userRole, startTime,
                        IntentMatchResult.builder()
                                .bestMatch(intent)
                                .confidence(1.0d)
                                .matchMethod(IntentMatchResult.MatchMethod.EXACT)
                                .requiresConfirmation(false)
                                .questionType(QuestionType.OPERATIONAL_COMMAND)
                                .build(),
                        factoryPackRoute);
                return;
            }
            if (factoryPackConstrained
                    && request.getIntentCode() != null
                    && !request.getIntentCode().isBlank()) {
                if (explicitIntent.isEmpty()) {
                    streamTerminalResponse(
                            emitter,
                            buildFactoryPackNoMatch(factoryPackRoute, "explicit-intent-not-found"),
                            startTime);
                    return;
                }
                AIIntentConfig intent = explicitIntent.get();
                IntentMatchResult explicitMatch = IntentMatchResult.builder()
                        .bestMatch(intent)
                        .confidence(1.0d)
                        .matchMethod(IntentMatchResult.MatchMethod.EXACT)
                        .requiresConfirmation(false)
                        .questionType(QuestionType.OPERATIONAL_COMMAND)
                        .build();
                sendSseEvent(emitter, "intent_recognized", Map.of(
                        "intentCode", intent.getIntentCode(),
                        "intentName", intent.getIntentName(),
                        "intentCategory", intent.getIntentCategory(),
                        "confidence", 1.0d,
                        "matchMethod", "EXPLICIT"));
                executeAndStreamResult(
                        emitter, factoryId, request, intent, userId, userRole, startTime,
                        explicitMatch, factoryPackRoute);
                return;
            }

            // Card4 (2026-07-28): restaurant tiered-first, aligned to
            // IntentExecutionOrchestrator.execute() :371-400. Restaurant natural-language reads
            // get one top-level semantic authority on the SSE channel too — ahead of the bounded
            // Runtime selector, early question-type detection and the semantic cache below, so a
            // streamed answer can no longer diverge from /execute's answer to the same question.
            // Explicit intent codes are handled above and never reach here; write verbs and
            // explicit read-vetoes fall through unchanged to the legacy branches.
            //
            // hasExplicitReadVeto/isRestaurantWriteRequest are called on IntentExecutionOrchestrator
            // itself (package-private static there) rather than duplicated here — a card4 fix-round
            // finding (2026-07-28): an earlier copy of the veto phrase-matcher in this file silently
            // diverged from orchestrator's dimension-contrast-aware rewrite (卡1, PR #1914), which
            // reintroduced exactly the "same question, different entry, different answer" bug this
            // card exists to close. Single source of truth — never copy this logic again.
            boolean restaurantTenant = !factoryPackConstrained
                    && isRestaurantTenantId(factoryId, resolveFactoryDomainForTenantCheck(factoryId));

            // 2026-10-04 追问解读: 「好了 / 下一个 / 再加十公斤 / 批了吧」+ App 带回的 lastAction
            // → 确认按钮 (不执行)。在早期问题类型检测之前 —— 否则「好了」会被当闲聊流式回复;
            // 显式意图码 (点了按钮回来) 已在上面处理, 不会再进这里。能力包受限路由与餐饮租户不走。
            // 2026-10-06 缺参续轮在它前面: 上一轮刚问了「请提供工序任务ID」, 「1827」是在回答那个问题。
            if (!factoryPackConstrained && !restaurantTenant
                    && streamSlotContinuation(emitter, factoryId, request, userId, userRole, startTime,
                            factoryPackRoute)) {
                return;
            }
            if (!factoryPackConstrained && !restaurantTenant
                    && streamFollowUpConfirmation(emitter, factoryId, request, userId, userRole, startTime)) {
                return;
            }
            boolean restaurantSemanticPlanEligible = tieredFirstEnabled
                    && !factoryPackConstrained
                    && !Boolean.TRUE.equals(request.getPreviewOnly())
                    && restaurantTenant
                    && userInput != null && !userInput.isEmpty()
                    && !IntentExecutionOrchestrator.hasExplicitReadVeto(userInput);
            // 写动词是唯一压掉语义权威的原因时记下来 —— 识别层要靠它判「闸说改、解析器说看」
            // 的矛盾, 与 IntentExecutionOrchestrator.execute() 同一套推导。
            boolean restaurantWriteVerbSuppressedPlan = restaurantSemanticPlanEligible
                    && IntentExecutionOrchestrator.isRestaurantWriteRequest(userInput);
            boolean requiresRestaurantSemanticPlan = restaurantSemanticPlanEligible
                    && !restaurantWriteVerbSuppressedPlan;
            if (requiresRestaurantSemanticPlan) {
                IntentExecuteResponse tieredFirst = tryRestaurantTieredDelegate(
                        factoryId, userInput, request, "sse_tiered_first");
                if (tieredFirst != null) {
                    log.info("[SSE][Branch:TieredFirst] restaurant semantic planner accepted: factoryId={}",
                            factoryId);
                    streamTerminalResponse(emitter, tieredFirst, startTime);
                    return;
                }
                // Fail closed: no delegate available (or it errored) means no 8-layer legacy
                // fallback and no stale-cache leak for this restaurant question — an explicit
                // "no answer this turn" beats silently returning a possibly wrong/old answer.
                log.warn("[SSE][Branch:TieredFirst] restaurant semantic planner unavailable; "
                        + "fail closed: factoryId={}", factoryId);
                streamTerminalResponse(emitter, buildRestaurantTieredFirstFailClosedResponse(request), startTime);
                return;
            }

            // Keep the stream front door aligned with /execute. This returns a bounded launch
            // instruction only; it never proxies the restaurant Runtime's own SSE stream.
            if (!factoryPackConstrained
                    && !Boolean.TRUE.equals(request.getPreviewOnly())
                    && (request.getIntentCode() == null || request.getIntentCode().isBlank())) {
                Optional<IntentExecuteResponse> restaurantAgentRoute =
                        restaurantGrossMarginChatRouteSelector == null
                                ? Optional.empty()
                                : restaurantGrossMarginChatRouteSelector.select(
                                        factoryId, userInput, userRole);
                if (restaurantAgentRoute.isPresent()) {
                    IntentExecuteResponse response = restaurantAgentRoute.get();
                    sendSseEvent(emitter, "intent_recognized", Map.of(
                            "intentCode", response.getIntentCode(),
                            "intentName", response.getIntentName(),
                            "intentCategory", response.getIntentCategory(),
                            "confidence", response.getConfidence(),
                            "matchMethod", response.getMatchMethod()));
                    sendSseEvent(emitter, "result", response);
                    sendSseEvent(emitter, "complete", Map.of(
                            "status", response.getStatus(),
                            "cacheHit", false,
                            "totalLatencyMs", System.currentTimeMillis() - startTime));
                    emitter.complete();
                    return;
                }
            }

            // 1.5. 早期问题类型检测
            if (!factoryPackConstrained && userInput != null && !userInput.isEmpty()) {
                QuestionType earlyQuestionType = knowledgeBase.detectQuestionType(userInput);
                if (earlyQuestionType == QuestionType.GENERAL_QUESTION ||
                    earlyQuestionType == QuestionType.CONVERSATIONAL) {

                    boolean isAnalysis = earlyQuestionType == QuestionType.GENERAL_QUESTION
                            && analysisRouterService.isAnalysisRequest(userInput, earlyQuestionType);
                    Optional<String> foodPhrase = knowledgeBase.matchPhrase(userInput);
                    boolean isFood = foodPhrase.isPresent() && "FOOD_KNOWLEDGE_QUERY".equals(foodPhrase.get())
                            && aiIntentService.getIntentByCode(factoryId, "FOOD_KNOWLEDGE_QUERY")
                                    .filter(config -> Boolean.TRUE.equals(config.getIsActive())).isPresent();

                    if (!isAnalysis && !isFood) {
                        log.info("Stream: 早期检测到{}，使用流式 LLM 回复", earlyQuestionType);
                        streamConversationalResponse(emitter, factoryId, userId, userRole,
                                request.getSessionId(), userInput, earlyQuestionType,
                                request.getEnableThinking(), request.getThinkingBudget(), startTime, miniCancellation);
                        return;
                    }
                }
            }

            // 2. 查询语义缓存
            // Card4 (2026-07-28): restaurant tenants never read the semantic cache on the SSE
            // channel, even when tiered-first above didn't fire (write verb / read-veto /
            // tiered-first disabled). The cache can hold an answer up to 1h stale; the tiered
            // planner (when eligible) already produced a fresh answer above, and every other
            // restaurant branch below re-derives its own answer rather than replaying an old one.
            // 第 3b 步: 带最近对话的轮次也不读语义缓存 (缓存键只有工厂+原话, 不含上下文)。
            boolean skipCacheForRestaurant = factoryPackConstrained || restaurantTenant
                    || !recentTurns(request).isEmpty();
            SemanticCacheHit cacheHit = skipCacheForRestaurant
                    ? SemanticCacheHit.miss(0L)
                    : semanticCacheService.queryCache(factoryId, userInput);

            if (!skipCacheForRestaurant && cacheHit.isHit()) {
                sendSseEvent(emitter, "cache_hit", Map.of(
                        "hitType", cacheHit.getHitType(),
                        "similarity", cacheHit.getSimilarity() != null ? cacheHit.getSimilarity() : 1.0,
                        "latencyMs", cacheHit.getLatencyMs()
                ));

                // The factory/input cache has no actor, permissions, mode or conversation
                // scope. A hit is metadata only, as on /execute: never replay business rows
                // or old intent configuration/parameters. Continue fresh recognition so
                // clarification, current admission and tool scope checks all run below.
            } else {
                sendSseEvent(emitter, "cache_miss", Map.of(
                        "latencyMs", cacheHit.getLatencyMs()
                ));
            }

            // 4. 意图识别
            sendSseEvent(emitter, "progress", Map.of(
                    "stage", "intent_recognition",
                    "message", "正在识别意图..."
            ));

            IntentMatchResult matchResult;
            try {
                // spec §8.2 洞②: 流式路径同样把 tab 模式带进识别层, 否则咨询 tab 走 SSE 时
                // 候选集里仍然留着写意图 (多意图执行/兜底重排会用到它们)。
                matchResult = aiIntentService.recognizeIntentWithConfidence(
                        userInput, factoryId, 3, userId, userRole, request.getSessionId(),
                        request.getMode(), null, recentTurns(request));
            } catch (LlmSchemaValidationException e) {
                IntentExecuteResponse validationFailureResponse = buildValidationFailureResponse(e);
                sendSseEvent(emitter, "result", validationFailureResponse);
                sendSseEvent(emitter, "complete", Map.of(
                        "status", "VALIDATION_FAILED",
                        "cacheHit", false,
                        "totalLatencyMs", System.currentTimeMillis() - startTime
                ));
                emitter.complete();
                return;
            }

            // 5. 处理识别结果
            if (!matchResult.hasMatch()) {
                if (factoryPackConstrained) {
                    String reason = matchResult.getQuestionType() == QuestionType.GENERAL_QUESTION
                            || matchResult.getQuestionType() == QuestionType.CONVERSATIONAL
                            ? "general-analysis-not-allowed"
                            : "intent-not-allowed";
                    streamTerminalResponse(
                            emitter, buildFactoryPackNoMatch(factoryPackRoute, reason), startTime);
                } else {
                    IntentExecuteResponse noMatchResponse = buildNoMatchResponseForStream(matchResult, factoryId);
                    sendSseEvent(emitter, "result", noMatchResponse);
                    sendSseEvent(emitter, "complete", Map.of(
                            "status", noMatchCompleteStatus(matchResult),
                            "cacheHit", false,
                            "totalLatencyMs", System.currentTimeMillis() - startTime
                    ));
                    emitter.complete();
                }
                return;
            }

            AIIntentConfig intent = matchResult.getBestMatch();
            if (streamRestaurantWriteReadAmbiguity(
                    emitter, restaurantWriteVerbSuppressedPlan, intent, request, startTime)) {
                return;
            }
            sendSseEvent(emitter, "intent_recognized", Map.of(
                    "intentCode", intent.getIntentCode(),
                    "intentName", intent.getIntentName(),
                    "intentCategory", intent.getIntentCategory(),
                    "confidence", matchResult.getConfidence(),
                    "matchMethod", matchResult.getMatchMethod() != null ? matchResult.getMatchMethod().name() : "UNKNOWN"
            ));

            // Pack-constrained intents authorize and terminate here. This deliberately precedes
            // generic confirmation, legacy Skill and slot-filling branches so none can bypass the
            // Pack allowlist or turn a declared workflow mutation into execution.
            if (factoryPackConstrained) {
                executeAndStreamResult(
                        emitter, factoryId, request, intent, userId, userRole, startTime,
                        matchResult, factoryPackRoute);
                return;
            }

            // 6. 需要确认的情况
            if (Boolean.TRUE.equals(matchResult.getRequiresConfirmation())
                    && !Boolean.TRUE.equals(request.getForceExecute())) {
                IntentExecuteResponse clarificationResponse = buildClarificationResponseForStream(
                        matchResult, factoryId, userId, userRole, request.getMode());
                sendSseEvent(emitter, "result", clarificationResponse);
                sendSseEvent(emitter, "complete", Map.of(
                        "status", "NEED_CLARIFICATION",
                        "cacheHit", false,
                        "totalLatencyMs", System.currentTimeMillis() - startTime
                ));
                emitter.complete();
                return;
            }

            // 7a. Skill 路由 —— 与 /execute (IntentExecutionOrchestrator 3.5) 同一前提:
            // 只给「没绑工具」的意图 (WORKDESK 类, Skill 就是它的执行器), 且意图权限先过。
            // 2026-10-06 生产: 这里曾对所有意图都先跑关键词 Skill, 操作员说「上班打卡」(已识别为
            // CLOCK_IN) 被 0.30 分的人员排班 Skill 抢走, 跑了管理员级考勤统计, 卡没打上。
            String streamBoundToolName = intent.getToolName();
            boolean intentHasNoTool = streamBoundToolName == null || streamBoundToolName.isBlank();
            if (dynamicToolSelectionService.isSkillsEnabled() && intentHasNoTool
                    && permissionDeniedMessage(intent, userId, userRole) == null) {
                try {
                    IntentExecuteResponse skillResponse = dynamicToolSelectionService.trySkillRoute(
                            request.getUserInput(), factoryId, userId, userRole);
                    if (skillResponse != null) {
                        sendSseEvent(emitter, "result", skillResponse);
                        sendSseEvent(emitter, "complete", Map.of(
                                "status", skillResponse.getStatus(),
                                "cacheHit", false,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                        return;
                    }

                    // Sprint 9 P0.1 fix (2026-05-21): tool_name=NULL intent (WORKDESK 等)
                    // 当 trySkillRoute (keyword 匹用户原文) 失败时, 用 intent_code 显式查 Skill.
                    IntentExecuteResponse explicitSkillResp = dynamicToolSelectionService
                            .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId, userRole);
                    if (explicitSkillResp != null) {
                        sendSseEvent(emitter, "result", explicitSkillResp);
                        sendSseEvent(emitter, "complete", Map.of(
                                "status", explicitSkillResp.getStatus(),
                                "cacheHit", false,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                        return;
                    }
                } catch (Exception e) {
                    log.warn("[SSE] Skill 路由异常，回退到 Tool: {}", e.getMessage());
                }
            }

            // 7a.5 写意图先抽参 —— 与显式意图路径 (/preview、按钮点选) 同一个方法, 结果留在 context:
            //      Slot Filling 只追问真正没抽到的; 被 W0 拦下时随确认卡带去预览 (见 attachPreviewParams)。
            //      ⛔ 先过意图权限再抽: 没权限的请求不许碰执行层 (SseSemanticCacheAdmissionContractTest 守的就是这条),
            //      也不该为一个注定被拒的请求花一次 LLM 抽参、再追问参数。权限只算这一次, 结果带进 7c (不重算)。
            String admissionDenied = permissionDeniedMessage(intent, userId, userRole);
            if (admissionDenied == null) {
                admissionDenied = frontlineWriteDenied(factoryId, userRole, intent);
            }
            if (admissionDenied != null) {
                streamNoPermission(emitter, intent, admissionDenied, startTime);
                return;
            }
            prefillWriteToolParameters(request, intent);

            // 7b. Slot Filling
            if (userId != null && !Boolean.TRUE.equals(request.getSkipSlotFilling())
                    && slotFillingService != null) {
                try {
                    IntentExecuteResponse slotFillingResponse = slotFillingService.checkAndStartSlotFilling(
                            factoryId, userId, intent, request, matchResult);
                    if (slotFillingResponse != null) {
                        log.info("[SSE] 触发 Slot Filling: intentCode={}", intent.getIntentCode());
                        if (slotFillingResponse.getFormattedText() == null
                                && slotFillingResponse.getMessage() != null
                                && slotFillingResponse.getMessage().length() >= 5) {
                            slotFillingResponse.setFormattedText(slotFillingResponse.getMessage());
                        }
                        sendSseEvent(emitter, "result", slotFillingResponse);
                        sendSseEvent(emitter, "complete", Map.of(
                                "status", "NEED_MORE_INFO",
                                "cacheHit", false,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                        return;
                    }
                } catch (Exception e) {
                    log.warn("[SSE] Slot Filling 异常，直接执行 Tool: {}", e.getMessage());
                }
            }

            // 7c. 执行意图 (Tool) —— 权限已在 7a.5 判过
            executeAndStreamResult(
                    emitter, factoryId, request, intent, userId, userRole, startTime,
                    matchResult, factoryPackRoute, true);

        } catch (Exception e) {
            log.error("SSE 执行失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            try {
                sendSseEvent(emitter, "error", Map.of(
                        "message", ErrorSanitizer.sanitize(e),
                        "type", ErrorSanitizer.getSafeTypeName(e)
                ));
            } catch (Exception ignored) {
            }
            emitter.completeWithError(e);
        }
    }

    /** 最近几轮对话最多带几轮、每轮最多几个字 —— 只为消歧, 不是完整会话。 */
    static final int MAX_RECENT_TURNS = 4;
    static final int MAX_TURN_CHARS = 200;

    /**
     * App 随请求带来的 {@code context.recentTurns}: 只认 role=user/assistant 的非空文本,
     * 截断到 {@link #MAX_TURN_CHARS} 字, 取最后 {@link #MAX_RECENT_TURNS} 轮。格式不对的条目丢弃。
     */
    static java.util.List<Map<String, String>> recentTurns(IntentExecuteRequest request) {
        if (request == null || request.getContext() == null) {
            return java.util.Collections.emptyList();
        }
        Object raw = request.getContext().get("recentTurns");
        if (!(raw instanceof java.util.Collection<?> items)) {
            return java.util.Collections.emptyList();
        }
        java.util.List<Map<String, String>> out = new java.util.ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (!("user".equals(role) || "assistant".equals(role)) || !(content instanceof String text)
                    || text.isBlank()) {
                continue;
            }
            String trimmed = text.strip();
            out.add(Map.of("role", (String) role,
                    "content", trimmed.length() > MAX_TURN_CHARS ? trimmed.substring(0, MAX_TURN_CHARS) : trimmed));
        }
        return out.size() > MAX_RECENT_TURNS ? out.subList(out.size() - MAX_RECENT_TURNS, out.size()) : out;
    }

    /**
     * 澄清按钮点选 → 学习样本 (异步、尽力而为)。只有 App 在 context 里标了
     * {@code clarificationSelection=true} 才记, 普通的显式意图调用不算「用户替系统做了选择」。
     */
    void recordClarificationSelection(String factoryId, Long userId, String userRole,
                                      IntentExecuteRequest request, AIIntentConfig intent) {
        if (pythonAiMatcherClient == null || request == null || request.getContext() == null
                || !Boolean.TRUE.equals(request.getContext().get("clarificationSelection"))) {
            return;
        }
        // 追问确认按钮不是学习样本: 「好了 → 完成工序」只在「刚开始了一道工序」这个上下文里成立,
        // 记成「好了」的无条件样本会把别人的「好了」也往完成工序上拉。
        if (request.getContext().get(FollowUpResolver.FOLLOW_UP_RULE_KEY) != null) {
            return;
        }
        Object rawCandidates = request.getContext().get("clarificationCandidates");
        java.util.List<String> candidates = new java.util.ArrayList<>();
        if (rawCandidates instanceof java.util.Collection<?> c) {
            for (Object o : c) {
                if (o != null && candidates.size() < 10) {
                    candidates.add(String.valueOf(o));
                }
            }
        }
        String query = request.getUserInput();
        java.util.concurrent.CompletableFuture.runAsync(() -> pythonAiMatcherClient.recordSelection(
                factoryId, userId, userRole, query, intent.getIntentCode(), candidates));
    }

    private ConversationContext establishOwnedSession(String factoryId,
                                                       IntentExecuteRequest request,
                                                       Long userId) {
        if (conversationMemoryService == null) return null;
        if (request == null || factoryId == null || factoryId.isBlank() || userId == null) return null;
        ConversationContext context = conversationMemoryService.getOrCreateContext(
                factoryId, userId, request.getSessionId());
        if (context != null) request.setSessionId(context.getSessionId());
        return context;
    }

    private IntentExecuteResponse sessionOwnershipFailureResponse() {
        String message = "当前 AI 会话身份已失效或不属于当前工厂，未执行查询。请重新发起会话。";
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("SESSION_OWNERSHIP_DENIED")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private boolean isPaginationRequest(IntentExecuteRequest request) {
        if (request == null) return false;
        if ("PAGINATION_NEXT".equalsIgnoreCase(request.getIntentCode())
                || "NAVIGATION_NEXT_PAGE".equalsIgnoreCase(request.getIntentCode())) return true;
        if ("PAGINATION_PREVIOUS".equalsIgnoreCase(request.getIntentCode())
                || "NAVIGATION_PREVIOUS_PAGE".equalsIgnoreCase(request.getIntentCode())) return true;
        String input = request.getUserInput() == null ? "" : request.getUserInput().trim();
        return Set.of("下一页", "翻页", "翻到下一页", "更多", "上一页", "翻到上一页").contains(input);
    }

    private void executePaginationRequest(SseEmitter emitter, String factoryId,
                                          IntentExecuteRequest request, Long userId, String userRole,
                                          long startTime,
                                          FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute)
            throws IOException {
        boolean previous = isPreviousPaginationRequest(request);
        IntentExecuteResponse refusal = paginationRefusal(previous, "当前会话没有可继续的成功列表查询。");
        if (conversationMemoryService == null || request.getSessionId() == null
                || request.getSessionId().isBlank() || userId == null || factoryId == null) {
            streamTerminalResponse(emitter, refusal, startTime);
            return;
        }

        ConversationContext owner = conversationMemoryService.getContext(request.getSessionId());
        if (owner == null || !factoryId.equals(owner.getFactoryId()) || !userId.equals(owner.getUserId())) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "当前会话不属于当前用户或工厂，无法翻页。"), startTime);
            return;
        }
        Map<String, Object> metadata = latestPaginationMetadata(owner);
        if (metadata == null) {
            streamTerminalResponse(emitter, refusal, startTime);
            return;
        }
        String intentCode = stringValue(metadata.get("intentCode"));
        if (intentCode == null || intentCode.isBlank()) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "上次查询没有可复用的服务端意图。"), startTime);
            return;
        }
        Optional<AIIntentConfig> intentOpt = aiIntentService.getIntentByCode(factoryId, intentCode);
        if (intentOpt.isEmpty()) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "上次查询已不可用，无法安全翻页。"), startTime);
            return;
        }
        AIIntentConfig intent = intentOpt.get();
        Optional<ToolExecutor> toolOpt = intent.getToolName() == null
                ? Optional.empty() : toolRegistry.getExecutor(intent.getToolName());
        if (toolOpt.isEmpty() || toolOpt.get().getAccessMode() != ToolExecutor.AccessMode.READ
                || (writeGuardService != null && writeGuardService.isWriteTool(toolOpt.get()))
                || (writeGuardService != null && writeGuardService.isWriteIntent(intent))) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "上次查询不是受支持的只读列表，无法翻页。"), startTime);
            return;
        }
        Integer pageBase = paginationPageBase(toolOpt.get());

        Map<String, Object> replayContext = new LinkedHashMap<>();
        Object filters = metadata.get("filters");
        if (filters instanceof Map<?, ?> filterMap) {
            for (Map.Entry<?, ?> entry : filterMap.entrySet()) {
                String key = entry.getKey() == null ? null : String.valueOf(entry.getKey());
                if (key != null && entry.getValue() != null
                        && !Set.of("page", "pageBase", "paginationReplay").contains(key)) {
                    replayContext.put(key, entry.getValue());
                }
            }
        }
        Integer currentPage = strictPageValue(metadata.get("page"));
        if (currentPage == null) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "上次查询页码基准未知，无法安全翻页。"), startTime);
            return;
        }
        boolean hasMorePresent = metadata.containsKey("hasMore");
        Boolean hasMore = hasMorePresent ? strictBooleanValue(metadata.get("hasMore")) : null;
        if (hasMorePresent && hasMore == null) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "分页元数据中的 hasMore 无效，无法安全翻页。"), startTime);
            return;
        }
        boolean totalPagesPresent = metadata.containsKey("totalPages");
        Long totalPages = totalPagesPresent ? strictTotalPagesValue(metadata.get("totalPages")) : null;
        if (totalPagesPresent && totalPages == null) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "分页元数据中的总页数无效，无法安全翻页。"), startTime);
            return;
        }
        if (pageBase == null) {
            if (previous) {
                streamTerminalResponse(emitter, paginationRefusal(previous, "上次查询页码基准未知，无法安全翻页。"), startTime);
                return;
            }
            if (!hasMorePresent || !Boolean.TRUE.equals(hasMore)) {
                streamTerminalResponse(emitter, paginationRefusal(previous,
                        hasMorePresent ? "已经是最后一页，没有更多数据。" : "分页元数据缺少 hasMore，无法安全翻页。"), startTime);
                return;
            }
        } else if (totalPages != null) {
            long lastPage = pageBase == 0 ? totalPages - 1L : totalPages;
            if (totalPages <= 0 || currentPage < pageBase || (long) currentPage > lastPage) {
                streamTerminalResponse(emitter, paginationRefusal(previous, "当前页码超出服务端分页范围，无法安全翻页。"), startTime);
                return;
            }
            boolean atTail = currentPage == lastPage;
            if (hasMore != null && hasMore != !atTail) {
                streamTerminalResponse(emitter, paginationRefusal(previous, "分页元数据与总页数不一致，无法安全翻页。"), startTime);
                return;
            }
            if (!previous && atTail) {
                streamTerminalResponse(emitter, paginationRefusal(previous, "已经是最后一页，没有更多数据。"), startTime);
                return;
            }
        } else {
            if (currentPage < pageBase) {
                streamTerminalResponse(emitter, paginationRefusal(previous, "当前页码超出服务端分页范围，无法安全翻页。"), startTime);
                return;
            }
            if (!previous) {
                if (!hasMorePresent) {
                    streamTerminalResponse(emitter, paginationRefusal(previous,
                            "分页元数据缺少 hasMore 和总页数，无法安全翻页。"), startTime);
                    return;
                }
                if (Boolean.FALSE.equals(hasMore)) {
                    streamTerminalResponse(emitter, paginationRefusal(previous,
                            "已经是最后一页，没有更多数据。"), startTime);
                    return;
                }
            }
        }
        if (!previous && currentPage == Integer.MAX_VALUE) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "下一页页码超出可支持范围，无法安全翻页。"), startTime);
            return;
        }
        int targetPage = previous ? currentPage - 1 : currentPage + 1;
        if (previous && targetPage < pageBase) {
            streamTerminalResponse(emitter, paginationRefusal(previous, "已经是第一页，没有上一页。"), startTime);
            return;
        }
        replayContext.put("page", targetPage);
        Object size = metadata.get("size");
        if (size != null) replayContext.put("size", size);
        replayContext.put("paginationReplay", true);
        String queryInput = stringValue(metadata.get("queryInput"));
        if (queryInput == null || queryInput.isBlank()) queryInput = "列表查询";
        IntentExecuteRequest replay = IntentExecuteRequest.builder()
                .userInput(queryInput)
                .intentCode(intentCode)
                .context(replayContext)
                .sessionId(request.getSessionId())
                .mode(request.getMode())
                .build();
        IntentMatchResult replayMatch = IntentMatchResult.builder()
                .bestMatch(intent)
                .confidence(1.0d)
                .matchMethod(IntentMatchResult.MatchMethod.EXACT)
                .requiresConfirmation(false)
                .build();
        sendSseEvent(emitter, "intent_recognized", Map.of(
                "intentCode", intentCode,
                "intentName", intent.getIntentName() == null ? intentCode : intent.getIntentName(),
                "confidence", 1.0d,
                "matchMethod", "SERVER_SESSION"));
        executeAndStreamResult(emitter, factoryId, replay, intent, userId, userRole,
                startTime, replayMatch, factoryPackRoute);
    }

    private IntentExecuteResponse paginationRefusal(String message) {
        return paginationRefusal(false, message);
    }

    private IntentExecuteResponse paginationRefusal(boolean previous, String message) {
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(previous ? "PAGINATION_PREVIOUS" : "PAGINATION_NEXT")
                .status("PAGINATION_REFUSED")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private boolean isPreviousPaginationRequest(IntentExecuteRequest request) {
        if (request == null) return false;
        if ("PAGINATION_PREVIOUS".equalsIgnoreCase(request.getIntentCode())
                || "NAVIGATION_PREVIOUS_PAGE".equalsIgnoreCase(request.getIntentCode())) return true;
        String input = request.getUserInput() == null ? "" : request.getUserInput().trim();
        return Set.of("上一页", "翻到上一页").contains(input);
    }

    private Integer strictPageValue(Object value) {
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.signum() < 0
                        || decimal.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return null;
                return decimal.intValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof String text) {
            try {
                int parsed = Integer.parseInt(text.trim());
                return parsed < 0 ? null : parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Boolean strictBooleanValue(Object value) {
        return value instanceof Boolean booleanValue ? booleanValue : null;
    }

    private Long strictTotalPagesValue(Object value) {
        if (value instanceof Number number) {
            try {
                BigDecimal decimal = new BigDecimal(number.toString());
                if (decimal.signum() < 0 || decimal.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                    return null;
                }
                return decimal.longValueExact();
            } catch (ArithmeticException | NumberFormatException ignored) {
                return null;
            }
        }
        if (value instanceof String text) {
            try {
                long parsed = Long.parseLong(text.trim());
                return parsed < 0 ? null : parsed;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Integer paginationPageBase(ToolExecutor executor) {
        if (executor == null) return null;
        Map<String, Object> schema;
        try {
            schema = executor.getParametersSchema();
        } catch (RuntimeException ignored) {
            return null;
        }
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties)) return null;
        Object rawPage = properties.get("page");
        if (!(rawPage instanceof Map<?, ?> pageSchema)) return null;
        Integer minimum = strictPageValue(pageSchema.get("minimum"));
        if (minimum == null || (minimum != 0 && minimum != 1)) return null;
        if (pageSchema.containsKey("default")) {
            Integer defaultPage = strictPageValue(pageSchema.get("default"));
            if (defaultPage == null || defaultPage < minimum) return null;
        }
        return minimum;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> latestPaginationMetadata(ConversationContext context) {
        List<ConversationMessage> messages = context.getRecentMessages();
        if (messages == null) return null;
        for (int i = messages.size() - 1; i >= 0; i--) {
            ConversationMessage message = messages.get(i);
            if (message.getRole() != ConversationMessage.Role.ASSISTANT || message.getMetadata() == null) continue;
            Object raw = message.getMetadata().get("pagination");
            if (raw instanceof Map<?, ?> map) return new LinkedHashMap<>((Map<String, Object>) map);
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private int numberValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try { return value == null ? fallback : Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return fallback; }
    }

    /**
     * 执行意图并流式返回结果
     */
    private void executeAndStreamResult(SseEmitter emitter, String factoryId,
                                         IntentExecuteRequest request, AIIntentConfig intent,
                                         Long userId, String userRole, long startTime,
                                         IntentMatchResult matchResult,
                                         FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute)
            throws IOException {
        executeAndStreamResult(emitter, factoryId, request, intent, userId, userRole, startTime,
                matchResult, factoryPackRoute, false);
    }

    /**
     * @param permissionAlreadyChecked 调用方刚用 {@link #permissionDeniedMessage} 判过且放行 —— 不重算
     *        (每个请求只算一次意图权限; 其它入口一律传 false)。
     */
    private void executeAndStreamResult(SseEmitter emitter, String factoryId,
                                         IntentExecuteRequest request, AIIntentConfig intent,
                                         Long userId, String userRole, long startTime,
                                         IntentMatchResult matchResult,
                                         FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute,
                                         boolean permissionAlreadyChecked)
            throws IOException {
        if (!permissionAlreadyChecked) {
            String deniedMessage = permissionDeniedMessage(intent, userId, userRole);
            if (deniedMessage != null) {
                streamNoPermission(emitter, intent, deniedMessage, startTime);
                return;
            }
        }

        IntentExecuteResponse factoryPackResponse =
                applyFactoryPackDecision(factoryPackRoute, intent);
        if (factoryPackResponse != null) {
            streamTerminalResponse(emitter, factoryPackResponse, startTime);
            return;
        }

        // 审批检查
        if (intent.needsApproval() && !Boolean.TRUE.equals(request.getForceExecute())) {
            IntentExecuteResponse approvalResponse = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("PENDING_APPROVAL")
                    .message("此操作需要审批，已提交审批请求")
                    .requiresApproval(true)
                    .executedAt(LocalDateTime.now())
                    .build();
            sendSseEvent(emitter, "result", approvalResponse);
            sendSseEvent(emitter, "complete", Map.of(
                    "status", "PENDING_APPROVAL",
                    "totalLatencyMs", System.currentTimeMillis() - startTime
            ));
            emitter.complete();
            return;
        }

        // 业态门控 (Sprint 13 #305): domain-exclusive intent on a mismatched factory.type →
        // honest empty-state + domain next-action, instead of executing a tool with no data.
        if (!factoryPackRoute.isConstrained()) {
            Optional<IntentExecuteResponse> gate = businessTypeGate.check(factoryId, intent);
            if (gate.isPresent()) {
                IntentExecuteResponse gateResponse = gate.get();
                sendSseEvent(emitter, "result", gateResponse);
                sendSseEvent(emitter, "complete", Map.of(
                        "status", gateResponse.getStatus() != null ? gateResponse.getStatus() : "NOT_APPLICABLE",
                        "totalLatencyMs", System.currentTimeMillis() - startTime
                ));
                emitter.complete();
                return;
            }
        }

        // 发送执行中事件
        sendSseEvent(emitter, "executing", Map.of(
                "intentCode", intent.getIntentCode(),
                "intentName", intent.getIntentName()
        ));

        // 路由到 Tool
        String toolName = intent.getToolName();
        IntentExecuteResponse response;

        if (toolName != null && !toolName.isEmpty()) {
            Optional<ToolExecutor> toolOpt = toolRegistry.getExecutor(toolName);
            if (toolOpt.isPresent()) {
                log.info("[SSE] 使用 Tool 执行: intentCode={}, toolName={}", intent.getIntentCode(), toolName);
                response = toolDispatchService.executeWithTool(toolOpt.get(), factoryId, request, intent, userId, userRole, matchResult);
                attachPreviewParams(response, request, toolOpt.get());
            } else {
                log.warn("[SSE] Tool 未找到: toolName={}", toolName);
                response = noToolResponseWithRestaurantFallback(intent, factoryId, request);
            }
        } else {
            // Sprint 9 P0.1 fix (2026-05-21): tool_name=NULL — 走 explicit Skill fallback (cover
            // 7a Skill route 已尝试但仍 fall through 到 executeAndStreamResult 的 corner case).
            IntentExecuteResponse explicitSkillFallback = dynamicToolSelectionService
                    .tryExplicitSkillRouteForIntent(intent, request.getUserInput(), factoryId, userId, userRole);
            if (explicitSkillFallback != null) {
                log.info("[SSE] 显式 Skill 路由 (executeAndStreamResult fallback) 成功: intentCode={}",
                        intent.getIntentCode());
                response = explicitSkillFallback;
            } else {
                log.warn("[SSE] 无 Tool 绑定 + 无 Skill 匹配: intentCode={}", intent.getIntentCode());
                response = noToolResponseWithRestaurantFallback(intent, factoryId, request);
            }
        }

        // 格式化结果
        boolean isSuccessStatus = "SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus());
        if (resultFormatterService != null && isSuccessStatus && response.getResultData() != null) {
            try {
                resultFormatterService.formatAndSet(response);
            } catch (Exception e) {
                log.warn("[SSE] 结果格式化异常: {}", e.getMessage());
            }
        }
        if (response.getFormattedText() == null && response.getMessage() != null
                && response.getMessage().length() >= 5) {
            response.setFormattedText(response.getMessage());
        }

        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            response.setSessionId(request.getSessionId());
        }
        persistSuccessfulReadPagination(factoryId, userId, request, intent, toolName, response);

        sendSseEvent(emitter, "result", response);

        // Cache recognition only; business responses belong to the current actor.
        if (("COMPLETED".equals(response.getStatus()) || "SUCCESS".equals(response.getStatus()))
                && recentTurns(request).isEmpty()      // 带上下文的结果不写入按原话缓存
                && (request.getIntentCode() == null || request.getIntentCode().isBlank())) {
            // 显式意图码 (澄清按钮点选) 也不写: 那是这一个人的选择, 写进去会按原话回放给所有人
            try {
                semanticCacheService.cacheIntentResult(factoryId, request.getUserInput(), matchResult);
            } catch (Exception e) {
                log.warn("缓存意图结果失败: {}", e.getMessage());
            }
        }

        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("status", response.getStatus());
        complete.put("cacheHit", false);
        complete.put("totalLatencyMs", System.currentTimeMillis() - startTime);
        if (request.getSessionId() != null && !request.getSessionId().isBlank()) {
            complete.put("sessionId", request.getSessionId());
        }
        sendSseEvent(emitter, "complete", complete);
        emitter.complete();
    }

    /**
     * 权限检查: 意图声明了权限码 → 走权限码门 (fail-closed, 与 /execute 同源);
     * 没声明 → 保留 requiredRoles 旧判定。返回 null 表示放行, 否则是给用户看的拒绝原因。
     * 执行前 ({@link #executeAndStreamResult}) 与追问确认按钮出按钮前共用这一份。
     */
    private void streamNoPermission(SseEmitter emitter, AIIntentConfig intent, String deniedMessage,
                                    long startTime) throws IOException {
        IntentExecuteResponse noPermissionResponse = IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .status("NO_PERMISSION")
                .message(deniedMessage)
                .formattedText(deniedMessage)
                .executedAt(LocalDateTime.now())
                .build();
        sendSseEvent(emitter, "result", noPermissionResponse);
        sendSseEvent(emitter, "complete", Map.of(
                "status", "NO_PERMISSION",
                "totalLatencyMs", System.currentTimeMillis() - startTime
        ));
        emitter.complete();
    }

    private String permissionDeniedMessage(AIIntentConfig intent, Long userId, String userRole) {
        String requiredPermission = intent.getRequiredPermission();
        if (requiredPermission != null && !requiredPermission.isBlank() && intentPermissionGate != null) {
            IntentPermissionGate.PermissionCheck check = intentPermissionGate.check(intent, userId, userRole);
            if (check.isDenied()) {
                return "您没有权限执行此操作。需要权限: " + check.requiredPermission()
                        + "，请联系工厂管理员开通。";
            }
        } else if (!aiIntentService.hasPermission(intent.getIntentCode(), userRole)) {
            return "您没有权限执行此操作。需要角色: " + intent.getRequiredRoles();
        }
        return null;
    }

    /**
     * 追问解读 (2026-10-04): App 带着 {@code context.lastAction} 来, 这一句又是
     * {@link FollowUpResolver} 规则表里的短句 → 不识别、不执行, 直接回一个「您是要 X 吗？」
     * 和<b>一个</b> SELECT_INTENT 确认按钮。用户点了按钮, App 用「原话 + 意图码 + 按钮参数」
     * 回来, 走上面的显式意图分支 —— 权限 / 审批 / 业态门 / W0 写确认全部照常。
     *
     * <p>目标意图不在用 / 当前角色没权限 → 不出按钮, 返回 false 让正常识别流程接着跑。
     *
     * @return true 表示已经流出确认响应, 调用方必须就地 return
     */
    private boolean streamFollowUpConfirmation(SseEmitter emitter, String factoryId,
                                               IntentExecuteRequest request, Long userId, String userRole,
                                               long startTime) throws IOException {
        if (request.getContext() == null || !request.getContext().containsKey(LastActionContract.CONTEXT_KEY)) {
            return false;
        }
        Optional<FollowUpResolver.FollowUp> resolved = followUpResolver.resolve(
                request.getUserInput(), request.getContext().get(LastActionContract.CONTEXT_KEY),
                System.currentTimeMillis());
        if (resolved.isEmpty()) {
            return false;
        }
        FollowUpResolver.FollowUp followUp = resolved.get();
        Optional<AIIntentConfig> target = aiIntentService.getIntentByCode(factoryId, followUp.intentCode());
        if (target.isEmpty()) {
            log.info("[SSE][FollowUp] 规则 {} 命中但目标意图不在用: {}", followUp.ruleId(), followUp.intentCode());
            return false;
        }
        AIIntentConfig intent = target.get();
        if (permissionDeniedMessage(intent, userId, userRole) != null) {
            log.info("[SSE][FollowUp] 规则 {} 命中但当前角色无权执行 {}, 交回正常流程",
                    followUp.ruleId(), followUp.intentCode());
            return false;
        }

        String intentName = intent.getIntentName() != null ? intent.getIntentName() : intent.getIntentCode();
        String detail = followUp.summary() == null || followUp.summary().isBlank()
                ? "" : "（" + followUp.summary() + "）";
        Map<String, Object> buttonParams = new LinkedHashMap<>();
        buttonParams.put("intentCode", intent.getIntentCode());
        buttonParams.putAll(followUp.params());
        buttonParams.put(FollowUpResolver.FOLLOW_UP_RULE_KEY, followUp.ruleId());
        IntentExecuteResponse.SuggestedAction confirm = IntentExecuteResponse.SuggestedAction.builder()
                .actionCode("SELECT_INTENT")
                .actionName(intentName + detail)
                .description("点一下确认; 不是的话直接告诉我您要做什么")
                .endpoint("/api/mobile/" + factoryId + "/ai-intents/execute")
                .parameters(buttonParams)
                .build();
        String message = "您是要「" + intentName + "」吗？" + detail;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put(FollowUpResolver.FOLLOW_UP_RULE_KEY, followUp.ruleId());
        log.info("[SSE][FollowUp] 规则 {} → 确认按钮 {} (不直接执行)", followUp.ruleId(), intent.getIntentCode());
        streamTerminalResponse(emitter, IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status("NEED_CLARIFICATION")
                .message(message)
                .formattedText(message)
                .suggestedActions(List.of(confirm))
                .metadata(metadata)
                .sessionId(request.getSessionId())
                .executedAt(LocalDateTime.now())
                .build(), startTime);
        return true;
    }

    /**
     * 缺参续轮 (2026-10-06)。上一轮 Slot Filling 问了「请提供工序任务ID」并建了参数收集会话,
     * 结果里的 {@code sessionId} 就是那个会话 —— App 把它当会话 id 原样带回来。这一句「1827」要接着
     * 填进那个槽、继续<b>同一个意图</b>, 而不是当一句新话重新识别 (prod 实测: 会话建了, 从没被读过,
     * 「1827」出了澄清按钮「开始工序 / 查询温度记录」)。
     *
     * <h3>规则 —— 什么时候算「在回答那个问题」</h3>
     * <ol>
     *   <li>请求带的 sessionId 指向一个参数收集会话, 按 工厂 + 本人 查得到 (别人的 / 别厂的查不到);</li>
     *   <li>会话仍 ACTIVE 且没超时 (cretas.ai.conversation.timeout-minutes, 默认 10 分钟);</li>
     *   <li>这句话「像一个值」({@link SlotAnswerRule}): 数字型槽位 = 去掉数字和包装词后什么都不剩。</li>
     * </ol>
     * 1、2 不满足 → 当没有会话, 正常识别。3 不满足 → 用户换话题了: 关掉这个会话再正常识别 ——
     * 否则十分钟内随便一个数字都会被这个旧问题截走。显式意图码 (点了按钮) 不进这里, 见
     * {@link #closeOpenSlotSession}。
     *
     * <p>补齐之后用「会话里已收集的全部参数」(含首句抽到的, 见 SlotFillingServiceImpl) 走显式意图执行:
     * 权限 / 审批 / 业态门 / W0 写确认全部照常, 写操作照样只出确认卡。
     *
     * @return true 表示已经流出响应, 调用方必须就地 return
     */
    private boolean streamSlotContinuation(SseEmitter emitter, String factoryId, IntentExecuteRequest request,
                                           Long userId, String userRole, long startTime,
                                           FactoryCapabilityPackRoutingPolicy.Route factoryPackRoute)
            throws IOException {
        Optional<com.cretas.aims.entity.conversation.ConversationSession> open =
                openSlotSession(factoryId, userId, request);
        if (open.isEmpty()) {
            return false;
        }
        com.cretas.aims.entity.conversation.ConversationSession session = open.get();
        String slotSessionId = session.getSessionId();
        List<com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter> pending =
                session.getRequiredParameters().stream().filter(p -> !p.isCollected()).toList();
        if (!SlotAnswerRule.looksLikeAnswer(request.getUserInput(), pending,
                text -> knowledgeBase.matchPhrase(text).isPresent())) {
            log.info("[SSE][SlotContinuation] 这句不像在回答缺参问题, 关闭会话后正常识别: session={}, pending={}",
                    slotSessionId, pending.stream().map(p -> p.getName()).toList());
            conversationService.cancelConversation(factoryId, userId, slotSessionId);
            return false;
        }
        Optional<AIIntentConfig> target = session.getKnownIntentCode() == null
                ? Optional.empty()
                : aiIntentService.getIntentByCode(factoryId, session.getKnownIntentCode());
        if (target.isEmpty()) {
            log.info("[SSE][SlotContinuation] 会话的意图已不在用, 关闭会话: session={}, intent={}",
                    slotSessionId, session.getKnownIntentCode());
            conversationService.cancelConversation(factoryId, userId, slotSessionId);
            return false;
        }
        AIIntentConfig intent = target.get();

        com.cretas.aims.service.ConversationService.ConversationResponse reply =
                conversationService.continueConversation(factoryId, userId, slotSessionId, request.getUserInput());
        if (reply == null || reply.getStatus() == null
                || reply.getStatus() == com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.CANCELLED
                || reply.getStatus() == com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.TIMEOUT) {
            return false;
        }

        if (reply.isCompleted()) {
            Map<String, Object> context = new HashMap<>();
            if (request.getContext() != null) {
                context.putAll(request.getContext());
            }
            // 会话里记的是服务端自己收集的值, 盖过同名的客户端字段
            conversationService.getSession(factoryId, userId, slotSessionId)
                    .map(com.cretas.aims.entity.conversation.ConversationSession::getCollectedParameters)
                    .ifPresent(context::putAll);
            request.setContext(com.cretas.aims.ai.tool.WriteGuardService.withoutCallerConfirmation(context));
            request.setIntentCode(intent.getIntentCode());
            log.info("[SSE][SlotContinuation] 缺参已补齐, 继续原意图: session={}, intent={}, params={}",
                    slotSessionId, intent.getIntentCode(), context.keySet());
            Map<String, Object> recognized = new LinkedHashMap<>();
            recognized.put("intentCode", intent.getIntentCode());
            recognized.put("intentName", intent.getIntentName());
            recognized.put("intentCategory", intent.getIntentCategory());
            recognized.put("confidence", 1.0d);
            recognized.put("matchMethod", "SLOT_CONTINUATION");
            sendSseEvent(emitter, "intent_recognized", recognized);
            executeAndStreamResult(emitter, factoryId, request, intent, userId, userRole, startTime,
                    IntentMatchResult.builder()
                            .bestMatch(intent)
                            .confidence(1.0d)
                            .matchMethod(IntentMatchResult.MatchMethod.EXACT)
                            .requiresConfirmation(false)
                            .questionType(QuestionType.OPERATIONAL_COMMAND)
                            .build(),
                    factoryPackRoute);
            return true;
        }

        // 还缺别的 / 轮次用尽: 把会话的下一问 (或结束语) 原样流出, 会话 id 不变
        String message = reply.getMessage() == null ? "" : reply.getMessage().replace("**", "").strip();
        boolean stillCollecting = reply.getStatus()
                == com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE;
        streamTerminalResponse(emitter, IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status(stillCollecting ? "NEED_MORE_INFO" : reply.getStatus().name())
                .message(message)
                .formattedText(message)
                .sessionId(slotSessionId)
                .executedAt(LocalDateTime.now())
                .build(), startTime);
        return true;
    }

    /** 请求点名的、本人本厂的、仍在收集参数且没超时的会话。查询失败一律当没有。 */
    private Optional<com.cretas.aims.entity.conversation.ConversationSession> openSlotSession(
            String factoryId, Long userId, IntentExecuteRequest request) {
        if (conversationService == null || userId == null || request == null
                || request.getSessionId() == null || request.getSessionId().isBlank()
                || request.getUserInput() == null || request.getUserInput().isBlank()) {
            return Optional.empty();
        }
        try {
            return conversationService.getSession(factoryId, userId, request.getSessionId())
                    .filter(com.cretas.aims.entity.conversation.ConversationSession::isParameterCollectionMode)
                    .filter(s -> s.getStatus()
                            == com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.ACTIVE)
                    .filter(s -> !s.isExpired());
        } catch (Exception e) {
            log.warn("[SSE][SlotContinuation] 查参数收集会话失败, 按没有会话处理: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** 用户点了按钮 (显式意图码): 之前没答完的缺参追问作废。 */
    private void closeOpenSlotSession(String factoryId, Long userId, IntentExecuteRequest request, String reason) {
        Optional<com.cretas.aims.entity.conversation.ConversationSession> open =
                openSlotSession(factoryId, userId, request);
        if (open.isPresent()) {
            log.info("[SSE][SlotContinuation] 关闭未答完的缺参追问: session={}, reason={}",
                    open.get().getSessionId(), reason);
            conversationService.cancelConversation(factoryId, userId, open.get().getSessionId());
        }
    }

    /**
     * 写意图在流式入口先抽参 (2026-10-06)。调用的是显式意图路径用的<b>同一个方法</b>
     * ({@link ToolDispatchService#extractParametersWithLLM}) —— 抽什么、接受什么由那里定, 这里不复制。
     *
     * <p>为什么只对写: 写意图在 executeWithTool 里先被 W0 拦下, 走不到它自己的抽参步骤;
     * 以前这一步要等 App 再调 /preview 时重新抽一遍, prod 2026-10-06 那一次 (/api/llm/tool-call 500)
     * 失败了, 于是「开始1827号工序任务」被反问任务号, 而流式这边其实已经抽到了 1827。
     * 读意图不提前: executeWithTool 会先用学习规则、再用同一个方法补缺, 提前跑会绕过学习规则。
     * 抽到的只补 context 里没有的键。
     */
    private void prefillWriteToolParameters(IntentExecuteRequest request, AIIntentConfig intent) {
        String toolName = intent.getToolName();
        if (toolName == null || toolName.isBlank()
                || request.getUserInput() == null || request.getUserInput().isBlank()) {
            return;
        }
        Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
        if (tool.isEmpty() || writeGuardService == null
                || !(writeGuardService.isWriteTool(tool.get()) || writeGuardService.isWriteIntent(intent))) {
            return;
        }
        Map<String, Object> context = new HashMap<>();
        if (request.getContext() != null) {
            context.putAll(request.getContext());
        }
        Map<String, Object> extracted;
        try {
            extracted = toolDispatchService.extractParametersWithLLM(request.getUserInput(), tool.get(), context);
        } catch (Exception e) {
            log.warn("[SSE] 写意图抽参失败, 交给 Slot Filling: intentCode={}, error={}",
                    intent.getIntentCode(), e.getMessage());
            return;
        }
        if (extracted == null || extracted.isEmpty()) {
            return;
        }
        extracted.forEach(context::putIfAbsent);
        request.setContext(com.cretas.aims.ai.tool.WriteGuardService.withoutCallerConfirmation(context));
        log.info("[SSE] 写意图抽参: intentCode={}, keys={}", intent.getIntentCode(), extracted.keySet());
    }

    /** 主体键与确认类键不随确认卡带去预览 (预览端会按 JWT 重新绑定, 这里也不给它们出门的机会)。 */
    private static final Set<String> PREVIEW_PARAM_EXCLUDED = Set.of(
            "factoryId", "factory_id", "tenantId", "tenant_id", "userId", "user_id",
            "actorUserId", "actor_user_id", "userRole", "user_role", "role",
            "userInput", "intentCode", "confirmed", "forceExecute", "force_execute");
    private static final int MAX_PREVIEW_PARAM_CHARS = 200;

    /**
     * 写操作被 W0 拦下 (WRITE_CONFIRM_REQUIRED) 时, 把这一轮已经定下来的工具参数写进
     * {@code metadata.previewParams}。App 请求 /preview 时原样放进 context —— 预览不用再从原话
     * 重新抽一遍 (那一遍可能失败, 也可能抽得和这里不一样), 追问确认按钮带来的 taskId 也到得了预览。
     *
     * <p>只带工具 schema 里声明的参数、只带标量。这些值本来就来自这个用户自己的话 / 他点的按钮,
     * /preview 也本来就接受 context; 带回去不给任何新权限 —— 预览端照样重绑主体、照样出确认卡。
     */
    void attachPreviewParams(IntentExecuteResponse response, IntentExecuteRequest request, ToolExecutor tool) {
        if (response == null || !"WRITE_CONFIRM_REQUIRED".equals(response.getStatus())
                || request == null || request.getContext() == null || tool == null) {
            return;
        }
        Map<String, Object> schema = tool.getParametersSchema();
        if (schema == null || !(schema.get("properties") instanceof Map<?, ?> properties) || properties.isEmpty()) {
            return;
        }
        Map<String, Object> previewParams = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : request.getContext().entrySet()) {
            String key = e.getKey();
            Object value = e.getValue();
            if (key == null || !properties.containsKey(key) || PREVIEW_PARAM_EXCLUDED.contains(key)) {
                continue;
            }
            if (value instanceof Number || value instanceof Boolean
                    || (value instanceof String s && !s.isBlank() && s.length() <= MAX_PREVIEW_PARAM_CHARS)) {
                previewParams.put(key, value);
            }
        }
        if (previewParams.isEmpty()) {
            return;
        }
        Map<String, Object> metadata = response.getMetadata() != null
                ? new LinkedHashMap<>(response.getMetadata()) : new LinkedHashMap<>();
        metadata.put("previewParams", previewParams);
        response.setMetadata(metadata);
    }

    private void persistSuccessfulReadPagination(String factoryId, Long userId,
                                                 IntentExecuteRequest request,
                                                 AIIntentConfig intent, String toolName,
                                                 IntentExecuteResponse response) {
        if (conversationMemoryService == null || request == null || userId == null
                || request.getSessionId() == null || request.getSessionId().isBlank()
                || intent == null || toolName == null || response == null
                || !("SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus()))) {
            return;
        }
        Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
        if (executor.isEmpty() || executor.get().getAccessMode() != ToolExecutor.AccessMode.READ
                || (writeGuardService != null && writeGuardService.isWriteTool(executor.get()))
                || (writeGuardService != null && writeGuardService.isWriteIntent(intent))) {
            return;
        }
        Map<String, Object> pagination = buildPaginationMetadata(request, intent, toolName,
                executor.get(), response.getResultData());
        if (pagination == null) return;

        try {
            ConversationContext owner = conversationMemoryService.getOrCreateContext(
                    factoryId, userId, request.getSessionId());
            if (owner == null || !factoryId.equals(owner.getFactoryId()) || !userId.equals(owner.getUserId())) return;
            conversationMemoryService.addMessage(request.getSessionId(),
                    ConversationMessage.user(request.getUserInput()));
            ConversationMessage assistant = ConversationMessage.assistant(
                    response.getMessage() == null ? "查询完成" : response.getMessage(), intent.getIntentCode());
            Map<String, Object> messageMetadata = new LinkedHashMap<>();
            messageMetadata.put("pagination", pagination);
            assistant.setMetadata(messageMetadata);
            conversationMemoryService.addMessage(request.getSessionId(), assistant);
            conversationMemoryService.updateLastIntent(request.getSessionId(), intent.getIntentCode());
        } catch (Exception e) {
            log.warn("SSE 对话记忆持久化失败: factoryId={}, userId={}, failureType={}",
                    factoryId, userId, e.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPaginationMetadata(IntentExecuteRequest request,
                                                        AIIntentConfig intent, String toolName,
                                                        ToolExecutor executor,
                                                        Object rawResultData) {
        Map<String, Object> pageData = findPageMap(rawResultData);
        if (pageData == null) return null;
        Object pageValue = pageData.containsKey("currentPage")
                ? pageData.get("currentPage") : pageData.get("page");
        if (pageValue == null) return null;
        Integer pageNumber = strictPageValue(pageValue);
        if (pageNumber == null) return null;
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("intentCode", intent.getIntentCode());
        metadata.put("toolName", toolName);
        metadata.put("queryInput", request.getUserInput());
        metadata.put("page", pageNumber);
        Integer pageBase = paginationPageBase(executor);
        if (pageBase != null && (pageBase == 0 || pageBase == 1)) metadata.put("pageBase", pageBase);
        Object size = firstValue(pageData, "size", "pageSize", "limit");
        if (size == null && request.getContext() != null) {
            size = firstValue(request.getContext(), "size", "pageSize", "limit");
        }
        if (size != null) metadata.put("size", size);
        Object hasMore = pageData.get("hasMore");
        if (hasMore != null) metadata.put("hasMore", hasMore);
        Object totalPages = pageData.get("totalPages");
        if (totalPages != null) metadata.put("totalPages", totalPages);

        Object conditions = firstValue(pageData, "queryConditions", "filters", "filter");
        if (conditions instanceof Map<?, ?> conditionMap) {
            metadata.put("filters", copyMap(conditionMap));
        } else if (request.getContext() != null) {
            Map<String, Object> requestFilters = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : request.getContext().entrySet()) {
                if (entry.getValue() != null && !Set.of("page", "pageBase", "size", "paginationReplay").contains(entry.getKey())) {
                    requestFilters.put(entry.getKey(), entry.getValue());
                }
            }
            if (!requestFilters.isEmpty()) metadata.put("filters", requestFilters);
        }
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> findPageMap(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return null;
        Map<String, Object> candidate = copyMap(map);
        if (candidate.containsKey("currentPage") || candidate.containsKey("page")) return candidate;
        Object nested = candidate.get("data");
        return nested instanceof Map<?, ?> ? findPageMap(nested) : null;
    }

    private Object firstValue(Map<String, Object> map, String... keys) {
        for (String key : keys) if (map.containsKey(key)) return map.get(key);
        return null;
    }

    private Map<String, Object> copyMap(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (entry.getKey() != null) copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    /**
     * 流式生成对话回复
     */
    private void streamConversationalResponse(SseEmitter emitter, String factoryId,
                                               Long userId, String userRole, String sessionId,
                                               String userInput, QuestionType questionType,
                                               Boolean enableThinking, Integer thinkingBudget,
                                               long startTime,
                                               com.cretas.aims.ai.client.F006BossQuestionClient.Cancellation miniCancellation) throws IOException {
        if (questionType == QuestionType.GENERAL_QUESTION && f006BossQuestionClient != null
                && f006BossQuestionClient.shouldRoute(factoryId, userId, userRole)) {
            if (conversationMemoryService == null || sessionId == null || sessionId.isBlank()) {
                streamTerminalResponse(emitter, sessionOwnershipFailureResponse(), startTime);
                return;
            }
            streamF006MiniQuestion(emitter, factoryId, userId, userRole, sessionId,
                    userInput, Boolean.TRUE.equals(enableThinking), startTime, miniCancellation);
            return;
        }
        String systemPrompt;
        if (questionType == QuestionType.GENERAL_QUESTION) {
            systemPrompt = """
                你是白垩纪AI Agent的智能助手。用户正在询问一个关于生产管理、质量控制或食品安全的通用咨询问题。

                请根据以下原则回答：
                1. 提供专业、实用的建议
                2. 结合食品加工行业的最佳实践
                3. 如果问题涉及具体数据查询，建议用户使用系统的具体功能
                4. 回答简洁明了，不超过300字
                5. 使用中文回答

                注意：这不是一个具体的系统操作指令，而是通用知识咨询。
                """;
        } else {
            systemPrompt = """
                你是白垩纪AI Agent的智能助手。用户发起了一个日常对话。

                请根据以下原则回答：
                1. 友好、亲切地回应
                2. 如果用户打招呼，简单回应并询问是否需要帮助
                3. 适时引导用户使用系统功能
                4. 回答简洁，不超过100字
                5. 使用中文回答
                """;
        }

        boolean useThinking = Boolean.TRUE.equals(enableThinking)
                && questionType == QuestionType.GENERAL_QUESTION
                && dashScopeConfig.isThinkingEnabled();

        String model = useThinking ? dashScopeConfig.getModel() : dashScopeConfig.getFastModel();
        int maxTokens = useThinking ? 2000 : 500;

        ChatCompletionRequest aiRequest = ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(ChatMessage.system(systemPrompt), ChatMessage.user(userInput)))
                .temperature(0.7)
                .maxTokens(maxTokens)
                .extraBody(ChatCompletionRequest.ExtraBody.builder()
                        .enableThinking(useThinking).build())
                .build();

        sendSseEvent(emitter, "meta", Map.of(
                "model", model,
                "thinking", useThinking,
                "questionType", questionType.name()));

        try {
            dashScopeClient.chatCompletionStream(aiRequest,
                token -> {
                    try {
                        emitter.send(SseEmitter.event().name("token").data(token));
                    } catch (Exception e) {
                        log.debug("Client disconnected during stream token");
                    }
                },
                response -> {
                    try {
                        String fullContent = response.getContent() != null ? response.getContent() : "";
                        Integer tokensUsed = response.getUsage() != null
                                ? response.getUsage().getTotalTokens() : null;
                        String finishReason = response.getChoices() != null && !response.getChoices().isEmpty()
                                ? response.getChoices().get(0).getFinishReason() : "stop";

                        IntentExecuteResponse fullResult = IntentExecuteResponse.builder()
                                .intentRecognized(false)
                                .status("COMPLETED")
                                .message(fullContent)
                                .formattedText(fullContent)
                                .executedAt(LocalDateTime.now())
                                .build();
                        sendSseEvent(emitter, "result", fullResult);

                        sendSseEvent(emitter, "complete", Map.of(
                                "status", "SUCCESS",
                                "fullContent", fullContent,
                                "tokensUsed", tokensUsed != null ? tokensUsed : 0,
                                "finishReason", finishReason,
                                "model", model,
                                "totalLatencyMs", System.currentTimeMillis() - startTime
                        ));
                        emitter.complete();
                    } catch (Exception e) {
                        log.debug("Error sending stream complete: {}", e.getMessage());
                    }
                });
        } catch (Exception e) {
            log.error("Stream conversational response failed: {}", e.getMessage());
            String fallback = questionType == QuestionType.GENERAL_QUESTION
                    ? "抱歉，我暂时无法回答您的问题。您可以尝试询问具体的系统操作。"
                    : "您好！有什么可以帮您的吗？";

            IntentExecuteResponse fallbackResult = IntentExecuteResponse.builder()
                    .intentRecognized(false)
                    .status("COMPLETED")
                    .message(fallback)
                    .formattedText(fallback)
                    .executedAt(LocalDateTime.now())
                    .build();
            sendSseEvent(emitter, "result", fallbackResult);
            sendSseEvent(emitter, "complete", Map.of(
                    "status", "LLM_ERROR",
                    "fullContent", fallback,
                    "totalLatencyMs", System.currentTimeMillis() - startTime
            ));
            emitter.complete();
        }
    }

    /** The enabled pilot is terminal: errors cannot fall through to another provider. */
    private void streamF006MiniQuestion(SseEmitter emitter, String factoryId, Long userId,
                                        String userRole, String sessionId, String userInput,
                                        boolean thinking, long startTime,
                                        com.cretas.aims.ai.client.F006BossQuestionClient.Cancellation cancellation) throws IOException {
        try {
            var answer = f006BossQuestionClient.stream(factoryId, userId, userRole, userInput, thinking,
                    metadata -> {
                        try {
                            sendSseEvent(emitter, "meta", Map.of(
                                    "provider", metadata.provider(), "model", metadata.model(),
                                    "thinking", metadata.thinking(), "correlationId", metadata.correlationId(),
                                    "questionType", QuestionType.GENERAL_QUESTION.name()));
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }, token -> {
                        try {
                            sendSseEvent(emitter, "token", token);
                        } catch (IOException e) {
                            throw new java.io.UncheckedIOException(e);
                        }
                    }, cancellation);
            Map<String, Object> source = new LinkedHashMap<>();
            source.put("provider", answer.provider());
            source.put("model", answer.model());
            source.put("correlationId", answer.correlationId());
            source.put("usageAvailable", answer.usage() != null);
            source.put("usage", answer.usage());
            sendSseEvent(emitter, "result", IntentExecuteResponse.builder()
                    .intentRecognized(false).status("COMPLETED").sessionId(sessionId)
                    .message(answer.content()).formattedText(answer.content())
                    .resultData(source).executedAt(LocalDateTime.now()).build());
            Map<String, Object> complete = new LinkedHashMap<>(source);
            complete.put("status", "SUCCESS");
            complete.put("sessionId", sessionId);
            complete.put("fullContent", answer.content());
            complete.put("tokensUsed", answer.usage() == null ? null : answer.usage().totalTokens());
            complete.put("finishReason", answer.finishReason());
            complete.put("totalLatencyMs", System.currentTimeMillis() - startTime);
            sendSseEvent(emitter, "complete", complete);
            emitter.complete();
            log.info("F006 Mini question completed: userId={}, correlationId={}, usageAvailable={}",
                    userId, answer.correlationId(), answer.usage() != null);
        } catch (java.io.UncheckedIOException disconnected) {
            // Throwing from the transport callback closes the upstream response immediately.
            throw disconnected.getCause();
        } catch (IOException unavailable) {
            log.warn("F006 Mini question failed: userId={}, elapsedMs={}",
                    userId, System.currentTimeMillis() - startTime);
            sendSseEvent(emitter, "error", Map.of("status", "FAILED", "message", unavailable.getMessage()));
            emitter.complete();
        }
    }

    // ==================== 工具方法 ====================

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
        return switch (decision.status()) {
            case ALLOW_READ, NOT_APPLICABLE -> null;
            case GUIDANCE -> buildFactoryPackWorkflowGuidance(
                    route, intent, decision.workflowReference());
            case NO_MATCH -> buildFactoryPackNoMatch(route, decision.reason());
        };
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
                .message(message)
                .formattedText(message)
                .requiresApproval(reference.approvalRequired())
                .suggestedActions(List.of(action))
                .metadata(Map.copyOf(metadata))
                .executedAt(LocalDateTime.now())
                .build();
    }

    private void streamTerminalResponse(
            SseEmitter emitter, IntentExecuteResponse response, long startTime) throws IOException {
        sendSseEvent(emitter, "result", response);
        sendSseEvent(emitter, "complete", Map.of(
                "status", response.getStatus() != null ? response.getStatus() : "COMPLETED",
                "cacheHit", false,
                "totalLatencyMs", System.currentTimeMillis() - startTime));
        emitter.complete();
    }

    void sendSseEvent(SseEmitter emitter, String eventName, Object data) throws IOException {
        try {
            String json = objectMapper.writeValueAsString(data);
            emitter.send(SseEmitter.event().name(eventName).data(json));
        } catch (JsonProcessingException e) {
            log.warn("序列化 SSE 事件数据失败: {}", e.getMessage());
            throw new IOException("Failed to serialize SSE event data", e);
        }
    }

    private IntentExecuteResponse buildValidationFailureResponse(LlmSchemaValidationException e) {
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("VALIDATION_FAILED")
                .message("AI 无法准确理解您的意图，请重新描述或从常用操作中选择。")
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Select the streamed no-match message. Priority: conversationMessage → clarificationQuestion → generic.
     * W1b: a negation VETO_READ (e.g. "不用查库存了"/"别给我看订单") produces a REJECTED result
     * (bestMatch=null → hasMatch()=false reaches the no-match branch) that carries a clarificationQuestion.
     * Surface it so the streaming chat reply is the negation clarification, NOT the generic "我没有理解您的意图".
     */
    static String selectNoMatchStreamMessage(IntentMatchResult matchResult) {
        if (matchResult.getConversationMessage() != null && !matchResult.getConversationMessage().isEmpty()) {
            return matchResult.getConversationMessage();
        }
        if (matchResult.getClarificationQuestion() != null && !matchResult.getClarificationQuestion().isEmpty()) {
            return matchResult.getClarificationQuestion();
        }
        return "我没有理解您的意图，请更详细地描述您的需求。";
    }

    /**
     * Returns the SSE complete-event status string for a no-match result.
     * W1b: when the no-match carries a clarificationQuestion (negation VETO_READ), the complete
     * status must be NEED_CLARIFICATION to match the result event — not the generic NO_MATCH.
     * Mirrors the selectNoMatchStreamMessage priority: conversationMessage → clarificationQuestion → generic.
     */
    static String noMatchCompleteStatus(IntentMatchResult matchResult) {
        boolean isNegationClarification = matchResult.getClarificationQuestion() != null
                && !matchResult.getClarificationQuestion().isEmpty();
        return isNegationClarification ? "NEED_CLARIFICATION" : "NO_MATCH";
    }

    private IntentExecuteResponse buildNoMatchResponseForStream(IntentMatchResult matchResult, String factoryId) {
        String message = selectNoMatchStreamMessage(matchResult);
        return IntentExecuteResponse.builder()
                .intentRecognized(false)
                .status("NEED_CLARIFICATION")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private IntentExecuteResponse buildClarificationResponseForStream(
            IntentMatchResult matchResult, String factoryId, Long userId, String userRole, String mode) {
        AIIntentConfig matchedIntent = matchResult.getBestMatch();
        List<IntentExecuteResponse.SuggestedAction> candidateActions =
                clarificationCandidateService == null
                        ? List.of(IntentExecuteResponse.SuggestedAction.builder()
                                .actionCode("REPHRASE").actionName("重新描述").build())
                        : clarificationCandidateService.buildCandidateActions(
                                matchResult, factoryId, userId, userRole, mode);
        String clarificationMessage = buildSafeClarificationMessage(candidateActions);
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(matchedIntent.getIntentCode())
                .intentName(matchedIntent.getIntentName())
                .intentCategory(matchedIntent.getIntentCategory())
                .status("NEED_CLARIFICATION")
                .message(clarificationMessage)
                .formattedText(clarificationMessage)
                .confidence(matchResult.getConfidence())
                .suggestedActions(candidateActions)
                .executedAt(LocalDateTime.now())
                .build();
    }

    private String buildSafeClarificationMessage(List<IntentExecuteResponse.SuggestedAction> actions) {
        String choices = streamChoicesLine(actions);
        if (choices.isBlank()) {
            return "当前没有可安全提供的业务选项，请重新描述您的需求。";
        }
        // 只剩一个选项时不说「可能匹配多个操作」—— 2026-10-06 真机: 一个按钮配「请选择」读起来像坏了。
        List<IntentExecuteResponse.SuggestedAction> intentChoices = actions.stream()
                .filter(x -> x != null && "SELECT_INTENT".equals(x.getActionCode())
                        && x.getActionName() != null && !x.getActionName().isBlank())
                .toList();
        if (intentChoices.size() == 1) {
            return "您是要「" + intentChoices.get(0).getActionName() + "」吗？点下面确认；不是的话请换个说法。";
        }
        return "您的请求可能匹配多个操作, 请选择您实际想做的:" + choices;
    }

    /** 一线角色 + 写操作 + 本厂未开放 ⇒ 拒绝说明; 否则 null。与执行入口 (ToolDispatchService) 同一判定。 */
    private String frontlineWriteDenied(String factoryId, String userRole, AIIntentConfig intent) {
        if (frontlineWriteGrants == null || intent == null) {
            return null;
        }
        ToolExecutor tool = intent.getToolName() == null || intent.getToolName().isBlank()
                ? null : toolRegistry.getExecutor(intent.getToolName()).orElse(null);
        return frontlineWriteGrants.denyReason(factoryId, userRole, tool, intent).orElse(null);
    }

    private String streamChoicesLine(List<IntentExecuteResponse.SuggestedAction> actions) {
        if (actions == null || actions.isEmpty()) {
            return "";
        }
        StringBuilder line = new StringBuilder("\n");
        int index = 1;
        for (IntentExecuteResponse.SuggestedAction action : actions) {
            if (action == null || !"SELECT_INTENT".equals(action.getActionCode())
                    || action.getActionName() == null || action.getActionName().isBlank()) {
                continue;
            }
            line.append(index++).append(". ").append(action.getActionName()).append("\n");
            if (index > 3) {
                break;
            }
        }
        return index == 1 ? "" : line.toString();
    }

    /** R16: SSE no-tool 出口的餐饮 tiered 兜底 — 未命中回落原死胡同提示。 */
    private IntentExecuteResponse noToolResponseWithRestaurantFallback(
            AIIntentConfig intent, String factoryId, IntentExecuteRequest request) {
        IntentExecuteResponse delegated = tryRestaurantTieredDelegate(
                factoryId, request != null ? request.getUserInput() : null, request, "sse_no_tool");
        if (delegated != null) {
            log.info("[SSE][Branch:TieredDelegate] no-tool 出口被 tiered 路由接管: intentCode={}",
                    intent != null ? intent.getIntentCode() : null);
            return delegated;
        }
        return toolDispatchService.buildNoToolResponse(intent);
    }

    /**
     * Card4 (2026-07-28): shared restaurant tiered-delegate call, used by both the new
     * top-of-stream tiered-first gate ("sse_tiered_first") and the pre-existing R16 no-tool
     * dead-end fallback ("sse_no_tool"). Returns null on no-match, unavailable delegate or
     * delegate error — callers decide what "no answer" means for their branch.
     */
    private IntentExecuteResponse tryRestaurantTieredDelegate(
            String factoryId, String userInput, IntentExecuteRequest request, String origin) {
        if (sseTieredIntentDelegate == null
                || !isRestaurantTenantId(factoryId, resolveFactoryDomainForTenantCheck(factoryId))
                || userInput == null) {
            return null;
        }
        try {
            Map<String, Object> delegateParams = new java.util.HashMap<>();
            delegateParams.put("userInput", userInput);
            Map<String, Object> delegateContext = new java.util.HashMap<>();
            delegateContext.put("request", request);
            Map<String, Object> delegated = sseTieredIntentDelegate.tryDelegate(
                    factoryId, delegateParams, delegateContext, origin);
            if (delegated == null || delegated.get("message") == null) {
                return null;
            }
            String delegatedMessage = delegated.get("message").toString();
            Map<String, Object> delegatedData = new java.util.HashMap<>();
            delegatedData.put("charts", delegated.getOrDefault("charts", java.util.List.of()));
            delegatedData.put("kpis", delegated.getOrDefault("kpis", java.util.List.of()));
            delegatedData.put("source", "restaurant_ops_gold");
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(delegated.get("code") != null ? delegated.get("code").toString() : null)
                    .status("SUCCESS")
                    .message(delegatedMessage)
                    .formattedText(delegatedMessage)
                    .resultData(delegatedData)
                    .executedAt(java.time.LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.warn("[SSE][Branch:TieredDelegate] delegate 失败 (origin={}): {}", origin, e.getMessage());
            return null;
        }
    }

    /**
     * 写动词判成写 → 语义权威被跳过, 而 Java 解析出来的是个【读】意图 —— 两个判据矛盾。
     * 这条路上没有 Python 的 request_coverage 契约, 继续执行等于猜用户问的是什么
     * (2026-08-11 实测: 「要不要下架」被猜成慢销榜, 用销量答毛利问题)。fail closed。
     *
     * <p>判定与话术都取自 {@link IntentExecutionOrchestrator} —— 同一句话在流式与非流式
     * 入口必须走出同一条路, 这正是 card4 立下「never copy this logic again」的原因。
     *
     * @return true 表示已经把澄清响应流出去了, 调用方必须就地 return
     */
    private boolean streamRestaurantWriteReadAmbiguity(SseEmitter emitter,
                                                       boolean writeVerbSuppressedPlan,
                                                       AIIntentConfig intent,
                                                       IntentExecuteRequest request,
                                                       long startTime) throws IOException {
        if (!writeVerbSuppressedPlan || intent == null
                || IntentExecutionOrchestrator.isWriteIntentDeclarationAware(
                        intent, intentAccessModeFilter, writeGuardService)) {
            return false;
        }
        log.warn("[SSE][Branch:RestaurantWriteReadAmbiguity] 写判定与读意图矛盾, fail closed: "
                        + "candidateIntent={}, input='{}'",
                intent.getIntentCode(), request != null ? request.getUserInput() : null);
        String message = IntentExecutionOrchestrator.RESTAURANT_WRITE_READ_AMBIGUITY_MESSAGE;
        streamTerminalResponse(emitter, IntentExecuteResponse.builder()
                .intentRecognized(true)
                .status("NEED_CLARIFICATION")
                .message(message)
                .formattedText(message)
                .sessionId(request != null ? request.getSessionId() : null)
                .executedAt(LocalDateTime.now())
                .build(), startTime);
        return true;
    }

    /**
     * Card4 (2026-07-28): fail-closed response for the tiered-first gate, mirrors
     * IntentExecutionOrchestrator's buildRestaurantDeterministicResponse(request,
     * "NEED_CLARIFICATION", true, ...) call at execute() :395-399.
     */
    private IntentExecuteResponse buildRestaurantTieredFirstFailClosedResponse(IntentExecuteRequest request) {
        String message = "餐饮语义规划暂时不可用，本次没有执行任何分析。请稍后重试。";
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .status("NEED_CLARIFICATION")
                .message(message)
                .formattedText(message)
                .sessionId(request != null ? request.getSessionId() : null)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Task-1 (2026-08-05, restaurant-tenant-consolidation): now domain-aware, matching
     * {@link IntentExecutionOrchestrator#isRestaurantTenantId} /
     * {@link DynamicToolSelectionService#isRestaurantTenantId} /
     * {@code AIIntentConfigController#isRestaurantTenantId}. Domain is authoritative;
     * the RES_/REST_/DEMO_REST id-prefix check is only the fallback for when domain
     * resolution is unavailable or fails.
     *
     * <p>Card4 (2026-07-28) history: this used to be an ID-only check ("no factory-domain
     * DB lookup is wired into SseStreamingService") and its Javadoc explicitly warned "a
     * factory whose id doesn't match RES_/REST_/DEMO_REST but whose domain IS resolved as
     * RESTAURANT would gate tiered-first in /execute but NOT here — do not assume the two
     * checks always agree." MOCK_REST is exactly that factory; task-1 closes the gap by
     * resolving domain via {@link BusinessTypeGate#resolveDomain} (already-injected
     * dependency, reused instead of wiring a new one) at the call sites and passing it in.
     */
    static boolean isRestaurantTenantId(String factoryId, String factoryDomain) {
        if ("RESTAURANT".equalsIgnoreCase(factoryDomain)) {
            return true;
        }
        if (factoryId == null || factoryId.isBlank()) {
            return false;
        }
        String normalized = factoryId.trim().toUpperCase(java.util.Locale.ROOT);
        return "DEMO_REST".equals(normalized)
                || normalized.startsWith("RES_")
                || normalized.startsWith("REST_");
    }

    /**
     * Fail-soft domain lookup for {@link #isRestaurantTenantId}: {@code businessTypeGate} is
     * null in older unit tests built via {@code new SseStreamingService(...)} that never call
     * {@code ReflectionTestUtils.setField(service, "businessTypeGate", ...)} — those keep the
     * pre-task-1 ID-prefix-only behavior by getting a null domain here.
     */
    private String resolveFactoryDomainForTenantCheck(String factoryId) {
        return businessTypeGate == null ? null : businessTypeGate.resolveDomain(factoryId);
    }
}
