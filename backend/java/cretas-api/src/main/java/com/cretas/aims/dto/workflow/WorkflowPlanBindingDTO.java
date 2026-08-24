package com.cretas.aims.dto.workflow;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A production plan currently pinned to one concrete Workflow row/version. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowPlanBindingDTO {
    private String planId;
    private String planNumber;
    private String status;
    private boolean productionStarted;
}
