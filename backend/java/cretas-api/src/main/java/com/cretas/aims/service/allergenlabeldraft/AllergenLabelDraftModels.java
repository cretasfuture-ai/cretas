package com.cretas.aims.service.allergenlabeldraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for allergen matrix and label DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares
 * {@code allergen-label -> products + materials + routes}. {@code sanitation-cip} is pinned only as
 * an explicit safety pin for changeover cleaning and never becomes a catalog dependency.</p>
 *
 * <p>There is no field through which a client or an AI can publish a label version, print or
 * release a label, change a product or material master record, write inventory, batches, BOM,
 * production data, quality results, alerts or notifications.</p>
 */
public final class AllergenLabelDraftModels {
    public static final String SCHEMA_VERSION = "allergen-label-draft/v1";
    public static final String ALGORITHM_VERSION = "allergen-label-policy/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CATALOG:products+materials+routes+SAFETY_PIN:sanitation-cip";

    private AllergenLabelDraftModels() {
    }

    public enum SubjectKind { MATERIAL, MATERIAL_DRAFT, PRODUCT, PRODUCT_DRAFT }

    /** GB 7718 / CXC 1 style closed allergen list. Never free text. */
    public enum Allergen {
        CEREALS_CONTAINING_GLUTEN, CRUSTACEAN, EGG, FISH, PEANUT, SOYBEAN, MILK, TREE_NUT,
        SESAME, SULPHITES
    }

    /** Material side only. */
    public enum PresenceKind { CONTAINS, MAY_CONTAIN_CROSS_CONTACT }

    /** Material side only. */
    public enum EvidenceSource { SUPPLIER_SPECIFICATION, RECIPE_COMPOSITION, SITE_ASSESSMENT }

    /** Product side only. */
    public enum LabelStatement { DECLARED_IN_INGREDIENTS, PRECAUTIONARY_MAY_CONTAIN }

    /** Server-derived; never accepted from the request. */
    public enum DeclarationBasis { MATERIAL_CONTAINS, MATERIAL_CROSS_CONTACT }

    public enum ChangeoverMethod {
        DEDICATED_LINE, FULL_WET_CLEAN, DRY_CLEAN_AND_FLUSH, SEQUENCED_PRODUCTION
    }

    public enum ChangeoverVerification {
        VISUAL, ALLERGEN_SWAB_RAPID_TEST, PROTEIN_SWAB, RINSE_WATER_TEST, LINE_CLEARANCE_CHECKLIST
    }

    public enum ResponsibleRole {
        QUALITY_INSPECTOR, SANITATION_OPERATOR, PRODUCTION_SUPERVISOR, LABELLING_OPERATOR
    }

    public enum EvidenceType { CHECKLIST, PHOTO, LAB_REPORT, QUALITY_SIGNOFF }

    private static final Set<ChangeoverVerification> OBJECTIVE_VERIFICATIONS = Set.of(
            ChangeoverVerification.ALLERGEN_SWAB_RAPID_TEST, ChangeoverVerification.PROTEIN_SWAB,
            ChangeoverVerification.RINSE_WATER_TEST);

    public static boolean objectiveVerification(ChangeoverVerification verification) {
        return OBJECTIVE_VERIFICATIONS.contains(verification);
    }

    /**
     * `allergen-label-policy/v1`: a label statement may never be weaker than the matrix.
     * An allergen backed by a CONTAINS material must be declared in the ingredient list; an
     * allergen backed only by cross-contact must stay precautionary.
     */
    public static LabelStatement requiredStatement(DeclarationBasis basis) {
        return basis == DeclarationBasis.MATERIAL_CONTAINS
                ? LabelStatement.DECLARED_IN_INGREDIENTS
                : LabelStatement.PRECAUTIONARY_MAY_CONTAIN;
    }

    public static boolean changeoverRequired(DeclarationBasis basis) {
        return basis == DeclarationBasis.MATERIAL_CONTAINS;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SanitationTemplatePin(String draftId, String desiredDigest) {
        public SanitationTemplatePin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SubjectRef(SubjectKind kind, String targetId, String targetSnapshotDigest) {
        public SubjectRef {
            targetId = clean(targetId);
            targetSnapshotDigest = clean(targetSnapshotDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DeclarationInput(
            SubjectRef subject,
            Allergen allergen,
            PresenceKind presence,
            EvidenceSource evidenceSource,
            LabelStatement labelStatement,
            String labelVersionCode,
            String changeReason) {
        public DeclarationInput {
            labelVersionCode = clean(labelVersionCode).toUpperCase(Locale.ROOT);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ChangeoverInput(
            String productCode,
            String routeNodeId,
            Allergen allergen,
            ChangeoverMethod changeoverMethod,
            List<ChangeoverVerification> verifications,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            boolean holdUntilVerified,
            String sanitationTemplateCode,
            String changeReason) {
        public ChangeoverInput {
            productCode = clean(productCode);
            routeNodeId = clean(routeNodeId);
            verifications = verifications == null ? List.of() : List.copyOf(verifications);
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
            sanitationTemplateCode = clean(sanitationTemplateCode).toUpperCase(Locale.ROOT);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedDeclaration(
            int ordinal,
            String declarationCode,
            SubjectKind subjectKind,
            String targetId,
            String targetSnapshotDigest,
            String subjectCode,
            String subjectName,
            Allergen allergen,
            PresenceKind presence,
            EvidenceSource evidenceSource,
            LabelStatement labelStatement,
            String labelVersionCode,
            DeclarationBasis declarationBasis,
            boolean changeoverRequired,
            String changeReason) {
    }

    public record DerivedChangeover(
            int ordinal,
            String changeoverCode,
            String productCode,
            String routeNodeId,
            String routeNodeLabel,
            String routeSnapshotDigest,
            Allergen allergen,
            ChangeoverMethod changeoverMethod,
            List<ChangeoverVerification> verifications,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            boolean holdUntilVerified,
            boolean objectiveVerification,
            String sanitationTemplateCode,
            Integer sanitationItemOrdinal,
            String changeReason) {
        public DerivedChangeover {
            verifications = List.copyOf(verifications);
            requiredEvidence = List.copyOf(requiredEvidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            SanitationTemplatePin sanitationTemplate,
            List<DeclarationInput> declarations,
            List<ChangeoverInput> changeovers,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            declarations = declarations == null ? List.of() : List.copyOf(declarations);
            changeovers = changeovers == null ? List.of() : List.copyOf(changeovers);
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
            List<DerivedDeclaration> declarations,
            List<DerivedChangeover> changeovers,
            String sourceSnapshotDigest,
            String subjectSnapshotDigest,
            String routeSnapshotDigest,
            String sanitationSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int productMasterWriteCount,
            int materialMasterWriteCount,
            int labelPublishCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int bomWriteCount,
            int productionDataWriteCount,
            int qualityResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("products", "materials", "routes");
            declarations = List.copyOf(declarations);
            changeovers = List.copyOf(changeovers);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 2 + declarations.size() + changeovers.size() : 0;
            productMasterWriteCount = 0;
            materialMasterWriteCount = 0;
            labelPublishCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            bomWriteCount = 0;
            productionDataWriteCount = 0;
            qualityResultWriteCount = 0;
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
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int declarationCount,
            int changeoverCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int productMasterWriteCount,
            int materialMasterWriteCount,
            int labelPublishCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int bomWriteCount,
            int productionDataWriteCount,
            int qualityResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            productMasterWriteCount = 0;
            materialMasterWriteCount = 0;
            labelPublishCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            bomWriteCount = 0;
            productionDataWriteCount = 0;
            qualityResultWriteCount = 0;
            alertNotificationWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<DerivedDeclaration> declarations,
            List<DerivedChangeover> changeovers,
            String sourceSnapshotDigest,
            String desiredDigest,
            int productMasterWriteCount,
            int materialMasterWriteCount,
            int labelPublishCount,
            int inventoryWriteCount,
            int batchWriteCount,
            int bomWriteCount,
            int productionDataWriteCount,
            int qualityResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            declarations = List.copyOf(declarations);
            changeovers = List.copyOf(changeovers);
            productMasterWriteCount = 0;
            materialMasterWriteCount = 0;
            labelPublishCount = 0;
            inventoryWriteCount = 0;
            batchWriteCount = 0;
            bomWriteCount = 0;
            productionDataWriteCount = 0;
            qualityResultWriteCount = 0;
            alertNotificationWriteCount = 0;
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
