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
    void sameScriptThroughOrdinaryAndStreamingEntry(boolean streaming) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var entry = new Entry(h, streaming);
            assertThat(entry.run("看看销售排行", true).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.run("上个月呢", false).getStatus()).isEqualTo("SUCCESS");
            clearInvocations(h.ranking);
            assertThat(entry.run("不是金额，按销量，前五个", false).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
            verifyNoInteractions(h.ranking);
            assertThat(entry.lastRequest.getContext()).containsEntry("startDate", "2026-10-01")
                    .containsEntry("limit", 5).containsEntry("metric", "quantity");
            assertThat(entry.run("不是不查，先看上个月", false).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
            assertThat(entry.run("按金额", false).getStatus()).isEqualTo("SUCCESS");
            assertThat(entry.run("不用查了", false).getStatus()).isEqualTo("CANCELLED");
            clearInvocations(h.ranking);
            assertThat(entry.run("上个月呢", false).getStatus()).isEqualTo("NEED_MORE_INFO");
            verifyNoInteractions(h.ranking);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void revokedPermissionAndForeignIdentityNeverExecuteRanking(boolean streaming) throws Exception {
        try (var h = new SalesRankingSemanticContractTest.Harness()) {
            var entry = new Entry(h, streaming);
            assertThat(entry.run("看看销售排行", true).getStatus()).isEqualTo("SUCCESS");
            when(entry.intents.hasPermission(anyString(), anyString())).thenReturn(false);
            clearInvocations(h.ranking);
            assertThat(entry.run("上个月呢", false).getStatus()).isIn("NO_PERMISSION", "FAILED", "SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(h.ranking);
            h.owner.setFactoryId("FOREIGN");
            assertThat(entry.run("上个月呢", false).getStatus()).isIn("NO_PERMISSION", "FAILED", "SESSION_OWNERSHIP_DENIED");
            verifyNoInteractions(h.ranking);
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void inventoryAndOrderReadSwitchesDoNotInheritRankingAndCanCancel(boolean streaming) throws Exception {
        for (String toolName : List.of("material_batch_query", "order_list")) {
            try (var h = new SalesRankingSemanticContractTest.Harness()) {
                var entry = new Entry(h, streaming);
                assertThat(entry.run("上个月销售排行前五个", true).getStatus()).isEqualTo("SUCCESS");
                String code = toolName.equals("order_list") ? "ORDER_LIST" : "MATERIAL_BATCH_QUERY";
                var read = mock(ToolExecutor.class);
                when(read.getToolName()).thenReturn(toolName);
                when(read.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
                when(read.getActionType()).thenReturn(ToolExecutor.ActionType.READ);
                when(read.getParametersSchema()).thenReturn(Map.of("type", "object", "properties", Map.of(), "required", List.of()));
                when(read.execute(any(), anyMap())).thenReturn("{\"success\":true,\"data\":{\"message\":\"受控只读结果\",\"items\":[]}}");
                when(h.registry.getExecutor(toolName)).thenReturn(Optional.of(read));
                var intent = com.cretas.aims.entity.config.AIIntentConfig.builder().intentCode(code)
                        .intentName("受控只读查询").intentCategory("DATA_QUERY").toolName(toolName)
                        .businessType("FACTORY").isActive(true).build();
                when(entry.intents.getIntentByCode("F006", code)).thenReturn(Optional.of(intent));
                var next = h.query(toolName.equals("order_list") ? "先查销售订单" : "算了，先查原料库存");
                next.setIntentCode(code);
                assertThat(entry.execute(next).getStatus()).isEqualTo("SUCCESS");
                var args = org.mockito.ArgumentCaptor.forClass(com.cretas.aims.ai.dto.ToolCall.class);
                verify(read).execute(args.capture(), anyMap());
                assertThat(args.getValue().getFunction().getArguments()).doesNotContain("startDate", "endDate", "metric", "limit");
                assertThat(entry.run("不用查了", false).getStatus()).isEqualTo("CANCELLED");
                clearInvocations(h.ranking, read);
                assertThat(entry.run("上个月呢", false).getStatus()).isEqualTo("NEED_MORE_INFO");
                verifyNoInteractions(h.ranking, read);
            }
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
            ordinary = construct(IntentExecutionOrchestrator.class, deps);
            var configs = mock(IntentConfigManagementService.class);
            when(configs.resolveBusinessDomain("F006")).thenReturn("FACTORY");
            ReflectionTestUtils.setField(ordinary, "configService", configs);
            ReflectionTestUtils.setField(ordinary, "writeGuardService", new WriteGuardService());
            var domain = mock(BusinessTypeGate.class);
            when(domain.check(anyString(), any())).thenReturn(Optional.empty());
            ReflectionTestUtils.setField(ordinary, "businessTypeGate", domain);
            ReflectionTestUtils.setField(ordinary, "salesRankingConversationSupport", h.support);
            sse = spy(construct(SseStreamingService.class, deps));
            ReflectionTestUtils.setField(sse, "conversationMemoryService", h.memory);
            ReflectionTestUtils.setField(sse, "conversationService", h.conversations);
            ReflectionTestUtils.setField(sse, "writeGuardService", new WriteGuardService());
            ReflectionTestUtils.setField(sse, "businessTypeGate", domain);
            ReflectionTestUtils.setField(sse, "salesRankingConversationSupport", h.support);
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
