package com.cretas.aims.blueprint.workflowcontrol;

import com.cretas.aims.blueprint.rule.FactoryRuleModels.InputValue;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.RuleDefinition;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.TriState;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.VariableCatalog;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable B23 guard, join and rework preview contracts. None of these records is a runtime entity. */
public final class FactoryWorkflowControlModels {

    public static final String SCHEMA_VERSION = "factory-workflow-control/v1";
    public static final String COMPILER_VERSION = "factory-workflow-control-compiler/v1";
    public static final String SIMULATOR_VERSION = "factory-workflow-control-simulator/v1";

    private FactoryWorkflowControlModels() {
    }

    public enum SelectionMode { EXACTLY_ONE, AT_LEAST_ONE, ALL_MATCHING }

    public enum JoinMode { ALL_SELECTED_COMPLETED, ANY_SELECTED_COMPLETED, MANUAL_RELEASE }

    public enum UnknownDisposition { HOLD_FOR_REVIEW, BLOCK }

    public enum QuantityPolicy { FULL_LOT, FAILED_QUANTITY_ONLY, MANUAL_SPLIT }

    public enum GenealogyPolicy { SAME_LOT_ATTEMPT, CHILD_LOT }

    public enum LimitDisposition { HOLD, SCRAP, MANUAL_REVIEW }

    public enum IssueSeverity { BLOCKING, WARNING }

    public record BranchPath(
            String branchId,
            String targetProcessNodeId,
            RuleDefinition guardRule,
            boolean defaultPath,
            List<String> evidenceRefs) {
        public BranchPath {
            branchId = clean(branchId);
            targetProcessNodeId = clean(targetProcessNodeId);
            evidenceRefs = immutable(evidenceRefs);
        }
    }

    public record BranchPolicy(
            String policyId,
            String sourceProcessNodeId,
            SelectionMode selectionMode,
            UnknownDisposition unknownDisposition,
            List<BranchPath> paths,
            List<String> evidenceRefs) {
        public BranchPolicy {
            policyId = clean(policyId);
            sourceProcessNodeId = clean(sourceProcessNodeId);
            paths = immutable(paths);
            evidenceRefs = immutable(evidenceRefs);
        }
    }

    public record JoinPolicy(
            String policyId,
            String joinProcessNodeId,
            JoinMode mode,
            List<String> upstreamBranchIds,
            List<String> evidenceRefs) {
        public JoinPolicy {
            policyId = clean(policyId);
            joinProcessNodeId = clean(joinProcessNodeId);
            upstreamBranchIds = immutable(upstreamBranchIds);
            evidenceRefs = immutable(evidenceRefs);
        }
    }

    /** Immutable overlay. Rework is never represented as a cycle in the Workflow definition DAG. */
    public record ReworkPolicyVersion(
            String policyVersionId,
            String sourceProcessNodeId,
            RuleDefinition triggerRule,
            String returnProcessNodeId,
            RuleDefinition releaseRule,
            int maxAttempts,
            QuantityPolicy quantityPolicy,
            GenealogyPolicy genealogyPolicy,
            LimitDisposition dispositionOnLimit,
            List<String> evidenceRefs) {
        public ReworkPolicyVersion {
            policyVersionId = clean(policyVersionId);
            sourceProcessNodeId = clean(sourceProcessNodeId);
            returnProcessNodeId = clean(returnProcessNodeId);
            evidenceRefs = immutable(evidenceRefs);
        }
    }

    public record ControlBundle(
            String schemaVersion,
            String factoryId,
            String sourceRouteDigest,
            String sourceWorkflowPreviewDigest,
            VariableCatalog variableCatalog,
            List<BranchPolicy> branches,
            List<JoinPolicy> joins,
            List<ReworkPolicyVersion> reworkPolicies) {
        public ControlBundle {
            schemaVersion = clean(schemaVersion);
            factoryId = clean(factoryId);
            sourceRouteDigest = clean(sourceRouteDigest);
            sourceWorkflowPreviewDigest = clean(sourceWorkflowPreviewDigest);
            branches = immutable(branches);
            joins = immutable(joins);
            reworkPolicies = immutable(reworkPolicies);
        }
    }

    public record ControlIssue(
            String code,
            IssueSeverity severity,
            String path,
            String message,
            String recovery) {
        public ControlIssue {
            code = clean(code);
            severity = severity == null ? IssueSeverity.BLOCKING : severity;
            path = clean(path);
            message = clean(message);
            recovery = clean(recovery);
        }
    }

    public record ControlPreview(
            String schemaVersion,
            String compilerVersion,
            String status,
            String contentHash,
            ControlBundle bundle,
            List<ControlIssue> issues,
            String publicationState,
            String activationState,
            String runtimeBindingState,
            int businessWriteCount) {
        public ControlPreview {
            schemaVersion = SCHEMA_VERSION;
            compilerVersion = COMPILER_VERSION;
            status = clean(status);
            contentHash = clean(contentHash);
            issues = immutable(issues);
            publicationState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            runtimeBindingState = "NOT_BOUND_TO_RUNTIME";
            businessWriteCount = 0;
        }
    }

    public record ReworkEvent(
            String eventId,
            String policyVersionId,
            String lotId,
            BigDecimal lotQuantity,
            BigDecimal failedQuantity,
            BigDecimal manualSplitQuantity,
            int existingAttemptCount,
            Map<String, InputValue> triggerInputs,
            Map<String, InputValue> releaseInputs,
            Instant evaluationTime) {
        public ReworkEvent {
            eventId = clean(eventId);
            policyVersionId = clean(policyVersionId);
            lotId = clean(lotId);
            triggerInputs = triggerInputs == null ? Map.of() : Map.copyOf(triggerInputs);
            releaseInputs = releaseInputs == null ? Map.of() : Map.copyOf(releaseInputs);
        }
    }

    public record SimulationRequest(
            Map<String, Map<String, InputValue>> ruleInputs,
            Map<String, Boolean> branchCompletions,
            Set<String> manualJoinReleases,
            List<ReworkEvent> reworkEvents,
            Instant evaluationTime) {
        public SimulationRequest {
            ruleInputs = ruleInputs == null ? Map.of() : Map.copyOf(ruleInputs);
            branchCompletions = branchCompletions == null ? Map.of() : Map.copyOf(branchCompletions);
            manualJoinReleases = manualJoinReleases == null ? Set.of() : Set.copyOf(manualJoinReleases);
            reworkEvents = immutable(reworkEvents);
        }
    }

    public record BranchPathReceipt(
            String branchId,
            TriState guardResult,
            boolean selected,
            String reason,
            String ruleDisposition,
            List<String> trace) {
        public BranchPathReceipt {
            branchId = clean(branchId);
            guardResult = guardResult == null ? TriState.UNKNOWN : guardResult;
            reason = clean(reason);
            ruleDisposition = clean(ruleDisposition);
            trace = immutable(trace);
        }
    }

    public record BranchReceipt(
            String policyId,
            String status,
            List<String> selectedBranchIds,
            List<BranchPathReceipt> paths,
            String disposition) {
        public BranchReceipt {
            policyId = clean(policyId);
            status = clean(status);
            selectedBranchIds = immutable(selectedBranchIds);
            paths = immutable(paths);
            disposition = clean(disposition);
        }
    }

    public record JoinReceipt(
            String policyId,
            String status,
            List<String> selectedBranchIds,
            List<String> completedBranchIds,
            List<String> ignoredUnselectedBranchIds,
            boolean released,
            String disposition) {
        public JoinReceipt {
            policyId = clean(policyId);
            status = clean(status);
            selectedBranchIds = immutable(selectedBranchIds);
            completedBranchIds = immutable(completedBranchIds);
            ignoredUnselectedBranchIds = immutable(ignoredUnselectedBranchIds);
            disposition = clean(disposition);
        }
    }

    public record ReworkReceipt(
            String eventId,
            String policyVersionId,
            String status,
            TriState triggerResult,
            TriState releaseResult,
            int attemptNumber,
            String attemptId,
            BigDecimal attemptQuantity,
            String sourceLotId,
            String resultLotId,
            GenealogyPolicy genealogyPolicy,
            String disposition,
            boolean idempotentReplay) {
        public ReworkReceipt {
            eventId = clean(eventId);
            policyVersionId = clean(policyVersionId);
            status = clean(status);
            triggerResult = triggerResult == null ? TriState.UNKNOWN : triggerResult;
            releaseResult = releaseResult == null ? TriState.UNKNOWN : releaseResult;
            attemptId = clean(attemptId);
            sourceLotId = clean(sourceLotId);
            resultLotId = clean(resultLotId);
            disposition = clean(disposition);
        }
    }

    public record ControlSimulationReceipt(
            String schemaVersion,
            String simulatorVersion,
            String previewHash,
            String status,
            List<BranchReceipt> branchReceipts,
            List<JoinReceipt> joinReceipts,
            List<ReworkReceipt> reworkReceipts,
            String publicationState,
            String activationState,
            String runtimeBindingState,
            int businessWriteCount) {
        public ControlSimulationReceipt {
            schemaVersion = SCHEMA_VERSION;
            simulatorVersion = SIMULATOR_VERSION;
            previewHash = clean(previewHash);
            status = clean(status);
            branchReceipts = immutable(branchReceipts);
            joinReceipts = immutable(joinReceipts);
            reworkReceipts = immutable(reworkReceipts);
            publicationState = "NOT_PUBLISHED";
            activationState = "NOT_ACTIVATED";
            runtimeBindingState = "NOT_BOUND_TO_RUNTIME";
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
