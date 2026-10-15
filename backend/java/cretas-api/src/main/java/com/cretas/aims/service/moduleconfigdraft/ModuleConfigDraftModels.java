package com.cretas.aims.service.moduleconfigdraft;

import java.util.List;

/** Typed wire-neutral records for {@link ModuleConfigDraftCommand}. */
public final class ModuleConfigDraftModels {

    public static final String SCHEMA_VERSION = "module-config-draft/v1";
    public static final String HISTORICAL_READ_POLICY = "RETAIN_EXISTING_RECORDS_READABLE";
    public static final String DEPENDENCY_BASIS = "CAPABILITY_CONTRACT:factory";

    private ModuleConfigDraftModels() {
    }

    /** The request has no raw gate codes, table names, JSON patches or executable expressions. */
    public record AuthenticatedOperator(com.cretas.aims.security.ActorRef actor, String factoryId, java.util.Set<String> permissions) {
        public AuthenticatedOperator(long operatorId, String factoryId, java.util.Set<String> permissions) {
            this(new com.cretas.aims.security.ActorRef(com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, operatorId), factoryId, permissions);
        }
        public long operatorId() { return actor.id(); }
    }

    public record RequestedModule(String moduleId, Boolean enabled, String rationale) {
        public RequestedModule {
            moduleId = clean(moduleId).toLowerCase();
            rationale = canonicalText(rationale);
        }
    }

    public record DraftRequest(
            String factoryId,
            String blueprintId,
            long blueprintVersion,
            String sourceDigest,
            List<RequestedModule> modules,
            String confirmedPreviewDigest,
            String idempotencyKey,
            long operatorId) {
        public DraftRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            sourceDigest = clean(sourceDigest).toLowerCase();
            modules = modules == null ? List.of() : List.copyOf(modules);
            confirmedPreviewDigest = clean(confirmedPreviewDigest).toLowerCase();
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record GateLayerSnapshot(
            boolean effective,
            String reason,
            Integer publishedVersion,
            int rowsAtPublishedVersion,
            List<String> staleGateCodes,
            String pendingWorkflowStatus,
            List<String> pendingPublishGateCodes) {
        public GateLayerSnapshot {
            staleGateCodes = List.copyOf(staleGateCodes);
            pendingPublishGateCodes = List.copyOf(pendingPublishGateCodes);
        }
    }

    public record ResolvedModule(
            int ordinal,
            String moduleId,
            String label,
            String detail,
            String carrier,
            boolean desiredEnabled,
            String rationale,
            boolean currentFrontendVisible,
            boolean currentBackendEnabled,
            String currentState,
            boolean backendGated,
            List<String> gateCodes,
            String historicalReadPolicy) {
        public ResolvedModule {
            gateCodes = List.copyOf(gateCodes);
            historicalReadPolicy = HISTORICAL_READ_POLICY;
        }
    }

    public record DraftIssue(String code, String target, String message, String recovery) {
    }

    public record DraftPreview(
            String schemaVersion,
            String status,
            String factoryId,
            List<String> dependencies,
            boolean dependencyClosureComplete,
            String dependencyBasis,
            String catalogDigest,
            GateLayerSnapshot gateLayer,
            List<ResolvedModule> modules,
            int moduleCount,
            int gateCodeCount,
            String sourceSnapshotDigest,
            String desiredDigest,
            String previewDigest,
            List<DraftIssue> issues,
            int expectedBusinessWriteCount,
            int expectedPersistedRowCount,
            String historicalReadPolicy,
            String publishState,
            String activationState) {
        public DraftPreview {
            schemaVersion = SCHEMA_VERSION;
            dependencies = List.of("factory");
            dependencyBasis = DEPENDENCY_BASIS;
            modules = List.copyOf(modules);
            issues = List.copyOf(issues);
            expectedBusinessWriteCount = "READY".equals(status) ? 1 : 0;
            expectedPersistedRowCount = "READY".equals(status) ? 1 + moduleCount + gateCodeCount : 0;
            historicalReadPolicy = HISTORICAL_READ_POLICY;
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
            String catalogDigest,
            String desiredDigest,
            String previewDigest,
            String requestDigest,
            int moduleCount,
            int gateCodeCount,
            boolean dependencyClosureComplete,
            String historicalReadPolicy,
            long lockVersion,
            boolean replayed,
            int businessWriteCount,
            int originalBusinessWriteCount,
            int persistedRowCount,
            String publishState,
            String activationState,
            String recovery) {
        public DraftReceipt {
            schemaVersion = SCHEMA_VERSION;
            historicalReadPolicy = HISTORICAL_READ_POLICY;
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record DraftReadback(
            String schemaVersion,
            String factoryId,
            String draftId,
            String status,
            List<ResolvedModule> modules,
            int gateCodeCount,
            boolean dependencyClosureComplete,
            String historicalReadPolicy,
            String publishState,
            String activationState) {
        public DraftReadback {
            schemaVersion = SCHEMA_VERSION;
            modules = List.copyOf(modules);
            historicalReadPolicy = HISTORICAL_READ_POLICY;
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

    private static String canonicalText(String value) {
        return clean(value).replaceAll("\\s+", " ");
    }
}
