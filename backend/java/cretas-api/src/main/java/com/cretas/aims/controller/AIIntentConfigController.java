package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentConfirmationRequest;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.ai.ParameterConfirmationRequest;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.intent.CleanupRequest;
import com.cretas.aims.dto.intent.IntentFeedbackRequest;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.KeywordEffectiveness;
import com.cretas.aims.utils.JwtUtil;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.entity.learning.ParameterExtractionRule;
import com.cretas.aims.entity.config.AIIntentConfigHistory;
import com.cretas.aims.utils.CookieAuthHelper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.cretas.aims.annotation.RateLimit;
import com.cretas.aims.annotation.RateLimit.LimitType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.util.RetiredAnalysisContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * AI意图配置控制器
 *
 * 提供AI意图识别配置的管理API:
 * - 意图配置的CRUD操作
 * - 意图识别测试
 * - 分类和权限查询
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-31
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai-intents")
@RequiredArgsConstructor
@Tag(name = "AI意图配置", description = "AI意图识别配置管理API")
public class AIIntentConfigController {

    private final AIIntentService aiIntentService;
    private final IntentExecutorService intentExecutorService;
    private final KeywordEffectivenessService keywordEffectivenessService;
    private final IntentConfigRollbackService rollbackService;
    private final ParameterExtractionLearningService parameterExtractionLearningService;
    private final JwtUtil jwtUtil;
    private final IntentConfigManagementService intentConfigManagementService;

    /** 意图配置写入只按路径工厂落行 (复审 H-A, 2026-10-29); 字段注入, 不改既有构造参数。 */
    @org.springframework.beans.factory.annotation.Autowired
    private com.cretas.aims.service.intent.IntentConfigWriteGuard intentConfigWriteGuard;

    /**
     * Sprint 11.5 P0 fix (2026-05-23): Extract JWT token from request supporting BOTH
     * Bearer header (mobile clients) AND HttpOnly cookie (web admin clients).
     *
     * <p>Pre-fix: all 9 endpoints used {@code @RequestHeader("Authorization") String authorization}
     * which is mandatory by Spring default. Web admin sends cookie-only requests (no Authorization
     * header) → Spring throws {@code MissingRequestHeaderException} → falls to generic
     * {@link com.cretas.aims.exception.GlobalExceptionHandler#handleRuntimeException} → HTTP 500
     * "系统处理异常 (追踪码: XXX)". 100% of web-admin UI clients hitting AI intent endpoints
     * saw cryptic 500. {@link com.cretas.aims.config.JwtAuthInterceptor#extractToken} already
     * supports both paths for authentication; the controller-level header dependency was the gap.
     *
     * <p>Post-fix: this helper mirrors {@link com.cretas.aims.config.JwtAuthInterceptor#extractToken}.
     * Bearer header takes priority (no behavior change for mobile); cookie fallback unlocks web admin.
     *
     * @return token string, or null if neither header nor cookie present (caller should treat as auth failure)
     */
    private String extractToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7).trim();
            if (!token.isEmpty()) {
                return token;
            }
        }
        return CookieAuthHelper.extractCookieValue(request, CookieAuthHelper.ACCESS_TOKEN_COOKIE);
    }

    private String requireFactoryIntentAccess(String factoryId, String userRole) {
        if (RetiredAnalysisContext.isRetiredIdentity(factoryId, userRole)) {
            RetiredAnalysisContext.requireFactory(factoryId, "RESTAURANT", userRole);
        }
        final String domain;
        try {
            domain = intentConfigManagementService.resolveBusinessDomain(factoryId);
        } catch (RuntimeException e) {
            throw new BusinessException(503, "无法确认租户业务类型，暂不可执行意图")
                    .withCode("TENANT_TYPE_UNAVAILABLE");
        }
        RetiredAnalysisContext.requireFactory(factoryId, domain, userRole);
        return domain;
    }

    // ==================== 意图查询 ====================

    @GetMapping
    @Operation(summary = "获取所有意图配置", description = "获取所有启用的AI意图配置列表（租户隔离）")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> getAllIntents(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<AIIntentConfig> intents = aiIntentService.getAllIntents(factoryId);
        return ResponseEntity.ok(ApiResponse.success(intents));
    }

    @GetMapping("/category/{category}")
    @Operation(summary = "按分类获取意图", description = "根据分类获取意图配置列表（租户隔离）")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> getIntentsByCategory(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图分类 (ANALYSIS, DATA_OP, FORM, SCHEDULE, SYSTEM)")
            @PathVariable String category) {

        List<AIIntentConfig> intents = aiIntentService.getIntentsByCategory(factoryId, category);
        return ResponseEntity.ok(ApiResponse.success(intents));
    }

    @GetMapping("/categories")
    @Operation(summary = "获取所有分类", description = "获取所有可用的意图分类列表（租户隔离）")
    public ResponseEntity<ApiResponse<List<String>>> getAllCategories(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        List<String> categories = aiIntentService.getAllCategories(factoryId);
        return ResponseEntity.ok(ApiResponse.success(categories));
    }

    @GetMapping("/sensitivity/{level}")
    @Operation(summary = "按敏感度获取意图", description = "根据敏感度级别获取意图配置（租户隔离）")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> getIntentsBySensitivity(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "敏感度级别 (LOW, MEDIUM, HIGH, CRITICAL)")
            @PathVariable String level) {

        List<AIIntentConfig> intents = aiIntentService.getIntentsBySensitivity(factoryId, level);
        return ResponseEntity.ok(ApiResponse.success(intents));
    }

    @GetMapping("/keyword-stats")
    @Operation(summary = "获取关键词效果统计", description = "获取指定意图的关键词效果统计数据")
    public ResponseEntity<ApiResponse<List<KeywordEffectiveness>>> getKeywordStats(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @RequestParam(required = false) String intentCode,
            @Parameter(description = "效果阈值") @RequestParam(required = false, defaultValue = "0") java.math.BigDecimal threshold) {

        List<KeywordEffectiveness> stats = keywordEffectivenessService.getEffectiveKeywords(
                factoryId, intentCode, threshold);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @GetMapping("/keyword-stats/count")
    @Operation(summary = "获取关键词数量", description = "获取指定意图的关键词数量")
    public ResponseEntity<ApiResponse<Long>> getKeywordCount(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @RequestParam(required = false) String intentCode) {

        long count = keywordEffectivenessService.countKeywords(factoryId, intentCode);
        return ResponseEntity.ok(ApiResponse.success(count));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/keywords/cleanup")
    @Operation(summary = "清理低效关键词", description = "清理效果评分低于阈值的关键词（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Integer>> cleanupLowEffectivenessKeywords(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody CleanupRequest request) {

        int cleaned = keywordEffectivenessService.cleanupLowEffectivenessKeywords(
                factoryId, request.getThreshold(), request.getMinNegative());
        log.info("Cleaned {} low-effectiveness keywords for factory {}", cleaned, factoryId);
        return ResponseEntity.ok(ApiResponse.success("清理完成，共删除 " + cleaned + " 个低效关键词", cleaned));
    }

    @GetMapping("/{intentCode}")
    @Operation(summary = "获取单个意图", description = "根据意图代码获取意图配置详情")
    public ResponseEntity<ApiResponse<AIIntentConfig>> getIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        return aiIntentService.getIntentByCode(factoryId, intentCode)
                .map(i -> ResponseEntity.ok(ApiResponse.success(i)))
                .orElseThrow(() -> new BusinessException(404, "意图配置不存在: " + intentCode).withHint("请检查 ID 是否正确"));
    }

    // ==================== 意图识别 ====================

    // Sprint 11 Round 2 (2026-05-22): removed @RequirePermission({"system:read_write"}).
    // Intent recognition is read-only diagnostic — gated by JWT auth alone.
    // Per-intent sensitivity is enforced by AIIntentService.hasPermission() in
    // IntentExecutionOrchestrator (line 240) — controller-level system:read_write
    // was blocking all non-super-admin roles from even probing what they can do.
    @PostMapping("/recognize")
    @Operation(summary = "测试意图识别", description = "输入文本测试意图识别结果（支持操作类型检测）")
    public ResponseEntity<ApiResponse<IntentRecognitionResult>> recognizeIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentRecognitionRequest request,
            HttpServletRequest httpRequest) {

        log.debug("Recognizing intent for input: {}", request.getUserInput());

        // 🔴 2026-10-07: 这里原来传 (userId=null, role=null)。识别层的 Python 分支把 userId 下发给
        // /api/ai/intent/match, 而 Python 侧 userId 必填 —— 缺了就是每次 422 (还会被熔断器记成失败)。
        // /execute 和 SSE 入口都带了, 只有这个诊断入口漏传, 所以线上日志里该接口 0 次 422。
        // 身份只认 token (Bearer / cookie), ⛔ 不取请求体里客户端自报的字段; 解不出来就拒绝, 不用 0 / null 顶替。
        String token = extractToken(httpRequest);
        Long userId = token == null ? null : jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new BusinessException(401, "无法确定当前登录用户，意图识别已取消")
                    .withHint("请重新登录后再试");
        }
        String userRole = jwtUtil.getRoleFromToken(token);
        requireFactoryIntentAccess(factoryId, userRole);

        // 使用带操作类型检测的增强版识别方法 (BUG-001/002 修复)
        IntentMatchResult matchResult = aiIntentService.recognizeIntentWithConfidence(
                request.getUserInput(), factoryId, 1, userId, userRole);

        IntentRecognitionResult result = new IntentRecognitionResult();
        result.setUserInput(request.getUserInput());
        result.setMatched(matchResult.hasMatch());

        if (matchResult.hasMatch()) {
            AIIntentConfig intent = matchResult.getBestMatch();
            result.setIntentCode(intent.getIntentCode());
            result.setIntentName(intent.getIntentName());
            result.setCategory(intent.getIntentCategory());
            result.setSensitivityLevel(intent.getSensitivityLevel());
            result.setQuotaCost(intent.getQuotaCost());
            result.setRequiresApproval(intent.needsApproval());
            // 额外添加置信度和匹配方法信息
            result.setConfidence(matchResult.getConfidence());
            result.setMatchMethod(matchResult.getMatchMethod() != null ?
                    matchResult.getMatchMethod().name() : null);
        }
        result.setTimingMs(matchResult.getTimingMs());

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — read-only diagnostic.
    @PostMapping("/recognize-all")
    @Operation(summary = "识别所有匹配意图", description = "获取所有可能匹配的意图列表")
    public ResponseEntity<ApiResponse<List<AIIntentConfig>>> recognizeAllIntents(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentRecognitionRequest request,
            HttpServletRequest httpRequest) {

        String token = extractToken(httpRequest);
        Long userId = token == null ? null : jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            throw new BusinessException(401, "无法确定当前登录用户，意图识别已取消")
                    .withHint("请重新登录后再试");
        }
        String userRole = jwtUtil.getRoleFromToken(token);
        requireFactoryIntentAccess(factoryId, userRole);

        List<AIIntentConfig> matchedIntents = aiIntentService.recognizeAllIntents(factoryId, request.getUserInput());
        return ResponseEntity.ok(ApiResponse.success(matchedIntents));
    }

    // ==================== 意图执行 ====================

    // Sprint 11 Round 2 (2026-05-22): removed @RequirePermission({"system:read_write"}).
    // Was a P0 — qhj_warehouse_mgr / qhj_finance_mgr / qhj_sales_mgr / qhj_operator (all
    // RES_3101_009 spec'd accounts) blocked at the controller before ever reaching the
    // intent-level permission check. Per-intent sensitivity + required_roles is now the
    // sole permission gate (enforced in IntentExecutionOrchestrator.execute line 240
    // via aiIntentService.hasPermission(intentCode, userRole) → returns FORBIDDEN
    // response when role not in intent's required_roles JSON array; intents with
    // sensitivity_level=LOW + empty required_roles allow all authenticated users).

    /**
     * 日志用的安全截断。
     *
     * <p>🔴 2026-08-10 prod: 原来这里是
     * {@code request.getUserInput().length() > 30 ? ... : ...} 直接写在 log.info
     * 的参数里。请求体缺 {@code userInput} 时 NPE → GlobalExceptionHandler 兜成
     * <b>500「系统处理异常」</b>，而**下游全是 null 安全的**
     * 下游处理对空 userInput 均有 null-safe 保护 —— 也就是说这个 500 完全来自
     * 一行日志。
     *
     * <p>🔑 判据: <b>日志语句永远不该有能力让请求失败。</b> 它在旁路上，
     * 崩了不但没记成日志，还把一个本来能正常处理的请求变成 500 —— 而 500 会
     * 污染错误监控、掩盖真正的故障。
     *
     * <p>⚠️ 这个缺陷是我自己的探针踩出来的(把字段名写成了 question)，不是用户
     * 报的。判据: <b>探针报异常时先怀疑探针，但修完探针要回头看被探的东西
     * 是不是也有问题</b> —— 这次探针确实写错了，而它顺带暴露了一个真的缺陷。
     */
    static String truncateForLog(String userInput) {
        if (userInput == null) {
            return "<null>";
        }
        return userInput.length() > 30 ? userInput.substring(0, 30) + "..." : userInput;
    }

    @PostMapping("/execute")
    @Operation(summary = "执行AI意图", description = "识别用户输入的意图并执行对应操作")
    @RateLimit(count = 240, period = 60, limitType = LimitType.USER, message = "AI请求过于频繁，请稍后再试")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> executeIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): extract token via cookie-aware helper (was @RequestHeader only)
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        removeCallerConfirmationAuthority(request);

        log.info("执行AI意图: factoryId={}, userInput={}, userId={}, role={}",
                factoryId, truncateForLog(request.getUserInput()), userId, userRole);

        IntentExecuteResponse response = intentExecutorService.execute(factoryId, request, userId, userRole);
        com.cretas.aims.service.execution.CustomerTextSanitizer.sanitize(response);
        return envelopeByInnerStatus(response);
    }

    /**
     * 按【内层 status】决定 envelope 的 success/message。
     *
     * <p>🔴 2026-08-19 (测试同学 7-31「什么指令都操作成功」): 这几个 AI 入口原来一律
     * {@code ApiResponse.success(response)}, 而那个重载把 message 写死成「操作成功」
     * ({@code ApiResponse:61})。于是 status=FAILED / NO_PERMISSION / NOT_RECOGNIZED
     * 的结果, 在 envelope 上依然写着「操作成功」——RN 端读的正是 envelope。
     *
     * <p>⛔ 不改 {@code ApiResponse.success} 本身: 全仓所有接口都在用它, 改 envelope
     * 是 repo-wide blast radius, 而我们没有覆盖那个面的回归。只收窄这一头
     * (形态 E: 宁可窄而可信, 不要宽而被关掉)。
     *
     * <p>web-admin 的 {@code useAiChat.ts} 早就在前端做过同样的解包并写明了原因;
     * 这里是把那条判断挪到**产出端**, 让所有消费者(RN / web / 第三方)都拿到同一个真相。
     */
    private ResponseEntity<ApiResponse<IntentExecuteResponse>> envelopeByInnerStatus(
            IntentExecuteResponse response) {
        if (response == null) {
            // 读不到就说读不到, ⛔ 不要兜成成功 (形态 A¹⁰)。
            return ResponseEntity.ok(ApiResponse.error("AI 未返回任何结果，请重试或换一种说法。"));
        }
        String status = response.getStatus();
        String innerMessage = response.getMessage();
        boolean hasInnerMessage = innerMessage != null && !innerMessage.isBlank();

        if (isFailureStatus(status)) {
            ApiResponse<IntentExecuteResponse> body = ApiResponse.error(
                    hasInnerMessage ? innerMessage : defaultFailureMessage(status));
            // 仍然把 data 带上 —— 前端要靠 status/候选项/澄清问题继续走流程。
            body.setData(response);
            return ResponseEntity.ok(body);
        }
        // 非失败态: envelope 文案用内层的真实描述; 没有内层文案时给中性措辞,
        // ⛔ 不写「操作成功」—— 那正是被投诉的那句。
        return ResponseEntity.ok(ApiResponse.success(hasInnerMessage ? innerMessage : "已处理", response));
    }

    /** 明确表示「这次没成」的状态。NEED_MORE_INFO 不算失败, 但它也绝不该说「操作成功」。 */
    private boolean isFailureStatus(String status) {
        return "FAILED".equals(status)
                || "NO_PERMISSION".equals(status)
                || "NOT_RECOGNIZED".equals(status);
    }

    private String defaultFailureMessage(String status) {
        if ("NO_PERMISSION".equals(status)) {
            return "当前账号没有执行该操作的权限，请联系管理员开通后重试。";
        }
        if ("NOT_RECOGNIZED".equals(status)) {
            return "没有识别出你要做的操作，请换一种说法，或补充具体的单据号、物料名和时间范围。";
        }
        return "执行失败，请稍后重试；如果反复失败请联系管理员并提供本次操作的时间。";
    }

    private boolean hasExplicitReadVeto(String input) {
        if (input == null || input.isBlank()) {
            return false;
        }
        String q = input.replaceAll("\\s+", "");
        return containsAny(q,
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
                "\u4e0d\u770b");
    }

    private boolean containsAny(String input, String... terms) {
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

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — same rationale as /execute.
    @PostMapping("/execute/multi")
    @Operation(summary = "执行多意图 (Multi-Label Classification)",
               description = "使用 Sigmoid-based 多标签分类识别并执行多个意图")
    @RateLimit(count = 240, period = 60, limitType = LimitType.USER, message = "AI请求过于频繁，请稍后再试")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> executeMultiIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        removeCallerConfirmationAuthority(request);
        log.info("执行多意图: factoryId={}, userInput={}, userId={}, role={}",
                factoryId,
                request.getUserInput().length() > 30 ?
                        request.getUserInput().substring(0, 30) + "..." : request.getUserInput(),
                userId, userRole);

        IntentExecuteResponse response = intentExecutorService.executeMultiIntent(factoryId, request, userId, userRole);
        com.cretas.aims.service.execution.CustomerTextSanitizer.sanitize(response);
        return envelopeByInnerStatus(response);
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — same rationale as /execute.
    @PostMapping(value = "/execute/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "流式执行AI意图 (SSE)", description = "通过 Server-Sent Events 实时返回执行进度")
    @RateLimit(count = 240, period = 60, limitType = LimitType.USER, message = "AI请求过于频繁，请稍后再试")
    public SseEmitter executeIntentStream(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        removeCallerConfirmationAuthority(request);
        log.info("流式执行AI意图: factoryId={}, inputLength={}, userId={}, role={}",
                factoryId,
                request.getUserInput() == null ? 0 : request.getUserInput().length(),
                userId, userRole);

        return intentExecutorService.executeStream(factoryId, request, userId, userRole);
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — preview is read-only.
    @PostMapping("/preview")
    @Operation(summary = "预览AI意图执行结果", description = "识别意图并预览执行结果，不实际执行")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> previewIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentExecuteRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        removeCallerConfirmationAuthority(request);
        log.info("预览AI意图: factoryId={}, userInput={}", factoryId, request.getUserInput());

        IntentExecuteResponse response = intentExecutorService.preview(factoryId, request, userId, userRole);
        return envelopeByInnerStatus(response);
    }

    // requestId and idempotencyKey reserve the future Gateway HTTP contract. They are validated,
    // but are deliberately not represented as persisted/executed idempotency in this phase. The
    // database atomic token claim remains the only single-use execution authority.
    @PostMapping("/confirm")
    @Operation(summary = "确认执行预览的意图",
               description = "从专用请求头接收不透明令牌，原子认领参数绑定的预览命令并复核当前JWT权限")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> confirmIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestHeader("X-Cretas-Confirmation-Token") String confirmationToken,
            @Valid @RequestBody IntentConfirmationRequest confirmationRequest,
            HttpServletRequest httpRequest) {

        if (confirmationToken == null || confirmationToken.isBlank()
                || confirmationToken.length() > 2048) {
            return ResponseEntity.badRequest().body(ApiResponse.errorWithCode(
                    HttpStatus.BAD_REQUEST.value(),
                    "INVALID_CONFIRMATION_PROOF",
                    "Confirmation proof token is missing or invalid",
                    null,
                    "BLOCKING"));
        }

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        IntentExecuteResponse response = intentExecutorService.confirm(
                factoryId,
                confirmationRequest.toConfirmationProof(confirmationToken),
                userId,
                userRole);
        return envelopeByInnerStatus(response);
    }

    /**
     * Terminates legacy path-token confirmation without binding, logging, or executing the token.
     * Upstream access-log policy is outside this controller boundary; new clients must use the
     * fixed {@code /confirm} endpoint so proof tokens never enter request URLs.
     */
    @PostMapping("/confirm/{confirmToken}")
    public ResponseEntity<ApiResponse<Void>> rejectLegacyPathConfirmation() {
        ApiResponse<Void> response = ApiResponse.errorWithCode(
                HttpStatus.GONE.value(),
                "CONFIRMATION_ENDPOINT_UPGRADE_REQUIRED",
                "Confirmation endpoint upgrade required",
                "Use the fixed /confirm endpoint with X-Cretas-Confirmation-Token",
                "BLOCKING");
        return ResponseEntity.status(HttpStatus.GONE)
                .cacheControl(CacheControl.noStore())
                .body(response);
    }

    // ==================== 参数确认和规则学习 ====================

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}). Parameter learning
    // is per-user-per-intent. If executeAfterConfirm=true, the intent-level perm gate in
    // IntentExecutionOrchestrator still applies.
    @PostMapping("/params/confirm")
    @Operation(summary = "确认参数并学习规则",
               description = "用户确认 LLM 提取的参数后，系统学习提取规则，下次可直接使用规则提取（无需调用 LLM）")
    public ResponseEntity<ApiResponse<IntentExecuteResponse>> confirmParameters(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody ParameterConfirmationRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String userRole = jwtUtil.getRoleFromToken(token);

        Map<String, Object> confirmedParams = WriteGuardService.withoutCallerConfirmation(
                request.getConfirmedParams());
        log.info("确认参数并学习规则: factoryId={}, intentCode={}, params={}",
                factoryId, request.getIntentCode(),
                confirmedParams != null ? confirmedParams.keySet() : java.util.Set.of());

        // 1. 学习提取规则
        parameterExtractionLearningService.learnAndConfirm(
                factoryId,
                request.getIntentCode(),
                request.getUserInput(),
                confirmedParams);

        // 2. 如果需要执行，构建执行请求
        if (Boolean.TRUE.equals(request.getExecuteAfterConfirm())) {
            // Parameter correction is not execution authority. A write still has to use the
            // preview -> single-use token -> /confirm flow; read-only intents remain compatible.
            Map<String, Object> context = confirmedParams != null
                    ? new java.util.HashMap<>(confirmedParams)
                    : new java.util.HashMap<>();
            IntentExecuteRequest executeRequest = IntentExecuteRequest.builder()
                    .userInput(request.getUserInput())
                    .intentCode(request.getIntentCode())
                    .context(context)
                    .build();

            IntentExecuteResponse response = intentExecutorService.execute(factoryId, executeRequest, userId, userRole);
            return ResponseEntity.ok(ApiResponse.success("参数已确认并执行", response));
        }

        // 只学习规则，不执行
        return ResponseEntity.ok(ApiResponse.success("参数已确认，规则已学习", null));
    }

    /** HTTP request fields are data, never write-confirmation authority. */
    private void removeCallerConfirmationAuthority(IntentExecuteRequest request) {
        if (request == null) return;
        request.setForceExecute(false);
        request.setContext(WriteGuardService.withoutCallerConfirmation(request.getContext()));
    }

    @GetMapping("/params/rules/{intentCode}")
    @Operation(summary = "获取意图的参数提取规则", description = "获取指定意图的所有活跃参数提取规则")
    public ResponseEntity<ApiResponse<List<ParameterExtractionRule>>> getExtractionRules(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        List<ParameterExtractionRule> rules = parameterExtractionLearningService.getActiveRules(factoryId, intentCode);
        return ResponseEntity.ok(ApiResponse.success(rules));
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/params/rules/{ruleId}")
    @Operation(summary = "删除参数提取规则", description = "删除指定的参数提取规则（仅管理员）")
    public ResponseEntity<ApiResponse<Void>> deleteExtractionRule(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "规则ID") @PathVariable String ruleId) {

        log.info("删除参数提取规则: factoryId={}, ruleId={}", factoryId, ruleId);
        parameterExtractionLearningService.deleteRule(factoryId, ruleId);
        return ResponseEntity.ok(ApiResponse.success("规则已删除", null));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/params/rules/cleanup")
    @Operation(summary = "清理低成功率规则", description = "清理成功率低于阈值的参数提取规则")
    public ResponseEntity<ApiResponse<Integer>> cleanupLowSuccessRules(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "最小命中次数") @RequestParam(defaultValue = "10") int minHitCount,
            @Parameter(description = "最大成功率阈值") @RequestParam(defaultValue = "0.3") double maxSuccessRate) {

        log.info("清理低成功率规则: factoryId={}, minHitCount={}, maxSuccessRate={}",
                factoryId, minHitCount, maxSuccessRate);
        int count = parameterExtractionLearningService.cleanupLowSuccessRules(factoryId, minHitCount, maxSuccessRate);
        return ResponseEntity.ok(ApiResponse.success("已清理 " + count + " 条规则", count));
    }

    // ==================== 权限查询 ====================

    @GetMapping("/{intentCode}/permission")
    @Operation(summary = "检查意图权限", description = "检查指定角色是否有权限执行意图")
    public ResponseEntity<ApiResponse<PermissionCheckResult>> checkPermission(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @Parameter(description = "用户角色") @RequestParam String userRole) {

        // 按路径工厂查 (本厂行优先, 再平台级), 不用不分工厂的旧重载 (复审 L1, 2026-10-29)
        boolean hasPermission = aiIntentService.hasPermission(factoryId, intentCode, userRole);
        boolean requiresApproval = aiIntentService.requiresApproval(factoryId, intentCode);
        int quotaCost = aiIntentService.getQuotaCost(factoryId, intentCode);

        PermissionCheckResult result = new PermissionCheckResult();
        result.setIntentCode(intentCode);
        result.setUserRole(userRole);
        result.setHasPermission(hasPermission);
        result.setRequiresApproval(requiresApproval);
        result.setQuotaCost(quotaCost);

        return ResponseEntity.ok(ApiResponse.success(result));
    }

    // ==================== 意图管理 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping
    @Operation(summary = "创建意图配置", description = "创建新的AI意图配置（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AIIntentConfig>> createIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody AIIntentConfig intentConfig) {

        // 工厂取路径, 体里的 id / factoryId 不认; 平台级只给平台账号且不许提保密产品 (Steve 2026-10-29)
        AIIntentConfig safe = intentConfigWriteGuard.prepareCreate(factoryId, intentConfig,
                com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());
        AIIntentConfig created;
        try {
            created = aiIntentService.createIntent(safe);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // 意图代码全库唯一 (含已删除的行): 不透露占用方 (复审 L3)
            throw new com.cretas.aims.exception.BusinessException(409,
                    com.cretas.aims.service.intent.IntentConfigWriteGuard.CODE_TAKEN_MESSAGE)
                    .withHint(com.cretas.aims.service.intent.IntentConfigWriteGuard.CODE_TAKEN_HINT);
        }
        log.info("Created AI intent: {} for factory context: {}", safe.getIntentCode(), factoryId);
        return ResponseEntity.ok(ApiResponse.success("意图配置创建成功", created));
    }

    @RequirePermission({"system:read_write"})
    @PutMapping("/{intentCode}")
    @Operation(summary = "更新意图配置", description = "更新现有的AI意图配置（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AIIntentConfig>> updateIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @RequestBody AIIntentConfig intentConfig) {

        // 按 (路径工厂, 意图代码) 取本厂的行, 只拷业务字段; 平台级只给平台账号 (Steve 2026-10-29)
        AIIntentConfig safe = intentConfigWriteGuard.prepareUpdate(factoryId, intentCode, intentConfig,
                com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());
        AIIntentConfig updated = aiIntentService.updateIntent(safe);
        log.info("Updated AI intent: {}", intentCode);
        return ResponseEntity.ok(ApiResponse.success("意图配置更新成功", updated));
    }

    @RequirePermission({"system:read_write"})
    @PatchMapping("/{intentCode}/active")
    @Operation(summary = "启用/禁用意图", description = "切换意图的启用状态（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> setIntentActive(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @RequestBody ActiveStatusRequest request) {

        // 只改路径工厂自己的行; PLATFORM 路径只给平台账号 (复审 H1, 2026-10-29)
        String target = intentConfigWriteGuard.resolveTarget(factoryId, com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());
        aiIntentService.setIntentActive(target, intentCode, request.isActive());
        String action = request.isActive() ? "启用" : "禁用";
        log.info("{} AI intent: {}", action, intentCode);
        return ResponseEntity.ok(ApiResponse.successMessage("意图已" + action));
    }

    @RequirePermission({"system:read_write"})
    @DeleteMapping("/{intentCode}")
    @Operation(summary = "删除意图配置", description = "软删除意图配置（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> deleteIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        // 只删路径工厂自己的行 (本厂没有 → 404); PLATFORM 路径只给平台账号 (复审 H1)
        AIIntentConfig owned = intentConfigWriteGuard.loadOwned(factoryId, intentCode, com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());
        aiIntentService.deleteIntent(owned.getFactoryId(), intentCode);
        log.info("Deleted AI intent: {}", intentCode);
        return ResponseEntity.ok(ApiResponse.successMessage("意图配置删除成功"));
    }

    // ==================== 版本回滚 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/{intentCode}/rollback")
    @Operation(summary = "回滚意图配置", description = "回滚单个意图配置到上个版本（仅工厂管理员）")
    public ResponseEntity<ApiResponse<AIIntentConfig>> rollbackIntent(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);

        // 只回滚路径工厂自己的行 (getIntentByCode 会退回平台级行, 不用); PLATFORM 只给平台账号 (复审 H2)
        AIIntentConfig config = intentConfigWriteGuard.loadOwned(factoryId, intentCode, com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());

        AIIntentConfig rolled = rollbackService.rollbackToLastVersion(
                config.getId(), userId, username, request.getReason());

        log.info("Rolled back AI intent: {} to version {}", intentCode, rolled.getConfigVersion());
        return ResponseEntity.ok(ApiResponse.success("意图配置回滚成功", rolled));
    }

    @GetMapping("/{intentCode}/history")
    @Operation(summary = "获取版本历史", description = "获取意图配置的版本历史记录")
    public ResponseEntity<ApiResponse<java.util.List<AIIntentConfigHistory>>> getVersionHistory(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @Parameter(description = "意图代码") @PathVariable String intentCode) {

        // 版本历史 (含各版快照) 只给本厂的行; 平台级行只给平台账号 (复审 H2)
        AIIntentConfig config = intentConfigWriteGuard.loadOwned(factoryId, intentCode, com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());

        java.util.List<AIIntentConfigHistory> history = rollbackService.getVersionHistory(config.getId());
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/rollback-all")
    @Operation(summary = "批量回滚工厂意图", description = "回滚工厂的所有意图配置到上个版本（仅工厂管理员）")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> rollbackAllIntents(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody RollbackRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);
        String username = jwtUtil.getUsernameFromToken(token);

        // 只回滚本厂自己的行; PLATFORM 路径 + 平台账号才回滚平台级行 (复审 H2)
        String target = intentConfigWriteGuard.resolveTarget(factoryId, com.cretas.aims.security.AuthenticatedActor.fromCurrentRequest());
        java.util.Map<String, Object> result = rollbackService.rollbackFactoryToLastVersion(
                target, userId, username, request.getReason());

        log.info("Batch rollback for factory {}: {}", factoryId, result);
        return ResponseEntity.ok(ApiResponse.success("批量回滚完成", result));
    }

    // ==================== 反馈记录 ====================

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}). Feedback is
    // per-user signal data — every authenticated user who can use AI must be able to
    // submit feedback (positive/negative). JWT auth alone is sufficient.
    @PostMapping("/feedback/positive")
    @Operation(summary = "记录正向反馈", description = "当用户确认意图匹配正确时调用，用于关键词效果追踪")
    public ResponseEntity<ApiResponse<Void>> recordPositiveFeedback(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody PositiveFeedbackRequest request) {

        log.info("记录正向反馈: factoryId={}, intentCode={}, keywords={}",
                factoryId, request.getIntentCode(), request.getMatchedKeywords());

        aiIntentService.recordPositiveFeedback(
                factoryId,
                request.getIntentCode(),
                request.getMatchedKeywords());

        return ResponseEntity.ok(ApiResponse.successMessage("正向反馈已记录"));
    }

    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — feedback path.
    @PostMapping("/feedback/negative")
    @Operation(summary = "记录负向反馈", description = "当用户拒绝匹配结果并选择其他意图时调用")
    public ResponseEntity<ApiResponse<Void>> recordNegativeFeedback(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody NegativeFeedbackRequest request) {

        log.info("记录负向反馈: factoryId={}, rejected={}, selected={}, keywords={}",
                factoryId, request.getRejectedIntentCode(),
                request.getSelectedIntentCode(), request.getMatchedKeywords());

        aiIntentService.recordNegativeFeedback(
                factoryId,
                request.getRejectedIntentCode(),
                request.getSelectedIntentCode(),
                request.getMatchedKeywords());

        return ResponseEntity.ok(ApiResponse.successMessage("负向反馈已记录"));
    }

    /**
     * 意图识别反馈接口
     * 用户可以纠正错误的意图识别结果，系统自动学习
     */
    // Sprint 11 Round 2: removed @RequirePermission({"system:read_write"}) — feedback path.
    @PostMapping("/feedback")
    @Operation(summary = "提交意图识别反馈", description = "用户可以纠正错误的意图识别结果，系统自动学习")
    public ResponseEntity<ApiResponse<Void>> submitIntentFeedback(
            @Parameter(description = "工厂ID") @PathVariable String factoryId,
            @RequestBody IntentFeedbackRequest request,
            HttpServletRequest httpRequest) {

        // Sprint 11.5 P0 (2026-05-23): cookie-aware token extraction
        String token = extractToken(httpRequest);
        Long userId = jwtUtil.getUserIdFromToken(token);

        log.info("提交意图反馈: factoryId={}, userId={}, input='{}', matched={}, correct={}, isCorrect={}",
                factoryId, userId, request.getUserInput(), request.getMatchedIntentCode(),
                request.getCorrectIntentCode(), request.getIsCorrect());

        aiIntentService.processIntentFeedback(factoryId, userId, request);

        return ResponseEntity.ok(ApiResponse.successMessage("反馈已记录，系统将自动学习"));
    }

    // ==================== 缓存管理 ====================

    @RequirePermission({"system:read_write"})
    @PostMapping("/cache/refresh")
    @Operation(summary = "刷新意图缓存", description = "清除并重新加载意图配置缓存（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> refreshCache(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        aiIntentService.refreshCache();
        log.info("Refreshed AI intent cache");
        return ResponseEntity.ok(ApiResponse.successMessage("意图缓存已刷新"));
    }

    @RequirePermission({"system:read_write"})
    @PostMapping("/cache/clear")
    @Operation(summary = "清除意图缓存", description = "清除意图配置缓存（仅工厂管理员）")
    public ResponseEntity<ApiResponse<Void>> clearCache(
            @Parameter(description = "工厂ID") @PathVariable String factoryId) {

        aiIntentService.clearCache();
        log.info("Cleared AI intent cache");
        return ResponseEntity.ok(ApiResponse.successMessage("意图缓存已清除"));
    }

    // ==================== DTO Classes ====================

    /**
     * 意图识别请求
     */
    @lombok.Data
    public static class IntentRecognitionRequest {
        private String userInput;
    }

    /**
     * 意图识别结果
     */
    @lombok.Data
    public static class IntentRecognitionResult {
        private String userInput;
        private boolean matched;
        private String intentCode;
        private String intentName;
        private String category;
        private String sensitivityLevel;
        private Integer quotaCost;
        private Boolean requiresApproval;
        private Double confidence;      // 匹配置信度 (BUG-001/002 修复新增)
        private String matchMethod;     // 匹配方法 (REGEX/KEYWORD/SEMANTIC/LLM)
        private Map<String, Long> timingMs;  // 各阶段耗时
    }

    /**
     * 权限检查结果
     */
    @lombok.Data
    public static class PermissionCheckResult {
        private String intentCode;
        private String userRole;
        private boolean hasPermission;
        private boolean requiresApproval;
        private int quotaCost;
    }

    /**
     * 启用状态请求
     */
    @lombok.Data
    public static class ActiveStatusRequest {
        private boolean active;
    }

    /**
     * 正向反馈请求
     */
    @lombok.Data
    public static class PositiveFeedbackRequest {
        /** 意图代码 */
        private String intentCode;
        /** 匹配到的关键词列表 */
        private java.util.List<String> matchedKeywords;
    }

    /**
     * 负向反馈请求
     */
    @lombok.Data
    public static class NegativeFeedbackRequest {
        /** 被拒绝的意图代码 */
        private String rejectedIntentCode;
        /** 用户选择的正确意图代码 */
        private String selectedIntentCode;
        /** 原匹配到的关键词列表 */
        private java.util.List<String> matchedKeywords;
    }

    /**
     * 回滚请求
     */
    @lombok.Data
    public static class RollbackRequest {
        /** 回滚原因 */
        private String reason;
    }
}
