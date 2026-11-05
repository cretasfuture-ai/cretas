package com.cretas.aims.dto.shortage;

import com.cretas.aims.entity.shortage.ProcessParamDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Safe, source-specific view of a process-parameter deviation case. */
public final class ProcessParamDeviationCaseDTO {

    private ProcessParamDeviationCaseDTO() {
    }

    public record CaseView(String id, String sourceKey, String sourceKind, String processName, String batchNumber,
                           String paramKey, String paramLabel,
                           String unit, String status, String stage, String stageLabel, String ownerRole,
                           Long ownerUserId, String ownerName, long version, LocalDateTime observedAt, LocalDateTime closedAt,
                           LocalDateTime createdAt, LocalDateTime lastRefreshedAt) {
    }

    public record DetailView(CaseView caseView, Long sourceReportId, Long productionBatchId, String productionPlanId,
                             Long workProcessTaskId, String processEntryStepKey, String productTypeId,
                             BigDecimal measuredValue, BigDecimal standardValue, BigDecimal rangeMin,
                             BigDecimal rangeMax, String resolutionCode, String resolutionNote,
                             Long resolvedBy, LocalDateTime resolvedAt) {
    }

    public static CaseView view(ShortageCase c, ProcessParamDeviationCaseDetail d, String ownerName) {
        return new CaseView(c.getId(), d.getSourceKey(), d.getSourceKind().name(), d.getProcessName(), d.getBatchNumber(),
                d.getParamKey(), d.getParamLabel(),
                d.getUnit(), d.getStatus(), c.getStage().name(), c.getStage().label(), c.getOwnerRole(),
                c.getOwnerUserId(), ownerName, c.getVersion(), d.getObservedAt(), c.getClosedAt(), c.getCreatedAt(),
                c.getLastRefreshedAt());
    }

    public static DetailView detail(ShortageCase c, ProcessParamDeviationCaseDetail d, String ownerName) {
        return new DetailView(view(c, d, ownerName), d.getSourceReportId(), d.getProductionBatchId(), d.getProductionPlanId(), d.getWorkProcessTaskId(),
                d.getProcessEntryStepKey(), d.getProductTypeId(), d.getMeasuredValue(), d.getStandardValue(),
                d.getRangeMin(), d.getRangeMax(), d.getResolutionCode() == null ? null : d.getResolutionCode().name(),
                d.getResolutionNote(), d.getResolvedBy(), d.getResolvedAt());
    }
}
