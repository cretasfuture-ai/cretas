package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.*;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.cache.SemanticCache;
import com.cretas.aims.entity.cache.SemanticCacheConfig;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.SemanticCacheConfigRepository;
import com.cretas.aims.repository.SemanticCacheRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.impl.SemanticCacheServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real SSE/cache/admission behavior. Dispatch responses are actor-specific I/O fixtures;
 * actual Gateway row/price filtering is covered by its separate integration contracts. */
class SseSemanticCacheAdmissionContractTest {
    private static final String INPUT = "查询库存";
    private static final String CODE = "MATERIAL_STOCK_QUERY";

    @ParameterizedTest
    @CsvSource({"150,factory_super_admin", "101,warehouse_worker", "102,warehouse_worker"})
    void sharedBossExecutionResultNeverReplacesFreshActorDispatch(long id, String role) throws Exception {
        Harness h = new Harness("F006", id, role, true);
        h.seedBossExecution();
        IntentExecuteResponse fresh = h.freshResponse();
        h.dispatchReturns(fresh);

        assertThat(h.execute()).isSameAs(fresh);
        h.verifyFreshAdmissionAndDispatch();
        assertThat(h.events).anyMatch(e -> e.name().equals("cache_hit"));
        h.verifyNoProvider();
    }

    @Test
    void sharedSuccessfulCacheCannotGrantInventoryPermission() throws Exception {
        Harness h = new Harness("F006", 201L, "quality_manager", false);
        h.seedBossExecution();

        IntentExecuteResponse result = h.execute();

        assertThat(result.getStatus()).isEqualTo("NO_PERMISSION");
        assertThat(result.getResultData()).isNull();
        verify(h.permissions).hasAnyPermission(eq(h.actor), eq("inventory:read"));
        verifyNoInteractions(h.dispatch);
        h.verifyNoProvider();
    }

    @Test
    void permissionChangeIsAppliedOnNextIdenticalCacheHit() throws Exception {
        Harness h = new Harness("F006", 101L, "warehouse_worker", true);
        h.seedBossExecution();
        h.dispatchReturns(h.freshResponse());
        assertThat(h.execute().getStatus()).isEqualTo("SUCCESS");
        when(h.permissions.hasAnyPermission(eq(h.actor), any(String[].class))).thenReturn(false);
        assertThat(h.execute().getStatus()).isEqualTo("NO_PERMISSION");
        verify(h.dispatch, times(1)).executeWithTool(any(), anyString(), any(), any(), anyLong(), anyString(), any());
        h.verifyNoProvider();
    }

    @Test
    void cachedIntentCannotSkipCurrentConfirmationOrUseOldParameters() throws Exception {
        Harness h = new Harness("F006", 150L, "factory_super_admin", true);
        AIIntentConfig staleWrite = AIIntentConfig.builder().intentCode("MATERIAL_BATCH_DELETE")
                .intentName("old write").intentCategory("WRITE").sensitivityLevel("LOW")
                .toolName("material_batch_delete").build();
        h.entry.setIntentCode(staleWrite.getIntentCode());
        h.seedIntent(staleWrite);
        h.currentMatch.setRequiresConfirmation(true);

        assertThat(h.execute().getStatus()).isEqualTo("NEED_CLARIFICATION");
        h.verifyRecognition();
        verifyNoInteractions(h.dispatch);
        h.verifyNoProvider();
    }

    @Test
    void staleIntentCannotExecuteWhenFreshRecognitionHasNoMatch() throws Exception {
        Harness h = new Harness("F006", 150L, "factory_super_admin", true);
        h.seedIntent(h.current);
        h.currentMatch.setBestMatch(null);
        when(h.dispatch.executeWithTool(any(), eq("F006"), any(), any(), anyLong(), anyString(), any()))
                .thenReturn(h.freshResponse());

        assertThat(h.execute().getStatus()).isNotEqualTo("SUCCESS");
        h.verifyRecognition();
        verifyNoInteractions(h.dispatch);
        h.verifyNoProvider();
    }

    @Test
    void corruptLegacyCachePayloadStillUsesFreshRequest() throws Exception {
        Harness h = new Harness("F006", 101L, "warehouse_worker", true);
        h.entry.setExecutionResult("{broken private response");
        h.entry.setIntentResult("{broken intent");
        IntentExecuteResponse fresh = h.freshResponse();
        h.dispatchReturns(fresh);
        assertThat(h.execute()).isSameAs(fresh);
        h.verifyFreshAdmissionAndDispatch();
        h.verifyNoProvider();
    }

    @Test
    void foreignFactoryMissUsesForeignFactoryForFreshDispatch() throws Exception {
        Harness h = new Harness("F007", 101L, "warehouse_worker", true);
        h.seedBossExecution();
        IntentExecuteResponse fresh = h.freshResponse();
        h.dispatchReturns(fresh);
        assertThat(h.execute()).isSameAs(fresh);
        h.verifyFreshAdmissionAndDispatch();
        assertThat(h.events).noneMatch(e -> e.name().equals("cache_hit"));
        verify(h.rows).findByFactoryIdAndInputHashAndExpiresAtAfter(eq("F007"), anyString(), any());
        h.verifyNoProvider();
    }

    @Test
    void successfulFreshExecutionWritesOnlyRecognitionData() throws Exception {
        Harness h = new Harness("F006", 101L, "warehouse_worker", true);
        when(h.rows.findByFactoryIdAndInputHashAndExpiresAtAfter(anyString(), anyString(), any()))
                .thenReturn(Optional.empty());
        h.dispatchReturns(h.freshResponse());
        assertThat(h.execute().getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<SemanticCache> saved = ArgumentCaptor.forClass(SemanticCache.class);
        verify(h.rows).save(saved.capture());
        assertThat(saved.getValue().getFactoryId()).isEqualTo("F006");
        assertThat(saved.getValue().getIntentResult()).contains(CODE);
        assertThat(saved.getValue().getExecutionResult()).isNull();
        h.verifyFreshAdmissionAndDispatch();
        h.verifyNoProvider();
    }

    private record Event(String name, Object data) { }

    private static final class Harness {
        final ObjectMapper mapper = new ObjectMapper();
        final SemanticCacheRepository rows = mock(SemanticCacheRepository.class);
        final AIIntentService intents = mock(AIIntentService.class);
        final PermissionService permissions = mock(PermissionService.class);
        final ToolDispatchService dispatch = mock(ToolDispatchService.class);
        final DashScopeClient provider = mock(DashScopeClient.class);
        final User actor = new User();
        final SemanticCache entry = SemanticCache.builder().id(900L).factoryId("F006")
                .intentCode(CODE).hitCount(0).build();
        final AIIntentConfig current = AIIntentConfig.builder().intentCode(CODE).intentName("current stock")
                .intentCategory("QUERY").sensitivityLevel("LOW").toolName("material_stock_summary")
                .requiredPermission("inventory:read").build();
        final IntentMatchResult currentMatch = IntentMatchResult.builder().bestMatch(current).confidence(1.0)
                .questionType(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND)
                .requiresConfirmation(false).build();
        final ToolExecutor executor = mock(ToolExecutor.class);
        final List<Event> events = new ArrayList<>();
        final SseStreamingService service;
        final IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(INPUT).mode("READ").build();
        final String factory;
        final long id;
        final String role;

        Harness(String factory, long id, String role, boolean allowed) throws Exception {
            this.factory = factory; this.id = id; this.role = role;
            actor.setId(id); actor.setFactoryId(factory); actor.setRoleCode(role);
            SemanticCacheConfigRepository config = mock(SemanticCacheConfigRepository.class);
            when(config.findByFactoryId(anyString())).thenReturn(Optional.of(SemanticCacheConfig.defaultConfig()));
            when(rows.findByFactoryIdAndInputHashAndExpiresAtAfter(anyString(), anyString(), any()))
                    .thenAnswer(i -> "F006".equals(i.getArgument(0)) ? Optional.of(entry) : Optional.empty());
            SemanticCacheService cache = new SemanticCacheServiceImpl(rows, config, mock(EmbeddingClient.class),
                    mock(RequestScopedEmbeddingCache.class), mapper);
            IntentKnowledgeBase knowledge = mock(IntentKnowledgeBase.class);
            when(knowledge.detectQuestionType(INPUT)).thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
            when(intents.recognizeIntentWithConfidence(eq(INPUT), eq(factory), eq(3), eq(id), eq(role),
                    any(), eq("READ"), any(), any())).thenReturn(currentMatch);
            UserRepository users = mock(UserRepository.class);
            when(users.findById(id)).thenReturn(Optional.of(actor));
            when(permissions.hasAnyPermission(eq(actor), any(String[].class))).thenReturn(allowed);
            ToolRbacGuard rbac = new ToolRbacGuard();
            ReflectionTestUtils.setField(rbac, "userRepository", users);
            ReflectionTestUtils.setField(rbac, "permissionService", permissions);
            IntentPermissionGate gate = new IntentPermissionGate();
            ReflectionTestUtils.setField(gate, "rbacGuard", rbac);
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.getExecutor(current.getToolName())).thenReturn(Optional.of(executor));
            service = spy(new SseStreamingService(intents, cache, knowledge, mock(AnalysisRouterService.class), mapper,
                    registry, dispatch, mock(DynamicToolSelectionService.class), provider, mock(DashScopeConfig.class)));
            ReflectionTestUtils.setField(service, "intentPermissionGate", gate);
            ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(service, "businessTypeGate", mock(BusinessTypeGate.class));
            doAnswer(i -> { events.add(new Event(i.getArgument(1), i.getArgument(2))); return null; })
                    .when(service).sendSseEvent(any(), anyString(), any());
        }

        void seedBossExecution() throws Exception {
            entry.setExecutionResult(mapper.writeValueAsString(IntentExecuteResponse.builder().status("SUCCESS")
                    .intentCode(CODE).message("old boss result")
                    .resultData(Map.of("items", List.of(Map.of("id", "A", "createdBy", 101, "unitPrice", 99),
                            Map.of("id", "B", "createdBy", 102, "unitPrice", 88)))).build()));
        }

        void seedIntent(AIIntentConfig intent) throws Exception {
            // Use an actually deserializable legacy payload, excluding derived read-only
            // Jackson properties. A broken fixture must not masquerade as a stale-cache deny.
            Map<String, Object> config = new LinkedHashMap<>(Map.of(
                    "intentCode", intent.getIntentCode(), "intentName", intent.getIntentName(),
                    "intentCategory", intent.getIntentCategory(), "sensitivityLevel", "LOW",
                    "toolName", intent.getToolName()));
            if (intent.getRequiredPermission() != null) config.put("requiredPermission", intent.getRequiredPermission());
            String json = mapper.writeValueAsString(Map.of("bestMatch", config,
                    "confidence", 1.0, "requiresConfirmation", false,
                    "additionalIntents", List.of(Map.of("intentCode", intent.getIntentCode(),
                            "confidence", 1.0, "extractedParams", Map.of("createdBy", 150, "batchId", "old-private")))));
            assertThat(mapper.readValue(json, IntentMatchResult.class).hasMatch()).isTrue();
            entry.setIntentResult(json);
        }

        IntentExecuteResponse freshResponse() {
            return IntentExecuteResponse.builder().status("SUCCESS").intentCode(CODE).message("fresh actor data")
                    .resultData(Map.of("factoryId", factory, "actorId", id, "row", "fresh-" + id)).build();
        }

        void dispatchReturns(IntentExecuteResponse response) {
            when(dispatch.executeWithTool(eq(executor), eq(factory), same(request), same(current), eq(id), eq(role),
                    same(currentMatch))).thenReturn(response);
        }

        IntentExecuteResponse execute() {
            events.clear();
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", mock(SseEmitter.class), factory,
                    request, id, role);
            List<IntentExecuteResponse> results = events.stream().filter(e -> e.name().equals("result"))
                    .map(e -> (IntentExecuteResponse) e.data()).toList();
            assertThat(results).hasSize(1);
            return results.get(0);
        }

        void verifyRecognition() {
            verify(intents).recognizeIntentWithConfidence(eq(INPUT), eq(factory), eq(3), eq(id), eq(role),
                    any(), eq("READ"), any(), eq(List.of()));
        }

        void verifyFreshAdmissionAndDispatch() {
            verifyRecognition();
            verify(permissions).hasAnyPermission(eq(actor), eq("inventory:read"));
            verify(dispatch).executeWithTool(eq(executor), eq(factory), same(request), same(current), eq(id), eq(role),
                    same(currentMatch));
        }

        void verifyNoProvider() { verifyNoInteractions(provider); }
    }
}
