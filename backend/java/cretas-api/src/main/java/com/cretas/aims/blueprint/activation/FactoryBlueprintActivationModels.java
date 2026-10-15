package com.cretas.aims.blueprint.activation;

import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Typed B26 activation contracts. No raw SQL, script, graph or AI text is executable. */
public final class FactoryBlueprintActivationModels {

    public static final String PLAN_SCHEMA = "factory-blueprint-activation-plan/v1";
    public static final String RECEIPT_SCHEMA = "factory-blueprint-activation-receipt/v1";

    private FactoryBlueprintActivationModels() {
    }

    public enum PlanStatus { READY_FOR_APPROVAL, BLOCKED }

    public enum ModuleExecutionStatus { ACTIVATED, FAILED, BLOCKED_DEPENDENCY }

    public record DraftCandidate(
            String capabilityId,
            String draftId,
            long expectedRevision,
            String expectedDigest,
            String currentState,
            Map<String, String> externalDependencyRuntimeStates) {
        public DraftCandidate {
            capabilityId = clean(capabilityId);
            draftId = clean(draftId);
            expectedDigest = clean(expectedDigest);
            currentState = clean(currentState);
            externalDependencyRuntimeStates = externalDependencyRuntimeStates == null
                    ? Map.of() : Map.copyOf(externalDependencyRuntimeStates);
        }
    }

    public record ActivationPlanRequest(
            String factoryId,
            String blueprintId,
            int blueprintVersion,
            String blueprintDigest,
            long requestedBy,
            Set<String> requesterPermissions,
            List<DraftCandidate> candidates,
            ActorRef requestedActor) {
        public ActivationPlanRequest(String factoryId,
                String blueprintId,
                int blueprintVersion,
                String blueprintDigest,
                long requestedBy,
                Set<String> requesterPermissions,
                List<DraftCandidate> candidates) {
            this(factoryId, blueprintId, blueprintVersion, blueprintDigest, requestedBy, requesterPermissions, candidates, new ActorRef(AuthenticatedActorType.FACTORY_USER, requestedBy));
        }

        public ActivationPlanRequest(String factoryId,
                String blueprintId,
                int blueprintVersion,
                String blueprintDigest,
                ActorRef requestedActor,
                Set<String> requesterPermissions,
                List<DraftCandidate> candidates) {
            this(factoryId, blueprintId, blueprintVersion, blueprintDigest, requestedActor.id(), requesterPermissions, candidates, requestedActor);
        }

        public ActivationPlanRequest {
            Objects.requireNonNull(requestedActor, "requestedActor");
            if (requestedBy != requestedActor.id()) throw new IllegalArgumentException("Actor identity mismatch");
            factoryId = clean(factoryId);
            blueprintId = clean(blueprintId);
            blueprintDigest = clean(blueprintDigest);
            requesterPermissions = requesterPermissions == null ? Set.of() : Set.copyOf(requesterPermissions);
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record ActivationIssue(
            String code,
            String capabilityId,
            String message,
            String recovery) {
        public ActivationIssue {
            code = clean(code);
            capabilityId = clean(capabilityId);
            message = clean(message);
            recovery = clean(recovery);
        }
    }

    public record ModuleActivationPlan(
            String capabilityId,
            String moduleOwner,
            String dedicatedCommand,
            String draftId,
            long expectedRevision,
            String expectedDigest,
            List<String> dependencies,
            String requiredActivationPermission,
            List<String> readbackAssertions,
            String recoveryStrategy) {
        public ModuleActivationPlan {
            dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
            readbackAssertions = readbackAssertions == null ? List.of() : List.copyOf(readbackAssertions);
        }
    }

    public record ActivationPlan(
            String schemaVersion,
            String planId,
            String factoryId,
            String blueprintId,
            int blueprintVersion,
            String blueprintDigest,
            String planDigest,
            long requestedBy,
            PlanStatus status,
            List<ModuleActivationPlan> modules,
            List<ActivationIssue> issues,
            int expectedActivationCount,
            int businessWriteCount,
            String approvalState,
            String activationState,
            ActorRef requestedActor) {
        public ActivationPlan(String schemaVersion,
                String planId,
                String factoryId,
                String blueprintId,
                int blueprintVersion,
                String blueprintDigest,
                String planDigest,
                long requestedBy,
                PlanStatus status,
                List<ModuleActivationPlan> modules,
                List<ActivationIssue> issues,
                int expectedActivationCount,
                int businessWriteCount,
                String approvalState,
                String activationState) {
            this(schemaVersion, planId, factoryId, blueprintId, blueprintVersion, blueprintDigest, planDigest, requestedBy, status, modules, issues, expectedActivationCount, businessWriteCount, approvalState, activationState, new ActorRef(AuthenticatedActorType.FACTORY_USER, requestedBy));
        }

        public ActivationPlan(String schemaVersion,
                String planId,
                String factoryId,
                String blueprintId,
                int blueprintVersion,
                String blueprintDigest,
                String planDigest,
                ActorRef requestedActor,
                PlanStatus status,
                List<ModuleActivationPlan> modules,
                List<ActivationIssue> issues,
                int expectedActivationCount,
                int businessWriteCount,
                String approvalState,
                String activationState) {
            this(schemaVersion, planId, factoryId, blueprintId, blueprintVersion, blueprintDigest, planDigest, requestedActor.id(), status, modules, issues, expectedActivationCount, businessWriteCount, approvalState, activationState, requestedActor);
        }

        public ActivationPlan {
            Objects.requireNonNull(requestedActor, "requestedActor");
            if (requestedBy != requestedActor.id()) throw new IllegalArgumentException("Actor identity mismatch");
            schemaVersion = PLAN_SCHEMA;
            modules = modules == null ? List.of() : List.copyOf(modules);
            issues = issues == null ? List.of() : List.copyOf(issues);
            expectedActivationCount = status == PlanStatus.READY_FOR_APPROVAL ? modules.size() : 0;
            businessWriteCount = 0;
            approvalState = "NOT_APPROVED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record ApprovedActivationPlan(
            ActivationPlan plan,
            long approvedBy,
            String approvalReason,
            String approvalDigest,
            Instant approvedAt,
            String approvalState,
            String activationState,
            ActorRef approvedActor) {
        public ApprovedActivationPlan(ActivationPlan plan,
                long approvedBy,
                String approvalReason,
                String approvalDigest,
                Instant approvedAt,
                String approvalState,
                String activationState) {
            this(plan, approvedBy, approvalReason, approvalDigest, approvedAt, approvalState, activationState, new ActorRef(AuthenticatedActorType.FACTORY_USER, approvedBy));
        }

        public ApprovedActivationPlan(ActivationPlan plan,
                ActorRef approvedActor,
                String approvalReason,
                String approvalDigest,
                Instant approvedAt,
                String approvalState,
                String activationState) {
            this(plan, approvedActor.id(), approvalReason, approvalDigest, approvedAt, approvalState, activationState, approvedActor);
        }

        public ApprovedActivationPlan {
            Objects.requireNonNull(approvedActor, "approvedActor");
            if (approvedBy != approvedActor.id()) throw new IllegalArgumentException("Actor identity mismatch");
            approvalReason = clean(approvalReason);
            approvalDigest = clean(approvalDigest);
            approvalState = "APPROVED";
            activationState = "NOT_ACTIVATED";
        }
    }

    public record CurrentDraft(
            String capabilityId,
            String draftId,
            long revision,
            String digest,
            String state) {
        public CurrentDraft {
            capabilityId = clean(capabilityId);
            draftId = clean(draftId);
            digest = clean(digest);
            state = clean(state);
        }
    }

    public record ModuleActivationCommandRequest(
            String factoryId,
            String planId,
            String capabilityId,
            String draftId,
            long expectedRevision,
            String expectedDigest,
            String idempotencyKey,
            long executedBy,
            ActorRef executedActor) {
        public ModuleActivationCommandRequest(String factoryId,
                String planId,
                String capabilityId,
                String draftId,
                long expectedRevision,
                String expectedDigest,
                String idempotencyKey,
                long executedBy) {
            this(factoryId, planId, capabilityId, draftId, expectedRevision, expectedDigest, idempotencyKey, executedBy, new ActorRef(AuthenticatedActorType.FACTORY_USER, executedBy));
        }

        public ModuleActivationCommandRequest(String factoryId,
                String planId,
                String capabilityId,
                String draftId,
                long expectedRevision,
                String expectedDigest,
                String idempotencyKey,
                ActorRef executedActor) {
            this(factoryId, planId, capabilityId, draftId, expectedRevision, expectedDigest, idempotencyKey, executedActor.id(), executedActor);
        }

        public ModuleActivationCommandRequest {
            Objects.requireNonNull(executedActor, "executedActor");
            if (executedBy != executedActor.id()) throw new IllegalArgumentException("Actor identity mismatch");
            idempotencyKey = clean(idempotencyKey);
        }
    }

    public record ModuleCommandReceipt(
            String capabilityId,
            String commandReceiptId,
            String targetRuntimeId,
            long activatedRevision,
            String activatedDigest,
            int businessWriteCount) {
        public ModuleCommandReceipt {
            capabilityId = clean(capabilityId);
            commandReceiptId = clean(commandReceiptId);
            targetRuntimeId = clean(targetRuntimeId);
            activatedDigest = clean(activatedDigest);
        }
    }

    public record RuntimeReadback(
            String capabilityId,
            String targetRuntimeId,
            long activeRevision,
            String activeDigest,
            String runtimeState) {
        public RuntimeReadback {
            capabilityId = clean(capabilityId);
            targetRuntimeId = clean(targetRuntimeId);
            activeDigest = clean(activeDigest);
            runtimeState = clean(runtimeState);
        }
    }

    public record ModuleActivationReceipt(
            String capabilityId,
            ModuleExecutionStatus status,
            String commandReceiptId,
            String targetRuntimeId,
            long activeRevision,
            String activeDigest,
            int businessWriteCount,
            String failureCode,
            String failureMessage,
            String recovery) {
        public ModuleActivationReceipt {
            capabilityId = clean(capabilityId);
            commandReceiptId = clean(commandReceiptId);
            targetRuntimeId = clean(targetRuntimeId);
            activeDigest = clean(activeDigest);
            failureCode = clean(failureCode);
            failureMessage = clean(failureMessage);
            recovery = clean(recovery);
        }
    }

    public record ActivationCenterReceipt(
            String schemaVersion,
            String factoryId,
            String planId,
            String requestDigest,
            String idempotencyKey,
            String status,
            long requestedBy,
            long approvedBy,
            long executedBy,
            List<ModuleActivationReceipt> modules,
            int successfulCount,
            int failedCount,
            int blockedCount,
            int originalBusinessWriteCount,
            int invocationBusinessWriteCount,
            boolean idempotentReplay,
            String recoverySummary,
            String blueprintPublishState,
            String workflowPublishState,
            String bomPublishState,
            String factoryPublishState,
            ActorRef requestedActor,
            ActorRef approvedActor,
            ActorRef executedActor) {
        public ActivationCenterReceipt(String schemaVersion,
                String factoryId,
                String planId,
                String requestDigest,
                String idempotencyKey,
                String status,
                long requestedBy,
                long approvedBy,
                long executedBy,
                List<ModuleActivationReceipt> modules,
                int successfulCount,
                int failedCount,
                int blockedCount,
                int originalBusinessWriteCount,
                int invocationBusinessWriteCount,
                boolean idempotentReplay,
                String recoverySummary,
                String blueprintPublishState,
                String workflowPublishState,
                String bomPublishState,
                String factoryPublishState) {
            this(schemaVersion, factoryId, planId, requestDigest, idempotencyKey, status, requestedBy, approvedBy, executedBy, modules, successfulCount, failedCount, blockedCount, originalBusinessWriteCount, invocationBusinessWriteCount, idempotentReplay, recoverySummary, blueprintPublishState, workflowPublishState, bomPublishState, factoryPublishState, null, null, null);
        }

        public ActivationCenterReceipt {
            schemaVersion = RECEIPT_SCHEMA;
            modules = modules == null ? List.of() : List.copyOf(modules);
            // A module activation receipt says exactly one thing: the named modules reached the
            // runtime state their own readback reports. It is NOT a Blueprint publish, NOT a
            // Workflow or BOM publish/activate, and NOT a statement that the factory as a whole
            // went live. Those are separate commands with their own authorisation, so these four
            // are fixed here and cannot be talked up by a caller.
            blueprintPublishState = "NOT_PUBLISHED";
            workflowPublishState = "NOT_PUBLISHED";
            bomPublishState = "NOT_PUBLISHED";
            factoryPublishState = "NOT_PUBLISHED";
        }
    }

    static String clean(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
