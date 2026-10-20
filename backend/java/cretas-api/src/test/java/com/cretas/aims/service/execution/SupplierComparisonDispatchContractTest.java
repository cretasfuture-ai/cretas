package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The dispatch layer must preserve an unavailable supplier comparison as a
 * failed read.  A normal JSON response is not an execution exception, so the
 * result status, audit record and cache decision all need explicit coverage.
 */
class SupplierComparisonDispatchContractTest {

    private static final String UNAVAILABLE = "{\"success\":false,\"error\":\"供应商价格比较暂不可用：未接入真实报价\","
            + "\"data\":{\"status\":\"UNAVAILABLE\",\"code\":\"SUPPLIER_PRICE_COMPARISON_UNAVAILABLE\","
            + "\"source\":\"NO_QUOTE_SERVICE\",\"toolName\":\"supplier_price_comparison\"}}";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WriteGuardService writeGuard = new WriteGuardService();

    @Test
    void realUnavailableSupplierToolIsFailedAndNeverCachedOnRepeatedCalls() throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture fixture = newDispatch(false, Optional.empty());
        AIIntentConfig intent = intent(tool);

        IntentExecuteResponse first = execute(fixture.dispatch, tool, intent, "supplier first");
        IntentExecuteResponse second = execute(fixture.dispatch, tool, intent, "supplier first");

        assertUnavailable(first);
        assertUnavailable(second);
        assertThat(fixture.records).hasSize(2)
                .allSatisfy(record -> assertThat(record.getExecutionStatus())
                        .isEqualTo(ToolCallRecord.ExecutionStatus.FAILED));
        assertThat(fixture.records).allSatisfy(record -> assertThat(record.getErrorMessage())
                .contains("未接入真实报价"));
        verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
        verify(fixture.validator, never()).validate(anyString(), anyString(), anyMap(), anyString());
        verify(fixture.externalVerifier, never()).verifyToolCall(anyString(), anyString(), anyMap(), anyString());
        verify(fixture.correctionAgent, never()).analyzeAndCorrect(anyString(), anyString(), anyMap(), anyString(), any(), anyInt());
        verify(tool, times(2)).execute(any(), anyMap());
        verifyNoInteractions((SupplierService) ReflectionTestUtils.getField(tool, "supplierService"));
    }

    @Test
    void cachedUnavailableResultRemainsFailedAndRetainsToolAndSourceMetadata() throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture fixture = newDispatch(true, Optional.of(UNAVAILABLE));

        IntentExecuteResponse response = execute(fixture.dispatch, tool, intent(tool), "supplier cached");

        assertUnavailable(response);
        assertThat(response.getMetadata()).containsEntry("cached", true).containsEntry("toolName", tool.getToolName());
        assertThat(response.getResultData()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getResultData();
        assertThat(data.get("code")).isEqualTo("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE");
        assertThat(data.get("source")).isEqualTo("NO_QUOTE_SERVICE");
        assertThat(data.get("toolName")).isEqualTo("supplier_price_comparison");
        assertThat(fixture.records).isEmpty();
        verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void successfulReadRemainsSuccessAndIsCached() throws Exception {
        ScriptedReadTool tool = new ScriptedReadTool();
        DispatchFixture fixture = newDispatch(false, Optional.empty());

        IntentExecuteResponse response = execute(fixture.dispatch, tool, intent(tool), "supplier success");

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        assertThat(fixture.records).singleElement().extracting(ToolCallRecord::getExecutionStatus)
                .isEqualTo(ToolCallRecord.ExecutionStatus.SUCCESS);
        verify(fixture.redundancy).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
    }

    @Test
    void publicExplicitIntentUsesCodeOwnedMetadataAndRealDispatch() {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());

        IntentExecuteResponse response = fixture.orchestrator.executeWithExplicitIntent(
                "F006", request(), 42L, "procurement_manager");

        assertThat(response.getIntentCode()).isEqualTo("SUPPLIER_PRICE_COMPARISON");
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("未接入真实报价");
        assertThat(dispatch.records).singleElement().extracting(ToolCallRecord::getExecutionStatus)
                .isEqualTo(ToolCallRecord.ExecutionStatus.FAILED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "请比较现有供应商的报价；没有物料报价时说明不能比价",
            "请对比供应商的原料价格",
            "供应商价格比较：黄油鸡原料哪家供应商最低价？请注明报价来源；没有真实报价请明确不可比价。"
    })
    void originalManualQueriesReachRealReadToolThroughPublicExecute(String input) {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        var request = IntentExecuteRequest.builder().userInput(input).context(new HashMap<>()).build();
        assertThat(request.getIntentCode()).isNull();
        IntentExecuteResponse response = fixture.orchestrator.execute("F006", request, 42L, "purchaser");
        assertThat(response.getIntentCode()).isEqualTo(SupplierComparisonReadIntent.CODE);
        assertUnavailable(response);
        assertThat(response.getMetadata()).containsEntry("toolName", SupplierComparisonReadIntent.TOOL);
        assertThat(dispatch.records).singleElement().extracting(ToolCallRecord::getExecutionStatus)
                .isEqualTo(ToolCallRecord.ExecutionStatus.FAILED);
    }

    @Test
    void publicExplicitIntentHonorsPermissionMatrixBeforeRealDispatch() throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.denied("procurement:read_write"), Optional.empty());

        IntentExecuteResponse response = fixture.orchestrator.executeWithExplicitIntent(
                "F006", request(), 42L, "warehouse_operator");

        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void publicExplicitIntentHonorsCriticalApprovalBeforeRealDispatch() throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        AIIntentConfig critical = AIIntentConfig.builder().intentCode(SupplierComparisonReadIntent.CODE)
                .intentName("供应商报价比较").intentCategory("CRM").businessType("FACTORY")
                .factoryId("F006").toolName(tool.getToolName()).isActive(true)
                .sensitivityLevel("CRITICAL").requiresApproval(true)
                .requiredPermission("procurement:read_write,finance:read_write").build();
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.of(critical));

        IntentExecuteResponse response = fixture.orchestrator.executeWithExplicitIntent(
                "F006", request(), 42L, "procurement_manager");

        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());

        IntentExecuteRequest serverConfirmed = request();
        serverConfirmed.setContext(writeGuard.withServerConfirmation(new HashMap<>()));
        IntentExecuteResponse approval = fixture.orchestrator.executeWithExplicitIntent(
                "F006", serverConfirmed, 42L, "procurement_manager");
        assertThat(approval.getStatus()).isEqualTo("PENDING_APPROVAL");
        verify(tool, never()).execute(any(), anyMap());
    }

    private IntentExecuteRequest request() {
        return IntentExecuteRequest.builder().intentCode(SupplierComparisonReadIntent.CODE)
                .userInput("供应商报价比较").context(new HashMap<>()).build();
    }

    @Test
    void actualPermissionGateRejectsMissingActorBeforePublicDispatch() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        var fixture = newOrchestrator(tool, dispatch, IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        ReflectionTestUtils.setField(fixture.orchestrator, "intentPermissionGate", new IntentPermissionGate());
        var response = fixture.orchestrator.executeWithExplicitIntent("F006", request(), null, "purchaser");
        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void configuredPlatformLegacyRoleRestrictionStillRejectsBuyer() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        var configured = intent(tool);
        configured.setFactoryId(null);
        configured.setRequiredPermission(null);
        configured.setRequiredRoles("[\"factory_super_admin\"]");
        var fixture = newOrchestrator(tool, dispatch, IntentPermissionGate.PermissionCheck.legacy(), Optional.of(configured));
        ReflectionTestUtils.setField(fixture.orchestrator, "intentPermissionGate", new IntentPermissionGate());
        var recognition = mock(com.cretas.aims.service.AIIntentService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognition);
        var response = fixture.orchestrator.executeWithExplicitIntent("F006", request(), 42L, "purchaser");
        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        verify(recognition).hasPermission(SupplierComparisonReadIntent.CODE, "purchaser");
        verify(tool, never()).execute(any(), anyMap());
        assertThat(dispatch.records).isEmpty();
    }

    @Test
    void configuredPermissionRetainsEstablishedPriorityOverLegacyRoles() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        var configured = intent(tool);
        configured.setRequiredPermission("procurement:read_write");
        configured.setRequiredRoles("[\"factory_super_admin\"]");
        var fixture = newOrchestrator(tool, dispatch, IntentPermissionGate.PermissionCheck.allowed(), Optional.of(configured));
        var guard = mock(com.cretas.aims.ai.tool.ToolRbacGuard.class);
        when(guard.hasAnyPermission(anyMap(), org.mockito.ArgumentMatchers.eq("procurement:read_write"))).thenReturn(true);
        var gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", guard);
        ReflectionTestUtils.setField(fixture.orchestrator, "intentPermissionGate", gate);
        var recognition = mock(com.cretas.aims.service.AIIntentService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognition);
        var response = fixture.orchestrator.executeWithExplicitIntent("F006", request(), 42L, "purchaser");
        assertUnavailable(response);
        verify(recognition, never()).hasPermission(anyString(), anyString());
        verify(tool).execute(any(), anyMap());
    }

    private OrchestratorFixture newOrchestrator(SupplierPriceComparisonTool tool,
                                                DispatchFixture dispatch,
                                                IntentPermissionGate.PermissionCheck permission,
                                                Optional<AIIntentConfig> configured) {
        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class,
                org.mockito.Answers.CALLS_REAL_METHODS);
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
        AIIntentConfigRepository repository = mock(AIIntentConfigRepository.class);
        configured.ifPresent(row -> {
            row.setId("configured-comparison");
            var authority = mock(AIIntentConfigRepository.SupplierComparisonAuthority.class);
            when(authority.getId()).thenReturn(row.getId());
            when(authority.getFactoryId()).thenReturn(row.getFactoryId());
            when(authority.getActive()).thenReturn(row.getIsActive());
            when(authority.getDeleted()).thenReturn(false);
            when(repository.findSupplierComparisonAuthority(SupplierComparisonReadIntent.CODE)).thenReturn(List.of(authority));
            when(repository.findByFactoryIdAndIntentCode(row.getFactoryId(), SupplierComparisonReadIntent.CODE)).thenReturn(configured);
        });
        IntentPermissionGate permissionGate = mock(IntentPermissionGate.class);
        when(permissionGate.check(any(), any(), anyString())).thenReturn(permission);
        ReflectionTestUtils.setField(orchestrator, "toolRegistry", tools);
        ReflectionTestUtils.setField(orchestrator, "toolDispatchService", dispatch.dispatch);
        ReflectionTestUtils.setField(orchestrator, "intentConfigRepository", repository);
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", permissionGate);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", writeGuard);
        com.cretas.aims.config.IntentKnowledgeBase knowledge = new com.cretas.aims.config.IntentKnowledgeBase();
        knowledge.initDefaults();
        ReflectionTestUtils.setField(orchestrator, "knowledgeBase", knowledge);
        com.cretas.aims.service.intent.IntentConfigManagementService configuration =
                mock(com.cretas.aims.service.intent.IntentConfigManagementService.class);
        when(configuration.resolveBusinessDomain("F006")).thenReturn("FACTORY");
        ReflectionTestUtils.setField(orchestrator, "configService", configuration);
        ReflectionTestUtils.setField(orchestrator, "queryPreprocessorService",
                mock(com.cretas.aims.service.impl.QueryPreprocessorServiceImpl.class, org.mockito.Answers.CALLS_REAL_METHODS));
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
        return new OrchestratorFixture(orchestrator);
    }

    private record OrchestratorFixture(IntentExecutionOrchestrator orchestrator) {}

    private SupplierPriceComparisonTool realSupplierTool() {
        SupplierPriceComparisonTool tool = new SupplierPriceComparisonTool();
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(tool, "supplierService", mock(SupplierService.class));
        return tool;
    }

    private IntentExecuteResponse execute(ToolDispatchService dispatch, ToolExecutor tool,
                                          AIIntentConfig intent, String input) {
        return dispatch.executeWithTool(tool, "F006",
                IntentExecuteRequest.builder().userInput(input).context(new HashMap<>()).build(),
                intent, 42L, "procurement_manager", null);
    }

    private void assertUnavailable(IntentExecuteResponse response) {
        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("未接入真实报价");
        assertThat(response.getResultData()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) response.getResultData();
        assertThat(data.get("code")).isEqualTo("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE");
        assertThat(data.get("source")).isEqualTo("NO_QUOTE_SERVICE");
    }

    private AIIntentConfig intent(ToolExecutor tool) {
        return AIIntentConfig.builder().intentCode("SUPPLIER_PRICE_COMPARISON")
                .intentName("供应商价格比较").intentCategory("PROCUREMENT")
                .toolName(tool.getToolName()).build();
    }

    private DispatchFixture newDispatch(boolean redundant, Optional<String> cached) {
        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(redundant);
        when(redundancy.getCachedResult(anyString(), anyString(), anyMap())).thenReturn(cached);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        List<ToolCallRecord> records = new ArrayList<>();
        when(redundancy.recordToolCall(any(ToolCallRecord.class))).thenAnswer(invocation -> {
            ToolCallRecord record = invocation.getArgument(0);
            record.setId(1L + records.size());
            records.add(record);
            return record;
        });

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
        ToolResultValidatorService validator = mock(ToolResultValidatorService.class);
        ExternalVerifierService externalVerifier = mock(ExternalVerifierService.class);
        CorrectionAgentService correctionAgent = mock(CorrectionAgentService.class);
        ToolDispatchService dispatch = new ToolDispatchService(
                registry, objectMapper, null, redundancy,
                mock(SelfCorrectionService.class), correctionAgent, externalVerifier, validator,
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
        return new DispatchFixture(dispatch, redundancy, validator, externalVerifier, correctionAgent, records);
    }

    private record DispatchFixture(ToolDispatchService dispatch, ToolCallRedundancyService redundancy,
                                   ToolResultValidatorService validator, ExternalVerifierService externalVerifier,
                                   CorrectionAgentService correctionAgent, List<ToolCallRecord> records) {}

    private final class ScriptedReadTool implements ToolExecutor {
        @Override public AccessMode getAccessMode() { return AccessMode.READ; }
        @Override public String getToolName() { return "supplier_read_control"; }
        @Override public String getDescription() { return "read control"; }
        @Override public Map<String, Object> getParametersSchema() { return Map.of("type", "object", "properties", Map.of(), "required", List.of()); }
        @Override public String execute(ToolCall call, Map<String, Object> context) { return "{\"success\":true,\"data\":{\"source\":\"REAL_READ\"}}"; }
    }
}
