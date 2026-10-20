package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseOrderCreateTool;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.ConfirmationProof;
import com.cretas.aims.ai.tool.gateway.ToolExecutionMode;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.PreviewTokenService.ClaimResult;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import com.cretas.aims.service.PreviewTokenService.BoundTokenRequest;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.List;
import com.cretas.aims.dto.ai.IntentExecuteRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

class PurchaseTaskConfirmBoundaryContractTest {
    private static final String FACTORY = "F001";
    private static final long USER = 12L;
    private static final String TOKEN = "purchase-proof";
    private static final String CLAIM = "server-claim";
    private static final String DIGEST = "a".repeat(64);

    private PreviewTokenService tokenService;
    private AIIntentService aiIntentService;
    private ToolDispatchService dispatch;
    private ToolRegistry registry;
    private ControlledPurchaseTaskExecutionService controlled;
    private IntentExecutionOrchestrator orchestrator;
    private ToolExecutor canonicalTool;

    @BeforeEach
    void setUp() {
        tokenService = mock(PreviewTokenService.class);
        dispatch = mock(ToolDispatchService.class);
        registry = mock(ToolRegistry.class);
        controlled = mock(ControlledPurchaseTaskExecutionService.class);
        canonicalTool = mock(ToolExecutor.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService = mock(AIIntentService.class), mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class), mock(ConversationMemoryService.class),
                new ObjectMapper(), mock(DashScopeClient.class), mock(DashScopeConfig.class), mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class), registry, mock(AnalysisRouterService.class), mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                dispatch, mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "previewTokenService", tokenService);
        ReflectionTestUtils.setField(orchestrator, "controlledPurchase", controlled);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        when(tokenService.claimToken(TOKEN, FACTORY, USER, DIGEST)).thenReturn(claimResult());
        AIIntentConfig config = new AIIntentConfig();
        config.setIntentCode("PURCHASE_ORDER_CREATE");
        config.setToolName("purchase_order_create");
        when(aiIntentService.getIntentByCode(FACTORY, "PURCHASE_ORDER_CREATE")).thenReturn(Optional.of(config));
        when(aiIntentService.hasPermission("PURCHASE_ORDER_CREATE", "FACTORY_ADMIN")).thenReturn(true);
        when(controlled.replay(eq(FACTORY), any())).thenReturn(Optional.empty());
        when(registry.getExecutor("purchase_order_create")).thenReturn(Optional.of(canonicalTool));
        when(canonicalTool.getVersion()).thenReturn(ControlledPurchaseTaskExecutionService.VERSION);
    }

    @Test
    void canonicalSuccessCallsControlledFacadeAndNeverLegacyDispatch() {
        when(controlled.executeConfirmed(FACTORY, 91L, CLAIM))
                .thenReturn(new ControlledPurchaseTaskExecutionService.Outcome("preview-91", "DRAFT", "PO-91", null, null, 2L, false));
        when(tokenService.resolveClaim(TOKEN, CLAIM, true, "采购草稿已创建:PO-91")).thenReturn(true);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(controlled).executeConfirmed(FACTORY, 91L, CLAIM);
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void controlledRejectionResolvesFailedAndDoesNotRetryLegacyRoute() {
        when(controlled.executeConfirmed(FACTORY, 91L, CLAIM)).thenThrow(new com.cretas.aims.exception.BusinessException(403, "scope rejected"));

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "受控采购被拒绝");
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void currentPermissionDenialResolvesClaimAndNeverCallsControlledFacade() {
        when(aiIntentService.hasPermission("PURCHASE_ORDER_CREATE", "FACTORY_ADMIN")).thenReturn(false);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        verify(tokenService).resolveClaim(TOKEN, CLAIM, false, "当前意图权限不足");
        verify(controlled, never()).executeConfirmed(anyString(), anyLong(), anyString());
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void successfulFacadeWithUnresolvedClaimReturnsUnknownAndCannotRetryLegacyRoute() {
        when(controlled.executeConfirmed(FACTORY, 91L, CLAIM))
                .thenReturn(new ControlledPurchaseTaskExecutionService.Outcome("preview-91", "DRAFT", "PO-91", null, null, 2L, false));
        when(tokenService.resolveClaim(TOKEN, CLAIM, true, "采购草稿已创建:PO-91")).thenReturn(false);

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        verify(controlled).executeConfirmed(FACTORY, 91L, CLAIM);
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void unexpectedFacadeFailureReturnsUnknownWithoutRetry() {
        when(controlled.executeConfirmed(FACTORY, 91L, CLAIM)).thenThrow(new RuntimeException("commit uncertain"));

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("OUTCOME_UNKNOWN");
        verify(tokenService, never()).resolveClaim(TOKEN, CLAIM, false, "受控采购被拒绝");
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void replayReturnsPersistedOutcomeBeforeClaimAndLegacyDispatch() {
        when(controlled.replay(eq(FACTORY), any())).thenReturn(Optional.of(
                new ControlledPurchaseTaskExecutionService.Outcome("preview-91", "DRAFT", "PO-91", null, null, 2L, true)));

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(controlled, never()).executeConfirmed(anyString(), anyLong(), anyString());
        verify(tokenService, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void descriptorVersionDriftRejectsBeforeControlledExecution() {
        when(canonicalTool.getVersion()).thenReturn("9.9.9");

        IntentExecuteResponse response = orchestrator.confirm(FACTORY, proof(), USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("FAILED");
        verify(controlled, never()).executeConfirmed(anyString(), anyLong(), anyString());
        verify(dispatch, never()).executeWithTool(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void actualDispatchWiresCanonicalPurchasePreviewIntoBoundToken() throws Exception {
        ToolDispatchService actualDispatch = actualDispatch();
        PurchaseOrderCreateTool tool = new PurchaseOrderCreateTool(controlled);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper().findAndRegisterModules());
        Map<String, Object> flat = new java.util.LinkedHashMap<>(Map.of(
                "supplierId", "SUP-1", "materialTypeId", "MAT-1", "quantity", 2,
                "unit", "kg", "unitPrice", 10, "currency", "CNY", "taxRate", 13,
                "amountCap", 22.60));
        Map<String, Object> canonical = new java.util.LinkedHashMap<>(Map.of(
                "purchaseOrderRequest", Map.of("supplierId", "SUP-1", "items", List.of(Map.of("materialTypeId", "MAT-1"))),
                "currency", "CNY", "amountCap", 22.60, "net", 20.00, "tax", 2.60, "gross", 22.60));
        when(controlled.previewParameters(eq(FACTORY), anyMap())).thenReturn(canonical);
        when(controlled.preview(FACTORY, canonical)).thenReturn(Map.of("status", "PREVIEW", "newValues", canonical));
        IntentPreviewToken bound = IntentPreviewToken.builder().id(91L).token(TOKEN).commandDigest(DIGEST)
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        when(tokenService.createBoundToken(any(BoundTokenRequest.class))).thenReturn(bound);
        AIIntentConfig purchaseIntent = AIIntentConfig.builder().intentCode("PURCHASE_ORDER_CREATE")
                .intentName("创建采购单").intentCategory("PROCUREMENT").toolName("purchase_order_create").build();

        IntentExecuteResponse response = actualDispatch.executeToolPreview(tool, FACTORY,
                IntentExecuteRequest.builder().intentCode("PURCHASE_ORDER_CREATE")
                        .userInput("创建采购单").context(flat).previewOnly(true).build(),
                purchaseIntent, USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("PREVIEW");
        assertThat(response.getConfirmableAction()).isNotNull();
        assertThat(response.getConfirmableAction().getPreviewData()).containsEntry("grantId", "preview-91");
        assertThat(response.getResultData()).isEqualTo(Map.of("grantId", "preview-91"));
        ArgumentCaptor<BoundTokenRequest> request = ArgumentCaptor.forClass(BoundTokenRequest.class);
        verify(tokenService).createBoundToken(request.capture());
        assertThat(request.getValue().parameters()).isEqualTo(canonical);
        verify(controlled).previewParameters(eq(FACTORY), argThat(values ->
                "SUP-1".equals(values.get("supplierId")) && "MAT-1".equals(values.get("materialTypeId"))));
        verify(controlled).preview(FACTORY, canonical);
    }

    @Test
    void actualDispatchDoesNotIssueTokenWhenCanonicalFacadeRejectsFlatPreview() throws Exception {
        ToolDispatchService actualDispatch = actualDispatch();
        PurchaseOrderCreateTool tool = new PurchaseOrderCreateTool(controlled);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper().findAndRegisterModules());
        Map<String, Object> flat = new java.util.LinkedHashMap<>(Map.of(
                "supplierId", "SUP-1", "materialTypeId", "MAT-1", "quantity", 2,
                "unit", "kg", "unitPrice", 10, "currency", "CNY", "taxRate", 9,
                "amountCap", 22.60));
        when(controlled.previewParameters(eq(FACTORY), anyMap())).thenThrow(new IllegalArgumentException("税率不支持"));
        AIIntentConfig purchaseIntent = AIIntentConfig.builder().intentCode("PURCHASE_ORDER_CREATE")
                .intentName("创建采购单").intentCategory("PROCUREMENT").toolName("purchase_order_create").build();

        IntentExecuteResponse response = actualDispatch.executeToolPreview(tool, FACTORY,
                IntentExecuteRequest.builder().intentCode("PURCHASE_ORDER_CREATE")
                        .userInput("创建采购单").context(flat).previewOnly(true).build(),
                purchaseIntent, USER, "FACTORY_ADMIN");

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getConfirmableAction()).isNull();
        verify(tokenService, never()).createBoundToken(any());
        verify(controlled, never()).preview(eq(FACTORY), anyMap());
    }

    private ToolDispatchService actualDispatch() {
        ToolDispatchService actual = new ToolDispatchService(mock(ToolRegistry.class), new ObjectMapper(),
                mock(DashScopeClient.class), mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(actual, "previewTokenService", tokenService);
        ReflectionTestUtils.setField(actual, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(actual, "controlledPurchase", controlled);
        return actual;
    }

    private ClaimResult claimResult() {
        IntentPreviewToken token = IntentPreviewToken.builder().id(91L).token(TOKEN).factoryId(FACTORY).tenantId(FACTORY)
                .userId(USER).intentCode("PURCHASE_ORDER_CREATE").toolName("purchase_order_create")
                .descriptorVersion(ControlledPurchaseTaskExecutionService.VERSION).executionMode(ToolExecutionMode.EXECUTE)
                .status(IntentPreviewToken.TokenStatus.EXECUTING).claimId(CLAIM)
                .expiresAt(LocalDateTime.now().plusMinutes(5)).build();
        return ClaimResult.success(token, CLAIM, Map.of());
    }

    private ConfirmationProof proof() { return new ConfirmationProof(TOKEN, DIGEST, Instant.now().plusSeconds(300)); }
}
