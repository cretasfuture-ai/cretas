package com.cretas.aims.security;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.cache.SemanticCacheHit;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.execution.SseStreamingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PaymentSseActorPropagationContractTest {
    @ParameterizedTest @ValueSource(strings={"factory","auditor","super_admin"})
    void actualSseWorkerPreservesTypedNamespaceAndCleansPoolThread(String role) throws Exception {
        var actor=new AuthenticatedActor(role.equals("factory")?AuthenticatedActorType.FACTORY_USER:
                AuthenticatedActorType.PLATFORM_ADMIN,56L,role.equals("factory")?"F1":null,role);
        var mapper=new ObjectMapper().findAndRegisterModules();
        var observed=new CompletableFuture<AuthenticatedActor>();
        // The SSE service now hands MVC a typed DataWithMediaType payload; it no longer
        // serializes the event on this worker. Observe the actor at the first real worker
        // callback instead, then keep the pool-thread cleanup assertion below.
        var intents=mock(AIIntentService.class, CALLS_REAL_METHODS);
        doAnswer(call -> {
            assertThat(RequestContextHolder.getRequestAttributes()).isNull();
            observed.complete(AuthenticatedActor.fromCurrentExecution().orElse(null));
            return IntentMatchResult.builder().confidence(0d).build();
        }).when(intents).recognizeIntentWithConfidence(anyString(), anyString(), anyInt(), nullable(Long.class),
                anyString(), nullable(String.class));
        var cache=mock(SemanticCacheService.class);
        when(cache.queryCache(anyString(), anyString())).thenReturn(SemanticCacheHit.miss(0L));
        var knowledge=mock(IntentKnowledgeBase.class);
        when(knowledge.detectQuestionType(anyString())).thenReturn(IntentKnowledgeBase.QuestionType.OPERATIONAL_COMMAND);
        var service=new SseStreamingService(intents,cache,knowledge,null,mapper,null,null,null,null,null);
        var executor=Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(service,"sseExecutor",executor);
        var memory=mock(ConversationMemoryService.class);
        ReflectionTestUtils.setField(service,"conversationMemoryService",memory);
        when(memory.getOrCreateContext(eq("F1"),eq(56L),any())).thenReturn(
                com.cretas.aims.dto.conversation.ConversationContext.builder()
                        .sessionId("sse-fixture").factoryId("F1").userId(56L).build());
        var request=new MockHttpServletRequest();request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            IntentExecuteRequest intentRequest=IntentExecuteRequest.builder().userInput("测试").build();
            service.executeStream("F1",intentRequest,56L,role);
            assertThat(observed.get(5,TimeUnit.SECONDS)).isEqualTo(actor);
            assertThat(executor.submit(() -> AuthenticatedActor.fromCurrentExecution().isEmpty()
                    && com.cretas.aims.utils.SecurityUtils.currentUserId()==null).get(5,TimeUnit.SECONDS)).isTrue();
            if (role.equals("factory")) verify(memory).getOrCreateContext(eq("F1"),eq(56L),any());
            else verifyNoInteractions(memory);
        } finally {RequestContextHolder.resetRequestAttributes();service.destroy();executor.shutdownNow();}
    }
}
