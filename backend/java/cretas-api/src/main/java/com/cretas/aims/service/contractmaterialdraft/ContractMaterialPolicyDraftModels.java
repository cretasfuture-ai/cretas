package com.cretas.aims.service.contractmaterialdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for customer-supplied / contract-manufacturing material policy
 * DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares
 * {@code contract-material -> materials + supplier-receiving}. Every policy row pins one resolved
 * policy of a same-lineage, readback-ready B1 supplier-receiving DRAFT and inherits its material
 * identity from there; this command never resolves material master data on its own.</p>
 *
 * <p>{@code customerScopeCode} is an operator-entered contract scope label. The customer module is
 * NOT in this dependency closure, so the code is deliberately not resolved against any customer
 * master record and no customer row is ever read or written.</p>
 *
 * <p>There is no field through which a client or an AI can create a purchase order, a receiving
 * document, an inventory or batch movement, an accounts-payable entry, or a formal material
 * record.</p>
 */
public final class ContractMaterialPolicyDraftModels {
    public static final String SCHEMA_VERSION = "contract-material-policy-draft/v1";
    public static final String ALGORITHM_VERSION = "contract-material-policy/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CATALOG:materials+supplier-receiving";

    private ContractMaterialPolicyDraftModels() {
    }

    public enum OwnershipType { CUSTOMER_OWNED, FACTORY_OWNED_FOR_CONTRACT }

    public enum SegregationRequirement {
        DEDICATED_WAREHOUSE, DEDICATED_LOCATION, LABELLED_SHARED_LOCATION
    }

    public enum IssuancePolicy {
        CONTRACT_ORDER_ONLY, CONTRACT_SCOPE_ONLY
    }

    public enum ReturnPolicy {
        RETURN_TO_CUSTOMER, SCRAP_WITH_CUSTOMER_APPROVAL, RETAIN_FOR_NEXT_CONTRACT_ORDER
    }

    public enum SettlementPolicy {
        NO_SETTLEMENT_CUSTOMER_OWNED, PROCESSING_FEE_ONLY, FULL_MATERIAL_AND_PROCESSING
    }

    public enum ResponsibleRole {
        WAREHOUSE_SUPERVISOR, QUALITY_INSPECTOR, PRODUCTION_SUPERVISOR, FINANCE_CONTROLLER
    }

    public enum EvidenceType {
        CHECKLIST, PHOTO, CUSTOMER_APPROVAL, QUALITY_SIGNOFF, WEIGHT_RECORD
    }

    /** Server-derived; never accepted from the request. */
    public enum AccountingTreatment { CUSTOMER_ASSET_OFF_BOOK, FACTORY_ASSET_ON_BOOK }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SupplierReceivingPin(String draftId, String desiredDigest) {
        public SupplierReceivingPin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    /** Points at one resolved policy of the pinned B1 draft. */
    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SupplierMaterialRef(String supplierId, String materialRefType,
                                      String materialRefId) {
        public SupplierMaterialRef {
            supplierId = clean(supplierId);
            materialRefType = clean(materialRefType).toUpperCase(Locale.ROOT);
            materialRefId = clean(materialRefId);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PolicyInput(
            String customerScopeCode,
            SupplierMaterialRef supplierMaterial,
            OwnershipType ownershipType,
            SegregationRequirement segregationRequirement,
            IssuancePolicy issuancePolicy,
            ReturnPolicy returnPolicy,
            SettlementPolicy settlementPolicy,
            BigDecimal residualTolerancePercent,
            Integer retentionDays,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            String changeReason) {
        public PolicyInput {
            customerScopeCode = clean(customerScopeCode).toUpperCase(Locale.ROOT);
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedPolicy(
            int ordinal,
            String policyCode,
            String customerScopeCode,
            String supplierId,
            String materialRefType,
            String materialRefId,
            int supplierReceivingItemOrdinal,
            String materialSnapshotDigest,
            String supplierSnapshotDigest,
            String acceptanceMode,
            OwnershipType ownershipType,
            SegregationRequirement segregationRequirement,
            IssuancePolicy issuancePolicy,
            ReturnPolicy returnPolicy,
            SettlementPolicy settlementPolicy,
            BigDecimal residualTolerancePercent,
            int retentionDays,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            AccountingTreatment accountingTreatment,
            boolean apPostingAllowed,
            boolean inventoryValuationIncluded,
            boolean purchaseOrderAllowed,
            boolean crossCustomerIssueAllowed,
            boolean returnOnCompletionRequired,
            String changeReason) {
        public DerivedPolicy {
            requiredEvidence = List.copyOf(requiredEvidence);
        }
    }

    /**
     * `contract-material-policy/v1`: customer-owned material never enters the factory books.
     * Cross-customer issuance is never allowed for either ownership type — contract material is
     * always scope-bound.
     */
    public static AccountingTreatment accountingTreatment(OwnershipType ownershipType) {
        return ownershipType == OwnershipType.CUSTOMER_OWNED
                ? AccountingTreatment.CUSTOMER_ASSET_OFF_BOOK
                : AccountingTreatment.FACTORY_ASSET_ON_BOOK;
    }

    public static boolean apPostingAllowed(OwnershipType ownershipType) {
        return ownershipType == OwnershipType.FACTORY_OWNED_FOR_CONTRACT;
    }

    public static boolean inventoryValuationIncluded(OwnershipType ownershipType) {
        return ownershipType == OwnershipType.FACTORY_OWNED_FOR_CONTRACT;
    }

    public static boolean purchaseOrderAllowed(OwnershipType ownershipType) {
        return ownershipType == OwnershipType.FACTORY_OWNED_FOR_CONTRACT;
    }

    public static boolean crossCustomerIssueAllowed(OwnershipType ownershipType) {
        return false;
    }

    public static boolean returnOnCompletionRequired(OwnershipType ownershipType) {
        return ownershipType == OwnershipType.CUSTOMER_OWNED;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            SupplierReceivingPin supplierReceiving,
            List<PolicyInput> policies,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            policies = policies == null ? List.of() : List.copyOf(policies);
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

    public record DraftIssue(String code, String target, String message, String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String algorithmVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            DraftRequest request,
            List<DerivedPolicy> policies,
            String sourceSnapshotDigest,
            String supplierReceivingSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int purchaseOrderWriteCount,
            int receivingWriteCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int accountsPayableWriteCount,
            int materialMasterWriteCount,
            int customerRecordReadCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("materials", "supplier-receiving");
            policies = List.copyOf(policies);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 2 + policies.size() : 0;
            purchaseOrderWriteCount = 0;
            receivingWriteCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            accountsPayableWriteCount = 0;
            materialMasterWriteCount = 0;
            customerRecordReadCount = 0;
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
            int policyCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int purchaseOrderWriteCount,
            int receivingWriteCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int accountsPayableWriteCount,
            int materialMasterWriteCount,
            int customerRecordReadCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            purchaseOrderWriteCount = 0;
            receivingWriteCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            accountsPayableWriteCount = 0;
            materialMasterWriteCount = 0;
            customerRecordReadCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<DerivedPolicy> policies,
            String sourceSnapshotDigest,
            String desiredDigest,
            int purchaseOrderWriteCount,
            int receivingWriteCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int accountsPayableWriteCount,
            int materialMasterWriteCount,
            int customerRecordReadCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            policies = List.copyOf(policies);
            purchaseOrderWriteCount = 0;
            receivingWriteCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            accountsPayableWriteCount = 0;
            materialMasterWriteCount = 0;
            customerRecordReadCount = 0;
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
