package com.cretas.aims.service.execution;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.cretas.aims.service.impl.ConversationServiceImpl;
import com.cretas.aims.service.impl.SlotFillingServiceImpl;
import com.cretas.aims.service.intent.impl.IntentConfigManagementServiceImpl;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.execution.IntentExecutionOrchestrator;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
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

        IntentExecuteResponse first;
        IntentExecuteResponse second;
        try (ActorRequest ignored = actor(factoryActor())) {
            first = execute(fixture.dispatch, tool, intent, "supplier first");
            second = execute(fixture.dispatch, tool, intent, "supplier first");
        }

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
        // Generic cache parsing remains supported; supplier results themselves opt out.
        ScriptedReadTool tool = new ScriptedReadTool();
        DispatchFixture fixture = newDispatch(true, Optional.of(UNAVAILABLE));

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = execute(fixture.dispatch, tool, intent(tool), "supplier cached");
        }

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

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

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
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.execute("F006", request, 42L, "purchaser");
        }
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
    void untrustedFreshRecognizerReceivesStatelessIdentityAndSession() {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        com.cretas.aims.service.AIIntentService recognizer = mock(com.cretas.aims.service.AIIntentService.class);
        when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                any(), eq("platform_admin"), any(), any(), any()))
                .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().build());
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);

        try (ActorRequest ignored = actor(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin"))) {
            IntentExecuteResponse response = fixture.orchestrator.execute("F006",
                    IntentExecuteRequest.builder().userInput("未知查询").build(),
                    42L, "platform_admin");
            assertThat(response.getStatus()).isEqualTo("NEED_CLARIFICATION");
            assertThat(response.getIntentRecognized()).isFalse();
        }
        verify(recognizer).recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                isNull(), eq("platform_admin"), isNull(), any(), any());
        assertThat(dispatch.records).isEmpty();
    }

    @Test
    void syncFreshUsesRealSlotFillingOnlyForBoundFactoryActor() throws Exception {
        for (String kind : List.of("missing", "platform", "wrongtype", "different-id", "foreign-factory", "client-spoof")) {
            SupplierPriceComparisonTool supplier = spy(realSupplierTool());
            DispatchFixture dispatch = newDispatch(false, Optional.empty());
            OrchestratorFixture fixture = newOrchestrator(supplier, dispatch,
                    IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
            AIIntentConfig slotIntent = AIIntentConfig.builder().intentCode("OTHER_SLOT")
                    .intentName("other slot").intentCategory("CRM").businessType("FACTORY")
                    .factoryId("F006").toolName("other_slot_tool").sensitivityLevel("LOW")
                    .requiresApproval(false).build();
            ToolExecutor slotTool = mock(ToolExecutor.class);
            when(slotTool.getToolName()).thenReturn("other_slot_tool");
            when(slotTool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
            when(slotTool.getParametersSchema()).thenReturn(Map.of(
                    "type", "object", "properties", Map.of("batchId", Map.of("type", "string")),
                    "required", List.of("batchId")));
            ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(fixture.orchestrator, "toolRegistry");
            when(registry.getExecutor("other_slot_tool")).thenReturn(Optional.of(slotTool));
            ConversationSessionRepository sessions = attachRealSlotFilling(fixture.orchestrator, registry);
            com.cretas.aims.service.AIIntentService recognizer = mock(com.cretas.aims.service.AIIntentService.class);
            when(recognizer.hasPermission("OTHER_SLOT", "procurement_manager")).thenReturn(true);
            when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                    any(), eq("procurement_manager"), any(), any(), any()))
                    .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(slotIntent)
                            .confidence(0.99d).matchMethod(com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.KEYWORD)
                            .questionType(com.cretas.aims.config.IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                            .build());
            ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);
            ToolDispatchService terminalDispatch = mock(ToolDispatchService.class);
            when(terminalDispatch.executeWithTool(any(), anyString(), any(), any(), any(), anyString(), any()))
                    .thenReturn(IntentExecuteResponse.builder().status("UNEXPECTED_DISPATCH").build());
            ReflectionTestUtils.setField(fixture.orchestrator, "toolDispatchService", terminalDispatch);
            AuthenticatedActor current = switch (kind) {
                case "platform" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
                case "wrongtype" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
                case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
                case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
                default -> null;
            };
            try (ActorRequest ignored = actor(current)) {
                if ("wrongtype".equals(kind)) {
                    ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest()
                            .setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                    Map.of("type", "FACTORY_USER", "id", 42L, "factoryId", "F006"));
                }
                IntentExecuteResponse response = fixture.orchestrator.execute("F006",
                        IntentExecuteRequest.builder().userInput("查询缺少批次").context("client-spoof".equals(kind) ? new HashMap<>(Map.of("userId", 42L, "factoryId", "F006", "userRole", "procurement_manager")) : new HashMap<>()).build(),
                        42L, "procurement_manager");
                assertThat(response.getStatus()).isEqualTo("UNEXPECTED_DISPATCH");
            }
            verifyNoInteractions(sessions);
            verify(slotTool, never()).execute(any(), anyMap());
        }

        SupplierPriceComparisonTool supplier = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(supplier, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        AIIntentConfig slotIntent = AIIntentConfig.builder().intentCode("OTHER_SLOT")
                .intentName("other slot").intentCategory("CRM").businessType("FACTORY")
                .factoryId("F006").toolName("other_slot_tool").sensitivityLevel("LOW")
                .requiresApproval(false).build();
        ToolExecutor slotTool = mock(ToolExecutor.class);
        when(slotTool.getToolName()).thenReturn("other_slot_tool");
        when(slotTool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(slotTool.getParametersSchema()).thenReturn(Map.of(
                "type", "object", "properties", Map.of("batchId", Map.of("type", "string")),
                "required", List.of("batchId")));
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(fixture.orchestrator, "toolRegistry");
        when(registry.getExecutor("other_slot_tool")).thenReturn(Optional.of(slotTool));
        ConversationSessionRepository sessions = attachRealSlotFilling(fixture.orchestrator, registry);
        com.cretas.aims.service.AIIntentService recognizer = mock(com.cretas.aims.service.AIIntentService.class);
        when(recognizer.hasPermission("OTHER_SLOT", "procurement_manager")).thenReturn(true);
        when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                any(), eq("procurement_manager"), any(), any(), any()))
                .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(slotIntent)
                        .confidence(0.99d).matchMethod(com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(com.cretas.aims.config.IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build());
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse response = fixture.orchestrator.execute("F006",
                    IntentExecuteRequest.builder().userInput("查询缺少批次").build(),
                    42L, "procurement_manager");
            assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
        }
        org.mockito.ArgumentCaptor<ConversationSession> saved =
                org.mockito.ArgumentCaptor.forClass(ConversationSession.class);
        verify(sessions).save(saved.capture());
        assertThat(saved.getValue().getFactoryId()).isEqualTo("F006");
        assertThat(saved.getValue().getUserId()).isEqualTo(42L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "platform", "wrongtype", "different-id", "foreign-factory", "client-spoof"})
    void recognizerReceivesNoStateIdentityForEveryUntrustedActor(String kind) {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        com.cretas.aims.service.AIIntentService recognizer = mock(com.cretas.aims.service.AIIntentService.class);
        when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                any(), eq("platform_admin"), any(), any(), any()))
                .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().build());
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);
        AuthenticatedActor current = switch (kind) {
            case "platform" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
            case "wrongtype" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
            case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
            case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
            default -> null;
        };
        try (ActorRequest ignored = actor(current)) {
            if ("wrongtype".equals(kind)) {
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest()
                        .setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                Map.of("type", "FACTORY_USER", "id", 42L, "factoryId", "F006"));
            }
            fixture.orchestrator.execute("F006",
                    IntentExecuteRequest.builder().userInput("未知查询").context(
                            "client-spoof".equals(kind)
                                    ? new HashMap<>(Map.of("userId", 42L, "factoryId", "F006"))
                                    : new HashMap<>()).build(),
                    42L, "platform_admin");
        }
        verify(recognizer).recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                isNull(), eq("platform_admin"), isNull(), any(), any());
    }

    @Test
    void recognizerRetainsExactFactoryUserAndSessionIdentity() {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        com.cretas.aims.service.AIIntentService recognizer = mock(com.cretas.aims.service.AIIntentService.class);
        when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                any(), eq("procurement_manager"), any(), any(), any()))
                .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().build());
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);
        try (ActorRequest ignored = actor(factoryActor())) {
            fixture.orchestrator.execute("F006", IntentExecuteRequest.builder().userInput("未知查询").sessionId("supplier-session-1").context(new HashMap<>()).build(), 42L, "procurement_manager");
        }
        verify(recognizer).recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                eq(42L), eq("procurement_manager"), eq("supplier-session-1"), any(), any());
    }

    @Test
    void platformFreshUnrelatedReadKeepsStatelessRecognizerButReachesRealReadTool() {
        ScriptedReadTool read = spy(new ScriptedReadTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(realSupplierTool(), dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        AIIntentConfig unrelated = AIIntentConfig.builder().intentCode("OTHER_READ")
                .intentName("other read").intentCategory("CRM").businessType("FACTORY")
                .sensitivityLevel("LOW").requiresApproval(false).toolName(read.getToolName())
                .factoryId("F006").build();
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(fixture.orchestrator, "toolRegistry");
        when(registry.getExecutor(read.getToolName())).thenReturn(Optional.of(read));
        AIIntentService recognizer = mock(AIIntentService.class);
        when(recognizer.hasPermission("OTHER_READ", "platform_admin")).thenReturn(true);
        when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                any(), eq("platform_admin"), any(), any(), any()))
                .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(unrelated)
                        .confidence(0.99d)
                        .matchMethod(com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(com.cretas.aims.config.IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                        .build());
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);

        com.cretas.aims.service.ConversationMemoryService observedMemory = mock(com.cretas.aims.service.ConversationMemoryService.class);
        com.cretas.aims.service.ConversationService observedSessions = mock(com.cretas.aims.service.ConversationService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationMemoryService", observedMemory);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationService", observedSessions);
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin"))) {
            response = fixture.orchestrator.execute("F006",
                    IntentExecuteRequest.builder().userInput("读取公开工厂资料").build(),
                    42L, "platform_admin");
        }

        assertThat(response.getStatus()).isEqualTo("SUCCESS");
        verify(recognizer).recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                isNull(), eq("platform_admin"), isNull(), any(), any());
        verify(read).execute(any(), org.mockito.ArgumentMatchers.argThat(context ->
                Long.valueOf(42L).equals(((Number) context.get("userId")).longValue()) && "F006".equals(context.get("factoryId"))));
        verifyNoInteractions(observedMemory, observedSessions);
    }

    @Test
    void nonSupplierNamedSessionRequiresBoundActorOnBothPublicEntrances() throws Exception {
        ScriptedReadTool read = spy(new ScriptedReadTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = newOrchestrator(realSupplierTool(), dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());
        AIIntentConfig unrelated = AIIntentConfig.builder().intentCode("OTHER_READ")
                .intentName("other read").intentCategory("CRM").businessType("FACTORY")
                .sensitivityLevel("LOW").requiresApproval(false).toolName(read.getToolName())
                .factoryId("F006").build();
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(fixture.orchestrator, "toolRegistry");
        when(registry.getExecutor(read.getToolName())).thenReturn(Optional.of(read));
        AIIntentService recognizer = mock(AIIntentService.class);
        when(recognizer.getIntentByCode(anyString(), eq("OTHER_READ"))).thenReturn(Optional.of(unrelated));
        when(recognizer.hasPermission("OTHER_READ", "procurement_manager")).thenReturn(true);
        when(recognizer.recognizeIntentWithConfidence(anyString(), eq("F006"), eq(3),
                any(), eq("procurement_manager"), any(), any(), any()))
                .thenReturn(com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(unrelated)
                        .confidence(0.99d)
                        .matchMethod(com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.KEYWORD)
                        .questionType(com.cretas.aims.config.IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                        .build());
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);
        IntentExecuteRequest request = IntentExecuteRequest.builder().intentCode("OTHER_READ")
                .userInput("读取会话资料").sessionId("OTHER-SESSION").build();

        com.cretas.aims.service.ConversationMemoryService memory = mock(com.cretas.aims.service.ConversationMemoryService.class);
        com.cretas.aims.service.ConversationService sessions = mock(com.cretas.aims.service.ConversationService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationMemoryService", memory);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationService", sessions);
        for (String kind : List.of("missing", "platform", "wrongtype", "different-id", "foreign-factory", "client-spoof")) {
            AuthenticatedActor current = switch (kind) {
                case "platform", "wrongtype" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
                case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
                case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
                default -> null;
            };
            for (String mode : List.of("explicit-api", "explicit-request", "natural", "preview")) {
                IntentExecuteRequest deniedRequest = IntentExecuteRequest.builder()
                        .intentCode("natural".equals(mode) ? null : "OTHER_READ")
                        .userInput("读取会话资料").sessionId("OTHER-SESSION")
                        .previewOnly("preview".equals(mode))
                        .context("client-spoof".equals(kind) ? new HashMap<>(Map.of(
                                "userId",42L,"factoryId","F006","userRole","procurement_manager")) : new HashMap<>())
                        .build();
                try (ActorRequest ignored = actor(current)) {
                    if ("wrongtype".equals(kind)) ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                            .getRequest().setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                    Map.of("type", "FACTORY_USER", "id", 42L, "factoryId", "F006"));
                    IntentExecuteResponse denied = "explicit-api".equals(mode)
                            ? fixture.orchestrator.executeWithExplicitIntent("F006", deniedRequest, 42L, "procurement_manager")
                            : fixture.orchestrator.execute("F006", deniedRequest, 42L, "procurement_manager");
                    assertThat(denied.getStatus()).as("%s / %s", kind, mode).isEqualTo("NO_PERMISSION");
                }
                verifyNoInteractions(memory, sessions, recognizer);
                verify(read, never()).execute(any(), anyMap());
            }
        }
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationMemoryService", null);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationService", null);

        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteResponse allowed = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request, 42L, "procurement_manager");
            assertThat(allowed.getStatus()).isEqualTo("SUCCESS");
        }
        verify(read).execute(any(), anyMap());
    }

    @Test
    void previewMissingParametersCannotOpenSessionForUntrustedIdentity() throws Exception {
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getToolName()).thenReturn("preview_read");
        when(tool.isEnabled()).thenReturn(true);
        when(tool.supportsPreview()).thenReturn(true);
        when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.WRITE);
        when(tool.getParametersSchema()).thenReturn(Map.of(
                "type", "object", "properties", Map.of("batchId", Map.of("type", "string")),
                "required", List.of("batchId")));
        when(tool.preview(any(), anyMap())).thenReturn(
                "{\"success\":false,\"status\":\"NEED_MORE_INFO\","
                        + "\"needMoreInfo\":true,\"missingParameters\":[\"batchId\"]}");
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("PREVIEW_READ")
                .intentName("preview").toolName(tool.getToolName()).sensitivityLevel("HIGH").build();
        com.cretas.aims.service.ConversationService conversation = mock(
                com.cretas.aims.service.ConversationService.class);
        ReflectionTestUtils.setField(dispatch.dispatch, "conversationService", conversation);

        for (String kind : List.of("missing", "platform", "wrongtype", "different-id", "foreign-factory", "client-spoof")) {
            AuthenticatedActor current = switch (kind) {
                case "platform", "wrongtype" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
                case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
                case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
                default -> null;
            };
            try (ActorRequest ignored = actor(current)) {
                if ("wrongtype".equals(kind)) ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes())
                        .getRequest().setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                                Map.of("type", "FACTORY_USER", "id", 42L, "factoryId", "F006"));
                IntentExecuteResponse response = dispatch.dispatch.executeToolPreview(tool, "F006",
                        IntentExecuteRequest.builder().userInput("查询批次").context("client-spoof".equals(kind)
                                ? new HashMap<>(Map.of("userId",42L,"factoryId","F006","userRole","procurement_manager")) : new HashMap<>()).build(), intent,
                        42L, "platform_admin");
                assertThat(response.getStatus()).isEqualTo("NEED_MORE_INFO");
            }
            verifyNoInteractions(conversation);
        }

        when(conversation.startParameterCollection(anyString(), anyLong(), anyString(), anyString(),
                any(), any(), any())).thenReturn(
                com.cretas.aims.service.ConversationService.ConversationResponse.builder()
                        .sessionId("owned-preview-session").build());
        try (ActorRequest ignored = actor(factoryActor())) {
            dispatch.dispatch.executeToolPreview(tool, "F006",
                    IntentExecuteRequest.builder().userInput("查询批次").build(), intent,
                    42L, "procurement_manager");
        }
        verify(conversation).startParameterCollection(anyString(), eq(42L), anyString(), anyString(),
                any(), any(), any());
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

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

        assertThat(response.getStatus()).isEqualTo("WRITE_CONFIRM_REQUIRED");
        assertThat(response.getRequiresApproval()).isTrue();
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());

        IntentExecuteResponse approval;
        try (ActorRequest ignored = actor(factoryActor())) {
            IntentExecuteRequest serverConfirmed = request();
            serverConfirmed.setContext(writeGuard.withServerConfirmation(new HashMap<>()));
            approval = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", serverConfirmed, 42L, "procurement_manager");
        }
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
        AIIntentConfigRepository roleRepository = mock(AIIntentConfigRepository.class);
        when(roleRepository.findByIntentCodeAndIsActiveTrueAndDeletedAtIsNull(
                SupplierComparisonReadIntent.CODE)).thenReturn(Optional.of(configured));
        IntentConfigManagementServiceImpl roleConfigService =
                new IntentConfigManagementServiceImpl(roleRepository);
        AIIntentServiceImpl recognition = spy(new AIIntentServiceImpl(null, roleConfigService, null));
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognition);
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent("F006", request(), 42L, "purchaser");
        }
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
        configured.setFactoryId("F006");
        configured.setRequiredPermission("procurement:read_write");
        configured.setRequiredRoles("[\"factory_super_admin\"]");
        var fixture = securedOrchestrator(tool, dispatch, Optional.of(configured), true);
        var recognition = mock(com.cretas.aims.service.AIIntentService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognition);
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent("F006", request(), 42L, "purchaser");
        }
        assertUnavailable(response);
        verify(recognition, never()).hasPermission(anyString(), anyString());
        verify(tool).execute(any(), anyMap());
        assertThat(dispatch.records).singleElement().extracting(ToolCallRecord::getExecutionStatus)
                .isEqualTo(ToolCallRecord.ExecutionStatus.FAILED);
    }

    @Test
    void configuredInactiveSupplierIntentCannotFallThroughToNativeDefault() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        var configured = intent(tool);
        configured.setFactoryId("F006");
        configured.setIsActive(false);
        var fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.of(configured));

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("未找到意图配置");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void configuredForeignTenantSupplierIntentCannotBecomePlatformDefault() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        var configured = intent(tool);
        configured.setFactoryId("F-OTHER");
        var fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.of(configured));

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

        assertThat(response.getStatus()).isEqualTo("FAILED");
        assertThat(response.getMessage()).contains("未找到意图配置");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void actualPermissionGateDenialCannotReachSupplierTool() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        var configured = intent(tool);
        configured.setFactoryId("F006");
        configured.setRequiredPermission("procurement:read_write");
        var fixture = securedOrchestrator(tool, dispatch, Optional.of(configured), false);

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(response.getRequiredPermission()).isEqualTo("procurement:read_write");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void disabledSupplierToolCannotReachRealToolExecution() throws Exception {
        var tool = spy(realSupplierTool());
        var dispatch = newDispatch(false, Optional.empty());
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(dispatch.dispatch, "toolRegistry");
        when(registry.isToolEnabledForFactory("F006", tool.getToolName())).thenReturn(false);
        var fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), Optional.empty());

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

        assertThat(response.getStatus()).isEqualTo("TOOL_DISABLED");
        assertThat(response.getMessage()).contains("当前在工厂 F006 不可用");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    @Test
    void supplierExplicitIntentRejectsPlatformActorWithSameNumericId() throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = securedOrchestrator(tool, dispatch);

        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(new AuthenticatedActor(
                AuthenticatedActorType.PLATFORM_ADMIN, 42L, null, "platform_admin"))) {
            response = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", request(), 42L, "procurement_manager");
        }

        assertActorDenied(response, dispatch, tool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"platform-collision", "platform-scoped-collision", "foreign-factory", "different-id", "missing-actor", "client-spoof"})
    void supplierPublicPathsRejectUntrustedOrMismatchedFactoryActor(String mutation) throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = securedOrchestrator(tool, dispatch);
        AuthenticatedActor currentActor = switch (mutation) {
            case "platform-collision" -> new AuthenticatedActor(
                    AuthenticatedActorType.PLATFORM_ADMIN, 42L, null, "platform_admin");
            case "platform-scoped-collision" -> new AuthenticatedActor(
                    AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
            case "foreign-factory" -> new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
            case "different-id" -> new AuthenticatedActor(
                    AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
            default -> null;
        };

        IntentExecuteRequest explicitRequest = request();
        if ("client-spoof".equals(mutation) || "missing-actor".equals(mutation)) {
            explicitRequest.setContext(new HashMap<>(Map.of(
                    "userId", 42L, "userRole", "factory_super_admin", "factoryId", "F006")));
        }
        IntentExecuteResponse explicit;
        try (ActorRequest ignored = actor(currentActor)) {
            explicit = fixture.orchestrator.executeWithExplicitIntent(
                    "F006", explicitRequest, 42L, "procurement_manager");
        }
        assertActorDenied(explicit, dispatch, tool);

        dispatch = newDispatch(false, Optional.empty());
        fixture = securedOrchestrator(tool, dispatch);
        IntentExecuteRequest natural = IntentExecuteRequest.builder()
                .userInput("请对比供应商的原料价格")
                .context(new HashMap<>(Map.of(
                        "userId", 42L, "userRole", "factory_super_admin", "factoryId", "F006")))
                .build();
        IntentExecuteResponse naturalResponse;
        try (ActorRequest ignored = actor(currentActor)) {
            naturalResponse = fixture.orchestrator.execute("F006", natural, 42L, "procurement_manager");
        }
        assertActorDenied(naturalResponse, dispatch, tool);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-actor", "platform-collision", "platform-scoped-collision",
            "foreign-factory", "different-id", "wrongtype", "client-spoof"})
    void supplierCacheCannotBypassTrustedActor(String kind) throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture fixture = newDispatch(true, Optional.of(
                "{\"success\":true,\"data\":{\"secret\":\"LEGACY_SUPPLIER_PRICE\"}}"));
        AuthenticatedActor current = switch (kind) {
            case "platform-collision" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, null, "platform_admin");
            case "platform-scoped-collision" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
            case "foreign-factory" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
            case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
            default -> null;
        };
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("supplier cached")
                .context(new HashMap<>(Map.of("userId", 42L, "factoryId", "F006", "userRole", "factory_super_admin"))).build();
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(current)) {
            if ("wrongtype".equals(kind)) {
                ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest()
                        .setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, Map.of("type", "FACTORY_USER", "id", 42L, "factoryId", "F006"));
            }
            response = fixture.dispatch.executeWithTool(tool, "F006", request, intent(tool), 42L, "procurement_manager", null);
        }
        assertThat(response.getStatus()).isEqualTo("FORBIDDEN");
        assertThat(response.getResultData().toString()).doesNotContain("LEGACY_SUPPLIER_PRICE", "NO_QUOTE_SERVICE");
        assertThat(response.getMetadata() == null || !Boolean.TRUE.equals(response.getMetadata().get("cached"))).isTrue();
        verify(fixture.redundancy, never()).isRedundant(anyString(), anyString(), anyMap());
        verify(fixture.redundancy, never()).getCachedResult(anyString(), anyString(), anyMap());
        verify(tool, never()).execute(any(), anyMap());
        verifyNoInteractions((SupplierService) ReflectionTestUtils.getField(tool, "supplierService"));
    }

    @Test
    void supplierCodeCannotDispatchDifferentReadToolEvenWithValidActor() throws Exception {
        ScriptedReadTool other = spy(new ScriptedReadTool());
        DispatchFixture fixture = newDispatch(true, Optional.of("{\"success\":true,\"data\":{\"secret\":\"WRONG_TOOL\"}}"));
        AIIntentConfig spoof = intent(other);
        spoof.setIntentCode(SupplierComparisonReadIntent.CODE);
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = execute(fixture.dispatch, other, spoof, "misbound supplier");
        }
        assertThat(response.getStatus()).isEqualTo("FORBIDDEN");
        verify(other, never()).execute(any(), anyMap());
        verify(fixture.redundancy, never()).getCachedResult(anyString(), anyString(), anyMap());
    }

    @Test
    void validSupplierActorIgnoresLegacySuccessCacheAndCallsActualTool() throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture fixture = newDispatch(true, Optional.of(
                "{\"success\":true,\"data\":{\"secret\":\"LEGACY_SUPPLIER_PRICE\"}}"));
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = execute(fixture.dispatch, tool, intent(tool), "warm supplier cache");
        }
        assertUnavailable(response);
        assertThat(response.getResultData().toString()).doesNotContain("LEGACY_SUPPLIER_PRICE");
        verify(tool).execute(any(), anyMap());
        verify(fixture.redundancy, never()).isRedundant(anyString(), anyString(), anyMap());
        verify(fixture.redundancy, never()).getCachedResult(anyString(), anyString(), anyMap());
        verify(fixture.redundancy, never()).cacheResult(anyString(), anyString(), anyMap(), anyString(), any());
        verifyNoInteractions((SupplierService) ReflectionTestUtils.getField(tool, "supplierService"));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.NullAndEmptySource
    @ValueSource(strings = {"OTHER_READ"})
    void supplierToolCannotUseAnUnrelatedOrMissingIntentCode(String code) throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture fixture = newDispatch(true, Optional.of(UNAVAILABLE));
        AIIntentConfig wrong = intent(tool);
        wrong.setIntentCode(code);
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = execute(fixture.dispatch, tool, wrong, "supplier mismatched code");
        }
        assertThat(response.getStatus()).isEqualTo("FORBIDDEN");
        verify(tool, never()).execute(any(), anyMap());
        verify(fixture.redundancy, never()).getCachedResult(anyString(), anyString(), anyMap());
        verifyNoInteractions((SupplierService) ReflectionTestUtils.getField(tool, "supplierService"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "inactive", "foreign", "permission-denied", "legacy-role-denied"})
    void recognizerSupplierBestMatchRejoinsCanonicalAuthority(String state) throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        AIIntentConfig configured = intent(tool);
        configured.setFactoryId("foreign".equals(state) ? "OTHER" : "F006");
        configured.setIsActive(!"inactive".equals(state));
        configured.setRequiredPermission("procurement:read_write");
        if ("legacy-role-denied".equals(state)) {
            configured.setRequiredPermission(null);
            configured.setRequiredRoles("platform_admin");
        }
        OrchestratorFixture fixture = securedOrchestrator(tool, dispatch, Optional.of(configured), !"permission-denied".equals(state));
        com.cretas.aims.service.AIIntentService recognizer = mock(com.cretas.aims.service.AIIntentService.class);
        AIIntentConfig untrusted = intent(tool);
        untrusted.setToolName("unrelated_read_tool");
        untrusted.setRequiredPermission("procurement:read_write");
        var match = com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(untrusted).confidence(1.0)
                .matchMethod(com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.EXACT)
                .requiresConfirmation(false).questionType(com.cretas.aims.config.IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND).build();
        when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any(), any(), any()))
                .thenReturn(match);
        when(recognizer.hasPermission(anyString(), anyString())).thenReturn(!"legacy-role-denied".equals(state));
        ReflectionTestUtils.setField(fixture.orchestrator, "aiIntentService", recognizer);
        ToolRegistry registry = (ToolRegistry) ReflectionTestUtils.getField(fixture.orchestrator, "toolRegistry");
        ToolExecutor other = spy(new ScriptedReadTool());
        when(registry.getExecutor("unrelated_read_tool")).thenReturn(Optional.of(other));
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(factoryActor())) {
            response = fixture.orchestrator.execute("F006", IntentExecuteRequest.builder()
                    .userInput("核对本次往来资料凭据ZQ781").context(new HashMap<>()).build(), 42L, "procurement_manager");
        }
        verify(recognizer).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), anyString(), any(), any(), any());
        verify(other, never()).execute(any(), anyMap());
        if ("valid".equals(state)) {
            assertUnavailable(response);
            verify(tool).execute(any(), anyMap());
        } else {
            assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
            verify(tool, never()).execute(any(), anyMap());
        }
        verifyNoInteractions((SupplierService) ReflectionTestUtils.getField(tool, "supplierService"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing", "platform", "platform-scoped", "foreign", "different-id", "spoof", "inactive", "permission-denied"})
    void supplierContinuationIsAdmittedBeforeAnySessionMutation(String kind) throws Exception {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        AIIntentConfig configured = intent(tool);
        configured.setFactoryId("F006");
        configured.setIsActive(!"inactive".equals(kind));
        configured.setRequiredPermission("procurement:read_write");
        OrchestratorFixture fixture = securedOrchestrator(tool, dispatch, Optional.of(configured), !"permission-denied".equals(kind));
        com.cretas.aims.service.ConversationService conversation = mock(com.cretas.aims.service.ConversationService.class);
        var session = com.cretas.aims.entity.conversation.ConversationSession.createForParameterCollection(
                "F006", 42L, SupplierComparisonReadIntent.CODE, List.of(), "补充资料");
        when(conversation.getSession("F006", 42L, "SUPPLIER-SESSION")).thenReturn(Optional.of(session));
        when(conversation.continueConversation(anyString(), any(), anyString(), anyString())).thenReturn(
                com.cretas.aims.service.ConversationService.ConversationResponse.builder()
                        .completed(true).intentCode(SupplierComparisonReadIntent.CODE)
                        .status(com.cretas.aims.entity.conversation.ConversationSession.SessionStatus.COMPLETED).build());
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationService", conversation);
        var memory = mock(com.cretas.aims.service.ConversationMemoryService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationMemoryService", memory);
        AuthenticatedActor current = switch (kind) {
            case "missing", "spoof" -> null;
            case "platform" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, null, "platform_admin");
            case "platform-scoped" -> new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, "F006", "platform_admin");
            case "foreign" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 42L, "OTHER", "procurement_manager");
            case "different-id" -> new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, "F006", "procurement_manager");
            default -> factoryActor();
        };
        IntentExecuteResponse response;
        try (ActorRequest ignored = actor(current)) {
            response = fixture.orchestrator.execute("F006", IntentExecuteRequest.builder()
                    .userInput("补充资料ZQ781").sessionId("SUPPLIER-SESSION")
                    .context("spoof".equals(kind) ? new HashMap<>(Map.of("userId", 42L, "factoryId", "F006", "role", "platform_admin")) : new HashMap<>())
                    .build(), 42L, "procurement_manager");
        }
        if ("valid".equals(kind)) {
            assertUnavailable(response);
            verify(conversation).continueConversation(anyString(), any(), anyString(), anyString());
            verify(conversation).endConversation(anyString(), any(), anyString(), anyString());
        } else {
            assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
            verify(conversation, never()).continueConversation(anyString(), any(), anyString(), anyString());
            verify(conversation, never()).endConversation(anyString(), any(), anyString(), anyString());
            verify(conversation, never()).cancelConversation(anyString(), any(), anyString());
            if (!List.of("inactive", "permission-denied").contains(kind)) verifyNoInteractions(conversation, memory);
            verify(tool, never()).execute(any(), anyMap());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"owned", "null", "foreign-user", "foreign-tenant", "wrong-session"})
    void explicitMemoryPersistenceRequiresSuccessfulExactOwnershipReadback(String kind) {
        SupplierPriceComparisonTool tool = spy(realSupplierTool());
        DispatchFixture dispatch = newDispatch(false, Optional.empty());
        OrchestratorFixture fixture = securedOrchestrator(tool, dispatch);
        var memory = mock(com.cretas.aims.service.ConversationMemoryService.class);
        ReflectionTestUtils.setField(fixture.orchestrator, "conversationMemoryService", memory);
        var owner = "null".equals(kind) ? null : com.cretas.aims.dto.conversation.ConversationContext.builder()
                .factoryId("foreign-tenant".equals(kind) ? "OTHER" : "F006")
                .userId("foreign-user".equals(kind) ? 99L : 42L)
                .sessionId("wrong-session".equals(kind) ? "OTHER-SESSION" : "MEMORY-SESSION").build();
        when(memory.getOrCreateContext("F006",42L,"MEMORY-SESSION")).thenReturn(owner);
        var request = IntentExecuteRequest.builder().userInput("供应商报价").sessionId("MEMORY-SESSION").build();
        try (ActorRequest ignored = actor(factoryActor())) {
            fixture.orchestrator.persistConversationMemoryForExplicitIntent("F006",request,
                    IntentExecuteResponse.builder().message("未接入真实报价").build(),intent(tool),42L);
        }
        verify(memory).getOrCreateContext("F006",42L,"MEMORY-SESSION");
        if ("owned".equals(kind)) {
            verify(memory, times(2)).addMessage(eq("MEMORY-SESSION"),any());
            verify(memory).updateLastIntent("MEMORY-SESSION",SupplierComparisonReadIntent.CODE);
        } else verifyNoMoreInteractions(memory);
    }

    private void assertActorDenied(IntentExecuteResponse response, DispatchFixture dispatch,
                                   SupplierPriceComparisonTool tool) throws Exception {
        assertThat(response.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(response.getMessage()).contains("没有权限");
        assertThat(dispatch.records).isEmpty();
        verify(tool, never()).execute(any(), anyMap());
    }

    private OrchestratorFixture securedOrchestrator(SupplierPriceComparisonTool tool,
                                                     DispatchFixture dispatch) {
        return securedOrchestrator(tool, dispatch, Optional.empty(), true);
    }

    private OrchestratorFixture securedOrchestrator(SupplierPriceComparisonTool tool,
                                                     DispatchFixture dispatch,
                                                     Optional<AIIntentConfig> configured,
                                                     boolean grantSupplierPermission) {
        SecurityFixture security = securityFixture(grantSupplierPermission);
        OrchestratorFixture fixture = newOrchestrator(tool, dispatch,
                IntentPermissionGate.PermissionCheck.allowed(), configured);
        bindSecurity(fixture, dispatch, security);
        return fixture;
    }

    private void bindSecurity(OrchestratorFixture fixture, DispatchFixture dispatch,
                              SecurityFixture security) {
        IntentPermissionGate gate = new IntentPermissionGate();
        ReflectionTestUtils.setField(gate, "rbacGuard", security.guard);
        ReflectionTestUtils.setField(fixture.orchestrator, "intentPermissionGate", gate);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", security.guard);
        ReflectionTestUtils.setField(dispatch.dispatch, "toolRbacEnforcer", enforcer);
    }

    private SecurityFixture securityFixture(boolean grantSupplierPermission) {
        UserRepository users = mock(UserRepository.class);
        PermissionService permissions = mock(PermissionService.class);
        User user = new User();
        user.setId(42L);
        user.setFactoryId("F006");
        user.setRoleCode("procurement_manager");
        user.setIsActive(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(permissions.hasAnyPermission(any(User.class), any(String[].class))).thenAnswer(invocation -> {
            String[] requested = (String[]) invocation.getRawArguments()[1];
            return grantSupplierPermission && java.util.Arrays.stream(requested)
                    .anyMatch(Set.of("procurement:read_write", "finance:read_write")::contains);
        });
        com.cretas.aims.ai.tool.ToolRbacGuard guard = new com.cretas.aims.ai.tool.ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        return new SecurityFixture(guard);
    }

    private AuthenticatedActor factoryActor() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,
                42L, "F006", "procurement_manager");
    }

    private ActorRequest actor(AuthenticatedActor actor) {
        RequestAttributes previous = RequestContextHolder.getRequestAttributes();
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (actor != null) {
            request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, actor);
        }
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return new ActorRequest(previous);
    }

    private record SecurityFixture(com.cretas.aims.ai.tool.ToolRbacGuard guard) {}

    private static final class ActorRequest implements AutoCloseable {
        private final RequestAttributes previous;
        private ActorRequest(RequestAttributes previous) { this.previous = previous; }
        @Override public void close() {
            if (previous == null) RequestContextHolder.resetRequestAttributes();
            else RequestContextHolder.setRequestAttributes(previous);
        }
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
        ReflectionTestUtils.setField(orchestrator, "dynamicToolSelectionService", mock(DynamicToolSelectionService.class));
        for (String counter : List.of("branchToolDirect", "branchSkill", "branchDynamic", "branchNoMatch")) {
            ReflectionTestUtils.setField(orchestrator, counter, new java.util.concurrent.atomic.AtomicLong());
        }
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
        return AIIntentConfig.builder().intentCode(tool instanceof SupplierPriceComparisonTool
                        ? SupplierComparisonReadIntent.CODE : "READ_CONTROL")
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

    private ConversationSessionRepository attachRealSlotFilling(IntentExecutionOrchestrator orchestrator,
                                                                 ToolRegistry registry) {
        ConversationSessionRepository sessions = mock(ConversationSessionRepository.class);
        Map<String, com.cretas.aims.entity.conversation.ConversationSession> stored = new HashMap<>();
        when(sessions.save(any(com.cretas.aims.entity.conversation.ConversationSession.class)))
                .thenAnswer(invocation -> {
                    var session = invocation.getArgument(0, com.cretas.aims.entity.conversation.ConversationSession.class);
                    if (session.getSessionId() == null) session.setSessionId(UUID.randomUUID().toString());
                    if (session.getLastActiveAt() == null) session.setLastActiveAt(LocalDateTime.now());
                    stored.put(session.getSessionId(), session);
                    return session;
                });
        when(sessions.findBySessionIdAndFactoryIdAndUserId(anyString(), anyString(), anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(stored.get(invocation.getArgument(0)))
                        .filter(session -> invocation.getArgument(1, String.class).equals(session.getFactoryId()))
                        .filter(session -> invocation.getArgument(2, Long.class).equals(session.getUserId())));
        ConversationServiceImpl conversations = new ConversationServiceImpl(
                sessions, mock(AIIntentConfigRepository.class), null, mock(com.cretas.aims.ai.client.DashScopeClient.class));
        ReflectionTestUtils.setField(conversations, "maxRounds", 5);
        ReflectionTestUtils.setField(conversations, "timeoutMinutes", 10);
        IntentSlotConfiguration slotConfiguration = new IntentSlotConfiguration();
        slotConfiguration.init();
        ReflectionTestUtils.setField(orchestrator, "slotFillingService", new SlotFillingServiceImpl(
                slotConfiguration, registry, conversations, mock(ParameterExtractionLearningService.class)));
        return sessions;
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
