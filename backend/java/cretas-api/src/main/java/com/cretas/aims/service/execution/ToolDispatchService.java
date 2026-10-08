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

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
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
        try {
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
            request.setContext(com.cretas.aims.ai.tool.WriteGuardService
                    .withoutServerConfirmationMarker(request.getContext()));

            // 1.5. 预览模式（不支持预览的请求已在上方 fail closed）
            if (Boolean.TRUE.equals(request.getPreviewOnly())) {
                log.info("Tool preview 模式: tool={}, intentCode={}", tool.getToolName(), intent.getIntentCode());
                return executeToolPreview(tool, factoryId, request, intent, userId, userRole);
            }

            // 2. 构建 ToolCall
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
            params.put("userInput", userInputToUse);
            params.put("intentCode", intent.getIntentCode());

            // 2.5. 从预处理结果中提取解析的引用
            if (matchResult != null && matchResult.getPreprocessedQuery() != null) {
                PreprocessedQuery pq = matchResult.getPreprocessedQuery();
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
                        log.info("从预处理结果提取时间范围: {} ~ {}", timeRange.getStart(), timeRange.getEnd());
                    }
                }
            }

            // 2.7. 优先使用已学习的规则提取参数
            Map<String, Object> parametersSchema = tool.getParametersSchema();
            @SuppressWarnings("unchecked")
            List<String> requiredParams = parametersSchema != null ?
                    (List<String>) parametersSchema.get("required") : null;

            Map<String, Object> ruleExtractedParams = new HashMap<>();
            if (requiredParams != null && !requiredParams.isEmpty()) {
                List<String> missingParams = requiredParams.stream()
                        .filter(p -> !params.containsKey(p) ||
                                     params.get(p) == null ||
                                     (params.get(p) instanceof String && ((String) params.get(p)).trim().isEmpty()))
                        .collect(Collectors.toList());

                if (!missingParams.isEmpty()) {
                    ruleExtractedParams = parameterExtractionLearningService.extractWithLearnedRules(
                            factoryId, intent.getIntentCode(), userInputToUse, missingParams);

                    if (!ruleExtractedParams.isEmpty()) {
                        params.putAll(ruleExtractedParams);
                        log.info("使用学习规则提取参数: {} (无需调用 LLM)", ruleExtractedParams.keySet());
                    }
                }
            }

            // 2.8. LLM 提取剩余参数
            Map<String, Object> llmExtractedParams = extractParametersWithLLM(userInputToUse, tool, params);
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

            // Learned rules, LLM extraction and resolved references are all untrusted inputs.
            // Re-bind the authenticated principal immediately before hashing and Tool execution.
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);

            // This approved stock READ tool always needs Gateway identity, row and price scope,
            // including ordinary mobile SSE calls while the broad migration flag is disabled.
            // Other tools retain the independent default-off legacy migration allowlist.
            // A selected Gateway attempt never enters legacy cache/retry on denial or failure.
            if ("material_stock_summary".equals(tool.getToolName())
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
            if (redundancyService.isRedundant(sessionId, tool.getToolName(), params)) {
                log.info("检测到冗余调用，跳过执行: tool={}, session={}", tool.getToolName(), sessionId);
                Optional<String> cachedResult = redundancyService.getCachedResult(sessionId, tool.getToolName(), params);
                if (cachedResult.isPresent()) {
                    // Sprint 12: parse cached JSON to extract message + data, don't leak raw JSON as message text.
                    IntentExecuteResponse parsed = parseToolResultToResponse(cachedResult.get(), intent);
                    String parsedMsg = parsed.getMessage() != null ? parsed.getMessage() : "操作已完成。";
                    Map<String, Object> cachedMetadata = new HashMap<>();
                    cachedMetadata.put("cached", true);
                    // 冗余缓存复用的也是一次成功执行 (同参数), 一样产出 lastAction
                    putLastAction(cachedMetadata, parsed.getStatus(), parsed.getResultData(),
                            intent, tool.getToolName(), params);
                    return IntentExecuteResponse.builder()
                            .intentRecognized(true)
                            .intentCode(intent.getIntentCode())
                            .intentName(intent.getIntentName())
                            .intentCategory(intent.getIntentCategory())
                            .status("SUCCESS")
                            .message(parsedMsg)
                            .resultData(parsed.getResultData())
                            .formattedText(parsed.getFormattedText())
                            .metadata(cachedMetadata)
                            .executedAt(LocalDateTime.now())
                            .build();
                }
            }

            // 5. 执行 Tool（带自动重试）
            final int MAX_RETRIES = 3;
            String resultJson = null;
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
                    resultJson = tool.execute(toolCall, context);
                    long executionTime = System.currentTimeMillis() - startTime;
                    totalExecutionTime += executionTime;

                    // 执行成功，记录
                    try {
                        ToolCallRecord record = ToolCallRecord.builder()
                                .sessionId(sessionId)
                                .factoryId(factoryId)
                                .toolName(tool.getToolName())
                                .toolParameters(argumentsJson)
                                .parametersHash(redundancyService.computeParametersHash(params))
                                .executionStatus(ToolCallRecord.ExecutionStatus.SUCCESS)
                                .executionTimeMs((int) executionTime)
                                .retryCount(attempt - 1)
                                .recovered(attempt > 1)
                                .build();
                        ToolCallRecord savedRecord = redundancyService.recordToolCall(record);
                        if (savedRecord != null && resultJson != null) {
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
                    if (resultJson != null && attempt < MAX_RETRIES) {
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
                        extractParametersWithLLM(request.getUserInput(), tool, params);
                if (!llmExtractedParams.isEmpty()) {
                    params.putAll(llmExtractedParams);
                    log.info("Preview 路径 LLM 抽取参数: {}", llmExtractedParams.keySet());
                }
            } catch (Exception ex) {
                log.warn("Preview 路径参数抽取失败 (继续用已有 params): {}", ex.getMessage());
            }

            // Preview must expose the same principal-bound arguments as real execution.
            TrustedExecutionContext.enforcePrincipal(params, factoryId, userId, userRole);

            String argumentsJson = objectMapper.writeValueAsString(params);
            ToolCall toolCall = ToolCall.of(
                    java.util.UUID.randomUUID().toString(),
                    tool.getToolName(),
                    argumentsJson
            );

            Map<String, Object> context = TrustedExecutionContext.merge(
                    null, factoryId, userId, userRole);
            context.put("intentConfig", intent);

            String resultJson = tool.preview(toolCall, context);

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
                    com.cretas.aims.entity.intent.IntentPreviewToken boundToken =
                            previewTokenService.createBoundToken(
                                    new PreviewTokenService.BoundTokenRequest(
                                            factoryId, userId, null,
                                            intent.getIntentCode(), intent.getIntentName(),
                                            tool.getToolName(), tool.getVersion(),
                                            ToolExecutionMode.EXECUTE,
                                            null, null, null,
                                            params,
                                            tokenCurrentValues != null ? tokenCurrentValues : java.util.Map.of(),
                                            tokenNewValues != null ? tokenNewValues : java.util.Map.of(),
                                            300));
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

    /** 预览被业务规则拦下 (canDo=false) 或预览本身失败 (success=false) 时的状态。 */
    static final String PREVIEW_BLOCKED = "PREVIEW_BLOCKED";

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
        try {
            String expectedVersion = "material_stock_summary".equals(toolName)
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
            ToolExecutionResult gatewayResult = toolExecutionGateway.execute(command);

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

    /** 工具 schema 顶层开关: 只有可选参数的工具要不要在执行前让大模型抽可选参数 (默认不抽)。 */
    public static final String EXTRACT_OPTIONAL_FLAG = "x-llm-extract-optional";

    Map<String, Object> extractParametersWithLLM(String userInput, ToolExecutor tool,
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

            ChatCompletionResponse response = dashScopeClient.chatWithTools(
                    systemPrompt, userInput, List.of(extractionTool));

            if (dashScopeClient.hasToolCalls(response)) {
                ToolCall toolCall = dashScopeClient.getFirstToolCall(response);
                if (toolCall != null && toolCall.getFunction() != null) {
                    String argumentsJson = toolCall.getFunction().getArguments();
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
