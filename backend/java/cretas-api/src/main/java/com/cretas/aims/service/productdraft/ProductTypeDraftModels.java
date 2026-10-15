package com.cretas.aims.service.productdraft;

import java.util.List;

/** Typed wire-neutral records for {@link ProductTypeDraftCommand}. */
public final class ProductTypeDraftModels {

    public static final String SCHEMA_VERSION = "product-draft/v1";

    private ProductTypeDraftModels() {
    }

    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, java.util.Set<String> permissions) {
        public AuthenticatedOperator(long operatorId, String factoryId, java.util.Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId), factoryId, permissions);
        }
        public long operatorId() { return actor.id(); }
    }

    public record DesiredProduct(
            String code,
            String name,
            String unit,
            String productCategory) {
        public DesiredProduct {
            code = clean(code);
            name = clean(name);
            unit = clean(unit);
            productCategory = clean(productCategory);
        }
    }

    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            String targetProductTypeId,
            DesiredProduct desired,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase();
            targetProductTypeId = clean(targetProductTypeId);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase();
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record ProductSnapshot(
            String productTypeId,
            String code,
            String name,
            String unit,
            String productCategory,
            String updatedAt) {
    }

    public record DraftIssue(
            String code,
            String target,
            String message,
            String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            ProductSnapshot current,
            DesiredProduct desired,
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
            String targetProductTypeId,
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
