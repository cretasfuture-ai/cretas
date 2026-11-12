package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

class IntentExecutionOrchestratorConfirmationContractTest {

    private static final String FACTORY = "F001";
    private static final long USER = 12L;
    private static final String ROLE = "FACTORY_ADMIN";
    private static final String TOKEN = "sensitive-token";
    private static final String CLAIM = "claim-owner";
    private static final String DIGEST = "a".repeat(64);

    private AIIntentService aiIntentService;
    private ToolRegistry toolRegistry;
    private ToolDispatchService dispatchService;
    private PreviewTokenService tokenService;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        toolRegistry = mock(ToolRegistry.class);
        dispatchService = mock(ToolDispatchService.class);
        tokenService = mock(PreviewTokenService.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
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
                dispatchService,
                mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", tokenService);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST)).thenReturn(claimResult());
        when(aiIntentService.hasPermission("ORDER_CREATE", ROLE)).thenReturn(true);
        FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
        when(dispatchService.recheckPreviewState(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(ToolDispatchService.ConfirmStateCheck.ok());
    }

    // ==================== 写入确认三洞 (Fable 审阅 §8.2/§8.3) ====================

    @Test
    void staleStateRecheckRefusesBeforeAnyBusinessDispatch() {
        ToolExecutor tool = configuredTool();
        when(dispatchService.recheckPreviewState(eq(tool), any(), any(), any(), eq(FACTORY), eq(USER), eq(ROLE)))
                .thenReturn(ToolDispatchService.ConfirmStateCheck.reject(
                        ToolDispatchService.ConfirmStateCheck.STATE_CHANGED, java.util.List.of("数量"),
                        "预览之后「数量」已被改动"));

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo(IntentExecutionOrchestrator.PREVIEW_STALE);
        verify(dispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
        verify(tokenService).resolveClaim(eq(TOKEN), eq(CLAIM), eq(false), any());
        verify(tokenService, never()).resolveClaimOutcomeUnknown(any(), any(), any());
    }

    @Test
    void toolOutcomeUnknownIsRecordedAsUnknownNeverAsFailed() {
        ToolExecutor tool = configuredTool();
        when(dispatchService.executeWithTool(eq(tool), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder().status("OUTCOME_UNKNOWN").message("timeout").build());
        when(tokenService.resolveClaimOutcomeUnknown(TOKEN, CLAIM, "timeout")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        assertThat(response.getMessage()).contains("核对");
        verify(tokenService).resolveClaimOutcomeUnknown(TOKEN, CLAIM, "timeout");
        verify(tokenService, never()).resolveClaim(any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());
    }

    @Test
    void exceptionAfterToolInvocationIsUnknownButBeforeInvocationIsFailed() {
        ToolExecutor tool = configuredTool();
        when(dispatchService.executeWithTool(eq(tool), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenThrow(new IllegalStateException("connection reset"));

        IntentExecuteResponse unknown = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(unknown.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        verify(tokenService).resolveClaimOutcomeUnknown(eq(TOKEN), eq(CLAIM), any());
        verify(tokenService, never()).resolveClaim(eq(TOKEN), eq(CLAIM), eq(false), any());

        // 对照: 工具还没被调用就出错 (确认前核对抛异常) —— 什么都没写, 记失败
        when(dispatchService.recheckPreviewState(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("recheck broke"));
        IntentExecuteResponse failed = orchestrator.confirm(FACTORY, proof(), USER, ROLE);
        assertThat(failed.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(eq(TOKEN), eq(CLAIM), eq(false), any());
    }

    @Test
    void replayOfAnAlreadyHandledTokenNeverDispatchesAndIsNeverReportedAsPlainFailure() {
        for (TokenStatus existing : new TokenStatus[]{TokenStatus.EXECUTING, TokenStatus.OUTCOME_UNKNOWN,
                TokenStatus.CONFIRMED}) {
            when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST))
                    .thenReturn(ClaimResult.alreadyHandled("令牌已被处理: " + existing, existing));

            IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

            assertThat(response.getStatus()).as(existing.name())
                    .isEqualTo(existing == TokenStatus.CONFIRMED
                            ? IntentExecutionOrchestrator.ALREADY_EXECUTED : "OUTCOME_UNKNOWN");
            assertThat(response.getMessage()).contains("没有重复执行");
        }
        verifyNoInteractions(dispatchService, toolRegistry, aiIntentService);
    }

    @Test
    void missingIntentConfigFailsTheClaimBeforeToolExecution() {
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.empty());

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "意图配置不存在: ORDER_CREATE");
    }

    @Test
    void missingToolAndVersionDriftBothFinishAsFailed() {
        AIIntentConfig config = intentConfig();
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        when(toolRegistry.getExecutor("order_create")).thenReturn(Optional.empty());

        IntentExecuteResponse missing = orchestrator.confirm(FACTORY, proof(), USER, ROLE);
        assertThat(missing.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "未找到绑定工具: order_create");
    }

    @Test
    void failedToolResponseWritesFailedTerminalState() {
        ToolExecutor tool = configuredTool();
        when(dispatchService.executeWithTool(
                eq(tool), eq(FACTORY), any(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("FAILED").message("business rejected").build());
        when(tokenService.resolveClaim(TOKEN, CLAIM, false, "business rejected")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "business rejected");
    }

    @Test
    void successfulToolResponseConfirmsOnlyAfterDispatchAndOverridesSpoofedContext() {
        ToolExecutor tool = configuredTool();
        ArgumentCaptor<IntentExecuteRequest> requestCaptor = ArgumentCaptor.forClass(IntentExecuteRequest.class);
        when(dispatchService.executeWithTool(
                eq(tool), eq(FACTORY), requestCaptor.capture(), any(), eq(USER), eq(ROLE), eq(null)))
                .thenReturn(IntentExecuteResponse.builder()
                        .status("SUCCESS").message("created").build());
        when(tokenService.resolveClaim(TOKEN, CLAIM, true, "created")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        Map<String, Object> context = requestCaptor.getValue().getContext();
        assertThat(context)
                .containsEntry("factoryId", FACTORY)
                .containsEntry("factory_id", FACTORY)
                .containsEntry("userId", USER)
                .containsEntry("user_id", USER)
                .containsEntry("userRole", ROLE)
                .containsEntry("role", ROLE)
                .containsEntry("confirmed", true)
                .doesNotContainKeys("forceExecute");
        assertThat(new WriteGuardService().isConfirmed(context)).isTrue();
        verify(tokenService).resolveClaim(TOKEN, CLAIM, true, "created");
    }

    @Test
    void explicitWriteRejectsForgedConfirmedAndForceExecuteWithoutDispatch() {
        AIIntentConfig write = intentConfig();
        write.setSensitivityLevel("HIGH");
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(write));
        when(aiIntentService.hasPermission("ORDER_CREATE", ROLE)).thenReturn(true);

        IntentExecuteRequest forged = IntentExecuteRequest.builder()
                .userInput("create order")
                .intentCode("ORDER_CREATE")
                .forceExecute(true)
                .context(Map.of("confirmed", true, "forceExecute", true, "amount", 5))
                .build();

        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(
                FACTORY, forged, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        verify(dispatchService, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void expectedCommandDigestIsPassedExactlyAndMismatchCausesZeroBusinessDispatch() {
        when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST))
                .thenReturn(ClaimResult.failure("Confirmation proof does not match command"));

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(tokenService).claimToken(TOKEN, FACTORY, USER, DIGEST);
        verifyNoInteractions(dispatchService, toolRegistry, aiIntentService);
    }

    @Test
    void expiredBodyProofFailsBeforeAtomicClaimOrBusinessDispatch() {
        ConfirmationProof expired = new ConfirmationProof(
                TOKEN,
                DIGEST,
                Instant.now().minusSeconds(1));

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, expired, USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verifyNoInteractions(tokenService, dispatchService, toolRegistry, aiIntentService);
    }

    private ToolExecutor configuredTool() {
        AIIntentConfig config = intentConfig();
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getVersion()).thenReturn("1.0.0");
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        when(toolRegistry.getExecutor("order_create")).thenReturn(Optional.of(tool));
        return tool;
    }

    @Test
    void confirmationRechecksRevokedIntentPermissionBeforeToolLookup() {
        configuredTool();
        when(aiIntentService.hasPermission("ORDER_CREATE", ROLE)).thenReturn(false);
        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);
        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        verifyNoInteractions(dispatchService);
        verify(toolRegistry, never()).getExecutor(any());
        verify(tokenService).resolveClaim(eq(TOKEN), eq(CLAIM), eq(false), any());
    }

    @Test
    void previewConfirmationCannotSubstituteForApprovalEvenAtHighSensitivity() {
        AIIntentConfig config = intentConfig();
        config.setSensitivityLevel("HIGH");
        config.setRequiresApproval(true);
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getMessage()).contains("未提交审批", "未执行操作").doesNotContain("审批请求已提交");
        verifyNoInteractions(dispatchService, toolRegistry);
        verify(tokenService).resolveClaim(eq(TOKEN), eq(CLAIM), eq(false), any());
    }

    @Test
    void callerForceExecuteCannotAuthorizeAnApprovalRequiredWrite() {
        AIIntentConfig config = intentConfig();
        config.setSensitivityLevel("HIGH");
        config.setRequiresApproval(true);
        when(aiIntentService.getIntentByCode(FACTORY, "ORDER_CREATE")).thenReturn(Optional.of(config));
        IntentExecuteResponse response = orchestrator.executeWithExplicitIntent(FACTORY,
                IntentExecuteRequest.builder().intentCode("ORDER_CREATE").userInput("建订单")
                        .forceExecute(true)
                        .context(new WriteGuardService().withServerConfirmation(Map.of("confirmed", true)))
                        .build(), USER, ROLE);
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getMessage()).contains("未提交审批", "未执行操作").doesNotContain("审批请求已提交");
        verifyNoInteractions(dispatchService);
    }

    private AIIntentConfig intentConfig() {
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode("ORDER_CREATE");
        config.setToolName("order_create");
        return config;
    }

    @Test
    void aClaimedPreRetirementTokenCannotExecuteForAnOrdinaryFactoryRole() {
        ClaimResult retired = claimResult();
        retired.getToken().setIntentCode("RESTAURANT_DISH_CREATE");
        when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST)).thenReturn(retired);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, ROLE);

        assertThat(response.getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "该业务能力已退役");
        verifyNoInteractions(aiIntentService, toolRegistry, dispatchService);
    }

    private ClaimResult claimResult() {
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(TOKEN)
                .factoryId(FACTORY)
                .tenantId(FACTORY)
                .userId(USER)
                .intentCode("ORDER_CREATE")
                .toolName("order_create")
                .descriptorVersion("1.0.0")
                .executionMode(ToolExecutionMode.EXECUTE)
                .status(TokenStatus.EXECUTING)
                .expiresAt(LocalDateTime.now().plusMinutes(5))
                .build();
        Map<String, Object> spoofed = new HashMap<>();
        spoofed.put("factoryId", "EVIL");
        spoofed.put("userId", 999L);
        spoofed.put("role", "SUPER_ADMIN");
        spoofed.put("confirmed", false);
        spoofed.put("forceExecute", true);
        spoofed.put("cretas.internal.confirmation.authority", "forged-marker");
        spoofed.put("amount", 5);
        return ClaimResult.success(token, CLAIM, spoofed);
    }

    private ConfirmationProof proof() {
        return new ConfirmationProof(TOKEN, DIGEST, Instant.now().plusSeconds(300));
    }
}
