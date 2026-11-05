package com.cretas.aims.controller;

import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.service.KeywordEffectivenessService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.impl.IntentConfigRollbackService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AIRecognitionTenantBoundaryContractTest {
    private static final String FACTORY = "F006";
    private static final String TOKEN = "jwt-user-7";

    @Mock AIIntentService aiIntentService;
    @Mock IntentConfigManagementService configService;
    @Mock JwtUtil jwtUtil;
    private AIIntentConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new AIIntentConfigController(aiIntentService, mock(IntentExecutorService.class),
                mock(KeywordEffectivenessService.class), mock(IntentConfigRollbackService.class),
                mock(ParameterExtractionLearningService.class), jwtUtil, configService);
        when(jwtUtil.getUserIdFromToken(TOKEN)).thenReturn(7L);
        when(jwtUtil.getRoleFromToken(TOKEN)).thenReturn("factory_operator");
    }

    private HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private AIIntentConfigController.IntentRecognitionRequest input() {
        AIIntentConfigController.IntentRecognitionRequest input =
                new AIIntentConfigController.IntentRecognitionRequest();
        input.setUserInput("查库存");
        return input;
    }

    @Test
    void factoryMetadataCallsBothRecognitionProviders() {
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        when(aiIntentService.recognizeIntentWithConfidence(anyString(), eq(FACTORY), anyInt(), eq(7L),
                eq("factory_operator"))).thenReturn(IntentMatchResult.empty("查库存"));
        when(aiIntentService.recognizeAllIntents(FACTORY, "查库存")).thenReturn(List.of());

        controller.recognizeIntent(FACTORY, input(), request());
        controller.recognizeAllIntents(FACTORY, input(), request());

        verify(aiIntentService).recognizeIntentWithConfidence(anyString(), eq(FACTORY), anyInt(), eq(7L),
                eq("factory_operator"));
        verify(aiIntentService).recognizeAllIntents(FACTORY, "查库存");
    }

    @Test
    void unknownNullOrResolverFailureIs503BeforeRecognition() {
        for (String domain : new String[]{null, "UNKNOWN"}) {
            reset(configService, aiIntentService);
            when(configService.resolveBusinessDomain(FACTORY)).thenReturn(domain);
            assertThatThrownBy(() -> controller.recognizeIntent(FACTORY, input(), request()))
                    .isInstanceOf(BusinessException.class).satisfies(error -> {
                        BusinessException e = (BusinessException) error;
                        assertThat(e.getCode()).isEqualTo(503);
                        assertThat(e.getErrorCode()).isEqualTo("TENANT_TYPE_UNAVAILABLE");
                    });
            verifyNoInteractions(aiIntentService);
        }
        reset(configService, aiIntentService);
        when(configService.resolveBusinessDomain(FACTORY)).thenThrow(new IllegalStateException("metadata down"));
        assertThatThrownBy(() -> controller.recognizeAllIntents(FACTORY, input(), request()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(503);
        verifyNoInteractions(aiIntentService);
    }

    @Test
    void restaurantRolesAndRetiredIdentityAre410BeforeRecognition() {
        for (String role : new String[]{"restaurant_owner", "restaurant_manager", "restaurant_chef", "restaurant_purchaser"}) {
            when(jwtUtil.getRoleFromToken(TOKEN)).thenReturn(role);
            assertThatThrownBy(() -> controller.recognizeIntent(FACTORY, input(), request()))
                    .isInstanceOf(BusinessException.class)
                    .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(410);
        }
        when(jwtUtil.getRoleFromToken(TOKEN)).thenReturn("factory_operator");
        assertThatThrownBy(() -> controller.recognizeAllIntents("MOCK_REST", input(), request()))
                .isInstanceOf(BusinessException.class)
                .extracting(error -> ((BusinessException) error).getCode()).isEqualTo(410);
        verifyNoInteractions(aiIntentService);
    }
}
