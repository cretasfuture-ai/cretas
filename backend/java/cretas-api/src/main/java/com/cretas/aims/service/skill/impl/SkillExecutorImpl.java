package com.cretas.aims.service.skill.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflow;
import com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflowInput;
import com.cretas.aims.ai.workflow.inventory.InventoryAnalysisWorkflowResult;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.service.skill.SkillExecutor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Skill 执行器服务实现
 *
 * 负责执行 Skill 的完整生命周期:
 * 1. 准备 Prompt (替换模板变量)
 * 2. 调用 LLM 获取参数和执行计划
 * 3. 解析 LLM 响应，提取参数
 * 4. 执行关联的 Tools
 *
 * 集成现有服务:
 * - ToolRegistry: 获取和执行工具
 * - DashScopeClient: LLM 调用
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-18
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SkillExecutorImpl implements SkillExecutor {

    private final ToolRegistry toolRegistry;
    private final DashScopeClient dashScopeClient;
    private final ObjectMapper objectMapper;

    /**
     * Skill 提示词 (含已抽参数) 与原话发给模型前脱敏; 单测直接 new 本类时用默认实例。
     * required=false: 采购授权的 Postgres 切片测试 (@DataJpaTest + @Import) 把本类装进没有守卫 bean 的上下文;
     * 完整应用里守卫 bean 总在, 切片里沿用默认实例 (号码 / 人名照样脱敏, 只是不查保密产品名)。
     */
    @Autowired(required = false)
    private com.cretas.aims.service.consent.LlmEgressGuard llmEgressGuard =
            com.cretas.aims.service.consent.LlmEgressGuard.standalone();

    static final String EGRESS_SITE = "skill-executor";

    // W0 write-guard (intent-w0) — SITE D. Skill orchestration calls ToolExecutor.execute() directly
    // (executeSingleTool), bypassing ToolDispatchService (Site B). Field-injected (not in the Lombok
    // @RequiredArgsConstructor) so existing direct-construction tests stay unaffected; tests set it via
    // reflection. Stateless → safe on the skill worker thread pool.
    @Autowired
    private com.cretas.aims.ai.tool.WriteGuardService writeGuardService;

    // W9 红线 + 2026-10-06 — SITE D: 与 ToolDispatchService.executeWithTool 同一条角色门链
    // (一线写操作门 → 旧式角色串 → 中央 RBAC)。此前这里只接了 RBAC, 旧式角色串与一线门对 Skill
    // 路径不存在, 生产上操作员经 Skill 跑了管理员级的「全员考勤统计」。为空 (裸构造的单测) 时拒绝执行。
    @Autowired
    private com.cretas.aims.service.execution.ToolPermissionChain toolPermissionChain;

    /**
     * Canonical inventory workflow adapter. Optional field injection preserves the legacy
     * constructor used by focused tests; the canonical alias fails closed if this bean is absent.
     */
    @Autowired(required = false)
    private InventoryAnalysisWorkflow inventoryAnalysisWorkflow;

    // S6-02 — a tool with an approved runtime policy runs through the tool gateway, exactly as an
    // interactive call of that tool does: the gateway re-reads the user, factory and permissions
    // from the database, checks that the policy admits SKILL_WORKFLOW, enforces confirmation and
    // writes the audit event. Field-injected so focused tests that construct this class directly
    // keep their legacy tools; a governed tool with no gateway present is refused, never run directly.
    @Autowired(required = false)
    private com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry toolRuntimeRegistry;

    @Autowired(required = false)
    private com.cretas.aims.ai.tool.gateway.ToolExecutionGateway toolExecutionGateway;

    @Autowired(required = false)
    private com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory toolPrincipalFactory;

    /** Validates caller parameters of a Skill that declares a parameter contract; absent → such Skills are refused. */
    @Autowired(required = false)
    private com.cretas.aims.service.skill.contract.SkillParameterContractValidator contractValidator;

    /** Run receipts; absent (focused tests) → runs are not recorded and carry no runId. */
    @Autowired(required = false)
    private com.cretas.aims.service.skill.contract.SkillRunStore runStore;

    /** Steps of the run in progress, per context (parallel tools add from worker threads). */
    private final Map<SkillContext, List<com.cretas.aims.service.skill.contract.SkillRunStore.Step>> runSteps =
            Collections.synchronizedMap(new IdentityHashMap<>());

    /** Gateway admission for governed tools in the permission preflight (same check the gateway makes). */
    @Autowired(required = false)
    private com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy toolPrincipalPolicy;

    /** Source prefix of a definition built from a factory's saved Skill; the rest is the saved Skill id. */
    public static final String SAVED_SKILL_SOURCE_PREFIX = "FACTORY_SAVED:";

    // ==================== Configuration ====================

    @Value("${ai.skill_executor.parallel_execution_enabled:true}")
    private boolean parallelExecutionEnabled;

    @Value("${ai.skill_executor.tool_timeout_seconds:30}")
    private int toolTimeoutSeconds;

    @Value("${ai.skill_executor.max_tools_per_skill:5}")
    private int maxToolsPerSkill;

    @Value("${ai.skill_executor.default_timeout_ms:60000}")
    private long defaultTimeoutMs;

    /**
     * Tool execution thread pool
     */
    private final ExecutorService executorService = Executors.newFixedThreadPool(4);

    /**
     * Variable placeholder pattern: ${variableName}
     */
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    @PreDestroy
    public void shutdown() {
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== Interface Implementation ====================

    @Override
    public SkillResult execute(SkillDefinition skill, SkillContext context) {
        return execute(skill, context, defaultTimeoutMs);
    }

    @Override
    public SkillResult execute(SkillDefinition skill, SkillContext context, long timeoutMs) {
        if (context == null) {
            return executeUnrecorded(skill, null, timeoutMs);
        }
        runSteps.put(context, Collections.synchronizedList(new ArrayList<>()));
        try {
            return finishRun(skill, context, executeUnrecorded(skill, context, timeoutMs));
        } finally {
            runSteps.remove(context);
        }
    }

    private SkillResult executeUnrecorded(SkillDefinition skill, SkillContext context, long timeoutMs) {
        long startTime = System.currentTimeMillis();
        List<String> executedTools = new ArrayList<>();

        log.info("Starting skill execution: skill={}, userQuery='{}', timeout={}ms",
                skill.getName(),
                truncateQuery(context.getUserQuery()),
                timeoutMs);

        try {
            // Canonical alias is a fixed governed workflow. Do not validate or consume the
            // definition's tools/DAG/prompt because database overrides are not execution policy.
            if (InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME.equals(skill.getName())) {
                return executeInventoryAnalysis(skill, context, timeoutMs, startTime);
            }

            // 1. Validate context
            String validationError = validateContext(skill, context);
            if (validationError != null) {
                log.warn("Context validation failed for skill {}: {}", skill.getName(), validationError);
                return SkillResult.error(skill.getName(), validationError,
                        executedTools, System.currentTimeMillis() - startTime);
            }

            // 2. Check if skill is enabled
            if (!skill.isEnabled()) {
                log.warn("Skill is disabled: {}", skill.getName());
                return SkillResult.error(skill.getName(), "Skill is disabled",
                        executedTools, System.currentTimeMillis() - startTime);
            }

            // 2a. Permission preflight (S6 acceptance, ERP-S6-02-SKILL-ROLE-BYPASS-001): every tool this Skill
            // may run is checked against the caller's current permissions before anything runs. A Skill
            // never returns part of what the caller could not read directly; one denied tool fails the
            // whole Skill as a permission refusal.
            Preflight preflight = permissionPreflight(skill, context);
            if (preflight.unavailable() || !preflight.denied().isEmpty()) {
                // One tool the caller may not use is enough: the whole Skill is refused and nothing runs,
                // so a Skill never returns any part of what the caller could not read directly.
                log.warn("Skill {} refused by permission preflight for userId={}: {}", skill.getName(),
                        context.getUserId(), preflight.denied());
                SkillResult refused = SkillResult.error(skill.getName(), preflight.unavailable()
                                ? "权限校验暂不可用，未执行任何操作。"
                                : "你没有运行「" + displayName(skill) + "」所需的全部权限（其中 " + preflight.denied().size()
                                        + " 项数据你在系统里无权直接查看），未执行任何操作。请联系工厂管理员开通对应权限。",
                        executedTools, System.currentTimeMillis() - startTime);
                refused.setPermissionDenied(true);
                return refused;
            }

            // 2b. Contract mode (S6-02): a Skill that declares a parameter contract runs only the
            // validated caller parameters, deterministically and only through the tool gateway.
            if (skill.getConfig() != null
                    && skill.getConfig().containsKey(com.cretas.aims.service.skill.contract.SkillParameterContract.CONFIG_KEY)) {
                return executeContracted(skill, context, executedTools, startTime);
            }

            // 3. DAG mode: execute conditional branching graph
            if (skill.hasExecutionGraph()) {
                return executeDAG(skill, context, executedTools, startTime);
            }

            // 4. Deterministic mode: skip LLM for single-tool skills
            if (canExecuteDeterministically(skill, context)) {
                return executeDeterministic(skill, context, executedTools, startTime);
            }

            // 5. LLM mode: Prepare Prompt (replace template variables)
            String prompt = preparePrompt(skill, context);
            log.debug("Prepared prompt for skill {}: length={}", skill.getName(), prompt.length());

            // 5. Call LLM to get parameters and execution plan
            Map<String, Object> params;
            try {
                String llmResponse = callLlm(prompt, context.getUserQuery(), context.getFactoryId());
                log.debug("LLM response for skill {}: length={}", skill.getName(),
                        llmResponse != null ? llmResponse.length() : 0);
                // 6. Parse LLM response and extract parameters
                params = extractParams(llmResponse, context);
            } catch (RuntimeException llmEx) {
                // LLM unavailable (timeout / rate-limit / network) — degrade to deterministic
                // tool-list execution instead of failing the whole Skill. Each Tool gets the
                // context's factoryId/userId but no LLM-determined params; Tools that can
                // execute with defaults will succeed. Avoids "Skill 执行失败" UX regression.
                log.warn("Skill {} LLM unavailable ({}), falling back to default-tool execution",
                        skill.getName(), llmEx.getMessage());
                params = new HashMap<>();
                if (context.getFactoryId() != null) params.put("factoryId", context.getFactoryId());
                if (context.getUserId() != null) params.put("userId", context.getUserId());
                params.put("_llmFallback", "deterministic_no_llm");
            }
            context.setExtractedParams(params);
            log.info("Extracted {} parameters for skill {}", params.size(), skill.getName());

            // 7. Execute associated Tools
            Object toolResult = executeTools(skill, params, executedTools, context);

            long executionTime = System.currentTimeMillis() - startTime;
            log.info("Skill execution completed: skill={}, tools={}, time={}ms",
                    skill.getName(), executedTools, executionTime);

            return SkillResult.builder()
                    .success(true)
                    .skillName(skill.getName())
                    .data(toolResult)
                    .executedTools(executedTools)
                    .executionTime(executionTime)
                    .build();

        } catch (Exception e) {
            long executionTime = System.currentTimeMillis() - startTime;
            log.error("Skill execution failed: skill={}, error={}",
                    skill.getName(), e.getMessage(), e);

            return SkillResult.error(skill.getName(), e.getMessage(),
                    executedTools, executionTime);
        }
    }

    private SkillResult executeInventoryAnalysis(
            SkillDefinition skill,
            SkillContext context,
            long timeoutMs,
            long startTime) {
        if (!skill.isEnabled()) {
            return SkillResult.error(
                    InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME,
                    "Skill is disabled",
                    List.of(),
                    System.currentTimeMillis() - startTime);
        }
        if (inventoryAnalysisWorkflow == null) {
            return SkillResult.error(
                    InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME,
                    "Inventory analysis workflow is unavailable",
                    List.of(),
                    System.currentTimeMillis() - startTime);
        }
        if (context == null) {
            return SkillResult.error(
                    InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME,
                    "Context is null",
                    List.of(),
                    System.currentTimeMillis() - startTime);
        }

        long userId;
        try {
            userId = Long.parseLong(context.getUserId());
        } catch (RuntimeException invalidUserId) {
            return SkillResult.error(
                    InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME,
                    "Valid userId is required",
                    List.of(),
                    System.currentTimeMillis() - startTime);
        }

        InventoryAnalysisWorkflowResult result = inventoryAnalysisWorkflow.execute(
                new InventoryAnalysisWorkflowInput(
                        context.getFactoryId(),
                        userId,
                        context.getSessionId(),
                        context.getUserQuery(),
                        timeoutMs));
        long executionTime = System.currentTimeMillis() - startTime;
        if (!result.success()) {
            return SkillResult.error(
                    InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME,
                    result.message(),
                    result.executedTools(),
                    executionTime);
        }
        return SkillResult.builder()
                .success(true)
                .skillName(InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME)
                .data(result.data())
                .message(result.message())
                .executedTools(result.executedTools())
                .executionTime(executionTime)
                .build();
    }

    /**
     * Check if a skill can be executed deterministically (without LLM).
     * Conditions: single tool + all required params available in context.
     */
    private boolean canExecuteDeterministically(SkillDefinition skill, SkillContext context) {
        List<String> tools = skill.getTools();
        if (tools == null || tools.size() != 1) {
            return false; // Multi-tool skills need LLM for orchestration
        }

        String toolName = tools.get(0);
        if (!toolRegistry.hasExecutor(toolName)) {
            return false;
        }

        // Single-tool skill with params available from context — skip LLM
        log.info("Skill {} eligible for deterministic execution (single tool: {})",
                skill.getName(), toolName);
        return true;
    }

    /**
     * Execute a single-tool skill deterministically without calling LLM.
     * Extracts params directly from context instead of LLM response.
     */
    private SkillResult executeDeterministic(SkillDefinition skill, SkillContext context,
                                              List<String> executedTools, long startTime) throws Exception {
        String toolName = skill.getTools().get(0);
        log.info("Deterministic execution: skill={}, tool={}", skill.getName(), toolName);

        // Build params from context (factoryId, userId, etc.)
        Map<String, Object> params = new HashMap<>();
        if (context.getFactoryId() != null) params.put("factoryId", context.getFactoryId());
        if (context.getUserId() != null) params.put("userId", context.getUserId());
        if (context.getExtractedParams() != null) params.putAll(context.getExtractedParams());
        context.setExtractedParams(params);

        // Execute the single tool directly
        Object result = executeSingleTool(toolName, params, context);
        executedTools.add(toolName);

        Map<String, Object> results = new LinkedHashMap<>();
        results.put(toolName, result);
        results.put("_executionOrder", "deterministic");
        results.put("_toolCount", 1);

        long executionTime = System.currentTimeMillis() - startTime;
        log.info("Deterministic execution completed: skill={}, tool={}, time={}ms",
                skill.getName(), toolName, executionTime);

        return SkillResult.builder()
                .success(true)
                .skillName(skill.getName())
                .data(results)
                .executedTools(executedTools)
                .executionTime(executionTime)
                .build();
    }

    @Override
    public String validateContext(SkillDefinition skill, SkillContext context) {
        if (context == null) {
            return "Context is null";
        }

        if (context.getUserQuery() == null || context.getUserQuery().trim().isEmpty()) {
            return "User query is empty";
        }

        // Check required context fields
        List<String> contextNeeded = skill.getContextNeeded();
        if (contextNeeded != null && !contextNeeded.isEmpty()) {
            List<String> missingFields = new ArrayList<>();

            for (String field : contextNeeded) {
                if (!hasContextField(context, field)) {
                    missingFields.add(field);
                }
            }

            if (!missingFields.isEmpty()) {
                return "Missing required context fields: " + String.join(", ", missingFields);
            }
        }

        // Check if required tools are available
        List<String> tools = skill.getTools();
        if (tools != null && !tools.isEmpty()) {
            List<String> missingTools = tools.stream()
                    .filter(t -> !toolRegistry.hasExecutor(t))
                    .collect(Collectors.toList());

            if (!missingTools.isEmpty()) {
                return "Required tools not available: " + String.join(", ", missingTools);
            }
        }

        return null; // Validation passed
    }

    @Override
    public boolean supports(SkillDefinition skill) {
        if (skill == null || skill.getName() == null) {
            return false;
        }

        if (InventoryAnalysisWorkflow.CANONICAL_SKILL_NAME.equals(skill.getName())) {
            return inventoryAnalysisWorkflow != null;
        }

        // Check if all required tools are available
        List<String> tools = skill.getTools();
        if (tools == null || tools.isEmpty()) {
            return true; // Skill with no tools is supported
        }

        return tools.stream().allMatch(toolRegistry::hasExecutor);
    }

    // ==================== Private Helper Methods ====================

    /**
     * Check if context has a specific field
     */
    private boolean hasContextField(SkillContext context, String field) {
        switch (field.toLowerCase()) {
            case "factoryid":
                return context.getFactoryId() != null && !context.getFactoryId().isEmpty();
            case "userid":
                return context.getUserId() != null && !context.getUserId().isEmpty();
            case "sessionid":
                return context.getSessionId() != null && !context.getSessionId().isEmpty();
            case "userquery":
                return context.getUserQuery() != null && !context.getUserQuery().isEmpty();
            default:
                // Check in extracted params
                if (context.getExtractedParams() != null) {
                    return context.getExtractedParams().containsKey(field);
                }
                return false;
        }
    }

    /**
     * Prepare Prompt
     *
     * Replace template variables:
     * - ${userQuery}: User query
     * - ${factoryId}: Factory ID
     * - ${userId}: User ID
     * - ${tools}: Available tools list
     * - ${contextNeeded}: Required context
     * - Custom variables: From context.extractedParams
     */
    private String preparePrompt(SkillDefinition skill, SkillContext context) {
        String template = skill.getPromptTemplate();

        if (template == null || template.isEmpty()) {
            // Use default template
            template = buildDefaultPromptTemplate(skill);
        }

        // Build variable mapping
        Map<String, String> variables = new HashMap<>();
        variables.put("userQuery", context.getUserQuery() != null ? context.getUserQuery() : "");
        variables.put("factoryId", context.getFactoryId() != null ? context.getFactoryId() : "");
        variables.put("userId", context.getUserId() != null ? context.getUserId() : "");
        variables.put("sessionId", context.getSessionId() != null ? context.getSessionId() : "");
        variables.put("skillName", skill.getName());
        variables.put("skillDescription", skill.getDescription() != null ? skill.getDescription() : "");

        // Add tools list
        if (skill.getTools() != null && !skill.getTools().isEmpty()) {
            String toolsList = skill.getTools().stream()
                    .map(this::formatToolInfo)
                    .collect(Collectors.joining("\n"));
            variables.put("tools", toolsList);
        } else {
            variables.put("tools", "No specific tools defined");
        }

        // Add context requirements
        if (skill.getContextNeeded() != null && !skill.getContextNeeded().isEmpty()) {
            variables.put("contextNeeded", String.join(", ", skill.getContextNeeded()));
        } else {
            variables.put("contextNeeded", "None");
        }

        // Add extracted params from context
        if (context.getExtractedParams() != null) {
            for (Map.Entry<String, Object> entry : context.getExtractedParams().entrySet()) {
                variables.put(entry.getKey(),
                        entry.getValue() != null ? entry.getValue().toString() : "");
            }
        }

        // Replace variables
        return replaceVariables(template, variables);
    }

    /**
     * Build default Prompt template
     */
    private String buildDefaultPromptTemplate(SkillDefinition skill) {
        StringBuilder sb = new StringBuilder();

        sb.append("## Role\n");
        sb.append("You are an intelligent assistant executing the '").append(skill.getDisplayName()).append("' skill.\n\n");

        if (skill.getDescription() != null && !skill.getDescription().isEmpty()) {
            sb.append("## Skill Description\n");
            sb.append(skill.getDescription()).append("\n\n");
        }

        sb.append("## User Request\n");
        sb.append("${userQuery}\n\n");

        sb.append("## Context Information\n");
        sb.append("- Factory ID: ${factoryId}\n");
        sb.append("- User ID: ${userId}\n");
        sb.append("- Required Context: ${contextNeeded}\n\n");

        if (skill.getTools() != null && !skill.getTools().isEmpty()) {
            sb.append("## Available Tools\n");
            sb.append("${tools}\n\n");
        }

        sb.append("## Task\n");
        sb.append("1. Analyze user request and extract key parameters\n");
        sb.append("2. Determine which tools to call\n");
        sb.append("3. Prepare required parameters for each tool\n\n");

        sb.append("## Output Format (Strict JSON)\n");
        sb.append("```json\n");
        sb.append("{\n");
        sb.append("  \"extracted_params\": {\n");
        sb.append("    \"param_name\": \"param_value\"\n");
        sb.append("  },\n");
        sb.append("  \"tools_to_execute\": [\n");
        sb.append("    {\n");
        sb.append("      \"tool_name\": \"tool_name\",\n");
        sb.append("      \"params\": {}\n");
        sb.append("    }\n");
        sb.append("  ],\n");
        sb.append("  \"execution_order\": \"sequential or parallel\",\n");
        sb.append("  \"reasoning\": \"Execution plan explanation\"\n");
        sb.append("}\n");
        sb.append("```\n");

        return sb.toString();
    }

    /**
     * Format tool information
     */
    private String formatToolInfo(String toolName) {
        Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isPresent()) {
            ToolExecutor executor = executorOpt.get();
            return String.format("- %s: %s", toolName, executor.getDescription());
        }
        return String.format("- %s: (Tool info not available)", toolName);
    }

    /**
     * Replace template variables
     */
    private String replaceVariables(String template, Map<String, String> variables) {
        Matcher matcher = VARIABLE_PATTERN.matcher(template);
        StringBuffer result = new StringBuffer();

        while (matcher.find()) {
            String varName = matcher.group(1);
            String replacement = variables.getOrDefault(varName, "");
            // Escape special characters
            replacement = Matcher.quoteReplacement(replacement);
            matcher.appendReplacement(result, replacement);
        }
        matcher.appendTail(result);

        return result.toString();
    }

    /**
     * Call LLM
     */
    private String callLlm(String prompt, String userQuery, String factoryId) {
        // 脱敏不了就当 LLM 不可用 (调用点降级为确定性执行), 不发; 抽出的参数换回真值
        com.cretas.aims.service.consent.LlmEgressRedactor.Result redacted =
                llmEgressGuard.texts(EGRESS_SITE, factoryId, prompt, userQuery);
        if (redacted.refused()) {
            throw new IllegalStateException("skill prompt could not be redacted, not sending");
        }
        try {
            return redacted.restoreJsonText(dashScopeClient.chat(redacted.text(0), redacted.text(1)));
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to call LLM: " + e.getMessage(), e);
        }
    }

    /**
     * Extract parameters from LLM response
     */
    private Map<String, Object> extractParams(String llmResponse, SkillContext context) {
        Map<String, Object> params = new HashMap<>();

        // Preserve existing params from context
        if (context.getExtractedParams() != null) {
            params.putAll(context.getExtractedParams());
        }

        // Add base context params
        params.put("factoryId", context.getFactoryId());
        params.put("userId", context.getUserId());
        params.put("sessionId", context.getSessionId());
        params.put("userQuery", context.getUserQuery());

        if (llmResponse == null || llmResponse.isEmpty()) {
            log.warn("Empty LLM response, returning context params only");
            return params;
        }

        try {
            // Extract JSON
            String json = extractJson(llmResponse);
            if (json == null) {
                log.warn("No JSON found in LLM response");
                return params;
            }

            JsonNode root = objectMapper.readTree(json);

            // Extract extracted_params
            if (root.has("extracted_params")) {
                JsonNode paramsNode = root.get("extracted_params");
                Map<String, Object> extractedParams = objectMapper.convertValue(
                        paramsNode, new TypeReference<Map<String, Object>>() {});
                params.putAll(extractedParams);
            }

            // Store tools_to_execute for later use
            if (root.has("tools_to_execute")) {
                params.put("_tools_to_execute", root.get("tools_to_execute"));
            }

            // Store execution_order
            if (root.has("execution_order")) {
                params.put("_execution_order", root.get("execution_order").asText());
            }

            // Store reasoning
            if (root.has("reasoning")) {
                params.put("_reasoning", root.get("reasoning").asText());
            }

        } catch (JsonProcessingException e) {
            // LLM hallucinated broken JSON (unescaped backslash, trailing comma,
            // stray markdown). Code already returns base params so the skill
            // can still run with its defaults — just missing the LLM-extracted
            // extras. WARN is the right level: quality-of-output issue, not
            // a server bug.
            log.warn("LLM response JSON 解析失败, 使用基础 context params: {}", e.getMessage());
        }

        return params;
    }

    /**
     * Extract JSON from response
     */
    private String extractJson(String response) {
        if (response == null || response.isEmpty()) {
            return null;
        }

        // Try to find JSON code block
        int start = response.indexOf("```json");
        if (start != -1) {
            start = response.indexOf("\n", start) + 1;
            int end = response.indexOf("```", start);
            if (end != -1) {
                return response.substring(start, end).trim();
            }
        }

        // Try to find { } brackets
        start = response.indexOf("{");
        if (start != -1) {
            int end = response.lastIndexOf("}");
            if (end != -1 && end > start) {
                return response.substring(start, end + 1);
            }
        }

        return null;
    }

    /**
     * Execute associated Tools
     */
    private Object executeTools(SkillDefinition skill, Map<String, Object> params,
                                 List<String> executedTools, SkillContext context) {

        // 1. Determine tools to execute
        List<ToolExecutionPlan> toolPlans = buildToolExecutionPlans(skill, params);

        if (toolPlans.isEmpty()) {
            log.warn("No tools to execute for skill: {}", skill.getName());
            return Map.of("message", "No tools executed", "params", params);
        }

        // Limit tool count
        if (toolPlans.size() > maxToolsPerSkill) {
            log.warn("Too many tools ({}) for skill {}, limiting to {}",
                    toolPlans.size(), skill.getName(), maxToolsPerSkill);
            toolPlans = toolPlans.subList(0, maxToolsPerSkill);
        }

        // 2. Determine execution order
        String executionOrder = (String) params.getOrDefault("_execution_order", "sequential");
        boolean isParallel = "parallel".equalsIgnoreCase(executionOrder) && parallelExecutionEnabled;

        // 3. Execute tools
        Map<String, Object> results;
        if (isParallel && toolPlans.size() > 1) {
            results = executeToolsParallel(toolPlans, params, executedTools, context);
        } else {
            results = executeToolsSequential(toolPlans, params, executedTools, context);
        }

        // 4. Add execution metadata
        results.put("_executionOrder", executionOrder);
        results.put("_toolCount", executedTools.size());

        String reasoning = (String) params.get("_reasoning");
        if (reasoning != null) {
            results.put("_reasoning", reasoning);
        }

        return results;
    }

    /**
     * Build tool execution plans
     */
    private List<ToolExecutionPlan> buildToolExecutionPlans(SkillDefinition skill,
                                                            Map<String, Object> params) {
        List<ToolExecutionPlan> plans = new ArrayList<>();

        // Prioritize LLM-generated execution plan
        Object toolsToExecute = params.get("_tools_to_execute");
        if (toolsToExecute instanceof JsonNode) {
            JsonNode toolsNode = (JsonNode) toolsToExecute;
            if (toolsNode.isArray()) {
                int order = 1;
                for (JsonNode toolNode : toolsNode) {
                    String toolName = toolNode.has("tool_name") ?
                            toolNode.get("tool_name").asText() : null;

                    // 2026-10-06: LLM 只能在 Skill 自己声明的工具里排顺序, 不能点名别的工具 ——
                    // 抢先路由前的预检 (DynamicToolSelectionService) 只检查声明的那几个。
                    if (toolName != null && !skill.hasTool(toolName)) {
                        log.warn("LLM 计划里的工具不在 Skill {} 的声明内, 丢弃: {}", skill.getName(), toolName);
                    } else if (toolName != null && toolRegistry.hasExecutor(toolName)) {
                        Map<String, Object> toolParams = new HashMap<>();
                        if (toolNode.has("params")) {
                            try {
                                toolParams = objectMapper.convertValue(
                                        toolNode.get("params"),
                                        new TypeReference<Map<String, Object>>() {});
                            } catch (Exception e) {
                                log.warn("Failed to parse tool params for {}", toolName);
                            }
                        }

                        plans.add(new ToolExecutionPlan(toolName, toolParams, order++));
                    } else {
                        log.warn("Tool not found in registry: {}", toolName);
                    }
                }
            }
        }

        // If LLM didn't specify tools, use Skill's default tools
        if (plans.isEmpty() && skill.getTools() != null) {
            int order = 1;
            for (String toolName : skill.getTools()) {
                if (toolRegistry.hasExecutor(toolName)) {
                    plans.add(new ToolExecutionPlan(toolName, new HashMap<>(), order++));
                }
            }
        }

        return plans;
    }

    /**
     * Execute tools sequentially
     */
    private Map<String, Object> executeToolsSequential(List<ToolExecutionPlan> plans,
                                                        Map<String, Object> globalParams,
                                                        List<String> executedTools,
                                                        SkillContext context) {
        Map<String, Object> results = new LinkedHashMap<>();
        Map<String, Object> chainContext = new HashMap<>(globalParams);

        // Sort by order
        plans.sort(Comparator.comparingInt(p -> p.order));

        for (ToolExecutionPlan plan : plans) {
            try {
                // Merge params: global + tool-specific + chain context
                Map<String, Object> mergedParams = new HashMap<>(chainContext);
                mergedParams.putAll(plan.params);

                Object result = executeSingleTool(plan.toolName, mergedParams, context);
                results.put(plan.toolName, result);
                executedTools.add(plan.toolName);

                // Add result to chain context for subsequent tools
                chainContext.put("previous_" + plan.toolName, result);

            } catch (Exception e) {
                log.error("Tool execution failed, stopping chain: {}", plan.toolName, e);
                results.put(plan.toolName, Map.of("error", e.getMessage(), "success", false));
                executedTools.add(plan.toolName + " (failed)");
                break;
            }
        }

        return results;
    }

    /**
     * Execute tools in parallel
     */
    private Map<String, Object> executeToolsParallel(List<ToolExecutionPlan> plans,
                                                      Map<String, Object> globalParams,
                                                      List<String> executedTools,
                                                      SkillContext context) {
        Map<String, Future<Object>> futures = new LinkedHashMap<>();

        for (ToolExecutionPlan plan : plans) {
            Map<String, Object> mergedParams = new HashMap<>(globalParams);
            mergedParams.putAll(plan.params);

            futures.put(plan.toolName, executorService.submit(() ->
                    executeSingleTool(plan.toolName, mergedParams, context)));
        }

        Map<String, Object> results = new LinkedHashMap<>();

        for (Map.Entry<String, Future<Object>> entry : futures.entrySet()) {
            String toolName = entry.getKey();
            try {
                Object result = entry.getValue().get(toolTimeoutSeconds, TimeUnit.SECONDS);
                results.put(toolName, result);
                executedTools.add(toolName);
            } catch (TimeoutException e) {
                log.error("Tool execution timeout: {}", toolName);
                results.put(toolName, Map.of("error", "Execution timeout", "success", false));
                executedTools.add(toolName + " (timeout)");
            } catch (Exception e) {
                log.error("Tool execution failed: {}", toolName, e);
                results.put(toolName, Map.of("error", e.getMessage(), "success", false));
                executedTools.add(toolName + " (failed)");
            }
        }

        return results;
    }

    /**
     * Execute a single tool
     */
    private Object executeSingleTool(String toolName, Map<String, Object> params,
                                      SkillContext context) throws Exception {
        Optional<ToolExecutor> executorOpt = toolRegistry.getExecutor(toolName);
        if (executorOpt.isEmpty()) {
            throw new IllegalArgumentException("Tool not found: " + toolName);
        }

        // Round 9 Fix (R8-β tail): respect factory_tool_configs disable flag.
        // R7b fixed this at ToolDispatchService but Skill orchestration was a 2nd path
        // that still ran disabled tools. Now skills also honor the Canvas switch.
        if (!toolRegistry.isToolEnabledForFactory(context.getFactoryId(), toolName)) {
            log.warn("Tool disabled for factory via Canvas: tool={}, factoryId={}, skill path",
                    toolName, context.getFactoryId());
            throw new IllegalStateException("Tool disabled for factory: " + toolName);
        }

        // S6-02: a governed tool never runs on the legacy path below, whatever the gateway answers.
        if (toolRuntimeRegistry != null && toolRuntimeRegistry.isGoverned(toolName)) {
            return executeThroughGateway(toolName, executorOpt.get(), params, context);
        }

        // W0 write-guard (intent-w0) — SITE D: Skill orchestration calls executor.execute() directly,
        // bypassing ToolDispatchService (Site B). Block a write tool unless a confirmed=true signal is
        // present (checked across the immediate tool params AND the SkillContext extractedParams).
        // Exception-based (mirrors the tool-not-found / tool-disabled throws above); the skill engine's
        // catch(Exception) blocks surface it. WriteGuardService is stateless → safe on the worker pool.
        com.cretas.aims.ai.tool.ToolExecutor wgTool = executorOpt.get();
        java.util.Map<String, Object> skCtx = params != null ? params
                : (context != null && context.getExtractedParams() != null ? context.getExtractedParams() : java.util.Map.of());
        boolean wgConfirmed = writeGuardService.isConfirmed(skCtx)
                || (context != null && writeGuardService.isConfirmed(context.getExtractedParams()));
        if (writeGuardService.isWriteTool(wgTool) && !wgConfirmed) {
            log.info("W0 write-guard (skill): blocked write tool {} (confirmed=false)", toolName);
            throw new IllegalStateException("W0 write-guard: tool '" + toolName + "' 需要显式确认后才能执行");
        }

        // SITE D: Skill orchestration bypasses Site B. Same role-gate chain as executeWithTool.
        // Identity comes from the SkillContext (server-authenticated), never from params — params
        // here are LLM-extracted and must not be able to carry a userId/userRole of their own.
        String factoryId = context != null ? context.getFactoryId() : null;
        Long userId = context != null ? parseUserId(context.getUserId()) : null;
        String userRole = context != null ? context.getUserRole() : null;
        java.util.Map<String, Object> rbacCtx = new java.util.HashMap<>(
                skCtx != null ? skCtx : java.util.Map.of());
        com.cretas.aims.service.execution.TrustedExecutionContext.enforcePrincipal(
                rbacCtx, factoryId, userId, userRole);
        if (toolPermissionChain == null) {
            throw new IllegalStateException("权限校验暂不可用，未执行「" + toolName + "」。");
        }
        // Only this concrete adapter selects the server-owned canonical purchase task. Other
        // Skills retain null intent; context/LLM intent strings never become policy authority.
        var fixedIntent = wgTool instanceof com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool
                ? com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool.canonicalIntent() : null;
        Optional<String> denied = toolPermissionChain.denyReason(wgTool, factoryId, userRole, fixedIntent, rbacCtx);
        if (denied.isPresent()) {
            log.warn("Skill 工具权限拒绝: tool={}, userRole={}", toolName, userRole);
            throw new IllegalStateException(denied.get());
        }

        ToolExecutor executor = executorOpt.get();

        // Bind every identity alias (userRole/role/user_id ...) to the authenticated principal so an
        // LLM-extracted "userRole" cannot reach the tool, neither via arguments nor via context —
        // same binding as executeWithTool.
        // W0 already consumed the process-local authority above. Never serialize it as a Tool
        // argument or expose it to business context; persisted domain grants remain authority.
        Map<String, Object> trustedParams = new HashMap<>(
                com.cretas.aims.ai.tool.WriteGuardService.withoutServerConfirmationMarker(params != null ? params : Map.of()));
        com.cretas.aims.service.execution.TrustedExecutionContext.enforcePrincipal(
                trustedParams, factoryId, userId, userRole);

        // Build ToolCall
        ToolCall toolCall = ToolCall.builder()
                .id(UUID.randomUUID().toString())
                .type("function")
                .function(ToolCall.FunctionCall.builder()
                        .name(toolName)
                        .arguments(objectMapper.writeValueAsString(trustedParams))
                        .build())
                .build();

        // Build execution context
        Map<String, Object> execContext = new HashMap<>(trustedParams);
        execContext.put("sessionId", context.getSessionId());

        long startTime = System.currentTimeMillis();
        String result;
        try {
            result = executor.execute(toolCall, execContext);
        } catch (Exception e) {
            recordStep(context, toolName, executor, trustedParams, "FAILED", null);
            throw e;
        }
        long duration = System.currentTimeMillis() - startTime;

        log.info("Tool {} executed in {}ms", toolName, duration);

        // Try to parse as JSON
        Object parsed;
        try {
            parsed = objectMapper.readTree(result);
        } catch (Exception e) {
            parsed = result;
        }
        boolean succeeded = parsed instanceof JsonNode node && node.path("success").asBoolean(false);
        recordStep(context, toolName, executor, trustedParams, succeeded ? "SUCCEEDED" : "FAILED", null);
        return parsed;
    }

    /**
     * Runs one governed tool through the tool gateway as the Skill's authenticated user. Identity,
     * confirmation and chain-context keys never reach the gateway parameters: the gateway binds
     * the tenant and user from its own principal re-read. Anything but SUCCEEDED stops this step
     * with the gateway's status and audit id; OUTCOME_UNKNOWN is reported as unknown, never success.
     */
    private Object executeThroughGateway(String toolName, ToolExecutor executor, Map<String, Object> params,
                                         SkillContext context) {
        String factoryId = context != null ? context.getFactoryId() : null;
        Long userId = context != null ? parseUserId(context.getUserId()) : null;
        String userRole = context != null ? context.getUserRole() : null;
        if (toolExecutionGateway == null || toolPrincipalFactory == null) {
            throw new IllegalStateException("工具网关暂不可用，未执行「" + toolName + "」。");
        }
        Map<String, Object> gatewayParams = gatewayParameters(params);
        com.cretas.aims.ai.tool.gateway.ExecutionPrincipal principal;
        try {
            principal = toolPrincipalFactory.create(factoryId, userId, userRole);
        } catch (SecurityException noIdentity) {
            recordStep(context, toolName, executor, gatewayParams, "DENIED", null);
            throw new IllegalStateException("没有可确认的执行人，未执行「" + toolName + "」。");
        }
        String correlation = context != null && context.getSessionId() != null && !context.getSessionId().isBlank()
                ? context.getSessionId() : "skill-" + UUID.randomUUID();
        com.cretas.aims.ai.tool.gateway.ToolExecutionCommand command =
                new com.cretas.aims.ai.tool.gateway.ToolExecutionCommand(
                        "skill-" + UUID.randomUUID(),
                        correlation,
                        "skill-" + UUID.randomUUID(),
                        toolName,
                        executor.getVersion(),
                        objectMapper.valueToTree(gatewayParams),
                        principal,
                        com.cretas.aims.ai.tool.gateway.ToolExecutionSource.SKILL_WORKFLOW,
                        com.cretas.aims.ai.tool.gateway.ToolExecutionMode.EXECUTE,
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        java.time.Instant.now().plusSeconds(Math.max(1, toolTimeoutSeconds)));
        com.cretas.aims.ai.tool.gateway.ToolExecutionResult result = toolExecutionGateway.execute(command);
        recordStep(context, toolName, executor, gatewayParams, result.status().name(), result.auditEventId());
        switch (result.status()) {
            case SUCCEEDED, IDEMPOTENT_REPLAY -> {
                return result.payload();
            }
            case OUTCOME_UNKNOWN, TIMEOUT -> throw new IllegalStateException("「" + toolName + "」执行结果未知（审计 "
                    + result.auditEventId() + "），不会自动重试，请核对后再处理。");
            case CONFIRMATION_REQUIRED, APPROVAL_REQUIRED -> throw new IllegalStateException("「" + toolName
                    + "」需要先确认才能执行，Skill 不会代为确认（审计 " + result.auditEventId() + "）。");
            case DENIED -> throw new IllegalStateException("「" + toolName + "」被工具网关拒绝：" + result.message()
                    + "（审计 " + result.auditEventId() + "）");
            default -> throw new IllegalStateException("「" + toolName + "」执行失败：" + result.status()
                    + "（审计 " + result.auditEventId() + "）");
        }
    }

    /** Caller/LLM parameters minus identity, confirmation, session and chain-context keys. */
    static Map<String, Object> gatewayParameters(Map<String, Object> params) {
        Map<String, Object> clean = new LinkedHashMap<>();
        if (params == null) {
            return clean;
        }
        Map<String, Object> withoutMarker =
                com.cretas.aims.ai.tool.WriteGuardService.withoutServerConfirmationMarker(params);
        for (Map.Entry<String, Object> entry : withoutMarker.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.startsWith("_") || key.startsWith("previous_")
                    || com.cretas.aims.service.skill.contract.SkillParameterContractValidator.isReservedKey(key)
                    || entry.getValue() == null) {
                continue;
            }
            clean.put(key, entry.getValue());
        }
        return clean;
    }

    private void recordStep(SkillContext context, String toolName, ToolExecutor executor, Map<String, Object> params,
                            String status, String auditEventId) {
        if (context == null) {
            return;
        }
        List<com.cretas.aims.service.skill.contract.SkillRunStore.Step> steps = runSteps.get(context);
        if (steps == null) {
            return;
        }
        Map<String, Object> recorded = gatewayParameters(params);
        recorded.remove("userQuery");
        JsonNode node;
        try {
            node = objectMapper.valueToTree(recorded);
        } catch (IllegalArgumentException notSerializable) {
            node = objectMapper.createObjectNode().put("_unrecordable", true);
        }
        steps.add(new com.cretas.aims.service.skill.contract.SkillRunStore.Step(
                toolName, executor.getVersion(), node, status, auditEventId));
    }

    /**
     * Closes a run: a gateway step that did not succeed makes the Skill unsuccessful (it is never
     * reported as success), and the run's receipt is recorded when there is an authenticated user.
     */
    private SkillResult finishRun(SkillDefinition skill, SkillContext context, SkillResult result) {
        List<com.cretas.aims.service.skill.contract.SkillRunStore.Step> steps;
        List<com.cretas.aims.service.skill.contract.SkillRunStore.Step> live = runSteps.get(context);
        if (live == null || result == null) {
            return result;
        }
        synchronized (live) {
            steps = List.copyOf(live);
        }
        if (steps.isEmpty()) {
            return result;
        }
        String status = runStatus(steps);
        List<String> refused = steps.stream()
                .filter(step -> step.auditEventId() != null && !"SUCCEEDED".equals(step.gatewayStatus()))
                .map(step -> step.tool() + "=" + step.gatewayStatus())
                .collect(Collectors.toList());
        if (!refused.isEmpty() && result.isSuccess()) {
            result.setSuccess(false);
            result.setMessage("Skill 未完成，工具网关没有放行：" + String.join("，", refused));
        }
        if (steps.stream().anyMatch(step -> "DENIED".equals(step.gatewayStatus()))) {
            result.setPermissionDenied(true);
        }
        if (!result.isSuccess() && "SUCCEEDED".equals(status)) {
            status = "FAILED";
        }
        Long userId = parseUserId(context.getUserId());
        if (runStore != null && userId != null && context.getFactoryId() != null) {
            String savedSkillId = skill.getSource() != null && skill.getSource().startsWith(SAVED_SKILL_SOURCE_PREFIX)
                    ? skill.getSource().substring(SAVED_SKILL_SOURCE_PREFIX.length()) : null;
            try {
                result.setRunId(runStore.recordRun(context.getFactoryId(), userId, skill.getName(), savedSkillId,
                        status, steps));
            } catch (RuntimeException receiptFailure) {
                // The tools already ran; without a receipt this run just cannot be saved or traced here.
                log.error("Skill run receipt not recorded: skill={}, status={}", skill.getName(), status, receiptFailure);
            }
        }
        return result;
    }

    static String runStatus(List<com.cretas.aims.service.skill.contract.SkillRunStore.Step> steps) {
        boolean unknown = false;
        boolean denied = false;
        boolean failed = false;
        for (com.cretas.aims.service.skill.contract.SkillRunStore.Step step : steps) {
            switch (step.gatewayStatus()) {
                case "SUCCEEDED", "IDEMPOTENT_REPLAY" -> { }
                case "OUTCOME_UNKNOWN", "TIMEOUT" -> unknown = true;
                case "DENIED", "CONFIRMATION_REQUIRED", "APPROVAL_REQUIRED", "PREVIEW_UNSUPPORTED" -> denied = true;
                default -> failed = true;
            }
        }
        return unknown ? "OUTCOME_UNKNOWN" : denied ? "DENIED" : failed ? "FAILED" : "SUCCEEDED";
    }

    /**
     * S6-02 contract mode: the caller's parameters are checked against the Skill's contract on the
     * server before any tool runs; the run then executes only the declared steps, in order, with
     * the validated values, and only through the tool gateway. The LLM is never consulted, so a
     * reused Skill cannot pick another tool or invent a parameter.
     */
    @SuppressWarnings("unchecked")
    private SkillResult executeContracted(SkillDefinition skill, SkillContext context,
                                          List<String> executedTools, long startTime) {
        com.cretas.aims.service.skill.contract.SkillParameterContract contract;
        try {
            contract = com.cretas.aims.service.skill.contract.SkillParameterContract
                    .fromConfig(skill.getConfig(), objectMapper).orElseThrow();
        } catch (IllegalArgumentException malformed) {
            return SkillResult.error(skill.getName(), "Skill 的参数契约无效：" + malformed.getMessage(),
                    executedTools, System.currentTimeMillis() - startTime);
        }
        if (contractValidator == null || toolRuntimeRegistry == null || toolExecutionGateway == null) {
            return SkillResult.error(skill.getName(), "参数契约校验或工具网关暂不可用，未执行",
                    executedTools, System.currentTimeMillis() - startTime);
        }
        List<Map<String, Object>> steps = new ArrayList<>();
        Object configured = skill.getConfig().get("steps");
        if (configured instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> step) || !(step.get("tool") instanceof String)) {
                    return SkillResult.error(skill.getName(), "Skill 的步骤定义无效", executedTools,
                            System.currentTimeMillis() - startTime);
                }
                steps.add((Map<String, Object>) step);
            }
        } else if (skill.getTools() != null) {
            skill.getTools().forEach(tool -> steps.add(Map.of("tool", tool)));
        }
        if (steps.isEmpty()) {
            return SkillResult.error(skill.getName(), "Skill 没有可执行的步骤", executedTools,
                    System.currentTimeMillis() - startTime);
        }
        for (Map<String, Object> step : steps) {
            String tool = (String) step.get("tool");
            if (!toolRuntimeRegistry.isGoverned(tool)) {
                return SkillResult.error(skill.getName(), "「" + tool + "」还没有纳入工具网关治理，不能作为可复用 Skill 执行",
                        executedTools, System.currentTimeMillis() - startTime);
            }
        }
        com.cretas.aims.service.skill.contract.SkillParameterContractValidator.Validation validation =
                contractValidator.validate(contract, context.getFactoryId(), context.getExtractedParams());
        if (!validation.ok()) {
            return SkillResult.error(skill.getName(), "参数不符合契约，未执行：" + String.join("；", validation.errors()),
                    executedTools, System.currentTimeMillis() - startTime);
        }
        Map<String, Object> results = new LinkedHashMap<>();
        for (Map<String, Object> step : steps) {
            String tool = (String) step.get("tool");
            Map<String, Object> stepParams = new LinkedHashMap<>();
            Object defaults = step.get("params");
            if (defaults instanceof Map<?, ?> map) {
                map.forEach((key, value) -> stepParams.put(String.valueOf(key), value));
            }
            for (Map.Entry<String, Object> accepted : validation.accepted().entrySet()) {
                // A contract value replaces the captured value of that name; a step that never had
                // the parameter keeps its captured parameters (a step without captured ones takes all).
                if (stepParams.containsKey(accepted.getKey()) || !(defaults instanceof Map<?, ?> m) || m.isEmpty()) {
                    stepParams.put(accepted.getKey(), accepted.getValue());
                }
            }
            try {
                Object result = executeSingleTool(tool, stepParams, context);
                results.put(tool, result);
                executedTools.add(tool);
            } catch (Exception stepFailure) {
                executedTools.add(tool + " (failed)");
                return SkillResult.builder()
                        .success(false)
                        .skillName(skill.getName())
                        .message(stepFailure.getMessage())
                        .partialResults(results)
                        .executedTools(executedTools)
                        .executionTime(System.currentTimeMillis() - startTime)
                        .build();
            }
        }
        results.put("_executionOrder", "contract");
        results.put("_toolCount", executedTools.size());
        return SkillResult.builder()
                .success(true)
                .skillName(skill.getName())
                .data(results)
                .executedTools(executedTools)
                .executionTime(System.currentTimeMillis() - startTime)
                .build();
    }

    /** Result of the permission preflight: tools checked, tools denied, or the check unavailable. */
    private record Preflight(int checked, List<String> denied, boolean unavailable) {
    }

    /** The gateway's own admission for a governed tool: principal re-read from the database, then policy resolve. */
    private boolean gatewayAdmits(ToolExecutor tool, SkillContext context) {
        if (toolPrincipalFactory == null || toolPrincipalPolicy == null) {
            return false;
        }
        try {
            com.cretas.aims.ai.tool.gateway.ExecutionPrincipal asserted = toolPrincipalFactory.create(
                    context.getFactoryId(), parseUserId(context.getUserId()), context.getUserRole());
            var current = toolPrincipalPolicy.rehydrate(asserted);
            if (current.isEmpty()) {
                return false;
            }
            String id = "skill-preflight-" + UUID.randomUUID();
            com.cretas.aims.ai.tool.gateway.ToolExecutionCommand probe = new com.cretas.aims.ai.tool.gateway.ToolExecutionCommand(
                    id, id, id, tool.getToolName(), tool.getVersion(), objectMapper.createObjectNode(), asserted,
                    com.cretas.aims.ai.tool.gateway.ToolExecutionSource.SKILL_WORKFLOW,
                    com.cretas.aims.ai.tool.gateway.ToolExecutionMode.EXECUTE,
                    Optional.empty(), Optional.empty(), Optional.empty(), java.time.Instant.now().plusSeconds(30));
            return toolRuntimeRegistry.resolve(probe, current.get().principal()).isPresent();
        } catch (RuntimeException rejected) {
            return false;
        }
    }

    private static String displayName(SkillDefinition skill) {
        return skill.getDisplayName() != null ? skill.getDisplayName() : skill.getName();
    }

    /**
     * Checks every tool a Skill can run (declared tools, DAG nodes and their fallbacks; contract steps)
     * with the same permission chain as each tool's execution, i.e. the same codes as the direct API
     * serving that data. Governed tools are left to the tool gateway, which re-reads the caller's
     * permissions itself.
     */
    private Preflight permissionPreflight(SkillDefinition skill, SkillContext context) {
        java.util.LinkedHashSet<String> tools = new java.util.LinkedHashSet<>();
        if (skill.getTools() != null) tools.addAll(skill.getTools());
        if (skill.getExecutionGraph() != null) {
            for (SkillDefinition.ExecutionNode node : skill.getExecutionGraph()) {
                if (node.getToolName() != null) tools.add(node.getToolName());
                if (node.getFallbackTool() != null) tools.add(node.getFallbackTool());
            }
        }
        if (skill.getConfig() != null && skill.getConfig().get("steps") instanceof List<?> steps) {
            for (Object step : steps) {
                if (step instanceof Map<?, ?> map && map.get("tool") instanceof String tool) tools.add(tool);
            }
        }
        String factoryId = context.getFactoryId();
        Long userId = parseUserId(context.getUserId());
        String userRole = context.getUserRole();
        List<String> denied = new ArrayList<>();
        int checked = 0;
        for (String toolName : tools) {
            Optional<ToolExecutor> tool = toolRegistry.getExecutor(toolName);
            if (tool.isEmpty()) {
                continue;  // missing tools fail at context validation
            }
            if (toolRuntimeRegistry != null && toolRuntimeRegistry.isGoverned(toolName)) {
                checked++;
                if (!gatewayAdmits(tool.get(), context)) {
                    denied.add(toolName);
                }
                continue;
            }
            if (toolPermissionChain == null) {
                return new Preflight(checked, denied, true);
            }
            checked++;
            Map<String, Object> rbacCtx = new HashMap<>();
            com.cretas.aims.service.execution.TrustedExecutionContext.enforcePrincipal(
                    rbacCtx, factoryId, userId, userRole);
            var fixedIntent = tool.get() instanceof com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool
                    ? com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool.canonicalIntent() : null;
            if (toolPermissionChain.denyReason(tool.get(), factoryId, userRole, fixedIntent, rbacCtx).isPresent()) {
                denied.add(toolName);
            }
        }
        return new Preflight(checked, List.copyOf(denied), false);
    }

    private static Long parseUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(userId.trim());
        } catch (NumberFormatException e) {
            return null;   // RBAC 按缺 userId 处理 = 拒绝 (fail-closed)
        }
    }

    /**
     * Truncate query for logging
     */
    private String truncateQuery(String query) {
        if (query == null) {
            return "";
        }
        return query.length() > 50 ? query.substring(0, 50) + "..." : query;
    }

    // ==================== P0: DAG Execution Engine ====================

    /**
     * Execute a skill using its DAG execution graph with conditional branching and error recovery.
     *
     * Algorithm:
     * 1. Build adjacency map from executionGraph
     * 2. Topological sort by dependencies
     * 3. Execute nodes in order, evaluating conditions against previous results
     * 4. Handle errors per errorStrategy (STOP / CONTINUE_ON_ERROR / RETRY / FALLBACK)
     */
    private SkillResult executeDAG(SkillDefinition skill, SkillContext context,
                                    List<String> executedTools, long startTime) {
        List<SkillDefinition.ExecutionNode> nodes = skill.getExecutionGraph();
        SkillDefinition.ErrorStrategy strategy = skill.getErrorStrategy();
        if (strategy == null) strategy = SkillDefinition.ErrorStrategy.STOP;

        log.info("DAG execution: skill={}, nodes={}, errorStrategy={}",
                skill.getName(), nodes.size(), strategy);

        // Build node lookup map
        Map<String, SkillDefinition.ExecutionNode> nodeMap = new LinkedHashMap<>();
        for (SkillDefinition.ExecutionNode node : nodes) {
            nodeMap.put(node.getId(), node);
        }

        // Topological sort
        List<String> sortedIds = topologicalSort(nodes);
        if (sortedIds == null) {
            return SkillResult.error(skill.getName(), "Circular dependency detected in execution graph",
                    executedTools, System.currentTimeMillis() - startTime);
        }

        // Execution state: nodeId -> result
        Map<String, NodeExecutionResult> nodeResults = new LinkedHashMap<>();
        Map<String, Object> partialResults = new LinkedHashMap<>();
        List<String> skippedNodes = new ArrayList<>();
        boolean hasError = false;

        // Build global params from context
        Map<String, Object> globalParams = new HashMap<>();
        if (context.getFactoryId() != null) globalParams.put("factoryId", context.getFactoryId());
        if (context.getUserId() != null) globalParams.put("userId", context.getUserId());
        if (context.getExtractedParams() != null) globalParams.putAll(context.getExtractedParams());

        for (String nodeId : sortedIds) {
            SkillDefinition.ExecutionNode node = nodeMap.get(nodeId);
            if (node == null) continue;

            // Check if all dependencies succeeded (or handle per strategy)
            if (node.hasDependencies()) {
                boolean depsMet = checkDependencies(node, nodeResults, strategy);
                if (!depsMet) {
                    log.info("DAG: skipping node {} — dependencies not met", nodeId);
                    skippedNodes.add(nodeId);
                    nodeResults.put(nodeId, NodeExecutionResult.skipped(nodeId));
                    continue;
                }
            }

            // Evaluate condition
            if (node.hasCondition()) {
                boolean conditionMet = evaluateCondition(node.getCondition(), nodeResults);
                if (!conditionMet) {
                    log.info("DAG: skipping node {} — condition not met: {}", nodeId, node.getCondition());
                    skippedNodes.add(nodeId);
                    nodeResults.put(nodeId, NodeExecutionResult.skipped(nodeId));
                    continue;
                }
            }

            // Execute the tool
            NodeExecutionResult result = executeNodeWithStrategy(
                    node, globalParams, nodeResults, context, strategy);
            nodeResults.put(nodeId, result);

            if (result.success) {
                executedTools.add(node.getToolName());
                partialResults.put(nodeId, result.data);
            } else {
                hasError = true;
                executedTools.add(node.getToolName() + " (failed)");
                partialResults.put(nodeId, Map.of("error", result.errorMessage, "success", false));

                if (strategy == SkillDefinition.ErrorStrategy.STOP) {
                    log.warn("DAG: stopping execution at node {} due to STOP strategy", nodeId);
                    break;
                }
            }
        }

        long executionTime = System.currentTimeMillis() - startTime;

        // Determine overall success: for CONTINUE_ON_ERROR, partial success is OK
        boolean overallSuccess = !hasError ||
                (strategy == SkillDefinition.ErrorStrategy.CONTINUE_ON_ERROR && !partialResults.isEmpty());

        log.info("DAG execution completed: skill={}, executed={}, skipped={}, hasError={}, time={}ms",
                skill.getName(), executedTools.size(), skippedNodes.size(), hasError, executionTime);

        return SkillResult.builder()
                .success(overallSuccess)
                .skillName(skill.getName())
                .data(partialResults)
                .executedTools(executedTools)
                .executionTime(executionTime)
                .partialResults(partialResults)
                .skippedNodes(skippedNodes)
                .errorStrategy(strategy.name())
                .message(overallSuccess ? "DAG execution completed" :
                        "DAG execution failed at one or more nodes")
                .build();
    }

    /**
     * Topological sort of execution nodes by dependencies.
     * Returns null if circular dependency detected.
     */
    private List<String> topologicalSort(List<SkillDefinition.ExecutionNode> nodes) {
        Map<String, List<String>> deps = new LinkedHashMap<>();
        Map<String, Integer> inDegree = new LinkedHashMap<>();

        for (SkillDefinition.ExecutionNode node : nodes) {
            deps.putIfAbsent(node.getId(), new ArrayList<>());
            inDegree.putIfAbsent(node.getId(), 0);

            if (node.getDependsOn() != null) {
                for (String dep : node.getDependsOn()) {
                    deps.computeIfAbsent(dep, k -> new ArrayList<>()).add(node.getId());
                    inDegree.merge(node.getId(), 1, Integer::sum);
                }
            }
        }

        Queue<String> queue = new LinkedList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        List<String> sorted = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.poll();
            sorted.add(current);
            for (String dependent : deps.getOrDefault(current, List.of())) {
                int newDegree = inDegree.merge(dependent, -1, Integer::sum);
                if (newDegree == 0) queue.add(dependent);
            }
        }

        if (sorted.size() != nodes.size()) {
            log.error("Circular dependency detected: sorted {} out of {} nodes", sorted.size(), nodes.size());
            return null;
        }
        return sorted;
    }

    /**
     * Check if a node's dependencies are satisfied based on the error strategy.
     */
    private boolean checkDependencies(SkillDefinition.ExecutionNode node,
                                       Map<String, NodeExecutionResult> nodeResults,
                                       SkillDefinition.ErrorStrategy strategy) {
        for (String depId : node.getDependsOn()) {
            NodeExecutionResult depResult = nodeResults.get(depId);
            if (depResult == null) return false; // dependency not yet executed
            if (depResult.skipped) continue; // skipped deps don't block

            if (!depResult.success && strategy == SkillDefinition.ErrorStrategy.STOP) {
                return false; // failed dep blocks under STOP
            }
        }
        return true;
    }

    /**
     * Execute a single node with error handling per strategy (RETRY, FALLBACK, etc.)
     */
    private NodeExecutionResult executeNodeWithStrategy(
            SkillDefinition.ExecutionNode node,
            Map<String, Object> globalParams,
            Map<String, NodeExecutionResult> nodeResults,
            SkillContext context,
            SkillDefinition.ErrorStrategy strategy) {

        // Merge params: global + node-specific + chain context from previous nodes
        Map<String, Object> mergedParams = new HashMap<>(globalParams);
        if (node.getParams() != null) mergedParams.putAll(node.getParams());

        // Add previous node results to context
        for (Map.Entry<String, NodeExecutionResult> entry : nodeResults.entrySet()) {
            if (entry.getValue().success && entry.getValue().data != null) {
                mergedParams.put("previous_" + entry.getKey(), entry.getValue().data);
            }
        }

        // Determine max attempts
        int maxAttempts = (strategy == SkillDefinition.ErrorStrategy.RETRY)
                ? Math.max(1, node.getMaxRetries()) : 1;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                Object result = executeSingleTool(node.getToolName(), mergedParams, context);
                return NodeExecutionResult.success(node.getId(), result);
            } catch (Exception e) {
                log.warn("DAG: node {} attempt {}/{} failed: {}",
                        node.getId(), attempt, maxAttempts, e.getMessage());

                if (attempt == maxAttempts) {
                    // Try fallback if available
                    if (strategy == SkillDefinition.ErrorStrategy.FALLBACK
                            && node.getFallbackTool() != null) {
                        return executeFallback(node, mergedParams, context, e.getMessage());
                    }
                    return NodeExecutionResult.error(node.getId(), e.getMessage());
                }
            }
        }
        return NodeExecutionResult.error(node.getId(), "Max retries exceeded");
    }

    /**
     * Execute a fallback tool when the primary tool fails.
     */
    private NodeExecutionResult executeFallback(SkillDefinition.ExecutionNode node,
                                                 Map<String, Object> params,
                                                 SkillContext context,
                                                 String originalError) {
        String fallback = node.getFallbackTool();
        log.info("DAG: executing fallback tool {} for node {} (original error: {})",
                fallback, node.getId(), originalError);
        try {
            Object result = executeSingleTool(fallback, params, context);
            return NodeExecutionResult.success(node.getId(), result);
        } catch (Exception e) {
            log.error("DAG: fallback tool {} also failed: {}", fallback, e.getMessage());
            return NodeExecutionResult.error(node.getId(),
                    "Primary (" + originalError + ") and fallback (" + e.getMessage() + ") both failed");
        }
    }

    /**
     * Evaluate a condition expression against node execution results.
     *
     * Supported syntax:
     * - "{nodeId}.success" → boolean
     * - "{nodeId}.data.{field}" → checks field existence and truthiness
     * - "&&", "||", "!" → boolean operators
     * - "true", "false" → literals
     */
    private boolean evaluateCondition(String condition, Map<String, NodeExecutionResult> nodeResults) {
        if (condition == null || condition.trim().isEmpty()) return true;

        try {
            return evaluateExpression(condition.trim(), nodeResults);
        } catch (Exception e) {
            log.warn("Condition evaluation failed for '{}': {}", condition, e.getMessage());
            return false; // fail-closed: skip node if condition can't be evaluated
        }
    }

    private boolean evaluateExpression(String expr, Map<String, NodeExecutionResult> nodeResults) {
        expr = expr.trim();

        // Handle parentheses
        if (expr.startsWith("(") && findMatchingParen(expr, 0) == expr.length() - 1) {
            return evaluateExpression(expr.substring(1, expr.length() - 1), nodeResults);
        }

        // Handle OR (lowest precedence, split on || not inside parens)
        int orIndex = findOperatorOutsideParens(expr, "||");
        if (orIndex >= 0) {
            return evaluateExpression(expr.substring(0, orIndex), nodeResults)
                    || evaluateExpression(expr.substring(orIndex + 2), nodeResults);
        }

        // Handle AND
        int andIndex = findOperatorOutsideParens(expr, "&&");
        if (andIndex >= 0) {
            return evaluateExpression(expr.substring(0, andIndex), nodeResults)
                    && evaluateExpression(expr.substring(andIndex + 2), nodeResults);
        }

        // Handle NOT
        if (expr.startsWith("!")) {
            return !evaluateExpression(expr.substring(1), nodeResults);
        }

        // Literals
        if ("true".equalsIgnoreCase(expr)) return true;
        if ("false".equalsIgnoreCase(expr)) return false;

        // Evaluate dotted reference: nodeId.success or nodeId.data.fieldName
        return evaluateDottedRef(expr, nodeResults);
    }

    private boolean evaluateDottedRef(String ref, Map<String, NodeExecutionResult> nodeResults) {
        String[] parts = ref.split("\\.", 3);
        if (parts.length < 2) return false;

        String nodeId = parts[0].trim();
        String field = parts[1].trim();

        NodeExecutionResult result = nodeResults.get(nodeId);
        if (result == null) return false;

        if ("success".equals(field)) {
            return result.success;
        }

        if ("data".equals(field) && parts.length >= 3) {
            String dataField = parts[2].trim();
            return evaluateDataField(result.data, dataField);
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private boolean evaluateDataField(Object data, String fieldName) {
        if (data == null) return false;

        // Try JsonNode
        if (data instanceof com.fasterxml.jackson.databind.JsonNode) {
            com.fasterxml.jackson.databind.JsonNode node = (com.fasterxml.jackson.databind.JsonNode) data;
            if (!node.has(fieldName)) return false;
            com.fasterxml.jackson.databind.JsonNode fieldNode = node.get(fieldName);
            if (fieldNode.isBoolean()) return fieldNode.asBoolean();
            if (fieldNode.isNumber()) return fieldNode.asDouble() > 0;
            if (fieldNode.isTextual()) return !fieldNode.asText().isEmpty();
            return !fieldNode.isNull();
        }

        // Try Map
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            Object value = map.get(fieldName);
            if (value == null) return false;
            if (value instanceof Boolean) return (Boolean) value;
            if (value instanceof Number) return ((Number) value).doubleValue() > 0;
            if (value instanceof String) return !((String) value).isEmpty();
            return true;
        }

        return false;
    }

    private int findOperatorOutsideParens(String expr, String operator) {
        int depth = 0;
        for (int i = 0; i < expr.length() - operator.length() + 1; i++) {
            char c = expr.charAt(i);
            if (c == '(') depth++;
            else if (c == ')') depth--;
            else if (depth == 0 && expr.startsWith(operator, i)) {
                return i;
            }
        }
        return -1;
    }

    private int findMatchingParen(String expr, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < expr.length(); i++) {
            if (expr.charAt(i) == '(') depth++;
            else if (expr.charAt(i) == ')') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    /**
     * Internal result holder for DAG node execution
     */
    private static class NodeExecutionResult {
        final String nodeId;
        final boolean success;
        final boolean skipped;
        final Object data;
        final String errorMessage;

        private NodeExecutionResult(String nodeId, boolean success, boolean skipped,
                                     Object data, String errorMessage) {
            this.nodeId = nodeId;
            this.success = success;
            this.skipped = skipped;
            this.data = data;
            this.errorMessage = errorMessage;
        }

        static NodeExecutionResult success(String nodeId, Object data) {
            return new NodeExecutionResult(nodeId, true, false, data, null);
        }

        static NodeExecutionResult error(String nodeId, String errorMessage) {
            return new NodeExecutionResult(nodeId, false, false, null, errorMessage);
        }

        static NodeExecutionResult skipped(String nodeId) {
            return new NodeExecutionResult(nodeId, false, true, null, "Skipped");
        }
    }

    // ==================== Inner Classes ====================

    /**
     * Tool execution plan
     */
    private static class ToolExecutionPlan {
        final String toolName;
        final Map<String, Object> params;
        final int order;

        ToolExecutionPlan(String toolName, Map<String, Object> params, int order) {
            this.toolName = toolName;
            this.params = params;
            this.order = order;
        }
    }
}
