package com.cretas.aims.service.sanitationcipdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SanitationCipDraftModels {
    public static final String SCHEMA_VERSION = "sanitation-cip-template/v1";
    public static final String ALGORITHM_VERSION = "sanitation-cip-policy/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CATALOG:routes+SAFETY_PIN:equipment-calibration";

    private SanitationCipDraftModels() {
    }

    public enum TargetType { ROUTE_PROCESS, EQUIPMENT_CIP, TOOLS, ENVIRONMENT, EMPLOYEE_HYGIENE }
    public enum SanitationMethod { MANUAL_CLEAN, FOAM_CLEAN, CLEAN_OUT_OF_PLACE, CLEAN_IN_PLACE }
    public enum ChemicalType {
        NONE, ALKALINE, ACID, CHLORINE, PERACETIC_ACID, QUATERNARY_AMMONIUM,
        HOT_WATER, STEAM
    }
    public enum ConcentrationUnit { PPM, PERCENT }
    public enum Frequency {
        BEFORE_STARTUP, AFTER_SHIFT, AFTER_PRODUCT_CHANGEOVER,
        AFTER_ALLERGEN_CHANGEOVER, DAILY, WEEKLY
    }
    public enum ResponsibleRole {
        SANITATION_OPERATOR, PRODUCTION_SUPERVISOR, QUALITY_INSPECTOR
    }
    public enum VerificationMethod {
        VISUAL, ATP, PROTEIN_SWAB, CONDUCTIVITY, PH, TEMPERATURE,
        MICROBIOLOGICAL_SWAB
    }
    public enum EvidenceType { CHECKLIST, PHOTO, METER_READING, QUALITY_SIGNOFF }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record EquipmentPlanPin(String draftId, String desiredDigest) {
        public EquipmentPlanPin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TemplateInput(
            String productCode,
            String routeNodeId,
            TargetType targetType,
            Long equipmentId,
            SanitationMethod method,
            ChemicalType chemicalType,
            BigDecimal concentrationMin,
            BigDecimal concentrationTarget,
            BigDecimal concentrationMax,
            ConcentrationUnit concentrationUnit,
            BigDecimal temperatureMinC,
            BigDecimal temperatureTargetC,
            BigDecimal temperatureMaxC,
            Integer contactTimeSeconds,
            BigDecimal flowRateLitersPerMinute,
            Integer preRinseSeconds,
            Integer finalRinseSeconds,
            Frequency frequency,
            List<VerificationMethod> verificationMethods,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            boolean holdUntilVerified,
            String changeReason) {
        public TemplateInput {
            productCode = clean(productCode);
            routeNodeId = clean(routeNodeId);
            verificationMethods = verificationMethods == null
                    ? List.of() : List.copyOf(verificationMethods);
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedTemplate(
            int ordinal,
            String templateCode,
            String productCode,
            String routeNodeId,
            String routeNodeLabel,
            TargetType targetType,
            Long equipmentId,
            Integer equipmentPlanEntryOrdinal,
            String targetSnapshotDigest,
            SanitationMethod method,
            ChemicalType chemicalType,
            BigDecimal concentrationMin,
            BigDecimal concentrationTarget,
            BigDecimal concentrationMax,
            ConcentrationUnit concentrationUnit,
            BigDecimal temperatureMinC,
            BigDecimal temperatureTargetC,
            BigDecimal temperatureMaxC,
            int contactTimeSeconds,
            BigDecimal flowRateLitersPerMinute,
            int preRinseSeconds,
            int finalRinseSeconds,
            Frequency frequency,
            List<VerificationMethod> verificationMethods,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            boolean holdUntilVerified,
            String changeReason) {
        public DerivedTemplate {
            verificationMethods = List.copyOf(verificationMethods);
            requiredEvidence = List.copyOf(requiredEvidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            EquipmentPlanPin equipmentPlan,
            List<TemplateInput> templates,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            templates = templates == null ? List.of() : List.copyOf(templates);
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
            List<DerivedTemplate> templates,
            String sourceSnapshotDigest,
            String routeSnapshotDigest,
            String equipmentPlanSnapshotDigest,
            String ssopBaselineDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int ssopProcedureWriteCount,
            int ssopExecutionWriteCount,
            int productionGateWriteCount,
            int equipmentControlWriteCount,
            int environmentalResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("routes");
            templates = List.copyOf(templates);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 2 + templates.size() : 0;
            ssopProcedureWriteCount = 0;
            ssopExecutionWriteCount = 0;
            productionGateWriteCount = 0;
            equipmentControlWriteCount = 0;
            environmentalResultWriteCount = 0;
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
            int templateCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int ssopProcedureWriteCount,
            int ssopExecutionWriteCount,
            int productionGateWriteCount,
            int equipmentControlWriteCount,
            int environmentalResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            ssopProcedureWriteCount = 0;
            ssopExecutionWriteCount = 0;
            productionGateWriteCount = 0;
            equipmentControlWriteCount = 0;
            environmentalResultWriteCount = 0;
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
            List<DerivedTemplate> templates,
            String sourceSnapshotDigest,
            String desiredDigest,
            int ssopProcedureWriteCount,
            int ssopExecutionWriteCount,
            int productionGateWriteCount,
            int equipmentControlWriteCount,
            int environmentalResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            templates = List.copyOf(templates);
            ssopProcedureWriteCount = 0;
            ssopExecutionWriteCount = 0;
            productionGateWriteCount = 0;
            equipmentControlWriteCount = 0;
            environmentalResultWriteCount = 0;
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
