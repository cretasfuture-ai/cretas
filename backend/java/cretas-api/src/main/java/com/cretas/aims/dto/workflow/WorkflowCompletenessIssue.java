package com.cretas.aims.dto.workflow;

/** A publish check or advisory, with the canvas node and optional port to locate. */
public record WorkflowCompletenessIssue(
        String code,
        String nodeId,
        String portId,
        String message,
        String action) {
}
