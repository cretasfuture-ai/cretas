package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** /execute/multi 的两个公开入口也必须先过个人工资拒答守卫 (评审 R-M1): 不识别、不执行任何子意图。 */
@ExtendWith(MockitoExtension.class)
class MultiIntentPersonalPayRefusalContractTest {
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
        lenient().when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
    }

    private IntentExecuteRequest request(String text) {
        IntentExecuteRequest r = new IntentExecuteRequest();
        r.setUserInput(text);
        return r;
    }

    @Test
    void multiIntentEntryRefusesPersonalPayBeforeRecognitionAndExecution() {
        IntentExecuteResponse r = service.executeMultiIntent(FACTORY, request("每个工人的工资多少，本月人工成本多少"), 7L, "factory_operator");
        assertThat(r.getStatus()).isEqualTo("PERSONAL_PAY_REFUSED");
        verifyNoInteractions(aiIntentService, orchestrator);
    }

    @Test
    void matchResultEntryRefusesPersonalPayToo() {
        IntentExecuteResponse r = service.executeMultiIntentFromMatchResult(request("每位员工的工资是多少"),
                IntentMatchResult.builder().additionalIntents(List.of()).build(), FACTORY, 7L, "factory_operator");
        assertThat(r.getStatus()).isEqualTo("PERSONAL_PAY_REFUSED");
        verifyNoInteractions(aiIntentService, orchestrator);
    }

    @Test
    void negativeControl_ordinaryQuestionStillReachesTheOrchestrator() {
        when(aiIntentService.recognizeMultiIntent(anyString(), anyString())).thenReturn(null);
        when(orchestrator.execute(anyString(), any(), any(), anyString()))
                .thenReturn(IntentExecuteResponse.builder().status("SUCCESS").build());
        IntentExecuteResponse r = service.executeMultiIntent(FACTORY, request("本月人工成本多少"), 7L, "factory_operator");
        assertThat(r.getStatus()).isEqualTo("SUCCESS");
        verify(orchestrator).execute(anyString(), any(), any(), anyString());
    }
}
