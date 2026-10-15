package com.cretas.aims.service.emergencydefensedraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Closed, wire-neutral contract for emergency and food-defense PLAN/DRAFTs.
 *
 * <p>The immutable capability catalog v2 declares
 * {@code emergency-defense -> trace-recall + alerts}. The adapter contract previously declared
 * {@code factory + alerts}, omitting {@code trace-recall} entirely; B17 converges it. Both real
 * dependencies are pinned: every plan pins a same-lineage, readback-ready trace/recall PLAN draft
 * and alert-policy draft, and every plan's detection trigger names an actual alert policy.</p>
 *
 * <p>These are plans and drill templates. No real emergency action is taken: no recall event, no
 * recall action, no inventory or batch freeze, no alert event, no notification, no production stop
 * and no equipment command.</p>
 */
public final class EmergencyDefensePlanDraftModels {
    public static final String SCHEMA_VERSION = "emergency-defense-plan-draft/v1";
    public static final String ALGORITHM_VERSION = "emergency-defense-policy/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CATALOG:trace-recall+alerts";

    private EmergencyDefensePlanDraftModels() {
    }

    public enum ScenarioType {
        POWER_FAILURE, WATER_FAILURE, COLD_CHAIN_FAILURE, FIRE, FLOOD,
        INTENTIONAL_ADULTERATION, SUPPLY_INTERRUPTION, RECALL_DRILL
    }

    public enum ImpactTier { LOCALISED, LINE_WIDE, SITE_WIDE }

    public enum ContainmentAction {
        ISOLATE_AFFECTED_LOT, STOP_AFFECTED_LINE, STOP_SITE, SWITCH_TO_BACKUP_UTILITY,
        SECURE_ACCESS_POINTS, QUARANTINE_INCOMING
    }

    public enum ResponsibleRole {
        FACTORY_DIRECTOR, QUALITY_MANAGER, PRODUCTION_SUPERVISOR, WAREHOUSE_SUPERVISOR,
        MAINTENANCE_OWNER, SECURITY_OWNER
    }

    public enum EvidenceType { CHECKLIST, PHOTO, DRILL_REPORT, QUALITY_SIGNOFF, UTILITY_LOG }

    /** Server-derived; never accepted from the request. */
    public enum NotificationTier { INTERNAL_ONLY, INTERNAL_AND_CUSTOMER, INTERNAL_AND_REGULATOR }

    private static final Set<ScenarioType> FOOD_SAFETY_SCENARIOS = Set.of(
            ScenarioType.COLD_CHAIN_FAILURE, ScenarioType.FLOOD,
            ScenarioType.INTENTIONAL_ADULTERATION, ScenarioType.RECALL_DRILL);
    private static final Set<ScenarioType> TRACE_REQUIRED_SCENARIOS = Set.of(
            ScenarioType.COLD_CHAIN_FAILURE, ScenarioType.INTENTIONAL_ADULTERATION,
            ScenarioType.RECALL_DRILL);

    /** `emergency-defense-policy/v1`. Pure and total. */
    public static boolean foodSafetyImpacting(ScenarioType scenario) {
        return FOOD_SAFETY_SCENARIOS.contains(scenario);
    }

    /** Scenarios that can put product in the market require a pinned trace/recall plan. */
    public static boolean traceLinkRequired(ScenarioType scenario) {
        return TRACE_REQUIRED_SCENARIOS.contains(scenario);
    }

    public static NotificationTier notificationTier(ScenarioType scenario, ImpactTier impact) {
        if (scenario == ScenarioType.INTENTIONAL_ADULTERATION
                || (foodSafetyImpacting(scenario) && impact == ImpactTier.SITE_WIDE)) {
            return NotificationTier.INTERNAL_AND_REGULATOR;
        }
        if (foodSafetyImpacting(scenario)) {
            return NotificationTier.INTERNAL_AND_CUSTOMER;
        }
        return NotificationTier.INTERNAL_ONLY;
    }

    public static int containmentSlaMinutes(ImpactTier impact) {
        return switch (impact) {
            case LOCALISED -> 240;
            case LINE_WIDE -> 60;
            case SITE_WIDE -> 30;
        };
    }

    public static boolean drillRequired(ScenarioType scenario) {
        return foodSafetyImpacting(scenario);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record TraceRecallPin(String draftId, String desiredDigest) {
        public TraceRecallPin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record AlertPolicyPin(String draftId, String desiredDigest) {
        public AlertPolicyPin {
            draftId = clean(draftId);
            desiredDigest = clean(desiredDigest).toLowerCase(Locale.ROOT);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record PlanInput(
            String planCode,
            String title,
            ScenarioType scenarioType,
            ImpactTier impactTier,
            String detectionAlertPolicyCode,
            String traceRecallSegmentKey,
            List<ContainmentAction> containmentActions,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            Integer drillFrequencyDays,
            String changeReason) {
        public PlanInput {
            planCode = clean(planCode).toUpperCase(Locale.ROOT);
            title = clean(title);
            detectionAlertPolicyCode = clean(detectionAlertPolicyCode).toUpperCase(Locale.ROOT);
            traceRecallSegmentKey = clean(traceRecallSegmentKey);
            containmentActions = containmentActions == null
                    ? List.of() : List.copyOf(containmentActions);
            requiredEvidence = requiredEvidence == null ? List.of() : List.copyOf(requiredEvidence);
            changeReason = clean(changeReason);
        }
    }

    public record DerivedPlan(
            int ordinal,
            String planCode,
            String title,
            ScenarioType scenarioType,
            ImpactTier impactTier,
            String detectionAlertPolicyCode,
            String detectionAlertSeverity,
            String detectionAlertSnapshotDigest,
            String traceRecallSegmentKey,
            List<ContainmentAction> containmentActions,
            ResponsibleRole responsibleRole,
            List<EvidenceType> requiredEvidence,
            Integer drillFrequencyDays,
            boolean foodSafetyImpacting,
            boolean traceLinkRequired,
            boolean drillRequired,
            NotificationTier notificationTier,
            int containmentSlaMinutes,
            String changeReason) {
        public DerivedPlan {
            containmentActions = List.copyOf(containmentActions);
            requiredEvidence = List.copyOf(requiredEvidence);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            TraceRecallPin traceRecall,
            AlertPolicyPin alertPolicy,
            List<PlanInput> plans,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase(Locale.ROOT);
            plans = plans == null ? List.of() : List.copyOf(plans);
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
            List<DerivedPlan> plans,
            String sourceSnapshotDigest,
            String traceRecallSnapshotDigest,
            String alertPolicySnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int recallEventWriteCount,
            int recallActionWriteCount,
            int inventoryBatchFreezeWriteCount,
            int alertEventWriteCount,
            int notificationWriteCount,
            int productionStopWriteCount,
            int equipmentCommandWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            algorithmVersion = ALGORITHM_VERSION;
            dependencies = List.of("trace-recall", "alerts");
            plans = List.copyOf(plans);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 2 + plans.size() : 0;
            recallEventWriteCount = 0;
            recallActionWriteCount = 0;
            inventoryBatchFreezeWriteCount = 0;
            alertEventWriteCount = 0;
            notificationWriteCount = 0;
            productionStopWriteCount = 0;
            equipmentCommandWriteCount = 0;
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
            int planCount,
            int evidenceCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int recallEventWriteCount,
            int recallActionWriteCount,
            int inventoryBatchFreezeWriteCount,
            int alertEventWriteCount,
            int notificationWriteCount,
            int productionStopWriteCount,
            int equipmentCommandWriteCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            recallEventWriteCount = 0;
            recallActionWriteCount = 0;
            inventoryBatchFreezeWriteCount = 0;
            alertEventWriteCount = 0;
            notificationWriteCount = 0;
            productionStopWriteCount = 0;
            equipmentCommandWriteCount = 0;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<DerivedPlan> plans,
            String sourceSnapshotDigest,
            String desiredDigest,
            int recallEventWriteCount,
            int recallActionWriteCount,
            int inventoryBatchFreezeWriteCount,
            int alertEventWriteCount,
            int notificationWriteCount,
            int productionStopWriteCount,
            int equipmentCommandWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            plans = List.copyOf(plans);
            recallEventWriteCount = 0;
            recallActionWriteCount = 0;
            inventoryBatchFreezeWriteCount = 0;
            alertEventWriteCount = 0;
            notificationWriteCount = 0;
            productionStopWriteCount = 0;
            equipmentCommandWriteCount = 0;
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
