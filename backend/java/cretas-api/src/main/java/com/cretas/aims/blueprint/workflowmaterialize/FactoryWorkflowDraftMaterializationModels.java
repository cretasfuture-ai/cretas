package com.cretas.aims.blueprint.workflowmaterialize;

import com.cretas.aims.blueprint.workflowpreview.FactoryWorkflowPreviewModels.CompileRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.cretas.aims.security.AuthenticatedActorType;

import java.util.List;
import java.util.Objects;

/** Typed B24 operation preview, command and receipt contracts. */
public final class FactoryWorkflowDraftMaterializationModels {

    public static final String SCHEMA_VERSION = "factory-workflow-draft-materialization/v1";

    private FactoryWorkflowDraftMaterializationModels() {
    }

    public enum DraftMode {
        CREATE_NEW_ONLY,
        MERGE_EXISTING,
        SNAPSHOT_AND_REPLACE
    }

    /**
     * A BOM target is an already-existing DRAFT. B24 never invents a recipe or quantity.
     * Expected pins are part of the confirmed operation so concurrent edits fail closed.
     */
    public record BomPinTarget(
            String recipeId,
            Long expectedWorkflowRevisionId,
            String expectedWorkflowRevisionHash) {
        public BomPinTarget {
            recipeId = clean(recipeId);
            expectedWorkflowRevisionHash = clean(expectedWorkflowRevisionHash);
        }
    }

    /**
     * Internal trusted command. No HTTP controller accepts a client-authored Workflow graph.
     * The service recompiles {@code compileRequest} and compares both confirmation digests.
     */
    public record DraftMaterializationRequest(
            String idempotencyKey,
            String confirmedPreviewDigest,
            String confirmedOperationDigest,
            DraftMode mode,
            Long expectedLockVersion,
            String expectedRevisionHash,
            Long operatorId,
            AuthenticatedActorType actorType,
            CompileRequest compileRequest,
            List<BomPinTarget> bomTargets) {
        public DraftMaterializationRequest(String idempotencyKey, String confirmedPreviewDigest,
                                           String confirmedOperationDigest, DraftMode mode,
                                           Long expectedLockVersion, String expectedRevisionHash,
                                           Long operatorId, CompileRequest compileRequest,
                                           List<BomPinTarget> bomTargets) {
            this(idempotencyKey, confirmedPreviewDigest, confirmedOperationDigest, mode,
                    expectedLockVersion, expectedRevisionHash, operatorId,
                    operatorId == null ? null : AuthenticatedActorType.FACTORY_USER,
                    compileRequest, bomTargets);
        }
        public DraftMaterializationRequest {
            idempotencyKey = clean(idempotencyKey);
            confirmedPreviewDigest = clean(confirmedPreviewDigest);
            confirmedOperationDigest = clean(confirmedOperationDigest);
            mode = mode == null ? DraftMode.CREATE_NEW_ONLY : mode;
            expectedRevisionHash = clean(expectedRevisionHash);
            bomTargets = immutable(bomTargets);
        }
    }

    public record OperationIssue(
            String code,
            String target,
            String message,
            String recovery) {
        public OperationIssue {
            code = clean(code);
            target = clean(target);
            message = clean(message);
            recovery = clean(recovery);
        }
    }

    /** Read-only confirmation surface. Calling preview never creates a ledger or business row. */
    public record DraftOperationPreview(
            String schemaVersion,
            String status,
            String sourceDigest,
            String previewDigest,
            String operationDigest,
            DraftMode mode,
            String targetFactoryId,
            String targetProductTypeId,
            Long expectedLockVersion,
            String expectedRevisionHash,
            List<BomPinTarget> bomTargets,
            int expectedWorkflowDraftWrites,
            int expectedBomDraftWrites,
            List<OperationIssue> issues,
            String publicationState,
            String activationState,
            int actualBusinessWriteCount) {
        public DraftOperationPreview {
            schemaVersion = SCHEMA_VERSION;
            status = clean(status);
            sourceDigest = clean(sourceDigest);
            previewDigest = clean(previewDigest);
            operationDigest = clean(operationDigest);
            targetFactoryId = clean(targetFactoryId);
            targetProductTypeId = clean(targetProductTypeId);
            expectedRevisionHash = clean(expectedRevisionHash);
            bomTargets = immutable(bomTargets);
            issues = immutable(issues);
            publicationState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            actualBusinessWriteCount = 0;
        }
    }

    public record WorkflowDraftReadback(
            Long workflowId,
            String factoryId,
            String productTypeId,
            String status,
            Integer definitionVersion,
            Long lockVersion,
            Long revisionId,
            String revisionHash,
            String businessDigest,
            int nodeCount,
            int edgeCount) {
        public WorkflowDraftReadback {
            factoryId = clean(factoryId);
            productTypeId = clean(productTypeId);
            status = clean(status);
            revisionHash = clean(revisionHash);
            businessDigest = clean(businessDigest);
        }
    }

    public record BomDraftReadback(
            String recipeId,
            String productTypeId,
            String status,
            Long workflowId,
            Long workflowRevisionId,
            String workflowRevisionHash,
            Integer workflowDefinitionVersion) {
        public BomDraftReadback {
            recipeId = clean(recipeId);
            productTypeId = clean(productTypeId);
            status = clean(status);
            workflowRevisionHash = clean(workflowRevisionHash);
        }
    }

    public record DraftMaterializationReceipt(
            String schemaVersion,
            String receiptId,
            String status,
            String requestDigest,
            String previewDigest,
            String operationDigest,
            DraftMode mode,
            WorkflowDraftReadback workflow,
            List<BomDraftReadback> bomDrafts,
            String bomReadiness,
            String editorDeepLink,
            boolean idempotentReplay,
            int thisInvocationBusinessWriteCount,
            int originalBusinessWriteCount,
            String publicationState,
            String activationState,
            String runtimeBindingState,
            String recovery) {
        public DraftMaterializationReceipt {
            schemaVersion = SCHEMA_VERSION;
            receiptId = clean(receiptId);
            status = clean(status);
            requestDigest = clean(requestDigest);
            previewDigest = clean(previewDigest);
            operationDigest = clean(operationDigest);
            bomDrafts = immutable(bomDrafts);
            bomReadiness = clean(bomReadiness);
            editorDeepLink = clean(editorDeepLink);
            publicationState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            runtimeBindingState = "NOT_BOUND_TO_RUNTIME";
            recovery = clean(recovery);
        }

        /**
         * The capability draft id is the persisted Workflow primary key, not the ledger receipt
         * UUID. Keep this as a derived read-only property so existing record constructors and
         * historical ledger JSON (which predates {@code draftId}) remain compatible.
         */
        @JsonProperty(value = "draftId", access = JsonProperty.Access.READ_ONLY)
        public String getDraftId() {
            return workflow == null || workflow.workflowId() == null
                    ? null : String.valueOf(workflow.workflowId());
        }
    }

    static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }

    private static <T> List<T> immutable(List<T> values) {
        return values == null ? List.of() : List.copyOf(values);
    }
}
