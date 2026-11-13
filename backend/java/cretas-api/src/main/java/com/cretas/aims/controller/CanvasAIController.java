package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.config.CanvasConfigPlanBindingService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.utils.TokenUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/config/v2/ai")
@RequiredArgsConstructor
@Tag(name = "Canvas AI Agent", description = "Canvas AI 配置助手 (DashScope Qwen)")
public class CanvasAIController {

    private static final BigDecimal JS_MAX_SAFE_INTEGER = BigDecimal.valueOf(9_007_199_254_740_991L);
    private static final Object INVALID_CANONICAL_VALUE = new Object();

    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final DashScopeClient dashScopeClient;
    private final MobileService mobileService;
    private final ToolExecutionGateway toolExecutionGateway;

    /** Optional for direct unit-test construction; Spring injects the persistent proof issuer. */
    @Autowired(required = false)
    private PreviewTokenService previewTokenService;

    /** All six configuration writers require a server-owned state binding. */
    @Autowired
    private CanvasConfigPlanBindingService canvasConfigPlanBindingService;

    /** 发给模型前脱敏; 单测直接 new 本类时用默认实例 (不查保密产品名)。 */
    @Autowired
    private LlmEgressGuard llmEgressGuard = LlmEgressGuard.standalone();

    static final String APPROVAL_WORKFLOW_EGRESS_SITE = "canvas-approval-workflow";
    static final String PRODUCT_PROCESS_WORKFLOW_EGRESS_SITE = "canvas-product-process-workflow";
    static final String EGRESS_RESULT_SITE = "canvas-plan";
    static final String EGRESS_REFUSED_REPLY = "内容里有无法脱敏的信息, 未发给 AI, 请手动编辑";

    private static final String PRODUCT_WORK_PROCESS_MODULE = "product_work_process_config";
    private static final String PRODUCT_WORK_PROCESS_TOOL = "canvas_product_work_process_draft_preview";
    private static final String PRODUCT_WORK_PROCESS_VERSION = "1.0.0";
    private static final String PRODUCT_WORK_PROCESS_DRAFT_REPLY = "已生成工序配置草稿";

    private static final String PRODUCT_PROCESS_WORKFLOW_MODULE = "product_process_workflow_config";
    private static final String PRODUCT_PROCESS_WORKFLOW_TOOL = "canvas_product_process_workflow_config";
    private static final String PRODUCT_PROCESS_WORKFLOW_REPLY = "已生成可审核的 Workflow 本地补丁";
    private static final String PRODUCT_PROCESS_WORKFLOW_SYSTEM_PROMPT = """
            You are a preview-only product-process Workflow configuration assistant.
            Return only a JSON WorkflowPatch[] array. Never call tools, APIs, save, publish,
            activate, create products, create reports, create tasks, or change inventory.
            Allowed operations: UPSERT_NODE, REMOVE_NODE, UPSERT_EDGE, REMOVE_EDGE, SET_NODE_FIELD.
            SET_NODE_FIELD roots: name, skuId, skuCode, specification, ports, conversionRule,
            reportingRequired. Do not return any other operation or field path.
            The patch array is atomic. Order dependent patches as UPSERT_NODE first,
            SET_NODE_FIELD second, and UPSERT_EDGE last. Any invalid member rejects the whole array.

            Current definition: %s
            Selected node id: %s
            """;

    private static final String APPROVAL_WORKFLOW_MODULE = "approval_workflow_config";
    private static final String APPROVAL_WORKFLOW_REPLY = "当前审批草稿已更新";
    private static final String APPROVAL_WORKFLOW_SYSTEM_PROMPT = """
            You are a preview-only approval Workflow draft editor. Return ONLY one JSON object.
            Never call tools, APIs, save, publish, enable, archive, approve, reject, or change
            running approval instances. The frontend applies the result only to the reversible
            local DRAFT.

            Output shape:
            {
              "name": "<friendly workflow name>",
              "startNodeId": "<id of the only start node>",
              "nodes": [
                {
                  "id": "<preserve existing id; new ids use ai_<short_key>>",
                  "type": "start|approval|condition|parallel|join|notify|end",
                  "label": "<friendly Chinese label>",
                  "config": {
                    "approverRoles": ["<exact role code from directory>"],
                    "approverUserIds": ["<exact user id from directory>"],
                    "requiredApprovers": 1,
                    "timeoutMinutes": 0,
                    "departmentIds": [],
                    "delegateUserId": "<exact user id from directory>",
                    "notifyRoles": ["<exact role code from directory>"],
                    "channels": ["wechat|dingtalk|email"],
                    "mode": "ALL|N_OF_M|ANY",
                    "n": 2,
                    "outcome": "APPROVED|REJECTED|TIMEOUT|CANCELLED",
                    "description": "<friendly condition description>"
                  }
                }
              ],
              "edges": [
                {
                  "id": "<preserve existing id; new ids use ai_edge_<short_key>>",
                  "source": "<node id>",
                  "target": "<node id>",
                  "label": "<friendly branch label>",
                  "priority": 0,
                  "amountThreshold": 50000,
                  "default": false
                }
              ]
            }

            Rules:
            - Return the complete desired draft, preserving every unchanged node and edge.
            - Existing ids must remain stable. Do not invent role codes, user ids, or department ids.
            - Use only directory identities included in context. If the requested person or role
              is absent, return {"error":"<clear Chinese reason>"}.
            - Never output raw JSON expressions, SpEL, UUIDs for people, hashes, or database code.
              For amount routing use amountThreshold/default only.
            - Keep exactly one start node, at least one end node, no duplicate edges, no cycles,
              and no edges entering start or leaving end.
            - Omit config keys irrelevant to the node type. Preserve advanced fields that the user
              did not ask to change by keeping the existing node config.
            - If a selected Cell is present and the request says "this/current/selected", modify it.

            Current approval draft and allowed directories:
            %s
            """;

    // 长久方案 (弃补丁): LLM 只出「语义规格」(rawMaterials + steps), 不再拼底层图补丁。
    // 前端确定性编译器 (复用 createProcessBranch) 把规格建成合法图 —— LLM 再也不碰 node/edge/id。
    private static final String PRODUCT_PROCESS_WORKFLOW_SPEC_PROMPT = """
            You are a product-process Workflow planning assistant. From the user's natural-language
            description, output ONLY a JSON object describing the workflow at a SEMANTIC level.
            Do NOT output graph nodes / edges / ids / patches. Never call tools, save, publish, or
            change data. The frontend deterministically builds the graph from your JSON.

            Output shape (JSON only, no prose):
            {
              "rawMaterials": ["<entry raw material name>", ...],
              "steps": [
                {
                  "process": "<work process name, e.g. 清洗/卤制/切片装盒>",
                  "inputs": ["<extra raw material name>", ...],
                  "processCategory": "<optional: 熟制/注射/切配/... — the work process category>",
                  "outputs": [
                    { "kind": "SEMI_FINISHED"|"FINISHED_GOOD", "name": "<output product name>",
                       "unit": "<kg/盒/只/...>", "byproduct": false, "rework": false,
                       "fixedCostShare": <number 0-100 or null, ONLY when the step uses FIXED_RATIO> }
                  ],
                   "costAllocationMethod": "WEIGHT"|"FIXED_RATIO",
                  "inputShares": [ { "name": "<input raw material name>", "percent": <number 0-100 or null> } ],
                  "reworkSources": [ { "output": "<name of a rework output in this spec>", "inputRatioPercent": <0-100 or null> } ],
                  "ccps": [
                    { "name": "<CCP name, e.g. 中心温度>", "hazardType": "BIOLOGICAL"|"CHEMICAL"|"PHYSICAL"|null,
                      "criticalLimitMin": <number or null>, "criticalLimitMax": <number or null>, "unit": "<℃/min/...>"|null }
                  ],
                  "processParams": [
                    { "label": "<process parameter name, e.g. 油温/炒制时间/绞碎孔径>", "unit": "<℃/min/mm/percent/...>"|null,
                      "standardValue": <number or null>, "min": <number or null>, "max": <number or null> }
                  ],
                  "seasonings": [
                    { "name": "<seasoning/auxiliary material name>", "dosagePerKgG": <grams per kg of input>,
                      "subsequentPotRatio": <optional 0-100, ONLY for 熟制 steps> }
                  ],
                  "injectionAmount": <optional kg, ONLY for 注射 steps>
                }
              ]
            }
            Rules:
            - steps are ordered; each step consumes the previous step's first output
              (step 1 consumes the rawMaterials).
            - a step with multiple outputs = 分流/多产出 (one process yields several products).
             - costAllocationMethod is optional. Omitted (or WEIGHT) keeps the existing按重量行为.
               Only emit FIXED_RATIO when the user explicitly asks for fixed output cost shares.
               In FIXED_RATIO, match each fixedCostShare to an output by its exact name; every named
               output needs a finite positive share and all shares must total exactly 100. If an output
               is missing, duplicated/ambiguous, unmatched, invalid, or the total is not 100, keep the
               unresolved value as null and make the item explicit for human confirmation. Never guess,
               default, truncate, or silently clear an existing allocation.
            - "inputs" (optional) = 合流/混批: EXTRA raw materials this step也投入, on TOP of
              the previous step's output. Use it when the user says several ingredients/materials
              are combined/mixed/assembled into one process (e.g. 拼装/分装/调配/混合). Omit or []
              when the step只沿主链 consumes the upstream output. Do NOT list the previous step's
              output here — only NEW raw materials entering at this step.
            - the LAST step output is usually FINISHED_GOOD; intermediate outputs are SEMI_FINISHED.
            - use the user's own wording for process/output/material names. Keep it minimal, valid JSON only.
            - "byproduct": true marks an output as 副产 (a by-product such as 鸡架/骨头/肥油 that is
              warehoused but is not the main product). Quantity is entered at reporting time, never here.
            - "inputShares" (optional) = 主料份额: when the user gives a ratio between this step's input raw
              materials (e.g. "牛肉猪肉 3:7"), put BOTH materials in the step's inputs (the first one may be
              rawMaterials[0]) and give percent 30 / 70 (relative amounts, need not sum to 100).
            - an output the user calls 边角料/碎料 that is a by-product to warehouse = "byproduct": true.
              An output the user says goes back into production (回料) = "rework": true, and the process that takes
              it back lists it in "reworkSources" with inputRatioPercent (percent of that process's main input).
            - "ccps" (optional) = 关键控制点 on this step (e.g. "卤制要测中心温度" → {"name":"中心温度"}).
              ⚠️ NEVER invent limits, units or hazard types the user did not say. Use null for anything not
              stated — the frontend marks null fields 待确认 and a person must fill them before publishing.
            - "processParams" (optional) = 工艺标准参数 the user states for this step as numbers
              (e.g. "油温 150–160℃" → {"label":"油温","unit":"℃","min":150,"max":160};
              "炒制 20 分钟" → {"label":"炒制时间","unit":"min","standardValue":20}). A range gives min/max;
              a single figure gives standardValue. Convert unit words (度/摄氏度→℃, 分钟→min). This is NOT a
              CCP: use "ccps" only when the user says 关键控制点/CCP/安全限值. ⚠️ NEVER invent numbers or
              units the user did not say; use null — the frontend marks incomplete params 待确认 and
              nothing is written to the work process until a person confirms and publishes.
            - "seasonings" (optional) = 调料/辅料 INPUT amounts for this step. dosagePerKgG MUST be > 0.
            - "subsequentPotRatio" (0-100) is ONLY meaningful on 熟制 steps; "injectionAmount" (> 0) is
              ONLY meaningful on 注射 steps. Do NOT emit them on other步骤 —— the frontend rejects
              them and tells the user, which just wastes a round trip.

            Current definition (context, may be empty): %s
            Selected node id: %s
            """;

    // D3: narrow route for work-process catalog (create/update work-process master data)
    private static final String WORK_PROCESS_CATALOG_MODULE = "work_process_catalog";
    private static final String WORK_PROCESS_CATALOG_TOOL = "canvas_work_process_catalog";
    private static final String WORK_PROCESS_CATALOG_VERSION = "1.1.0";
    /**
     * Prose → canvas_work_process_catalog parameters. This prompt used to live in the
     * browser (WorkProcessAIChatPanel.buildInstruction) and was posted as the chat
     * `message`, which meant it was never read by any model — this narrow route skips
     * the generic LLM path. It belongs on the server, next to the tool whose schema it
     * describes, so the two cannot drift apart.
     */
    private static final String WORK_PROCESS_CATALOG_SYSTEM_PROMPT = """
            你是工序主数据抽取器。把用户的中文描述转成工序管理工具的调用参数。
            只返回一个 JSON 对象，不要解释，不要代码围栏。

            字段:
            - action: "create"(新建/新增/加一道) 或 "update"(修改/更新/把某工序改成)，必填
            - id: action=update 且用户给了工序ID时填；没给就省略
            - processName: 工序名称，如 焯水、滚揉、装盒
            - processCategory: 工序类别，如 前处理、加工、包装、质检
            - estimatedMinutes: 标准工时，整数，单位分钟
            - standardYieldMin / standardYieldMax: 标准出成率上下限，用小数，例如 30% 写 0.30
            - needsInput: 是否需要录投入量，true / false
            - standardHourlyRate: 标准时薪，数字，单位元/小时
            - customFieldSchema: 可选的逐工序录入字段数组。每项使用 key/label/type/enabled；type=number 时，
              可按用户明确说出的工艺标准填写 unit/standardValue/min/max，例如油温标准 155℃、范围 150~160：
              [{"key":"oilTemp","label":"油温","type":"number","enabled":true,"unit":"℃",
                "standardValue":155,"min":150,"max":160}]

            规则:
            - 工序主数据不维护投入/产出单位，禁止生成顶层 unit 或 outputUnit；customFieldSchema 内的 unit 是工艺参数单位，可按用户明确输入填写。
            - 用户没提到的字段一律省略，不要猜、不要补默认值；尤其不要凭空生成工艺标准数值。
            - action=update 时省略 customFieldSchema 表示保持原配置，不要隐式清空；只有用户明确要求清空时才返回 []。
            - customFieldSchema 必须是安全的 JSON 数组；key/label/type 必填且 key 唯一，标准值/上下限只能用于 number，
              非数字、畸形对象、重复 key 或 min 大于 max 都不要猜测，返回 {"error":"..."}。
            - 如果无法判断要做什么，返回 {"error":"<一句中文说明还缺什么>"}。

            例子:
            输入: 新建焯水工序，加工类，标准时薪25
            输出: {"action":"create","processName":"焯水","processCategory":"加工","standardHourlyRate":25}
            输入: 新建油炸工序，油温标准155℃，允许150到160℃
            输出: {"action":"create","processName":"油炸","customFieldSchema":[{"key":"oilTemp","label":"油温","type":"number","enabled":true,"unit":"℃","standardValue":155,"min":150,"max":160}]}
            """;

    /**
     * Only canvas_* tools are allowed via AI chat to prevent prompt injection from
     * calling data-destructive or system-level tools.
     */
    private static final Pattern CANVAS_TOOL_PATTERN = Pattern.compile("^canvas_[a-z0-9_]+$");

    private void validateCanvasTool(String toolName) {
        if (toolName == null || !CANVAS_TOOL_PATTERN.matcher(toolName).matches()) {
            // e.getMessage() 会原样进 errors 列表回传前端 Canvas AI 聊天面板 (applyDiffs 里的
            // catch 块) —— 此前是纯英文, 用户看到的是一句读不懂的技术报错。
            throw new BusinessException("Canvas AI 只允许调用 canvas_* 工具, 已拒绝: " + toolName);
        }
    }

    private Map<String, Object> buildToolContext(String factoryId, String authorization) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("factoryId", factoryId);
        try {
            if (authorization != null) {
                String token = TokenUtils.extractToken(authorization);
                Long userId = mobileService.getUserFromToken(token).getId();
                if (userId != null) {
                    ctx.put("userId", userId);
                    ctx.put("operatorId", userId);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to extract userId from token: {}", e.getMessage());
        }
        return ctx;
    }

    private static final String CANVAS_SYSTEM_PROMPT = """
            你是工厂配置助手。用户通过 Canvas 配置系统管理工厂的模块、字段、工作流、校验规则、触发链和AI工具。

            你可以调用以下 Canvas 配置工具:
            - canvas_toggle_module: 启用/禁用工厂模块 (参数: moduleCode, enabled)
            - canvas_toggle_tool: 启用/禁用AI工具 (参数: toolName, enabled)
            - canvas_toggle_skill: 启用/禁用AI技能 (参数: skillName, enabled)
            - canvas_update_field: 修改字段属性 (参数: moduleCode, fieldCode, property[visible/required/label/defaultValue], value)
            - canvas_update_trigger_chain: 修改触发链 (参数: chainCode, enabled, stepOrder, stepEnabled)
            - canvas_apply_template: 应用行业模板 (参数: templateCode[FOOD_PROCESSING/BAKERY/AQUACULTURE])

            可用模块: sales_order, purchase_order, bom, production_plan, production_report,
            quality_inspection, inventory, inbound, outbound, equipment, customer, supplier,
            finance_ar, finance_ap, hr_employee, transfer, traceability
            """;

    @Data
    public static class AIRequest {
        private String message;
        private String mode; // autopilot, plan, action
        private String moduleCode;
        private Map<String, Object> params;
    }

    @Data
    public static class AIResponse {
        private String reply;
        private List<Map<String, Object>> diffs;
        private boolean applied;
    }

    @PostMapping("/chat")
    @Operation(summary = "Canvas AI 对话 (DashScope Qwen)")
    // Round 7a P0 fix: AI chat in autopilot mode calls arbitrary canvas_* tools via LLM.
    // Previously NO auth check — any authenticated user (viewer/operator/etc) could run
    // Unsupported cross-domain template instructions must not be turned into DDL-level changes.
    // Now restricted to canvas config admins only.
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<AIResponse> chat(
            @PathVariable String factoryId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestBody AIRequest request) {

        AIResponse response = new AIResponse();
        String mode = request.getMode() != null ? request.getMode() : "action";
        String message = request.getMessage();
        log.info("Canvas AI [{}] factory={}: {}", mode, factoryId, message);
        try {
            if (PRODUCT_WORK_PROCESS_MODULE.equals(request.getModuleCode())) {
                AIResponse productWorkProcess = handleProductWorkProcessConfigChat(
                        request, authenticatedPrincipal(factoryId, authorization));
                // This route returns a local product/work-process draft. The later human
                // save flow owns its authorization; do not mint a generic Canvas EXECUTE proof.
                return ApiResponse.success(productWorkProcess);
            }
            // D3: work_process_catalog narrow route — bypasses LLM prompt entirely
            if (WORK_PROCESS_CATALOG_MODULE.equals(request.getModuleCode())) {
                AIResponse workProcessCatalog = handleWorkProcessCatalogChat(
                        request, authenticatedPrincipal(factoryId, authorization));
                return ApiResponse.success(bindApplyableDiffs(factoryId, authorization, workProcessCatalog));
            }
            if (APPROVAL_WORKFLOW_MODULE.equals(request.getModuleCode())) {
                return ApiResponse.success(handleApprovalWorkflowConfigChat(request, factoryId));
            }

            Map<String, Object> toolContext = buildToolContext(factoryId, authorization);
            if (PRODUCT_PROCESS_WORKFLOW_MODULE.equals(request.getModuleCode())) {
                return ApiResponse.success(handleProductProcessWorkflowConfigChat(request, toolContext));
            }

            switch (mode) {
                case "autopilot" -> {
                    List<Map<String, Object>> diffs = generatePlan(factoryId, message);
                    diffs = bindApplyableDiffs(factoryId, authorization, diffs);
                    response.setDiffs(diffs);
                    response.setReply("自动模式已生成 " + diffs.size()
                            + " 项候选变更。首次请求不会写入，请明确确认后执行。");
                    response.setApplied(false);
                }
                case "plan" -> {
                    List<Map<String, Object>> diffs = generatePlan(factoryId, message);
                    diffs = bindApplyableDiffs(factoryId, authorization, diffs);
                    response.setDiffs(diffs);
                    response.setReply("已生成 " + diffs.size() + " 项变更方案，请逐项审核后应用。");
                    response.setApplied(false);
                }
                case "action" -> {
                    response.setReply(analyzeImpact(factoryId, message));
                    response.setApplied(false);
                }
                default -> response.setReply("未知模式: " + mode);
            }
        } catch (Exception e) {
            log.error("Canvas AI error: {}", e.getMessage(), e);
            response.setReply("AI 处理失败: " + e.getMessage());
        }

        return ApiResponse.success(response);
    }

    private AIResponse handleApprovalWorkflowConfigChat(AIRequest request, String factoryId) throws Exception {
        Map<String, Object> requestParams = request.getParams() != null
                ? request.getParams()
                : Map.of();
        Map<String, Object> context = readStringObjectMap(requestParams.get("context"));
        // 审批人名单里的姓名 / 登录名换成 [人员A], 管理员原话里提到同一个人时是同一个代号; 用户 id 照发 (草稿要引用)
        LlmEgressRedactor.Result redacted = llmEgressGuard.texts(APPROVAL_WORKFLOW_EGRESS_SITE, factoryId,
                approvalDirectoryNames(context),
                Arrays.asList(objectMapper.writeValueAsString(context), request.getMessage()));
        if (redacted.refused()) {
            return approvalWorkflowFailureResponse(EGRESS_REFUSED_REPLY);
        }
        String prompt = APPROVAL_WORKFLOW_SYSTEM_PROMPT.formatted(redacted.text(0));
        String llmResponse = dashScopeClient.chatLowTemp(prompt, redacted.text(1));
        Map<String, Object> spec;
        try {
            spec = castStringObjectMap(redacted.restoreDeep(objectMapper.readValue(
                    extractJsonObject(llmResponse), new TypeReference<Map<String, Object>>() {})));
        } catch (Exception parseError) {
            return approvalWorkflowFailureResponse("AI 未能生成可用的审批草稿，请换一种说法重试");
        }
        if (spec.get("error") != null) {
            return approvalWorkflowFailureResponse(Objects.toString(spec.get("error")));
        }
        if (!(spec.get("nodes") instanceof List<?> nodeList) || nodeList.isEmpty()
                || !(spec.get("edges") instanceof List<?>)
                || !(spec.get("startNodeId") instanceof String startNodeId)
                || startNodeId.isBlank()) {
            return approvalWorkflowFailureResponse("AI 返回的审批草稿结构不完整，请把审批顺序说清楚后重试");
        }

        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(APPROVAL_WORKFLOW_REPLY);
        response.setDiffs(List.of(Map.of(
                "type", "APPROVAL_WORKFLOW_SPEC",
                "tool", "canvas_approval_workflow_draft",
                "params", Map.of("spec", spec),
                "description", APPROVAL_WORKFLOW_REPLY)));
        return response;
    }

    /** 前端审批目录 {@code users: [{id, username, name}]} 里的姓名与登录名。 */
    private static Map<String, LlmEgressRedactor.Party> approvalDirectoryNames(Map<String, Object> context) {
        Map<String, LlmEgressRedactor.Party> names = new LinkedHashMap<>();
        if (context.get("users") instanceof List<?> users) {
            for (Object u : users) {
                if (u instanceof Map<?, ?> user) {
                    for (String key : List.of("name", "username", "fullName", "realName")) {
                        if (user.get(key) instanceof String name && !name.isBlank()) {
                            names.put(name, LlmEgressRedactor.Party.PERSON);
                        }
                    }
                }
            }
        }
        return names;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castStringObjectMap(Object restored) {
        return (Map<String, Object>) restored;
    }

    private AIResponse approvalWorkflowFailureResponse(String message) {
        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setDiffs(List.of());
        response.setReply(message);
        return response;
    }

    /**
     * Workflow-only route. It always returns before generic autopilot/plan/action handling and
     * can only call the dedicated tool's public preview entry point.
     */
    private AIResponse handleProductProcessWorkflowConfigChat(
            AIRequest request, Map<String, Object> toolContext) throws Exception {
        ToolExecutor executor = toolRegistry.getExecutor(PRODUCT_PROCESS_WORKFLOW_TOOL)
                .orElseThrow(() -> new BusinessException("工具不存在: " + PRODUCT_PROCESS_WORKFLOW_TOOL));

        Map<String, Object> requestParams = request.getParams() != null
                ? request.getParams()
                : Map.of();
        Map<String, Object> nestedContext = readStringObjectMap(requestParams.get("context"));
        Object definition = requestParams.containsKey("definition")
                ? requestParams.get("definition")
                : nestedContext.get("definition");
        Object selectedNodeId = requestParams.containsKey("selectedNodeId")
                ? requestParams.get("selectedNodeId")
                : nestedContext.get("selectedNodeId");

        // 发给模型前: 工艺保密产品名、号码、公司名 / 人名换代号, 脱敏不了就不发
        LlmEgressRedactor.Result redacted = llmEgressGuard.texts(PRODUCT_PROCESS_WORKFLOW_EGRESS_SITE,
                Objects.toString(toolContext.get("factoryId"), null), Map.of(),
                Arrays.asList(objectMapper.writeValueAsString(definition), request.getMessage(),
                        Objects.toString(selectedNodeId, "null")));
        if (redacted.refused()) {
            AIResponse refused = workflowSpecFailureResponse();
            refused.setReply(EGRESS_REFUSED_REPLY);
            return refused;
        }
        // 弃补丁: 让 LLM 只产出语义规格 (rawMaterials + steps), 前端确定性编译成图。
        String prompt = PRODUCT_PROCESS_WORKFLOW_SPEC_PROMPT.formatted(
                redacted.text(0),
                redacted.text(2));
        String llmResponse = dashScopeClient.chatLowTemp(prompt, redacted.text(1));
        Map<String, Object> spec;
        try {
            // 规格是 JSON 对象 {...} 不是数组, 必须用 extractJsonObject (extractJson 只抓 [...] 数组)。
            spec = castStringObjectMap(redacted.restoreDeep(objectMapper.readValue(
                    extractJsonObject(llmResponse), new TypeReference<Map<String, Object>>() {})));
        } catch (Exception parseError) {
            return workflowSpecFailureResponse();
        }
        // 只校验规格 shape (steps 非空); 图的合法性由前端编译器保证 (端口/边/id 全代码生成)。
        if (!(spec.get("steps") instanceof List<?> stepList) || stepList.isEmpty()) {
            return workflowSpecFailureResponse();
        }

        Map<String, Object> diffParams = new LinkedHashMap<>();
        diffParams.put("spec", spec);

        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(PRODUCT_PROCESS_WORKFLOW_REPLY);
        response.setDiffs(List.of(Map.of(
                "type", "PRODUCT_PROCESS_WORKFLOW_SPEC",
                "tool", PRODUCT_PROCESS_WORKFLOW_TOOL,
                "params", diffParams,
                "description", PRODUCT_PROCESS_WORKFLOW_REPLY)));
        return response;
    }

    private AIResponse workflowPatchFailureResponse() {
        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setDiffs(List.of());
        response.setReply("AI 未返回可审核的 Workflow 补丁，请调整描述后重试");
        return response;
    }

    private AIResponse workflowSpecFailureResponse() {
        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setDiffs(List.of());
        response.setReply("AI 未能从描述里解析出可用的工序步骤，请把每一步的工序名和产出说清楚后重试");
        return response;
    }

    private Map<String, Object> readStringObjectMap(Object value) {
        if (!(value instanceof Map<?, ?> source)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, entryValue) -> {
            if (key instanceof String stringKey) {
                result.put(stringKey, entryValue);
            }
        });
        return result;
    }

    private AIResponse bindApplyableDiffs(
            String factoryId, String authorization, AIResponse response) {
        if (response == null) {
            return null;
        }
        response.setDiffs(bindApplyableDiffs(factoryId, authorization, response.getDiffs()));
        return response;
    }

    /** Issue one persistent proof per selectable diff; sibling intent codes are never reused. */
    private List<Map<String, Object>> bindApplyableDiffs(
            String factoryId, String authorization, List<Map<String, Object>> diffs) {
        if (diffs == null || diffs.isEmpty() || previewTokenService == null) {
            return diffs;
        }
        ExecutionPrincipal principal = authenticatedPrincipal(factoryId, authorization);
        Long userId = Long.valueOf(principal.principalId());
        List<Map<String, Object>> bound = new ArrayList<>(diffs.size());
        for (Map<String, Object> diff : diffs) {
            if (diff == null) {
                bound.add(null);
                continue;
            }
            String toolName = diff.get("tool") instanceof String name ? name : "";
            Map<String, Object> params = readStringObjectMap(diff.get("params"));
            if (toolName.isBlank()
                    || PRODUCT_PROCESS_WORKFLOW_TOOL.equals(toolName)
                    || PRODUCT_WORK_PROCESS_TOOL.equals(toolName)
                    || !isExecutableCanvasTool(toolName)
                    || !(diff.get("params") instanceof Map<?, ?>)) {
                bound.add(diff);
                continue;
            }
            Map<String, Object> canonicalParams = canonicalJsonMap(params);
            if (canonicalParams == null) {
                bound.add(diff);
                continue;
            }
            Optional<ToolExecutor> executor = toolRegistry.getExecutor(toolName);
            if (executor.isEmpty() || !CANVAS_TOOL_PATTERN.matcher(toolName).matches()) {
                bound.add(diff);
                continue;
            }
            Map<String, Object> persistedParameters = canonicalParams;
            if (CanvasConfigPlanBindingService.isConfigurationTool(toolName)) {
                if (canvasConfigPlanBindingService == null) {
                    // Direct construction must not turn a missing mandatory bean into an unbound proof.
                    bound.add(diff);
                    continue;
                }
                persistedParameters = canvasConfigPlanBindingService.issue(
                        factoryId, toolName, canonicalParams);
            }
            IntentPreviewToken token = previewTokenService.createBoundToken(
                    new PreviewTokenService.BoundTokenRequest(
                            factoryId,
                            userId,
                            null,
                            "CANVAS_DIFF_" + UUID.randomUUID(),
                            "Canvas AI configuration diff",
                            toolName,
                            executor.get().getVersion(),
                            ToolExecutionMode.EXECUTE,
                            "CANVAS_DIFF",
                            toolName,
                            "UPDATE",
                            persistedParameters,
                            Map.of(),
                            canonicalParams,
                            300));
            if (token == null || token.getToken() == null || token.getCommandDigest() == null) {
                bound.add(diff);
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(diff);
            copy.put("params", canonicalParams);
            copy.put("proof", Map.of(
                    "token", token.getToken(),
                    "commandDigest", token.getCommandDigest(),
                    "descriptorVersion", executor.get().getVersion()));
            bound.add(copy);
        }
        return bound;
    }

    /**
     * D1 narrow module route: product work-process config needs catalog-aware
     * parsing by a business Tool, not the generic Canvas prompt. Existing canvas
     * modules keep the original LLM path above.
     */
    private AIResponse handleProductWorkProcessConfigChat(
            AIRequest request, ExecutionPrincipal principal) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (request.getParams() != null) {
            params.putAll(request.getParams());
        }
        params.put("message", request.getMessage());
        params.put("apply", false);

        ToolExecutionResult gatewayResult = executePreview(
                PRODUCT_WORK_PROCESS_TOOL, PRODUCT_WORK_PROCESS_VERSION, params, principal);
        Map<String, Object> toolResponse = gatewayPayload(gatewayResult);

        // D1 B-1: surface tool failures instead of silently returning a fake success draft
        if (!Boolean.TRUE.equals(toolResponse.get("success"))) {
            String errorMsg = toolFailureMessage(toolResponse,
                    "工序配置工具没能处理这次请求。请补充产品和工序顺序后重试。");
            AIResponse errorResponse = new AIResponse();
            errorResponse.setApplied(false);
            errorResponse.setReply(errorMsg);
            errorResponse.setDiffs(List.of());
            return errorResponse;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) toolResponse.getOrDefault("data", Map.of());

        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(Objects.toString(
                data.getOrDefault("message", PRODUCT_WORK_PROCESS_DRAFT_REPLY),
                PRODUCT_WORK_PROCESS_DRAFT_REPLY));
        response.setDiffs(List.of(Map.of(
                "type", "PRODUCT_WORK_PROCESS_DRAFT",
                "tool", PRODUCT_WORK_PROCESS_TOOL,
                "params", data,
                "description", response.getReply()
        )));
        return response;
    }

    /**
     * D3 narrow module route: work-process catalog (create/update work-process master data).
     * Mirrors handleProductWorkProcessConfigChat — directly calls the tool, no LLM intermediary.
     * This ensures canvas_work_process_catalog is reachable without being listed in
     * CANVAS_SYSTEM_PROMPT (where it was previously missing and thus never called via autopilot/plan).
     */
    private AIResponse handleWorkProcessCatalogChat(
            AIRequest request, ExecutionPrincipal principal) {
        String mode = request.getMode() == null ? "action" : request.getMode().trim().toLowerCase();
        if (!"autopilot".equals(mode) && !"plan".equals(mode) && !"action".equals(mode)) {
            throw new BusinessException(400, "未知模式: " + mode);
        }

        Map<String, Object> params = new LinkedHashMap<>();
        if (request.getParams() != null) {
            params.putAll(request.getParams());
        }

        // D3 fix (2026-08-19): canvas_work_process_catalog takes typed parameters
        // (action / processName / processCategory / ...), and on this route nobody
        // ever produced them — the panel posted prose and this narrow route skips the
        // generic LLM plan path, so `action` was always absent and every one of the
        // three entries (计划/自动/建议) died in validateRequiredParams.
        // Extract the parameters here, mirroring handleApprovalWorkflowConfigChat:
        // the tool keeps its typed contract (so ToolRouter / Skill function-calling
        // still work off getParametersSchema), and the prose→params step lives on the
        // server next to the tool it describes.
        if (params.get("action") == null) {
            AIResponse extractionFailure =
                    fillWorkProcessCatalogParams(request.getMessage(), params, principal.tenantId());
            if (extractionFailure != null) {
                return extractionFailure;
            }
        }
        params.put("message", request.getMessage());

        ToolExecutionResult gatewayResult = executePreview(
                WORK_PROCESS_CATALOG_TOOL, WORK_PROCESS_CATALOG_VERSION, params, principal);
        Map<String, Object> toolResponse = gatewayPayload(gatewayResult);

        // D3 B-1: surface tool failures — never return a fake success
        if (!Boolean.TRUE.equals(toolResponse.get("success"))) {
            String errorMsg = toolFailureMessage(toolResponse,
                    "工序管理工具没能处理这次请求。请写清楚动作和工序名，"
                            + "例如「新建焯水工序，加工类，标准时薪25」。");
            AIResponse errorResponse = new AIResponse();
            errorResponse.setApplied(false);
            errorResponse.setReply(errorMsg);
            errorResponse.setDiffs(List.of());
            return errorResponse;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) toolResponse.getOrDefault("data", Map.of());
        String replyMsg = Objects.toString(data.getOrDefault("message", "工序操作已完成"), "工序操作已完成");

        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(switch (mode) {
            case "autopilot" -> replyMsg + "；自动模式首次仅预览，请点击“确认并执行”后写入。";
            case "plan" -> replyMsg + "；计划模式仅生成方案，请审核后点击“应用”。";
            case "action" -> "建议模式仅分析，不会写入。" + replyMsg;
            default -> replyMsg;
        });
        Map<String, Object> diff = Map.of(
                "type", "WORK_PROCESS_CATALOG",
                "tool", WORK_PROCESS_CATALOG_TOOL,
                "params", data,
                "description", replyMsg
        );
        response.setDiffs("action".equals(mode) ? List.of() : List.of(diff));
        return response;
    }

    /**
     * D3 fix: turn the operator's prose into canvas_work_process_catalog's typed
     * parameters. Mutates {@code params} in place.
     *
     * @return {@code null} when extraction succeeded, otherwise a ready-to-send
     *         failure response that says what could not be understood. It never
     *         returns a half-filled params map with a success-looking response —
     *         a missing {@code action} is reported, not guessed.
     */
    private AIResponse fillWorkProcessCatalogParams(String message, Map<String, Object> params, String factoryId) {
        String userMessage = Objects.toString(message, "").trim();
        if (userMessage.isEmpty()) {
            return workProcessCatalogFailure(
                    "还没有收到工序描述。请写清楚动作和工序名，例如「新建焯水工序，加工类，标准时薪25」。");
        }

        LlmEgressRedactor.Result masked = llmEgressGuard.texts(EGRESS_RESULT_SITE, factoryId, userMessage);
        if (masked.refused()) {
            return workProcessCatalogFailure(EGRESS_REFUSED_REPLY);
        }
        Map<String, Object> extracted;
        try {
            String llmResponse = dashScopeClient.chatLowTemp(
                    WORK_PROCESS_CATALOG_SYSTEM_PROMPT, masked.text(0));
            extracted = castStringObjectMap(masked.restoreDeep(objectMapper.readValue(
                    extractJsonObject(llmResponse), new TypeReference<Map<String, Object>>() {})));
        } catch (Exception e) {
            log.warn("工序主数据参数抽取失败: message={}, error={}", userMessage, e.getMessage());
            return workProcessCatalogFailure(
                    "没能从这句话里读出要对哪道工序做什么。请写清楚动作和工序名，"
                            + "例如「新建焯水工序，加工类，标准时薪25」。");
        }

        if (extracted.get("error") != null) {
            return workProcessCatalogFailure(Objects.toString(extracted.get("error")));
        }
        extracted.forEach((key, value) -> {
            // "message" 是工具自己的入参名, 不让模型覆写它
            if (value != null && !"message".equals(key)) {
                params.putIfAbsent(key, value);
            }
        });

        if (params.get("action") == null) {
            return workProcessCatalogFailure(
                    "没听出这是要新建工序还是修改已有工序。请在描述里写明「新建」或「修改」，"
                            + "例如「新建焯水工序，加工类」。");
        }
        return null;
    }

    private AIResponse workProcessCatalogFailure(String message) {
        AIResponse response = new AIResponse();
        response.setApplied(false);
        response.setReply(message);
        response.setDiffs(List.of());
        return response;
    }

    /**
     * A failed tool result carries its reason under {@code error}
     * (AbstractTool#buildErrorResult) or under {@code message}
     * (AbstractBusinessTool#buildNeedMoreInfoResult). Reading only {@code error}
     * replaced every clarification request with one generic sentence and threw away
     * the single piece of information the user actually needed — that is how
     * "工序管理工具执行失败" reached prod with no content behind it.
     */
    private String toolFailureMessage(Map<String, Object> toolResponse, String fallback) {
        for (String key : List.of("error", "message")) {
            Object value = toolResponse.get(key);
            if (value != null && !Objects.toString(value).isBlank()) {
                return Objects.toString(value);
            }
        }
        return fallback;
    }

    private ExecutionPrincipal authenticatedPrincipal(
            String pathFactoryId,
            String authorization) {
        String token = TokenUtils.extractToken(authorization);
        UserDTO authenticated = mobileService.getUserFromToken(token);
        if (authenticated == null
                || authenticated.getId() == null
                || authenticated.getFactoryId() == null
                || authenticated.getFactoryType() == null
                || authenticated.getFactoryType().isBlank()
                || authenticated.getRoleCode() == null) {
            throw new BusinessException(401, "登录身份不完整，请重新登录");
        }
        if (!pathFactoryId.equals(authenticated.getFactoryId())) {
            throw new BusinessException(403, "无权访问其他组织的 Canvas 配置");
        }
        return new ExecutionPrincipal(
                authenticated.getFactoryId(),
                authenticated.getFactoryType(),
                authenticated.getId().toString(),
                PrincipalType.USER,
                Set.of(authenticated.getRoleCode().name()),
                Set.of(),
                Set.of());
    }

    private ToolExecutionResult executePreview(
            String toolName,
            String descriptorVersion,
            Map<String, Object> params,
            ExecutionPrincipal principal) {
        String requestId = "canvas-preview-" + UUID.randomUUID();
        ToolExecutionCommand command = new ToolExecutionCommand(
                requestId,
                "canvas-chat-" + UUID.randomUUID(),
                "canvas-trace-" + UUID.randomUUID(),
                toolName,
                descriptorVersion,
                objectMapper.valueToTree(params),
                principal,
                ToolExecutionSource.HTTP_CONTROLLER,
                ToolExecutionMode.PREVIEW,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Instant.now().plusSeconds(30));
        return toolExecutionGateway.execute(command);
    }

    private Map<String, Object> gatewayPayload(ToolExecutionResult result) {
        if (result.status() == ToolExecutionStatus.PREVIEW_UNSUPPORTED) {
            throw new BusinessException(409, "该工具不支持无写入预览，已阻止执行");
        }
        if (result.status() != ToolExecutionStatus.SUCCEEDED
                && result.status() != ToolExecutionStatus.FAILED) {
            throw new BusinessException(403, "工具预览未获授权");
        }
        if (!result.payload().isObject()) {
            throw new BusinessException(502, "工具预览返回格式无效");
        }
        return objectMapper.convertValue(
                result.payload(), new TypeReference<Map<String, Object>>() {});
    }

    @PostMapping("/apply-diffs")
    @Operation(summary = "批量应用 Plan Mode 生成的变更")
    // Round 7a P0 fix: same exposure as /chat — plan-mode diffs execute canvas_* tools.
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ApplyDiffsResult> applyDiffsWithProtocol(
            @PathVariable String factoryId,
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestHeader(value = "X-Canvas-Result-Protocol", required = false) String resultProtocol,
            @RequestBody List<Map<String, Object>> diffs) {
        if (!"3".equals(resultProtocol)) {
            return ApiResponse.error("页面版本已更新，请刷新页面后重新确认；本次未执行任何变更");
        }
        return applyBoundDiffs(factoryId, authorization, diffs);
    }

    /**
     * Secure HTTP confirmation path. The request body is only an index/receipt carrier;
     * the claimed, persisted token is the sole source of tool name and parameters.
     */
    private ApiResponse<ApplyDiffsResult> applyBoundDiffs(
            String factoryId, String authorization, List<Map<String, Object>> diffs) {
        if (previewTokenService == null) {
            return ApiResponse.error("Canvas 确认凭据服务不可用，本次未执行任何变更");
        }
        ExecutionPrincipal principal;
        try {
            principal = authenticatedPrincipal(factoryId, authorization);
        } catch (Exception e) {
            return ApiResponse.error("登录身份无效，本次未执行任何变更");
        }
        Long userId = Long.valueOf(principal.principalId());
        String operationId = "canvas-confirm-" + UUID.randomUUID();
        List<ApplyDiffResult> items = new ArrayList<>();
        int total = diffs == null ? 0 : diffs.size();

        for (int index = 0; index < total; index++) {
            Map<String, Object> diff = diffs.get(index);
            Map<String, Object> proof = diff == null ? null : readStringObjectMap(diff.get("proof"));
            String token = proof == null ? null : Objects.toString(proof.get("token"), null);
            String submittedTool = diff != null && diff.get("tool") instanceof String name ? name : "";
            Map<String, Object> submittedParams = diff == null
                    ? Map.of() : readStringObjectMap(diff.get("params"));
            if (token == null || token.isBlank()) {
                items.add(new ApplyDiffResult(index, "FAILURE", "缺少服务端确认凭据，未执行"));
                continue;
            }
            if (diff == null || !(diff.get("params") instanceof Map<?, ?>)
                    || submittedTool.isBlank()) {
                items.add(new ApplyDiffResult(index, "FAILURE", "确认内容不完整，未执行"));
                continue;
            }
            Map<String, Object> canonicalSubmittedParams = canonicalJsonMap(submittedParams);
            if (canonicalSubmittedParams == null) {
                items.add(new ApplyDiffResult(index, "FAILURE", "确认内容包含无法安全重建的数值，未执行"));
                continue;
            }

            Optional<PreviewTokenService.BoundTokenSnapshot> snapshot;
            try {
                snapshot = previewTokenService.readBoundSnapshot(token, factoryId, userId);
            } catch (Exception readError) {
                log.warn("Canvas confirmation binding read failed: index={}", index);
                items.add(new ApplyDiffResult(index, "UNKNOWN", "确认状态不明，请核对实际记录，勿重复执行"));
                continue;
            }
            if (snapshot.isEmpty()) {
                items.add(new ApplyDiffResult(index, "FAILURE", "确认凭据无效或未绑定当前工厂用户，未执行"));
                continue;
            }
            PreviewTokenService.BoundTokenSnapshot bound = snapshot.get();
            String descriptorVersion = bound.descriptorVersion();
            String proofDigest = proof == null ? null : Objects.toString(proof.get("commandDigest"), null);
            boolean purposeMatches = isCanvasDiffProofToken(bound, bound.toolName());
            boolean toolMatches = bound.toolName().equals(submittedTool);
            String submittedVersion = proof == null ? null
                    : Objects.toString(proof.get("descriptorVersion"), null);
            boolean versionMatches = descriptorVersion != null
                    && descriptorVersion.equals(submittedVersion);
            Map<String, Object> commandParams = canonicalSubmittedParams;
            if (CanvasConfigPlanBindingService.isConfigurationTool(submittedTool)) {
                if (canvasConfigPlanBindingService == null) {
                    items.add(new ApplyDiffResult(index, "FAILURE", "配置绑定服务不可用，本次未执行"));
                    continue;
                }
                Optional<PreviewTokenService.BoundTokenExecution> execution;
                try {
                    execution = previewTokenService.readBoundExecution(token, factoryId, userId);
                    if (execution.isEmpty()) {
                        items.add(new ApplyDiffResult(index, "FAILURE", "确认凭据缺少服务器配置绑定，未执行"));
                        continue;
                    }
                    commandParams = canonicalJsonMap(execution.get().parameters());
                    if (commandParams == null
                            || !canvasConfigPlanBindingService.publicParametersMatch(commandParams, canonicalSubmittedParams)) {
                        items.add(new ApplyDiffResult(index, "FAILURE", "确认内容与服务器配置绑定不一致，请重新生成方案"));
                        continue;
                    }
                } catch (RuntimeException bindingError) {
                    items.add(new ApplyDiffResult(index, "FAILURE", "确认凭据缺少有效服务器配置绑定，未执行"));
                    continue;
                }
            }
            String submittedDigest;
            try {
                submittedDigest = ToolCommandDigest.commandDigest(
                        factoryId, userId, submittedTool, descriptorVersion, ToolExecutionMode.EXECUTE,
                        objectMapper.valueToTree(commandParams));
            } catch (RuntimeException invalidParams) {
                submittedDigest = null;
            }
            boolean paramsMatch = Objects.equals(bound.commandDigest(), submittedDigest)
                    && Objects.equals(bound.commandDigest(), proofDigest);
            if (!purposeMatches || !toolMatches || !versionMatches || !paramsMatch) {
                items.add(new ApplyDiffResult(index, "FAILURE", "确认内容与服务端预览不一致，请重新生成方案"));
                continue;
            }

            ToolExecutionCommand command;
            try {
                command = new ToolExecutionCommand(
                        operationId + "-" + index,
                        operationId,
                        "canvas-trace-" + UUID.randomUUID(),
                        bound.toolName(),
                        descriptorVersion,
                        objectMapper.valueToTree(commandParams),
                        principal,
                        ToolExecutionSource.HTTP_CONTROLLER,
                        ToolExecutionMode.EXECUTE,
                        Optional.of(ToolCommandDigest.persistentSecretFingerprint(token)),
                        Optional.of(new ConfirmationProof(token, bound.commandDigest(),
                                bound.expiresAt().atZone(java.time.ZoneId.systemDefault()).toInstant())),
                        Optional.empty(),
                        Instant.now().plusSeconds(30));
            } catch (RuntimeException invalidCommand) {
                items.add(new ApplyDiffResult(index, "FAILURE", "确认命令无效，未执行"));
                continue;
            }
            ToolExecutionResult gatewayResult;
            try {
                gatewayResult = toolExecutionGateway.execute(command);
            } catch (Exception gatewayError) {
                log.warn("Canvas gateway result unknown: operationId={}, index={}, tool={}",
                        operationId, index, bound.toolName());
                items.add(new ApplyDiffResult(index, "UNKNOWN", "执行结果不明，请核对实际记录后再处理，勿重复执行"));
                continue;
            }
            items.add(mapGatewayApplyResult(index, gatewayResult));
        }
        return applyDiffsReceipt(operationId, total, items);
    }

    private ApplyDiffResult mapGatewayApplyResult(int index, ToolExecutionResult result) {
        if (result == null) {
            return new ApplyDiffResult(index, "UNKNOWN", "执行结果不明，请核对实际记录后再处理，勿重复执行");
        }
        if (result.status() == ToolExecutionStatus.SUCCEEDED) {
            JsonNode payload = result.payload();
            if (payload.isObject() && payload.has("success")
                    && (!payload.get("success").isBoolean() || !payload.get("success").asBoolean())) {
                return new ApplyDiffResult(index, "UNKNOWN", "执行结果不明，请核对实际记录后再处理，勿重复执行");
            }
            if ("PENDING_DDL".equals(payload.path("data").path("status").asText())
                    || "PENDING_DDL".equals(payload.path("data").path("data").path("status").asText())
                    || "PENDING_DDL".equals(payload.path("status").asText())) {
                return new ApplyDiffResult(index, "UNKNOWN", "执行结果不明，请核对实际记录后再处理，勿重复执行");
            }
            if ("DUPLICATE".equals(result.payload().path("data").path("status").asText())) {
                return new ApplyDiffResult(index, "REUSED", "已存在相同记录，复用已有记录，未新增");
            }
            return new ApplyDiffResult(index, "SUCCESS", "变更已完成");
        }
        if (result.status() == ToolExecutionStatus.IDEMPOTENT_REPLAY) {
            String original = result.payload().path("originalStatus").asText("UNKNOWN");
            return switch (original) {
                case "SUCCEEDED" -> new ApplyDiffResult(index, "REUSED", "已复用已有成功记录，未重复执行");
                case "FAILED" -> new ApplyDiffResult(index, "FAILURE", "已复用已有失败记录，未重复执行");
                default -> new ApplyDiffResult(index, "UNKNOWN", "历史执行结果不明，请核对实际记录，勿重复执行");
            };
        }
        if (result.status() == ToolExecutionStatus.OUTCOME_UNKNOWN
                || result.status() == ToolExecutionStatus.TIMEOUT) {
            return new ApplyDiffResult(index, "UNKNOWN", "执行结果不明，请核对实际记录后再处理，勿重复执行");
        }
        String message = result.message() == null || result.message().isBlank()
                ? "变更未完成，未执行重试"
                : result.message();
        return new ApplyDiffResult(index, "FAILURE", message);
    }

    /**
     * Make the issued command and its persisted digest use the same JSON scalar representation
     * that a claim will read back from storage. A non-equivalent round-trip is never made
     * executable; the original diff remains a local, unbound preview.
     */
    private Map<String, Object> canonicalJsonMap(Map<String, Object> params) {
        try {
            JsonNode tree = objectMapper.valueToTree(params);
            Object normalized = canonicalJsonValue(tree);
            if (!(normalized instanceof Map<?, ?> map)) {
                return null;
            }
            Map<String, Object> canonical = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    return null;
                }
                canonical.put(key, entry.getValue());
            }
            String serialized = objectMapper.writeValueAsString(canonical);
            Map<String, Object> persistedReadback = objectMapper.readValue(serialized,
                    new TypeReference<Map<String, Object>>() {});
            return sameJsonValue(canonical, persistedReadback) ? persistedReadback : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normalize every JSON number to the exact finite decimal that a browser
     * JavaScript number can round-trip.  The controller deliberately rejects a
     * non-equivalent double conversion and integers outside Number.MAX_SAFE_INTEGER;
     * those values must never receive a proof or reach the execution gateway.
     */
    private Object canonicalJsonValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return canonicalJsonNumber(node.numberValue());
        }
        if (node.isTextual()) {
            return node.textValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                Object value = canonicalJsonValue(field.getValue());
                if (value == INVALID_CANONICAL_VALUE) {
                    return INVALID_CANONICAL_VALUE;
                }
                object.put(field.getKey(), value);
            }
            return object;
        }
        if (node.isArray()) {
            List<Object> array = new ArrayList<>(node.size());
            for (JsonNode element : node) {
                Object value = canonicalJsonValue(element);
                if (value == INVALID_CANONICAL_VALUE) {
                    return INVALID_CANONICAL_VALUE;
                }
                array.add(value);
            }
            return array;
        }
        return INVALID_CANONICAL_VALUE;
    }

    private Object canonicalJsonNumber(Number number) {
        try {
            BigDecimal source = number instanceof BigDecimal decimal
                    ? decimal
                    : number instanceof BigInteger integer
                            ? new BigDecimal(integer)
                            : new BigDecimal(number.toString());
            double asJavaScriptNumber = source.doubleValue();
            if (!Double.isFinite(asJavaScriptNumber)) {
                return INVALID_CANONICAL_VALUE;
            }
            BigDecimal canonical = BigDecimal.valueOf(asJavaScriptNumber);
            if (source.compareTo(canonical) != 0) {
                return INVALID_CANONICAL_VALUE;
            }
            BigDecimal normalized = canonical.stripTrailingZeros();
            if (normalized.scale() < 0) {
                normalized = normalized.setScale(0);
            }
            if (normalized.scale() == 0 && normalized.abs().compareTo(JS_MAX_SAFE_INTEGER) > 0) {
                return INVALID_CANONICAL_VALUE;
            }
            return normalized.signum() == 0 ? BigDecimal.ZERO : normalized;
        } catch (RuntimeException invalidNumber) {
            return INVALID_CANONICAL_VALUE;
        }
    }

    /** Numeric values may change Java scalar class during JSON persistence (e.g. 1E-7 -> Double). */
    private boolean sameJsonValue(Object expected, Object actual) {
        return sameJsonNode(objectMapper.valueToTree(expected), objectMapper.valueToTree(actual));
    }

    private boolean sameJsonNode(JsonNode left, JsonNode right) {
        if (left == null || right == null || left.isNull() || right.isNull()) {
            return left == right || (left != null && right != null && left.isNull() && right.isNull());
        }
        if (left.isNumber() && right.isNumber()) {
            return left.decimalValue().compareTo(right.decimalValue()) == 0;
        }
        if (left.isObject() && right.isObject()) {
            if (left.size() != right.size()) return false;
            Iterator<String> fields = left.fieldNames();
            while (fields.hasNext()) {
                String field = fields.next();
                if (!right.has(field) || !sameJsonNode(left.get(field), right.get(field))) return false;
            }
            return true;
        }
        if (left.isArray() && right.isArray()) {
            if (left.size() != right.size()) return false;
            for (int i = 0; i < left.size(); i++) {
                if (!sameJsonNode(left.get(i), right.get(i))) return false;
            }
            return true;
        }
        return left.equals(right);
    }

    private boolean isCanvasDiffProofToken(
            PreviewTokenService.BoundTokenSnapshot token, String toolName) {
        return token.executionMode() == ToolExecutionMode.EXECUTE
                && token.intentCode() != null
                && token.intentCode().matches("CANVAS_DIFF_[0-9a-fA-F-]{36}")
                && "CANVAS_DIFF".equals(token.entityType())
                && "UPDATE".equals(token.operation())
                && toolName.equals(token.entityId())
                && isExecutableCanvasTool(toolName);
    }

    private boolean isExecutableCanvasTool(String toolName) {
        return Set.of(
                "canvas_toggle_module", "canvas_toggle_tool", "canvas_toggle_skill",
                "canvas_update_field", "canvas_update_trigger_chain", "canvas_apply_template",
                "canvas_work_process_catalog").contains(toolName);
    }

    private ApiResponse<ApplyDiffsResult> applyDiffsReceipt(
            String operationId, int total, List<ApplyDiffResult> items) {
        int applied = (int) items.stream().filter(item -> "SUCCESS".equals(item.status())).count();
        int reused = (int) items.stream().filter(item -> "REUSED".equals(item.status())).count();
        boolean complete = total > 0 && applied + reused == total;
        String msg = total == 0 ? "没有可执行的变更" : "已完成 " + applied + "/" + total + " 项，复用 " + reused + " 项";
        if (!complete && total > 0) msg += "；其余未完成或结果不明，请逐项核对，勿整批重试";
        ApplyDiffsResult result = new ApplyDiffsResult(operationId, total, applied, reused, List.copyOf(items));
        ApiResponse<ApplyDiffsResult> response = complete ? ApiResponse.success(msg, result) : ApiResponse.error(msg);
        response.setData(result);
        return response;
    }

    // Internal callers use the same proof-bound engine as HTTP; no legacy raw execution path.
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<ApplyDiffsResult> applyDiffs(
            String factoryId, String authorization, List<Map<String, Object>> diffs) {
        return applyBoundDiffs(factoryId, authorization, diffs);
    }
    public record ApplyDiffResult(int index, String status, String message) {}
    public record ApplyDiffsResult(String operationId, int total, int applied, int reused, List<ApplyDiffResult> items) {}

    /**
     * Plan Mode: LLM 生成变更方案 (不执行)，用户逐项审核后批量应用
     */
    private List<Map<String, Object>> generatePlan(String factoryId, String rawMessage) {
        // 用户原话里的手机号 / 证件号 / 卡号换成可还原的代号再发
        LlmEgressRedactor.Result masked = llmEgressGuard.texts(EGRESS_RESULT_SITE, factoryId, rawMessage);
        if (masked.refused()) {
            return List.of(Map.of("type", "INFO", "tool", "", "params", Map.of(), "description", EGRESS_REFUSED_REPLY));
        }
        String message = masked.text(0);
        String prompt = CANVAS_SYSTEM_PROMPT + """

                模式: PLAN (生成方案，不执行)
                工厂ID: %s

                用户需求: "%s"

                请分析需求，生成配置变更方案。格式为 JSON 数组:
                [{"type":"TOOL_TOGGLE|FIELD_CHANGE|TRIGGER_CHAIN_CHANGE|VALIDATION_RULE_CHANGE",
                  "tool":"canvas_xxx",
                  "params":{"key":"value"},
                  "description":"中文说明这个变更的目的"}]

                只返回 JSON 数组。每个变更必须有清晰的中文 description。
                """.formatted(factoryId, message);

        String llmResponse = dashScopeClient.chatFast(prompt, message);
        log.info("Canvas AI Plan LLM response: {}", llmResponse);

        try {
            String json = extractJson(llmResponse);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> plan = (List<Map<String, Object>>) masked.restoreDeep(
                    objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {}));
            return plan;
        } catch (Exception e) {
            llmResponse = masked.restore(llmResponse);
            log.warn("Plan JSON parse failed: {}", e.getMessage());
            // Return a single item explaining the error
            return List.of(Map.of(
                    "type", "INFO",
                    "tool", "",
                    "params", Map.of(),
                    "description", "AI 分析: " + llmResponse
            ));
        }
    }

    /**
     * Action Mode: LLM 分析用户操作的关联影响
     */
    private String analyzeImpact(String factoryId, String rawMessage) {
        LlmEgressRedactor.Result masked = llmEgressGuard.texts(EGRESS_RESULT_SITE, factoryId, rawMessage);
        if (masked.refused()) {
            return EGRESS_REFUSED_REPLY;
        }
        String message = masked.text(0);
        String prompt = CANVAS_SYSTEM_PROMPT + """

                模式: ACTION (实时提示)
                工厂ID: %s

                用户正在操作: "%s"

                请分析这个操作可能的关联影响:
                1. 哪些模块/字段/工具会受影响？
                2. 有什么潜在风险？
                3. 有什么建议？

                用简洁的中文回答，不超过 200 字。
                """.formatted(factoryId, message);

        return masked.restore(dashScopeClient.chatFast(prompt, message));
    }

    /**
     * Extract JSON array from LLM response (handles markdown code blocks)
     */
    private String extractJson(String response) {
        if (response == null) return "[]";
        String trimmed = response.trim();
        // Remove markdown code block if present
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();
        // Find first [ and last ]
        int start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf(']');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "[]";
    }

    /** 抽取 JSON 对象 {...} (语义规格用; extractJson 只抓数组 [...])。 */
    private String extractJsonObject(String response) {
        if (response == null) {
            return "{}";
        }
        String trimmed = response.trim();
        if (trimmed.startsWith("```json")) {
            trimmed = trimmed.substring(7);
        } else if (trimmed.startsWith("```")) {
            trimmed = trimmed.substring(3);
        }
        if (trimmed.endsWith("```")) {
            trimmed = trimmed.substring(0, trimmed.length() - 3);
        }
        trimmed = trimmed.trim();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return trimmed.substring(start, end + 1);
        }
        return "{}";
    }
}
