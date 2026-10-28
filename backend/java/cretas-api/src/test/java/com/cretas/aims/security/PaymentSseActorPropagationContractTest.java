package com.cretas.aims.security;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.service.ConversationMemoryService;
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
        var mapper=spy(new ObjectMapper().findAndRegisterModules());
        var observed=new CompletableFuture<AuthenticatedActor>();
        // Observe actual worker identity at a stateless event serialization checkpoint.
        // Platform principals must not touch factory conversation memory merely to be observed.
        doAnswer(call -> {
            assertThat(RequestContextHolder.getRequestAttributes()).isNull();
            observed.complete(AuthenticatedActor.fromCurrentExecution().orElse(null));
            return call.callRealMethod();
        }).when(mapper).writeValueAsString(any());
        var service=new SseStreamingService(null,null,null,null,mapper,null,null,null,null,null);
        var executor=Executors.newSingleThreadExecutor();
        ReflectionTestUtils.setField(service,"sseExecutor",executor);
        var memory=mock(ConversationMemoryService.class);
        ReflectionTestUtils.setField(service,"conversationMemoryService",memory);
        when(memory.getOrCreateContext(eq("F1"),eq(56L),any())).thenReturn(null);
        var request=new MockHttpServletRequest();request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,actor);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            service.executeStream("F1",new IntentExecuteRequest(),56L,role);
            assertThat(observed.get(5,TimeUnit.SECONDS)).isEqualTo(actor);
            assertThat(executor.submit(() -> AuthenticatedActor.fromCurrentExecution().isEmpty()
                    && com.cretas.aims.utils.SecurityUtils.currentUserId()==null).get(5,TimeUnit.SECONDS)).isTrue();
            if (role.equals("factory")) verify(memory).getOrCreateContext(eq("F1"),eq(56L),any());
            else verifyNoInteractions(memory);
        } finally {RequestContextHolder.resetRequestAttributes();service.destroy();executor.shutdownNow();}
    }
}
