package com.cretas.aims.audit;

import com.cretas.aims.filter.CorrelationIdFilter;
import com.cretas.aims.dto.common.ApiResponse;
import jakarta.servlet.DispatcherType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import java.util.concurrent.Callable;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class RequestAuditAsyncDispatchTest {
    @Test void productionRegistrationIncludesCompletionDispatchers() {
        var registration = new RequestAuditFilterConfiguration().correlationAuditRegistration(new CorrelationIdFilter());
        assertThat(ReflectionTestUtils.getField(registration, "dispatcherTypes"))
                .isEqualTo(java.util.EnumSet.of(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR));
        assertThat(registration.isAsyncSupported()).isTrue();
    }

    @Test void actualAsyncSuccessAndFailureRetainCorrelationAndCaptureFinalOutcome() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new AsyncController())
                .setControllerAdvice(new RequestAuditResponseAdvice(), new FailureAdvice())
                .addFilters(new CorrelationIdFilter()).build();
        for (String endpoint : new String[]{"ok", "fail"}) {
            var initial = mvc.perform(get("/api/mobile/F006/async/" + endpoint))
                    .andExpect(request().asyncStarted()).andReturn();
            String correlation = initial.getResponse().getHeader("X-Correlation-ID");
            var completed = mvc.perform(asyncDispatch(initial))
                    .andExpect(status().is("ok".equals(endpoint) ? 200 : 500))
                    .andExpect(header().string("X-Correlation-ID", correlation)).andReturn();
            assertThat(completed.getRequest().getAttribute(RequestAuditSupport.RESPONSE_SUCCESS_ATTRIBUTE))
                    .isEqualTo("ok".equals(endpoint));
            assertThat(org.slf4j.MDC.get("correlationId")).isNull();
        }
    }

    @RestController static class AsyncController {
        @GetMapping("/api/mobile/F006/async/ok")
        Callable<ApiResponse<String>> ok() { return () -> ApiResponse.success("done"); }
        @GetMapping("/api/mobile/F006/async/fail")
        Callable<ApiResponse<String>> fail() { return () -> { throw new IllegalStateException("failed"); }; }
    }
    @RestControllerAdvice static class FailureAdvice {
        @ExceptionHandler(IllegalStateException.class)
        ResponseEntity<ApiResponse<String>> failed() { return ResponseEntity.status(500).body(ApiResponse.error("failed")); }
    }
}
