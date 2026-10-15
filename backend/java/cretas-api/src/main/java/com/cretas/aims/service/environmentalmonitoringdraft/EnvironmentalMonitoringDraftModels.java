package com.cretas.aims.service.environmentalmonitoringdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for environmental and pathogen monitoring TEMPLATE/DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares {@code environmental-monitoring -> sanitation-cip}
 * as the direct dependency. {@code hygienic-zones} is pinned only as an explicit safety pin so a
 * sampling point cannot name a zone that no readback-ready zoning DRAFT owns; it never becomes a
 * catalog dependency. There is no field through which a client or an AI can submit a sampling
 * result, a positive event, a production hold, a quality freeze, a CAPA case, an alert, a
 * notification, an equipment command, arbitrary JSON/JSONB, SQL or a script.</p>
 */
public final class EnvironmentalMonitoringDraftModels {
    public static final String SCHEMA_VERSION = "environmental-monitoring-template/v1";
    public static final String ALGORITHM_VERSION = "environmental-monitoring-policy/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CATALOG:sanitation-cip+SAFETY_PIN:hygienic-zones";

    private EnvironmentalMonitoringDraftModels() {
    }

    public enum ContactClass {
        FOOD_CONTACT, NON_FOOD_CONTACT_ADJACENT, NON_FOOD_CONTACT_REMOTE, OUTSIDE_PROCESSING_AREA
    }

    public enum SampleMedium {
        SURFACE_SWAB, SPONGE_SWAB, AIR_SETTLE_PLATE, AIR_ACTIVE_SAMPLER, WATER, CONDENSATE
    }

    public enum TargetOrganism {
        LISTERIA_MONOCYTOGENES, LISTERIA_SPP, SALMONELLA, ENTEROBACTERIACEAE, COLIFORM,
        TOTAL_PLATE_COUNT, YEAST_MOLD, ATP_RESIDUE
    }

    public enum SamplingTiming { PRE_OPERATIONAL, DURING_OPERATION, POST_SANITATION }

    public enum SamplingFrequency { PER_SHIFT, DAILY, WEEKLY, BIWEEKLY, MONTHLY, QUARTERLY }

    public enum ResultUnit {
        CFU_PER_SWAB, CFU_PER_CM2, CFU_PER_ML, CFU_PER_PLATE, RLU, PRESENCE_ABSENCE
    }

    public enum ResponsibleRole {
        QUALITY_INSPECTOR, SANITATION_OPERATOR, PRODUCTION_SUPERVISOR, LAB_TECHNICIAN
    }

    public enum EvidenceType { CHECKLIST, PHOTO, LAB_REPORT, QUALITY_SIGNOFF }

    /** Server-derived; never accepted from the request. */
    public enum ResponseLevel {
        TREND_AND_MONITOR, RECLEAN_AND_RESAMPLE, INVESTIGATE_AND_VECTOR_SAMPLE,
        CORRECTIVE_ACTION_WITH_PRODUCT_ASSESSMENT
    }

    /** Server-derived; never accepted from the request. */
    public enum EscalationRole {
        QUALITY_INSPECTOR, SANITATION_SUPERVISOR, QUALITY_SUPERVISOR, QUALITY_MANAGER
    }

    private static final Set<TargetOrganism> PATHOGENS = Set.of(
            TargetOrganism.LISTERIA_MONOCYTOGENES, TargetOrganism.LISTERIA_SPP,
            TargetOrganism.SALMONELLA);

    public static boolean pathogenTarget(TargetOrganism organism) {
        return PATHOGENS.contains(organism);
    }

    /** `environmental-monitoring-policy/v1` response derivation. Pure and total. */
    public static ResponseLevel responseLevel(ContactClass contactClass, TargetOrganism organism) {
        boolean pathogen = pathogenTarget(organism);
        if (contactClass == ContactClass.FOOD_CONTACT) {
            return pathogen ? ResponseLevel.CORRECTIVE_ACTION_WITH_PRODUCT_ASSESSMENT
                    : ResponseLevel.RECLEAN_AND_RESAMPLE;
        }
        return pathogen ? ResponseLevel.INVESTIGATE_AND_VECTOR_SAMPLE
                : ResponseLevel.TREND_AND_MONITOR;
    }

    public static EscalationRole escalationRole(ResponseLevel level) {
        return switch (level) {
            case CORRECTIVE_ACTION_WITH_PRODUCT_ASSESSMENT -> EscalationRole.QUALITY_MANAGER;
            case INVESTIGATE_AND_VECTOR_SAMPLE -> EscalationRole.QUALITY_SUPERVISOR;
            case RECLEAN_AND_RESAMPLE -> EscalationRole.SANITATION_SUPERVISOR;
            case TREND_AND_MONITOR -> EscalationRole.QUALITY_INSPECTOR;
        };
    }

    public static boolean vectorSamplingRequired(TargetOrganism organism) {
        return pathogenTarget(organism);
    }

    public static boolean resampleRequired(ResponseLevel level) {
        return level != ResponseLevel.TREND_AND_MONITOR;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record SanitationTemplatePin(String draftId, String desiredDigest) {
        public SanitationTemplatePin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HygienicZonePin(String draftId, String desiredDigest) {
        public HygienicZonePin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record MonitoringPointInput(
            String zoneCode,
            String sanitationTemplateCode,
            ContactClass contactClass,
            SampleMedium sampleMedium,
            TargetOrganism targetOrganism,
            SamplingTiming samplingTiming,
            SamplingFrequency samplingFrequency,
            Integer sampleSizeCount,
            ResultUnit resultUnit,
            BigDecimal alertThreshold,
            BigDecimal actionThreshold,
            Integer resultHoldHours,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            String changeReason) {
        public MonitoringPointInput {
            zoneCode = clean(zoneCode).toUpperCase(Locale.ROOT);
            sanitationTemplateCode = clean(sanitationTemplateCode).toUpperCase(Locale.ROOT);
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedPoint(
            int ordinal,
            String pointCode,
            String zoneCode,
            String zoneName,
            String zoneHygieneLevel,
            String sanitationTemplateCode,
            int sanitationItemOrdinal,
            String productCode,
            String routeNodeId,
            String routeNodeLabel,
            String targetSnapshotDigest,
            ContactClass contactClass,
            SampleMedium sampleMedium,
            TargetOrganism targetOrganism,
            SamplingTiming samplingTiming,
            SamplingFrequency samplingFrequency,
            int sampleSizeCount,
            ResultUnit resultUnit,
            BigDecimal alertThreshold,
            BigDecimal actionThreshold,
            int resultHoldHours,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            boolean pathogenTarget,
            ResponseLevel responseLevel,
            EscalationRole escalationRole,
            boolean vectorSamplingRequired,
            boolean resampleRequired,
            String changeReason) {
        public DerivedPoint {
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
            HygienicZonePin hygienicZone,
            List<MonitoringPointInput> points,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            points = points == null ? List.of() : List.copyOf(points);
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
            List<DerivedPoint> points,
            String sourceSnapshotDigest,
            String sanitationSnapshotDigest,
            String hygienicZoneSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int environmentalResultWriteCount,
            int positiveEventWriteCount,
            int productionHoldWriteCount,
            int qualityFreezeWriteCount,
            int capaWriteCount,
            int alertNotificationWriteCount,
            int equipmentControlWriteCount,
            int productionDataWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("sanitation-cip");
            points = List.copyOf(points);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 2 + points.size() : 0;
            environmentalResultWriteCount = 0;
            positiveEventWriteCount = 0;
            productionHoldWriteCount = 0;
            qualityFreezeWriteCount = 0;
            capaWriteCount = 0;
            alertNotificationWriteCount = 0;
            equipmentControlWriteCount = 0;
            productionDataWriteCount = 0;
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
            int pointCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int environmentalResultWriteCount,
            int positiveEventWriteCount,
            int productionHoldWriteCount,
            int qualityFreezeWriteCount,
            int capaWriteCount,
            int alertNotificationWriteCount,
            int equipmentControlWriteCount,
            int productionDataWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            environmentalResultWriteCount = 0;
            positiveEventWriteCount = 0;
            productionHoldWriteCount = 0;
            qualityFreezeWriteCount = 0;
            capaWriteCount = 0;
            alertNotificationWriteCount = 0;
            equipmentControlWriteCount = 0;
            productionDataWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<DerivedPoint> points,
            String sourceSnapshotDigest,
            String desiredDigest,
            int environmentalResultWriteCount,
            int positiveEventWriteCount,
            int productionHoldWriteCount,
            int qualityFreezeWriteCount,
            int capaWriteCount,
            int alertNotificationWriteCount,
            int equipmentControlWriteCount,
            int productionDataWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            points = List.copyOf(points);
            environmentalResultWriteCount = 0;
            positiveEventWriteCount = 0;
            productionHoldWriteCount = 0;
            qualityFreezeWriteCount = 0;
            capaWriteCount = 0;
            alertNotificationWriteCount = 0;
            equipmentControlWriteCount = 0;
            productionDataWriteCount = 0;
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
