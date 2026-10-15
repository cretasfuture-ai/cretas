package com.cretas.aims.blueprint.compilation;

import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, serialization-friendly contracts for the read-only Blueprint compilation kernel. */
public final class FactoryBlueprintCompilationModels {

    public static final String SCHEMA_VERSION = "factory-blueprint-artifact/v1";
    public static final String CHANGE_SET_SCHEMA_VERSION = "factory-capability-change-set/v1";

    private FactoryBlueprintCompilationModels() {
    }

    public enum FactSource {
        USER_CONFIRMED,
        EXISTING_CONFIG,
        AI_SUGGESTED,
        AI_INFERRED
    }

    public enum ConfirmationState {
        CONFIRMED,
        PENDING,
        REJECTED
    }

    public enum IssueSeverity {
        BLOCKING,
        WARNING
    }

    public enum AdapterStatus {
        AVAILABLE,
        MISSING
    }

    public enum ChangeStatus {
        PREVIEW_READY,
        BLOCKED,
        NOT_REQUESTED
    }

    public record SourceFact(
            String factId,
            String factoryId,
            String capabilityId,
            String subjectType,
            String subjectId,
            String fieldPath,
            String valueType,
            String value,
            FactSource source,
            ConfirmationState confirmationState,
            List<String> evidenceRefs) {
        public SourceFact {
            factId = clean(factId);
            factoryId = clean(factoryId);
            capabilityId = clean(capabilityId);
            subjectType = clean(subjectType);
            subjectId = clean(subjectId);
            fieldPath = clean(fieldPath);
            valueType = clean(valueType);
            value = clean(value);
            source = source == null ? FactSource.AI_INFERRED : source;
            confirmationState = confirmationState == null ? ConfirmationState.PENDING : confirmationState;
            evidenceRefs = immutable(evidenceRefs);
        }

        public String identityKey() {
            return subjectType + "\u001f" + subjectId + "\u001f" + fieldPath;
        }

        public boolean authoritative() {
            return confirmationState == ConfirmationState.CONFIRMED
                    && (source == FactSource.USER_CONFIRMED || source == FactSource.EXISTING_CONFIG);
        }
    }

    public record CompilationRequest(
            String factoryId,
            String blueprintId,
            int blueprintVersion,
            String catalogVersion,
            String catalogDigest,
            List<String> capabilityIds,
            List<SourceFact> facts,
            List<RouteCompilation> routeCompilations) {
        public CompilationRequest {
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            catalogVersion = clean(catalogVersion);
            catalogDigest = clean(catalogDigest);
            capabilityIds = immutable(capabilityIds);
            facts = immutable(facts);
            routeCompilations = immutable(routeCompilations);
        }
    }

    public record CompilationIssue(
            String code,
            IssueSeverity severity,
            String capabilityId,
            String factId,
            String message,
            String recovery) {
        public CompilationIssue {
            code = clean(code);
            severity = severity == null ? IssueSeverity.BLOCKING : severity;
            capabilityId = clean(capabilityId);
            factId = clean(factId);
            message = clean(message);
            recovery = clean(recovery);
        }
    }

    public record CapabilityArtifact(
            String capabilityId,
            String artifactType,
            String targetSchemaVersion,
            Map<String, Object> payload,
            List<String> sourceFactRefs,
            String publishState,
            int businessWriteCount) {
        public CapabilityArtifact {
            capabilityId = clean(capabilityId);
            artifactType = clean(artifactType);
            targetSchemaVersion = clean(targetSchemaVersion);
            payload = payload == null ? Map.of() : Map.copyOf(payload);
            sourceFactRefs = immutable(sourceFactRefs);
            publishState = "NOT_PUBLISHED";
            businessWriteCount = 0;
        }
    }

    public record CapabilityChange(
            String capabilityId,
            ChangeStatus status,
            List<String> fieldPaths,
            List<String> sourceFactRefs,
            String blockingReason) {
        public CapabilityChange {
            capabilityId = clean(capabilityId);
            status = status == null ? ChangeStatus.NOT_REQUESTED : status;
            fieldPaths = immutable(fieldPaths);
            sourceFactRefs = immutable(sourceFactRefs);
            blockingReason = clean(blockingReason);
        }
    }

    public record CapabilityChangeSet(
            String schemaVersion,
            List<CapabilityChange> changes,
            int businessWriteCount) {
        public CapabilityChangeSet {
            schemaVersion = CHANGE_SET_SCHEMA_VERSION;
            changes = immutable(changes);
            businessWriteCount = 0;
        }
    }

    public record AdapterReadiness(
            String capabilityId,
            AdapterStatus status,
            String adapterId,
            String artifactType,
            String targetSchemaVersion,
            String nextStep) {
        public AdapterReadiness {
            capabilityId = clean(capabilityId);
            status = status == null ? AdapterStatus.MISSING : status;
            adapterId = clean(adapterId);
            artifactType = clean(artifactType);
            targetSchemaVersion = clean(targetSchemaVersion);
            nextStep = clean(nextStep);
        }
    }

    public record CompilationArtifact(
            String schemaVersion,
            String factoryId,
            String blueprintId,
            int blueprintVersion,
            String catalogVersion,
            String catalogDigest,
            String contentDigest,
            String status,
            String executionMode,
            String publishState,
            String activationState,
            List<SourceFact> confirmedFacts,
            List<CapabilityArtifact> capabilityArtifacts,
            CapabilityChangeSet changeSet,
            List<AdapterReadiness> adapterReadiness,
            List<CompilationIssue> issues,
            int businessWriteCount) {
        public CompilationArtifact {
            schemaVersion = SCHEMA_VERSION;
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            catalogVersion = clean(catalogVersion);
            catalogDigest = clean(catalogDigest);
            contentDigest = clean(contentDigest);
            status = clean(status);
            executionMode = "PREVIEW_ONLY";
            publishState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            confirmedFacts = immutable(confirmedFacts);
            capabilityArtifacts = immutable(capabilityArtifacts);
            adapterReadiness = immutable(adapterReadiness);
            issues = immutable(issues);
            businessWriteCount = 0;
        }
    }

    static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
