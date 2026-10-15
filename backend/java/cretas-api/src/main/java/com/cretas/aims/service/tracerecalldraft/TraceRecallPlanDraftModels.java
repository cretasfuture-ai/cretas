package com.cretas.aims.service.tracerecalldraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Closed typed contract for a Blueprint-owned trace/recall preparedness plan. */
public final class TraceRecallPlanDraftModels {
    public static final String SCHEMA_VERSION = "trace-recall-plan-draft/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CATALOG:routes+bom+warehouses+quality-release;SAFETY_PIN:deviation-capa";
    public static final String PLAN_ALGORITHM_VERSION = "trace-recall-plan-policy/v1";

    private TraceRecallPlanDraftModels() {}

    public enum TraceDirection { UPSTREAM, DOWNSTREAM, BIDIRECTIONAL }
    public enum SourceType { ROUTE_NODE, BOM_ITEM, WAREHOUSE, QUALITY_RELEASE_SPEC, DEVIATION_RULE }
    public enum Stage {
        SUPPLIER_RECEIPT, MATERIAL_STORAGE, PRODUCTION_TRANSFORMATION,
        FINISHED_GOODS_STORAGE, CUSTOMER_DISPATCH, QUALITY_DISPOSITION
    }
    public enum IdentifierType {
        SUPPLIER_LOT, MATERIAL_BATCH, PRODUCTION_BATCH, FINISHED_LOT,
        SHIPMENT_REFERENCE, QUALITY_DECISION_REFERENCE
    }
    public enum EscalationRole {
        QUALITY_OWNER, PRODUCTION_OWNER, WAREHOUSE_OWNER, FACTORY_OWNER
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftPin(String draftId, String desiredDigest) {
        public DraftPin {
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PlanPolicy(
            TraceDirection direction,
            int maxTraversalDepth,
            int maxTraversalNodes,
            int traceCompletionMinutes,
            int massBalanceToleranceBasisPoints,
            int mockRecallFrequencyDays,
            int evidenceRetentionDays,
            List<EscalationRole> escalationRoles,
            String changeReason) {
        public PlanPolicy {
            escalationRoles = immutable(escalationRoles);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            DraftPin deviationCapa,
            DraftPin qualityRelease,
            DraftPin bom,
            DraftPin warehouse,
            PlanPolicy policy,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = digestValue(sourceDigest);
            confirmedPreviewDigest = digestValue(confirmedPreviewDigest);
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, Set<String> permissions) {
        public AuthenticatedOperator {
            factoryId = clean(factoryId);
            permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        }

        public AuthenticatedOperator(long operatorId, String factoryId, Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(
                    com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId),
                    factoryId, permissions);
        }

        /** Legacy accessor retained for request compatibility; identity is actor-typed. */
        public long operatorId() {
            return actor.id();
        }
    }

    public record DraftIssue(String code, String target, String message, String recovery) {}

    public record DerivedSegment(
            int ordinal,
            SourceType sourceType,
            String sourceRefId,
            String sourceSnapshotDigest,
            Stage stage,
            TraceDirection direction,
            IdentifierType identifierType,
            boolean requiredLink,
            boolean quantityCaptureRequired,
            boolean evidenceRequired,
            int completionSlaMinutes,
            int evidenceRetentionDays,
            String segmentKey) {}

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            DraftRequest desired,
            List<DerivedSegment> segments,
            String sourceSnapshotDigest,
            String routeSnapshotDigest,
            String bomSnapshotDigest,
            String warehouseSnapshotDigest,
            String qualityReleaseSnapshotDigest,
            String deviationCapaSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedModuleDraftWriteCount,
            int expectedPersistedRowCount,
            int lineageQueryWriteCount,
            int recallEventWriteCount,
            int recallActionWriteCount,
            int inventoryBatchFreezeWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("routes", "bom", "warehouses", "quality-release");
            segments = segments == null ? List.of() : List.copyOf(segments);
            issues = issues == null ? List.of() : List.copyOf(issues);
            int authorityCount = desired == null ? 0 : 1;
            expectedModuleDraftWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 1 + segments.size() + authorityCount : 0;
            lineageQueryWriteCount = 0;
            recallEventWriteCount = 0;
            recallActionWriteCount = 0;
            inventoryBatchFreezeWriteCount = 0;
            alertNotificationWriteCount = 0;
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
            String sourceSnapshotDigest,
            String routeSnapshotDigest,
            String bomSnapshotDigest,
            String warehouseSnapshotDigest,
            String qualityReleaseSnapshotDigest,
            String deviationCapaSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int segmentCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int moduleDraftWriteCount,
            int originalModuleDraftWriteCount,
            int persistedRowCount,
            int lineageQueryWriteCount,
            int recallEventWriteCount,
            int recallActionWriteCount,
            int inventoryBatchFreezeWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            DraftRequest desired,
            List<DerivedSegment> segments,
            int lineageQueryWriteCount,
            int recallEventWriteCount,
            int recallActionWriteCount,
            int inventoryBatchFreezeWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            segments = segments == null ? List.of() : List.copyOf(segments);
            lineageQueryWriteCount = 0;
            recallEventWriteCount = 0;
            recallActionWriteCount = 0;
            inventoryBatchFreezeWriteCount = 0;
            alertNotificationWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record RecoveryPlan(String schemaVersion, String factoryId, String draftId,
                               String expectedDesiredDigest, String action, String effect,
                               String publishState, String activationState) {
        public RecoveryPlan {
            schemaVersion = SCHEMA_VERSION;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String digestValue(String value) { return clean(value).toLowerCase(Locale.ROOT); }
}
