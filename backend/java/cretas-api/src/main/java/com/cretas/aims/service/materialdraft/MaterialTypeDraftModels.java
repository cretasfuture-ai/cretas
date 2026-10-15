package com.cretas.aims.service.materialdraft;

import java.util.List;

/** Typed wire-neutral records for {@link MaterialTypeDraftCommand}. */
public final class MaterialTypeDraftModels {

    public static final String SCHEMA_VERSION = "material-draft/v1";

    private MaterialTypeDraftModels() {
    }

    /** Canonical storage intent; packaging categories intentionally use {@code null}. */
    public enum StorageMode { FROZEN, REFRIGERATED, AMBIENT }

    /**
     * Only confirmed user facts enter the request. Category labels are resolved server-side.
     *
     * @param classificationPackagingCategory only for a <b>planned</b> L1 (a 3-digit code that is not in
     *        the factory's segment table yet but is in the current blueprint's {@code codeSegments}, created
     *        when the first material under it is promoted): whether that category gets packaging semantics.
     *        The blueprint has no such fact, so the user answers it. For a category that already exists the
     *        table decides; the value may then be left empty, and a value that disagrees with the table is
     *        refused rather than ignored.
     */
    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, java.util.Set<String> permissions) {
        public AuthenticatedOperator(long operatorId, String factoryId, java.util.Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId), factoryId, permissions);
        }
        public long operatorId() { return actor.id(); }
    }

    public record RequestedMaterial(
            String name,
            String classificationSegmentCode,
            String unit,
            StorageMode storageMode,
            Integer shelfLifeDays,
            boolean byproduct,
            Boolean classificationPackagingCategory) {
        public RequestedMaterial {
            name = clean(name);
            classificationSegmentCode = clean(classificationSegmentCode);
            unit = clean(unit);
        }

        /** A request without a planned-category answer (every category comes from the segment table). */
        public RequestedMaterial(String name, String classificationSegmentCode, String unit, StorageMode storageMode,
                                 Integer shelfLifeDays, boolean byproduct) {
            this(name, classificationSegmentCode, unit, storageMode, shelfLifeDays, byproduct, null);
        }
    }

    /** Where the resolved L1 comes from. */
    public static final String CLASSIFICATION_SOURCE_FACTORY = "FACTORY";
    /** Not in the segment table yet: taken from the current blueprint, created on promotion. */
    public static final String CLASSIFICATION_SOURCE_BLUEPRINT_PLANNED = "BLUEPRINT_PLANNED";

    /**
     * @param classificationSource {@link #CLASSIFICATION_SOURCE_FACTORY}, {@link #CLASSIFICATION_SOURCE_BLUEPRINT_PLANNED},
     *                             or empty when the classification did not resolve
     */
    public record ResolvedMaterial(
            String naturalKey,
            String name,
            String classificationSegmentCode,
            String primaryCategoryCode,
            String category,
            String unit,
            StorageMode storageMode,
            Integer shelfLifeDays,
            boolean byproduct,
            boolean packagingCategory,
            String classificationSource) {
    }

    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            String targetMaterialTypeId,
            RequestedMaterial desired,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase();
            targetMaterialTypeId = clean(targetMaterialTypeId);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase();
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record MaterialSnapshot(
            String materialTypeId,
            String code,
            String businessCode,
            String name,
            String primaryCategoryCode,
            String category,
            String unit,
            String storageType,
            Integer shelfLifeDays,
            boolean byproduct,
            String updatedAt) {
    }

    public record DraftIssue(String code, String target, String message, String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            MaterialSnapshot current,
            ResolvedMaterial desired,
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
            String targetMaterialTypeId,
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
}
