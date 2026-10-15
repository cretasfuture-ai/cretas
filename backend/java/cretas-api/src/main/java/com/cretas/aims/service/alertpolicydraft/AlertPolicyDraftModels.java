package com.cretas.aims.service.alertpolicydraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for alert policy DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares {@code alerts -> modules}. Every policy binds to
 * a module that a same-lineage, readback-ready module-config DRAFT actually enables.</p>
 *
 * <p>This contract deliberately does NOT reuse the legacy {@code AlertRule} model, whose
 * {@code trigger_condition_spel} is a free TEXT SpEL expression and whose {@code notify_channels}
 * / {@code notify_roles} are JSONB, and whose engine creates {@code AlertEvent} rows and sends
 * notifications. Here the trigger is a typed metric plus a closed comparison and typed bounds,
 * recipients are typed roles, and no alert event or notification is ever produced.</p>
 */
public final class AlertPolicyDraftModels {
    public static final String SCHEMA_VERSION = "alert-policy-draft/v1";
    public static final String ALGORITHM_VERSION = "alert-policy/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CATALOG:modules";

    private AlertPolicyDraftModels() {
    }

    /** Closed metric list. There is no free-text metric key and no expression. */
    public enum Metric {
        COLD_STORAGE_TEMPERATURE, PROCESS_CORE_TEMPERATURE, ROOM_HUMIDITY,
        EQUIPMENT_DOWNTIME_MINUTES, BATCH_YIELD_PERCENT, QUALITY_FAIL_RATE_PERCENT,
        INVENTORY_DAYS_ON_HAND, OVERDUE_CALIBRATION_COUNT, OVERDUE_SANITATION_COUNT
    }

    public enum MetricDimension { TEMPERATURE, PERCENT, DURATION_MINUTES, COUNT, DAYS }

    public enum Comparison {
        GREATER_THAN, GREATER_OR_EQUAL, LESS_THAN, LESS_OR_EQUAL, OUTSIDE_RANGE
    }

    public enum Severity { INFO, WARNING, CRITICAL }

    public enum RecipientRole {
        QUALITY_INSPECTOR, QUALITY_MANAGER, PRODUCTION_SUPERVISOR, WAREHOUSE_SUPERVISOR,
        MAINTENANCE_OWNER
    }

    /** Server-derived; never accepted from the request. */
    public enum EscalationRole { QUALITY_INSPECTOR, PRODUCTION_SUPERVISOR, QUALITY_MANAGER }

    public static MetricDimension dimension(Metric metric) {
        return switch (metric) {
            case COLD_STORAGE_TEMPERATURE, PROCESS_CORE_TEMPERATURE -> MetricDimension.TEMPERATURE;
            case ROOM_HUMIDITY, BATCH_YIELD_PERCENT, QUALITY_FAIL_RATE_PERCENT
                    -> MetricDimension.PERCENT;
            case EQUIPMENT_DOWNTIME_MINUTES -> MetricDimension.DURATION_MINUTES;
            case INVENTORY_DAYS_ON_HAND -> MetricDimension.DAYS;
            case OVERDUE_CALIBRATION_COUNT, OVERDUE_SANITATION_COUNT -> MetricDimension.COUNT;
        };
    }

    public static String canonicalUnit(MetricDimension dimension) {
        return switch (dimension) {
            case TEMPERATURE -> "Cel";
            case PERCENT -> "%";
            case DURATION_MINUTES -> "min";
            case COUNT -> "count";
            case DAYS -> "d";
        };
    }

    /** `alert-policy/v1`: severity alone fixes the response posture. */
    public static EscalationRole escalationRole(Severity severity) {
        return switch (severity) {
            case INFO -> EscalationRole.QUALITY_INSPECTOR;
            case WARNING -> EscalationRole.PRODUCTION_SUPERVISOR;
            case CRITICAL -> EscalationRole.QUALITY_MANAGER;
        };
    }

    public static int acknowledgeSlaMinutes(Severity severity) {
        return switch (severity) {
            case INFO -> 1440;
            case WARNING -> 240;
            case CRITICAL -> 30;
        };
    }

    public static int autoEscalateAfterMinutes(Severity severity) {
        return switch (severity) {
            case INFO -> 0;
            case WARNING -> 480;
            case CRITICAL -> 60;
        };
    }

    public static boolean twoPersonAcknowledgeRequired(Severity severity) {
        return severity == Severity.CRITICAL;
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record ModuleConfigPin(String draftId, String desiredDigest) {
        public ModuleConfigPin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PolicyInput(
            String policyCode,
            String title,
            String moduleId,
            Metric metric,
            Comparison comparison,
            BigDecimal thresholdValue,
            BigDecimal upperThresholdValue,
            Integer evaluationWindowMinutes,
            Integer consecutiveBreaches,
            Severity severity,
            List<RecipientRole> recipientRoles,
            Integer suppressionWindowMinutes,
            String changeReason) {
        public PolicyInput {
            policyCode = clean(policyCode).toUpperCase(Locale.ROOT);
            title = clean(title);
            moduleId = clean(moduleId).toLowerCase(Locale.ROOT);
            recipientRoles = recipientRoles == null ? List.of() : List.copyOf(recipientRoles);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedPolicy(
            int ordinal,
            String policyCode,
            String title,
            String moduleId,
            String moduleLabel,
            String moduleSnapshotDigest,
            Metric metric,
            MetricDimension metricDimension,
            String canonicalUnit,
            Comparison comparison,
            BigDecimal thresholdValue,
            BigDecimal upperThresholdValue,
            int evaluationWindowMinutes,
            int consecutiveBreaches,
            Severity severity,
            List<RecipientRole> recipientRoles,
            int suppressionWindowMinutes,
            EscalationRole escalationRole,
            int acknowledgeSlaMinutes,
            int autoEscalateAfterMinutes,
            boolean twoPersonAcknowledgeRequired,
            String changeReason) {
        public DerivedPolicy {
            recipientRoles = List.copyOf(recipientRoles);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            ModuleConfigPin moduleConfig,
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
            String moduleSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int alertRuleWriteCount,
            int alertEventWriteCount,
            int notificationWriteCount,
            int spelExpressionCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("modules");
            policies = List.copyOf(policies);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 2 + policies.size() : 0;
            alertRuleWriteCount = 0;
            alertEventWriteCount = 0;
            notificationWriteCount = 0;
            spelExpressionCount = 0;
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
            int alertRuleWriteCount,
            int alertEventWriteCount,
            int notificationWriteCount,
            int spelExpressionCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            alertRuleWriteCount = 0;
            alertEventWriteCount = 0;
            notificationWriteCount = 0;
            spelExpressionCount = 0;
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
            int alertRuleWriteCount,
            int alertEventWriteCount,
            int notificationWriteCount,
            int spelExpressionCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            policies = List.copyOf(policies);
            alertRuleWriteCount = 0;
            alertEventWriteCount = 0;
            notificationWriteCount = 0;
            spelExpressionCount = 0;
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
