package com.cretas.aims.dto.scheduling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Explicit scheduling capability contract. Unsupported automation must remain unavailable. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchedulingCapabilitiesDTO {
    private String contractVersion;
    private Capability aiGeneration;
    private Capability workerOptimization;
    private Capability reschedule;
    private Capability manualCreation;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Capability {
        private String status;
        private String mode;
        private String reason;
    }
}
