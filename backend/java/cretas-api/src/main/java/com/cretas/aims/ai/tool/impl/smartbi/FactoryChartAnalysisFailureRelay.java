package com.cretas.aims.ai.tool.impl.smartbi;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;

/** Request-scoped bridge for typed Python business failures across the legacy Tool envelope. */
@Component
@RequestScope
public class FactoryChartAnalysisFailureRelay {

    private Failure failure;

    public void record(String requestId, int statusCode, String message) {
        if (failure == null && requestId != null && !requestId.isBlank()) {
            failure = new Failure(requestId, statusCode, message);
        }
    }

    public Optional<Failure> consume(String requestId) {
        if (failure == null || !failure.requestId().equals(requestId)) {
            return Optional.empty();
        }
        Failure value = failure;
        failure = null;
        return Optional.of(value);
    }

    public void clear(String requestId) {
        if (failure != null && failure.requestId().equals(requestId)) {
            failure = null;
        }
    }

    public record Failure(String requestId, int statusCode, String message) {
    }
}
