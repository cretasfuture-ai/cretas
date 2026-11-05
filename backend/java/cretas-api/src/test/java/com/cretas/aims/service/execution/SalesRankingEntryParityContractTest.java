package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.*;
import com.cretas.aims.dto.ai.*;
import com.cretas.aims.service.*;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Controlled service-entry regressions: actual normal/SSE entry, dispatch and ranking Tool.
 * Model, repository and permission dependencies are controlled, not production E2E evidence.
 */
class SalesRankingEntryParityContractTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void semanticCorrectionsAndCancellationThroughBothEntries(boolean streaming) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var entry = new Entry(h, streaming);
            assertThat(entry.semantic("看看销售排行", h.intent, "NEW", Map.of()).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.semantic("同样的看上月", h.intent, "CORRECT", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31")).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.lastRequest.getSemanticParameters()).containsEntry("startDate", "2026-10-01").containsEntry("metric", "amount");
            clearInvocations(h.ranking);
            assertThat(entry.semantic("不是金额，是数量", h.intent, "UNSUPPORTED", Map.of()).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
            verifyNoInteractions(h.ranking);
            assertThat(entry.semantic("不用查了", null, "CANCEL", Map.of()).getStatus()).isEqualTo("CANCELLED");
            verify(h.conversations).cancelConversation("F006", 7L, "sales-session");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void orderTimeCorrectionRetainsStatusAndSwitchDropsIt(boolean streaming) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var entry = new Entry(h, streaming);
            var order = entry.read("order_list", "ORDER_LIST");
            var material = entry.read("material_batch_query", "MATERIAL_BATCH_QUERY");
            assertThat(entry.semantic("查上月已确认订单", order, "NEW", Map.of("startDate", "2026-10-01", "endDate", "2026-10-31", "status", "CONFIRMED")).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.semantic("时间改成本月，状态不变", order, "CORRECT", Map.of("startDate", "2026-11-01", "endDate", "2026-11-30")).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.lastRequest.getSemanticParameters()).containsEntry("status", "CONFIRMED").containsEntry("startDate", "2026-11-01");
            assertThat(entry.semantic("查今天入库的原料", material, "SWITCH", Map.of("startDate", "2026-11-05", "endDate", "2026-11-05")).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.lastRequest.getSemanticParameters()).doesNotContainKey("status");
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void revokedPermissionAndForeignOwnerNeverExecute(boolean streaming) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var entry = new Entry(h, streaming);
            when(entry.intents.hasPermission(anyString(), anyString())).thenReturn(false);
            assertThat(entry.semantic("看看销售排行", h.intent, "NEW", Map.of()).getStatus()).isIn("NO_PERMISSION", "FAILED", "SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(h.ranking);
            h.owner.setFactoryId("FOREIGN");
            assertThat(entry.semantic("看看销售排行", h.intent, "NEW", Map.of()).getStatus()).isIn("NO_PERMISSION", "FAILED", "SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(h.ranking);
        }
    }

    static class Entry {
        final SalesRankingSemanticContractTest.Harness h;
        final boolean streaming;
        final AIIntentService intents = mock(AIIntentService.class);
        final IntentExecutionOrchestrator ordinary;
        final SseStreamingService sse;
        final AtomicReference<IntentExecuteResponse> result = new AtomicReference<>();
        IntentExecuteRequest lastRequest;
        Entry(SalesRankingSemanticContractTest.Harness h, boolean streaming) throws Exception {
            this.h = h; this.streaming = streaming;
            when(intents.hasPermission(anyString(), anyString())).thenReturn(true);
            when(intents.getIntentByCode(eq("F006"), eq(SalesRankingParameters.INTENT))).thenReturn(Optional.of(h.intent));
            var deps = new HashMap<Class<?>, Object>();
            deps.put(AIIntentService.class, intents); deps.put(ConversationMemoryService.class, h.memory);
            deps.put(ConversationService.class, h.conversations); deps.put(ToolRegistry.class, h.registry);
            deps.put(ToolDispatchService.class, h.dispatch); deps.put(com.fasterxml.jackson.databind.ObjectMapper.class, h.mapper);
            var semanticCache = mock(SemanticCacheService.class);
            when(semanticCache.queryCache(anyString(), anyString())).thenReturn(com.cretas.aims.dto.cache.SemanticCacheHit.miss(0L));
            deps.put(SemanticCacheService.class, semanticCache);
            ordinary = construct(IntentExecutionOrchestrator.class, deps);
            var configs = mock(IntentConfigManagementService.class);
            when(configs.resolveBusinessDomain("F006")).thenReturn("FACTORY");
            ReflectionTestUtils.setField(ordinary, "configService", configs);
            ReflectionTestUtils.setField(ordinary, "writeGuardService", new WriteGuardService());
            var domain = mock(BusinessTypeGate.class);
            when(domain.check(anyString(), any())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(ordinary, "businessTypeGate", domain);
            ReflectionTestUtils.setField(ordinary, "salesRankingConversationSupport", h.support);
            var context = new SemanticConversationContextService(h.memory, SalesRankingSemanticContractTest.CLOCK);
            ReflectionTestUtils.setField(ordinary, "semanticConversationContextService", context);
            sse = spy(construct(SseStreamingService.class, deps));
            ReflectionTestUtils.setField(sse, "conversationMemoryService", h.memory);
            ReflectionTestUtils.setField(sse, "conversationService", h.conversations);
            ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(sse, "businessTypeGate", domain);
            ReflectionTestUtils.setField(sse, "salesRankingConversationSupport", h.support);
            ReflectionTestUtils.setField(sse, "semanticConversationContextService", context);
            doAnswer(c -> {
                if ("result".equals(c.getArgument(1)) && c.getArgument(2) instanceof IntentExecuteResponse response) result.set(response);
                if ("error".equals(c.getArgument(1))) throw new AssertionError("SSE error: " + c.getArgument(2));
                return null;
            }).when(sse).sendSseEvent(any(), anyString(), any());
        }
        IntentExecuteResponse run(String text, boolean explicit) {
            lastRequest = h.query(text); if (!explicit) lastRequest.setIntentCode(null);
            return execute(lastRequest);
        }
        IntentExecuteResponse semantic(String text, com.cretas.aims.entity.config.AIIntentConfig intent, String action, Map<String,Object> delta) throws Exception {
            QueryPreprocessorService preprocessor = (QueryPreprocessorService) ReflectionTestUtils.getField(ordinary, "queryPreprocessorService");
            when(preprocessor.detectNegationVeto(eq(text), any())).thenReturn("CANCEL".equals(action)
                    ? QueryPreprocessorService.NegationKind.VETO_READ : QueryPreprocessorService.NegationKind.NONE);
            var match = com.cretas.aims.dto.intent.IntentMatchResult.builder().bestMatch(intent)
                    .confidence(intent == null ? 0.0 : .99).matchMethod(com.cretas.aims.dto.intent.IntentMatchResult.MatchMethod.LLM)
                    .requiresConfirmation(false).build();
            when(intents.recognizeIntentWithConfidence(anyString(), eq("F006"), anyInt(), any(), anyString(), any(), any(), any(), anyList(), anyMap())).thenReturn(match);
            SemanticReadTurnResolverContractTest.model(h, action, delta);
            return run(text, false);
        }
        com.cretas.aims.entity.config.AIIntentConfig read(String name, String code) throws Exception {
            var tool = mock(ToolExecutor.class);
            when(tool.getToolName()).thenReturn(name);
            when(tool.getDescription()).thenReturn("受控只读查询");
            when(tool.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
            when(tool.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
            when(tool.getParametersSchema()).thenReturn(Map.of("type", "object", "properties", Map.of(
                    "startDate", Map.of("type", "string"), "endDate", Map.of("type", "string"), "status", Map.of("type", "string")), "required", List.of()));
            when(tool.execute(any(), anyMap())).thenReturn("{\"success\":true,\"data\":{\"total\":0,\"content\":[]},\"message\":\"受控空结果\"}");
            when(h.registry.getExecutor(name)).thenReturn(Optional.of(tool));
            var intent = com.cretas.aims.entity.config.AIIntentConfig.builder().intentCode(code).intentName(code)
                    .toolName(name).intentCategory("DATA_OPERATION").businessType("FACTORY").isActive(true).build();
            when(intents.getIntentByCode("F006", code)).thenReturn(Optional.of(intent));
            return intent;
        }
        IntentExecuteResponse execute(IntentExecuteRequest request) {
            lastRequest = request;
            if (!streaming) return ordinary.execute("F006", lastRequest, 7L, "sales_manager");
            result.set(null);
            sse.executeStreamAsync(new SseEmitter(), "F006", lastRequest, 7L, "sales_manager", null);
            assertThat(result.get()).as("SSE must return a terminal business response").isNotNull();
            return result.get();
        }
        // Reuse the real production constructor; only its dependencies are mocked. This avoids
        // copying a second execution framework or bypassing entry admission/routing.
        static <T> T construct(Class<T> type, Map<Class<?>, Object> deps) throws Exception {
            var ctor = type.getConstructors()[0];
            Object[] args = Arrays.stream(ctor.getParameterTypes()).map(t -> deps.computeIfAbsent(t, k -> mock(k))).toArray();
            return type.cast(ctor.newInstance(args));
        }
    }
}
