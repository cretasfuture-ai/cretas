package com.cretas.aims.service.qualityreleasedraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Typed public contract for Blueprint-owned quality release template drafts. */
public final class QualityReleaseTemplateDraftModels {
    public static final String SCHEMA_VERSION = "quality-release-template/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CATALOG:critical-controls+materials";
    public static final String DECISION_ALGORITHM_VERSION = "quality-release-template-policy/v1";

    private QualityReleaseTemplateDraftModels() {}

    public enum SubjectType { FINISHED_PRODUCT, MATERIAL, PROCESS_CONTROL }
    public enum SubjectRefType {
        FORMAL_PRODUCT, PRODUCT_DRAFT, FORMAL_MATERIAL, MATERIAL_DRAFT, CRITICAL_CONTROL
    }
    public enum QualityCategory {
        SENSORY, PHYSICAL, CHEMICAL, MICROBIOLOGICAL, PACKAGING, CONTROL_VERIFICATION
    }
    public enum ValueType { NUMERIC, RANGE, TEXT, BOOLEAN }
    public enum SamplingStrategy { FULL_INSPECTION, RANDOM, PERIODIC, EVERY_BATCH, AQL }
    public enum Severity { CRITICAL, MAJOR, MINOR }
    public enum ReleaseEffect { BLOCK_RELEASE, REQUIRE_QUALITY_REVIEW, ADVISORY }
    public enum ResponsibleRole { QUALITY_OWNER, QUALITY_INSPECTOR, FACTORY_OWNER }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record CriticalControlPin(String draftId, String desiredDigest) {
        public CriticalControlPin {
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record InspectionSpec(
            SubjectType subjectType,
            SubjectRefType subjectRefType,
            String subjectRefId,
            String linkedCriticalControlItemId,
            String itemCode,
            String itemName,
            QualityCategory category,
            ValueType valueType,
            BigDecimal lowerValue,
            BigDecimal upperValue,
            String expectedText,
            Boolean expectedBoolean,
            String unit,
            BigDecimal tolerance,
            String standardReference,
            String inspectionMethod,
            SamplingStrategy samplingStrategy,
            BigDecimal samplingRatio,
            int minimumSampleSize,
            BigDecimal aqlLevel,
            Severity severity,
            boolean required,
            boolean photoRequiredOnFailure,
            boolean noteRequiredOnFailure,
            ResponsibleRole responsibleRole,
            int recordRetentionDays,
            List<String> authorityCodes,
            String changeReason) {
        public InspectionSpec {
            subjectRefId = clean(subjectRefId);
            linkedCriticalControlItemId = clean(linkedCriticalControlItemId);
            itemCode = clean(itemCode).toUpperCase(Locale.ROOT);
            itemName = clean(itemName);
            expectedText = clean(expectedText);
            unit = clean(unit);
            standardReference = clean(standardReference);
            inspectionMethod = clean(inspectionMethod);
            authorityCodes = immutableStrings(authorityCodes);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ReleasePolicy(
            int maximumMajorFailuresForConditionalReview,
            int maximumMinorFailuresForConditionalReview,
            ResponsibleRole approvalRole,
            int recordRetentionDays,
            String changeReason) {
        public ReleasePolicy {
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
            List<InspectionSpec> items,
            ReleasePolicy releasePolicy,
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
            String criticalControlSnapshotDigest,
            String bomSnapshotDigest,
            String materialSnapshotDigest,
            String authoritySnapshotDigest,
            String formalQualitySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedModuleDraftWriteCount,
            int expectedPersistedRowCount,
            int qualityInspectionWriteCount,
            int qualityResultWriteCount,
            int batchStatusWriteCount,
            int inventoryWriteCount,
            int approvalWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("critical-controls", "materials");
            issues = List.copyOf(issues);
            int evidence = desired == null ? 0 : desired.items().stream()
                    .mapToInt(item -> item == null ? 0 : item.authorityCodes().size()).sum();
            expectedModuleDraftWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 1 + desired.items().size() + evidence : 0;
            qualityInspectionWriteCount = 0;
            qualityResultWriteCount = 0;
            batchStatusWriteCount = 0;
            inventoryWriteCount = 0;
            approvalWriteCount = 0;
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
            String criticalControlSnapshotDigest,
            String bomSnapshotDigest,
            String materialSnapshotDigest,
            String authoritySnapshotDigest,
            String formalQualitySnapshotDigest,
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
            int qualityInspectionWriteCount,
            int qualityResultWriteCount,
            int batchStatusWriteCount,
            int inventoryWriteCount,
            int approvalWriteCount,
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
            int qualityInspectionWriteCount,
            int qualityResultWriteCount,
            int batchStatusWriteCount,
            int inventoryWriteCount,
            int approvalWriteCount,
            int alertNotificationWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            qualityInspectionWriteCount = 0;
            qualityResultWriteCount = 0;
            batchStatusWriteCount = 0;
            inventoryWriteCount = 0;
            approvalWriteCount = 0;
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

    public static ReleaseEffect releaseEffect(Severity severity) {
        if (severity == Severity.CRITICAL) return ReleaseEffect.BLOCK_RELEASE;
        if (severity == Severity.MAJOR) return ReleaseEffect.REQUIRE_QUALITY_REVIEW;
        return ReleaseEffect.ADVISORY;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null) return List.of();
        return Collections.unmodifiableList(new ArrayList<>(
                values.stream().map(QualityReleaseTemplateDraftModels::clean).toList()));
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String digestValue(String value) { return clean(value).toLowerCase(Locale.ROOT); }
}
