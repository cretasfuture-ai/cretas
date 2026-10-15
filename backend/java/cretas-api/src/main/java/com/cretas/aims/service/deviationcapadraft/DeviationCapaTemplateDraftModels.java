package com.cretas.aims.service.deviationcapadraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Typed public contract for Blueprint-owned deviation and CAPA template drafts. */
public final class DeviationCapaTemplateDraftModels {
    public static final String SCHEMA_VERSION = "deviation-capa-template/v1";
    public static final String DEPENDENCY_BASIS =
            "CAPABILITY_CATALOG:critical-controls+quality-release";
    public static final String DECISION_ALGORITHM_VERSION =
            "deviation-capa-classification-policy/v1";

    private DeviationCapaTemplateDraftModels() {}

    public enum SourceType { CRITICAL_CONTROL, QUALITY_RELEASE_SPEC }
    public enum TriggerMode { SINGLE_EVENT, REPEAT_EVENT }
    public enum Severity { CRITICAL, MAJOR, MINOR }
    public enum Classification { DEVIATION, NON_CONFORMANCE, CAPA }
    public enum ImmediateControlAction { HOLD_AFFECTED_PRODUCT, ESCALATE_AND_ASSESS }
    public enum ApprovalRoute {
        QUALITY_OWNER, QUALITY_THEN_PRODUCTION_OWNER, QUALITY_THEN_FACTORY_OWNER
    }
    public enum RootCauseMethod { FIVE_WHYS, FISHBONE, BOTH }
    public enum CorrectionTemplateCode { STOP_AND_CONTAIN, SEGREGATE_AND_ASSESS }
    public enum CorrectiveActionCode { RESTORE_CONTROL, REVISE_PROCESS_CONTROL }
    public enum PreventiveActionCode { SYSTEMIC_CONTROL_REDESIGN, AUDIT_EXPANSION }
    public enum EffectivenessMetricCode { CONTROL_COMPLIANCE, DEFECT_RATE_REDUCTION }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CriticalControlPin(String draftId, String desiredDigest) {
        public CriticalControlPin {
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record QualityReleasePin(String draftId, String desiredDigest) {
        public QualityReleasePin {
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record RuleSpec(
            SourceType sourceType,
            String sourceItemId,
            TriggerMode triggerMode,
            Integer repeatThreshold,
            Integer recurrenceWindowDays,
            List<String> authorityCodes,
            String changeReason) {
        public RuleSpec {
            sourceItemId = clean(sourceItemId);
            authorityCodes = immutableStrings(authorityCodes);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TemplatePolicy(
            int deviationSlaHours,
            int nonConformanceSlaHours,
            int capaSlaHours,
            RootCauseMethod rootCauseMethod,
            int capaEffectivenessReviewDays,
            int recordRetentionDays,
            String changeReason) {
        public TemplatePolicy {
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            CriticalControlPin criticalControl,
            QualityReleasePin qualityRelease,
            List<RuleSpec> rules,
            TemplatePolicy policy,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = digestValue(sourceDigest);
            rules = immutable(rules);
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

    public record DerivedRule(
            RuleSpec desired,
            Severity sourceSeverity,
            Classification classification,
            ImmediateControlAction immediateControlAction,
            ApprovalRoute approvalRoute,
            int escalationSlaHours,
            boolean rootCauseRequired,
            boolean correctiveActionRequired,
            boolean preventiveActionRequired,
            boolean effectivenessReviewRequired,
            RootCauseMethod rootCauseMethod,
            Integer effectivenessReviewDays,
            CorrectionTemplateCode correctionTemplateCode,
            CorrectiveActionCode correctiveActionCode,
            PreventiveActionCode preventiveActionCode,
            EffectivenessMetricCode effectivenessMetricCode) {}

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            DraftRequest desired,
            List<DerivedRule> derivedRules,
            String sourceSnapshotDigest,
            String criticalControlSnapshotDigest,
            String qualityReleaseSnapshotDigest,
            String authoritySnapshotDigest,
            String formalDispositionSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedModuleDraftWriteCount,
            int expectedPersistedRowCount,
            int deviationCaseWriteCount,
            int qualityInspectionWriteCount,
            int defectReturnWriteCount,
            int batchInventoryStatusWriteCount,
            int approvalWriteCount,
            int alertNotificationRecallWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("critical-controls", "quality-release");
            derivedRules = derivedRules == null ? List.of() : List.copyOf(derivedRules);
            issues = issues == null ? List.of() : List.copyOf(issues);
            int evidence = desired == null ? 0 : desired.rules().stream()
                    .mapToInt(rule -> rule == null ? 0 : rule.authorityCodes().size()).sum();
            expectedModuleDraftWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 1 + desired.rules().size() + evidence : 0;
            deviationCaseWriteCount = 0;
            qualityInspectionWriteCount = 0;
            defectReturnWriteCount = 0;
            batchInventoryStatusWriteCount = 0;
            approvalWriteCount = 0;
            alertNotificationRecallWriteCount = 0;
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
            String criticalControlSnapshotDigest,
            String qualityReleaseSnapshotDigest,
            String authoritySnapshotDigest,
            String formalDispositionSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int ruleCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int moduleDraftWriteCount,
            int originalModuleDraftWriteCount,
            int persistedRowCount,
            int deviationCaseWriteCount,
            int qualityInspectionWriteCount,
            int defectReturnWriteCount,
            int batchInventoryStatusWriteCount,
            int approvalWriteCount,
            int alertNotificationRecallWriteCount,
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
            List<DerivedRule> derivedRules,
            int deviationCaseWriteCount,
            int qualityInspectionWriteCount,
            int defectReturnWriteCount,
            int batchInventoryStatusWriteCount,
            int approvalWriteCount,
            int alertNotificationRecallWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            derivedRules = derivedRules == null ? List.of() : List.copyOf(derivedRules);
            deviationCaseWriteCount = 0;
            qualityInspectionWriteCount = 0;
            defectReturnWriteCount = 0;
            batchInventoryStatusWriteCount = 0;
            approvalWriteCount = 0;
            alertNotificationRecallWriteCount = 0;
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

    private static List<String> immutableStrings(List<String> values) {
        if (values == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(
                values.stream().map(DeviationCapaTemplateDraftModels::clean).toList()));
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String digestValue(String value) { return clean(value).toLowerCase(Locale.ROOT); }
}
