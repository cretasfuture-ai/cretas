package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 一线角色写操作开放名单接在执行入口与预览入口上 (owner 2026-10-06)。
 *
 * <p>走真的 {@link ToolDispatchService} + 真的 {@link FrontlineAiWriteGrantService}; 只桩仓储与工具。
 * 每条「拦住」都看工具<b>一次都没被调</b>、确认票<b>没发</b>; 「放行」的对照要看到工具真的被调了
 * (「没发生」配一个「必发生」)。
 */
class ToolDispatchFrontlineWriteGateTest {

    private static final String FACTORY = "F006";

    private ToolDispatchService dispatch;
    private PreviewTokenService previewTokenService;
    private WriteGuardService writeGuardService;

    private static AIIntentConfig intent(String code, String name, String toolName) {
        return AIIntentConfig.builder().intentCode(code).intentName(name).intentCategory("TEST")
                .sensitivityLevel("MEDIUM").toolName(toolName).build();
    }

    private static final AIIntentConfig BATCH_CREATE = intent("PRODUCTION_BATCH_CREATE", "创建生产批次", "production_batch_create");
    private static final AIIntentConfig TASK_COMPLETE = intent("WORK_PROCESS_TASK_COMPLETE", "完成工序", "work_process_task_complete");

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
        // 工具在本厂是启用的 —— 否则请求停在更前面的「本厂已关闭此工具」, 测不到一线名单
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        dispatch = new ToolDispatchService(
                registry, new ObjectMapper(), mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        previewTokenService = mock(PreviewTokenService.class);
        when(previewTokenService.createBoundToken(any())).thenReturn(IntentPreviewToken.builder()
                .token("tok-1").commandDigest("digest-1").expiresAt(LocalDateTime.now().plusMinutes(5)).build());
        writeGuardService = mock(WriteGuardService.class);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", previewTokenService);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuardService);

        AiFrontlineIntentGrantRepository repo = mock(AiFrontlineIntentGrantRepository.class);
        when(repo.findByFactoryIdAndRevokedAtIsNull(anyString())).thenReturn(List.of());
        FrontlineAiWriteGrantService grants = new FrontlineAiWriteGrantService(
                repo, writeGuardService, mock(IntentConfigManagementService.class), mock(ToolRegistry.class));
        ReflectionTestUtils.setField(dispatch, "frontlineWriteGrants", grants);
    }

    private ToolExecutor writeTool(String name) throws Exception {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn(name);
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.UPDATE);
        when(tool.getRiskLevel()).thenReturn(ToolExecutor.RiskLevel.LOW);
        when(tool.supportsPreview()).thenReturn(true);
        when(tool.preview(any(ToolCall.class), anyMap())).thenReturn("{\"success\":true,\"data\":{\"canDo\":true}}");
        when(writeGuardService.isWriteTool(tool)).thenReturn(true);
        return tool;
    }

    private static IntentExecuteRequest request(AIIntentConfig intent, boolean previewOnly) {
        return IntentExecuteRequest.builder().intentCode(intent.getIntentCode()).userInput("帮我办一下")
                .context(new HashMap<>(Map.of("taskId", 12))).previewOnly(previewOnly).build();
    }

    @Test
    @DisplayName("预览入口: 操作员 + 未开放的写操作 ⇒ PERMISSION_DENIED, 预览不跑, 不发确认票")
    void previewDeniedForUnopenedWrite() throws Exception {
        ToolExecutor tool = writeTool("production_batch_create");
        IntentExecuteResponse r = dispatch.executeToolPreview(tool, FACTORY, request(BATCH_CREATE, true), BATCH_CREATE, 42L, "operator");

        assertThat(r.getStatus()).isEqualTo("PERMISSION_DENIED");
        assertThat(r.getMessage()).contains("创建生产批次").contains("操作员").contains("管理端");
        assertThat(r.getConfirmableAction()).isNull();
        verify(tool, never()).preview(any(ToolCall.class), anyMap());
        verify(previewTokenService, never()).createBoundToken(any());
    }

    @Test
    @DisplayName("对照: 操作员 + 默认开放的完成工序 ⇒ 预览真的跑了、发了确认票")
    void previewAllowedForDefaultOpen() throws Exception {
        ToolExecutor tool = writeTool("work_process_task_complete");
        IntentExecuteResponse r = dispatch.executeToolPreview(tool, FACTORY, request(TASK_COMPLETE, true), TASK_COMPLETE, 42L, "operator");

        assertThat(r.getStatus()).isNotEqualTo("PERMISSION_DENIED");
        verify(tool, times(1)).preview(any(ToolCall.class), anyMap());
        verify(previewTokenService, times(1)).createBoundToken(any());
    }

    @Test
    @DisplayName("对照: 车间主任 + 同一个写操作 ⇒ 不受一线名单影响, 预览照跑")
    void previewAllowedForNonFrontline() throws Exception {
        ToolExecutor tool = writeTool("production_batch_create");
        dispatch.executeToolPreview(tool, FACTORY, request(BATCH_CREATE, true), BATCH_CREATE, 42L, "workshop_supervisor");
        verify(tool, times(1)).preview(any(ToolCall.class), anyMap());
    }

    @Test
    @DisplayName("执行入口 (含确认之后那一次): 仓库员 + 未开放的写操作 ⇒ PERMISSION_DENIED, 工具不执行")
    void executeDeniedForUnopenedWrite() throws Exception {
        ToolExecutor tool = writeTool("production_batch_create");
        IntentExecuteResponse r = dispatch.executeWithTool(tool, FACTORY, request(BATCH_CREATE, false), BATCH_CREATE,
                42L, "warehouse_worker", null);

        assertThat(r.getStatus()).isEqualTo("PERMISSION_DENIED");
        assertThat(r.getMessage()).contains("仓库员");
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
        verify(tool, never()).preview(any(ToolCall.class), anyMap());
    }

    @Test
    @DisplayName("对照: 执行入口对非一线角色不给一线名单的拒绝 (会继续走到后面的写确认)")
    void executeNotDeniedByFrontlineForNonFrontline() throws Exception {
        ToolExecutor tool = writeTool("production_batch_create");
        IntentExecuteResponse r = dispatch.executeWithTool(tool, FACTORY, request(BATCH_CREATE, false), BATCH_CREATE,
                42L, "workshop_supervisor", null);
        assertThat(r.getStatus()).as("没确认的写操作应停在写确认, 不是一线拒绝").isEqualTo("WRITE_CONFIRM_REQUIRED");
    }

    @Test
    @DisplayName("接线: 生产上由 Spring 强制注入 (required=true), 不是可有可无")
    void wiringIsRequired() throws Exception {
        Field f = ToolDispatchService.class.getDeclaredField("frontlineWriteGrants");
        Autowired a = f.getAnnotation(Autowired.class);
        assertThat(a).isNotNull();
        assertThat(a.required()).isTrue();
    }
}
