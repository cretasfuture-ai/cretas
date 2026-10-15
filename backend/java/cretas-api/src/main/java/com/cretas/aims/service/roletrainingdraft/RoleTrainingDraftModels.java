package com.cretas.aims.service.roletrainingdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for factory position, role-requirement and training DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares {@code accounts -> factory}. The adapter contract
 * previously declared {@code modules}; B16 converges it to the catalog.</p>
 *
 * <p>A row here states that a position <em>requires</em> a platform role and a set of competencies.
 * It never creates a user, never grants a role or permission, never sends an invitation, never
 * captures an electronic signature and never writes a training record. Those remain separate,
 * separately-authorised commands.</p>
 */
public final class RoleTrainingDraftModels {
    public static final String SCHEMA_VERSION = "role-training-draft/v1";
    public static final String ALGORITHM_VERSION = "accounts-role-training-policy/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CATALOG:factory";

    private RoleTrainingDraftModels() {
    }

    /**
     * Closed subset of the canonical {@code FactoryUserRole} values that a factory position may
     * require. Cross-factory {@code platform_admin} and the restaurant-side roles are excluded:
     * they are outside a factory blueprint's scope.
     */
    public enum PlatformRole {
        factory_super_admin, permission_admin, department_admin, hr_admin, procurement_manager,
        sales_manager, dispatcher, production_manager, warehouse_manager, equipment_admin,
        quality_manager, quality_controller, finance_manager, cashier, workshop_supervisor,
        team_leader, group_leader, quality_inspector, operator, yield_operator, warehouse_worker,
        viewer
    }

    public enum Department { PROCESSING, WAREHOUSE, QUALITY, MAINTENANCE, MANAGEMENT }

    public enum Competency {
        FOOD_SAFETY_BASIC, HACCP_AWARENESS, ALLERGEN_CONTROL, SANITATION_SOP,
        EQUIPMENT_OPERATION, INTERNAL_AUDIT, FIRST_AID
    }

    private static final Set<PlatformRole> PRIVILEGED_ROLES =
            Set.of(PlatformRole.factory_super_admin, PlatformRole.permission_admin);
    private static final Set<Department> FOOD_HANDLING_DEPARTMENTS =
            Set.of(Department.PROCESSING, Department.WAREHOUSE, Department.QUALITY);

    /** `accounts-role-training-policy/v1`. Pure and total; never accepted from the request. */
    public static boolean privilegedRole(PlatformRole role) {
        return PRIVILEGED_ROLES.contains(role);
    }

    public static boolean segregationOfDutiesRequired(PlatformRole role) {
        return privilegedRole(role);
    }

    public static int accessReviewIntervalMonths(PlatformRole role) {
        return privilegedRole(role) ? 6 : 12;
    }

    public static boolean foodHandlingPosition(Department department) {
        return FOOD_HANDLING_DEPARTMENTS.contains(department);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PositionInput(
            String positionCode,
            String positionTitle,
            Department department,
            PlatformRole platformRole,
            Integer minimumHeadcount,
            List<Competency> requiredCompetencies,
            Integer trainingRefreshMonths,
            boolean healthCertificateRequired,
            String changeReason) {
        public PositionInput {
            positionCode = clean(positionCode).toUpperCase(Locale.ROOT);
            positionTitle = clean(positionTitle);
            requiredCompetencies = requiredCompetencies == null
                    ? List.of() : List.copyOf(requiredCompetencies);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedPosition(
            int ordinal,
            String positionCode,
            String positionTitle,
            Department department,
            PlatformRole platformRole,
            int minimumHeadcount,
            List<Competency> requiredCompetencies,
            int trainingRefreshMonths,
            boolean healthCertificateRequired,
            boolean privilegedRole,
            boolean segregationOfDutiesRequired,
            int accessReviewIntervalMonths,
            boolean foodHandlingPosition,
            String changeReason) {
        public DerivedPosition {
            requiredCompetencies = List.copyOf(requiredCompetencies);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            List<PositionInput> positions,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            positions = positions == null ? List.of() : List.copyOf(positions);
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
            List<DerivedPosition> positions,
            String sourceSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int userWriteCount,
            int roleGrantWriteCount,
            int permissionWriteCount,
            int invitationWriteCount,
            int electronicSignatureWriteCount,
            int trainingRecordWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("factory");
            positions = List.copyOf(positions);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 2 + positions.size() : 0;
            userWriteCount = 0;
            roleGrantWriteCount = 0;
            permissionWriteCount = 0;
            invitationWriteCount = 0;
            electronicSignatureWriteCount = 0;
            trainingRecordWriteCount = 0;
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
            int positionCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int userWriteCount,
            int roleGrantWriteCount,
            int permissionWriteCount,
            int invitationWriteCount,
            int electronicSignatureWriteCount,
            int trainingRecordWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            userWriteCount = 0;
            roleGrantWriteCount = 0;
            permissionWriteCount = 0;
            invitationWriteCount = 0;
            electronicSignatureWriteCount = 0;
            trainingRecordWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<DerivedPosition> positions,
            String sourceSnapshotDigest,
            String desiredDigest,
            int userWriteCount,
            int roleGrantWriteCount,
            int permissionWriteCount,
            int invitationWriteCount,
            int electronicSignatureWriteCount,
            int trainingRecordWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            positions = List.copyOf(positions);
            userWriteCount = 0;
            roleGrantWriteCount = 0;
            permissionWriteCount = 0;
            invitationWriteCount = 0;
            electronicSignatureWriteCount = 0;
            trainingRecordWriteCount = 0;
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
