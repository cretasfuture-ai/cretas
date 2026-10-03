package com.cretas.aims.controller;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.service.IntentExecutorService;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AIIntentStreamLogPrivacyContractTest {
    @Test
    void streamOperationLogKeepsIdentityAndLengthWithoutQuestionOrToken() {
        String question = "private-question-marker-for-log-acceptance";
        String token = "unit-test-token-only";
        JwtUtil jwt = mock(JwtUtil.class);
        when(jwt.getUserIdFromToken(token)).thenReturn(77L);
        when(jwt.getRoleFromToken(token)).thenReturn("factory_super_admin");
        HttpServletRequest http = mock(HttpServletRequest.class);
        when(http.getHeader("Authorization")).thenReturn("Bearer " + token);
        IntentExecutorService executor = mock(IntentExecutorService.class);
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput(question).build();
        SseEmitter emitter = new SseEmitter();
        when(executor.executeStream("F006", request, 77L, "factory_super_admin")).thenReturn(emitter);
        AIIntentConfigController controller = new AIIntentConfigController(
                null, executor, null, null, null, jwt, null);
        Logger logger = (Logger) LoggerFactory.getLogger(AIIntentConfigController.class);
        ListAppender<ILoggingEvent> capture = new ListAppender<>();
        capture.start();
        logger.addAppender(capture);
        try {
            assertThat(controller.executeIntentStream("F006", request, http)).isSameAs(emitter);
            verify(executor).executeStream("F006", request, 77L, "factory_super_admin");
            assertThat(capture.list).hasSize(1);
            assertThat(capture.list.getFirst().getFormattedMessage())
                    .contains("factoryId=F006", "inputLength=" + question.length(), "userId=77", "role=factory_super_admin")
                    .doesNotContain(question, "private-question", token, "Bearer", "userInput=");
        } finally {
            logger.detachAppender(capture);
            capture.stop();
        }
    }
}
