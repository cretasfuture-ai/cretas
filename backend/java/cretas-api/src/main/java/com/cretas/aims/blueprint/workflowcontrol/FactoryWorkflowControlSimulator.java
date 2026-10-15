package com.cretas.aims.blueprint.workflowcontrol;

import com.cretas.aims.blueprint.rule.FactoryRuleCompiler;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.SimulationResult;
import com.cretas.aims.blueprint.rule.FactoryRuleModels.TriState;
import com.cretas.aims.blueprint.rule.FactoryRuleUnitResolver;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.BranchPathReceipt;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.BranchReceipt;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ControlPreview;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ControlSimulationReceipt;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.GenealogyPolicy;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.JoinMode;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.JoinReceipt;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.LimitDisposition;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.QuantityPolicy;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ReworkEvent;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ReworkPolicyVersion;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.ReworkReceipt;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.SimulationRequest;
import com.cretas.aims.blueprint.workflowcontrol.FactoryWorkflowControlModels.UnknownDisposition;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Deterministic zero-write simulator for B23 selection, join and rework overlays. */
public final class FactoryWorkflowControlSimulator {

    private final FactoryRuleCompiler rules;

    public FactoryWorkflowControlSimulator(FactoryRuleUnitResolver units) {
        this.rules = new FactoryRuleCompiler(units);
    }

    public ControlSimulationReceipt simulate(ControlPreview preview, SimulationRequest request) {
        if (preview == null || !"PREVIEW_READY".equals(preview.status()) || preview.bundle() == null || request == null) {
            return new ControlSimulationReceipt(FactoryWorkflowControlModels.SCHEMA_VERSION,
                    FactoryWorkflowControlModels.SIMULATOR_VERSION,
                    preview == null ? "" : preview.contentHash(), "BLOCKED",
                    List.of(), List.of(), List.of(), "NOT_PUBLISHED", "NOT_ACTIVATED", "NOT_BOUND_TO_RUNTIME", 0);
        }
        List<BranchReceipt> branches = simulateBranches(preview, request);
        List<JoinReceipt> joins = simulateJoins(preview, request, branches);
        List<ReworkReceipt> reworks = simulateReworks(preview, request);
        boolean holds = branches.stream().anyMatch(item -> !"SELECTED".equals(item.status()))
                || joins.stream().anyMatch(item -> !item.released())
                || reworks.stream().anyMatch(item -> Set.of("BLOCKED", "HOLD_FOR_REVIEW", "LIMIT_REACHED", "AWAITING_RELEASE").contains(item.status()));
        return new ControlSimulationReceipt(FactoryWorkflowControlModels.SCHEMA_VERSION,
                FactoryWorkflowControlModels.SIMULATOR_VERSION, preview.contentHash(),
                holds ? "SIMULATED_WITH_HOLDS" : "SIMULATED",
                branches, joins, reworks, "NOT_PUBLISHED", "NOT_ACTIVATED", "NOT_BOUND_TO_RUNTIME", 0);
    }

    private List<BranchReceipt> simulateBranches(ControlPreview preview, SimulationRequest request) {
        List<BranchReceipt> receipts = new ArrayList<>();
        for (var policy : preview.bundle().branches().stream().sorted(Comparator.comparing(item -> item.policyId())).toList()) {
            List<BranchPathReceipt> pathReceipts = new ArrayList<>();
            List<String> trueBranches = new ArrayList<>();
            boolean unknown = false;
            for (var path : policy.paths().stream().filter(item -> !item.defaultPath()).sorted(Comparator.comparing(item -> item.branchId())).toList()) {
                Map<String, com.cretas.aims.blueprint.rule.FactoryRuleModels.InputValue> inputs =
                        request.ruleInputs().getOrDefault(path.guardRule().ruleId(), Map.of());
                SimulationResult evaluated = rules.simulate(path.guardRule(), preview.bundle().variableCatalog(), inputs, request.evaluationTime());
                TriState truth = evaluated.result().truth();
                if (truth == TriState.TRUE) trueBranches.add(path.branchId());
                if (truth == TriState.UNKNOWN) unknown = true;
                pathReceipts.add(new BranchPathReceipt(path.branchId(), truth, false,
                        truth == TriState.TRUE ? "RULE_TRUE" : truth == TriState.FALSE ? "RULE_FALSE" : "RULE_UNKNOWN",
                        evaluated.disposition(), evaluated.trace()));
            }
            var defaultPath = policy.paths().stream().filter(item -> item.defaultPath()).findFirst().orElseThrow();
            List<String> selected = new ArrayList<>();
            String status;
            String disposition;
            if (unknown) {
                status = policy.unknownDisposition() == UnknownDisposition.BLOCK ? "BLOCKED" : "HOLD_FOR_REVIEW";
                disposition = status;
            } else {
                selected.addAll(trueBranches);
                if (selected.isEmpty()) selected.add(defaultPath.branchId());
                boolean valid = switch (policy.selectionMode()) {
                    case EXACTLY_ONE -> selected.size() == 1;
                    case AT_LEAST_ONE, ALL_MATCHING -> !selected.isEmpty();
                };
                status = valid ? "SELECTED" : "BLOCKED";
                disposition = valid ? "NO_WRITE_SELECTION_PREVIEW" : "SELECTION_CARDINALITY_VIOLATION";
                if (!valid) selected.clear();
            }
            List<String> selectedStable = selected.stream().sorted().toList();
            List<BranchPathReceipt> finalized = pathReceipts.stream().map(item -> new BranchPathReceipt(
                    item.branchId(), item.guardResult(), selectedStable.contains(item.branchId()),
                    selectedStable.contains(item.branchId()) ? "SELECTED_BY_RULE" : item.reason(),
                    item.ruleDisposition(), item.trace())).collect(Collectors.toCollection(ArrayList::new));
            finalized.add(new BranchPathReceipt(defaultPath.branchId(), TriState.FALSE,
                    selectedStable.contains(defaultPath.branchId()),
                    selectedStable.contains(defaultPath.branchId()) ? "SELECTED_DEFAULT_ELSE"
                            : unknown ? "DEFAULT_SUPPRESSED_BY_UNKNOWN" : "DEFAULT_NOT_NEEDED",
                    "NO_RULE_DEFAULT_PATH", List.of()));
            finalized.sort(Comparator.comparing(BranchPathReceipt::branchId));
            receipts.add(new BranchReceipt(policy.policyId(), status, selectedStable, finalized, disposition));
        }
        return List.copyOf(receipts);
    }

    private List<JoinReceipt> simulateJoins(
            ControlPreview preview,
            SimulationRequest request,
            List<BranchReceipt> branches) {
        Map<String, Boolean> selected = new LinkedHashMap<>();
        Map<String, String> controlStatus = new HashMap<>();
        branches.forEach(receipt -> receipt.paths().forEach(path -> {
            selected.put(path.branchId(), path.selected());
            controlStatus.put(path.branchId(), receipt.status());
        }));
        List<JoinReceipt> receipts = new ArrayList<>();
        for (var policy : preview.bundle().joins().stream().sorted(Comparator.comparing(item -> item.policyId())).toList()) {
            List<String> selectedIds = policy.upstreamBranchIds().stream()
                    .filter(id -> Boolean.TRUE.equals(selected.get(id))).sorted().toList();
            List<String> ignored = policy.upstreamBranchIds().stream()
                    .filter(id -> !selectedIds.contains(id)).sorted().toList();
            List<String> completed = selectedIds.stream()
                    .filter(id -> Boolean.TRUE.equals(request.branchCompletions().get(id))).sorted().toList();
            boolean controlHeld = policy.upstreamBranchIds().stream()
                    .anyMatch(id -> Set.of("BLOCKED", "HOLD_FOR_REVIEW").contains(controlStatus.get(id)));
            boolean released = !controlHeld && !selectedIds.isEmpty() && switch (policy.mode()) {
                case ALL_SELECTED_COMPLETED -> completed.size() == selectedIds.size();
                case ANY_SELECTED_COMPLETED -> !completed.isEmpty();
                case MANUAL_RELEASE -> request.manualJoinReleases().contains(policy.policyId());
            };
            String status = controlHeld ? "WAITING_CONTROL_DECISION" : released ? "RELEASED" : "WAITING";
            String disposition = controlHeld ? "UPSTREAM_UNKNOWN_OR_BLOCKED"
                    : released ? "NO_WRITE_JOIN_RELEASE_PREVIEW"
                    : policy.mode() == JoinMode.MANUAL_RELEASE ? "MANUAL_RELEASE_REQUIRED" : "SELECTED_BRANCHES_INCOMPLETE";
            receipts.add(new JoinReceipt(policy.policyId(), status, selectedIds, completed, ignored, released, disposition));
        }
        return List.copyOf(receipts);
    }

    private List<ReworkReceipt> simulateReworks(ControlPreview preview, SimulationRequest request) {
        Map<String, ReworkPolicyVersion> policies = preview.bundle().reworkPolicies().stream().collect(Collectors.toMap(
                ReworkPolicyVersion::policyVersionId, Function.identity(), (left, right) -> left, LinkedHashMap::new));
        Map<String, Integer> allocated = new HashMap<>();
        for (ReworkEvent event : request.reworkEvents()) allocated.merge(
                event.policyVersionId(), Math.max(0, event.existingAttemptCount()), Math::max);
        Map<String, ReworkReceipt> byEvent = new LinkedHashMap<>();
        Map<String, ReworkEvent> eventPayloads = new LinkedHashMap<>();
        List<ReworkReceipt> receipts = new ArrayList<>();
        for (ReworkEvent event : request.reworkEvents().stream().sorted(Comparator.comparing(ReworkEvent::eventId)).toList()) {
            if (byEvent.containsKey(event.eventId())) {
                if (!event.equals(eventPayloads.get(event.eventId()))) {
                    receipts.add(blockedEvent(event, "IDEMPOTENCY_KEY_PAYLOAD_CONFLICT"));
                    continue;
                }
                ReworkReceipt first = byEvent.get(event.eventId());
                receipts.add(new ReworkReceipt(first.eventId(), first.policyVersionId(), "IDEMPOTENT_REPLAY",
                        first.triggerResult(), first.releaseResult(), first.attemptNumber(), first.attemptId(),
                        first.attemptQuantity(), first.sourceLotId(), first.resultLotId(), first.genealogyPolicy(),
                        first.disposition(), true));
                continue;
            }
            ReworkPolicyVersion policy = policies.get(event.policyVersionId());
            ReworkReceipt receipt = policy == null
                    ? blockedEvent(event, "UNKNOWN_REWORK_POLICY")
                    : simulateRework(preview, policy, event, allocated);
            byEvent.put(event.eventId(), receipt);
            eventPayloads.put(event.eventId(), event);
            receipts.add(receipt);
        }
        return List.copyOf(receipts);
    }

    private ReworkReceipt simulateRework(
            ControlPreview preview,
            ReworkPolicyVersion policy,
            ReworkEvent event,
            Map<String, Integer> allocated) {
        if (event.eventId().isBlank() || event.lotId().isBlank() || event.evaluationTime() == null) {
            return blockedEvent(event, "REWORK_EVENT_ID_LOT_TIME_REQUIRED");
        }
        SimulationResult trigger = rules.simulate(policy.triggerRule(), preview.bundle().variableCatalog(),
                event.triggerInputs(), event.evaluationTime());
        TriState triggerTruth = trigger.result().truth();
        if (triggerTruth == TriState.UNKNOWN) return new ReworkReceipt(event.eventId(), policy.policyVersionId(),
                "HOLD_FOR_REVIEW", triggerTruth, TriState.UNKNOWN, 0, "", null,
                event.lotId(), event.lotId(), policy.genealogyPolicy(), trigger.disposition(), false);
        if (triggerTruth == TriState.FALSE) return new ReworkReceipt(event.eventId(), policy.policyVersionId(),
                "NOT_TRIGGERED", triggerTruth, TriState.UNKNOWN, 0, "", BigDecimal.ZERO,
                event.lotId(), event.lotId(), policy.genealogyPolicy(), "RULE_FALSE", false);
        BigDecimal quantity = attemptQuantity(policy.quantityPolicy(), event);
        if (quantity == null || quantity.signum() <= 0) return blockedEvent(event, "REWORK_QUANTITY_INVALID");
        int nextAttempt = allocated.getOrDefault(policy.policyVersionId(), 0) + 1;
        if (nextAttempt > policy.maxAttempts()) {
            return new ReworkReceipt(event.eventId(), policy.policyVersionId(), "LIMIT_REACHED",
                    triggerTruth, TriState.UNKNOWN, nextAttempt, "", quantity,
                    event.lotId(), event.lotId(), policy.genealogyPolicy(), limitDisposition(policy.dispositionOnLimit()), false);
        }
        allocated.put(policy.policyVersionId(), nextAttempt);
        String attemptId = stableId("attempt", policy.policyVersionId(), event.eventId(), String.valueOf(nextAttempt));
        String resultLotId = policy.genealogyPolicy() == GenealogyPolicy.CHILD_LOT
                ? event.lotId() + "-RW-" + stableId("lot", policy.policyVersionId(), event.eventId()).substring(0, 8)
                : event.lotId();
        SimulationResult release = rules.simulate(policy.releaseRule(), preview.bundle().variableCatalog(),
                event.releaseInputs(), event.evaluationTime());
        TriState releaseTruth = release.result().truth();
        String status = releaseTruth == TriState.TRUE ? "CREATED_AND_RELEASED"
                : releaseTruth == TriState.FALSE ? "AWAITING_RELEASE" : "HOLD_FOR_REVIEW";
        String disposition = releaseTruth == TriState.TRUE ? "NO_WRITE_RELEASE_PREVIEW"
                : releaseTruth == TriState.FALSE ? "RELEASE_RULE_FALSE" : release.disposition();
        return new ReworkReceipt(event.eventId(), policy.policyVersionId(), status,
                triggerTruth, releaseTruth, nextAttempt, attemptId, quantity,
                event.lotId(), resultLotId, policy.genealogyPolicy(), disposition, false);
    }

    private BigDecimal attemptQuantity(QuantityPolicy policy, ReworkEvent event) {
        if (policy == null) return null;
        if (event.lotQuantity() == null || event.lotQuantity().signum() <= 0) return null;
        if (event.failedQuantity() != null && (event.failedQuantity().signum() <= 0
                || event.failedQuantity().compareTo(event.lotQuantity()) > 0)) return null;
        return switch (policy) {
            case FULL_LOT -> event.lotQuantity();
            case FAILED_QUANTITY_ONLY -> event.failedQuantity();
            case MANUAL_SPLIT -> event.manualSplitQuantity() != null && event.failedQuantity() != null
                    && event.manualSplitQuantity().compareTo(event.failedQuantity()) <= 0
                    ? event.manualSplitQuantity() : null;
        };
    }

    private ReworkReceipt blockedEvent(ReworkEvent event, String disposition) {
        return new ReworkReceipt(event == null ? "" : event.eventId(), event == null ? "" : event.policyVersionId(),
                "BLOCKED", TriState.UNKNOWN, TriState.UNKNOWN, 0, "", null,
                event == null ? "" : event.lotId(), event == null ? "" : event.lotId(), null, disposition, false);
    }

    private String limitDisposition(LimitDisposition disposition) {
        return switch (disposition) {
            case HOLD -> "HOLD_ON_LIMIT";
            case SCRAP -> "SCRAP_ON_LIMIT";
            case MANUAL_REVIEW -> "MANUAL_REVIEW_ON_LIMIT";
        };
    }

    private String stableId(String... parts) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha256.digest(String.join("\u001f", parts).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
