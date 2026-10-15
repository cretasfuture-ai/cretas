package com.cretas.aims.service.bomdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class BomRecipeDraftModels {
    public static final String SCHEMA_VERSION = "bom-draft/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CONTRACT:products+materials+workflow+packaging";

    private BomRecipeDraftModels() {}

    public enum ProductRefType { LIVE_PRODUCT, PRODUCT_DRAFT }
    public enum MaterialRefType { LIVE_MATERIAL, MATERIAL_DRAFT }
    public enum MaterialCategory { RAW, AUXILIARY, PACKAGING, BYPRODUCT }
    public enum PortDirection { INPUT, OUTPUT }
    public enum PackagingDependencyType { NONE, PACKAGING_DRAFT }
    /** conversionRatio means: one primary BOM unit requires N candidate catalog units. */
    public enum SubstituteRatioBasis { PRIMARY_UNIT_TO_CANDIDATE_UNIT }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ProductRef(ProductRefType type, String refId, String snapshotDigest) {
        public ProductRef {
            refId = clean(refId);
            snapshotDigest = digestValue(snapshotDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MaterialRef(MaterialRefType type, String refId, String snapshotDigest) {
        public MaterialRef {
            refId = clean(refId);
            snapshotDigest = digestValue(snapshotDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record WorkflowPin(long workflowId, long revisionId, String revisionHash,
                              int definitionVersion, int schemaVersion) {
        public WorkflowPin { revisionHash = digestValue(revisionHash); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PackagingPin(PackagingDependencyType type, String draftId, String desiredDigest) {
        public PackagingPin {
            type = type == null ? PackagingDependencyType.NONE : type;
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SubstituteCandidate(MaterialRef material, int priority,
                                      SubstituteRatioBasis conversionBasis,
                                      BigDecimal conversionRatio,
                                      BigDecimal maxSharePercent, String reason) {
        public SubstituteCandidate { reason = clean(reason); }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BomItem(
            MaterialRef material,
            MaterialCategory category,
            BigDecimal standardQuantity,
            String unit,
            BigDecimal yieldPercent,
            String priceUnit,
            BigDecimal quantityToPriceFactor,
            boolean optional,
            String workflowMaterialNodeId,
            String workflowProcessNodeId,
            String workflowPortId,
            String workflowEdgeId,
            PortDirection portDirection,
            List<SubstituteCandidate> substitutes,
            String changeReason) {
        public BomItem {
            unit = clean(unit);
            priceUnit = clean(priceUnit);
            workflowMaterialNodeId = clean(workflowMaterialNodeId);
            workflowProcessNodeId = clean(workflowProcessNodeId);
            workflowPortId = clean(workflowPortId);
            workflowEdgeId = clean(workflowEdgeId);
            substitutes = immutable(substitutes);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            ProductRef product,
            WorkflowPin workflow,
            PackagingPin packaging,
            BigDecimal outputQuantity,
            String outputUnit,
            BigDecimal netContentQuantity,
            String netContentUnit,
            List<BomItem> items,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = digestValue(sourceDigest);
            packaging = packaging == null
                    ? new PackagingPin(PackagingDependencyType.NONE, "", "") : packaging;
            outputUnit = clean(outputUnit);
            netContentUnit = clean(netContentUnit);
            items = immutable(items);
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

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            DraftRequest desired,
            String sourceSnapshotDigest,
            String workflowSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedModuleDraftWriteCount,
            int expectedPersistedRowCount,
            int liveBomWriteCount,
            int workflowWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int costWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("products", "materials", "workflow", "packaging");
            issues = List.copyOf(issues);
            int substitutes = desired == null ? 0 : desired.items().stream()
                    .filter(java.util.Objects::nonNull).mapToInt(item -> item.substitutes().size()).sum();
            expectedModuleDraftWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 1 + desired.items().size() + substitutes : 0;
            liveBomWriteCount = 0;
            workflowWriteCount = 0;
            inventoryWriteCount = 0;
            productionWriteCount = 0;
            costWriteCount = 0;
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
            String workflowSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int itemCount,
            int substituteCount,
            long lockVersion,
            boolean replayed,
            int moduleDraftWriteCount,
            int originalModuleDraftWriteCount,
            int persistedRowCount,
            int liveBomWriteCount,
            int workflowWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int costWriteCount,
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
            int liveBomWriteCount,
            int workflowWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int costWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            liveBomWriteCount = 0;
            workflowWriteCount = 0;
            inventoryWriteCount = 0;
            productionWriteCount = 0;
            costWriteCount = 0;
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
