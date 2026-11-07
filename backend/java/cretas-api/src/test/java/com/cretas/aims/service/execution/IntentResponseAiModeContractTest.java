package com.cretas.aims.service.execution;

import com.cretas.aims.ai.catalog.FactoryIntentCatalog;
import com.cretas.aims.ai.capability.FactoryCapabilityPack;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRegistry;
import com.cretas.aims.ai.capability.FactoryCapabilityPackRoutingPolicy;
import com.cretas.aims.ai.capability.FactoryCapabilityPackSelector;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.ValidationResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Real response construction; neither permissions nor the write executor are replaced by a success stub. */
class IntentResponseAiModeContractTest {
    private IntentExecutionOrchestrator orchestrator;
    private ToolRegistry tools;

    @BeforeEach
    void setUp() {
        orchestrator = mock(IntentExecutionOrchestrator.class, CALLS_REAL_METHODS);
        tools = mock(ToolRegistry.class);
        ReflectionTestUtils.setField(orchestrator, "toolRegistry", tools);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        var metadata = mock(IntentConfigManagementService.class);
        when(metadata.resolveBusinessDomain("F006")).thenReturn("FACTORY");
        ReflectionTestUtils.setField(orchestrator, "configService", metadata);
    }

    @Test
    void realExecuteEarlyRetirementResponseHasReadModeWithoutRevivingCapability() {
        var response = orchestrator.execute("F006", IntentExecuteRequest.builder()
                .intentCode(FactoryIntentCatalog.retiredCodes().iterator().next())
                .userInput("已退役能力").mode("READ").build(), 1707L, "viewer");
        assertThat(response.getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        assertThat(response.getIntentRecognized()).isFalse();
        assertThat(response.getAiMode()).isEqualTo("READ");
        assertThat(response.getConfirmableAction()).isNull();
        assertThat(response.getAffectedEntities()).isNullOrEmpty();
    }

    @Test
    void ambiguousReadKeepsClarificationAndReadMode() {
        var intent = intent("PRODUCTION_STATUS", null);
        var response = clarify(intent);
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getMessage()).contains("请选择");
        assertThat(response.getAiMode()).isEqualTo("READ");
        assertThat(response.getConfirmableAction()).isNull();
    }

    @Test
    void ambiguousDeclaredWriteKeepsClarificationAndWriteModeWithoutExecuting() throws Exception {
        var tool = mock(ToolExecutor.class);
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(tools.getExecutor("factory_config_agent")).thenReturn(Optional.of(tool));
        var response = clarify(intent("FACTORY_CONFIG_AGENT", "factory_config_agent"));
        assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getConfirmableAction()).isNull();
        verify(tool, never()).execute(any(ToolCall.class), anyMap());
    }

    @Test
    void deniedWriteDoesNotBecomeSuccessOrGainPermissions() {
        var response = denied(intent("PURCHASE_ORDER_CREATE", null));
        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getConfirmableAction()).isNull();
        assertThat(response.getResultData()).isNull();
    }

    @Test
    void deniedReadStillHasReadModeAndNoResult() {
        var response = denied(intent("RETURN_ORDER_LIST", null));
        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(response.getAiMode()).isEqualTo("READ");
        assertThat(response.getResultData()).isNull();
    }

    @Test
    void approvalBuilderAlsoUsedByConfirmationKeepsWriteModeAndDoesNotExecute() {
        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(orchestrator, "buildApprovalResponse",
                intent("PRODUCT_CREATE_FLYWHEEL", null));
        assertThat(response.getStatus()).isEqualTo("PENDING_APPROVAL");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getConfirmableAction()).isNull();
        assertThat(response.getResultData()).isNull();
    }

    @Test
    void businessRuleFailureKeepsWriteModeWithoutChangingTheFailure() {
        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(orchestrator, "buildDroolsFailureResponse",
                intent("PRODUCT_CREATE_FLYWHEEL", null), ValidationResult.builder().valid(false).build());
        assertThat(response.getStatus()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getConfirmableAction()).isNull();
        assertThat(response.getResultData()).isNull();
    }

    @Test
    void realExecuteUnsupportedWritePreviewCannotBeStampedAsRead() {
        var intent = intent("PRODUCT_CREATE_FLYWHEEL", "preview_missing");
        var recognizer = mock(AIIntentService.class);
        when(recognizer.getIntentByCode("F006", intent.getIntentCode())).thenReturn(Optional.of(intent));
        when(recognizer.hasPermission(intent.getIntentCode(), "viewer")).thenReturn(true);
        ReflectionTestUtils.setField(orchestrator, "aiIntentService", recognizer);
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", mock(BusinessTypeGate.class));
        var response = orchestrator.execute("F006", IntentExecuteRequest.builder().intentCode(intent.getIntentCode())
                .userInput("预览产品").previewOnly(true).build(), 1707L, "viewer");
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("预览模式暂不支持");
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getConfirmableAction()).isNull();
        assertThat(response.getResultData()).isNull();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void workflowMutationGuidanceKeepsWriteModeThroughBothPublicEntrypoints(boolean explicitEntry) {
        bindWorkflowGuidance();
        var request = IntentExecuteRequest.builder().intentCode("PRODUCTION_REPORT").userInput("我要报工").build();
        try (var ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1707L, "F006", "operator"))) {
            var response = explicitEntry
                    ? orchestrator.executeWithExplicitIntent("F006", request, 1707L, "operator")
                    : orchestrator.execute("F006", request, 1707L, "operator");
            assertThat(response.getStatus()).isEqualTo("FACTORY_PACK_WORKFLOW_GUIDANCE");
            assertThat(response.getAiMode()).isEqualTo("WRITE");
            assertThat(response.getMetadata()).containsEntry("mutation", true);
            assertThat(response.getConfirmableAction()).isNull();
            assertThat(response.getResultData()).isNull();
        }
    }

    @Test
    void nonMutationWorkflowGuidanceStillUsesReadMode() {
        var policy = bindWorkflowGuidance();
        IntentExecuteResponse response = ReflectionTestUtils.invokeMethod(orchestrator, "buildFactoryPackWorkflowGuidance",
                policy.evaluate("F006", "operator", "我要报工"), intent("READ_GUIDANCE", null),
                new FactoryCapabilityPack.WorkflowReference("FORM:READ_FIXTURE",
                        FactoryCapabilityPack.WorkflowReferenceType.FORM, false, false));
        assertThat(response.getStatus()).isEqualTo("FACTORY_PACK_WORKFLOW_GUIDANCE");
        assertThat(response.getAiMode()).isEqualTo("READ");
        assertThat(response.getMetadata()).containsEntry("mutation", false);
        assertThat(response.getConfirmableAction()).isNull();
    }

    @Test
    void existingWriteModeIsNeverOverwrittenByReadFallback() {
        var response = IntentExecuteResponse.builder().status("READ_MODE_WRITE_BLOCKED").aiMode("WRITE").build();
        ReflectionTestUtils.invokeMethod(orchestrator, "applyAiModeStamp", response, null, null);
        assertThat(response.getAiMode()).isEqualTo("WRITE");
        assertThat(response.getStatus()).isEqualTo("READ_MODE_WRITE_BLOCKED");
    }

    private IntentExecuteResponse clarify(AIIntentConfig intent) {
        return orchestrator.buildClarificationResponse(IntentMatchResult.builder().bestMatch(intent)
                .confidence(0.55).topCandidates(List.of()).clarificationQuestion("请选择生产看板或生产报表").build(), "F006");
    }

    private IntentExecuteResponse denied(AIIntentConfig intent) {
        return ReflectionTestUtils.invokeMethod(orchestrator, "buildNoPermissionResponse", intent);
    }

    private FactoryCapabilityPackRoutingPolicy bindWorkflowGuidance() {
        var factory = new Factory();
        factory.setId("F006");
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        var factories = mock(FactoryRepository.class);
        when(factories.findById("F006")).thenReturn(Optional.of(factory));
        var policy = new FactoryCapabilityPackRoutingPolicy(factories,
                new FactoryCapabilityPackSelector(new FactoryCapabilityPackRegistry()), true);
        ReflectionTestUtils.setField(orchestrator, "factoryCapabilityPackRoutingPolicy", policy);
        var recognizer = mock(AIIntentService.class);
        var writeIntent = intent("PRODUCTION_REPORT", "production_report_submit");
        writeIntent.setSensitivityLevel("HIGH");
        when(recognizer.getIntentByCode("F006", "PRODUCTION_REPORT")).thenReturn(Optional.of(writeIntent));
        when(recognizer.hasPermission("PRODUCTION_REPORT", "operator")).thenReturn(true);
        ReflectionTestUtils.setField(orchestrator, "aiIntentService", recognizer);
        return policy;
    }

    private AIIntentConfig intent(String code, String tool) {
        var intent = new AIIntentConfig();
        intent.setIntentCode(code);
        intent.setIntentName(code);
        intent.setToolName(tool);
        intent.setSensitivityLevel("LOW");
        intent.setBusinessType("COMMON");
        return intent;
    }
}
