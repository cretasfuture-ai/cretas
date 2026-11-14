package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.Tool;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.LegacyToolMigrationRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dev.faultinjection.ToolExecutionFaultInjector;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.PreprocessedQuery;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.calibration.CorrectionRecord;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.util.ErrorSanitizer;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tool 调度服务
 *
 * 负责直接 Tool 执行、Tool 预览、参数提取（LLM + 规则学习）、
 * 冗余检查、重试 + CRITIC 纠错循环。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolDispatchService {
    @Autowired
    private com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService controlledPurchase;

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;

    /** 参数抽取发给模型前脱敏; 单测直接 new 本类时用默认实例。 */
    @Autowired
    private com.cretas.aims.service.consent.LlmEgressGuard llmEgressGuard =
            com.cretas.aims.service.consent.LlmEgressGuard.standalone();

    static final String PARAM_EGRESS_SITE = "tool-param-extraction";
    private final ToolCallRedundancyService redundancyService;
    private final SelfCorrectionService selfCorrectionService;
    private final CorrectionAgentService correctionAgentService;
    private final ExternalVerifierService externalVerifierService;
    private final ToolResultValidatorService toolResultValidatorService;
    private final ParameterExtractionLearningService parameterExtractionLearningService;

    @Autowired(required = false)
    private PreviewTokenService previewTokenService;

    /** 预览追问开参数收集会话用 (见 {@link #openPreviewQuestionSession}); 没有它时预览追问照旧、只是没人接答案。 */
    @Autowired(required = false)
    private ConversationService conversationService;

    /**
     * 短语直达 / 显式意图分支 (matchResult == null) 没经过识别管线的预处理, 「这个批次」「上个月」
     * 到不了工具。这里补跑同样的<b>规则</b>步骤 (时间归一 + 会话指代), 不调大模型。见 {@link #shortcutPreprocess}。
     */
    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.cretas.aims.service.QueryPreprocessorService queryPreprocessorService;

    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.cretas.aims.service.ConversationMemoryService conversationMemoryService;

    // W0 write-guard (intent-w0) — SITE B. This is the direct-Tool execution choke point reached
    // by the main execute() ToolDirect branch AND by executeWithExplicitIntent. The guard blocks a
    // write tool (polymorphic getActionType() WRITE/UPDATE/DELETE) unless previewOnly or confirmed.
    // It is NOT conditioned on forceExecute (defense-in-depth even though Site A also guards the
    // explicit path) — a misroute to a write tool cannot silently execute here.
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 (AI-RBAC 系统性收口) — SITE B: central RBAC enforce for sensitive write tools whose
    // controllers carry @RequirePermission. The legacy tool.requiresPermission()/hasPermission() check
    // below only covers the ~47 tools that override those (default false → vast majority skipped),
    // so customer_delete / order_delete / finance_invoice_approve / transfer_approve / user_* etc.
    // had ZERO AI-path RBAC. This enforcer uses the same PermissionService matrix as HTTP (fail-closed).
    @Autowired
    private com.cretas.aims.ai.tool.ToolRbacEnforcer toolRbacEnforcer;

    // 一线角色 AI 写操作开放名单 (owner 2026-10-06: 先收窄, 管理端可开放)。执行与预览两个入口都问它,
    // 未开放的写操作连确认卡都不出。required=true: 生产上由 Spring 保证接上; 只有裸构造的单测里为 null。
    @Autowired
    private com.cretas.aims.service.intent.FrontlineAiWriteGrantService frontlineWriteGrants;

    /**
     * F5 fault-injection hook (optional). Bean exists only under
     * {@code dev-fault-injection} Spring profile; remains {@code null} in prod.
     * When active, {@link #executeWithTool} throws RuntimeException for tool
     * names listed in {@code MOCK_TOOL_THROW} env var (comma-separated).
     */
    @Autowired(required = false)
    private ToolExecutionFaultInjector toolExecutionFaultInjector;

    @Autowired
    private ToolExecutionGateway toolExecutionGateway;

    @Autowired
    private com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry toolRuntimeRegistry;

    @Autowired
    private LegacyToolMigrationRegistry legacyToolMigrationRegistry;

    @Autowired
    private AuthenticatedToolPrincipalFactory authenticatedToolPrincipalFactory;

    @Value("${cretas.ai.tool-gateway.intent-dispatch-migration.enabled:false}")
    private boolean intentDispatchGatewayMigrationEnabled;

    // ==================== 公开方法 ====================

    /**
     * 使用 Tool 执行意图
     */
    public IntentExecuteResponse executeWithTool(ToolExecutor tool, String factoryId,
                                                  IntentExecuteRequest request,
                                                  AIIntentConfig intent,
                                                  Long userId, String userRole,
                                                  IntentMatchResult matchResult) {
        // 深层外发 (抽参、纠错、选工具、消歧…) 不带工厂参数: 按本次路径上认定的工厂脱敏 —— 平台账号替某厂
        // 操作时也按那家工厂的保密产品名换代号 (Steve 2026-10-29, LlmEgressGuard.analysing)
        try (var egressFactory = com.cretas.aims.service.consent.LlmEgressGuard.analysing(factoryId)) {
            if (request != null) {
                request.setReadTurnSnapshot(null);
                request.setReadTurnNotices(null);
            }
            IntentExecuteResponse response = executeWithToolInScope(tool, factoryId, request, intent, userId, userRole, matchResult);
            attachReadQuery(request, response);
            attachReadNotices(request, response);
            return response;
        }
    }

    // ==================== INTENT-DISTILL D1: 只读参数来源与分页 ====================

    /**
     * 本轮来源: 请求 context、本轮原话的状态 / 分页、(上一步已放好的) 本轮原话时间。原话要分页而工具不支持分页时,
     * 直接返回明确的拒绝, 不执行。原话点名了工具不支持的口径时按支持的口径执行, 回复开头说明 (Steve 口径 6)。
     */
    private IntentExecuteResponse prepareReadTurn(ToolExecutor tool, AIIntentConfig intent,
                                                  IntentExecuteRequest request, String userInputToUse,
                                                  Map<String, Object> params,
                                                  Map<String, ReadTurnParameters.Source> sources,
                                                  java.util.Set<String> doNotInherit) {
        Map<String, Object> schema = tool.getParametersSchema();
        Map<?, ?> properties = ReadTurnParameters.properties(schema);
        boolean replay = request.getContext() != null
                && Boolean.TRUE.equals(request.getContext().get(ReadPaginationSupport.REPLAY_KEY));
        if (replay) {
            // 服务端翻页重放: 条件全部来自上一轮已执行的参数, 页码由服务端算出; 不再从旧原话里重新读页码
            String pageKey = ReadTurnParameters.pageKey(properties);
            properties.keySet().forEach(k -> {
                if (k instanceof String key && params.get(key) != null) {
                    sources.put(key, key.equals(pageKey) ? ReadTurnParameters.Source.SERVER_PAGE
                            : ReadTurnParameters.Source.INHERITED);
                }
            });
            return null;
        }
        if (request.getContext() != null) {
            request.getContext().keySet().forEach(key -> {
                if (properties.containsKey(key) && params.get(key) != null) {
                    sources.put(key, ReadTurnParameters.Source.CURRENT_TURN);
                }
            });
        }
        if (params.get(com.cretas.aims.ai.tool.TimeRangeFilter.SAID) != null) {
            for (String key : List.of("startDate", "endDate")) {
                if (properties.containsKey(key) && params.get(key) != null) {
                    sources.put(key, ReadTurnParameters.Source.CURRENT_TURN);
                }
            }
        }

        String capability = intent != null && intent.getIntentName() != null ? intent.getIntentName() : tool.getToolName();
        Optional<com.cretas.aims.ai.tool.UnsupportedOptionGuard.Substitution> unsupported =
                com.cretas.aims.ai.tool.UnsupportedOptionGuard.check(schema, request.getUserInput());
        if (unsupported.isPresent()) {
            // Steve 2026-11-12 口径 6: 说明「暂时只能按金额排」, 同时给出按金额的结果 (不再整轮拒绝)
            com.cretas.aims.ai.tool.UnsupportedOptionGuard.Substitution sub = unsupported.get();
            params.put(sub.parameter(), sub.appliedValue());
            sources.put(sub.parameter(), ReadTurnParameters.Source.CURRENT_TURN);
            Map<String, Object> fields = new java.util.LinkedHashMap<>();
            fields.put("parameter", sub.parameter());
            fields.put("requested", sub.requestedValue());
            fields.put("requestedLabel", sub.requestedLabel());
            fields.put("applied", sub.appliedValue());
            fields.put("appliedLabel", sub.appliedLabel());
            addReadNotice(request, com.cretas.aims.ai.tool.ReadReplyNotices.notice(
                    com.cretas.aims.ai.tool.ReadReplyNotices.UNSUPPORTED_OPTION, sub.notice(capability), fields));
        }

        Map<String, Object> statusProperty = com.cretas.aims.ai.tool.StatusFilter.statusProperty(schema);
        if (statusProperty != null && params.get("status") == null) {
            try {
                com.cretas.aims.ai.tool.StatusFilter.fromSentence(statusProperty, userInputToUse)
                        .ifPresent(code -> {
                            params.put("status", code);
                            sources.put("status", ReadTurnParameters.Source.CURRENT_TURN);
                        });
            } catch (RuntimeException unrecognizedStatus) {
                // 说了状态却认不出: 不拿上一轮的状态顶替, 交给工具按原话报同一个错
                doNotInherit.add("status");
            }
        }

        ReadTurnParameters.PageWords words = ReadTurnParameters.pageWords(request.getUserInput());
        if (words.mentioned()) {
            String pageKey = ReadTurnParameters.pageKey(properties);
            String sizeKey = ReadTurnParameters.sizeKey(properties);
            if ((words.page() != null && pageKey == null) || (words.size() != null && sizeKey == null)) {
                return readTurnRefusal(intent, "PAGINATION_REFUSED", "「" + capability + "」不支持"
                        + (words.page() != null ? "按页码查看" : "指定每页条数") + "；未执行查询。");
            }
            String outOfRange = words.page() == null ? null : boundViolation(properties.get(pageKey), words.page(), "页码");
            if (outOfRange == null && words.size() != null) {
                outOfRange = boundViolation(properties.get(sizeKey), words.size(), "每页条数");
            }
            if (outOfRange != null) return readTurnRefusal(intent, "PAGINATION_REFUSED", outOfRange + "；未执行查询。");
            if (words.page() != null) {
                params.put(pageKey, words.page());
                sources.put(pageKey, ReadTurnParameters.Source.CURRENT_TURN);
            }
            if (words.size() != null) {
                params.put(sizeKey, words.size());
                sources.put(sizeKey, ReadTurnParameters.Source.CURRENT_TURN);
            }
        }
        return null;
    }

    /** 模型 / 学习规则不写页码与每页条数 (程序按本轮原话或服务端翻页绑定); 其余抽到的键记为本轮来源。 */
    private static Map<String, Object> withoutModelPaging(Map<String, Object> extracted,
                                                          Map<String, ReadTurnParameters.Source> sources) {
        if (extracted == null || extracted.isEmpty()) return extracted == null ? new HashMap<>() : extracted;
        Map<String, Object> kept = new HashMap<>();
        extracted.forEach((key, value) -> {
            if (key == null || ReadTurnParameters.isPagingKey(key)) return;
            kept.put(key, value);
            sources.putIfAbsent(key, ReadTurnParameters.Source.CURRENT_TURN);
        });
        return kept;
    }

    private void completeReadTurn(ToolExecutor tool, AIIntentConfig intent, String factoryId, Long userId,
                                  IntentExecuteRequest request, Map<String, Object> params,
                                  Map<String, ReadTurnParameters.Source> sources, java.util.Set<String> doNotInherit) {
        Map<?, ?> properties = ReadTurnParameters.properties(tool.getParametersSchema());
        if (properties.isEmpty() || intent == null) return;
        boolean replay = request.getContext() != null
                && Boolean.TRUE.equals(request.getContext().get(ReadPaginationSupport.REPLAY_KEY));
        if (!replay && conversationMemoryService != null && request.getSessionId() != null
                && !request.getSessionId().isBlank() && userId != null) {
            try {
                Optional<ReadTurnParameters.Snapshot> previous = ReadTurnParameters.latest(
                        conversationMemoryService.getContext(request.getSessionId()), factoryId, userId,
                        LocalDateTime.now());
                java.util.Set<String> inherited = ReadTurnParameters.inherit(params, sources, properties,
                        previous.orElse(null), intent.getIntentCode(), tool.getToolName(), doNotInherit);
                if (inherited.contains("startDate") && inherited.contains("endDate")) {
                    params.put(com.cretas.aims.ai.tool.TimeRangeFilter.SAID,
                            params.get("startDate") + "~" + params.get("endDate"));
                }
                if (!inherited.isEmpty()) {
                    log.info("只读续句沿用同一目标的已确认条件: intent={}, keys={}", intent.getIntentCode(), inherited);
                }
            } catch (RuntimeException memoryUnavailable) {
                // 读不到会话记忆 = 不沿用 (按本轮原话执行), 不阻断查询
                log.warn("只读续句读取会话记忆失败, 不沿用旧条件: {}", memoryUnavailable.getClass().getSimpleName());
            }
        }
        // 模型抽参不许把被替换的口径改回做不到的那个 (工具会直接报错, 用户拿不到最接近的结果)
        if (request.getReadTurnNotices() != null) {
            for (Map<String, Object> notice : request.getReadTurnNotices()) {
                if (com.cretas.aims.ai.tool.ReadReplyNotices.UNSUPPORTED_OPTION.equals(notice.get("kind"))
                        && notice.get("parameter") instanceof String key) {
                    params.put(key, notice.get("applied"));
                }
            }
        }
        if (!replay) {
            applyDefaultRecentDays(tool.getParametersSchema(), properties, request, params, sources);
            applyDefaultPageSize(properties, params, sources);
        }
        ReadTurnParameters.markDefaults(params, sources, properties);
        request.setReadTurnSnapshot(ReadTurnParameters.snapshot(
                intent.getIntentCode(), tool.getToolName(), request.getUserInput(), params, sources, properties));
    }

    /** Steve 口径 7: 只读列表默认每页 20 条 (本轮没说、也没沿用时; 不超过工具声明的上限)。 */
    static final int DEFAULT_READ_PAGE_SIZE = 20;

    private static void applyDefaultPageSize(Map<?, ?> properties, Map<String, Object> params,
                                             Map<String, ReadTurnParameters.Source> sources) {
        String sizeKey = ReadTurnParameters.sizeKey(properties);
        if (sizeKey == null || params.get(sizeKey) != null) return;
        int size = DEFAULT_READ_PAGE_SIZE;
        if (properties.get(sizeKey) instanceof Map<?, ?> field && field.get("maximum") instanceof Number max) {
            size = (int) Math.min(size, max.longValue());
        }
        params.put(sizeKey, size);
        sources.put(sizeKey, ReadTurnParameters.Source.DEFAULT);
    }

    /**
     * Steve 口径 1: 没说时间 → 按工具声明的默认最近 N 天查 ({@link com.cretas.aims.ai.tool.TimeRangeFilter#DEFAULT_RECENT_DAYS}),
     * 回复开头写明。只对声明了这个默认、且真的按 startDate/endDate 过滤的工具生效。
     */
    private static void applyDefaultRecentDays(Map<String, Object> schema, Map<?, ?> properties,
                                               IntentExecuteRequest request, Map<String, Object> params,
                                               Map<String, ReadTurnParameters.Source> sources) {
        Integer days = com.cretas.aims.ai.tool.TimeRangeFilter.defaultRecentDays(schema);
        if (days == null || !properties.containsKey("startDate") || !properties.containsKey("endDate")
                || !blank(params.get("startDate")) || !blank(params.get("endDate"))) {
            return;
        }
        java.time.LocalDate to = java.time.LocalDate.now();
        java.time.LocalDate from = to.minusDays(days - 1L);
        params.put("startDate", from.toString());
        params.put("endDate", to.toString());
        // 与原话时间同一个标记: 结果按这段时间标 APPLIED, 0 条时回复点名时间
        params.put(com.cretas.aims.ai.tool.TimeRangeFilter.SAID, from + "~" + to);
        sources.put("startDate", ReadTurnParameters.Source.DEFAULT);
        sources.put("endDate", ReadTurnParameters.Source.DEFAULT);
        addReadNotice(request, com.cretas.aims.ai.tool.ReadReplyNotices.notice(
                com.cretas.aims.ai.tool.ReadReplyNotices.DEFAULT_TIME_RANGE,
                "没说时间，按最近 " + days + " 天（" + from + " 至 " + to + "）查询。",
                Map.of("days", days, "from", from.toString(), "to", to.toString())));
    }

    private static boolean blank(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    private static void addReadNotice(IntentExecuteRequest request, Map<String, Object> notice) {
        if (request.getReadTurnNotices() == null) request.setReadTurnNotices(new java.util.ArrayList<>());
        request.getReadTurnNotices().add(notice);
    }

    /**
     * 成功的只读执行: 本轮用的默认 / 替换的口径 / 多于一页时的总条数, 进 metadata.readNotices 并加在回复开头。
     */
    private static void attachReadNotices(IntentExecuteRequest request, IntentExecuteResponse response) {
        if (request == null || response == null || request.getReadTurnSnapshot() == null
                || !("SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus()))) {
            return;
        }
        List<Map<String, Object>> notices = new java.util.ArrayList<>();
        if (request.getReadTurnNotices() != null) notices.addAll(request.getReadTurnNotices());
        Map<String, Object> paged = pagedNotice(request.getReadTurnSnapshot(), response.getResultData());
        if (paged != null) notices.add(paged);
        if (notices.isEmpty()) return;
        Map<String, Object> metadata = response.getMetadata() == null
                ? new HashMap<>() : new HashMap<>(response.getMetadata());
        metadata.put(com.cretas.aims.ai.tool.ReadReplyNotices.METADATA_KEY, notices);
        response.setMetadata(metadata);
        List<String> texts = com.cretas.aims.ai.tool.ReadReplyNotices.texts(metadata);
        response.setMessage(com.cretas.aims.ai.tool.ReadReplyNotices.prefix(response.getMessage(), texts));
        response.setFormattedText(com.cretas.aims.ai.tool.ReadReplyNotices.prefix(response.getFormattedText(), texts));
    }

    /** Steve 口径 7: 结果多于一页时写明总条数与本页范围; 工具结果里拿不到总数时不写 (不猜)。 */
    private static Map<String, Object> pagedNotice(Map<String, Object> snapshot, Object resultData) {
        Object inner = com.cretas.aims.ai.tool.TimeRangeFilter.toolResultOf(resultData);
        if (inner instanceof Map<?, ?> map && !hasTotal(map) && map.get("data") instanceof Map<?, ?> nested) {
            inner = nested;
        }
        if (!(inner instanceof Map<?, ?> data) || !(snapshot.get("params") instanceof Map<?, ?> params)) return null;
        Long total = null;
        for (String key : List.of("total", "totalElements", "totalCount")) {
            if (data.get(key) instanceof Number n) {
                total = n.longValue();
                break;
            }
        }
        Integer size = null;
        int page = 1;
        for (Map.Entry<?, ?> e : params.entrySet()) {
            if (!(e.getKey() instanceof String key) || !(e.getValue() instanceof Number n)) continue;
            if (!ReadTurnParameters.isPagingKey(key)) continue;
            if ("size".equals(key) || "pageSize".equals(key)) size = n.intValue();
            else page = Math.max(1, n.intValue());
        }
        if (total == null || size == null || size <= 0 || total <= (long) page * size) return null;
        long first = (long) (page - 1) * size + 1;
        long last = Math.min(total, (long) page * size);
        return com.cretas.aims.ai.tool.ReadReplyNotices.notice(
                com.cretas.aims.ai.tool.ReadReplyNotices.PAGED_RESULT,
                "共 " + total + " 条，这里是第 " + first + "–" + last + " 条（每页 " + size
                        + " 条）；可以说「下一页」，或加上时间、状态缩小范围。",
                Map.of("total", total, "page", page, "size", size));
    }

    private static boolean hasTotal(Map<?, ?> map) {
        return map.containsKey("total") || map.containsKey("totalElements") || map.containsKey("totalCount");
    }

    private static String boundViolation(Object definition, int value, String label) {
        if (!(definition instanceof Map<?, ?> field)) return null;
        if (field.get("minimum") instanceof Number min && value < min.longValue()) {
            return label + "不能小于 " + min.longValue();
        }
        if (field.get("maximum") instanceof Number max && value > max.longValue()) {
            return label + "最多 " + max.longValue();
        }
        return null;
    }

    private static IntentExecuteResponse readTurnRefusal(AIIntentConfig intent, String status, String message) {
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent != null ? intent.getIntentCode() : null)
                .intentName(intent != null ? intent.getIntentName() : null)
                .intentCategory(intent != null ? intent.getIntentCategory() : null)
                .status(status)
                .aiMode("READ")
                .message(message)
                .formattedText(message)
                .executedAt(LocalDateTime.now())
                .build();
    }

    /** 成功的只读执行: 每个最终参数的值与来源进响应 metadata.readQuery (两个入口据此写会话记忆)。 */
    private static void attachReadQuery(IntentExecuteRequest request, IntentExecuteResponse response) {
        if (request == null || response == null || request.getReadTurnSnapshot() == null
                || !("SUCCESS".equals(response.getStatus()) || "COMPLETED".equals(response.getStatus()))) {
            return;
        }
        Map<String, Object> metadata = response.getMetadata() == null
                ? new HashMap<>() : new HashMap<>(response.getMetadata());
        metadata.put(ReadTurnParameters.METADATA_KEY, request.getReadTurnSnapshot());
        response.setMetadata(metadata);
    }

    private IntentExecuteResponse executeWithToolInScope(ToolExecutor tool, String factoryId,
                                                  IntentExecuteRequest request,
                                                  AIIntentConfig intent,
                                                  Long userId, String userRole,
                                                  IntentMatchResult matchResult) {
        try {
            // Request identity must be checked before parameter extraction or cache lookup.
            // A cached supplier result is subject to the same boundary as a fresh Tool call.
            if ((SupplierComparisonReadIntent.TOOL.equals(tool.getToolName())
                    || (intent != null && SupplierComparisonReadIntent.CODE.equals(intent.getIntentCode())))
                    && (!SupplierComparisonReadIntent.TOOL.equals(tool.getToolName())
                    || intent == null || !SupplierComparisonReadIntent.CODE.equals(intent.getIntentCode())
                    || com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                    .filter(actor -> actor.type() == com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER)
                    .filter(actor -> Objects.equals(actor.id(), userId))
                    .filter(actor -> Objects.equals(actor.factoryId(), factoryId)).isEmpty())) {
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent == null ? null : intent.getIntentCode())
                        .status("FORBIDDEN").aiMode("READ")
                        .message("供应商价格比较需要绑定的工厂用户身份及正式只读工具")
                        .resultData(Map.of("code", "FORBIDDEN", "toolName", tool.getToolName()))
                        .executedAt(LocalDateTime.now()).build();
            }
            // Round 8-β Fix: Canvas factory_tool_configs was a "write-only" table.
            // Factory admins toggled tools on/off in Canvas UI (ToolSkillMatrix.vue) but
            // ToolRegistry.isToolEnabledForFactory() was never called on the execution
            // path — the switch was decorative. Now we check it here so a disabled tool
            // returns a clear error instead of silently running.
            if (toolRegistry != null && !toolRegistry.isToolEnabledForFactory(factoryId, tool.getToolName())) {
                log.warn("Tool disabled for factory: tool={}, factoryId={}", tool.getToolName(), factoryId);
                // Sprint 12: 4-element error UX (≥80-char). Covers both genuine admin-disable
                // and cross-factory cases (e.g. F999 not configured → tool not enabled).
                String disabledMsg = String.format(
                        "「%s」功能当前在工厂 %s 不可用。可能原因: 1. 该功能已被工厂管理员在功能配置中关闭;"
                        + " 2. 当前工厂 (%s) 未开通此模块或工厂编号不存在; 3. 您的角色暂无此功能权限。"
                        + "建议: 联系工厂管理员在「功能配置」中开启, 或确认已切换到正确的工厂。",
                        intent.getIntentName(), factoryId, factoryId);
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("TOOL_DISABLED")
                        .message(disabledMsg)
                        .formattedText(disabledMsg)
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            IntentExecuteResponse frontlineDenied = frontlineWriteDenied(tool, factoryId, intent, userRole);
            if (frontlineDenied != null) {
                return frontlineDenied;
            }

            // previewOnly is a hard no-write contract. If the tool has no explicit safe preview,
            // fail closed here instead of falling through to the normal execute() path.
            if (Boolean.TRUE.equals(request.getPreviewOnly()) && !tool.supportsPreview()) {
                log.warn("Tool preview unsupported; execution blocked: tool={}, intentCode={}",
                        tool.getToolName(), intent != null ? intent.getIntentCode() : null);
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent != null ? intent.getIntentCode() : null)
                        .intentName(intent != null ? intent.getIntentName() : null)
                        .intentCategory(intent != null ? intent.getIntentCategory() : null)
                        .status("PREVIEW_UNSUPPORTED")
                        .message("该工具不支持安全预览，未执行任何操作。")
                        .formattedText("该工具不支持安全预览，未执行任何操作。")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // P1 读写分块 §4.4 (SITE B 兜底): 咨询 tab (mode=READ) 路由到写工具/写意图 → 不执行,
            // 返回跳转提示卡。confirm() 二阶段构造的内部请求不带 mode, 不受影响。
            if ("READ".equalsIgnoreCase(request.getMode())
                    && (writeGuardService.isWriteTool(tool)
                        || (intent != null && writeGuardService.isWriteIntent(intent)))) {
                log.info("READ 模式拦截写工具 (tool-dispatch): tool={}", tool.getToolName());
                String readBlockedMsg = "这是操作类请求，请切换到【操作】页处理。";
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent != null ? intent.getIntentCode() : null)
                        .intentName(intent != null ? intent.getIntentName() : null)
                        .intentCategory(intent != null ? intent.getIntentCategory() : null)
                        .status("READ_MODE_WRITE_BLOCKED")
                        .message(readBlockedMsg)
                        .formattedText(readBlockedMsg)
                        .aiMode("WRITE")
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // W0 write-guard (intent-w0) — SITE B: block a write tool unless safely previewed or confirmed.
            // Runs BEFORE the role-permission check so a misroute to a destructive operation cannot
            // silently execute. NOT conditioned on forceExecute (the multi-intent bypass flag).
            // The bound AIIntentConfig is available here (Site B only) — add a curated-sensitivity
            // backstop so HIGH/CRITICAL write intents (e.g. PROCESSING_WORKER_ASSIGN) are blocked even
            // if the tool-NAME heuristic misses the verb. Sites C/D/E/F lack the bound intent and rely
            // on the expanded WRITE_SUFFIXES list.
            Map<String, Object> wgCtx = TrustedExecutionContext.merge(
                    request.getContext(), factoryId, userId, userRole);
            if ((writeGuardService.isWriteTool(tool)
                    || (intent != null && writeGuardService.isWriteIntent(intent)))
                    && !Boolean.TRUE.equals(request.getPreviewOnly())
                    && !writeGuardService.isConfirmed(wgCtx)) {
                log.info("W0 write-guard (tool-dispatch): blocked write tool {} (confirmed=false)", tool.getToolName());
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent != null ? intent.getIntentCode() : null)
                        .status("WRITE_CONFIRM_REQUIRED")
                        .message("该操作会写入/修改数据，执行前需要确认。")
                        .requiresApproval(true)
                        // P1 读写分块: 确认卡属写路径, 显式标记 aiMode
                        .aiMode("WRITE")
                        .executedAt(java.time.LocalDateTime.now())
                        .build();
            }

            // 1. 权限检查 (legacy 角色串判定)。
            // 2026-07-24 修: 声明了 getRequiredPermissions() 的工具跳过本检查, 交给
            // 下方 W9 enforcer 按权限矩阵判定 — 此前 8 个工具的 hasPermission()
            // 无条件 false (本意是"逼调用方走权限码"), 但本检查先于 enforcer 执行,
            // 导致 3 个原料只读查询对所有角色全拒 (超管也拒), 4 个写工具的
            // confirm 终点被拦死。仅保留给只有 legacy 角色逻辑的旧工具。
            // 规则只有一份, 在 ToolPermissionChain —— Skill 路径也问它 (2026-10-06)。
            if (ToolPermissionChain.legacyRoleDenied(tool, userRole)) {
                log.warn("Tool 权限不足: tool={}, userRole={}", tool.getToolName(), userRole);
                // Sprint 12: 4-element error UX (≥80-char) for permission-denied case.
                String permDeniedMsg = ToolPermissionChain.legacyRoleDeniedMessage(userRole, intent.getIntentName());
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("PERMISSION_DENIED")
                        .message(permDeniedMsg)
                        .formattedText(permDeniedMsg)
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // 1.4. W9 红线 (AI-RBAC) — SITE B: central enforce. Covers sensitive write tools that do NOT
            // override requiresPermission() (the legacy check above misses them). Uses the same
            // PermissionService matrix as controller @RequirePermission (fail-closed). previewOnly is
            // allowed (it previews, not executes).
            if (!Boolean.TRUE.equals(request.getPreviewOnly())) {
                ToolRbacEnforcer.Decision rbac = toolRbacEnforcer.check(tool, wgCtx);
                if (!rbac.isAllowed()) {
                    log.warn("W9 AI-RBAC (tool-dispatch): denied tool={}, requiredAny={}",
                            tool.getToolName(), rbac.getRequiredPermissions());
                    return IntentExecuteResponse.builder()
                            .intentRecognized(true)
                            .intentCode(intent.getIntentCode())
                            .intentName(intent.getIntentName())
                            .intentCategory(intent.getIntentCategory())
                            .status("PERMISSION_DENIED")
                            .message(rbac.getMessage())
                            .formattedText(rbac.getMessage())
                            .executedAt(LocalDateTime.now())
                            .build();
                }
            }

            // The process-local confirmation marker exists only long enough for W0 and RBAC to
            // inspect it. Never serialize or forward it to a Tool; keep the separate legacy
            // business parameter confirmed=true for tools that still require that second check.
            boolean boundConfirmation = writeGuardService.isConfirmed(request.getContext());
            request.setContext(com.cretas.aims.ai.tool.WriteGuardService
                    .withoutServerConfirmationMarker(request.getContext()));

            // 1.5. 预览模式（不支持预览的请求已在上方 fail closed）
            if (Boolean.TRUE.equals(request.getPreviewOnly())) {
                log.info("Tool preview 模式: tool={}, intentCode={}", tool.getToolName(), intent.getIntentCode());
                return executeToolPreview(tool, factoryId, request, intent, userId, userRole);
            }

            // A claimed preview is already the approved command. Do not resolve references or
            // extract new parameters from the synthetic confirmation text after this boundary.
            boolean writeOperation = writeGuardService.isWriteTool(tool)
                    || writeGuardService.isWriteIntent(intent);

            // 2. 构建 ToolCall
            if (com.cretas.aims.service.intent.PurchaseTaskGrantService.CANONICAL_TOOL.equals(tool.getToolName())) {
                return IntentExecuteResponse.builder().status("WRITE_CONFIRM_REQUIRED")
                        .message("采购草稿必须通过预览卡的服务端确认入口执行，未创建采购单")
                        .intentCode(intent.getIntentCode()).aiMode("WRITE").build();
            }
            Map<String, Object> params = TrustedExecutionContext.merge(
                    request.getContext(), factoryId, userId, userRole);

            String userInputToUse = request.getUserInput();
            if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
                PreprocessedQuery pq = matchResult.getPreprocessedQuery();
                if (pq.getFinalQuery() != null && !pq.getFinalQuery().isEmpty()) {
                    userInputToUse = pq.getFinalQuery();
                    log.info("使用预处理后的查询: '{}' -> '{}'", request.getUserInput(), userInputToUse);
                }
            }
            if (!boundConfirmation) {
                params.put("userInput", userInputToUse);
            }
            params.put("intentCode", intent.getIntentCode());

            // 2.5. 从预处理结果中提取解析的引用
            // 识别分支带着管线的预处理结果; 短语直达 / 显式意图分支没有, 补跑规则预处理 (ERP-S6-10 同因):
            // 以前那条分支上「这个批次」「上个月」直接丢了, 同一句话走哪条分支结果不同。
            PreprocessedQuery preprocessed = boundConfirmation ? null
                    : matchResult != null && matchResult.getPreprocessedQuery() != null
                    ? matchResult.getPreprocessedQuery()
                    : shortcutPreprocess(request, factoryId, userId);
            if (!boundConfirmation && preprocessed != null) {
                PreprocessedQuery pq = preprocessed;
                Map<String, PreprocessedQuery.ResolvedReference> refs = pq.getResolvedReferences();
                if (refs != null && !refs.isEmpty()) {
                    for (Map.Entry<String, PreprocessedQuery.ResolvedReference> entry : refs.entrySet()) {
                        PreprocessedQuery.ResolvedReference ref = entry.getValue();
                        if (ref != null && ref.getEntityType() != null) {
                            switch (ref.getEntityType().toUpperCase()) {
                                case "BATCH":
                                    params.put("batchId", ref.getEntityId());
                                    if (ref.getEntityName() != null) {
                                        params.put("batchNumber", ref.getEntityName());
                                    }
                                    log.info("从上下文解析批次: id={}, number={}", ref.getEntityId(), ref.getEntityName());
                                    break;
                                case "SUPPLIER":
                                    params.put("supplierId", ref.getEntityId());
                                    log.info("从上下文解析供应商: {}", ref.getEntityId());
                                    break;
                                case "PRODUCT":
                                    params.put("productId", ref.getEntityId());
                                    log.info("从上下文解析产品: {}", ref.getEntityId());
                                    break;
                                case "STORE":
                                    params.put("store_name", ref.getEntityName());
                                    if (ref.getEntityId() != null) {
                                        params.put("store_id", ref.getEntityId());
                                    }
                                    log.info("从上下文解析门店: id={}, name={}", ref.getEntityId(), ref.getEntityName());
                                    break;
                                case "DISH":
                                    params.put("dish_name", ref.getEntityName());
                                    if (ref.getEntityId() != null) {
                                        params.put("dish_id", ref.getEntityId());
                                    }
                                    log.info("从上下文解析菜品: id={}, name={}", ref.getEntityId(), ref.getEntityName());
                                    break;
                            }
                        }
                    }
                }

                // 2.6. 从预处理结果中提取时间范围
                if (pq.hasTimeRange()) {
                    TimeNormalizationRules.TimeRange timeRange = pq.getPrimaryTimeRange();
                    if (timeRange != null && timeRange.isValid()) {
                        params.put("startDate", timeRange.getStart().toLocalDate().toString());
                        params.put("endDate", timeRange.getEnd().toLocalDate().toString());
                        // 标记这是用户说的时间: 工具不支持按时间筛时, 回复要说出来 (见 TimeRangeFilter)
                        params.put(com.cretas.aims.ai.tool.TimeRangeFilter.SAID,
                                params.get("startDate") + "~" + params.get("endDate"));
                        log.info("从预处理结果提取时间范围: {} ~ {}", timeRange.getStart(), timeRange.getEnd());
                    }
                }
            }

            // 2.65. INTENT-DISTILL D1: 只读查询的参数来源 (本轮 > 同一目标已确认 > 默认) 与程序绑定的分页
            boolean readTurn = !writeOperation && !boundConfirmation
                    && tool.getAccessMode() == ToolExecutor.AccessMode.READ;
            Map<String, ReadTurnParameters.Source> readSources = new java.util.LinkedHashMap<>();
            java.util.Set<String> readDoNotInherit = new java.util.HashSet<>();
            if (readTurn) {
                IntentExecuteResponse refusal = prepareReadTurn(tool, intent, request, userInputToUse, params,
                        readSources, readDoNotInherit);
                if (refusal != null) return refusal;
            }

            // 2.7. 优先使用已学习的规则提取参数
            Map<String, Object> parametersSchema = tool.getParametersSchema();
            @SuppressWarnings("unchecked")
            List<String> requiredParams = parametersSchema != null ?
                    (List<String>) parametersSchema.get("required") : null;

            Map<String, Object> ruleExtractedParams = new HashMap<>();
            if (!boundConfirmation && requiredParams != null && !requiredParams.isEmpty()) {
                List<String> missingParams = requiredParams.stream()
                        .filter(p -> !params.containsKey(p) ||
                                     params.get(p) == null ||
                                     (params.get(p) instanceof String && ((String) params.get(p)).trim().isEmpty()))
                        .collect(Collectors.toList());

                if (!missingParams.isEmpty()) {
                    ruleExtractedParams = parameterExtractionLearningService.extractWithLearnedRules(
                            factoryId, intent.getIntentCode(), userInputToUse, missingParams);
                    if (readTurn) ruleExtractedParams = withoutModelPaging(ruleExtractedParams, readSources);

                    if (!ruleExtractedParams.isEmpty()) {
                        params.putAll(ruleExtractedParams);
                        log.info("使用学习规则提取参数: {} (无需调用 LLM)", ruleExtractedParams.keySet());
                    }
                }
            }

            // 2.8. LLM 提取剩余参数
            Map<String, Object> llmExtractedParams = boundConfirmation ? new HashMap<>()
                    : extractParametersWithLLM(factoryId, userInputToUse, tool, params);
            if (readTurn) llmExtractedParams = withoutModelPaging(llmExtractedParams, readSources);
            if (!llmExtractedParams.isEmpty() && isFollowUpConfirmation(request)) {
                // 追问确认按钮带来的参数是用户在按钮上看见并点了的 (「领用 30 公斤」);
                // 补缺参时 LLM 从原话「再加十公斤」里读出的 10 不许把它改掉。
                llmExtractedParams.keySet().removeIf(key -> params.get(key) != null
                        && !(params.get(key) instanceof String s && s.isBlank()));
            }
            if (!llmExtractedParams.isEmpty()) {
                params.putAll(llmExtractedParams);
                log.info("合并 LLM 提取的参数: {}", llmExtractedParams.keySet());

                // 2.9. 从 LLM 提取结果中学习规则（异步）
                try {
                    parameterExtractionLearningService.learnFromLLMExtraction(
                            factoryId, intent.getIntentCode(), userInputToUse, llmExtractedParams);
                } catch (Exception e) {
                    log.warn("参数提取规则学习失败: {}", e.getMessage());
                }
            }

            // 2.95. INTENT-DISTILL D1: 本轮来源都已就位, 再按同一目标补本轮没说的已确认条件; 默认值只记来源,
            // 由工具在缺参时补 (合并之后补缺, 不会盖掉本轮或沿用的值)
            if (readTurn) {
                completeReadTurn(tool, intent, factoryId, userId, request, params, readSources, readDoNotInherit);
            }

            // Learned rules, LLM extraction and resolved references are all untrusted inputs.
            // Re-bind the authenticated principal immediately before hashing and Tool execution.
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);

            // This approved stock READ tool always needs Gateway identity, row and price scope,
            // including ordinary mobile SSE calls while the broad migration flag is disabled.
            // Other tools retain the independent default-off legacy migration allowlist.
            // A selected Gateway attempt never enters legacy cache/retry on denial or failure.
            boolean governed;
            try {
                governed = toolRuntimeRegistry != null && toolRuntimeRegistry.isGoverned(tool.getToolName());
            } catch (RuntimeException unavailablePolicy) {
                log.error("Tool governance unavailable; refusing legacy fallback: tool={}", tool.getToolName(), unavailablePolicy);
                return IntentExecuteResponse.builder().intentRecognized(true)
                        .intentCode(intent.getIntentCode()).status("DENIED")
                        .message("工具执行策略暂时无法核验，未执行操作，请稍后重试。")
                        .executedAt(LocalDateTime.now()).build();
            }
            if (governed
                    || "material_stock_summary".equals(tool.getToolName())
                    || (intentDispatchGatewayMigrationEnabled
                    && legacyToolMigrationRegistry.contains(tool.getToolName()))) {
                return executeThroughMigrationGateway(
                        tool, factoryId, params, intent, userId, userRole);
            }

            String argumentsJson = objectMapper.writeValueAsString(params);
            ToolCall toolCall = ToolCall.of(
                    java.util.UUID.randomUUID().toString(),
                    tool.getToolName(),
                    argumentsJson
            );

            // 3. 构建执行上下文
            Map<String, Object> context = TrustedExecutionContext.merge(
                    null, factoryId, userId, userRole);
            context.put("intentConfig", intent);
            context.put("request", request);

            // 4. 冗余检查 (ET-Agent 行为校准)
            String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";
            if (!writeOperation && !SupplierComparisonReadIntent.TOOL.equals(tool.getToolName())
                    && redundancyService.isRedundant(sessionId, tool.getToolName(), params)) {
                log.info("检测到冗余调用，跳过执行: tool={}, session={}", tool.getToolName(), sessionId);
                Optional<String> cachedResult = redundancyService.getCachedResult(sessionId, tool.getToolName(), params);
                if (cachedResult.isPresent()) {
                    // Sprint 12: parse cached JSON to extract message + data, don't leak raw JSON as message text.
                    IntentExecuteResponse parsed = parseToolResultToResponse(cachedResult.get(), intent);
                    String parsedMsg = parsed.getMessage() != null ? parsed.getMessage() : "操作已完成。";
                    Map<String, Object> cachedMetadata = new HashMap<>();
                    cachedMetadata.put("cached", true);
                    cachedMetadata.put("toolName", tool.getToolName());
                    putLastAction(cachedMetadata, parsed.getStatus(), parsed.getResultData(),
                            intent, tool.getToolName(), params);
                    return IntentExecuteResponse.builder()
                            .intentRecognized(true)
                            .intentCode(intent.getIntentCode())
                            .intentName(intent.getIntentName())
                            .intentCategory(intent.getIntentCategory())
                            .status(parsed.getStatus())
                            .message(parsedMsg)
                            .resultData(parsed.getResultData())
                            .formattedText(parsed.getFormattedText())
                            .metadata(cachedMetadata)
                            .executedAt(LocalDateTime.now())
                            .build();
                }
            }

            // 5. 执行 Tool（带自动重试）
            // A write may have committed before a transport/verification error. Never retry it.
            final int MAX_RETRIES = writeOperation ? 1 : 3;
            String resultJson = null;
            // 工具里按口径判定为可见、已写出的价格属性 (响应层对这一份结果不再按键名剥, 见 PriceSensitiveContext)
            java.util.Set<String> visiblePriceKeys = java.util.Set.of();
            Exception lastException = null;
            int retryCount = 0;
            long totalExecutionTime = 0;

            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    log.debug("执行 Tool (尝试 {}/{}): name={}, arguments={}",
                            attempt, MAX_RETRIES, tool.getToolName(), argumentsJson);

                    // F5 fault-injection (dev-fault-injection profile only):
                    // throw RuntimeException for tools in MOCK_TOOL_THROW list.
                    // Throws inside the try-block so it follows existing retry +
                    // CRITIC correction loop, mirroring a real Tool failure.
                    if (toolExecutionFaultInjector != null) {
                        toolExecutionFaultInjector.maybeThrow(tool.getToolName());
                    }

                    long startTime = System.currentTimeMillis();
                    // 价格字段按调用者的权限配置隐藏, 与 Gateway 路径 / REST 列表同一口径 (S5-12):
                    // 直调分支以前不设价格上下文, 工具序列化出来的采购价对无采购价权限的角色也照样带着
                    // (响应层的 Map 剥离只认价格容器路径, 不认 content 里的实体字段)。
                    try (com.cretas.aims.security.PriceSensitiveContext.Scope ignored =
                                 priceScopeFor(userId)) {
                        com.cretas.aims.security.PriceSensitiveContext.startRecordingVisible();
                        try {
                            resultJson = tool.execute(toolCall, context);
                        } finally {
                            visiblePriceKeys = com.cretas.aims.security.PriceSensitiveContext.stopRecordingVisible();
                        }
                    }
                    long executionTime = System.currentTimeMillis() - startTime;
                    totalExecutionTime += executionTime;
                    boolean toolSucceeded = isToolResultSuccessful(resultJson);
                    if (writeOperation && !toolSucceeded && isOutcomeUnknownResult(resultJson)) {
                        // 工具把异常吞成了 {success:false}, 但出错前已经有写入提交 (AbstractBusinessTool 标的):
                        // 不是失败, 是结果不明 —— 不重试、不进纠错、不报失败
                        log.error("Write tool reported a possibly-committed failure; outcome unknown: tool={}",
                                tool.getToolName());
                        return writeOutcomeUnknownResponse(intent, tool, extractToolFailureMessage(resultJson));
                    }
                    String toolFailureMessage = toolSucceeded ? null : extractToolFailureMessage(resultJson);

                    // 只有显式 Boolean true 才能审计为成功；无法确认的结果不得进入成功缓存。
                    try {
                        ToolCallRecord record = ToolCallRecord.builder()
                                .sessionId(sessionId)
                                .factoryId(factoryId)
                                .userId(userId)
                                .toolName(tool.getToolName())
                                .toolParameters(argumentsJson)
                                .parametersHash(redundancyService.computeParametersHash(params))
                                .executionStatus(toolSucceeded
                                        ? ToolCallRecord.ExecutionStatus.SUCCESS
                                        : ToolCallRecord.ExecutionStatus.FAILED)
                                .errorMessage(toolFailureMessage)
                                .executionTimeMs((int) executionTime)
                                .retryCount(attempt - 1)
                                .recovered(attempt > 1)
                                .build();
                        ToolCallRecord savedRecord = redundancyService.recordToolCall(record);
                        if (!writeOperation && toolSucceeded && savedRecord != null && resultJson != null
                                && !SupplierComparisonReadIntent.TOOL.equals(tool.getToolName())) {
                            // cacheResult stores into tool_call_cache.cached_result (JSONB).
                            // Don't substring-truncate — that splits tokens and produces
                            // invalid JSON like `{..."previous` which PG rejects at INSERT.
                            // Cap at 16KB via a structured wrapper so the JSON stays valid.
                            String cacheValue;
                            if (resultJson.length() > 16384) {
                                cacheValue = "{\"_truncated\":true,\"_originalSize\":" + resultJson.length()
                                    + ",\"preview\":" + objectMapper.writeValueAsString(
                                        resultJson.substring(0, 16000) + "...(truncated)") + "}";
                            } else {
                                cacheValue = resultJson;
                            }
                            redundancyService.cacheResult(sessionId, tool.getToolName(), params, cacheValue, savedRecord.getId());
                        }
                        if (attempt > 1) {
                            log.info("工具调用在第 {} 次尝试后恢复成功: tool={}", attempt, tool.getToolName());
                        }
                    } catch (Exception recordEx) {
                        log.warn("记录工具调用失败: {}", recordEx.getMessage());
                    }

                    // D7: 快速规则验证
                    if (toolSucceeded && resultJson != null && attempt < MAX_RETRIES) {
                        if (isResultClearlyValid(resultJson)) {
                            log.info("[D7-FastValidation] Result clearly valid ({}B), skipping LLM validation for tool={}",
                                    resultJson.length(), tool.getToolName());
                            break;
                        }

                        try {
                            ToolResultValidatorService.ValidationResult validationResult =
                                    toolResultValidatorService.validate(
                                            request.getUserInput(),
                                            tool.getToolName(),
                                            params,
                                            resultJson
                                    );

                            if (!validationResult.isValid()) {
                                log.info("结果验证失败: issue={}, description={}, matchScore={}",
                                        validationResult.issue(),
                                        validationResult.issueDescription(),
                                        validationResult.matchScore());

                                String pseudoError = String.format("[%s] %s",
                                        validationResult.issue(), validationResult.issueDescription());

                                ExternalVerifierService.VerificationResult externalVerification = null;
                                try {
                                    externalVerification = externalVerifierService.verifyToolCall(
                                            factoryId, tool.getToolName(), params, pseudoError);
                                } catch (Exception verifyEx) {
                                    log.warn("外部验证失败: {}", verifyEx.getMessage());
                                }

                                CorrectionAgentService.CorrectionResult correctionResult =
                                        correctionAgentService.analyzeAndCorrect(
                                                request.getUserInput(),
                                                tool.getToolName(),
                                                params,
                                                pseudoError,
                                                externalVerification,
                                                attempt
                                        );

                                log.info("纠错 Agent 结果（结果验证触发）: shouldRetry={}, strategy={}, confidence={}",
                                        correctionResult.shouldRetry(),
                                        correctionResult.correctionStrategy(),
                                        correctionResult.confidence());

                                if (correctionResult.shouldRetry() && correctionResult.correctedParams() != null) {
                                    replaceWithGroundedCorrection(params, correctionResult.correctedParams(), tool,
                                            factoryId, userId, userRole);
                                    params.put("_correctionStrategy", correctionResult.correctionStrategy());
                                    params.put("_retryAttempt", attempt);
                                    params.put("_validationIssue", validationResult.issue().name());

                                    argumentsJson = objectMapper.writeValueAsString(params);
                                    toolCall = ToolCall.of(
                                            java.util.UUID.randomUUID().toString(),
                                            tool.getToolName(),
                                            argumentsJson
                                    );

                                    log.info("结果验证纠错: 准备第 {} 次重试, strategy={}, hint={}",
                                            attempt + 1,
                                            correctionResult.correctionStrategy(),
                                            validationResult.correctionHint());

                                    resultJson = null;
                                    continue;
                                }
                            }
                        } catch (Exception validationEx) {
                            log.warn("结果验证过程出错: {}", validationEx.getMessage());
                        }
                    }

                    break;

                } catch (Exception e) {
                    lastException = e;
                    retryCount = attempt;
                    if (writeOperation) {
                        // The tool may already have committed. Read-side self correction must
                        // never replay a write or turn an uncertain outcome into a retry prompt.
                        log.error("Write tool outcome unknown; not retried: tool={}", tool.getToolName(), e);
                        return writeOutcomeUnknownResponse(intent, tool);
                    }

                    String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    log.warn("Tool 执行失败 (尝试 {}/{}): tool={}, error={}",
                            attempt, MAX_RETRIES, tool.getToolName(), errorMsg);

                    ExternalVerifierService.VerificationResult verificationResult = null;
                    try {
                        verificationResult = externalVerifierService.verifyToolCall(
                                factoryId, tool.getToolName(), params, errorMsg);
                        log.info("外部验证结果: hasData={}, status={}, suggestion={}",
                                verificationResult.hasData(), verificationResult.dataStatus(), verificationResult.suggestion());
                    } catch (Exception verifyEx) {
                        log.warn("外部验证失败: {}", verifyEx.getMessage());
                    }

                    CorrectionAgentService.CorrectionResult correctionResult = null;
                    try {
                        correctionResult = correctionAgentService.analyzeAndCorrect(
                                request.getUserInput(),
                                tool.getToolName(),
                                params,
                                errorMsg,
                                verificationResult,
                                attempt
                        );
                        log.info("纠错 Agent 结果: shouldRetry={}, strategy={}, confidence={}",
                                correctionResult.shouldRetry(), correctionResult.correctionStrategy(), correctionResult.confidence());
                    } catch (Exception agentEx) {
                        log.warn("纠错 Agent 调用失败: {}", agentEx.getMessage());
                    }

                    boolean shouldRetry = correctionResult != null && correctionResult.shouldRetry() && attempt < MAX_RETRIES;

                    if (shouldRetry && correctionResult.correctedParams() != null) {
                        replaceWithGroundedCorrection(params, correctionResult.correctedParams(), tool,
                                factoryId, userId, userRole);
                        params.put("_correctionStrategy", correctionResult.correctionStrategy());
                        params.put("_retryAttempt", attempt);
                        params.put("_confidence", correctionResult.confidence());

                        try {
                            argumentsJson = objectMapper.writeValueAsString(params);
                            toolCall = ToolCall.of(
                                    java.util.UUID.randomUUID().toString(),
                                    tool.getToolName(),
                                    argumentsJson
                            );
                        } catch (JsonProcessingException je) {
                            log.error("重试时参数序列化失败: {}", je.getMessage());
                            break;
                        }

                        log.info("CRITIC 纠错: 准备第 {} 次重试, strategy={}, confidence={}",
                                attempt + 1, correctionResult.correctionStrategy(), correctionResult.confidence());

                        try {
                            CorrectionRecord.ErrorCategory errorCategory = selfCorrectionService.classifyError(errorMsg, null);
                            selfCorrectionService.createCorrectionRecord(
                                    null, factoryId, sessionId,
                                    errorCategory.name(), errorMsg, correctionResult.errorAnalysis());
                        } catch (Exception recordEx) {
                            log.warn("记录纠错尝试失败: {}", recordEx.getMessage());
                        }
                    } else {
                        String reason = correctionResult != null ? correctionResult.errorAnalysis() : "纠错 Agent 不可用";
                        log.info("纠错 Agent 判断不重试: {}", reason);
                        break;
                    }
                }
            }

            // 所有重试都失败
            if (resultJson == null && lastException != null) {
                try {
                    ToolCallRecord failedRecord = ToolCallRecord.builder()
                            .sessionId(sessionId)
                            .factoryId(factoryId)
                            .toolName(tool.getToolName())
                            .toolParameters(argumentsJson)
                            .executionStatus(ToolCallRecord.ExecutionStatus.FAILED)
                            .errorMessage(lastException.getMessage())
                            .retryCount(retryCount)
                            .build();
                    redundancyService.recordToolCall(failedRecord);
                } catch (Exception recordEx) {
                    log.warn("记录失败调用时出错: {}", recordEx.getMessage());
                }

                String errorMessage = lastException.getMessage() != null ? lastException.getMessage() : lastException.getClass().getSimpleName();
                CorrectionRecord.ErrorCategory errorCategory = selfCorrectionService.classifyError(errorMessage, null);
                CorrectionRecord.CorrectionStrategy strategy = selfCorrectionService.determineStrategy(errorCategory);

                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("FAILED")
                        .message("执行失败 (已重试 " + retryCount + " 次): " + ErrorSanitizer.sanitize(lastException))
                        .metadata(Map.of(
                                "errorCategory", errorCategory.name(),
                                "correctionStrategy", strategy.name(),
                                "retryCount", retryCount,
                                "autoRetryExhausted", true
                        ))
                        .executedAt(LocalDateTime.now())
                        .build();
            }

            // 6. 解析 Tool 结果
            IntentExecuteResponse response = parseToolResultToResponse(resultJson, intent);
            com.cretas.aims.security.PriceSensitiveContext.approveKeys(response.getResultData(), visiblePriceKeys);

            // 统一填充 metadata.toolName (P5.6) - 下游 SmartBI chat 适配器读取此字段
            if (response != null) {
                Map<String, Object> metadata = response.getMetadata() != null ?
                        new HashMap<>(response.getMetadata()) : new HashMap<>();
                metadata.put("toolName", tool.getToolName());
                if (retryCount > 0 && "SUCCESS".equals(response.getStatus())) {
                    metadata.put("recoveredAfterRetries", retryCount);
                    metadata.put("totalExecutionTimeMs", totalExecutionTime);
                }
                putLastAction(metadata, response.getStatus(), response.getResultData(),
                        intent, tool.getToolName(), params);
                response.setMetadata(metadata);
            }

            return response;

        } catch (JsonProcessingException e) {
            log.error("Tool 参数序列化失败: tool={}, error={}", tool.getToolName(), e.getMessage());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("参数处理失败: " + e.getMessage())
                    .executedAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            log.error("Tool 执行失败: tool={}, error={}", tool.getToolName(), e.getMessage(), e);

            String sessionId = request.getSessionId() != null ? request.getSessionId() : "default";
            try {
                ToolCallRecord failedRecord = ToolCallRecord.builder()
                        .sessionId(sessionId)
                        .factoryId(factoryId)
                        .toolName(tool.getToolName())
                        .executionStatus(ToolCallRecord.ExecutionStatus.FAILED)
                        .errorMessage(e.getMessage())
                        .build();
                redundancyService.recordToolCall(failedRecord);
            } catch (Exception recordEx) {
                log.warn("记录失败调用时出错: {}", recordEx.getMessage());
            }

            String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            CorrectionRecord.ErrorCategory errorCategory = selfCorrectionService.classifyError(errorMessage, null);
            CorrectionRecord.CorrectionStrategy strategy = selfCorrectionService.determineStrategy(errorCategory);

            log.info("错误分类: category={}, strategy={}", errorCategory, strategy);

            String correctionPrompt = selfCorrectionService.generateCorrectionPrompt(errorCategory, errorMessage);

            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("执行失败: " + ErrorSanitizer.sanitize(e))
                    .metadata(Map.of(
                            "errorCategory", errorCategory.name(),
                            "correctionStrategy", strategy.name(),
                            "correctionHint", correctionPrompt.substring(0, Math.min(200, correctionPrompt.length()))
                    ))
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 一线角色 + 写操作 + 未开放 ⇒ 拒绝 (不出确认卡、不执行)。其它情况返回 null, 继续原有流程。
     */
    private IntentExecuteResponse frontlineWriteDenied(ToolExecutor tool, String factoryId,
                                                       AIIntentConfig intent, String userRole) {
        if (frontlineWriteGrants == null) {
            return null;
        }
        java.util.Optional<String> reason = frontlineWriteGrants.denyReason(factoryId, userRole, tool, intent);
        if (reason.isEmpty()) {
            return null;
        }
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent != null ? intent.getIntentCode() : null)
                .intentName(intent != null ? intent.getIntentName() : null)
                .intentCategory(intent != null ? intent.getIntentCategory() : null)
                .status("PERMISSION_DENIED")
                .message(reason.get())
                .formattedText(reason.get())
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * Tool 预览执行
     */
    public IntentExecuteResponse executeToolPreview(ToolExecutor tool, String factoryId,
                                                     IntentExecuteRequest request,
                                                     AIIntentConfig intent,
                                                     Long userId, String userRole) {
        // 深层外发 (抽参、纠错、选工具、消歧…) 不带工厂参数: 按本次路径上认定的工厂脱敏 —— 平台账号替某厂
        // 操作时也按那家工厂的保密产品名换代号 (Steve 2026-10-29, LlmEgressGuard.analysing)
        try (var egressFactory = com.cretas.aims.service.consent.LlmEgressGuard.analysing(factoryId)) {
            return executeToolPreviewInScope(tool, factoryId, request, intent, userId, userRole);
        }
    }

    private IntentExecuteResponse executeToolPreviewInScope(ToolExecutor tool, String factoryId,
                                                     IntentExecuteRequest request,
                                                     AIIntentConfig intent,
                                                     Long userId, String userRole) {
        IntentExecuteResponse frontlineDenied = frontlineWriteDenied(tool, factoryId, intent, userRole);
        if (frontlineDenied != null) {
            return frontlineDenied;
        }
        try {
            Map<String, Object> params = TrustedExecutionContext.merge(
                    request.getContext(), factoryId, userId, userRole);
            params.put("userInput", request.getUserInput());
            params.put("intentCode", intent.getIntentCode());

            // F2 修复: preview 路径也要抽参. 否则纯自然语言走 preview 时, 必填参数 (如 productTypeId /
            // workProcessNames) 为 null → tool.preview 内 resolve 抛 422, "NL→配工序"第一步就跑不通.
            // 与主执行路径 (executeWithTool 的 LLM 抽参) 一致: 只填缺失的必填项, 不覆盖 context 已有值.
            try {
                Map<String, Object> llmExtractedParams =
                        extractParametersWithLLM(factoryId, request.getUserInput(), tool, params);
                if (!llmExtractedParams.isEmpty()) {
                    params.putAll(llmExtractedParams);
                    log.info("Preview 路径 LLM 抽取参数: {}", llmExtractedParams.keySet());
                }
            } catch (Exception ex) {
                log.warn("Preview 路径参数抽取失败 (继续用已有 params): {}", ex.getMessage());
            }

            // Preview must expose the same principal-bound arguments as real execution.
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);
            if (com.cretas.aims.service.intent.PurchaseTaskGrantService.CANONICAL_TOOL.equals(tool.getToolName())) {
                params = controlledPurchase.previewParameters(factoryId, params);
            }

            // 缺「要操作哪一个」(如报工缺工序任务): 不问内部编号, 工具查出本人可选的对象来选 (见 ParameterChoiceResolver)
            if (tool instanceof com.cretas.aims.ai.tool.ParameterChoiceResolver resolver
                    && isBlankParam(params.get(resolver.choiceParameter()))) {
                IntentExecuteResponse asked = resolveChoiceOrAsk(resolver, tool, factoryId, userId, intent, params,
                        request.getUserInput());
                if (asked != null) {
                    return asked;
                }
            }

            String resultJson = runReadOnlyPreview(tool, intent, params, factoryId, userId, userRole);

            IntentExecuteResponse previewResponse = IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("PREVIEW")
                    .message(resultJson)
                    .executedAt(LocalDateTime.now())
                    .build();

            // P1.5 (2026-07-23 读写分块 spec §5.2): 预览成功即铸绑定 token 并回
            // confirmableAction — 补齐通用工具 TCC 的最后一环 (此前 token 只在
            // 餐饮 agent 工作流铸, 通用预览无 token → confirm() 无从驱动,
            // 确认卡在前端无米下锅)。只对写工具铸 (读工具预览无确认语义)。
            if (writeGuardService.isWriteTool(tool) || writeGuardService.isWriteIntent(intent)) {
                Map<String, Object> previewData = null;
                try {
                    previewData = objectMapper.readValue(resultJson, Map.class);
                } catch (Exception ignore) {
                    // 预览体非 JSON 对象时 previewData 留空, 前端渲染 message 原文
                }
                // 🔴 预览自己说了「不行」(success=false / canDo=false) 就**不铸 token**。
                //    以前只看是不是写工具: 预览失败、超收、任务已完成……照样拿到一张可确认的票,
                //    前端只能自己去 previewData 里翻 canDo 判断要不要亮「确认」——
                //    一个没翻的客户端就能把一次被业务规则拦下的操作确认出去(确认时才 409 或更糟)。
                //    「能不能确认」由产出端说了算: 没有票就没有确认按钮。
                IntentExecuteResponse blocked = blockedPreviewResponse(previewData, intent);
                if (blocked != null) {
                    log.info("预览未通过, 不铸确认 token: tool={}, status={}, reason={}",
                            tool.getToolName(), blocked.getStatus(), blocked.getMessage());
                    if ("NEED_MORE_INFO".equals(blocked.getStatus())) {
                        openPreviewQuestionSession(tool, factoryId, userId, intent, params, previewData, blocked);
                    }
                    return blocked;
                }
                try {
                    // 先提取改前/改后再铸 token — 让 token 侧也持久化对比数据
                    // (审计追溯: intent_preview_tokens.current_values/new_values)。
                    Map<String, Object> tokenCurrentValues = extractPreviewSection(
                            previewData, "currentValues", "currentValue", "current");
                    Map<String, Object> tokenNewValues = extractPreviewSection(
                            previewData, "newValues", "newValue", "proposed");
                    PreviewSubject subject = previewSubject(tool, params, previewData);
                    com.cretas.aims.entity.intent.IntentPreviewToken boundToken =
                            previewTokenService.createBoundToken(
                                    new PreviewTokenService.BoundTokenRequest(
                                            factoryId, userId, null,
                                            intent.getIntentCode(), intent.getIntentName(),
                                            tool.getToolName(), tool.getVersion(),
                                            ToolExecutionMode.EXECUTE,
                                            subject.entityType(), subject.entityId(), subject.operation(),
                                            params,
                                            priorStateSnapshot(tool, previewData),
                                            tokenNewValues != null ? tokenNewValues : java.util.Map.of(),
                                            300));
                    if (com.cretas.aims.service.intent.PurchaseTaskGrantService.CANONICAL_TOOL.equals(tool.getToolName())) {
                        previewData.put("grantId", com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService.grantId(boundToken.getId()));
                        previewResponse.setResultData(java.util.Map.of("grantId",
                                com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService.grantId(boundToken.getId())));
                    }
                    // 写操作影响契约 (2026-07-24): 改前/改后 + 确定性影响说明 —
                    // 前端对比表 + 二次确认弹窗的数据源。
                    String actionType = String.valueOf(tool.getActionType());
                    String riskLevel = String.valueOf(tool.getRiskLevel());
                    String impactSummary = buildImpactSummary(
                            actionType, intent.getIntentName(), tokenCurrentValues, tokenNewValues);
                    previewResponse.setAiMode("WRITE");
                    previewResponse.setConfirmableAction(
                            IntentExecuteResponse.ConfirmableAction.builder()
                                    .confirmToken(boundToken.getToken())
                                    .commandDigest(boundToken.getCommandDigest())
                                    .expiresAt(boundToken.getExpiresAt()
                                            .atZone(java.time.ZoneId.systemDefault()).toInstant())
                                    .expiresInSeconds(300)
                                    .description("确认后将实际执行「" + intent.getIntentName() + "」")
                                    .previewData(previewData)
                                    .currentValues(tokenCurrentValues)
                                    .newValues(tokenNewValues)
                                    .impactSummary(impactSummary)
                                    .actionType(actionType)
                                    .riskLevel(riskLevel)
                                    .build());
                } catch (Exception e) {
                    // fail-safe: 铸 token 失败不破坏预览本身 (无 confirmableAction →
                    // 前端按"暂不支持一键确认"降级提示), 但要留痕排查
                    log.warn("预览 token 铸造失败 (预览照常返回): tool={}, error={}",
                            tool.getToolName(), e.getMessage());
                }
            }

            return previewResponse;

        } catch (Exception e) {
            log.error("Tool preview 失败: tool={}, error={}", tool.getToolName(), e.getMessage(), e);
            String errorMsg = "预览失败: " + ErrorSanitizer.sanitize(e);
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .status("FAILED")
                    .message(errorMsg)
                    .formattedText(errorMsg)
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    private static boolean isBlankParam(Object value) {
        return value == null || (value instanceof String s && s.isBlank());
    }

    /**
     * 工具说「要操作哪一个」这个参数缺了: 查本人可选的对象。
     * <ul>
     *   <li>只有一个 → 直接填进 {@code params}, 返回 null 继续走原有预览 (仍出确认卡, 不自动提交);</li>
     *   <li>多个 → 返回追问 (列成序号), 并开参数收集会话挂上选项: 已给的产量等参数带着走, 回「1」/ 说产品名即选中;</li>
     *   <li>一个都没有 → 如实说没有 (不编造), 不出确认卡;</li>
     *   <li>查询出错 → 返回 null, 退回原有的追问 (不拿假候选顶替)。</li>
     * </ul>
     */
    private IntentExecuteResponse resolveChoiceOrAsk(com.cretas.aims.ai.tool.ParameterChoiceResolver resolver,
                                                     ToolExecutor tool, String factoryId, Long userId,
                                                     AIIntentConfig intent, Map<String, Object> params,
                                                     String userInput) {
        com.cretas.aims.ai.tool.ParameterChoiceResolver.Resolution resolution;
        try {
            resolution = resolver.resolveChoices(factoryId, userId, params, userInput);
        } catch (Exception e) {
            log.warn("查本人可选对象失败, 退回原有追问: tool={}, error={}", tool.getToolName(), e.getMessage());
            return null;
        }
        if (resolution == null) {
            return null;
        }
        String param = resolver.choiceParameter();
        switch (resolution.kind()) {
            case SINGLE: {
                if (resolution.choices().size() != 1) {
                    return null;
                }
                params.put(param, resolution.choices().get(0).value());
                log.info("缺 {} 且本人只有一个可选, 直接带出: tool={}, value={}", param, tool.getToolName(),
                        resolution.choices().get(0).value());
                return null;
            }
            case NONE:
                return IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status(PREVIEW_BLOCKED)
                        .message(resolution.message())
                        .formattedText(resolution.message())
                        .aiMode("WRITE")
                        .executedAt(LocalDateTime.now())
                        .build();
            case MANY: {
                if (resolution.choices().isEmpty()) {
                    return null;
                }
                String body = resolution.message();
                IntentExecuteResponse asked = IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status("NEED_MORE_INFO")
                        .aiMode("WRITE")
                        .executedAt(LocalDateTime.now())
                        .build();
                Map<String, Object> missing = new HashMap<>();
                missing.put("missingParameters", List.of(param));
                openPreviewQuestionSession(tool, factoryId, userId, intent, params, missing, asked);
                boolean attached = false;
                if (asked.getSessionId() != null && conversationService != null) {
                    List<ConversationService.CandidateInfo> options = new ArrayList<>();
                    for (com.cretas.aims.ai.tool.ParameterChoiceResolver.Choice c : resolution.choices()) {
                        options.add(ConversationService.CandidateInfo.builder()
                                .intentCode(c.value()).intentName(c.label())
                                .description(String.join(
                                        com.cretas.aims.service.ClarificationChoiceMatcher.KEYWORD_SEPARATOR, c.keywords()))
                                .build());
                    }
                    attached = conversationService.attachParameterChoices(
                            factoryId, userId, asked.getSessionId(), param, resolver.choiceLabel(), options);
                    if (!attached) {
                        // 没挂上选项 = 回「1」会被当成别的话: 不带会话 (不留一个答不对的追问)
                        log.warn("选项没挂上会话, 追问不带会话: tool={}, session={}", tool.getToolName(), asked.getSessionId());
                        conversationService.cancelConversation(factoryId, userId, asked.getSessionId());
                        asked.setSessionId(null);
                    }
                }
                // 回复有人接才叫用户「回数字」; 没会话时只能请他重新说一遍
                String hint = attached ? resolution.replyHint() : resolution.rephraseHint();
                String text = hint == null || hint.isBlank() ? body : body + "\n" + hint;
                asked.setMessage(text);
                asked.setFormattedText(text);
                asked.setClarificationQuestions(List.of(text));
                return asked;
            }
            default:
                return null;
        }
    }

    /** 预览被业务规则拦下 (canDo=false) 或预览本身失败 (success=false) 时的状态。 */
    static final String PREVIEW_BLOCKED = "PREVIEW_BLOCKED";

    // ==================== 写入确认: 对象与状态绑定 (Fable 审阅 §8.2 / 建议 5) ====================

    /**
     * 本类唯一的工具预览调用点: 预览铸凭据 ({@link #executeToolPreview}) 与确认前重读状态
     * ({@link #recheckPreviewState}) 都走这里 —— 直调 ToolExecutor 的位置受
     * {@code ToolExecutionArchitectureTest} 棘轮约束, 只许收不许增。参数已由调用方按可信主体收口。
     */
    private String runReadOnlyPreview(ToolExecutor tool, AIIntentConfig intent, Map<String, Object> params,
                                      String factoryId, Long userId, String userRole) throws Exception {
        ToolCall toolCall = ToolCall.of(
                java.util.UUID.randomUUID().toString(),
                tool.getToolName(),
                objectMapper.writeValueAsString(params));
        Map<String, Object> context = TrustedExecutionContext.merge(null, factoryId, userId, userRole);
        context.put("intentConfig", intent);
        return tool.preview(toolCall, context);
    }

    static final String WRITE_OUTCOME_UNKNOWN_MESSAGE = "操作结果暂时无法确认，请先在业务页面核对记录，勿重复提交。";

    /** 写操作结果不明 (可能已写入): 统一回执, 确认流程据此把凭据记 OUTCOME_UNKNOWN。 */
    private static IntentExecuteResponse writeOutcomeUnknownResponse(AIIntentConfig intent, ToolExecutor tool) {
        return writeOutcomeUnknownResponse(intent, tool, null);
    }

    /** 带上工具给的出错原因: 结果不明时用户仍要知道它为什么停下。 */
    private static IntentExecuteResponse writeOutcomeUnknownResponse(AIIntentConfig intent, ToolExecutor tool,
                                                                     String reason) {
        String message = reason == null || reason.isBlank()
                ? WRITE_OUTCOME_UNKNOWN_MESSAGE
                : WRITE_OUTCOME_UNKNOWN_MESSAGE + "出错原因：" + reason;
        return IntentExecuteResponse.builder()
                .intentRecognized(true).intentCode(intent.getIntentCode())
                .status("OUTCOME_UNKNOWN").aiMode("WRITE")
                .message(message)
                .formattedText(message)
                .metadata(Map.of("toolName", tool.getToolName(), "retryable", false))
                .executedAt(LocalDateTime.now()).build();
    }

    private boolean isOutcomeUnknownResult(String resultJson) {
        if (resultJson == null) {
            return false;
        }
        try {
            return flaggedOutcomeUnknown(objectMapper.readTree(resultJson));
        } catch (Exception unparsable) {
            return false;
        }
    }

    private static boolean flaggedOutcomeUnknown(com.fasterxml.jackson.databind.JsonNode payload) {
        return payload != null && payload.isObject()
                && payload.path(com.cretas.aims.ai.tool.AbstractBusinessTool.OUTCOME_UNKNOWN_FLAG).asBoolean(false);
    }

    /** 凭据上的对象: 类型、ID、操作。新建型工具没有已存在的对象, ID 记 {@link #NEW_RECORD_ENTITY_ID}。 */
    record PreviewSubject(String entityType, String entityId, String operation) { }

    static final String NEW_RECORD_ENTITY_ID = "NEW";

    private static final java.util.Set<String> PRINCIPAL_PARAMETER_KEYS = java.util.Set.of(
            "factoryId", "factory_id", "tenantId", "tenant_id", "userId", "user_id");

    /**
     * 铸凭据时把对象类型与对象 ID 填上 (以前三个都传 null, 对象只作为参数的一部分进了哈希)。
     * 类型: 预览自报的 entityType, 否则就是工具名 (工具即对象种类); ID: 预览自报的 entityId,
     * 否则按工具声明的必填参数顺序取第一个以 Id / No 结尾的值 (主体参数); 新建型工具取 NEW。
     */
    PreviewSubject previewSubject(ToolExecutor tool, Map<String, Object> params, Map<String, Object> previewData) {
        String entityType = previewText(previewData, "entityType");
        if (entityType == null) {
            entityType = tool.getToolName();
        }
        String entityId = previewText(previewData, "entityId");
        if (entityId == null && params != null) {
            for (String key : requiredParameterNames(tool)) {
                if (key == null || PRINCIPAL_PARAMETER_KEYS.contains(key)
                        || !(key.endsWith("Id") || key.endsWith("No"))) {
                    continue;
                }
                Object value = params.get(key);
                if (value != null && !String.valueOf(value).isBlank()) {
                    entityId = String.valueOf(value);
                    break;
                }
            }
        }
        if (entityId == null && tool.previewCreatesNewRecordOnly()) {
            entityId = NEW_RECORD_ENTITY_ID;
        }
        String operation = tool.getActionType() != null ? tool.getActionType().name() : null;
        return new PreviewSubject(truncate(entityType, 50), truncate(entityId, 100), truncate(operation, 20));
    }

    private static List<String> requiredParameterNames(ToolExecutor tool) {
        Map<String, Object> schema;
        try {
            schema = tool.getParametersSchema();
        } catch (RuntimeException e) {
            return List.of();
        }
        if (schema != null && schema.get("required") instanceof List<?> required) {
            List<String> names = new ArrayList<>();
            for (Object item : required) {
                if (item instanceof String name) {
                    names.add(name);
                }
            }
            return names;
        }
        return List.of();
    }

    private static String previewText(Map<String, Object> previewData, String key) {
        if (previewData == null) {
            return null;
        }
        Object value = previewData.get(key);
        if (value == null && previewData.get("data") instanceof Map<?, ?> nested) {
            value = nested.get(key);
        }
        return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    private static final java.util.regex.Pattern PRIOR_STATE_KEY = java.util.regex.Pattern.compile("^current(ly)?[A-Z].*");

    /**
     * 预览给出的改前值快照, 铸凭据时存进凭据、确认时重算后比对, 两处必须用同一条规则。
     * <ol>
     *   <li>预览的 {@code currentValues} (或 currentValue / current) —— 工具明确给出的改前值;</li>
     *   <li>没有时, 改动已有记录的工具取预览里以 {@code current} 开头的字段 (currentStatus、currentEnabled、
     *       currentQuantity…): 状态流转类工具普遍这样报改前状态, 只是没包成 currentValues;</li>
     *   <li>都没有 → 空 (新建型放行, 其余无法核对)。</li>
     * </ol>
     * 新建型工具不取第 2 类: 那类字段在新建工具里多半是旁证 (如「当前已有 N 条」), 不是被改对象的状态。
     * 返回值不为 null; 只进凭据, 不改确认卡上展示的改前/改后。
     */
    Map<String, Object> priorStateSnapshot(ToolExecutor tool, Map<String, Object> previewData) {
        Map<String, Object> explicit = extractPreviewSection(previewData, "currentValues", "currentValue", "current");
        if (explicit != null) {
            return explicit;
        }
        if (previewData == null || tool.previewCreatesNewRecordOnly()) {
            return Map.of();
        }
        Map<String, Object> data = previewData.get("data") instanceof Map<?, ?> nested
                ? (Map<String, Object>) nested : previewData;
        Map<String, Object> snapshot = new java.util.LinkedHashMap<>();
        data.forEach((key, value) -> {
            if (key != null && PRIOR_STATE_KEY.matcher(key).matches()) {
                snapshot.put(key, value);
            }
        });
        return snapshot;
    }

    static final String NOT_COMPARABLE_MESSAGE =
            "这个操作暂时不能在助手里确认：系统无法核对这条记录在预览之后有没有被别人改过，本次未执行。请到对应的业务页面办理。";

    /** 确认前状态核对的结论。{@code proceed=false} 时本次确认不得执行工具。 */
    record ConfirmStateCheck(boolean proceed, String code, List<String> changedFields, String message) {
        static final String STATE_CHANGED = "STATE_CHANGED";
        static final String PRECONDITION_CHANGED = "PRECONDITION_CHANGED";
        static final String NOT_COMPARABLE = "NOT_COMPARABLE";

        /** 重新预览能解决的才让用户重新预览; 无法核对的不能 (会原地打转)。 */
        boolean repreviewHelps() {
            return STATE_CHANGED.equals(code) || PRECONDITION_CHANGED.equals(code)
                    || REREAD_FAILED.equals(code);
        }

        /** 确认时重读失败 (预览抛异常 / 记录的改前值读不出): 一时的, 重新预览可以。 */
        static final String REREAD_FAILED = "REREAD_FAILED";

        static ConfirmStateCheck ok() {
            return new ConfirmStateCheck(true, "OK", List.of(), null);
        }

        static ConfirmStateCheck reject(String code, List<String> fields, String message) {
            return new ConfirmStateCheck(false, code, fields == null ? List.of() : List.copyOf(fields), message);
        }
    }

    /**
     * 确认时重读对象当前状态: 用凭据里绑定的参数重跑一次<b>只读</b>预览, 拿这次的改前值
     * ({@code currentValues}) 与铸凭据时记下的改前值逐字段比对。
     * <ul>
     *   <li>预览不再通过 (业务前提变了) → 拒绝, 带预览给的原因;</li>
     *   <li>有字段不一致 (预览之后有人改过) → 拒绝, 写明哪些字段变了;</li>
     *   <li>两次都给不出改前值: 只新建记录的工具 ({@link ToolExecutor#previewCreatesNewRecordOnly()}) 放行,
     *       其余一律按「无法核对」拒绝 (fail-closed);</li>
     *   <li>预览本身抛异常 → 拒绝 (什么都没执行, 不是「结果不明」)。</li>
     * </ul>
     * 本方法不执行任何写操作; 比对与执行之间的毫秒级窗口仍由各工具自己的业务校验兜底。
     */
    ConfirmStateCheck recheckPreviewState(ToolExecutor tool, AIIntentConfig intent,
                                          String storedCurrentValuesJson,
                                          Map<String, Object> claimedParameters,
                                          String factoryId, Long userId, String userRole) {
        Map<String, Object> stored;
        try {
            stored = storedCurrentValuesJson == null || storedCurrentValuesJson.isBlank()
                    ? Map.of()
                    : objectMapper.readValue(storedCurrentValuesJson,
                            new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.REREAD_FAILED, List.of(),
                    "预览时记录的改前值无法读取，本次未执行。请重新预览后再确认。");
        }
        if (stored == null) {
            stored = Map.of();
        }
        if (!tool.supportsPreview()) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.NOT_COMPARABLE, List.of(),
                    NOT_COMPARABLE_MESSAGE);
        }

        Map<String, Object> previewData;
        try {
            Map<String, Object> params = new HashMap<>(
                    claimedParameters != null ? claimedParameters : Map.of());
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);
            // 与铸凭据时同一个预览入口 (同样的可信上下文、同样的参数形状), 不另开一条直调工具的路径
            String resultJson = runReadOnlyPreview(tool, intent, params, factoryId, userId, userRole);
            previewData = objectMapper.readValue(resultJson,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            log.warn("确认前重读状态失败, 拒绝执行: tool={}, error={}", tool.getToolName(), e.getMessage());
            return ConfirmStateCheck.reject(ConfirmStateCheck.REREAD_FAILED, List.of(),
                    "确认前无法重新读取对象当前状态，本次未执行。请重新预览后再确认。");
        }
        if (previewData == null) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.REREAD_FAILED, List.of(),
                    "确认前无法重新读取对象当前状态，本次未执行。请重新预览后再确认。");
        }

        IntentExecuteResponse blocked = blockedPreviewResponse(previewData, intent);
        if (blocked != null) {
            return ConfirmStateCheck.reject(ConfirmStateCheck.PRECONDITION_CHANGED, List.of(),
                    "预览之后情况有变化，现在不能执行：" + blocked.getMessage() + " 本次未执行。");
        }

        Map<String, Object> fresh = priorStateSnapshot(tool, previewData);
        if (stored.isEmpty() && fresh.isEmpty()) {
            if (tool.previewCreatesNewRecordOnly()) {
                return ConfirmStateCheck.ok();
            }
            // ⛔ 不回「请重新预览」: 重新预览还是给不出改前值, 用户会在同一张卡上死循环
            return ConfirmStateCheck.reject(ConfirmStateCheck.NOT_COMPARABLE, List.of(),
                    NOT_COMPARABLE_MESSAGE);
        }

        List<String> changed = changedFields(stored, fresh);
        if (!changed.isEmpty()) {
            String fields = changed.stream().map(f -> "「" + f + "」").collect(java.util.stream.Collectors.joining(""));
            return ConfirmStateCheck.reject(ConfirmStateCheck.STATE_CHANGED, changed,
                    "预览之后" + fields + "已被改动（与预览时的改前值不一致），本次未执行。请重新预览后再确认。");
        }
        return ConfirmStateCheck.ok();
    }

    private List<String> changedFields(Map<String, Object> stored, Map<String, Object> fresh) {
        java.util.LinkedHashSet<String> keys = new java.util.LinkedHashSet<>(stored.keySet());
        keys.addAll(fresh.keySet());
        List<String> changed = new ArrayList<>();
        for (String key : keys) {
            if (!stored.containsKey(key) || !fresh.containsKey(key)) {
                changed.add(key);
                continue;
            }
            com.fasterxml.jackson.databind.JsonNode left = objectMapper.valueToTree(stored.get(key));
            com.fasterxml.jackson.databind.JsonNode right = objectMapper.valueToTree(fresh.get(key));
            if (!sameValue(left, right)) {
                changed.add(key);
            }
        }
        return changed;
    }

    /**
     * 同一个值: 数字按数值比; 元素全是标量的数组按多重集合比 —— 「当前已订阅的设备」这类列表来自没有
     * ORDER BY 的查询, 顺序不是状态, 按顺序比会把没人动过的记录误判成「被改过」。元素是对象的数组仍按顺序比
     * (FIFO 推荐批次之类, 顺序就是内容)。
     */
    private static boolean sameValue(com.fasterxml.jackson.databind.JsonNode left,
                                     com.fasterxml.jackson.databind.JsonNode right) {
        if (left.isArray() && right.isArray() && left.size() == right.size()
                && allScalars(left) && allScalars(right)) {
            return sortedScalars(left).equals(sortedScalars(right));
        }
        return left.equals(NUMERIC_AWARE, right);
    }

    private static boolean allScalars(com.fasterxml.jackson.databind.JsonNode array) {
        for (com.fasterxml.jackson.databind.JsonNode item : array) {
            if (item.isContainerNode()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> sortedScalars(com.fasterxml.jackson.databind.JsonNode array) {
        List<String> values = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : array) {
            values.add(item.isNumber() ? item.decimalValue().stripTrailingZeros().toPlainString() : item.toString());
        }
        java.util.Collections.sort(values);
        return values;
    }

    /** 12.5 与 12.50、5 与 5.0 算同一个值: 序列化往返会改数字的表示, 不能让它被读成「改过」。 */
    private static final java.util.Comparator<com.fasterxml.jackson.databind.JsonNode> NUMERIC_AWARE = (a, b) -> {
        if (a.isNumber() && b.isNumber()) {
            return a.decimalValue().compareTo(b.decimalValue());
        }
        return a.equals(b) ? 0 : 1;
    };

    /**
     * 预览没通过时给出的回执; 预览通过 (或判不了) 返回 {@code null}。
     *
     * <p>判据与两端客户端此前各自在前端做的那一份相同 (RN {@code blockedPreviewReason}):
     * <ul>
     *   <li>{@code {success:false, error|message}} —— 工具预览失败 / 缺参数</li>
     *   <li>{@code {success:true, data:{canDo:false, message}}} —— 业务边界 (超收 / 状态不对 …)</li>
     * </ul>
     *
     * <p>回执形状 (字段不变, 只是状态与 confirmableAction 不同):
     * <ul>
     *   <li>{@code status}: 工具说缺参数 → {@code NEED_MORE_INFO} (带 clarificationQuestions,
     *       与执行路径 {@link #parseToolResultToResponse} 的映射一致); 其余 → {@link #PREVIEW_BLOCKED}</li>
     *   <li>{@code message / formattedText}: 原因原文 —— web {@code AIQuery} 对非卡片状态渲染 message,
     *       RN 卡片对非 PREVIEW 状态渲染 formattedText ?? message。⚠️ 若仍回 {@code PREVIEW} 而无
     *       confirmableAction, 两端都只会显示一句通用的「暂不支持一键确认」, 原因丢失</li>
     *   <li>{@code resultData}: 完整预览体, 不丢细节</li>
     *   <li>{@code confirmableAction}: 恒为 null —— 没有票就没有确认按钮</li>
     * </ul>
     *
     * <p>⚠️ previewData 为 null (预览体不是 JSON 对象) 时判不了, 维持原行为 (照常铸 token)。
     */
    @SuppressWarnings("unchecked")
    IntentExecuteResponse blockedPreviewResponse(Map<String, Object> previewData, AIIntentConfig intent) {
        if (previewData == null) {
            return null;
        }
        String reason = null;
        String status = PREVIEW_BLOCKED;
        List<String> questions = null;
        if (Boolean.FALSE.equals(previewData.get("success"))) {
            reason = firstText(previewData.get("message"), previewData.get("error"));
            if (reason == null) {
                reason = "预览没有通过，未执行任何操作。";
            }
            if (Boolean.TRUE.equals(previewData.get("needMoreInfo"))
                    || "NEED_MORE_INFO".equals(previewData.get("status"))) {
                status = "NEED_MORE_INFO";
                Object q = previewData.get("clarificationQuestions");
                if (q instanceof List) {
                    questions = new ArrayList<>();
                    for (Object item : (List<Object>) q) {
                        if (item instanceof String && !((String) item).isBlank()) {
                            questions.add((String) item);
                        }
                    }
                }
            }
        } else {
            Object nested = previewData.get("data");
            Map<String, Object> data = nested instanceof Map ? (Map<String, Object>) nested : previewData;
            if (Boolean.FALSE.equals(data.get("canDo"))) {
                reason = firstText(data.get("message"));
                if (reason == null) {
                    reason = "当前条件下不能执行这个操作，未执行任何操作。";
                }
            }
        }
        if (reason == null) {
            return null;
        }
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status(status)
                .message(reason)
                .formattedText(reason)
                .clarificationQuestions(questions)
                .resultData(previewData)
                .aiMode("WRITE")
                .executedAt(LocalDateTime.now())
                .build();
    }

    /**
     * 预览发出的追问也要有人接答案 (2026-10-07, prod 真机: 「领用2015胸肉0.1公斤」→ 预览问「要领用哪个批次？」→
     * 答「MT-20260829-9687」被当一句新话, 识别成批次溯源查询, 0.1 公斤丢了)。
     *
     * <p>SSE 入口的 Slot Filling 缺参会开一个参数收集会话, 下一句由 {@code SseStreamingService#streamSlotContinuation}
     * 接着填槽、继续同一个意图。预览出口 (这里) 以前不开 —— 追问发出去就没了下文。现在同样开一个:
     * 意图 = 被预览的意图; 待问的槽 = 工具说缺的那几个 ({@code missingParameters}); 已收集 = 这一轮预览用到的、
     * 工具 schema 声明过的标量参数 (含 quantity=0.1; 待问的槽不带, 免得把「2015胸肉」这种旧值当答案);
     * 会话 id 放进回执 {@code sessionId}, App 把它原样带回下一句。
     *
     * <p>⛔ 不开会话的情况: 没有会话服务 / 没有主体 / 工具没说缺哪个槽 (答案无处可填, 开了只会截走下一句)。
     * 开会话失败不破坏追问本身 (App 退回老行为: 让用户重新说一遍)。
     */
    @SuppressWarnings("unchecked")
    private void openPreviewQuestionSession(ToolExecutor tool, String factoryId, Long userId, AIIntentConfig intent,
                                            Map<String, Object> params, Map<String, Object> previewData,
                                            IntentExecuteResponse asked) {
        if (conversationService == null || userId == null || factoryId == null || previewData == null) {
            return;
        }
        if (!hasBoundFactoryActor(factoryId, userId)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (previewData.get("missingParameters") instanceof List<?> names) {
            for (Object n : names) {
                if (n instanceof String s && !s.isBlank() && !missing.contains(s)) {
                    missing.add(s);
                }
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, Object> properties = tool.getParametersSchema() != null
                && tool.getParametersSchema().get("properties") instanceof Map
                ? (Map<String, Object>) tool.getParametersSchema().get("properties") : Map.of();
        List<ConversationService.RequiredParameter> required = new ArrayList<>();
        for (String name : missing) {
            Object def = properties.get(name);
            String type = def instanceof Map<?, ?> d && d.get("type") instanceof String t ? t : null;
            Object description = def instanceof Map<?, ?> d2 ? d2.get("description") : null;
            required.add(ConversationService.RequiredParameter.builder()
                    .name(name)
                    .label(description instanceof String text && !text.isBlank() ? text : name)
                    .type(slotTypeOf(type))
                    .collected(false)
                    .build());
        }
        Map<String, Object> collected = SseStreamingService.declaredScalarParams(params, tool);
        missing.forEach(collected::remove);
        try {
            ConversationService.ConversationResponse started = conversationService.startParameterCollection(
                    factoryId, userId, intent.getIntentCode(), intent.getIntentName(), required,
                    asked.getClarificationQuestions() != null ? asked.getClarificationQuestions() : List.of(),
                    collected);
            if (started != null && started.getSessionId() != null && !started.getSessionId().isBlank()) {
                asked.setSessionId(started.getSessionId());
                log.info("预览追问已开参数收集会话: sessionId={}, intent={}, asking={}, carried={}",
                        started.getSessionId(), intent.getIntentCode(), missing, collected.keySet());
            }
        } catch (Exception e) {
            log.warn("预览追问开会话失败 (追问照常返回): intent={}, error={}", intent.getIntentCode(), e.getMessage());
        }
    }

    private boolean hasBoundFactoryActor(String factoryId, Long userId) {
        return com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution()
                .filter(actor -> actor.type() == com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER)
                .filter(actor -> Objects.equals(actor.id(), userId))
                .filter(actor -> Objects.equals(actor.factoryId(), factoryId))
                .isPresent();
    }

    private static String slotTypeOf(String jsonType) {
        if (jsonType == null) {
            return "TEXT";
        }
        switch (jsonType.toLowerCase()) {
            case "integer": return "INTEGER";
            case "number": return "NUMBER";
            case "boolean": return "BOOLEAN";
            default: return "TEXT";
        }
    }

    private static String firstText(Object... candidates) {
        for (Object c : candidates) {
            if (c instanceof String && !((String) c).isBlank()) {
                return (String) c;
            }
        }
        return null;
    }

    /**
     * 写操作影响契约 (2026-07-24): 从 doPreview 结果里按既有约定字段提取
     * 改前/改后小节。兼容三种形态: 顶层 map / data 嵌套 / 标量值 (包成
     * {"值": v})。任一 key 命中即返回; 全miss 返回 null (前端回落参数网格)。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> extractPreviewSection(Map<String, Object> previewData, String... keys) {
        if (previewData == null) {
            return null;
        }
        Map<String, Object> data = previewData;
        Object nested = previewData.get("data");
        if (nested instanceof Map) {
            data = (Map<String, Object>) nested;
        }
        for (String key : keys) {
            Object v = data.get(key);
            if (v == null) {
                v = previewData.get(key);
            }
            if (v instanceof Map) {
                return (Map<String, Object>) v;
            }
            if (v != null) {
                Map<String, Object> wrapped = new HashMap<>();
                wrapped.put("值", v);
                return wrapped;
            }
        }
        return null;
    }

    /**
     * 确定性影响说明 (无 LLM): 按 ActionType 说清"确认后会发生什么", 有对比
     * 数据时附字段变更数。前端二次确认弹窗原文展示。
     */
    private String buildImpactSummary(String actionType, String intentName,
                                      Map<String, Object> currentValues,
                                      Map<String, Object> newValues) {
        String base;
        switch (actionType == null ? "" : actionType) {
            case "DELETE":
                base = "此操作将删除「" + intentName + "」相关数据，确认后立即生效且不可自动恢复。";
                break;
            case "UPDATE":
                base = "此操作将修改「" + intentName + "」相关的现有数据，确认后立即生效。";
                break;
            case "WRITE":
                base = "此操作将写入「" + intentName + "」相关数据，确认后立即生效。";
                break;
            default:
                base = "此操作确认后将立即执行「" + intentName + "」。";
        }
        if (newValues != null && !newValues.isEmpty()) {
            int changed = 0;
            for (Map.Entry<String, Object> e : newValues.entrySet()) {
                Object cur = currentValues != null ? currentValues.get(e.getKey()) : null;
                if (cur == null || !String.valueOf(cur).equals(String.valueOf(e.getValue()))) {
                    changed++;
                }
            }
            if (changed > 0) {
                base += " 本次共 " + changed + " 项字段变更，详见上方对比。";
            }
        }
        return base;
    }

    /**
     * 解析 Tool 执行结果为 IntentExecuteResponse
     */
    @SuppressWarnings("unchecked")
    public IntentExecuteResponse parseToolResultToResponse(String resultJson, AIIntentConfig intent) {
        try {
            Map<String, Object> result = objectMapper.readValue(resultJson, Map.class);

            // 🔴 2026-08-19: 原来是 `getOrDefault("success", true)` —— 把「工具没说成没成」
            //    翻译成「成功了」。本仓形态 A¹⁰: 读不到就炸, 比读到一个假的成功好。
            //    AbstractTool 的 buildSuccessResult / buildErrorResult **都**显式写 success,
            //    所以缺这个键 = 该工具没走标准出口, 属于需要修的工具, 不该被当成成功放行。
            //    这里不抛异常(会把一次查询变成 500), 而是: 不声称成功 + WARN 点名工具。
            Object rawSuccess = result.get("success");
            Boolean success;
            if (rawSuccess instanceof Boolean b) {
                success = b;
            } else {
                success = false;
                log.warn("Tool 结果缺少 success 字段, 按【未成功】处理 (不再默认 true): intent={}, tool={}, keys={}",
                        intent != null ? intent.getIntentCode() : "null",
                        intent != null ? intent.getToolName() : "null",
                        result.keySet());
            }
            Object data = result.get("data");

            String message = (String) result.get("message");
            if (message == null && data instanceof Map) {
                Map<String, Object> dataMap = (Map<String, Object>) data;
                message = (String) dataMap.get("message");
            }
            // 🔴 2026-08-19: 失败侧的文案键是 "error" 而不是 "message" ——
            //    AbstractTool.buildErrorResult 写的是 {"success":false,"error":"<真实原因>"},
            //    而这里此前只找 "message", 于是**每一次工具失败的真实原因都被丢掉**,
            //    掉进下面那句通用兜底。产出端有、消费端找的是另一个键 (形态 B: 投影丢失)。
            if (message == null) {
                Object err = result.get("error");
                if (err == null && data instanceof Map) {
                    err = ((Map<String, Object>) data).get("error");
                }
                if (err != null) {
                    message = String.valueOf(err);
                }
            }
            if (message == null) {
                // Sprint 12: enriched fallback. Bare "执行失败" violates close-gate
                // "≥80-char structured Chinese with concrete 数字/名称/日期, no placeholder".
                if (success) {
                    message = "操作已完成。";
                } else {
                    message = "本次查询没有获得可展示的结果。请补充明确的业务对象、指标和时间范围后重试；"
                            + "如果这些条件已经明确，可能是当前没有匹配数据或服务暂时繁忙。";
                }
            }

            Boolean needMoreInfo = (Boolean) result.getOrDefault("needMoreInfo", false);
            String resultStatus = (String) result.get("status");

            String status;
            if (Boolean.TRUE.equals(needMoreInfo) || "NEED_MORE_INFO".equals(resultStatus)) {
                status = "NEED_MORE_INFO";
            } else if (Boolean.TRUE.equals(success)) {
                status = "SUCCESS";
            } else if (Integer.valueOf(403).equals(result.get("errorCode"))) {
                // 工具里抛的 403 (如待财审的行级拦截): 与 RBAC 拒绝同一个状态, 不再降成笼统的 FAILED / 400
                status = "PERMISSION_DENIED";
            } else {
                status = "FAILED";
            }

            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status(status)
                    .message(message)
                    .resultData(data)
                    .executedAt(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            // 🔴 2026-08-19: 这里原来返回 status("SUCCESS") + message("执行完成") ——
            //    **解析失败被报成执行成功**。这是「什么指令都操作成功」最直接的一处:
            //    工具结果读不懂时, 我们对用户说的是"干成了"。
            //    ⚠️ 它同时还骗过运维: 日志里是一条 error, 而用户侧和 status 都是成功。
            //    改成如实 FAILED —— 本仓形态 A¹⁰: 读不到就炸, 比读到一个假的成功好。
            log.error("解析 Tool 结果失败: json={}, error={}", resultJson, e.getMessage());
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("操作没有完成：系统读不懂这次的执行结果，因此无法确认它是否生效。"
                            + "请先到对应模块核对数据，再决定是否重试；如果反复出现请联系管理员。")
                    .resultData(resultJson)
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    // ==================== 内部方法 ====================

    /**
     * Normal return is not a success verdict. Require explicit Boolean true,
     * matching the public response parser; absent, malformed and negative
     * verdicts must not enter success audit/cache or result-correction retries.
     */
    private boolean isToolResultSuccessful(String resultJson) {
        try {
            Map<?, ?> result = objectMapper.readValue(resultJson, Map.class);
            return result != null && Boolean.TRUE.equals(result.get("success"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private String extractToolFailureMessage(String resultJson) {
        try {
            Map<?, ?> result = objectMapper.readValue(resultJson, Map.class);
            Object error = result.get("error");
            if (error != null) return String.valueOf(error);
            Object data = result.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                Object code = dataMap.get("code");
                if (code != null) return String.valueOf(code);
            }
        } catch (Exception ignored) {
            // parseToolResultToResponse remains the source of the user-facing fallback.
        }
        return "工具结果未明确返回 success=true，无法确认执行成功";
    }

    /**
     * 使用 LLM Tool Calling 从用户输入中提取参数
     */
    private IntentExecuteResponse executeThroughMigrationGateway(
            ToolExecutor selectedTool,
            String factoryId,
            Map<String, Object> parameters,
            AIIntentConfig intent,
            Long userId,
            String userRole) {
        String toolName = selectedTool.getToolName();
        boolean writeTool = writeGuardService != null && writeGuardService.isWriteTool(selectedTool);
        boolean gatewayInvoked = false;
        try {
            String expectedVersion = "material_stock_summary".equals(toolName)
                    || (toolRuntimeRegistry != null && toolRuntimeRegistry.isGoverned(toolName))
                    ? selectedTool.getVersion()
                    : legacyToolMigrationRegistry.expectedVersion(toolName)
                            .orElseThrow(() -> new SecurityException(
                                    "Legacy migration manifest binding disappeared"));
            String callId = "intent-dispatch-" + java.util.UUID.randomUUID();
            ExecutionPrincipal principal = authenticatedToolPrincipalFactory.create(
                    factoryId, userId, userRole);
            ToolExecutionCommand command = new ToolExecutionCommand(
                    callId,
                    callId,
                    callId + "-trace",
                    toolName,
                    expectedVersion,
                    objectMapper.valueToTree(parameters),
                    principal,
                    ToolExecutionSource.AI_INTENT_DISPATCH,
                    ToolExecutionMode.EXECUTE,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Instant.now().plusSeconds(30));
            gatewayInvoked = true;
            ToolExecutionResult gatewayResult = toolExecutionGateway.execute(command);
            if (writeTool && (gatewayResult.status() == com.cretas.aims.ai.tool.gateway.ToolExecutionStatus.OUTCOME_UNKNOWN
                    || flaggedOutcomeUnknown(gatewayResult.payload()))) {
                log.error("Gateway write outcome unknown: tool={}, status={}", toolName, gatewayResult.status());
                return writeOutcomeUnknownResponse(intent, selectedTool);
            }

            IntentExecuteResponse response;
            if (gatewayResult.payload().isObject()
                    && gatewayResult.payload().path("success").isBoolean()) {
                response = parseToolResultToResponse(
                        objectMapper.writeValueAsString(gatewayResult.payload()), intent);
            } else {
                response = IntentExecuteResponse.builder()
                        .intentRecognized(true)
                        .intentCode(intent.getIntentCode())
                        .intentName(intent.getIntentName())
                        .intentCategory(intent.getIntentCategory())
                        .status(gatewayResult.status().name())
                        .message(gatewayResult.message())
                        .formattedText(gatewayResult.message())
                        .executedAt(LocalDateTime.now())
                        .build();
            }
            Map<String, Object> metadata = response.getMetadata() == null
                    ? new HashMap<>()
                    : new HashMap<>(response.getMetadata());
            metadata.put("toolName", toolName);
            metadata.put("executionBoundary", "TOOL_EXECUTION_GATEWAY");
            metadata.put("gatewayStatus", gatewayResult.status().name());
            metadata.put("gatewayAuditEventId", gatewayResult.auditEventId());
            putLastAction(metadata, response.getStatus(), response.getResultData(), intent, toolName, parameters);
            response.setMetadata(metadata);
            return response;
        } catch (Exception gatewayFailure) {
            // A selected migration call never re-enters legacy cache/retry/correction paths.
            log.error("D11B Gateway migration execution failed: tool={}",
                    toolName, gatewayFailure);
            if (writeTool && gatewayInvoked) {
                // 网关已经开始执行写工具后才抛: 写入可能已落下, 不能报失败
                return writeOutcomeUnknownResponse(intent, selectedTool);
            }
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .intentCode(intent.getIntentCode())
                    .intentName(intent.getIntentName())
                    .intentCategory(intent.getIntentCategory())
                    .status("FAILED")
                    .message("Tool gateway execution failed")
                    .formattedText("Tool gateway execution failed")
                    .metadata(Map.of(
                            "toolName", toolName,
                            "executionBoundary", "TOOL_EXECUTION_GATEWAY",
                            "gatewayStatus", "FAILED"))
                    .executedAt(LocalDateTime.now())
                    .build();
        }
    }

    /**
     * 工具执行成功后, 把「这一步做了什么」写进 {@code metadata.lastAction} —— App 带回来,
     * 下一句「好了 / 下一个 / 再加十公斤 / 批了吧」才有东西可解读 (见 {@link FollowUpResolver})。
     * 只在 SUCCESS 时产出; 参数只留白名单里的标识 / 数量键 ({@link LastActionContract}),
     * 主体键与自由文本一律不出去。
     */
    private static void putLastAction(Map<String, Object> metadata, String status, Object resultData,
                                      AIIntentConfig intent, String toolName, Map<String, Object> executedParams) {
        if (!"SUCCESS".equals(status)) {
            return;
        }
        Map<String, Object> lastAction = LastActionContract.build(
                intent, toolName, executedParams, resultData, System.currentTimeMillis());
        if (lastAction != null) {
            metadata.put(LastActionContract.CONTEXT_KEY, lastAction);
        }
    }

    /** 确认按钮点回来的追问 (按钮参数里带着规则 id, App 原样转进 context)。 */
    private static boolean isFollowUpConfirmation(IntentExecuteRequest request) {
        return request != null && request.getContext() != null
                && request.getContext().get(FollowUpResolver.FOLLOW_UP_RULE_KEY) instanceof String rule
                && !rule.isBlank();
    }

    /**
     * CRITIC 纠错给回的整套参数同样是 LLM 产出: 改动过的值也要过 {@link LlmParameterGrounding},
     * 没落地的退回原值 (原来没有就去掉) —— 纠错可以改格式、换成用户说过的数, 不能凭空造数或写备注。
     *
     * <p>整套参数被换掉之后, 主体身份 (factoryId / userId / role 及其别名) 按主路径同一个
     * {@link TrustedExecutionContext#enforcePrincipal} 重新绑定: 纠错代理给回的 factoryId=F999 之类
     * 不许到达工具 (以前换完参数就直接序列化执行, 主体由 LLM 输出决定)。两条重试路径都走这里。
     */
    private void replaceWithGroundedCorrection(Map<String, Object> params, Map<String, Object> corrected,
                                               ToolExecutor tool, String factoryId, Long userId,
                                               String userRole) {
        Map<String, Object> previous = new HashMap<>(params);
        Map<String, Object> changed = new HashMap<>();
        for (Map.Entry<String, Object> e : corrected.entrySet()) {
            if (!java.util.Objects.equals(previous.get(e.getKey()), e.getValue())) {
                changed.put(e.getKey(), e.getValue());
            }
        }
        Object userInput = previous.get("userInput");
        LlmParameterGrounding.Result grounded = LlmParameterGrounding.ground(
                changed, previous, userInput instanceof String s ? s : null, tool.getParametersSchema());
        params.clear();
        params.putAll(corrected);
        params.putAll(grounded.accepted());
        for (String key : grounded.dropped().keySet()) {
            if (previous.containsKey(key)) {
                params.put(key, previous.get(key));
            } else {
                params.remove(key);
            }
        }
        if (!grounded.dropped().isEmpty()) {
            log.info("纠错参数未落地, 已退回原值: tool={}, dropped={}", tool.getToolName(), grounded.dropped());
        }
        TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);
    }

    /**
     * 没经过识别管线的分支 (短语直达 / 显式意图) 补跑的预处理: 只跑规则 —— 时间归一
     * ({@code preprocessRulesOnly}) 加上有会话时的指代消解 ({@code resolveReferences}), 不做大模型改写。
     * 两个服务没装配 (切片测试) 或用户原话为空时返回 null, 与以前一样不补参数。
     */
    PreprocessedQuery shortcutPreprocess(IntentExecuteRequest request, String factoryId, Long userId) {
        if (queryPreprocessorService == null || request == null
                || request.getUserInput() == null || request.getUserInput().isBlank()) {
            return null;
        }
        try {
            PreprocessedQuery pq = queryPreprocessorService.preprocessRulesOnly(request.getUserInput());
            if (pq == null) {
                return null;
            }
            Map<String, PreprocessedQuery.ResolvedReference> refs = new HashMap<>();
            if (pq.getResolvedReferences() != null) {
                refs.putAll(pq.getResolvedReferences());
            }
            if (request.getSessionId() != null && conversationMemoryService != null) {
                com.cretas.aims.dto.conversation.ConversationContext context =
                        conversationMemoryService.getContext(request.getSessionId());
                if (context != null) {
                    com.cretas.aims.service.QueryPreprocessorService.ReferenceResolutionResult resolved =
                            queryPreprocessorService.resolveReferences(request.getUserInput(), context);
                    if (resolved != null && resolved.isHasResolutions() && resolved.getResolvedEntities() != null) {
                        resolved.getResolvedEntities().forEach((said, e) -> refs.put(said,
                                PreprocessedQuery.ResolvedReference.of(
                                        e.getEntityType(), e.getEntityId(), e.getEntityName(), said)));
                    }
                }
            }
            pq.setResolvedReferences(refs);
            return pq;
        } catch (RuntimeException e) {
            // 补跑失败不拦查询 (与以前「这条分支不补参数」一致), 但要留痕, 不静默
            log.warn("短语直达分支的规则预处理失败, 本次不补时间/指代参数: tool input='{}', error={}",
                    request.getUserInput(), e.toString());
            return null;
        }
    }

    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.cretas.aims.repository.UserRepository priceUserRepository;

    @org.springframework.context.annotation.Lazy
    @Autowired(required = false)
    private com.cretas.aims.service.PermissionService pricePermissionService;

    /**
     * 直调分支执行工具时的价格可见性: 与 Gateway (ToolPrincipalPolicy) 同三项 ——
     * 默认价格权限、采购价 (按权限配置, S5-12)、采购或财务价。用户查不到 ⇒ 全部隐藏 (fail closed)。
     * 两个依赖没装配 (切片测试) 时不改上下文。
     */
    com.cretas.aims.security.PriceSensitiveContext.Scope priceScopeFor(Long userId) {
        if (priceUserRepository == null || pricePermissionService == null) {
            return () -> { };
        }
        java.util.Set<String> hidden = new java.util.HashSet<>();
        com.cretas.aims.entity.User user = userId == null ? null : priceUserRepository.findById(userId).orElse(null);
        com.cretas.aims.security.PriceVisibility visibility =
                new com.cretas.aims.security.PriceVisibility(pricePermissionService);
        if (user == null || !pricePermissionService.hasPermission(user,
                com.cretas.aims.security.PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION)) {
            hidden.add(com.cretas.aims.security.PriceFieldResponseAdvice.PRICE_VIEW_PERMISSION);
        }
        if (user == null || !visibility.canView(user, com.cretas.aims.security.PriceSensitive.PURCHASE)) {
            hidden.add(com.cretas.aims.security.PriceSensitive.PURCHASE);
        }
        if (user == null || !visibility.canView(user, com.cretas.aims.security.PriceSensitive.PURCHASE_OR_FINANCE)) {
            hidden.add(com.cretas.aims.security.PriceSensitive.PURCHASE_OR_FINANCE);
        }
        return com.cretas.aims.security.PriceSensitiveContext.scopedSpecs(hidden);
    }

    /** 工具 schema 顶层开关: 只有可选参数的工具要不要在执行前让大模型抽可选参数 (默认不抽)。 */
    public static final String EXTRACT_OPTIONAL_FLAG = "x-llm-extract-optional";

    Map<String, Object> extractParametersWithLLM(String userInput, ToolExecutor tool,
                                                          Map<String, Object> existingParams) {
        return extractParametersWithLLM(null, userInput, tool, existingParams);
    }

    /**
     * @param factoryId 服务端认定的工厂 (路径 / 可信执行上下文, ⛔ 不取请求 context 里客户端给的值):
     *                  按这家的工艺保密产品名脱敏原话。平台账号替某厂执行时必须带, 否则外发被拒 (不抽参)。
     */
    Map<String, Object> extractParametersWithLLM(String factoryId, String userInput, ToolExecutor tool,
                                                 Map<String, Object> existingParams) {
        Map<String, Object> extractedParams = new HashMap<>();
        try {
            Map<String, Object> parametersSchema = tool.getParametersSchema();
            if (parametersSchema == null || parametersSchema.isEmpty()) {
                return extractedParams;
            }

            @SuppressWarnings("unchecked")
            List<String> requiredParams = (List<String>) parametersSchema.get("required");
            // Optional-only tools still carry user-specified filters (period, comparison, days).
            Map<String, Object> properties = parametersSchema.get("properties") instanceof Map<?, ?> p
                    ? (Map<String, Object>) p : Map.of();
            // ⛔ 只对显式声明 x-llm-extract-optional=true 的工具抽可选参数 (organizer 审 #967):
            // 这里每次工具执行都会进来, 不加开关等于给所有只有可选参数的读工具每次多一次大模型调用 (延迟 + 费用)。
            boolean optionalOnly = requiredParams == null || requiredParams.isEmpty();
            if (optionalOnly && !Boolean.TRUE.equals(parametersSchema.get(EXTRACT_OPTIONAL_FLAG))) {
                return extractedParams;
            }
            Collection<String> candidates = optionalOnly ? properties.keySet() : requiredParams;
            List<String> missingParams = candidates.stream()
                    .filter(p -> !existingParams.containsKey(p) ||
                                 existingParams.get(p) == null ||
                                 (existingParams.get(p) instanceof String &&
                                  ((String) existingParams.get(p)).trim().isEmpty()))
                    .collect(Collectors.toList());

            if (missingParams.isEmpty()) {
                return extractedParams;
            }

            log.info("工具 {} 缺少参数 {}，启动 LLM 参数提取", tool.getToolName(), missingParams);

            Tool extractionTool = Tool.of(
                    "extract_parameters",
                    "从用户输入中提取 " + tool.getToolName() + " 操作所需的参数",
                    parametersSchema
            );

            String systemPrompt = String.format("""
                你是一个参数提取助手。你的任务是从用户的自然语言输入中提取操作所需的参数。

                当前操作: %s
                操作描述: %s

                请仔细分析用户输入，提取其中包含的参数值。
                - 如果用户明确提供了某个参数的值，请提取它
                - 如果用户没有提供某个参数，不要猜测或编造，直接忽略该参数（不要填 0 或默认值）
                - 参数值应该是用户原文中的信息，不要修改或翻译
                - 同一个数字只填给一个参数；备注、原因、说明这类文字参数只照抄用户原话，
                  用户没说就不填，不要写你自己的分析或提示

                请使用 extract_parameters 工具返回提取的参数。
                """, tool.getToolName(), tool.getDescription() != null ? tool.getDescription() : "执行业务操作");

            // 原话里的号码 / 人名 / 公司名 / 保密产品名换代号再发; 抽出的参数换回真值后再做落地校验; 脱敏不了就不抽
            LlmEgressRedactor.Result redacted = llmEgressGuard.texts(PARAM_EGRESS_SITE, factoryId, systemPrompt, userInput);
            if (redacted.refused()) {
                return extractedParams;
            }
            ChatCompletionResponse response = dashScopeClient.chatWithTools(
                    redacted.text(0), redacted.text(1), List.of(extractionTool));

            if (dashScopeClient.hasToolCalls(response)) {
                ToolCall toolCall = dashScopeClient.getFirstToolCall(response);
                if (toolCall != null && toolCall.getFunction() != null) {
                    String argumentsJson = redacted.restoreJsonText(toolCall.getFunction().getArguments());
                    if (argumentsJson != null && !argumentsJson.isEmpty()) {
                        try {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> args = objectMapper.readValue(argumentsJson, Map.class);
                            Map<String, Object> raw = new HashMap<>();
                            for (Map.Entry<String, Object> entry : args.entrySet()) {
                                if (entry.getValue() != null &&
                                    !(entry.getValue() instanceof String && ((String) entry.getValue()).isEmpty())) {
                                    raw.put(entry.getKey(), entry.getValue());
                                }
                            }
                            // LLM 给的数字 / 备注必须在用户原话或上下文里落得了地, 否则丢掉 (参数保持缺失,
                            // 必填的由工具走 NEED_MORE_INFO 追问)。规则只有一份, 见 LlmParameterGrounding。
                            LlmParameterGrounding.Result grounded = LlmParameterGrounding.ground(
                                    raw, existingParams, userInput, parametersSchema);
                            extractedParams.putAll(grounded.accepted());
                            if (!grounded.dropped().isEmpty()) {
                                log.info("LLM 参数未落地, 已丢弃: tool={}, dropped={}",
                                        tool.getToolName(), grounded.dropped());
                            }
                            log.info("LLM 参数提取成功: tool={}, extracted={}", tool.getToolName(), extractedParams.keySet());
                        } catch (JsonProcessingException e) {
                            log.warn("解析 LLM 返回的参数失败: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.error("LLM 参数提取异常: tool={}, error={}", tool.getToolName(), e.getMessage(), e);
        }
        return extractedParams;
    }

    /**
     * D7: Quick rule-based check — skip LLM validation when result is clearly valid.
     */
    boolean isResultClearlyValid(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) return false;
        if (resultJson.length() < 10) return false;
        try {
            Object parsed = objectMapper.readValue(resultJson, Object.class);
            if (parsed instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) parsed;
                if (map.containsKey("data") && map.get("data") != null) {
                    Object data = map.get("data");
                    if (data instanceof List && !((List<?>) data).isEmpty()) return true;
                    if (data instanceof Map && !((Map<?, ?>) data).isEmpty()) return true;
                }
                if (map.containsKey("content") && map.get("content") != null) return true;
                if (map.containsKey("result") && map.get("result") != null) return true;
                if (Boolean.TRUE.equals(map.get("success"))) return true;
            }
            if (parsed instanceof List && !((List<?>) parsed).isEmpty()) return true;
        } catch (Exception e) {
            return false;
        }
        return false;
    }

    /**
     * 构建 "无 Tool" 响应
     *
     * <p>Sprint 11 Round 4 P1 fix (Bug 2) — surface diagnostic info instead of
     * generic "暂不支持此类型的意图执行: RESTAURANT_OPS". Per Sprint 10.5 PR #182
     * pattern (Skill 失败 surface 真实错误) and .claude/rules/fool-proof-design.md
     * "跨规则铁律 4 位一体" — error message must include actionable next-step,
     * not generic category name.
     *
     * <p>Triggered when:
     * <ul>
     *   <li>intent.tool_name was bound but Tool not found in ToolRegistry</li>
     *   <li>intent.tool_name=NULL AND no Skill routed AND no dynamic Tool selection</li>
     * </ul>
     *
     * <p>The customer-facing message tells the user the intent was recognized
     * (so they know AI understood them) but execution is not configured yet,
     * with an actionable hint to contact admin / try alternative phrasing.
     */
    public IntentExecuteResponse buildNoToolResponse(AIIntentConfig intent) {
        String intentName = intent.getIntentName() != null ? intent.getIntentName()
                : intent.getIntentCode();
        String boundTool = intent.getToolName();
        String diagnostic;
        String customerMessage;
        if (boundTool != null && !boundTool.isBlank()) {
            // tool_name configured but Tool not registered — likely missing @Component
            // or naming mismatch.
            diagnostic = String.format(
                "意图\"%s\"(%s)已识别，但配置的 Tool [%s] 未注册。"
                + "请联系管理员检查 Tool 注册或意图配置。",
                intentName, intent.getIntentCode(), boundTool);
            customerMessage = "已理解您的问题，但这项分析能力暂时不可用。请稍后重试；"
                    + "如果持续出现，请联系管理员检查分析能力配置。";
        } else {
            // tool_name=NULL and all dispatch paths exhausted — intent recognized
            // but no executor wired (Tool/Skill/dynamic selection all failed).
            diagnostic = String.format(
                "意图\"%s\"(%s)已识别，但暂未配置执行器（类别: %s）。"
                + "请联系管理员配置 Tool 或 Skill，或尝试更具体的提问方式。",
                intentName, intent.getIntentCode(), intent.getIntentCategory());
            customerMessage = "已理解您的问题，但当前还不能完成这项分析。"
                    + "请补充具体的业务对象、指标和时间范围后重试；如果条件已经明确，请联系管理员。";
        }
        log.warn("[ToolDispatch] customer-safe no-tool response; diagnostic={}", diagnostic);
        return IntentExecuteResponse.builder()
                .intentRecognized(true)
                .intentCode(intent.getIntentCode())
                .intentName(intent.getIntentName())
                .intentCategory(intent.getIntentCategory())
                .status("FAILED")
                .message(customerMessage)
                .formattedText(customerMessage)
                .executedAt(LocalDateTime.now())
                .build();
    }
}
