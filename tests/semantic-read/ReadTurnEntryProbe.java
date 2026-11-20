package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.crm.OrderListTool;
import com.cretas.aims.ai.tool.impl.crm.OrderQueryTool;
import com.cretas.aims.ai.tool.impl.material.MaterialBatchQueryTool;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.TimeNormalizationRules;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.material.MaterialBatchListFilter;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AgentOrchestrator;
import com.cretas.aims.service.AgenticRAGRouterService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ComplexityRouter;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.IntentSemanticsParser;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.ResultValidatorService;
import com.cretas.aims.service.RuleEngineService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.calibration.CorrectionAgentService;
import com.cretas.aims.service.calibration.ExternalVerifierService;
import com.cretas.aims.service.calibration.SelfCorrectionService;
import com.cretas.aims.service.calibration.ToolCallRedundancyService;
import com.cretas.aims.service.calibration.ToolResultValidatorService;
import com.cretas.aims.service.impl.ConversationMemoryServiceImpl;
import com.cretas.aims.service.impl.QueryPreprocessorServiceImpl;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.inventory.SalesService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockito.invocation.Invocation;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Evaluation-only replay of main's real read entry (INTENT-DISTILL line E). Not a test; not run by CI.
 *
 * <p>Same wiring as {@code ReadTurnParameterEntryContractTest.Harness} (ordinary entry): the real
 * {@link IntentExecutionOrchestrator}, {@link ToolDispatchService}, {@link QueryPreprocessorServiceImpl}
 * with {@link TimeNormalizationRules}, conversation memory and the real order / batch Tools. Only the
 * data services are mocks; every call they receive is captured, so the output is exactly the parameters
 * main executes with. The decision layer's choice is fed in per turn (recognizer stub); a turn the decision
 * layer did not execute goes through main's no-match branch, so memory sees a non-read reply as in prod.
 *
 * <p>stdin: JSON lines {case, sentence, intent, outcome}; one harness per case, in order.
 * stdout: JSON lines {case, status, calls:[{method, args}]}.
 */
public final class ReadTurnEntryProbe {

    private static final String FACTORY = "F006";
    private static final long USER = 1309L;
    private static final String ROLE = "factory_super_admin";
    private static final String SESSION = "e-probe-session";
    private static final Map<String, String> TOOLS = Map.of(
            "ORDER_LIST", "order_list", "ORDER_STATUS", "order_query",
            "MATERIAL_BATCH_QUERY", "material_batch_query", "PAGINATION_NEXT", "pagination_next");

    private ReadTurnEntryProbe() {
    }

    public static void main(String[] args) throws Exception {
        ObjectMapper om = new ObjectMapper().findAndRegisterModules();
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        Map<String, Harness> harnesses = new HashMap<>();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> req = om.readValue(line, Map.class);
            String caseId = String.valueOf(req.get("case"));
            Harness h = harnesses.computeIfAbsent(caseId, k -> {
                try {
                    return new Harness();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            String sentence = (String) req.get("sentence");
            String intent = (String) req.get("intent");
            if ("EXECUTE".equals(req.get("outcome")) && intent != null && TOOLS.containsKey(intent)) {
                h.byUtterance.put(sentence, h.intents.get(intent));
            } else {
                h.byUtterance.remove(sentence);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("case", caseId);
            try {
                IntentExecuteResponse response = h.say(sentence);
                out.put("status", response.getStatus());
            } catch (Exception failure) {
                out.put("status", "EXCEPTION:" + failure.getClass().getSimpleName());
            }
            out.put("calls", h.newCalls());
            System.out.println(om.writeValueAsString(out));
        }
    }

    static Object plain(Object v) {
        if (v == null || v instanceof Number || v instanceof String || v instanceof Boolean) return v;
        if (v instanceof Enum<?> e) return e.name();
        if (v instanceof LocalDate d) return d.toString();
        if (v instanceof PageRequest p) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("page", p.getPage());
            m.put("size", p.getSize());
            m.put("status", p.getStatus());
            m.put("keyword", p.getKeyword());
            return m;
        }
        if (v instanceof MaterialBatchListFilter f) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialTypeId", f.materialTypeId());
            m.put("receiptFrom", f.receiptFrom() == null ? null : f.receiptFrom().toString());
            m.put("receiptTo", f.receiptTo() == null ? null : f.receiptTo().toString());
            return m;
        }
        return String.valueOf(v);
    }

    private static final class Harness {
        private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        private final AIIntentService recognizer = mock(AIIntentService.class);
        private final SemanticCacheService semanticCache = mock(SemanticCacheService.class);
        private final ToolRegistry registry = mock(ToolRegistry.class);
        private final SalesService sales = mock(SalesService.class);
        private final SalesOrderRepository orderRepository = mock(SalesOrderRepository.class);
        private final MaterialBatchService materials = mock(MaterialBatchService.class);
        private final Map<String, AIIntentConfig> byUtterance = new HashMap<>();
        private final Map<String, AIIntentConfig> intents = new HashMap<>();
        private final Map<String, ConversationMemory> rows = new HashMap<>();
        private final Map<Object, Integer> seen = new HashMap<>();
        private final IntentExecutionOrchestrator orchestrator;

        Harness() throws Exception {
            ConversationMemoryRepository repository = mock(ConversationMemoryRepository.class);
            when(repository.findBySessionId(anyString())).thenAnswer(call ->
                    Optional.ofNullable(rows.get(call.getArgument(0, String.class))));
            when(repository.save(any(ConversationMemory.class))).thenAnswer(call -> {
                ConversationMemory row = call.getArgument(0, ConversationMemory.class);
                rows.put(row.getSessionId(), row);
                return row;
            });
            ConversationMemoryServiceImpl memory = new ConversationMemoryServiceImpl(repository, null);
            ReflectionTestUtils.setField(memory, "windowSize", 10);
            ReflectionTestUtils.setField(memory, "summaryThreshold", 1000);
            ReflectionTestUtils.setField(memory, "expireMinutes", 60);
            memory.getOrCreateContext(FACTORY, USER, SESSION);

            OrderListTool orderTool = new OrderListTool(sales);
            ReflectionTestUtils.setField(orderTool, "objectMapper", mapper);
            OrderQueryTool orderQuery = new OrderQueryTool(sales, orderRepository);
            ReflectionTestUtils.setField(orderQuery, "objectMapper", mapper);
            MaterialBatchQueryTool materialTool = new MaterialBatchQueryTool();
            ReflectionTestUtils.setField(materialTool, "objectMapper", mapper);
            ReflectionTestUtils.setField(materialTool, "materialBatchService", materials);
            for (ToolExecutor tool : List.<ToolExecutor>of(orderTool, orderQuery, materialTool)) {
                when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
            }
            when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
            when(semanticCache.queryCache(anyString(), anyString()))
                    .thenReturn(com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
            when(sales.getSalesOrders(anyString(), anyInt(), anyInt())).thenAnswer(call ->
                    page(call.getArgument(1, Integer.class), call.getArgument(2, Integer.class)));
            when(sales.getSalesOrdersFiltered(anyString(), any(), any(), any(), anyInt(), anyInt())).thenAnswer(call ->
                    page(call.getArgument(4, Integer.class), call.getArgument(5, Integer.class)));
            SalesOrder one = new SalesOrder();
            one.setId("id-1");
            one.setOrderNumber("SO-1");
            when(sales.getSalesOrderById(anyString(), anyString())).thenReturn(one);
            // 45 rows in total like the order stub, so server paging has a next page (an empty stub makes
            // main refuse "下一页" correctly but for the wrong reason).
            when(materials.getMaterialBatchList(anyString(), any(PageRequest.class), any(MaterialBatchListFilter.class)))
                    .thenAnswer(call -> {
                        PageRequest pr = call.getArgument(1, PageRequest.class);
                        int pg = pr.getPage() == null ? 1 : pr.getPage();
                        int sz = pr.getSize() == null ? 10 : pr.getSize();
                        List<com.cretas.aims.dto.material.MaterialBatchDTO> list = new ArrayList<>();
                        for (int n = (pg - 1) * sz + 1; n <= Math.min(45, pg * sz); n++) {
                            com.cretas.aims.dto.material.MaterialBatchDTO b = new com.cretas.aims.dto.material.MaterialBatchDTO();
                            b.setId("mb-" + n);
                            b.setBatchNumber("MB-" + n);
                            list.add(b);
                        }
                        return PageResponse.of(list, pg, sz, 45L);
                    });

            for (Map.Entry<String, String> e : TOOLS.entrySet()) {
                AIIntentConfig cfg = AIIntentConfig.builder().intentCode(e.getKey()).intentName(e.getKey())
                        .intentCategory("QUERY").businessType("FACTORY").sensitivityLevel("LOW").requiresApproval(false)
                        .toolName(e.getValue()).isActive(true).build();
                intents.put(e.getKey(), cfg);
                when(recognizer.getIntentByCode(FACTORY, e.getKey())).thenReturn(Optional.of(cfg));
                when(recognizer.getIntentByCode(e.getKey())).thenReturn(Optional.of(cfg));
                when(recognizer.hasPermission(e.getKey(), ROLE)).thenReturn(true);
                when(recognizer.hasPermission(FACTORY, e.getKey(), ROLE)).thenReturn(true);
            }
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any())).thenAnswer(call -> matched(call.getArgument(0, String.class)));
            when(recognizer.recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), any(), any(), any(),
                    any(), any(), any())).thenAnswer(call -> matched(call.getArgument(0, String.class)));

            ToolCallRedundancyService redundancy = mock(ToolCallRedundancyService.class);
            when(redundancy.isRedundant(anyString(), anyString(), anyMap())).thenReturn(false);
            ToolDispatchService dispatch = new ToolDispatchService(registry, mapper, mock(DashScopeClient.class), redundancy,
                    mock(SelfCorrectionService.class), mock(CorrectionAgentService.class),
                    mock(ExternalVerifierService.class), mock(ToolResultValidatorService.class),
                    mock(ParameterExtractionLearningService.class));
            ReflectionTestUtils.setField(dispatch, "writeGuardService", new WriteGuardService());
            ToolRbacEnforcer rbac = mock(ToolRbacEnforcer.class);
            when(rbac.check(any(), anyMap())).thenReturn(ToolRbacEnforcer.Decision.allow());
            ReflectionTestUtils.setField(dispatch, "toolRbacEnforcer", rbac);
            ReflectionTestUtils.setField(dispatch, "queryPreprocessorService", new QueryPreprocessorServiceImpl(
                    new TimeNormalizationRules(), new ColloquialMappings(), null, null, mapper, null, null));
            ReflectionTestUtils.setField(dispatch, "conversationMemoryService", memory);
            com.cretas.aims.service.impl.ResultFormatterServiceImpl formatter =
                    new com.cretas.aims.service.impl.ResultFormatterServiceImpl();
            com.cretas.aims.security.PriceFieldResponseAdvice priceView =
                    mock(com.cretas.aims.security.PriceFieldResponseAdvice.class);
            when(priceView.maskedViewFor(any(), any())).thenAnswer(call -> call.getArgument(0));
            ReflectionTestUtils.setField(formatter, "priceView", priceView);
            IntentPermissionGate gate = mock(IntentPermissionGate.class);
            when(gate.check(any(), any(), anyString())).thenReturn(IntentPermissionGate.PermissionCheck.allowed());
            BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
            when(businessTypeGate.check(anyString(), any())).thenReturn(Optional.empty());
            when(businessTypeGate.resolveDomain(FACTORY)).thenReturn("FACTORY");

            orchestrator = new IntentExecutionOrchestrator(
                    recognizer, mock(IntentSemanticsParser.class), semanticCache,
                    mock(RuleEngineService.class), mock(ConversationService.class), memory,
                    mapper, mock(DashScopeClient.class), mock(DashScopeConfig.class),
                    mock(IntentKnowledgeBase.class), mock(AIAnalysisResultRepository.class), registry,
                    mock(AnalysisRouterService.class), mock(ComplexityRouter.class), mock(AgentOrchestrator.class),
                    mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class), dispatch,
                    mock(DynamicToolSelectionService.class), mock(QueryPreprocessorService.class));
            ReflectionTestUtils.setField(orchestrator, "intentPermissionGate", gate);
            ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
            IntentConfigManagementService config = mock(IntentConfigManagementService.class);
            when(config.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
            ReflectionTestUtils.setField(orchestrator, "configService", config);
            ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);
            ReflectionTestUtils.setField(orchestrator, "resultFormatterService", formatter);
            ReflectionTestUtils.setField(orchestrator, "recognitionHistoryResolver", new RecognitionHistoryResolver(memory));
            OrchestratorPriceViewTestSupport.callerSeesAllAmounts(orchestrator);
            FactoryAnalysisTestMetadata.bindNamedFactories(orchestrator);
            for (String counter : List.of("branchToolDirect", "branchSkill", "branchDynamic", "branchNoMatch")) {
                ReflectionTestUtils.setField(orchestrator, counter, new java.util.concurrent.atomic.AtomicLong());
            }
        }

        private static PageResponse<SalesOrder> page(int page, int size) {
            List<SalesOrder> list = new ArrayList<>();
            for (int n = (page - 1) * size + 1; n <= Math.min(45, page * size); n++) {
                SalesOrder order = new SalesOrder();
                order.setId("id-" + n);
                order.setOrderNumber("SO-" + n);
                list.add(order);
            }
            return PageResponse.of(list, page, size, 45L);
        }

        private IntentMatchResult matched(String utterance) {
            AIIntentConfig intent = byUtterance.get(utterance);
            if (intent == null) return IntentMatchResult.empty(utterance);
            return IntentMatchResult.builder().bestMatch(intent).confidence(0.99d)
                    .matchMethod(IntentMatchResult.MatchMethod.KEYWORD).userInput(utterance)
                    .requiresConfirmation(false).build();
        }

        IntentExecuteResponse say(String utterance) throws Exception {
            IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(utterance).sessionId(SESSION).build();
            try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER, FACTORY, ROLE))) {
                return orchestrator.execute(FACTORY, request, USER, ROLE);
            }
        }

        List<Map<String, Object>> newCalls() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object m : List.of(sales, orderRepository, materials)) {
                List<Invocation> all = new ArrayList<>(mockingDetails(m).getInvocations());
                int from = seen.getOrDefault(m, 0);
                for (Invocation inv : all.subList(Math.min(from, all.size()), all.size())) {
                    Map<String, Object> call = new LinkedHashMap<>();
                    call.put("method", inv.getMethod().getName());
                    List<Object> args = new ArrayList<>();
                    for (Object a : inv.getArguments()) args.add(plain(a));
                    call.put("args", args);
                    out.add(call);
                }
                seen.put(m, all.size());
            }
            return out;
        }
    }
}
