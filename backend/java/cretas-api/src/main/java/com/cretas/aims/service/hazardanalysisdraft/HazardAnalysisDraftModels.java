package com.cretas.aims.service.hazardanalysisdraft;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class HazardAnalysisDraftModels {
    public static final String SCHEMA_VERSION = "hazard-analysis-draft/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CONTRACT:routes+bom";
    public static final String RISK_ALGORITHM_VERSION = "hazard-risk-matrix/v1";

    private HazardAnalysisDraftModels() {}

    public enum HazardType { BIOLOGICAL, CHEMICAL, PHYSICAL }

    public enum Severity {
        NEGLIGIBLE(1), MINOR(2), MODERATE(3), MAJOR(4), CRITICAL(5);
        private final int weight;
        Severity(int weight) { this.weight = weight; }
        public int weight() { return weight; }
    }

    public enum Likelihood {
        RARE(1), UNLIKELY(2), POSSIBLE(3), LIKELY(4), ALMOST_CERTAIN(5);
        private final int weight;
        Likelihood(int weight) { this.weight = weight; }
        public int weight() { return weight; }
    }

    public enum RiskBand { LOW, MEDIUM, HIGH, CRITICAL }

    /** A prevention class only; CCP/OPRP selection belongs to critical-controls. */
    public enum ControlMeasureType {
        PREREQUISITE_PROGRAM,
        PROCESS_CONTROL,
        SUPPLIER_CONTROL,
        SANITATION_CONTROL,
        ALLERGEN_CONTROL,
        TEMPERATURE_CONTROL
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record BomPin(String draftId, String desiredDigest) {
        public BomPin {
            draftId = clean(draftId);
            desiredDigest = digestValue(desiredDigest);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record HazardItem(
            String routeNodeId,
            String bomItemId,
            HazardType hazardType,
            String hazardDescription,
            String causeSummary,
            Severity severity,
            Likelihood likelihood,
            ControlMeasureType controlMeasureType,
            String preventiveMeasure,
            List<String> evidenceAuthorityCodes,
            String changeReason) {
        public HazardItem {
            routeNodeId = clean(routeNodeId);
            bomItemId = clean(bomItemId);
            hazardDescription = clean(hazardDescription);
            causeSummary = clean(causeSummary);
            preventiveMeasure = clean(preventiveMeasure);
            evidenceAuthorityCodes = immutableStrings(evidenceAuthorityCodes);
            changeReason = clean(changeReason);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            String routeProductCode,
            String routeSnapshotDigest,
            BomPin bom,
            List<HazardItem> items,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = digestValue(sourceDigest);
            routeProductCode = clean(routeProductCode);
            routeSnapshotDigest = digestValue(routeSnapshotDigest);
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
            String routeSnapshotDigest,
            String bomSnapshotDigest,
            String authoritySnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedModuleDraftWriteCount,
            int expectedPersistedRowCount,
            int liveHaccpWriteCount,
            int criticalControlWriteCount,
            int monitoringWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int qualityResultWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("routes", "bom");
            issues = List.copyOf(issues);
            int evidence = desired == null ? 0 : desired.items().stream()
                    .mapToInt(item -> item == null ? 0 : item.evidenceAuthorityCodes().size()).sum();
            expectedModuleDraftWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status)
                    ? 1 + desired.items().size() + evidence : 0;
            liveHaccpWriteCount = 0;
            criticalControlWriteCount = 0;
            monitoringWriteCount = 0;
            inventoryWriteCount = 0;
            productionWriteCount = 0;
            qualityResultWriteCount = 0;
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
            String routeSnapshotDigest,
            String bomSnapshotDigest,
            String authoritySnapshotDigest,
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
            int liveHaccpWriteCount,
            int criticalControlWriteCount,
            int monitoringWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int qualityResultWriteCount,
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
            int liveHaccpWriteCount,
            int criticalControlWriteCount,
            int monitoringWriteCount,
            int inventoryWriteCount,
            int productionWriteCount,
            int qualityResultWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            liveHaccpWriteCount = 0;
            criticalControlWriteCount = 0;
            monitoringWriteCount = 0;
            inventoryWriteCount = 0;
            productionWriteCount = 0;
            qualityResultWriteCount = 0;
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

    public static int riskScore(Severity severity, Likelihood likelihood) {
        if (severity == null || likelihood == null) return 0;
        return severity.weight() * likelihood.weight();
    }

    public static RiskBand riskBand(int score) {
        if (score >= 17) return RiskBand.CRITICAL;
        if (score >= 10) return RiskBand.HIGH;
        if (score >= 5) return RiskBand.MEDIUM;
        return RiskBand.LOW;
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of()
                : Collections.unmodifiableList(new ArrayList<>(values));
    }

    private static List<String> immutableStrings(List<String> values) {
        if (values == null) return List.of();
        List<String> result = values.stream().map(HazardAnalysisDraftModels::clean).toList();
        return Collections.unmodifiableList(new ArrayList<>(result));
    }

    static String clean(String value) { return value == null ? "" : value.trim(); }
    static String digestValue(String value) { return clean(value).toLowerCase(java.util.Locale.ROOT); }
}
