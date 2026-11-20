package com.cretas.aims.service.execution;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PhraseShortcutEligibilitySseContractTest {

    private static final String FACTORY = "F006";
    private static final long USER_ID = 7L;
    private static final String TRACE = "BATCH_TRACE_BY_CUSTOMER";
    private static final String ORIGINAL = "9月20日发给XX客户的牛肉是哪批原料做的";

    @ParameterizedTest
    @ValueSource(strings = {"sales_manager", "factory_super_admin"})
    void singleTraceQuestionTakesShortcutForBothRoles(String role) throws IOException {
        for (String input : List.of(ORIGINAL, "9 月 20 号，发给上海连锁餐饮的酱卤牛肉，是哪批原料做的？")) {
            var h = new Harness();
            h.execute(input, role);
            verify(h.intents, never()).recognizeIntentWithConfidence(
                    anyString(), anyString(), anyInt(), any(), anyString(), any(), any(), any(), any());
            verify(h.service).sendSseEvent(any(), eq("intent_recognized"), argThat(event ->
                    event instanceof Map<?, ?> fields && TRACE.equals(fields.get("intentCode"))
                            && "PHRASE_MATCH".equals(fields.get("matchMethod"))));
            verify(h.dispatch).executeWithTool(
                    eq(h.tool), eq(FACTORY), any(), eq(h.intent), eq(USER_ID), eq(role), any());
            verify(h.service).sendSseEvent(any(), eq("complete"), any());
            verify(h.service, never()).sendSseEvent(any(), eq("error"), any());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "9月20日发给某客户的牛肉是哪批原料做的，顺便列出本月出货金额",
            "9 月 20 号发给上海连锁餐饮的酱卤牛肉，是哪批原料做的？另外本月出货金额是多少？",
            ORIGINAL + "再列出本月出货金额",
            ORIGINAL + "？本月出货金额是多少？",
            "先列出本月出货金额，然后" + ORIGINAL,
            "9月20日发给XX客户的牛肉，列出本月出货金额，是哪批原料做的？"
    })
    void compoundQuestionReachesNormalRecognitionIntact(String input) throws IOException {
        var h = new Harness();
        String role = "sales_manager";
        var hit = h.knowledge.matchPhraseHit(input, "FACTORY").orElseThrow();
        assertThat(hit).isEqualTo(Map.entry(IntentKnowledgeBase.CUSTOMER_DATE_TRACE_PHRASE, TRACE));

        h.execute(input, role);

        verify(h.intents).recognizeIntentWithConfidence(
                eq(input), eq(FACTORY), eq(3), eq(USER_ID), eq(role), isNull(), isNull(), isNull(), eq(List.of()));
        verify(h.service).sendSseEvent(any(), eq("complete"), any());
        verify(h.service, never()).sendSseEvent(any(), eq("error"), any());
        verifyNoInteractions(h.dispatch);
        assertThat(PhraseShortcutEligibility.isUnconditionedSimple(input, hit.getKey(), hit.getValue())).isFalse();
    }

    private static final class Harness {
        private final IntentKnowledgeBase knowledge = new IntentKnowledgeBase();
        private final AIIntentService intents = mock(AIIntentService.class);
        private final ToolDispatchService dispatch = mock(ToolDispatchService.class);
        private final ToolExecutor tool = mock(ToolExecutor.class);
        private final AIIntentConfig intent = AIIntentConfig.builder()
                .intentCode(TRACE).intentName("客户出货批次追溯").intentCategory("QUERY")
                .toolName("batch_trace_by_customer_date").build();
        private final SseStreamingService service;

        private Harness() throws IOException {
            knowledge.initDefaults();
            when(intents.getIntentByCode(FACTORY, TRACE)).thenReturn(Optional.of(intent));
            when(intents.hasPermission(eq(TRACE), anyString())).thenReturn(true);
            when(intents.recognizeIntentWithConfidence(
                    anyString(), eq(FACTORY), eq(3), any(), anyString(), any(), any(), any(), any()))
                    .thenReturn(IntentMatchResult.builder().confidence(0.0).build());
            SemanticCacheService cache = mock(SemanticCacheService.class);
            when(cache.queryCache(eq(FACTORY), anyString())).thenReturn(SemanticCacheHit.miss(0L));
            ToolRegistry registry = mock(ToolRegistry.class);
            when(registry.getExecutor(intent.getToolName())).thenReturn(Optional.of(tool));
            when(dispatch.executeWithTool(eq(tool), eq(FACTORY), any(), eq(intent), eq(USER_ID), anyString(), any()))
                    .thenReturn(IntentExecuteResponse.builder().intentCode(TRACE).status("COMPLETED").build());
            service = spy(new SseStreamingService(intents, cache, knowledge, null, new ObjectMapper(),
                    registry, dispatch, mock(DynamicToolSelectionService.class), null, null));
            BusinessTypeGate gate = mock(BusinessTypeGate.class);
            when(gate.resolveDomain(FACTORY)).thenReturn("FACTORY");
            ReflectionTestUtils.setField(service, "businessTypeGate", gate);
            ReflectionTestUtils.setField(service, "writeGuardService", new WriteGuardService());
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
        }

        private void execute(String input, String role) {
            var request = new MockHttpServletRequest();
            request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                    new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, USER_ID, FACTORY, role));
            var previous = RequestContextHolder.getRequestAttributes();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            try (var executor = Executors.newSingleThreadExecutor()) {
                ReflectionTestUtils.setField(service, "sseExecutor", executor);
                service.executeStream(FACTORY, IntentExecuteRequest.builder().userInput(input).build(), USER_ID, role);
            } finally {
                RequestContextHolder.setRequestAttributes(previous);
            }
        }
    }
}
