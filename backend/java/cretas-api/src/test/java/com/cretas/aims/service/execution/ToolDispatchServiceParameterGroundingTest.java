package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskCompleteTool;
import com.cretas.aims.dto.WorkProcessTaskDTO;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.workprocess.WorkProcessTaskService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LLM 抽参必须「落地」(2026-10-06 prod F006 工序任务 1827: 用户只说「1827」, 确认卡出现
 * 实际产量 0 kg + 备注「用户仅提供了数字1827，未明确实际产量，需补充确认」, 确认后进了库)。
 *
 * <p>全部走真实入口 ({@code executeToolPreview} / {@code executeWithTool}) + 真实
 * {@link WorkProcessTaskCompleteTool}; 只桩 LLM (DashScopeClient) 与工序任务服务。
 * 每条「被丢掉」都配一条「照收」的对照, 并断言 LLM 抽参真的被调用过 (硬约束 9)。
 */
class ToolDispatchServiceParameterGroundingTest {

    private static final String FACTORY = "F006";
    private static final String INVENTED_NOTE = "用户仅提供了数字1827，未明确实际产量，需补充确认";

    private final ObjectMapper mapper = new ObjectMapper();
    private final WriteGuardService writeGuard = new WriteGuardService();
    private DashScopeClient llm;
    private PreviewTokenService previewTokenService;
    private WorkProcessTaskService service;
    private ParameterExtractionLearningService learning;
    private CorrectionAgentService correctionAgent;
    private ToolResultValidatorService validator;
    private ToolDispatchService dispatch;
    private WorkProcessTaskCompleteTool completeTool;
    private final AIIntentConfig completeIntent = AIIntentConfig.builder()
            .intentCode("WORK_PROCESS_TASK_COMPLETE").intentName("完成工序").intentCategory("PRODUCTION")
            .toolName("work_process_task_complete").build();

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        previewTokenService = mock(PreviewTokenService.class);
        service = mock(WorkProcessTaskService.class);
        learning = mock(ParameterExtractionLearningService.class);
        correctionAgent = mock(CorrectionAgentService.class);
        validator = mock(ToolResultValidatorService.class);
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        dispatch = new ToolDispatchService(registry, mapper, llm, redundancy,
                mock(SelfCorrectionService.class), correctionAgent, mock(ExternalVerifierService.class),
                validator, learning);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokenService);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        when(previewTokenService.createBoundToken(any())).thenReturn(IntentPreviewToken.builder()
                .token("tok-1").commandDigest("digest-1").expiresAt(LocalDateTime.now().plusMinutes(5)).build());

        completeTool = new WorkProcessTaskCompleteTool(service);
        ReflectionTestUtils.setField(completeTool, "objectMapper", mapper);
        when(service.getById(FACTORY, 1827L)).thenReturn(task());
        when(service.complete(eq(FACTORY), eq(1827L), any(), any())).thenReturn(task());
    }

    // ==================== 预览 (explicit-intent 按钮路径, 证据 8 的那一条) ====================

    @Test
    @DisplayName("证据 8 复现: 只说「1827」, LLM 编了产量 0 和备注 → 产量被丢, NEED_MORE_INFO 追问产量, 不出确认卡")
    void inventedQuantityIsDroppedAndAskedFor() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":0,\"notes\":\"" + INVENTED_NOTE + "\"}");

        IntentExecuteResponse r = dispatch.executeToolPreview(completeTool, FACTORY, preview("1827"),
                completeIntent, 42L, "operator");

        verify(llm, times(1)).chatWithTools(anyString(), eq("1827"), anyList());   // LLM 抽参真的跑了
        assertThat(r.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(r.getClarificationQuestions()).containsExactly("这道工序实际做了多少？请告诉我实际产量。");
        assertThat(r.getClarificationQuestions().toString()).doesNotContain("Long").doesNotContain("ID");
        assertThat(r.getConfirmableAction()).isNull();
        verify(previewTokenService, never()).createBoundToken(any());
        verify(service, never()).complete(anyString(), anyLong(), any(), any());
    }

    @Test
    @DisplayName("一处出现只给一个参数作证: 「1827」当了任务编号, LLM 再拿它当产量 → 丢")
    void oneMentionGroundsOnlyOneParameter() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":1827}");

        IntentExecuteResponse r = dispatch.executeToolPreview(completeTool, FACTORY, preview("1827"),
                completeIntent, 42L, "operator");

        assertThat(r.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(previewTokenService, never()).createBoundToken(any());
    }

    @Test
    @DisplayName("对照: 用户说了产量 50 → 照收, 出确认卡, 绑定参数里是 50")
    void quantityInInputIsAccepted() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":50}");

        IntentExecuteResponse r = dispatch.executeToolPreview(completeTool, FACTORY, preview("1827 产量50"),
                completeIntent, 42L, "operator");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(r.getConfirmableAction()).isNotNull();
        assertThat(boundParams()).containsEntry("actualQuantity", 50).containsEntry("taskId", 1827);
    }

    @Test
    @DisplayName("对照: 「十公斤」= 10 → 照收 (LLM 给字符串「十公斤」也规整成数字 10)")
    void chineseNumeralIsAccepted() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":\"十公斤\"}");

        IntentExecuteResponse r = dispatch.executeToolPreview(completeTool, FACTORY,
                preview("1827号做完了，做了十公斤"), completeIntent, 42L, "operator");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(boundParams()).containsEntry("actualQuantity", 10L);
    }

    @Test
    @DisplayName("对照 (#929): 用户明说产量 0 / 零 → 照收, 0 仍是合法产量")
    void explicitZeroFromUserIsAccepted() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":0}");
        IntentExecuteResponse arabic = dispatch.executeToolPreview(completeTool, FACTORY,
                preview("1827 产量0"), completeIntent, 42L, "operator");
        assertThat(arabic.getStatus()).isEqualTo("PREVIEW");
        assertThat(arabic.getConfirmableAction().getNewValues().get("实际产量").toString()).startsWith("0");

        IntentExecuteResponse chinese = dispatch.executeToolPreview(completeTool, FACTORY,
                preview("1827 产量零"), completeIntent, 42L, "operator");
        assertThat(chinese.getStatus()).isEqualTo("PREVIEW");
        verify(previewTokenService, times(2)).createBoundToken(any());
    }

    @Test
    @DisplayName("LLM 自己写的备注被丢 (产量是用户说的, 照常出卡, 卡上和绑定参数里都没有那句话)")
    void inventedNoteIsDropped() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":50,\"notes\":\"" + INVENTED_NOTE + "\"}");

        IntentExecuteResponse r = dispatch.executeToolPreview(completeTool, FACTORY, preview("1827 产量50"),
                completeIntent, 42L, "operator");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(boundParams()).doesNotContainKey("notes");
        assertThat(r.getConfirmableAction().getNewValues()).doesNotContainKey("备注");
        assertThat(String.valueOf(r.getConfirmableAction().getPreviewData()) + r.getMessage())
                .contains("50").doesNotContain("未明确实际产量");
    }

    @Test
    @DisplayName("对照: 用户原话里的备注照收 (LLM 去掉了标点也算原话)")
    void quotedNoteIsKept() throws Exception {
        llmReturns("{\"taskId\":1827,\"actualQuantity\":50,\"notes\":\"刀钝了 换了一把\"}");

        IntentExecuteResponse r = dispatch.executeToolPreview(completeTool, FACTORY,
                preview("1827 产量50，备注：刀钝了，换了一把"), completeIntent, 42L, "operator");

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(boundParams()).containsEntry("notes", "刀钝了 换了一把");
        assertThat(r.getConfirmableAction().getNewValues()).containsEntry("备注", "刀钝了 换了一把");
    }

    @Test
    @DisplayName("上下文里的数也算出处 (按钮 / lastAction 带来的), 本工具已绑定的参数键不给别的参数作证")
    void contextNumbersGroundButOwnParamKeysDoNot() throws Exception {
        // lastAction 里有 50 (上一步的数量) → 可以作证
        llmReturns("{\"actualQuantity\":50}");
        Map<String, Object> ctx = new HashMap<>(Map.of("taskId", 1827,
                "lastAction", Map.of("params", Map.of("quantity", 50))));
        IntentExecuteResponse ok = dispatch.executeToolPreview(completeTool, FACTORY,
                preview("做完了", ctx), completeIntent, 42L, "operator");
        assertThat(ok.getStatus()).isEqualTo("PREVIEW");

        // taskId=1827 只给它自己作证: LLM 拿 1827 当产量 → 丢
        llmReturns("{\"actualQuantity\":1827}");
        IntentExecuteResponse dropped = dispatch.executeToolPreview(completeTool, FACTORY,
                preview("做完了", new HashMap<>(Map.of("taskId", 1827))), completeIntent, 42L, "operator");
        assertThat(dropped.getStatus()).isEqualTo("NEED_MORE_INFO");
    }

    // ==================== 执行路径 (executeWithTool §2.8, SSE / 确认后的那一条) ====================

    @Test
    @DisplayName("executeWithTool: LLM 编的产量被丢 → 工具 NEED_MORE_INFO, 不写库, 也不拿编的值去学规则")
    void executePathDropsInventedQuantity() throws Exception {
        llmReturns("{\"actualQuantity\":0,\"notes\":\"" + INVENTED_NOTE + "\"}");

        IntentExecuteResponse r = dispatch.executeWithTool(completeTool, FACTORY,
                confirmed("1827", Map.of("taskId", 1827)), completeIntent, 42L, "operator", null);

        verify(llm, times(1)).chatWithTools(anyString(), eq("1827"), anyList());
        assertThat(r.getStatus()).isEqualTo("NEED_MORE_INFO");
        verify(service, never()).complete(anyString(), anyLong(), any(), any());
        verify(learning, never()).learnFromLLMExtraction(anyString(), anyString(), anyString(), anyMap());
    }

    @Test
    @DisplayName("对照: executeWithTool 用户说了产量 → 写库带 50, 备注为空")
    void executePathAcceptsGroundedQuantity() throws Exception {
        llmReturns("{\"actualQuantity\":50,\"notes\":\"" + INVENTED_NOTE + "\"}");

        IntentExecuteResponse r = dispatch.executeWithTool(completeTool, FACTORY,
                confirmed("产量50", Map.of("taskId", 1827)), completeIntent, 42L, "operator", null);

        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<WorkProcessTaskDTO.CompleteRequest> req =
                ArgumentCaptor.forClass(WorkProcessTaskDTO.CompleteRequest.class);
        verify(service).complete(eq(FACTORY), eq(1827L), any(), req.capture());
        assertThat(req.getValue().getActualQuantity()).isEqualByComparingTo("50");
        assertThat(req.getValue().getNotes()).isNull();
    }

    // ==================== CRITIC 纠错重试 (第三、四条 LLM 产参路径) ====================

    @Test
    @DisplayName("纠错给回的参数: 编的数退回原值, 编的备注去掉; 用户说过的数照收 (对照)")
    void correctionRetryIsGroundedToo() throws Exception {
        List<Map<String, Object>> seen = new ArrayList<>();
        int[] calls = {0};
        ToolExecutor flaky = new ToolExecutor() {
            @Override public String getToolName() { return "material_batch_use"; }
            @Override public String getDescription() { return "test"; }
            @Override public Map<String, Object> getParametersSchema() {
                return Map.of("type", "object",
                        "properties", Map.of("quantity", Map.of("type", "number"),
                                "batchId", Map.of("type", "string"),
                                "remark", Map.of("type", "string")),
                        "required", List.of("batchId", "quantity"));
            }
            @Override public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
                seen.add(mapper.readValue(toolCall.getFunction().getArguments(),
                        new TypeReference<Map<String, Object>>() { }));
                if (calls[0]++ == 0) {
                    throw new IllegalStateException("boom");
                }
                return "{\"success\":true,\"data\":{\"ok\":1}}";
            }
        };
        Map<String, Object> corrected = new HashMap<>(Map.of("batchId", "MB-7", "quantity", 999,
                "remark", "纠错代理认为数量可能有误", "page", 1));
        when(correctionAgent.analyzeAndCorrect(anyString(), anyString(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(new CorrectionAgentService.CorrectionResult(true, "x", corrected, "RETRY", null, 0.9));
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY").intentName("q")
                .intentCategory("MATERIAL").toolName("material_batch_use").build();

        dispatch.executeWithTool(flaky, FACTORY, IntentExecuteRequest.builder().userInput("领 30 公斤")
                .context(writeGuard.withServerConfirmation(new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30))))
                .build(), intent, 42L, "operator", null);

        assertThat(seen).as("工具确实被重试了一次").hasSize(2);
        Map<String, Object> retry = seen.get(1);
        assertThat(retry).containsEntry("quantity", 30)            // 999 没出处 → 退回原值 30
                .doesNotContainKey("remark")                        // 纠错代理自己的话 → 去掉
                .containsEntry("batchId", "MB-7");
        assertThat(retry).doesNotContainKey("page");               // 1 没出处 → 原来没有就去掉

        // 对照: 纠错改成用户说过的数 → 照收
        seen.clear();
        corrected.clear();
        corrected.putAll(Map.of("batchId", "MB-7", "quantity", 30));
        Map<String, Object> ctx = new HashMap<>(Map.of("batchId", "MB-7", "quantity", 3));
        calls[0] = 0;
        dispatch.executeWithTool(flaky, FACTORY, IntentExecuteRequest.builder().userInput("领 30 公斤")
                .context(writeGuard.withServerConfirmation(ctx)).build(), intent, 42L, "operator", null);
        assertThat(seen).hasSize(2);
        assertThat(seen.get(1)).containsEntry("quantity", 30);
    }

    // ==================== 纠错重试后主体重新绑定 ====================

    /** 纠错代理把主体换成别家: factoryId=F999 / userId=999 / role=platform_admin (含各种别名)。 */
    private static Map<String, Object> hijackedCorrection() {
        Map<String, Object> m = new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30));
        m.put("factoryId", "F999");
        m.put("factory_id", "F999");
        m.put("tenantId", "F999");
        m.put("userId", 999);
        m.put("user_id", 999);
        m.put("actorUserId", 999);
        m.put("userRole", "platform_admin");
        m.put("role", "platform_admin");
        return m;
    }

    /** 第一次按 firstResult 返回 (null = 抛异常), 之后成功; 记下每次的参数与执行上下文。 */
    private ToolExecutor recordingTool(List<Map<String, Object>> args, List<Map<String, Object>> contexts,
                                       String firstResult) {
        int[] calls = {0};
        return new ToolExecutor() {
            @Override public String getToolName() { return "material_batch_query"; }
            @Override public AccessMode getAccessMode() { return AccessMode.READ; }
            @Override public String getDescription() { return "test"; }
            @Override public Map<String, Object> getParametersSchema() {
                return Map.of("type", "object",
                        "properties", Map.of("quantity", Map.of("type", "number"),
                                "batchId", Map.of("type", "string")),
                        "required", List.of("batchId", "quantity"));
            }
            @Override public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
                args.add(mapper.readValue(toolCall.getFunction().getArguments(),
                        new TypeReference<Map<String, Object>>() { }));
                contexts.add(new HashMap<>(context));
                if (calls[0]++ == 0) {
                    if (firstResult == null) {
                        throw new IllegalStateException("boom");
                    }
                    return firstResult;
                }
                return "{\"success\":true,\"data\":{\"ok\":1}}";
            }
        };
    }

    private void assertCallerIdentity(Map<String, Object> m) {
        assertThat(m).containsEntry("factoryId", FACTORY).containsEntry("factory_id", FACTORY)
                .containsEntry("tenantId", FACTORY).containsEntry("userRole", "operator")
                .containsEntry("role", "operator");
        assertThat(((Number) m.get("userId")).longValue()).isEqualTo(42L);
        assertThat(((Number) m.get("user_id")).longValue()).isEqualTo(42L);
        assertThat(((Number) m.get("actorUserId")).longValue()).isEqualTo(42L);
        assertThat(m.toString()).doesNotContain("F999").doesNotContain("999,").doesNotContain("platform_admin");
    }

    private final AIIntentConfig queryIntent = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_QUERY")
            .intentName("q").intentCategory("MATERIAL").toolName("material_batch_query").build();

    @Test
    @DisplayName("异常重试: 纠错代理塞进来的 F999 / 999 / platform_admin 到不了工具, 重试仍以调用者身份执行")
    void exceptionRetryRebindsCallerIdentity() throws Exception {
        List<Map<String, Object>> args = new ArrayList<>();
        List<Map<String, Object>> contexts = new ArrayList<>();
        ToolExecutor tool = recordingTool(args, contexts, null);
        when(correctionAgent.analyzeAndCorrect(anyString(), anyString(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(new CorrectionAgentService.CorrectionResult(
                        true, "x", hijackedCorrection(), "RETRY", null, 0.9));

        IntentExecuteResponse r = dispatch.executeWithTool(tool, FACTORY, IntentExecuteRequest.builder()
                .userInput("查 MB-7 的 30 公斤").context(new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30)))
                .build(), queryIntent, 42L, "operator", null);

        assertThat(args).as("工具确实被重试了 (纠错路径真的走到)").hasSize(2);
        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        assertCallerIdentity(args.get(1));
        assertCallerIdentity(contexts.get(1));
    }

    @Test
    @DisplayName("结果校验重试: 同样重新绑定调用者身份")
    void validationRetryRebindsCallerIdentity() throws Exception {
        List<Map<String, Object>> args = new ArrayList<>();
        List<Map<String, Object>> contexts = new ArrayList<>();
        // Conditional-defense branch test: strict Boolean success:true normally satisfies the
        // fast heuristic, so production currently skips D7. Stub only this first fast decision
        // to exercise real validation/correction/rebinding; this is NOT public D7 reachability.
        String firstResult = "{\"success\":true,\"rows\":null,\"hint\":1}";
        ToolExecutor tool = recordingTool(args, contexts, firstResult);
        ToolDispatchService validationDispatch = org.mockito.Mockito.spy(dispatch);
        org.mockito.Mockito.doReturn(false).when(validationDispatch).isResultClearlyValid(firstResult);
        when(validator.validate(anyString(), anyString(), anyMap(), anyString()))
                .thenReturn(new ToolResultValidatorService.ValidationResult(false,
                        ToolResultValidatorService.ValidationIssue.NO_MATCH, "不匹配", 0.1, Map.of(), Map.of(), "x"));
        when(correctionAgent.analyzeAndCorrect(anyString(), anyString(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(new CorrectionAgentService.CorrectionResult(
                        true, "x", hijackedCorrection(), "RETRY", null, 0.9));

        validationDispatch.executeWithTool(tool, FACTORY, IntentExecuteRequest.builder()
                .userInput("查 MB-7 的 30 公斤").context(new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30)))
                .build(), queryIntent, 42L, "operator", null);

        assertThat(args).as("校验触发的重试真的走到").hasSize(2);
        assertCallerIdentity(args.get(1));
        verify(validator).validate(anyString(), anyString(), anyMap(), eq(firstResult));
        verify(correctionAgent).analyzeAndCorrect(anyString(), anyString(), anyMap(), anyString(), any(), eq(1));
    }

    @Test
    @DisplayName("对照: 纠错不碰身份时重试照常执行, 带调用者身份, 纠错改的业务参数照收")
    void ordinaryRetryStillRunsWithCallerIdentity() throws Exception {
        List<Map<String, Object>> args = new ArrayList<>();
        List<Map<String, Object>> contexts = new ArrayList<>();
        ToolExecutor tool = recordingTool(args, contexts, null);
        when(correctionAgent.analyzeAndCorrect(anyString(), anyString(), anyMap(), anyString(), any(), anyInt()))
                .thenReturn(new CorrectionAgentService.CorrectionResult(true, "x",
                        new HashMap<>(Map.of("batchId", "MB-7", "quantity", 30, "unit", "kg")), "RETRY", null, 0.9));

        IntentExecuteResponse r = dispatch.executeWithTool(tool, FACTORY, IntentExecuteRequest.builder()
                .userInput("查 MB-7 的 30 公斤").context(new HashMap<>(Map.of("batchId", "MB-7", "quantity", 3)))
                .build(), queryIntent, 42L, "operator", null);

        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        assertThat(args).hasSize(2);
        assertThat(args.get(1)).containsEntry("quantity", 30).containsEntry("unit", "kg");
        assertCallerIdentity(args.get(1));
    }

    // ==================== helpers ====================

    private void llmReturns(String argumentsJson) {
        ChatCompletionResponse resp = mock(ChatCompletionResponse.class);
        when(llm.chatWithTools(anyString(), anyString(), anyList())).thenReturn(resp);
        when(llm.hasToolCalls(resp)).thenReturn(true);
        when(llm.getFirstToolCall(resp)).thenReturn(ToolCall.of("c1", "extract_parameters", argumentsJson));
    }

    private Map<String, Object> boundParams() {
        ArgumentCaptor<PreviewTokenService.BoundTokenRequest> captor =
                ArgumentCaptor.forClass(PreviewTokenService.BoundTokenRequest.class);
        verify(previewTokenService, times(1)).createBoundToken(captor.capture());
        return captor.getValue().parameters();
    }

    private IntentExecuteRequest preview(String userInput) {
        return preview(userInput, new HashMap<>());
    }

    private IntentExecuteRequest preview(String userInput, Map<String, Object> ctx) {
        return IntentExecuteRequest.builder().intentCode(completeIntent.getIntentCode())
                .userInput(userInput).context(ctx).previewOnly(true).build();
    }

    private IntentExecuteRequest confirmed(String userInput, Map<String, Object> ctx) {
        return IntentExecuteRequest.builder().intentCode(completeIntent.getIntentCode()).userInput(userInput)
                .context(writeGuard.withServerConfirmation(new HashMap<>(ctx))).build();
    }

    private static WorkProcessTaskDTO task() {
        return WorkProcessTaskDTO.builder().id(1827L).factoryId(FACTORY).processName("切片")
                .status(Status.IN_PROGRESS).plannedQuantity(new BigDecimal("100")).plannedUnit("kg").build();
    }
}
