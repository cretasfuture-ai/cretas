package com.cretas.aims.dto.scheduling;

import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.math.BigDecimal;

/** Read-only scheduling preflight. It never creates a plan or prediction row. */
@Value
@Builder
public class SchedulingReadinessResponse {
    String status;
    String requestMode;
    String scheduleId;
    boolean readyForAutomation;
    String reason;
    ResourceReference productionLine;
    ResourceReference batch;
    TimeWindow timeWindow;
    ProbabilityProvenance probability;
    List<String> missingInputs;

    @Value
    @Builder
    public static class ResourceReference {
        String id;
        boolean selected;
        boolean confirmed;
        String source;
        String reason;
    }

    @Value
    @Builder
    public static class TimeWindow {
        String start;
        String end;
        boolean valid;
        String reason;
    }

    @Value
    @Builder
    public static class ProbabilityProvenance {
        String origin;
        String reason;
        String predictionMode;
        String modelVersion;
        BigDecimal value;
        boolean optimization;
    }
}
