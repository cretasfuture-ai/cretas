package com.cretas.aims.service.packagingdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PackagingDraftModels {
    public static final String SCHEMA_VERSION = "packaging-draft/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CONTRACT:products+materials";

    private PackagingDraftModels() {}

    public enum TargetKind { PRODUCT, PRODUCT_DRAFT, MATERIAL, MATERIAL_DRAFT }
    public enum PackagingRole { PRIMARY_CONTAINER, SECONDARY_CASE, TERTIARY_PALLET }
    public enum LabelReferenceType { NONE, CURRENT_TARGET_SNAPSHOT }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TargetRef(TargetKind kind, String targetId, String targetSnapshotDigest) {
        public TargetRef {
            targetId = clean(targetId);
            targetSnapshotDigest = clean(targetSnapshotDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record LabelReference(LabelReferenceType type, String referenceId, String referenceDigest) {
        public LabelReference {
            referenceId = clean(referenceId);
            referenceDigest = clean(referenceDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PackagingItem(
            TargetRef target,
            PackagingRole role,
            String specName,
            String packageUnit,
            String containedUnit,
            BigDecimal unitsPerPackage,
            BigDecimal netContentQuantity,
            String netContentUnit,
            boolean defaultSpec,
            int sortOrder,
            LabelReference labelReference,
            String changeReason) {
        public PackagingItem {
            specName = clean(specName);
            packageUnit = clean(packageUnit);
            containedUnit = clean(containedUnit);
            netContentUnit = clean(netContentUnit);
            labelReference = labelReference == null
                    ? new LabelReference(LabelReferenceType.NONE, "", "") : labelReference;
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            List<PackagingItem> items,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            items = items == null ? List.of()
                    : java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase(Locale.ROOT);
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

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            List<PackagingItem> items,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int productWriteCount,
            int materialWriteCount,
            int labelPublishCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int bomWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("products", "materials");
            items = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(items));
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 1 + items.size() : 0;
            productWriteCount = 0;
            materialWriteCount = 0;
            labelPublishCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            bomWriteCount = 0;
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
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int itemCount,
            int targetCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int productWriteCount,
            int materialWriteCount,
            int labelPublishCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int bomWriteCount,
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
            List<PackagingItem> items,
            int productWriteCount,
            int materialWriteCount,
            int labelPublishCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int bomWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            items = List.copyOf(items);
            productWriteCount = 0;
            materialWriteCount = 0;
            labelPublishCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            bomWriteCount = 0;
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

    static String clean(String value) { return value == null ? "" : value.trim(); }
}
