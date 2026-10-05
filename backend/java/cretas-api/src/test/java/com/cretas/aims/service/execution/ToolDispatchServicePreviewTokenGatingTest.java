package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskCompleteTool;
import com.cretas.aims.ai.tool.impl.workprocess.WorkProcessTaskStartTool;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 预览没通过就不铸确认 token。
 *
 * <p>以前 {@code executeToolPreview} 只看「是不是写工具」: 预览失败 / canDo=false 照样拿到一张
 * 可确认的票。每条「不铸」都配一条「照铸」的对照, 否则「没铸」可能只是 token 服务没接上
 * (硬约束 9: 「没发生」要配一个「必发生」)。
 */
class ToolDispatchServicePreviewTokenGatingTest {

    private static final String FACTORY = "F006";

    private final ObjectMapper mapper = new ObjectMapper();
    private ToolDispatchService dispatch;
    private PreviewTokenService previewTokenService;
    private WriteGuardService writeGuardService;
    private AIIntentConfig intent;

    @BeforeEach
    void setUp() {
        dispatch = new ToolDispatchService(
                mock(ToolRegistry.class),
                mapper,
                mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class),
                mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        previewTokenService = mock(PreviewTokenService.class);
        writeGuardService = mock(WriteGuardService.class);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokenService);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuardService);
        when(previewTokenService.createBoundToken(any())).thenReturn(IntentPreviewToken.builder()
                .token("tok-1")
                .commandDigest("digest-1")
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build());
        intent = AIIntentConfig.builder()
                .intentCode("WORK_PROCESS_TASK_START")
                .intentName("开始工序")
                .intentCategory("PRODUCTION")
                .toolName("work_process_task_start")
                .build();
    }

    // ==================== 不铸 ====================

    @Test
    @DisplayName("canDo=false → 不铸 token, PREVIEW_BLOCKED, 原因原文在 message/formattedText")
    void canDoFalseIssuesNoToken() throws Exception {
        ToolExecutor tool = writeTool("{\"success\":true,\"data\":{\"status\":\"INVALID_STATUS\","
                + "\"canDo\":false,\"message\":\"工序任务 12 已经是「已完成」，不能重新开始。\"}}");

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(r.getConfirmableAction()).isNull();
        assertThat(r.getMessage()).isEqualTo("工序任务 12 已经是「已完成」，不能重新开始。");
        assertThat(r.getFormattedText()).isEqualTo(r.getMessage());
        assertThat(r.getResultData()).as("完整预览体不丢").isInstanceOf(Map.class);
        assertThat(r.getAiMode()).isEqualTo("WRITE");
        verify(tool, times(1)).preview(any(ToolCall.class), anyMap()); // 预览真的跑了
        verify(previewTokenService, never()).createBoundToken(any());
    }

    @Test
    @DisplayName("success=false → 不铸 token, 原因取 error")
    void failedPreviewIssuesNoToken() throws Exception {
        ToolExecutor tool = writeTool("{\"success\":false,\"error\":\"参数验证失败: 任务编号不对\"}");

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(r.getConfirmableAction()).isNull();
        assertThat(r.getMessage()).isEqualTo("参数验证失败: 任务编号不对");
        verify(previewTokenService, never()).createBoundToken(any());
    }

    @Test
    @DisplayName("工具说缺参数 → NEED_MORE_INFO + clarificationQuestions, 不铸 token")
    void needMoreInfoIssuesNoToken() throws Exception {
        ToolExecutor tool = writeTool("{\"success\":false,\"status\":\"NEED_MORE_INFO\",\"needMoreInfo\":true,"
                + "\"clarificationQuestions\":[\"这道工序实际做了多少？请告诉我实际产量。\"],"
                + "\"message\":\"为了完成操作，还需要以下信息：\",\"error\":\"为了完成操作，还需要以下信息：\"}");

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getStatus()).isEqualTo("NEED_MORE_INFO");
        assertThat(r.getClarificationQuestions()).containsExactly("这道工序实际做了多少？请告诉我实际产量。");
        assertThat(r.getConfirmableAction()).isNull();
        verify(previewTokenService, never()).createBoundToken(any());
    }

    // ==================== 照铸 (对照) ====================

    @Test
    @DisplayName("对照: canDo=true → 照常铸 token, PREVIEW + confirmableAction (改前/改后取自预览)")
    void successfulPreviewStillIssuesToken() throws Exception {
        ToolExecutor tool = writeTool("{\"success\":true,\"data\":{\"status\":\"PREVIEW\",\"canDo\":true,"
                + "\"message\":\"确认开始？\",\"currentValues\":{\"状态\":\"待开始\"},"
                + "\"newValues\":{\"状态\":\"进行中\"}}}");

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(r.getConfirmableAction()).isNotNull();
        assertThat(r.getConfirmableAction().getConfirmToken()).isEqualTo("tok-1");
        assertThat(r.getConfirmableAction().getCommandDigest()).isEqualTo("digest-1");
        assertThat(r.getConfirmableAction().getCurrentValues()).containsEntry("状态", "待开始");
        assertThat(r.getConfirmableAction().getNewValues()).containsEntry("状态", "进行中");
        verify(previewTokenService, times(1)).createBoundToken(any());
    }

    @Test
    @DisplayName("对照: 不带 canDo 的老式成功预览 → 照常铸 token (只拦明说了 false 的)")
    void legacyPreviewWithoutCanDoStillIssuesToken() throws Exception {
        ToolExecutor tool = writeTool("{\"success\":true,\"data\":{\"status\":\"PREVIEW\",\"name\":\"x\"}}");

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(r.getConfirmableAction()).isNotNull();
        verify(previewTokenService, times(1)).createBoundToken(any());
    }

    @Test
    @DisplayName("对照: 读工具的 canDo=false 预览不受影响 (本来就不铸, 仍回 PREVIEW 原文)")
    void readToolPreviewUnchanged() throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("some_query");
        String json = "{\"success\":true,\"data\":{\"canDo\":false,\"message\":\"x\"}}";
        when(tool.preview(any(ToolCall.class), anyMap())).thenReturn(json);
        when(writeGuardService.isWriteTool(tool)).thenReturn(false);

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getStatus()).isEqualTo("PREVIEW");
        assertThat(r.getMessage()).isEqualTo(json);
        verify(previewTokenService, never()).createBoundToken(any());
    }

    // ==================== 真实工具接线 (报工) ====================

    @Test
    @DisplayName("真实 WorkProcessTaskStartTool: 已完成的任务 → 无 token + 原因; 待开始 → 有 token")
    void realStartToolWiring() throws Exception {
        WorkProcessTaskService service = mock(WorkProcessTaskService.class);
        WorkProcessTaskStartTool tool = new WorkProcessTaskStartTool(service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(writeGuardService.isWriteTool(tool)).thenReturn(true);

        when(service.getById(FACTORY, 12L)).thenReturn(task(Status.COMPLETED));
        IntentExecuteResponse blocked = dispatch.executeToolPreview(tool, FACTORY, request(), intent, 42L, "operator");
        assertThat(blocked.getStatus()).isEqualTo(ToolDispatchService.PREVIEW_BLOCKED);
        assertThat(blocked.getConfirmableAction()).isNull();
        assertThat(blocked.getMessage()).contains("工序任务 12").contains("已完成");
        verify(previewTokenService, never()).createBoundToken(any());

        when(service.getById(FACTORY, 12L)).thenReturn(task(Status.PENDING));
        IntentExecuteResponse ok = dispatch.executeToolPreview(tool, FACTORY, request(), intent, 42L, "operator");
        assertThat(ok.getStatus()).isEqualTo("PREVIEW");
        assertThat(ok.getConfirmableAction()).isNotNull();
        assertThat(ok.getConfirmableAction().getNewValues()).containsEntry("状态", "进行中");
        verify(previewTokenService, times(1)).createBoundToken(any());

        verify(service, never()).start(anyString(), anyLong(), any());
    }

    // ==================== 确认卡的 actionType / 影响说明 ====================

    @Test
    @DisplayName("真实 StartTool: 确认卡 actionType=UPDATE, 影响说明是「修改数据」措辞而不是「立即执行」")
    void startToolConfirmCardCarriesModifyingActionType() throws Exception {
        WorkProcessTaskService service = mock(WorkProcessTaskService.class);
        WorkProcessTaskStartTool tool = new WorkProcessTaskStartTool(service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(writeGuardService.isWriteTool(tool)).thenReturn(true);
        when(service.getById(FACTORY, 12L)).thenReturn(task(Status.PENDING));

        IntentExecuteResponse r = dispatch.executeToolPreview(tool, FACTORY, request(), intent, 42L, "operator");

        assertThat(r.getConfirmableAction()).as("预览通过才有确认卡").isNotNull();
        assertThat(r.getConfirmableAction().getActionType()).isEqualTo("UPDATE");
        assertThat(r.getConfirmableAction().getImpactSummary())
                .contains("将修改「开始工序」相关的现有数据")
                .doesNotContain("立即执行");
    }

    @Test
    @DisplayName("真实 CompleteTool: 确认卡 actionType=UPDATE, 影响说明是「修改数据」措辞")
    void completeToolConfirmCardCarriesModifyingActionType() throws Exception {
        WorkProcessTaskService service = mock(WorkProcessTaskService.class);
        WorkProcessTaskCompleteTool tool = new WorkProcessTaskCompleteTool(service);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(writeGuardService.isWriteTool(tool)).thenReturn(true);
        when(service.getById(FACTORY, 12L)).thenReturn(task(Status.IN_PROGRESS));
        AIIntentConfig completeIntent = AIIntentConfig.builder()
                .intentCode("WORK_PROCESS_TASK_COMPLETE")
                .intentName("完成工序")
                .intentCategory("PRODUCTION")
                .toolName("work_process_task_complete")
                .build();
        IntentExecuteRequest req = IntentExecuteRequest.builder()
                .intentCode(completeIntent.getIntentCode())
                .userInput("完成工序任务 12, 产量 95.5")
                .context(new java.util.HashMap<>(Map.of("taskId", 12, "actualQuantity", 95.5)))
                .previewOnly(true)
                .build();

        IntentExecuteResponse r = dispatch.executeToolPreview(tool, FACTORY, req, completeIntent, 42L, "operator");

        assertThat(r.getConfirmableAction()).as("预览通过才有确认卡").isNotNull();
        assertThat(r.getConfirmableAction().getActionType()).isEqualTo("UPDATE");
        assertThat(r.getConfirmableAction().getImpactSummary())
                .contains("将修改「完成工序」相关的现有数据")
                .doesNotContain("立即执行");
    }

    @Test
    @DisplayName("对照: 声明 READ 的工具仍然是「立即执行」措辞 —— 修的是这两个工具的声明, 没动措辞表")
    void genuinelyReadActionTypeKeepsExecuteWording() throws Exception {
        ToolExecutor tool = writeTool("{\"success\":true,\"data\":{\"status\":\"PREVIEW\",\"canDo\":true,"
                + "\"message\":\"确认？\",\"currentValues\":{\"状态\":\"待开始\"},\"newValues\":{\"状态\":\"进行中\"}}}");

        IntentExecuteResponse r = preview(tool);

        assertThat(r.getConfirmableAction()).isNotNull();
        assertThat(r.getConfirmableAction().getActionType()).isEqualTo("READ");
        assertThat(r.getConfirmableAction().getImpactSummary()).contains("确认后将立即执行「开始工序」");
    }

    // ==================== helpers ====================

    private ToolExecutor writeTool(String previewJson) throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("work_process_task_start");
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
        when(tool.getRiskLevel()).thenReturn(ToolExecutor.RiskLevel.LOW);
        when(tool.preview(any(ToolCall.class), anyMap())).thenReturn(previewJson);
        when(writeGuardService.isWriteTool(tool)).thenReturn(true);
        return tool;
    }

    private IntentExecuteResponse preview(ToolExecutor tool) {
        return dispatch.executeToolPreview(tool, FACTORY, request(), intent, 42L, "operator");
    }

    private IntentExecuteRequest request() {
        return IntentExecuteRequest.builder()
                .intentCode(intent.getIntentCode())
                .userInput("开始工序任务 12")
                .context(new java.util.HashMap<>(Map.of("taskId", 12)))
                .previewOnly(true)
                .build();
    }

    private static WorkProcessTaskDTO task(Status status) {
        return WorkProcessTaskDTO.builder()
                .id(12L)
                .factoryId(FACTORY)
                .processName("切片")
                .status(status)
                .plannedQuantity(new BigDecimal("100"))
                .plannedUnit("kg")
                .build();
    }
}
