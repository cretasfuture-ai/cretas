package com.cretas.aims.blueprint.canonical;

import com.cretas.aims.controller.dto.indicator.IndicatorApplicabilityResponse;
import java.time.LocalDateTime;
import java.util.List;

/** A read-only delivery view. Configuration acceptance never implies business sign-off. */
public record FactoryReadinessReport(
        String schemaVersion, String factoryId, String blueprintId, Integer blueprintVersion,
        Long rowVersion, String lifecycleState, List<Capability> capabilities,
        List<ProductRuntime> currentRuntime, AppliedVersion appliedVersion,
        IndicatorApplicabilityResponse plannedIndicators,
        IndicatorApplicabilityResponse currentIndicators,
        String businessAcceptanceState, int businessWriteCount, LocalDateTime readAt) {
    public record Capability(String id, String label, String configurationState,
            String acceptanceState, String stage, String nextStep, int receiptCount) { }
    public record ProductRuntime(String productId, String productName, String state,
            Long workflowId, Integer workflowVersion, String bomId, Integer bomVersion,
            String reason) { }
    public record AppliedVersion(String blueprintId, Integer version, boolean candidateDiffers,
            String boundary) { }
}
