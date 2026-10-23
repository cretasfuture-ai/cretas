package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.MultiIntentResult;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.SupplierPriceComparisonTool;
import com.cretas.aims.entity.calibration.ToolCallRecord;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.service.SupplierService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Multi-intent workers must carry only the server-captured actor and must clean
 * the pooled thread before it can process a later request.
 */
class SupplierComparisonMultiIntentContractTest {
    private static final String FACTORY = "F006";
    private static final long USER_ID = 42L;

    private ExecutorService executor;
    private MultiIntentExecutionService service;
    private IntentExecutionOrchestrator orchestrator;
    private CopyOnWriteArrayList<AuthenticatedActor> observedActors;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WriteGuardService writeGuard = new WriteGuardService();

    @BeforeEach
    void setUp() {
        executor = Executors.newFixedThreadPool(2);
        AIIntentService aiIntentService = mock(AIIntentService.class);
        orchestrator = mock(IntentExecutionOrchestrator.class);
        SseStreamingService streaming = mock(SseStreamingService.class);
        when(streaming.getSseExecutor()).thenReturn(executor);
        observedActors = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            observedActors.add(AuthenticatedActor.fromCurrentExecution().orElse(null));
            return IntentExecuteResponse.builder()
                    .intentRecognized(true)
                    .status("FAILED")
                    .message("供应商价格比较暂不可用：未接入真实报价")
                    .intentCode(invocation.<IntentExecuteRequest>getArgument(1).getIntentCode())
                    .build();
        }).when(orchestrator).executeWithExplicitIntent(any(), any(), any(), any());
        service = new MultiIntentExecutionService(aiIntentService, orchestrator, streaming);
        ReflectionTestUtils.setField(service, "multiIntentEnabled", true);
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
        executor.shutdownNow();
    }

    @Test
    void completableFutureSupplierPathCarriesTrustedActorAndCleansWorker() throws Exception {
        AuthenticatedActor actor = factoryActor();
        MultiIntentResult result = MultiIntentResult.builder()
                .isMultiIntent(true)
                .executionStrategy(MultiIntentResult.ExecutionStrategy.PARALLEL)
                .overallConfidence(0.99)
                .intents(List.of(
                        intent("SUPPLIER_PRICE_COMPARISON", 0),
                        intent("SUPPLIER_PRICE_COMPARISON", 1)))
                .build();
        when(serviceAi().recognizeMultiIntent("请比较供应商价格", FACTORY)).thenReturn(result);

        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            IntentExecuteResponse response = service.executeMultiIntent(
                    FACTORY, request("请比较供应商价格"), USER_ID, "procurement_manager");
            assertThat(response.getStatus()).isEqualTo("FAILED");
        }

        assertThat(observedActors).hasSize(2).allSatisfy(observed -> assertThat(observed).isEqualTo(actor));
        assertThat(executor.submit(() -> AuthenticatedActor.fromCurrentExecution().isEmpty()).get()).isTrue();
    }

    @Test
    void parallelStreamSupplierPathUsesCapturedActorAndFailsClosedWithoutOne() {
        IntentMatchResult match = IntentMatchResult.builder()
                .isMultiIntent(true)
                .executionStrategy(MultiIntentResult.ExecutionStrategy.PARALLEL)
                .confidence(0.99)
                .additionalIntents(List.of(
                        match("SUPPLIER_PRICE_COMPARISON", 0),
                        match("SUPPLIER_PRICE_COMPARISON", 1)))
                .build();
        AuthenticatedActor actor = factoryActor();
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(actor)) {
            service.executeMultiIntentFromMatchResult(
                    request("请比较供应商价格"), match, FACTORY, USER_ID, "procurement_manager");
        }
        assertThat(observedActors).hasSize(2).allSatisfy(observed -> assertThat(observed).isEqualTo(actor));

        observedActors.clear();
        service.executeMultiIntentFromMatchResult(
                request("请比较供应商价格"), match, FACTORY, USER_ID, "procurement_manager");
        assertThat(observedActors).hasSize(2).allSatisfy(observed -> assertThat(observed).isNull());
    }

    @Test
    void realSupplierToolRunsThroughMultiIntentAndRejectsUnboundActorsBeforeTool() throws Exception {
        SupplierFixture fixture = realSupplierFixture();
        AIIntentService ai = mock(AIIntentService.class);
        MultiIntentExecutionService realMulti = new MultiIntentExecutionService(ai, fixture.orchestrator, streaming(executor));
        ReflectionTestUtils.setField(realMulti, "multiIntentEnabled", true);
        when(ai.recognizeMultiIntent("请比较供应商价格", FACTORY)).thenReturn(parallelSupplierResult());

        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(factoryActor())) {
            IntentExecuteResponse response = realMulti.executeMultiIntent(
                    FACTORY, request("请比较供应商价格"), USER_ID, "procurement_manager");
            assertThat(response.getStatus()).isEqualTo("FAILED");
            assertThat(response.getMessage()).contains("未接入真实报价");
            assertThat(response.getResultData()).isInstanceOf(Map.class);
            Map<?, ?> merged = (Map<?, ?>) response.getResultData();
            assertThat(merged.get("intentResults")).isInstanceOf(List.class);
            assertThat((List<?>) merged.get("intentResults")).hasSize(2).allSatisfy(entry -> {
                Map<?, ?> intentResult = (Map<?, ?>) entry;
                assertThat(intentResult.get("status")).isEqualTo("FAILED");
                assertThat(intentResult.get("data")).isInstanceOf(Map.class);
                Map<?, ?> data = (Map<?, ?>) intentResult.get("data");
                assertThat(data.get("status")).isEqualTo("UNAVAILABLE");
                assertThat(data.get("code")).isEqualTo("SUPPLIER_PRICE_COMPARISON_UNAVAILABLE");
                assertThat(data.get("source")).isEqualTo("NO_QUOTE_SERVICE");
            });
        }
        org.mockito.Mockito.verify(fixture.tool, org.mockito.Mockito.times(2)).execute(any(), anyMap());
        assertThat(fixture.records).hasSize(2);
        org.mockito.Mockito.verifyNoInteractions(fixture.supplierService);

        for (AuthenticatedActor foreign : List.of(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 99L, FACTORY, "procurement_manager"),
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, "OTHER", "procurement_manager"),
                new AuthenticatedActor(AuthenticatedActorType.PLATFORM_ADMIN, USER_ID, FACTORY, "platform_admin"))) {
            fixture.records.clear();
            org.mockito.Mockito.clearInvocations(fixture.tool);
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(foreign)) {
                IntentExecuteResponse denied = realMulti.executeMultiIntent(
                        FACTORY, request("请比较供应商价格"), USER_ID, "procurement_manager");
                assertThat(denied.getStatus()).isEqualTo("PARTIAL_SUCCESS");
                assertDeniedChildren(denied);
            }
            org.mockito.Mockito.verify(fixture.tool, org.mockito.Mockito.never()).execute(any(), anyMap());
            assertThat(fixture.records).isEmpty();
            org.mockito.Mockito.verifyNoInteractions(fixture.supplierService);
        }

        fixture.records.clear();
        org.mockito.Mockito.clearInvocations(fixture.tool);
        IntentExecuteResponse missingActor = realMulti.executeMultiIntent(
                FACTORY, request("请比较供应商价格"), USER_ID, "procurement_manager");
        assertThat(missingActor.getStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertDeniedChildren(missingActor);
        org.mockito.Mockito.verify(fixture.tool, org.mockito.Mockito.never()).execute(any(), anyMap());
        assertThat(fixture.records).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(fixture.supplierService);
    }

    private void assertDeniedChildren(IntentExecuteResponse response) {
        assertThat(response.getResultData()).isInstanceOf(Map.class);
        Map<?, ?> merged = (Map<?, ?>) response.getResultData();
        assertThat(merged.get("intentResults")).isInstanceOf(List.class);
        assertThat((List<?>) merged.get("intentResults")).hasSize(2).allSatisfy(entry ->
                assertThat(((Map<?, ?>) entry).get("status")).isEqualTo("NO_PERMISSION"));
    }

    private MultiIntentResult parallelSupplierResult() {
        return MultiIntentResult.builder().isMultiIntent(true)
                .executionStrategy(MultiIntentResult.ExecutionStrategy.PARALLEL)
                .overallConfidence(0.99)
                .intents(List.of(intent("SUPPLIER_PRICE_COMPARISON", 0), intent("SUPPLIER_PRICE_COMPARISON", 1)))
                .build();
    }

    private SseStreamingService streaming(ExecutorService worker) {
        SseStreamingService streaming = mock(SseStreamingService.class);
        when(streaming.getSseExecutor()).thenReturn(worker);
        return streaming;
    }

    private SupplierFixture realSupplierFixture() {
        SupplierService supplierService = mock(SupplierService.class);
        SupplierPriceComparisonTool tool = org.mockito.Mockito.spy(new SupplierPriceComparisonTool());
        ReflectionTestUtils.setField(tool, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(tool, "supplierService", supplierService);

        ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
        when(redundancy.isRedundant(any(), any(), anyMap())).thenReturn(false);
        when(redundancy.computeParametersHash(anyMap())).thenReturn("hash");
        List<ToolCallRecord> records = new CopyOnWriteArrayList<>();
        when(redundancy.recordToolCall(any(ToolCallRecord.class))).thenAnswer(invocation -> {
            ToolCallRecord record = invocation.getArgument(0);
            records.add(record);
            return record;
        });
        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
        when(registry.isToolEnabledForFactory(any(), any())).thenReturn(true);
        ToolDispatchService dispatch = new ToolDispatchService(registry, objectMapper, null, redundancy,
                mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                mock(ParameterExtractionLearningService.class));
        ReflectionTestUtils.setField(dispatch, "writeGuardService", writeGuard);
        ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
        when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
        when(rbac.isAllowed(any(), anyMap())).thenReturn(true);
        ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);

        IntentExecutionOrchestrator orchestrator = mock(IntentExecutionOrchestrator.class,
                org.mockito.Answers.CALLS_REAL_METHODS);
        AIIntentConfig configured = AIIntentConfig.builder().intentCode("SUPPLIER_PRICE_COMPARISON")
                .intentName("供应商价格比较").intentCategory("PROCUREMENT").businessType("FACTORY")
                .factoryId(FACTORY).toolName(tool.getToolName()).isActive(true)
                .requiredPermission("procurement:read_write").build();
        AIIntentConfigRepository configs = mock(AIIntentConfigRepository.class);
        when(configs.findByFactoryIdAndIntentCode(FACTORY, configured.getIntentCode())).thenReturn(Optional.of(configured));
        ToolRegistry orchestratorRegistry = registry;
        IntentPermissionGate gate = mock(IntentPermissionGate.class);
        when(gate.check(any(), any(), any())).thenAnswer(invocation -> {
            AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
            return actor != null && actor.type() == AuthenticatedActorType.FACTORY_USER
                    && USER_ID == actor.id() && FACTORY.equals(actor.factoryId())
                    ? IntentPermissionGate.PermissionCheck.allowed()
                    : IntentPermissionGate.PermissionCheck.denied("procurement:read_write");
        });
        ReflectionTestUtils.setField(orchestrator, "toolRegistry", orchestratorRegistry);
        ReflectionTestUtils.setField(orchestrator, "toolDispatchService", dispatch);
        ReflectionTestUtils.setField(orchestrator, "intentConfigRepository", configs);
        ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", writeGuard);
        ReflectionTestUtils.setField(orchestrator, "dynamicToolSelectionService", mock(DynamicToolSelectionService.class));
        for (String counter : List.of("branchToolDirect", "branchSkill", "branchDynamic", "branchNoMatch")) {
            ReflectionTestUtils.setField(orchestrator, counter, new java.util.concurrent.atomic.AtomicLong());
        }
        ReflectionTestUtils.setField(orchestrator, "knowledgeBase", new IntentKnowledgeBase());
        ReflectionTestUtils.setField(orchestrator, "configService", mock(IntentConfigManagementService.class));
        ReflectionTestUtils.setField(orchestrator, "queryPreprocessorService",
                mock(QueryPreprocessorServiceImpl.class, org.mockito.Answers.CALLS_REAL_METHODS));
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", mock(BusinessTypeGate.class));
        return new SupplierFixture(orchestrator, tool, supplierService, records);
    }

    private record SupplierFixture(IntentExecutionOrchestrator orchestrator,
                                   SupplierPriceComparisonTool tool,
                                   SupplierService supplierService,
                                   List<ToolCallRecord> records) {}

    private AIIntentService serviceAi() {
        return (AIIntentService) ReflectionTestUtils.getField(service, "aiIntentService");
    }

    private static MultiIntentResult.SingleIntentMatch intent(String code, int order) {
        return MultiIntentResult.SingleIntentMatch.builder()
                .intentCode(code).intentName("供应商价格比较").confidence(0.99)
                .executionOrder(order).extractedParams(Map.of()).build();
    }

    private static IntentMatchResult.IntentMatch match(String code, int order) {
        return IntentMatchResult.IntentMatch.builder()
                .intentCode(code).intentName("供应商价格比较").confidence(0.99)
                .executionOrder(order).extractedParams(Map.of()).build();
    }

    private static IntentExecuteRequest request(String input) {
        return IntentExecuteRequest.builder().userInput(input).context(Map.of()).build();
    }

    private static AuthenticatedActor factoryActor() {
        return new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,
                USER_ID, FACTORY, "procurement_manager");
    }
}
