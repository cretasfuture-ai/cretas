package com.cretas.aims.service.hygieniczonedraft;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Closed, wire-neutral contract for hygienic zoning DRAFTs. */
public final class HygienicZoneDraftModels {
    public static final String SCHEMA_VERSION = "hygienic-zone-draft/v1";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CONTRACT:factory";

    private HygienicZoneDraftModels() {
    }

    public enum HygieneLevel { LOW_CARE, HIGH_CARE, HIGH_RISK, PERSONNEL_HYGIENE, WASTE }
    public enum TemperatureClass { AMBIENT, CHILLED, FROZEN, PROCESS_DEFINED }
    public enum EntryPolicy { OPEN, GOWNING_REQUIRED, HANDWASH_REQUIRED, SANITIZE_REQUIRED }
    public enum FlowType { PERSONNEL, MATERIAL, AIR }
    public enum FlowDirection { ONE_WAY, BIDIRECTIONAL }
    public enum CrossingPolicy { ALLOWED, CONTROLLED, FORBIDDEN }
    public enum ControlPoint { NONE, AIRLOCK, PASS_THROUGH, SANITIZATION, GOWNING }

    public record RequestedZone(
            String zoneCode,
            String zoneName,
            HygieneLevel hygieneLevel,
            String purpose,
            TemperatureClass temperatureClass,
            EntryPolicy entryPolicy,
            String changeReason) {
        public RequestedZone {
            zoneCode = code(zoneCode);
            zoneName = clean(zoneName);
            purpose = clean(purpose);
            changeReason = clean(changeReason);
        }
    }

    public record RequestedFlow(
            FlowType flowType,
            String fromZoneCode,
            String toZoneCode,
            FlowDirection direction,
            CrossingPolicy crossingPolicy,
            ControlPoint controlPoint,
            String rationale) {
        public RequestedFlow {
            fromZoneCode = code(fromZoneCode);
            toZoneCode = code(toZoneCode);
            rationale = clean(rationale);
        }
    }

    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            List<RequestedZone> zones,
            List<RequestedFlow> flows,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase();
            zones = zones == null ? List.of() : List.copyOf(zones);
            flows = flows == null ? List.of() : List.copyOf(flows);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase();
            idempotencyKey = clean(idempotencyKey);
        }
    }

    /** Server-authenticated actor context; never populated from AI-authored ChangeSet fields. */
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
            String status,
            String factoryId,
            List<String> dependencies,
            List<RequestedZone> zones,
            List<RequestedFlow> flows,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            int runtimeFacilityWriteCount,
            int haccpWriteCount,
            int ssopWriteCount,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("factory");
            zones = List.copyOf(zones);
            flows = List.copyOf(flows);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 1 + zones.size() + flows.size() : 0;
            runtimeFacilityWriteCount = 0;
            haccpWriteCount = 0;
            ssopWriteCount = 0;
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
            int zoneCount,
            int flowCount,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            int runtimeFacilityWriteCount,
            int haccpWriteCount,
            int ssopWriteCount,
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
            String desiredDigest,
            List<RequestedZone> zones,
            List<RequestedFlow> flows,
            int runtimeFacilityWriteCount,
            int haccpWriteCount,
            int ssopWriteCount,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            zones = List.copyOf(zones);
            flows = List.copyOf(flows);
            runtimeFacilityWriteCount = 0;
            haccpWriteCount = 0;
            ssopWriteCount = 0;
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

    static String code(String value) {
        return clean(value).toUpperCase(Locale.ROOT);
    }
}
