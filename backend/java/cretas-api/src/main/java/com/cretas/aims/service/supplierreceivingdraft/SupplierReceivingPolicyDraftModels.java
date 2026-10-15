package com.cretas.aims.service.supplierreceivingdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

public final class SupplierReceivingPolicyDraftModels {
    private SupplierReceivingPolicyDraftModels() {}

    public static final String SCHEMA_VERSION = "supplier-receiving-template/v1";
    public static final String DEPENDENCY_BASIS = "supplier-receiving -> materials";

    public enum MaterialRefType { LIVE_MATERIAL, MATERIAL_DRAFT }
    public enum AcceptanceMode { STANDARD, HIGH_RISK, NEW_SUPPLIER }
    public enum QualificationExpiryPolicy { VALID_ONLY, VALID_OR_EXPIRING_WITH_HOLD }
    public enum QualificationType {
        BUSINESS_LICENSE, SC_LICENSE, IMPORT_CERT, HACCP_CERT, ISO22000, FSSC22000, THIRD_PARTY_TEST, OTHER
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, Set<String> permissions) {
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

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            long operatorId,
            String idempotencyKey,
            String confirmedPreviewDigest,
            List<RequestedPolicy> policies) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RequestedPolicy(
            String supplierId,
            MaterialRefType materialRefType,
            String materialRefId,
            AcceptanceMode acceptanceMode,
            QualificationExpiryPolicy qualificationExpiryPolicy,
            BigDecimal samplePercentage,
            int minSampleSize,
            int maxSampleSize,
            boolean quarantineOnFailure,
            boolean receivingEvidenceRequired,
            String policyBasis,
            List<QualificationType> requiredQualifications) {}

    public record StoredQualification(QualificationType qualificationType, String currentEvidenceDigest) {}

    public record ResolvedPolicy(
            String supplierId,
            MaterialRefType materialRefType,
            String materialRefId,
            String materialSnapshotDigest,
            String supplierSnapshotDigest,
            String relationshipSnapshotDigest,
            AcceptanceMode acceptanceMode,
            QualificationExpiryPolicy qualificationExpiryPolicy,
            BigDecimal samplePercentage,
            int minSampleSize,
            int maxSampleSize,
            boolean quarantineOnFailure,
            boolean receivingEvidenceRequired,
            String policyBasis,
            List<StoredQualification> requiredQualifications) {}

    public record DraftIssue(String code, String target, String message, String recovery) {}

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            List<ResolvedPolicy> policies,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedDraftWrites,
            int expectedPurchaseOrderWrites,
            int expectedReceivingWrites,
            int expectedInventoryWrites,
            int expectedQualificationWrites,
            String publishState,
            String activationState) {}

    public record DraftReceipt(
            String schemaVersion,
            String draftId,
            String factoryId,
            String status,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int policyCount,
            int qualificationCount,
            long lockVersion,
            boolean replayed,
            int draftBusinessWrites,
            int parentRows,
            int persistedRows,
            int purchaseOrderWrites,
            int receivingWrites,
            int inventoryWrites,
            int supplierWrites,
            int qualificationWrites,
            String publishState,
            String activationState) {}

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            String desiredDigest,
            List<ResolvedPolicy> policies,
            int purchaseOrderWrites,
            int receivingWrites,
            int inventoryWrites,
            int supplierWrites,
            int qualificationWrites,
            String publishState,
            String activationState) {}

    public record RecoveryPlan(
            String schemaVersion,
            String factoryId,
            String draftId,
            String desiredDigest,
            String action,
            String impact,
            String requiredConfirmation,
            int expectedProductionWrites) {}
}
