package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Result of moving zero-report production plans to the newly active Workflow version. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPlanRebindSummaryDTO {
    private int reboundPlanCount;
    private int preservedStartedPlanCount;
    @Builder.Default
    private List<String> reboundPlanNumbers = List.of();
    @Builder.Default
    private List<String> preservedPlanNumbers = List.of();

    public static WorkflowPlanRebindSummaryDTO empty() {
        return WorkflowPlanRebindSummaryDTO.builder().build();
    }
}
