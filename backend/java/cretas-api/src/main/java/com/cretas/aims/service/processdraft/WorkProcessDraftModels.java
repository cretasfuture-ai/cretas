package com.cretas.aims.service.processdraft;

import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;

import java.math.BigDecimal;
import java.util.List;

/** Typed wire-neutral records for {@link WorkProcessDraftCommand}. */
public final class WorkProcessDraftModels {

    public static final String SCHEMA_VERSION = "work-process-draft/v1";

    private WorkProcessDraftModels() {
    }

    /** Only confirmed process-master facts belong here; route order and units are intentionally absent. */
    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, java.util.Set<String> permissions) {
        public AuthenticatedOperator(long operatorId, String factoryId, java.util.Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId), factoryId, permissions);
        }
        public long operatorId() { return actor.id(); }
    }

    public record RequestedProcess(
            String name,
            String category,
            boolean createCategory,
            String description,
            Integer estimatedMinutes,
            Boolean needsInput,
            WorkProcessOutputMaterialKind defaultOutputMaterialKind,
            BigDecimal standardYieldMin,
            BigDecimal standardYieldMax) {
        public RequestedProcess {
            name = clean(name);
            category = clean(category);
            description = cleanNullable(description);
        }
    }

    public record ResolvedProcess(
            String naturalKey,
            String name,
            String category,
            String categoryResolution,
            boolean createCategory,
            String description,
            Integer estimatedMinutes,
            Boolean needsInput,
            WorkProcessOutputMaterialKind defaultOutputMaterialKind,
            BigDecimal standardYieldMin,
            BigDecimal standardYieldMax) {
    }

    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            String targetWorkProcessId,
            RequestedProcess desired,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase();
            targetWorkProcessId = clean(targetWorkProcessId);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase();
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record ProcessSnapshot(
            String workProcessId,
            String name,
            String category,
            String description,
            Integer estimatedMinutes,
            boolean needsInput,
            String defaultOutputMaterialKind,
            BigDecimal standardYieldMin,
            BigDecimal standardYieldMax,
            boolean active,
            String mergedIntoId,
            long lockVersion,
            String updatedAt) {
    }

    public record DraftIssue(String code, String target, String message, String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            ProcessSnapshot current,
            ResolvedProcess desired,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReceipt(
            String schemaVersion,
            String receiptId,
            String factoryId,
            String draftId,
            String status,
            String naturalKey,
            String targetWorkProcessId,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record RecoveryPlan(
            String schemaVersion,
            String factoryId,
            String draftId,
            String expectedDesiredDigest,
            String action,
            String effect,
            String publishState,
            String activationState) {
        public RecoveryPlan {
            schemaVersion = SCHEMA_VERSION;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static String cleanNullable(String value) {
        String cleaned = clean(value);
        return cleaned.isEmpty() ? null : cleaned;
    }
}
