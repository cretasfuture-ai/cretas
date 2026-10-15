package com.cretas.aims.service.criticalcontroldraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CriticalControlDraftModels {
    public static final String SCHEMA_VERSION = "critical-control-draft/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CONTRACT:hazard-analysis";
    public static final String DECISION_ALGORITHM_VERSION = "critical-control-decision-tree/v1";

    private CriticalControlDraftModels() {}

    public enum ControlClassification { CCP, OPRP, PRP }
    public enum CriterionType { CRITICAL_LIMIT, ACTION_CRITERION, PRP_CRITERION }
    public enum ParameterType {
        TEMPERATURE, TIME, MASS, VOLUME, COUNT, CONCENTRATION, ACIDITY_PH
    }
    public enum LimitKind { MINIMUM, MAXIMUM, RANGE, EXACT }
    public enum MonitoringMethod {
        CONTINUOUS_SENSOR, MANUAL_INSTRUMENT, VISUAL_CHECK, LAB_TEST, RECORD_REVIEW
    }
    public enum VerificationMethod {
        RECORD_REVIEW, CALIBRATION_REVIEW, DIRECT_OBSERVATION, SAMPLE_TEST, INTERNAL_AUDIT
    }
    public enum FrequencyUnit {
        CONTINUOUS, EVERY_BATCH, EVENT_DRIVEN, MINUTE, HOUR, SHIFT, DAY, WEEK, MONTH
    }
    public enum ResponsibleRole {
        FACTORY_OWNER, PRODUCTION_OWNER, QUALITY_OWNER, WAREHOUSE_OWNER,
        FOOD_SAFETY_OWNER, LINE_LEAD, QUALITY_INSPECTOR
    }
    public enum DeviationDisposition {
        HOLD_AFFECTED_PRODUCT, REPROCESS, DISPOSE, ESCALATE_AND_ASSESS
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HazardPin(String draftId, String desiredDigest) {
        public HazardPin {
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DecisionAnswers(
            boolean controlMeasureAtStep,
            boolean stepDesignedToEliminateOrReduce,
            boolean hazardCouldOccurOrIncrease,
            boolean laterStepEliminatesOrReduces,
            boolean operationalPrerequisiteAdequate) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record Schedule(int interval, FrequencyUnit unit) {}

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ControlItem(
            String hazardItemId,
            String parameterCode,
            String parameterName,
            DecisionAnswers decision,
            ParameterType parameterType,
            LimitKind limitKind,
            BigDecimal lowerLimit,
            BigDecimal upperLimit,
            boolean lowerInclusive,
            boolean upperInclusive,
            String unit,
            MonitoringMethod monitoringMethod,
            Schedule monitoringFrequency,
            ResponsibleRole responsibleRole,
            String monitoringProcedure,
            DeviationDisposition deviationDisposition,
            String correctiveAction,
            VerificationMethod verificationMethod,
            Schedule verificationFrequency,
            String verificationProcedure,
            int recordRetentionDays,
            List<String> authorityCodes,
            String changeReason) {
        public ControlItem {
            hazardItemId = clean(hazardItemId);
            parameterCode = clean(parameterCode).toUpperCase(Locale.ROOT);
            parameterName = clean(parameterName);
            unit = clean(unit);
            monitoringProcedure = clean(monitoringProcedure);
            correctiveAction = clean(correctiveAction);
            verificationProcedure = clean(verificationProcedure);
            authorityCodes = immutableStrings(authorityCodes);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            HazardPin hazard,
            List<ControlItem> items,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = digestValue(sourceDigest);
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
            String hazardSnapshotDigest,
            String authoritySnapshotDigest,
            String formalCheckpointSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedModuleDraftWriteCount,
            int expectedPersistedRowCount,
            int liveHaccpCheckpointWriteCount,
            int monitoringRecordWriteCount,
            int deviationCapaWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int qualityResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("hazard-analysis");
            issues = List.copyOf(issues);
            int evidence = desired == null ? 0 : desired.items().stream()
                    .mapToInt(item -> item == null ? 0 : item.authorityCodes().size()).sum();
            expectedModuleDraftWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 1 + desired.items().size() + evidence : 0;
            liveHaccpCheckpointWriteCount = 0;
            monitoringRecordWriteCount = 0;
            deviationCapaWriteCount = 0;
            inventoryWriteCount = 0;
            productionWriteCount = 0;
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
            String hazardSnapshotDigest,
            String authoritySnapshotDigest,
            String formalCheckpointSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int itemCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int moduleDraftWriteCount,
            int originalModuleDraftWriteCount,
            int persistedRowCount,
            int liveHaccpCheckpointWriteCount,
            int monitoringRecordWriteCount,
            int deviationCapaWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int qualityResultWriteCount,
            int alertNotificationWriteCount,
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
            int liveHaccpCheckpointWriteCount,
            int monitoringRecordWriteCount,
            int deviationCapaWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int qualityResultWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            liveHaccpCheckpointWriteCount = 0;
            monitoringRecordWriteCount = 0;
            deviationCapaWriteCount = 0;
            inventoryWriteCount = 0;
            productionWriteCount = 0;
            qualityResultWriteCount = 0;
            alertNotificationWriteCount = 0;
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

    public static ControlClassification classify(DecisionAnswers answers) {
        if (answers == null || !answers.controlMeasureAtStep()) return ControlClassification.PRP;
        if (answers.stepDesignedToEliminateOrReduce()) return ControlClassification.CCP;
        if (answers.hazardCouldOccurOrIncrease() && !answers.laterStepEliminatesOrReduces()) {
            return ControlClassification.CCP;
        }
        if (answers.operationalPrerequisiteAdequate()) return ControlClassification.OPRP;
        return ControlClassification.PRP;
    }

    public static CriterionType criterionType(ControlClassification classification) {
        if (classification == ControlClassification.CCP) return CriterionType.CRITICAL_LIMIT;
        if (classification == ControlClassification.OPRP) return CriterionType.ACTION_CRITERION;
        return CriterionType.PRP_CRITERION;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null) return List.of();
        List<String> result = values.stream().map(CriticalControlDraftModels::clean).toList();
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String digestValue(String value) { return clean(value).toLowerCase(Locale.ROOT); }
}
