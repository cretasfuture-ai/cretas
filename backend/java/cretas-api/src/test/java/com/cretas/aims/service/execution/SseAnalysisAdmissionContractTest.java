package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.SemanticCacheService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SseAnalysisAdmissionContractTest {
    @ParameterizedTest
    @ValueSource(strings = {"NULL", "UNKNOWN", "THROW"})
    void unverifiedMetadataStopsBeforeAnySessionRecognitionOrProvider(String metadata) throws Exception {
        Harness h = new Harness();
        if (metadata.equals("THROW")) when(h.domains.resolveDomain("F006")).thenThrow(new IllegalStateException("metadata down"));
        else when(h.domains.resolveDomain("F006")).thenReturn(metadata.equals("NULL") ? null : "UNKNOWN");
        assertThat(h.execute("F006", "finance_manager").getStatus()).isEqualTo("TENANT_TYPE_UNAVAILABLE");
        verify(h.domains, times(1)).resolveDomain("F006");
        verifyNoInteractions(h.dependencies.toArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"restaurant_owner", "restaurant_manager", "restaurant_chef", "restaurant_purchaser"})
    void retiredRoleNeverReachesMetadataSessionOrProvider(String role) throws Exception {
        Harness h = new Harness();
        assertThat(h.execute("F006", role).getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        verifyNoInteractions(h.domains);
        verifyNoInteractions(h.dependencies.toArray());
    }

    @ParameterizedTest
    @ValueSource(strings = {"RESTAURANT", "BRANCH"})
    void persistedRetiredDomainRejectsOrdinaryLegacyRole(String domain) throws Exception {
        Harness h = new Harness();
        when(h.domains.resolveDomain("TENANT_42")).thenReturn(domain);
        assertThat(h.execute("TENANT_42", "finance_manager").getStatus()).isEqualTo("UNSUPPORTED_CAPABILITY");
        verifyNoInteractions(h.dependencies.toArray());
    }

    private static final class Harness {
        final List<Object> dependencies = new ArrayList<>();
        final BusinessTypeGate domains = mock(BusinessTypeGate.class);
        final SseEmitter emitter = mock(SseEmitter.class);
        final SseStreamingService service = spy(new SseStreamingService(
                dependency(AIIntentService.class), dependency(SemanticCacheService.class),
                dependency(IntentKnowledgeBase.class), dependency(AnalysisRouterService.class),
                new ObjectMapper(), dependency(ToolRegistry.class), dependency(ToolDispatchService.class),
                dependency(DynamicToolSelectionService.class), dependency(DashScopeClient.class), mock(DashScopeConfig.class)));
        Harness() throws Exception {
            ReflectionTestUtils.setField(service, "businessTypeGate", domains);
            doNothing().when(service).sendSseEvent(any(), anyString(), any());
        }
        <T> T dependency(Class<T> type) { T value = mock(type); dependencies.add(value); return value; }
        IntentExecuteResponse execute(String factory, String role) throws Exception {
            var request = IntentExecuteRequest.builder().userInput("查看本厂库存").sessionId("must-not-load").build();
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, factory, request, 7L, role);
            var result = ArgumentCaptor.forClass(Object.class);
            verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
            verify(emitter).complete();
            return (IntentExecuteResponse) result.getValue();
        }
    }
}
