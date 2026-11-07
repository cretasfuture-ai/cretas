package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.ai.tool.gateway.ToolExecutionLedgerService.Reservation;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CanvasAIGatewayApplyContractTest {
    private static final String FACTORY = "LOCAL_B05";
    private static final String TOOL = "canvas_toggle_module";
    private final ObjectMapper mapper = new ObjectMapper();
    private PreviewTokenService tokens;
    private ToolExecutionGateway gateway;
    private CanvasAIController controller;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
        MobileService mobile = mock(MobileService.class);
        tokens = mock(PreviewTokenService.class);
        gateway = mock(ToolExecutionGateway.class);
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setFactoryId(FACTORY);
        user.setFactoryType("FACTORY");
        user.setRoleCode(FactoryUserRole.permission_admin);
        when(mobile.getUserFromToken("local-test")).thenReturn(user);
        controller = new CanvasAIController(registry, mapper, mock(DashScopeClient.class), mobile, gateway);
        ReflectionTestUtils.setField(controller, "previewTokenService", tokens);
        wireBinding(controller, tokens, LocalDateTime.now().plusMinutes(5));
    }

    @Test
    void applyBuildsGatewayCommandWithImmutableProofAndTokenDerivedIdempotency() throws Exception {
        Map<String, Object> params = Map.of("moduleCode", "inventory", "enabled", true);
        String token = "proof-" + UUID.randomUUID();
        String digest = ToolCommandDigest.commandDigest(FACTORY, 7L, TOOL, "2.0.0",
                ToolExecutionMode.EXECUTE, mapper.valueToTree(commandParameters(params)));
        when(tokens.readBoundSnapshot(token, FACTORY, 7L)).thenReturn(Optional.of(snapshot(token, digest)));
        when(gateway.execute(any())).thenReturn(result(ToolExecutionStatus.SUCCEEDED, mapper.createObjectNode()));

        CanvasAIController.ApplyDiffsResult receipt = controller.applyDiffs(
                FACTORY, "Bearer local-test", List.of(Map.of("tool", TOOL, "params", params,
                        "proof", Map.of("token", token, "commandDigest", digest, "descriptorVersion", "2.0.0"))))
                .getData();

        assertThat(receipt.items()).singleElement().extracting(CanvasAIController.ApplyDiffResult::status)
                .isEqualTo("SUCCESS");
        var command = org.mockito.ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        ToolExecutionCommand issued = command.getValue();
        assertThat(issued.source()).isEqualTo(ToolExecutionSource.HTTP_CONTROLLER);
        assertThat(issued.mode()).isEqualTo(ToolExecutionMode.EXECUTE);
        assertThat(issued.idempotencyKey()).contains(ToolCommandDigest.persistentSecretFingerprint(token));
        ConfirmationProof proof = issued.confirmationProof().orElseThrow();
        assertThat(proof.proofToken()).isEqualTo(token);
        assertThat(proof.commandDigest()).isEqualTo(digest);
    }

    @Test
    void tamperedParametersDoNotReachGateway() {
        Map<String, Object> params = Map.of("moduleCode", "inventory", "enabled", true);
        String token = "proof-tampered";
        String digest = ToolCommandDigest.commandDigest(FACTORY, 7L, TOOL, "2.0.0",
                ToolExecutionMode.EXECUTE, mapper.valueToTree(commandParameters(params)));
        when(tokens.readBoundSnapshot(token, FACTORY, 7L)).thenReturn(Optional.of(snapshot(token, digest)));
        var receipt = controller.applyDiffs(FACTORY, "Bearer local-test", List.of(Map.of(
                "tool", TOOL, "params", Map.of("moduleCode", "finance", "enabled", true),
                "proof", Map.of("token", token, "commandDigest", digest, "descriptorVersion", "2.0.0")))).getData();
        assertThat(receipt.items()).singleElement().extracting(CanvasAIController.ApplyDiffResult::status)
                .isEqualTo("FAILURE");
        verifyNoInteractions(gateway);
    }

    @Test
    void replayMapsOnlyOriginalSuccessToReusedAndUnknownStaysUnknown() {
        Map<String, Object> params = Map.of("moduleCode", "inventory", "enabled", true);
        String token = "proof-replay";
        String digest = ToolCommandDigest.commandDigest(FACTORY, 7L, TOOL, "2.0.0",
                ToolExecutionMode.EXECUTE, mapper.valueToTree(commandParameters(params)));
        when(tokens.readBoundSnapshot(token, FACTORY, 7L)).thenReturn(Optional.of(snapshot(token, digest)));
        var replayPayload = mapper.createObjectNode().put("originalStatus", "SUCCEEDED");
        when(gateway.execute(any())).thenReturn(result(ToolExecutionStatus.IDEMPOTENT_REPLAY, replayPayload));
        var receipt = controller.applyDiffs(FACTORY, "Bearer local-test", List.of(Map.of(
                "tool", TOOL, "params", params,
                "proof", Map.of("token", token, "commandDigest", digest, "descriptorVersion", "2.0.0")))).getData();
        assertThat(receipt.items()).singleElement().extracting(CanvasAIController.ApplyDiffResult::status)
                .isEqualTo("REUSED");
    }

    @Test
    void realControllerGatewayLeaseAndLedgerExecuteOnceThenReplayWithoutClaim() throws Exception {
        IntegrationHarness h = integrationHarness(LocalDateTime.now().plusMinutes(5));
        h.executorAnswer("{\"success\":true,\"data\":{\"changed\":true}}");

        assertThat(h.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("SUCCESS");

        h.duplicateExisting();
        assertThat(h.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("REUSED");

        verify(h.executor, times(1)).execute(any(ToolCall.class), any());
        verify(h.previewTokens, times(1)).claimToken(
                eq(h.token), eq(FACTORY), eq(7L), eq(h.digest));
        verify(h.previewTokens, times(1)).resolveClaim(
                eq(h.token), eq("claim-1"), eq(true), anyString());
        verify(h.idempotencyRepository, times(2)).saveAndFlush(any());
        verify(h.idempotencyRepository, times(1)).findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                eq(FACTORY), eq(PrincipalType.USER), eq("7"), eq(TOOL), eq("2.0.0"), anyString());
    }

    @Test
    void realGatewayFailureIsTerminalAndFailedReplayDoesNotExecuteAgain() throws Exception {
        IntegrationHarness h = integrationHarness(LocalDateTime.now().plusMinutes(5));
        h.executorAnswer("{\"success\":false,\"status\":\"NEED_MORE_INFO\"}");

        assertThat(h.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");

        h.duplicateExisting();
        assertThat(h.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        verify(h.executor, times(1)).execute(any(ToolCall.class), any());
        verify(h.previewTokens, times(1)).claimToken(anyString(), eq(FACTORY), eq(7L), anyString());
    }

    @Test
    void realGatewayInDoubtAndExecutingReplaysRemainUnknownWithoutAutomaticRetry() throws Exception {
        IntegrationHarness h = integrationHarness(LocalDateTime.now().plusMinutes(5));
        h.previewTokensClaimThrows();

        assertThat(h.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");

        h.duplicateExisting();
        assertThat(h.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
        verify(h.executor, never()).execute(any(ToolCall.class), any());
        verify(h.previewTokens, times(1)).claimToken(anyString(), eq(FACTORY), eq(7L), anyString());

        IntegrationHarness executing = integrationHarness(LocalDateTime.now().plusMinutes(5));
        executing.duplicateExisting(completedRecord(
                ToolExecutionStatus.OUTCOME_UNKNOWN, ToolExecutionIdempotencyRecord.State.IN_PROGRESS));
        assertThat(executing.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
        verify(executing.executor, never()).execute(any(ToolCall.class), any());
        verify(executing.previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void pendingOrNestedFailedSuccessEnvelopeBecomesInDoubtAndReplayStaysUnknown() throws Exception {
        for (String payload : List.of(
                "{\"success\":true,\"status\":\"PENDING_DDL\"}",
                "{\"success\":true,\"data\":{\"status\":\"PENDING_DDL\"}}",
                "{\"success\":true,\"data\":{\"data\":{\"status\":\"PENDING_DDL\"}}}")) {
            IntegrationHarness h = integrationHarness(LocalDateTime.now().plusMinutes(5));
            h.executorAnswer(payload);

            assertThat(h.apply().items()).singleElement()
                    .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
            assertThat(h.existing.get().getState())
                    .isEqualTo(ToolExecutionIdempotencyRecord.State.IN_DOUBT);
            assertThat(h.existing.get().getOutcomeStatus())
                    .isEqualTo(ToolExecutionStatus.OUTCOME_UNKNOWN);
            verify(h.previewTokens).resolveClaim(eq(h.token), eq("claim-1"), eq(false), anyString());

            h.duplicateExisting();
            assertThat(h.apply().items()).singleElement()
                    .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
            verify(h.executor, times(1)).execute(any(ToolCall.class), any());
        }

        for (String payload : List.of(
                "{\"success\":true,\"data\":{\"success\":false}}",
                "{\"success\":true,\"data\":{\"data\":{\"success\":false}}}")) {
            IntegrationHarness nestedFailure = integrationHarness(LocalDateTime.now().plusMinutes(5));
            nestedFailure.executorAnswer(payload);
            assertThat(nestedFailure.apply().items()).singleElement()
                    .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
            assertThat(nestedFailure.existing.get().getState())
                    .isEqualTo(ToolExecutionIdempotencyRecord.State.IN_DOUBT);
            verify(nestedFailure.previewTokens).resolveClaim(
                    eq(nestedFailure.token), eq("claim-1"), eq(false), anyString());
        }

        IntegrationHarness nestedFailure = integrationHarness(LocalDateTime.now().plusMinutes(5));
        nestedFailure.executorAnswer("{\"success\":true,\"data\":{\"success\":false}}");
        assertThat(nestedFailure.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("UNKNOWN");
        verify(nestedFailure.previewTokens).resolveClaim(
                eq(nestedFailure.token), eq("claim-1"), eq(false), anyString());
    }

    @Test
    void existingSuccessReplaysAfterTokenExpiryButNewExpiredRequestNeverClaimsOrExecutes() throws Exception {
        IntegrationHarness replay = integrationHarness(LocalDateTime.now().minusMinutes(1));
        replay.duplicateExisting(completedRecord(ToolExecutionStatus.SUCCEEDED));
        assertThat(replay.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("REUSED");
        verify(replay.previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verify(replay.executor, never()).execute(any(ToolCall.class), any());

        IntegrationHarness fresh = integrationHarness(LocalDateTime.now().minusMinutes(1));
        assertThat(fresh.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        verify(fresh.previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verify(fresh.executor, never()).execute(any(ToolCall.class), any());
        verify(fresh.idempotencyRepository).saveAndFlush(any());
    }

    @Test
    void controllerBindingTamperAndCrossTenantAreRejectedBeforeRealGateway() throws Exception {
        IntegrationHarness h = integrationHarness(LocalDateTime.now().plusMinutes(5));
        var tampered = h.controller.applyDiffs(
                FACTORY, "Bearer local-test", List.of(Map.of(
                        "tool", TOOL,
                        "params", Map.of("moduleCode", "finance", "enabled", true),
                        "proof", Map.of("token", h.token, "commandDigest", h.digest,
                                "descriptorVersion", "2.0.0")))).getData();
        assertThat(tampered.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        verify(h.executor, never()).execute(any(ToolCall.class), any());
        verify(h.previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());

        MobileService otherMobile = mock(MobileService.class);
        UserDTO other = new UserDTO();
        other.setId(8L);
        other.setFactoryId(FACTORY);
        other.setFactoryType("FACTORY");
        other.setRoleCode(FactoryUserRole.permission_admin);
        when(otherMobile.getUserFromToken("other-user")).thenReturn(other);
        CanvasAIController otherController = new CanvasAIController(
                mock(ToolRegistry.class), mapper, mock(DashScopeClient.class), otherMobile, h.gateway);
        ReflectionTestUtils.setField(otherController, "previewTokenService", h.previewTokens);
        var crossActor = otherController.applyDiffs(
                FACTORY, "Bearer other-user", List.of(h.diff())).getData();
        assertThat(crossActor.items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        verify(h.executor, never()).execute(any(ToolCall.class), any());

        var crossTenant = h.controller.applyDiffs(
                "OTHER_FACTORY", "Bearer local-test", List.of(h.diff()));
        assertThat(crossTenant.getSuccess()).isFalse();
        verify(h.executor, never()).execute(any(ToolCall.class), any());

        IntegrationHarness gatewayTenant = integrationHarness(LocalDateTime.now().plusMinutes(5));
        ExecutionPrincipal wrongTenant = new ExecutionPrincipal(
                "OTHER_FACTORY", "FACTORY", "7", PrincipalType.USER,
                Set.of("permission_admin"), Set.of(), Set.of());
        when(gatewayTenant.principalPolicy.rehydrate(any())).thenReturn(Optional.of(
                new RehydratedPrincipal(wrongTenant, Map.of(
                        "factoryId", "OTHER_FACTORY", "tenantId", "OTHER_FACTORY",
                        "businessType", "FACTORY", "userId", 7L, "userRole", "permission_admin"))));
        assertThat(gatewayTenant.apply().items()).singleElement()
                .extracting(CanvasAIController.ApplyDiffResult::status).isEqualTo("FAILURE");
        verify(gatewayTenant.previewTokens, never()).claimToken(anyString(), anyString(), anyLong(), anyString());
        verify(gatewayTenant.executor, never()).execute(any(ToolCall.class), any());
    }

    private IntegrationHarness integrationHarness(LocalDateTime expiresAt) {
        return new IntegrationHarness(expiresAt);
    }

    private ToolExecutionIdempotencyRecord completedRecord(ToolExecutionStatus status) {
        return completedRecord(status, status == ToolExecutionStatus.OUTCOME_UNKNOWN
                ? ToolExecutionIdempotencyRecord.State.IN_DOUBT
                : status == ToolExecutionStatus.SUCCEEDED
                        ? ToolExecutionIdempotencyRecord.State.SUCCEEDED
                        : ToolExecutionIdempotencyRecord.State.FAILED);
    }

    private ToolExecutionIdempotencyRecord completedRecord(
            ToolExecutionStatus status, ToolExecutionIdempotencyRecord.State state) {
        ToolExecutionIdempotencyRecord existing = mock(ToolExecutionIdempotencyRecord.class);
        when(existing.getBusinessType()).thenReturn("FACTORY");
        when(existing.getCommandDigest()).thenReturn(
                ToolCommandDigest.commandDigest(FACTORY, 7L, TOOL, "2.0.0",
                        ToolExecutionMode.EXECUTE, mapper.valueToTree(commandParameters(Map.of("moduleCode", "inventory", "enabled", true)))));
        when(existing.getConfirmationFingerprint()).thenReturn(
                ToolCommandDigest.persistentSecretFingerprint("proof-integration"));
        when(existing.getState()).thenReturn(state);
        when(existing.getOutcomeStatus()).thenReturn(status);
        return existing;
    }

    private final class IntegrationHarness {
        private final String token = "proof-integration";
        private final Map<String, Object> params = Map.of("moduleCode", "inventory", "enabled", true);
        private final String digest = ToolCommandDigest.commandDigest(
                FACTORY, 7L, TOOL, "2.0.0", ToolExecutionMode.EXECUTE, mapper.valueToTree(commandParameters(params)));
        private final PreviewTokenService previewTokens = mock(PreviewTokenService.class);
        private final ToolExecutionIdempotencyRepository idempotencyRepository = mock(ToolExecutionIdempotencyRepository.class);
        private final ToolExecutionAuditEventRepository auditRepository = mock(ToolExecutionAuditEventRepository.class);
        private final ToolExecutor executor = mock(ToolExecutor.class);
        private final ToolRuntimeRegistry runtimeRegistry = mock(ToolRuntimeRegistry.class);
        private final ToolPrincipalPolicy principalPolicy = mock(ToolPrincipalPolicy.class);
        private final AtomicBoolean duplicate = new AtomicBoolean(false);
        private final AtomicReference<ToolExecutionIdempotencyRecord> existing = new AtomicReference<>();
        private final CanvasAIController controller;
        private final ToolExecutionGateway gateway;

        private IntegrationHarness(LocalDateTime expiresAt) {
            ExecutionPrincipal principal = new ExecutionPrincipal(
                    FACTORY, "FACTORY", "7", PrincipalType.USER,
                    Set.of("permission_admin"), Set.of(), Set.of());
            when(principalPolicy.rehydrate(any())).thenReturn(Optional.of(
                    new RehydratedPrincipal(principal, Map.of(
                            "factoryId", FACTORY, "tenantId", FACTORY, "businessType", "FACTORY",
                            "userId", 7L, "userRole", "permission_admin"))));
            ToolDescriptor descriptor = new ToolDescriptor(
                    TOOL, ToolExecutor.ActionType.UPDATE, ToolExecutor.RiskLevel.LOW,
                    Set.of(), Set.of("permission_admin"), Set.of(FactoryType.FACTORY),
                    Set.of("canvas"), "2.0.0", false,
                    ConfirmationPolicy.REQUIRED_FOR_EXECUTION, ApprovalPolicy.NOT_REQUIRED,
                    IdempotencyPolicy.REQUIRED_FOR_EXECUTION, DataClassification.INTERNAL,
                    Set.of(ToolExecutionSource.HTTP_CONTROLLER), ToolEgressPolicy.denyAll(),
                    DescriptorProvenance.EXPLICIT);
            when(runtimeRegistry.resolve(any(), any())).thenReturn(Optional.of(
                    new ResolvedTool(descriptor, executor)));

            when(auditRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(auditRepository.bindSecurityEvidence(anyString(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(1);
            when(auditRepository.completeStarted(anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(1);
            when(idempotencyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
                if (duplicate.get()) {
                    throw new org.springframework.dao.DataIntegrityViolationException("duplicate test reservation");
                }
                ToolExecutionIdempotencyRecord record = invocation.getArgument(0);
                existing.set(record);
                return record;
            });
            when(idempotencyRepository.findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                    anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                    .thenAnswer(invocation -> Optional.ofNullable(existing.get()));
            when(idempotencyRepository.completeFromState(anyString(), any(), any(), any(), anyString(), any()))
                    .thenAnswer(invocation -> {
                        ToolExecutionIdempotencyRecord record = existing.get();
                        if (record != null && record.getId() != null) {
                            ReflectionTestUtils.setField(record, "state", invocation.getArgument(2));
                            ReflectionTestUtils.setField(record, "outcomeStatus", invocation.getArgument(3));
                        }
                        return 1;
                    });

            ToolConfirmationLease confirmationLease = new ToolConfirmationLease(previewTokens, mapper);
            ToolExecutionLedgerService ledger = new ToolExecutionLedgerService(auditRepository, idempotencyRepository);
            gateway = new DefaultToolExecutionGateway(
                    principalPolicy, runtimeRegistry, confirmationLease, ledger, mapper);

            MobileService mobile = mock(MobileService.class);
            UserDTO user = new UserDTO();
            user.setId(7L);
            user.setFactoryId(FACTORY);
            user.setFactoryType("FACTORY");
            user.setRoleCode(FactoryUserRole.permission_admin);
            when(mobile.getUserFromToken("local-test")).thenReturn(user);
            controller = new CanvasAIController(mock(ToolRegistry.class), mapper,
                    mock(DashScopeClient.class), mobile, gateway);
            ReflectionTestUtils.setField(controller, "previewTokenService", previewTokens);
            wireBinding(controller, previewTokens, expiresAt);

            when(previewTokens.readBoundSnapshot(token, FACTORY, 7L)).thenReturn(Optional.of(
                    snapshot(token, digest, expiresAt)));
            IntentPreviewToken claimed = IntentPreviewToken.builder()
                    .token(token).factoryId(FACTORY).tenantId(FACTORY).userId(7L)
                    .intentCode("CANVAS_DIFF_123e4567-e89b-12d3-a456-426614174000")
                    .entityType("CANVAS_DIFF").entityId(TOOL).operation("UPDATE")
                    .toolName(TOOL).descriptorVersion("2.0.0")
                    .executionMode(ToolExecutionMode.EXECUTE).commandDigest(digest)
                    .expiresAt(expiresAt).status(IntentPreviewToken.TokenStatus.PENDING)
                    .build();
            when(previewTokens.claimToken(token, FACTORY, 7L, digest))
                    .thenReturn(PreviewTokenService.ClaimResult.success(
                            claimed, "claim-1", commandParameters(params)));
            when(previewTokens.resolveClaim(eq(token), eq("claim-1"), anyBoolean(), anyString()))
                    .thenReturn(true);
        }

        private void executorAnswer(String response) {
            try {
                when(executor.execute(any(ToolCall.class), any())).thenReturn(response);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }

        private void previewTokensClaimThrows() {
            when(previewTokens.claimToken(token, FACTORY, 7L, digest))
                    .thenThrow(new IllegalStateException("claim unavailable in integration fixture"));
        }

        private void duplicateExisting(ToolExecutionIdempotencyRecord record) {
            existing.set(record);
            duplicate.set(true);
        }

        private void duplicateExisting() {
            assertThat(existing.get()).isNotNull();
            duplicate.set(true);
        }

        private Map<String, Object> diff() {
            return Map.of("tool", TOOL, "params", params,
                    "proof", Map.of("token", token, "commandDigest", digest,
                            "descriptorVersion", "2.0.0"));
        }

        private CanvasAIController.ApplyDiffsResult apply() {
            return controller.applyDiffs(FACTORY, "Bearer local-test", List.of(diff())).getData();
        }
    }

    private Map<String, Object> commandParameters(Map<String, Object> params) {
        Map<String, Object> internal = new HashMap<>(params);
        internal.put(com.cretas.aims.service.config.CanvasConfigPlanBindingService.PUBLIC_PARAMETERS, params);
        internal.put(com.cretas.aims.service.config.CanvasConfigPlanBindingService.BINDING,
                Map.of("factoryId", FACTORY, "tool", TOOL, "revision", "config:7",
                        "targetStateDigest", "fixed-state-7", "targetState", Map.of()));
        return internal;
    }

    private void wireBinding(CanvasAIController target, PreviewTokenService tokenService, LocalDateTime expiresAt) {
        var binding = new com.cretas.aims.service.config.CanvasConfigPlanBindingService(
                mock(com.cretas.aims.service.config.CanvasConfigStateReader.class), mapper);
        ReflectionTestUtils.setField(target, "canvasConfigPlanBindingService", binding);
        when(tokenService.readBoundExecution(anyString(), eq(FACTORY), eq(7L))).thenAnswer(invocation -> {
            Map<String, Object> internal = commandParameters(Map.of("moduleCode", "inventory", "enabled", true));
            String digest = ToolCommandDigest.commandDigest(FACTORY, 7L, TOOL, "2.0.0",
                    ToolExecutionMode.EXECUTE, mapper.valueToTree(internal));
            return Optional.of(new PreviewTokenService.BoundTokenExecution(invocation.getArgument(0),
                    FACTORY, FACTORY, 7L, TOOL, "2.0.0", ToolExecutionMode.EXECUTE,
                    "fixture-parameters-hash", digest, "{}", IntentPreviewToken.TokenStatus.PENDING,
                    expiresAt, internal));
        });
    }

    @Test
    void missingMandatoryBindingCannotReachGatewayEvenWithOtherwiseValidProof() {
        ReflectionTestUtils.setField(controller, "canvasConfigPlanBindingService", null);
        Map<String, Object> params = Map.of("moduleCode", "inventory", "enabled", true);
        String token = "missing-binding-proof";
        String digest = ToolCommandDigest.commandDigest(FACTORY, 7L, TOOL, "2.0.0",
                ToolExecutionMode.EXECUTE, mapper.valueToTree(commandParameters(params)));
        when(tokens.readBoundSnapshot(token, FACTORY, 7L)).thenReturn(Optional.of(snapshot(token, digest)));
        var receipt = controller.applyDiffs(FACTORY, "Bearer local-test", List.of(Map.of(
                "tool", TOOL, "params", params, "proof", Map.of("token", token,
                        "commandDigest", digest, "descriptorVersion", "2.0.0")))).getData();
        assertThat(receipt.items()).singleElement().extracting(CanvasAIController.ApplyDiffResult::status)
                .isEqualTo("FAILURE");
        verifyNoInteractions(gateway);
    }

    private PreviewTokenService.BoundTokenSnapshot snapshot(String token, String digest) {
        return snapshot(token, digest, LocalDateTime.now().plusMinutes(5));
    }

    private PreviewTokenService.BoundTokenSnapshot snapshot(
            String token, String digest, LocalDateTime expiresAt) {
        return new PreviewTokenService.BoundTokenSnapshot(
                "CANVAS_DIFF_123e4567-e89b-12d3-a456-426614174000", "CANVAS_DIFF", TOOL, "UPDATE",
                TOOL, "2.0.0", ToolExecutionMode.EXECUTE, digest,
                expiresAt,
                com.cretas.aims.entity.intent.IntentPreviewToken.TokenStatus.PENDING);
    }

    private ToolExecutionResult result(ToolExecutionStatus status, com.fasterxml.jackson.databind.JsonNode payload) {
        return new ToolExecutionResult("request", TOOL, "2.0.0", "audit", "trace", status,
                payload, "gateway result", status == ToolExecutionStatus.IDEMPOTENT_REPLAY);
    }
}
