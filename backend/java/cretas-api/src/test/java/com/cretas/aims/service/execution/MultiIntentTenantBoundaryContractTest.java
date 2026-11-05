package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiIntentTenantBoundaryContractTest {
    private static final String FACTORY = "F006";

    @Mock AIIntentService aiIntentService;
    @Mock IntentExecutionOrchestrator orchestrator;
    @Mock SseStreamingService streaming;
    @Mock IntentConfigManagementService configService;
    private MultiIntentExecutionService service;

    @BeforeEach
    void setUp() {
        service = new MultiIntentExecutionService(aiIntentService, orchestrator, streaming, configService);
        ReflectionTestUtils.setField(service, "multiIntentEnabled", true);
    }

    private IntentExecuteRequest request() {
        IntentExecuteRequest request = new IntentExecuteRequest();
        request.setUserInput("查库存");
        return request;
    }

    private IntentMatchResult match() {
        return IntentMatchResult.builder().additionalIntents(List.of()).build();
    }

    @Test
    void factoryMetadataAllowsBothPublicPathsAndCallsOrchestrator() {
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("FAILED").build());
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        when(aiIntentService.recognizeMultiIntent("查库存", FACTORY)).thenReturn(null);

        service.executeMultiIntent(FACTORY, request(), 7L, "factory_operator");
        service.executeMultiIntentFromMatchResult(request(), match(), FACTORY, 7L, "factory_operator");

        verify(aiIntentService).recognizeMultiIntent("查库存", FACTORY);
        verify(orchestrator, atLeastOnce()).execute(anyString(), any(), any(), anyString());
    }

    @Test
    void unknownNullOrResolverFailureIs503AndDoesNotReachDownstream() {
        for (String domain : new String[]{null, "UNKNOWN"}) {
            reset(configService, aiIntentService, orchestrator);
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn(domain);
            assertThatThrownBy(() -> service.executeMultiIntent(FACTORY, request(), 7L, "factory_operator"))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(503);
            verifyNoInteractions(aiIntentService, orchestrator);
        }
        reset(configService, aiIntentService, orchestrator);
        when(configService.resolveBusinessDomain(FACTORY)).thenThrow(new IllegalStateException("metadata down"));
        assertThatThrownBy(() -> service.executeMultiIntentFromMatchResult(
                request(), match(), FACTORY, 7L, "factory_operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(503);
        verifyNoInteractions(aiIntentService, orchestrator);
    }

    @Test
    void restaurantRolesAndRetiredIdentityAre410BeforeDownstream() {
        for (String role : new String[]{"restaurant_owner", "restaurant_manager", "restaurant_chef", "restaurant_purchaser"}) {
            assertThatThrownBy(() -> service.executeMultiIntent(FACTORY, request(), 7L, role))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(410);
        }
        assertThatThrownBy(() -> service.executeMultiIntentFromMatchResult(
                request(), match(), "MOCK_REST", 7L, "factory_operator"))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(410);
        verifyNoInteractions(aiIntentService, orchestrator);
    }
}
