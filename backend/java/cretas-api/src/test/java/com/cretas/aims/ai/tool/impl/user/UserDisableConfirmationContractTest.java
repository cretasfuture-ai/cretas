package com.cretas.aims.ai.tool.impl.user;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.mapper.UserMapper;
import com.cretas.aims.repository.ai.ToolExecutionAuditEventRepository;
import com.cretas.aims.repository.ai.ToolExecutionIdempotencyRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.UserService;
import com.cretas.aims.service.config.FactoryConfigService;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.cretas.aims.service.PreviewTokenService;
import com.cretas.aims.utils.ExcelUtil;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.gateway.*;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.entity.ai.ToolExecutionIdempotencyRecord;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.entity.intent.IntentPreviewToken;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.execution.ToolDispatchService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserDisableConfirmationContractTest {
    @Mock UserService userService;
    @Mock UserRepository userRepository;
    @Mock PermissionService permissionService;
    @Mock UserModuleAccessService userModuleAccess;
    @Mock FactoryConfigService factoryConfig;
    @Mock ToolPrincipalPolicy principalPolicy;
    @Mock ToolRuntimeRegistry runtimeRegistry;
    @Mock ToolExecutionAuditEventRepository auditRepository;
    @Mock ToolExecutionIdempotencyRepository idempotencyRepository;
    @Mock PreviewTokenService previewTokenService;

    private UserDisableTool tool;
    private User actor;
    private User target;

    @BeforeEach
    void setUp() {
        tool = new UserDisableTool();
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(tool, "userService", userService);
        ReflectionTestUtils.setField(tool, "userRepository", userRepository);
        ReflectionTestUtils.setField(tool, "permissionService", permissionService);
        ReflectionTestUtils.setField(tool, "userModuleAccessService", userModuleAccess);
        ReflectionTestUtils.setField(tool, "factoryConfigService", factoryConfig);

        actor = user(10L, "F-CONFIRM", "actor", "factory_admin", true);
        target = user(11L, "F-CONFIRM", "target", "operator", true);
    }

    @Test
    void previewUsesMandatoryTargetIdAndPerformsNoBusinessWrite() throws Exception {
        stubValidActor();
        String response = tool.preview(call("{\"targetUserId\":11}"), context(10L));

        assertThat(response).contains("\"success\":true", "\"status\":\"PREVIEW\"", "\"targetUserId\":11");
        verifyNoInteractions(userService);
        verify(userRepository, never()).deactivateIfEligible(anyString(), anyLong(), any());
    }

    @Test
    void executeSeparatesActorAndTargetAndRejectsUsernameOnlyRequests() throws Exception {
        stubValidActor();
        String response = tool.execute(call("{\"targetUserId\":11}"), context(10L));

        assertThat(response).contains("\"success\":true", "\"userId\":11");
        verify(userService).deactivateUser("F-CONFIRM", 11L);
        verify(userService, never()).deactivateUser("F-CONFIRM", 10L);

        String rejected = tool.execute(call("{\"username\":\"target\"}"), context(10L));
        assertThat(rejected).contains("\"success\":false", "targetUserId");
        verifyNoMoreInteractions(userService);
        verify(userRepository, never()).findByFactoryIdAndUsername(anyString(), anyString());
    }

    @Test
    void toolDispatchPreviewRebindsMaliciousRequestUserIdToTrustedActorWithoutWriting() throws Exception {
        stubValidActor();
        ToolDispatchService dispatch = new ToolDispatchService(
                mock(ToolRegistry.class), new ObjectMapper(), mock(DashScopeClient.class),
                mock(ToolCallRedundancyService.class), mock(SelfCorrectionService.class),
                mock(CorrectionAgentService.class), mock(ExternalVerifierService.class),
                mock(ToolResultValidatorService.class), mock(ParameterExtractionLearningService.class));
        PreviewTokenService dispatchTokenService = mock(PreviewTokenService.class);
        WriteGuardService dispatchWriteGuard = mock(WriteGuardService.class);
        ReflectionTestUtils.setField(dispatch, "previewTokenService", dispatchTokenService);
        ReflectionTestUtils.setField(dispatch, "writeGuardService", dispatchWriteGuard);
        when(dispatchWriteGuard.isWriteTool(tool)).thenReturn(true);
        when(dispatchTokenService.createBoundToken(any())).thenReturn(IntentPreviewToken.builder()
                .token("dispatch-preview-token")
                .commandDigest("dispatch-preview-digest")
                .expiresAt(java.time.LocalDateTime.now().plusMinutes(5))
                .build());

        AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode("USER_DISABLE")
                .intentName("禁用用户")
                .intentCategory("USER")
                .toolName("user_disable")
                .build();
        IntentExecuteRequest request = IntentExecuteRequest.builder()
                .intentCode("USER_DISABLE")
                .userInput("确认禁用目标用户 11")
                .context(new java.util.HashMap<>(Map.of("targetUserId", 11L, "userId", 999L)))
                .previewOnly(true)
                .build();

        IntentExecuteResponse response = dispatch.executeToolPreview(
                tool, "F-CONFIRM", request, intent, 10L, "factory_admin");

        assertThat(response.getStatus()).isEqualTo("PREVIEW");
        assertThat(response.getConfirmableAction()).isNotNull();
        assertThat(response.getMessage()).contains("targetUserId");
        verify(userRepository).findById(10L);
        verify(userRepository).findByIdAndFactoryId(11L, "F-CONFIRM");
        verify(userService, never()).deactivateUser(anyString(), anyLong());
        verify(userRepository, never()).deactivateIfEligible(anyString(), anyLong(), any());
        verify(dispatchTokenService).createBoundToken(any());
    }

    @Test
    void previewRejectsFractionalNonFiniteOverflowAndBooleanTargetIdsBeforeLookup() throws Exception {
        for (String arguments : new String[]{
                "{\"targetUserId\":1.5}",
                "{\"targetUserId\":\"1.5\"}",
                "{\"targetUserId\":NaN}",
                "{\"targetUserId\":Infinity}",
                "{\"targetUserId\":\"9223372036854775808\"}",
                "{\"targetUserId\":true}"}) {
            assertThat(tool.preview(call(arguments), context(10L)))
                    .contains("\"success\":false");
        }
        verify(userRepository, never()).findByIdAndFactoryId(anyLong(), anyString());
        verifyNoInteractions(userService);
    }

    @Test
    void previewAndExecuteFailClosedForModulePermissionInactiveActorAndProtectedTarget() throws Exception {
        stubValidActor();
        when(factoryConfig.isModuleEnabled("F-CONFIRM", "hr_employee")).thenReturn(false);
        assertThat(tool.preview(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);

        when(factoryConfig.isModuleEnabled("F-CONFIRM", "hr_employee")).thenReturn(true);
        actor.setIsActive(false);
        assertThat(tool.preview(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        actor.setIsActive(true);
        target.setRoleCode("platform_admin");
        assertThat(tool.execute(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);
    }

    @Test
    void authorizationAndTargetBoundariesFailClosedBeforeAnyBusinessWriter() throws Exception {
        stubValidActor();
        when(userModuleAccess.canAccessModule(actor, "hr_employee")).thenReturn(false);
        assertThat(tool.execute(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);
        verify(userRepository, never()).findByIdAndFactoryId(anyLong(), anyString());

        reset(permissionService, userModuleAccess, userService, userRepository);
        stubValidActor();
        when(userModuleAccess.canAccessModule(actor, "hr_employee"))
                .thenThrow(new IllegalStateException("module access backend unavailable"));
        assertThat(tool.preview(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);
        verify(userRepository, never()).findByIdAndFactoryId(anyLong(), anyString());

        reset(permissionService, userModuleAccess, userService, userRepository);
        stubValidActor();
        when(permissionService.hasAnyPermission(actor, "hr:read_write")).thenReturn(false);
        assertThat(tool.execute(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);
        verify(userRepository, never()).findByIdAndFactoryId(anyLong(), anyString());

        reset(permissionService, userService, userRepository);
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(permissionService.hasAnyPermission(actor, "hr:read_write"))
                .thenThrow(new IllegalStateException("permission backend unavailable"));
        assertThat(tool.preview(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);
        verify(userRepository, never()).findByIdAndFactoryId(anyLong(), anyString());

        reset(permissionService, userService, userRepository);
        stubValidActor();
        target.setFactoryId("F-OTHER");
        when(userRepository.findByIdAndFactoryId(11L, "F-CONFIRM")).thenReturn(Optional.empty());
        assertThat(tool.preview(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        assertThat(tool.preview(call("{\"targetUserId\":10}"), context(10L)))
                .contains("\"success\":false");
        target.setFactoryId("F-CONFIRM");
        target.setPosition(" platform_admin ");
        when(userRepository.findByIdAndFactoryId(11L, "F-CONFIRM")).thenReturn(Optional.of(target));
        assertThat(tool.execute(call("{\"targetUserId\":11}"), context(10L)))
                .contains("\"success\":false");
        verifyNoInteractions(userService);
        verify(userRepository, never()).deactivateIfEligible(anyString(), anyLong(), any());
    }

    @Test
    void serviceFallbackFailsClosedForUnknownActiveStateAndSoftDeletedTarget() {
        UserServiceImpl service = new UserServiceImpl(userRepository, mock(FactoryRepository.class),
                mock(UserMapper.class), mock(PasswordEncoder.class), mock(ExcelUtil.class));
        when(userRepository.deactivateIfEligible(eq("F-CONFIRM"), eq(11L), any()))
                .thenReturn(0);

        target.setIsActive(null);
        when(userRepository.findById(11L)).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.deactivateUser("F-CONFIRM", 11L))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("状态");

        target.setIsActive(false);
        target.setDeletedAt(java.time.LocalDateTime.now());
        assertThatThrownBy(() -> service.deactivateUser("F-CONFIRM", 11L))
                .isInstanceOf(com.cretas.aims.exception.EntityNotFoundException.class);
    }

    @Test
    void serviceZeroRowsClassifyMissingWrongFactoryProtectedAndAlreadyDisabled() {
        UserServiceImpl service = new UserServiceImpl(userRepository, mock(FactoryRepository.class),
                mock(UserMapper.class), mock(PasswordEncoder.class), mock(ExcelUtil.class));
        when(userRepository.deactivateIfEligible(eq("F-CONFIRM"), eq(11L), any())).thenReturn(0);

        when(userRepository.findById(11L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.deactivateUser("F-CONFIRM", 11L))
                .isInstanceOf(com.cretas.aims.exception.EntityNotFoundException.class);

        target.setFactoryId("F-OTHER");
        when(userRepository.findById(11L)).thenReturn(Optional.of(target));
        assertThatThrownBy(() -> service.deactivateUser("F-CONFIRM", 11L))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("无权");

        target.setFactoryId("F-CONFIRM");
        target.setRoleCode("operator");
        target.setPosition(" 平台管理员 ");
        assertThatThrownBy(() -> service.deactivateUser("F-CONFIRM", 11L))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("保护");

        target.setPosition(null);
        target.setIsActive(false);
        service.deactivateUser("F-CONFIRM", 11L);
        verify(userRepository, times(4)).deactivateIfEligible(eq("F-CONFIRM"), eq(11L), any());
    }

    @Test
    void realGatewayLeaseAndLedgerExecuteBoundTargetOnceThenReplayWithoutSecondBusinessWrite()
            throws Exception {
        stubValidActor();
        when(userRepository.deactivateIfEligible(eq("F-CONFIRM"), eq(11L), any())).thenReturn(1);
        UserServiceImpl actualService = new UserServiceImpl(userRepository,
                mock(FactoryRepository.class), mock(UserMapper.class), mock(PasswordEncoder.class),
                mock(ExcelUtil.class));
        UserDisableTool actualTool = new UserDisableTool();
        ReflectionTestUtils.setField(actualTool, "objectMapper", new ObjectMapper());
        ReflectionTestUtils.setField(actualTool, "userService", actualService);
        ReflectionTestUtils.setField(actualTool, "userRepository", userRepository);
        ReflectionTestUtils.setField(actualTool, "permissionService", permissionService);
        ReflectionTestUtils.setField(actualTool, "userModuleAccessService", userModuleAccess);
        ReflectionTestUtils.setField(actualTool, "factoryConfigService", factoryConfig);

        ExecutionPrincipal principal = new ExecutionPrincipal("F-CONFIRM", "FACTORY", "10",
                PrincipalType.USER, Set.of("factory_admin"), Set.of("hr:read_write"), Set.of());
        RehydratedPrincipal current = new RehydratedPrincipal(principal,
                Map.of("factoryId", "F-CONFIRM", "tenantId", "F-CONFIRM", "userId", 10L,
                        "userRole", "factory_admin", "roleCode", "factory_admin"));
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode parameters =
                mapper.createObjectNode().put("targetUserId", 11);
        String digest = ToolCommandDigest.commandDigest("F-CONFIRM", 10L, "user_disable", "3.0.0",
                ToolExecutionMode.EXECUTE, parameters);
        String tokenValue = "bound-user-disable-token";
        IntentPreviewToken token = IntentPreviewToken.builder()
                .token(tokenValue).factoryId("F-CONFIRM").tenantId("F-CONFIRM").userId(10L)
                .toolName("user_disable").descriptorVersion("3.0.0")
                .executionMode(ToolExecutionMode.EXECUTE).commandDigest(digest)
                .status(IntentPreviewToken.TokenStatus.PENDING)
                .expiresAt(java.time.LocalDateTime.now().plusMinutes(5)).build();
        when(previewTokenService.claimToken(tokenValue, "F-CONFIRM", 10L, digest))
                .thenReturn(PreviewTokenService.ClaimResult.success(token, "claim-1",
                        Map.of("targetUserId", 11)));
        when(previewTokenService.resolveClaim(tokenValue, "claim-1", true,
                "gateway execution completed")).thenReturn(true);

        ToolDescriptor descriptor = new ToolDescriptor("user_disable", ToolExecutor.ActionType.UPDATE,
                ToolExecutor.RiskLevel.HIGH, Set.of("hr:read_write"), Set.of(),
                Set.of(FactoryType.FACTORY), Set.of("user", "hr", "identity"), "3.0.0", true,
                ConfirmationPolicy.REQUIRED_FOR_EXECUTION, ApprovalPolicy.NOT_REQUIRED,
                IdempotencyPolicy.REQUIRED_FOR_EXECUTION, DataClassification.RESTRICTED,
                Set.of(ToolExecutionSource.AI_CHAT), ToolEgressPolicy.denyAll(),
                DescriptorProvenance.EXPLICIT);
        ToolExecutionCommand command = new ToolExecutionCommand("disable-request", "disable-correlation",
                "disable-trace", "user_disable", "3.0.0", parameters, principal,
                ToolExecutionSource.AI_CHAT, ToolExecutionMode.EXECUTE,
                Optional.of("disable-idempotency"),
                Optional.of(new ConfirmationProof(tokenValue, digest, java.time.Instant.now().plusSeconds(120))),
                Optional.empty(), java.time.Instant.now().plusSeconds(60));
        when(principalPolicy.rehydrate(principal)).thenReturn(Optional.of(current));
        when(runtimeRegistry.resolve(command, principal))
                .thenReturn(Optional.of(new ResolvedTool(descriptor, actualTool)));

        ToolExecutionLedgerService realLedger = new ToolExecutionLedgerService(auditRepository,
                idempotencyRepository);
        AtomicReference<ToolExecutionIdempotencyRecord> persisted = new AtomicReference<>();
        AtomicInteger reservations = new AtomicInteger();
        when(auditRepository.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(auditRepository.bindSecurityEvidence(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(1);
        when(auditRepository.completeStarted(anyString(), any(), any(), any(), anyString(), any()))
                .thenReturn(1);
        when(idempotencyRepository.saveAndFlush(any())).thenAnswer(invocation -> {
            ToolExecutionIdempotencyRecord row = invocation.getArgument(0);
            if (reservations.incrementAndGet() > 1) {
                throw new org.springframework.dao.DataIntegrityViolationException("duplicate test key");
            }
            persisted.set(row);
            return row;
        });
        when(idempotencyRepository.findByTenantIdAndPrincipalTypeAndPrincipalIdAndToolNameAndDescriptorVersionAndIdempotencyKeyHash(
                anyString(), any(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(persisted.get()));
        when(idempotencyRepository.completeFromState(anyString(), any(), any(), any(), anyString(), any()))
                .thenAnswer(invocation -> {
                    ReflectionTestUtils.setField(persisted.get(), "state", invocation.getArgument(2));
                    ReflectionTestUtils.setField(persisted.get(), "outcomeStatus", invocation.getArgument(3));
                    return 1;
                });

        DefaultToolExecutionGateway gateway = new DefaultToolExecutionGateway(principalPolicy,
                runtimeRegistry, new ToolConfirmationLease(previewTokenService, mapper), realLedger, mapper);
        ToolExecutionResult first = gateway.execute(command);
        ToolExecutionResult replay = gateway.execute(command);

        assertThat(first.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        assertThat(first.payload().path("data").path("targetUserId").asLong()).isEqualTo(11L);
        assertThat(replay.status()).isEqualTo(ToolExecutionStatus.IDEMPOTENT_REPLAY);
        assertThat(replay.replayed()).isTrue();
        verify(userRepository, times(1)).deactivateIfEligible(eq("F-CONFIRM"), eq(11L), any());
        verify(previewTokenService, times(1)).claimToken(tokenValue, "F-CONFIRM", 10L, digest);
        assertThat(persisted.get().getState()).isEqualTo(ToolExecutionIdempotencyRecord.State.SUCCEEDED);
    }

    @Test
    void metadataRequiresStableTargetAndPreview() {
        assertThat(tool.getVersion()).isEqualTo("3.0.0");
        assertThat(tool.supportsPreview()).isTrue();
        assertThat(tool.getParametersSchema().get("required")).asString().contains("targetUserId");
        assertThat(tool.getParametersSchema().toString()).doesNotContain("username");
    }

    private ToolCall call(String arguments) {
        return ToolCall.of("confirmation-1", "user_disable", arguments);
    }

    private void stubValidActor() {
        when(userRepository.findById(10L)).thenReturn(Optional.of(actor));
        when(userRepository.findByIdAndFactoryId(11L, "F-CONFIRM")).thenReturn(Optional.of(target));
        when(factoryConfig.isModuleEnabled("F-CONFIRM", "hr_employee")).thenReturn(true);
        when(userModuleAccess.canAccessModule(actor, "hr_employee")).thenReturn(true);
        when(permissionService.hasAnyPermission(actor, "hr:read_write")).thenReturn(true);
    }

    private Map<String, Object> context(long actorId) {
        return Map.of("factoryId", "F-CONFIRM", "userId", actorId, "userRole", "factory_admin");
    }

    private static User user(Long id, String factoryId, String username, String role, boolean active) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setUsername(username);
        user.setPasswordHash("test-only");
        user.setRoleCode(role);
        user.setIsActive(active);
        return user;
    }
}
