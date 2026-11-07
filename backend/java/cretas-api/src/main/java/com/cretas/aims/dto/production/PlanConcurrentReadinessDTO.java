package com.cretas.aims.dto.production;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Read-only material feasibility and independently measured finished-goods receipt progress. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record PlanConcurrentReadinessDTO(
        String planId, String planNumber, String planStatus, LocalDate demandThroughDate,
        LocalDateTime observedAt, String cohortBasis, int consideredPlanCount, int unverifiedPlanCount,
        List<String> notes, MaterialCoverage material, Progress progress, boolean advisoryOnly) {
    public PlanConcurrentReadinessDTO { notes = List.copyOf(notes); }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record MaterialCoverage(String status, String reason, BigDecimal lowerPercent,
            BigDecimal upperPercent, List<Material> items) {
        public MaterialCoverage { items = List.copyOf(items); }
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Material(String materialTypeId, String materialName, String unit,
            BigDecimal required, BigDecimal consumed, BigDecimal ownHeldReserved,
            BigDecimal otherUnoccupied, BigDecimal stockFree, BigDecimal lowerPercent, BigDecimal upperPercent) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Progress(String status, String basis, BigDecimal plannedQuantity,
            BigDecimal receivedQuantity, BigDecimal remainingQuantity, BigDecimal completionPercent,
            String unit, String reason) { }
}
