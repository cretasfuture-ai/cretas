package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 写确认卡路径的 lastAction (2026-10-06, prod e2e 第 6 条)。
 *
 * <p>「开始1827」走的是 流式 W0 拦下 → /preview → /confirm。App 只在流式 SUCCESS 时记 lastAction,
 * 于是确认卡做成的那一步之后「做完了，产量0」没有上下文可读。这里先钉住<b>服务端那一半</b>:
 * /confirm 的真实入口 ({@link IntentExecutionOrchestrator#confirm}) 用<b>真的</b> ToolDispatchService
 * 跑工具, 成功响应带 {@code metadata.lastAction} (taskId 来自令牌绑定的参数)。
 * 对照: 同一工具同一参数只做预览 → 没有 lastAction (预览不是「做了」)。
 */
class IntentExecutionOrchestratorConfirmLastActionTest {

    private static final String FACTORY = "F006";
    private static final long USER = 1686L;
    private static final String ROLE = "operator";
    private static final String TOKEN = "opaque-token";
    private static final String CLAIM = "claim-1";
    private static final String DIGEST = "b".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private AIIntentService aiIntentService;
    private ToolRegistry toolRegistry;
    private PreviewTokenService tokenService;
    private ToolDispatchService dispatch;
    private IntentExecutionOrchestrator orchestrator;
    private ScriptedStartTool tool;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolRegistry = mock(ToolRegistry.class);
        tokenService = mock(PreviewTokenService.class);
        tool = new ScriptedStartTool();
        when(toolRegistry.getExecutor("work_process_task_start")).thenReturn(Optional.of(tool));
        when(toolRegistry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        when(aiIntentService.getIntentByCode(FACTORY, "WORK_PROCESS_TASK_START")).thenReturn(Optional.of(intent()));
        dispatch = realDispatch(toolRegistry);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                objectMapper,
                mock(DashScopeClient.class),
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                toolRegistry,
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                dispatch,
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", tokenService);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST)).thenReturn(claim());
        when(tokenService.resolveClaim(eq(TOKEN), eq(CLAIM), anyBoolean(), any())).thenReturn(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void confirmedWriteSuccessCarriesLastActionWithTheBoundTaskId() {
        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(tool.executeCount).as("工具真的被确认执行了").isEqualTo(1);
        assertThat(tool.arguments).containsEntry("taskId", 1827);
        Map<String, Object> lastAction = (Map<String, Object>) response.getMetadata().get("lastAction");
        assertThat(lastAction).as("确认成功的响应要带 lastAction, App 才能解读下一句「做完了」").isNotNull();
        assertThat(lastAction).containsEntry("intentCode", "WORK_PROCESS_TASK_START")
                .containsEntry("toolName", "work_process_task_start");
        assertThat((Map<String, Object>) lastAction.get("params")).containsExactly(Map.entry("taskId", 1827L));
    }

    @Test
    void previewOfTheSameWriteCarriesNoLastAction() {
        // 对照: 同一个真 dispatch、同一工具同一参数, 只是预览 —— 什么都没做, 不能有「上一步」
        IntentExecuteResponse preview = dispatch.executeWithTool(tool, FACTORY,
                IntentExecuteRequest.builder().userInput("开始1827号工序任务").previewOnly(true)
                        .context(new HashMap<>(Map.of("taskId", 1827))).build(),
                intent(), USER, ROLE, null);

        assertThat(preview.getStatus()).isEqualTo("PREVIEW");
        assertThat(tool.previewCount).as("预览真的跑到了工具").isEqualTo(1);
        assertThat(tool.executeCount).isZero();
        assertThat(preview.getMetadata() == null || !preview.getMetadata().containsKey("lastAction")).isTrue();
    }

    private ToolDispatchService realDispatch(ToolRegistry registry) {
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        ToolDispatchService d = new ToolDispatchService(
                registry, objectMapper, null, redundancy,
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(d, "writeGuardService", new WriteGuardService());
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(d, "toolRbacEnforcer", rbac);
        return d;
    }

    private static AIIntentConfig intent() {
        return AIIntentConfig.builder().intentCode("WORK_PROCESS_TASK_START").intentName("开始工序")
                .intentCategory("PRODUCTION").toolName("work_process_task_start").build();
    }

    private ClaimResult claim() {
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(TOKEN).factoryId(FACTORY).tenantId(FACTORY).userId(USER)
                .intentCode("WORK_PROCESS_TASK_START").toolName("work_process_task_start")
                .descriptorVersion("1.0.0").executionMode(ToolExecutionMode.EXECUTE)
                .status(TokenStatus.EXECUTING).expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        return ClaimResult.success(token, CLAIM, new HashMap<>(Map.of("taskId", 1827)));
    }

    private ConfirmationProof proof() {
        return new ConfirmationProof(TOKEN, DIGEST, Instant.now().plusSeconds(300));
    }

    private final class ScriptedStartTool implements ToolExecutor {
        private Map<String, Object> arguments = new HashMap<>();
        private int executeCount;
        private int previewCount;

        @Override
        public String getToolName() {
            return "work_process_task_start";
        }

        @Override
        public String getDescription() {
            return "开始工序";
        }

        @Override
        public AccessMode getAccessMode() {
            return AccessMode.WRITE;
        }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object",
                    "properties", Map.of("taskId", Map.of("type", "integer")),
                    "required", List.of("taskId"));
        }

        @Override
        public boolean supportsPreview() {
            return true;
        }

        @Override
        public String preview(ToolCall toolCall, Map<String, Object> context) {
            previewCount++;
            return "{\"success\":true,\"data\":{\"taskId\":1827,\"canDo\":true}}";
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) throws Exception {
            executeCount++;
            arguments = objectMapper.readValue(toolCall.getFunction().getArguments(),
                    new TypeReference<Map<String, Object>>() { });
            return "{\"success\":true,\"message\":\"工序任务 1827 已开始\",\"data\":{\"id\":1827}}";
        }
    }
}
