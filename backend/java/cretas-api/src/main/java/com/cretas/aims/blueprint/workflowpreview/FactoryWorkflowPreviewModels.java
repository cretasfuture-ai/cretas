package com.cretas.aims.blueprint.workflowpreview;

import com.cretas.aims.blueprint.canonical.FactoryRouteIntentCompiler.RouteCompilation;
import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable wire contracts for the B22 read-only Workflow DRAFT preview. */
public final class FactoryWorkflowPreviewModels {

    public static final String SCHEMA_VERSION = "factory-workflow-draft-preview/v1";
    public static final String COMPILER_VERSION = "factory-workflow-draft-compiler/v1";
    public static final String LAYOUT_VERSION = "factory-workflow-layered-layout/v1";

    private FactoryWorkflowPreviewModels() {
    }

    public enum MaterialKind { RAW_MATERIAL, SEMI_FINISHED, FINISHED_GOOD }

    public enum Severity { BLOCKING, WARNING }

    public enum DiffAction { CREATE, UPDATE, KEEP, CONFLICT, UNSUPPORTED }

    public record ProcessRef(String id, String factoryId, String name, boolean active) {
        public ProcessRef {
            id = clean(id); factoryId = clean(factoryId); name = clean(name);
        }
    }

    public record MaterialRef(
            String id, String factoryId, String name, MaterialKind kind, String canonicalUnit, boolean active) {
        public MaterialRef {
            id = clean(id); factoryId = clean(factoryId); name = clean(name); canonicalUnit = clean(canonicalUnit);
        }
    }

    public record ResolutionCatalog(
            String factoryId, String version, String digest,
            List<ProcessRef> processes, List<MaterialRef> materials) {
        public ResolutionCatalog {
            factoryId = clean(factoryId); version = clean(version); digest = clean(digest);
            processes = immutable(processes); materials = immutable(materials);
        }
    }

    public record BomInputSlot(
            String processRouteNodeId, String materialLookupKey, String requestedUnit,
            List<String> evidenceRefs) {
        public BomInputSlot {
            processRouteNodeId = clean(processRouteNodeId); materialLookupKey = clean(materialLookupKey);
            requestedUnit = clean(requestedUnit); evidenceRefs = immutable(evidenceRefs);
        }
    }

    public record WipBinding(
            String producerRouteNodeId, String materialLookupKey, String requestedUnit,
            List<String> evidenceRefs) {
        public WipBinding {
            producerRouteNodeId = clean(producerRouteNodeId); materialLookupKey = clean(materialLookupKey);
            requestedUnit = clean(requestedUnit); evidenceRefs = immutable(evidenceRefs);
        }
    }

    public record ExistingDraft(
            ProductProcessWorkflowDTO definition, Long lockVersion, String revisionHash) {
        public ExistingDraft { revisionHash = clean(revisionHash); }
    }

    public record CompileRequest(
            String factoryId,
            String sourceDigest,
            String catalogVersion,
            String catalogDigest,
            String unitCatalogDigest,
            String productLookupKey,
            RouteCompilation route,
            ResolutionCatalog catalog,
            List<BomInputSlot> bomInputs,
            List<WipBinding> wipBindings,
            ExistingDraft existingDraft) {
        public CompileRequest {
            factoryId = clean(factoryId); sourceDigest = clean(sourceDigest);
            catalogVersion = clean(catalogVersion); catalogDigest = clean(catalogDigest);
            unitCatalogDigest = clean(unitCatalogDigest); productLookupKey = clean(productLookupKey);
            bomInputs = immutable(bomInputs); wipBindings = immutable(wipBindings);
        }
    }

    public record PreviewIssue(
            String code, Severity severity, String phase, String target,
            String message, String recovery) {
        public PreviewIssue {
            code = clean(code); severity = severity == null ? Severity.BLOCKING : severity;
            phase = clean(phase); target = clean(target); message = clean(message); recovery = clean(recovery);
        }
    }

    public record EntityDiff(
            String entityType, String entityId, DiffAction action,
            String beforeDigest, String afterDigest, List<String> changedFields) {
        public EntityDiff {
            entityType = clean(entityType); entityId = clean(entityId);
            action = action == null ? DiffAction.CONFLICT : action;
            beforeDigest = clean(beforeDigest); afterDigest = clean(afterDigest);
            changedFields = immutable(changedFields);
        }
    }

    public record WorkflowChangeSet(
            String targetFactoryId,
            String targetProductTypeId,
            DiffAction action,
            Long expectedLockVersion,
            String expectedRevisionHash,
            String previewDigest,
            List<EntityDiff> entityDiffs,
            int expectedDraftMutationCount,
            int expectedActiveMutationCount,
            String materializationState,
            int actualBusinessWriteCount) {
        public WorkflowChangeSet {
            targetFactoryId = clean(targetFactoryId); targetProductTypeId = clean(targetProductTypeId);
            action = action == null ? DiffAction.CONFLICT : action;
            expectedRevisionHash = clean(expectedRevisionHash); previewDigest = clean(previewDigest);
            entityDiffs = immutable(entityDiffs); expectedActiveMutationCount = 0;
            materializationState = "NOT_MATERIALIZED"; actualBusinessWriteCount = 0;
        }
    }

    public record ValidationReceipt(
            boolean formalStructureValid,
            String validator,
            List<String> assertions,
            int businessWriteCount) {
        public ValidationReceipt {
            validator = clean(validator); assertions = immutable(assertions); businessWriteCount = 0;
        }
    }

    public record WorkflowDraftPreview(
            String schemaVersion,
            String compilerVersion,
            String status,
            String sourceDigest,
            String businessDigest,
            String layoutVersion,
            String layoutDigest,
            ProductProcessWorkflowDTO desiredDefinition,
            WorkflowChangeSet changeSet,
            ValidationReceipt validation,
            List<PreviewIssue> issues,
            String publicationState,
            String activationState,
            String runtimeBindingState,
            int actualBusinessWriteCount) {
        public WorkflowDraftPreview {
            schemaVersion = SCHEMA_VERSION; compilerVersion = COMPILER_VERSION;
            status = clean(status); sourceDigest = clean(sourceDigest); businessDigest = clean(businessDigest);
            layoutVersion = LAYOUT_VERSION; layoutDigest = clean(layoutDigest); issues = immutable(issues);
            publicationState = "NOT_PUBLISHED"; activationState = "NOT_ACTIVATED";
            runtimeBindingState = "NOT_BOUND_TO_RUNTIME"; actualBusinessWriteCount = 0;
        }
    }

    public record BusinessNode(String id, String kind, Map<String, Object> data) {
        public BusinessNode { id = clean(id); kind = clean(kind); data = data == null ? Map.of() : Map.copyOf(data); }
    }

    public record BusinessEdge(
            String id, String source, String sourceHandle, String target, String targetHandle) {
        public BusinessEdge {
            id = clean(id); source = clean(source); sourceHandle = clean(sourceHandle);
            target = clean(target); targetHandle = clean(targetHandle);
        }
    }

    public record BusinessSnapshot(
            String factoryId, String productTypeId, int schemaVersion,
            List<BusinessNode> nodes, List<BusinessEdge> edges) {
        public BusinessSnapshot {
            factoryId = clean(factoryId); productTypeId = clean(productTypeId);
            nodes = immutable(nodes); edges = immutable(edges);
        }
    }

    static String clean(String value) { return Objects.requireNonNullElse(value, "").trim(); }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
