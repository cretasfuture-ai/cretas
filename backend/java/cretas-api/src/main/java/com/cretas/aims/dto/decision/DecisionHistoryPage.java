package com.cretas.aims.dto.decision;

import com.cretas.aims.entity.DecisionAuditLog.DecisionType;

import java.time.LocalDateTime;
import java.util.List;

/** Discoverable audit references, excluding raw snapshots and actor/private business data. */
public record DecisionHistoryPage(List<Entry> records, int page, int pageSize,
                                  long totalElements, int totalPages) {
    public DecisionHistoryPage {
        records = List.copyOf(records);
    }

    public record Entry(String id, DecisionType decisionType, String decisionCode,
                        String entityType, String entityId, String ruleConfigId,
                        Integer ruleConfigVersion, LocalDateTime createdAt) { }
}
