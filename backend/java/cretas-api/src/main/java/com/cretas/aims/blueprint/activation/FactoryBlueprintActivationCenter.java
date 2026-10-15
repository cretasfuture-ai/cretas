package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationCenterReceipt;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationIssue;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationPlan;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ActivationPlanRequest;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ApprovedActivationPlan;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.CurrentDraft;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.DraftCandidate;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationCommandRequest;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationPlan;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleActivationReceipt;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleCommandReceipt;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.ModuleExecutionStatus;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.PlanStatus;
import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.RuntimeReadback;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts.AdapterMode;
import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts.CapabilityContract;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;

import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * B26 default-closed activation orchestrator. It has no Spring annotation, controller, or default
 * command registrations: production activation remains unavailable until a separate release
 * explicitly wires verified module commands and a durable execution store.
 */
public final class FactoryBlueprintActivationCenter {

    public static final String APPROVE_PERMISSION = "factory-blueprint:activation:approve";
    public static final String EXECUTE_PERMISSION = "factory-blueprint:activation:execute";

    /**
     * How long an approval stays executable. An approval is a statement about the exact module
     * versions the approver read; the longer it stays valid, the more it becomes a statement about
     * something that has since changed. Drift detection still runs at execute time — this bound
     * exists so a stale approval cannot sit around waiting for a window where drift happens to
     * look clean.
     */
    public static final Duration DEFAULT_APPROVAL_TTL = Duration.ofHours(2);

    /**
     * Column widths of factory_activation_execution_modules / _executions (V20261103_27).
     *
     * <p>Everything a module command hands back is unbounded text as far as this class is
     * concerned. If any of it exceeds its column, the INSERT in the store fails, the whole
     * receipt is lost, and the execution row stays CLAIMED forever — so the table whose entire
     * purpose is to record what happened records nothing precisely when a module has just failed,
     * and the idempotency key is burned. Free text is therefore trimmed to fit, and identifiers,
     * which must not be silently shortened into a different value, make that one module FAIL.</p>
     */
    private static final int MAX_MESSAGE = 500;
    private static final int MAX_IDENTIFIER = 120;
    private static final int MAX_FAILURE_CODE = 60;
    private static final String DIGEST_PATTERN = "[0-9a-f]{64}";

    private final FactoryCapabilityAdapterContracts.Registry registry;
    private final Map<String, FactoryModuleActivationCommand> commands;
    private final FactoryActivationExecutionStore executionStore;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final Clock clock;
    private final Duration approvalTtl;
    private final FactoryActivationModuleTransaction moduleTransaction;

    FactoryBlueprintActivationCenter(
            List<FactoryModuleActivationCommand> commands,
            FactoryActivationExecutionStore executionStore,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            Clock clock,
            Duration approvalTtl) {
        this(commands, executionStore, canonicalBlueprints, clock, approvalTtl, null, true);
    }

    public FactoryBlueprintActivationCenter(
            List<FactoryModuleActivationCommand> commands,
            FactoryActivationExecutionStore executionStore,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            Clock clock,
            Duration approvalTtl,
            FactoryActivationModuleTransaction moduleTransaction) {
        this(commands, executionStore, canonicalBlueprints, clock, approvalTtl,
                Objects.requireNonNull(moduleTransaction, "moduleTransaction"), false);
    }

    private FactoryBlueprintActivationCenter(
            List<FactoryModuleActivationCommand> commands,
            FactoryActivationExecutionStore executionStore,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            Clock clock, Duration approvalTtl,
            FactoryActivationModuleTransaction moduleTransaction, boolean legacyTestConstruction) {
        this.registry = FactoryCapabilityAdapterContracts.b25Defaults();
        this.moduleTransaction = moduleTransaction;
        this.executionStore = Objects.requireNonNull(executionStore, "executionStore");
        this.canonicalBlueprints = Objects.requireNonNull(canonicalBlueprints, "canonicalBlueprints");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.approvalTtl = Objects.requireNonNull(approvalTtl, "approvalTtl");
        if (approvalTtl.isNegative() || approvalTtl.isZero()) {
            throw new IllegalArgumentException("Approval TTL must be positive");
        }
        Map<String, FactoryModuleActivationCommand> indexed = new LinkedHashMap<>();
        for (FactoryModuleActivationCommand command : commands == null
                ? List.<FactoryModuleActivationCommand>of() : commands) {
            CapabilityContract contract = registry.require(command.capabilityId());
            if (contract.adapterMode() != AdapterMode.MODULE_DRAFT) {
                throw new IllegalArgumentException("Read-only capability cannot register activation command: "
                        + command.capabilityId());
            }
            if (command instanceof FactoryFormalModuleActivationCommand formal
                    && (!Set.of("modules", "workflow").contains(command.capabilityId())
                    || formal.formalCommandName() == null || formal.formalCommandName().isBlank()
                    || formal.formalDependencies() == null)) {
                throw new IllegalArgumentException("Invalid formal configuration/publication command");
            }
            if (indexed.putIfAbsent(command.capabilityId(), command) != null) {
                throw new IllegalArgumentException("Duplicate activation command: " + command.capabilityId());
            }
        }
        this.commands = Map.copyOf(indexed);
    }

    public ActivationPlan preview(ActivationPlanRequest request) {
        validateRequest(request);
        List<ActivationIssue> issues = new ArrayList<>();
        verifyBlueprintAuthority(request, issues);
        Map<String, DraftCandidate> candidates = new LinkedHashMap<>();
        for (DraftCandidate candidate : request.candidates()) {
            if (candidates.putIfAbsent(candidate.capabilityId(), candidate) != null) {
                issues.add(issue("DUPLICATE_CAPABILITY_CANDIDATE", candidate.capabilityId(),
                        "同一能力出现多个 activation candidate", "每项能力只保留一个已复核 DRAFT"));
            }
        }

        List<ModuleActivationPlan> modules = new ArrayList<>();
        for (DraftCandidate candidate : candidates.values()) {
            CapabilityContract contract;
            try {
                contract = registry.require(candidate.capabilityId());
            } catch (IllegalArgumentException unknown) {
                issues.add(issue("UNKNOWN_CAPABILITY", candidate.capabilityId(), unknown.getMessage(),
                        "重新加载 factory-capability-catalog/v2"));
                continue;
            }
            validateCandidate(request, candidate, contract, candidates.keySet(), issues);
            FactoryModuleActivationCommand registered = commands.get(candidate.capabilityId());
            FactoryFormalModuleActivationCommand formal = registered instanceof FactoryFormalModuleActivationCommand value
                    ? value : null;
            modules.add(new ModuleActivationPlan(contract.capabilityId(), contract.moduleOwner(),
                    formal == null ? contract.dedicatedCommand() : formal.formalCommandName(),
                    candidate.draftId(), candidate.expectedRevision(),
                    candidate.expectedDigest(), formal == null ? contract.dependencies() : formal.formalDependencies(),
                    activationPermission(contract.capabilityId()),
                    contract.readbackAssertions(), contract.recoveryStrategy()));
        }
        modules = topological(modules);
        issues.sort(Comparator.comparing(ActivationIssue::code).thenComparing(ActivationIssue::capabilityId));
        PlanStatus status = issues.isEmpty() ? PlanStatus.READY_FOR_APPROVAL : PlanStatus.BLOCKED;
        String planId = "activation-" + digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.blueprintDigest()).substring(0, 20);
        String planDigest = planDigest(request, modules, issues, status);
        return new ActivationPlan(FactoryBlueprintActivationModels.PLAN_SCHEMA, planId,
                request.factoryId(), request.blueprintId(), request.blueprintVersion(), request.blueprintDigest(),
                planDigest, request.requestedActor(), status, modules, issues, 0, 0,
                "NOT_APPROVED", "NOT_ACTIVATED");
    }

    public ApprovedActivationPlan approve(
            ActivationPlan plan,
            long approvedBy,
            Set<String> approverPermissions,
            String reason) {
        return approve(plan, new ActorRef(AuthenticatedActorType.FACTORY_USER, approvedBy), approverPermissions, reason);
    }

    public ApprovedActivationPlan approve(ActivationPlan plan, ActorRef approvedActor,
                                          Set<String> approverPermissions, String reason) {
        Objects.requireNonNull(approvedActor, "approvedActor");
        long approvedBy = approvedActor.id();
        Objects.requireNonNull(plan, "plan");
        if (plan.status() != PlanStatus.READY_FOR_APPROVAL || !plan.issues().isEmpty()) {
            throw new IllegalArgumentException("Blocked activation plan cannot be approved");
        }
        if (approvedBy <= 0 || approvedActor.equals(plan.requestedActor())) {
            throw new IllegalArgumentException("Activation requires a different valid approver");
        }
        if (approverPermissions == null || !approverPermissions.contains(APPROVE_PERMISSION)) {
            throw new IllegalArgumentException("Approver lacks activation approval permission");
        }
        String cleanReason = FactoryBlueprintActivationModels.clean(reason);
        if (cleanReason.length() < 8) throw new IllegalArgumentException("Approval reason is too short");
        Instant approvedAt = clock.instant();
        String approvalDigest = digest(plan.planDigest(), actorKey(approvedActor), cleanReason,
                String.valueOf(approvedAt.toEpochMilli()));
        return new ApprovedActivationPlan(plan, approvedActor, cleanReason, approvalDigest, approvedAt,
                "APPROVED", "NOT_ACTIVATED");
    }

    public ActivationCenterReceipt execute(
            ApprovedActivationPlan approved,
            String confirmedApprovalDigest,
            String idempotencyKey,
            long executedBy,
            Set<String> executorPermissions) {
        return execute(approved, confirmedApprovalDigest, idempotencyKey,
                new ActorRef(AuthenticatedActorType.FACTORY_USER, executedBy), executorPermissions);
    }

    public ActivationCenterReceipt execute(ApprovedActivationPlan approved, String confirmedApprovalDigest,
                                           String idempotencyKey, ActorRef executedActor,
                                           Set<String> executorPermissions) {
        Objects.requireNonNull(executedActor, "executedActor");
        long executedBy = executedActor.id();
        Objects.requireNonNull(approved, "approved");
        String key = FactoryBlueprintActivationModels.clean(idempotencyKey);
        if (key.isBlank() || key.length() > 128) throw new IllegalArgumentException("Invalid idempotency key");
        if (!approved.approvalDigest().equals(FactoryBlueprintActivationModels.clean(confirmedApprovalDigest))) {
            throw new IllegalArgumentException("Approval digest changed; refresh and re-approve");
        }
        if (executedBy <= 0 || executorPermissions == null || !executorPermissions.contains(EXECUTE_PERMISSION)) {
            throw new IllegalArgumentException("Executor lacks activation execution permission");
        }
        // Separation of duties: whoever approved the exact versions must not also be the one who
        // runs them. approve() already refuses a self-approving requester, so with this the three
        // roles cannot collapse into one person.
        if (executedActor.equals(approved.approvedActor())) {
            throw new IllegalArgumentException("Approver cannot execute their own activation");
        }
        if (approved.approvedAt() == null || approved.approvedAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Approval expired; re-preview and re-approve");
        }
        for (ModuleActivationPlan module : approved.plan().modules()) {
            if (!executorPermissions.contains(module.requiredActivationPermission())) {
                throw new IllegalArgumentException("Executor lacks permission for " + module.capabilityId());
            }
        }
        String requestDigest = digest(approved.approvalDigest(), key, actorKey(executedActor));
        var prior = executionStore.find(approved.plan().factoryId(), key);
        if (prior.isPresent()) {
            return replayOrReject(prior.get(), requestDigest);
        }
        if (Duration.between(approved.approvedAt(), clock.instant()).compareTo(approvalTtl) > 0) {
            throw new IllegalArgumentException("Approval expired; re-preview and re-approve");
        }

        // Drift is checked BEFORE the reservation is taken. Both of these are pre-flight
        // questions about state this execution has not touched yet, and neither can run a module
        // command. Taking the claim first meant a stale approval — the ordinary case, since the
        // Blueprint moves between approval and execution — left a CLAIMED row behind with no
        // receipt, which by the semantics stated below reads as "the module commands may have
        // run". That labelled the safest possible failure as the most dangerous one.
        List<ActivationIssue> drift = new ArrayList<>();
        verifyBlueprintAuthority(new ActivationPlanRequest(approved.plan().factoryId(),
                approved.plan().blueprintId(), approved.plan().blueprintVersion(),
                approved.plan().blueprintDigest(), approved.plan().requestedActor(),
                Set.of(), approved.plan().modules().stream().map(module -> new DraftCandidate(
                        module.capabilityId(), module.draftId(), module.expectedRevision(),
                        module.expectedDigest(), "DRAFT", Map.of())).toList()), drift);
        if (!drift.isEmpty()) {
            throw new IllegalArgumentException("Blueprint authority drifted since approval: "
                    + drift.get(0).code());
        }
        verifyNoVersionDrift(approved.plan(), executedActor);

        // The read above cannot decide anything on its own: a concurrent execution of the same key
        // may not have written its receipt yet, so both callers would see "absent" and both would
        // run the module commands. The reservation has to be atomic and it has to happen BEFORE
        // any command is invoked.
        if (!executionStore.claim(approved.plan().factoryId(), key, requestDigest,
                approved.plan().requestedActor(), approved.approvedActor(), executedActor)) {
            var concurrent = executionStore.find(approved.plan().factoryId(), key);
            if (concurrent.isPresent()) {
                return replayOrReject(concurrent.get(), requestDigest);
            }
            // Two different situations reach here and an operator has to be able to tell them
            // apart: a genuinely concurrent execution, or an earlier one that stopped before it
            // wrote its receipt and left the reservation behind. Neither may be retried silently
            // under the same key, because a stuck reservation can mean the module commands DID
            // run.
            throw new IllegalStateException(
                    "Idempotency key is already held: either an activation is executing now, or "
                            + "an earlier one stopped before writing its receipt. Inspect the "
                            + "CLAIMED execution row before retrying under a new key.");
        }

        List<ModuleActivationReceipt> receipts = new ArrayList<>();
        Map<String, ModuleExecutionStatus> statuses = new HashMap<>();
        int writes = 0;
        for (ModuleActivationPlan module : approved.plan().modules()) {
            List<String> failedDependencies = module.dependencies().stream()
                    .filter(statuses::containsKey)
                    .filter(dep -> statuses.get(dep) != ModuleExecutionStatus.ACTIVATED)
                    .toList();
            if (!failedDependencies.isEmpty()) {
                receipts.add(new ModuleActivationReceipt(module.capabilityId(),
                        ModuleExecutionStatus.BLOCKED_DEPENDENCY, "", "", 0, "", 0,
                        "DEPENDENCY_ACTIVATION_FAILED", "依赖未成功激活: " + failedDependencies,
                        module.recoveryStrategy()));
                statuses.put(module.capabilityId(), ModuleExecutionStatus.BLOCKED_DEPENDENCY);
                continue;
            }
            FactoryModuleActivationCommand command = commands.get(module.capabilityId());
            ModuleCommandReceipt commandReceipt = null;
            try {
                if (moduleTransaction != null) {
                    ModuleActivationReceipt committed = moduleTransaction.execute(() -> {
                        ModuleCommandReceipt activated = command.activate(new ModuleActivationCommandRequest(
                                approved.plan().factoryId(), approved.plan().planId(), module.capabilityId(),
                                module.draftId(), module.expectedRevision(), module.expectedDigest(),
                                key + ":" + module.capabilityId(), executedActor));
                        RuntimeReadback readback = command.readBack(approved.plan().factoryId(), activated);
                        requireReadback(module, activated, readback);
                        return new ModuleActivationReceipt(module.capabilityId(), ModuleExecutionStatus.ACTIVATED,
                                activated.commandReceiptId(), readback.targetRuntimeId(), readback.activeRevision(),
                                readback.activeDigest(), activated.businessWriteCount(), "", "",
                                fitted(recoveryOf(command, activated, module), MAX_MESSAGE));
                    });
                    receipts.add(committed);
                    writes += committed.businessWriteCount();
                    statuses.put(module.capabilityId(), ModuleExecutionStatus.ACTIVATED);
                    continue;
                }
                commandReceipt = command.activate(new ModuleActivationCommandRequest(
                        approved.plan().factoryId(), approved.plan().planId(), module.capabilityId(),
                        module.draftId(), module.expectedRevision(), module.expectedDigest(),
                        key + ":" + module.capabilityId(), executedActor));
                writes += commandReceipt.businessWriteCount();
                RuntimeReadback readback = command.readBack(approved.plan().factoryId(), commandReceipt);
                requireReadback(module, commandReceipt, readback);
                receipts.add(new ModuleActivationReceipt(module.capabilityId(), ModuleExecutionStatus.ACTIVATED,
                        commandReceipt.commandReceiptId(), readback.targetRuntimeId(), readback.activeRevision(),
                        readback.activeDigest(), commandReceipt.businessWriteCount(), "", "",
                        fitted(recoveryOf(command, commandReceipt, module), MAX_MESSAGE)));
                statuses.put(module.capabilityId(), ModuleExecutionStatus.ACTIVATED);
            } catch (RuntimeException failure) {
                int failedWrites = commandReceipt == null ? 0 : commandReceipt.businessWriteCount();
                String recovery = commandReceipt == null
                        ? module.recoveryStrategy() : recoveryOf(command, commandReceipt, module);
                receipts.add(new ModuleActivationReceipt(module.capabilityId(), ModuleExecutionStatus.FAILED,
                        fitted(commandReceipt == null ? "" : commandReceipt.commandReceiptId(),
                                MAX_IDENTIFIER),
                        fitted(commandReceipt == null ? "" : commandReceipt.targetRuntimeId(),
                                MAX_IDENTIFIER),
                        commandReceipt == null ? 0 : commandReceipt.activatedRevision(),
                        // A digest that is not a digest must not reach the column: storing it
                        // would fail the row and lose this failure record along with it.
                        storableDigest(commandReceipt == null ? "" : commandReceipt.activatedDigest()),
                        failedWrites, "MODULE_ACTIVATION_FAILED",
                        fitted(FactoryBlueprintActivationModels.clean(failure.getMessage()),
                                MAX_MESSAGE),
                        fitted(recovery, MAX_MESSAGE)));
                statuses.put(module.capabilityId(), ModuleExecutionStatus.FAILED);
            }
        }
        int success = count(receipts, ModuleExecutionStatus.ACTIVATED);
        int failed = count(receipts, ModuleExecutionStatus.FAILED);
        int blocked = count(receipts, ModuleExecutionStatus.BLOCKED_DEPENDENCY);
        String status = failed == 0 && blocked == 0 ? "ACTIVATED" : success == 0 ? "FAILED" : "PARTIAL";
        String recovery = failed == 0 && blocked == 0
                ? "运行态回读已验证；后续变更必须创建新 DRAFT/版本"
                : "保留成功模块回执；仅修复失败模块并重新生成新 activation plan，禁止整厂静默重放";
        ActivationCenterReceipt result = new ActivationCenterReceipt(
                FactoryBlueprintActivationModels.RECEIPT_SCHEMA, approved.plan().factoryId(), approved.plan().planId(),
                requestDigest, key, status, approved.plan().requestedBy(), approved.approvedBy(), executedBy,
                receipts, success, failed, blocked, writes, writes, false, fitted(recovery, MAX_MESSAGE),
                "NOT_PUBLISHED", "NOT_PUBLISHED", "NOT_PUBLISHED", "NOT_PUBLISHED",
                approved.plan().requestedActor(), approved.approvedActor(), executedActor);
        return executionStore.save(result);
    }

    private static String actorKey(ActorRef actor) {
        return actor.type().name() + ":" + actor.id();
    }

    private void validateRequest(ActivationPlanRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank()
                || request.blueprintVersion() <= 0 || request.blueprintDigest().isBlank()
                || request.requestedBy() <= 0 || request.candidates().isEmpty()) {
            throw new IllegalArgumentException("Activation plan request is incomplete");
        }
    }

    private void validateCandidate(ActivationPlanRequest request, DraftCandidate candidate,
                                   CapabilityContract contract, Set<String> requested,
                                   List<ActivationIssue> issues) {
        if (contract.adapterMode() != AdapterMode.MODULE_DRAFT) {
            issues.add(issue("READ_ONLY_CAPABILITY_CANNOT_ACTIVATE", candidate.capabilityId(),
                    "只读能力不能进入 activation", "从 activation plan 移除该能力"));
        }
        if (!"DRAFT".equals(candidate.currentState()) || candidate.draftId().isBlank()
                || candidate.expectedRevision() <= 0 || candidate.expectedDigest().isBlank()) {
            issues.add(issue("DRAFT_IDENTITY_INVALID", candidate.capabilityId(),
                    "candidate 不是带版本与摘要的 DRAFT", "回读模块 DRAFT 后重新预览"));
        }
        if (!commands.containsKey(candidate.capabilityId())) {
            issues.add(issue("ACTIVATION_COMMAND_NOT_REGISTERED", candidate.capabilityId(),
                    "模块专用 activation command 未注册", "独立实现、审核并注册 " + contract.dedicatedCommand()));
        }
        String permission = activationPermission(candidate.capabilityId());
        if (!request.requesterPermissions().contains(permission)) {
            issues.add(issue("REQUESTER_PERMISSION_MISSING", candidate.capabilityId(),
                    "发起人无此模块 activation 权限", "由有权限但仍需独立审批的操作者发起"));
        }
        if (commands.get(candidate.capabilityId()) instanceof FactoryFormalModuleActivationCommand formal) {
            issues.addAll(formal.validateFormalPrerequisites(request.factoryId(), candidate.draftId(),
                    request.requestedActor()));
            return;
        }
        for (String dependency : contract.dependencies()) {
            if (!requested.contains(dependency)
                    && !"ACTIVE".equals(candidate.externalDependencyRuntimeStates().get(dependency))) {
                issues.add(issue("DEPENDENCY_NOT_ACTIVE_OR_INCLUDED", candidate.capabilityId(),
                        "依赖 " + dependency + " 未在计划内且运行态不是 ACTIVE",
                        "先激活依赖，或把依赖的已复核 DRAFT 加入同一计划"));
            }
        }
    }

    private void verifyNoVersionDrift(ActivationPlan plan, ActorRef executedActor) {
        for (ModuleActivationPlan module : plan.modules()) {
            FactoryModuleActivationCommand command = commands.get(module.capabilityId());
            if (command instanceof FactoryFormalModuleActivationCommand formal
                    && !formal.validateFormalPrerequisites(plan.factoryId(), module.draftId(), executedActor).isEmpty()) {
                throw new IllegalArgumentException("Formal prerequisites drifted for " + module.capabilityId());
            }
            CurrentDraft current = command.currentDraft(plan.factoryId(), module.draftId(), executedActor);
            if (current == null || !module.capabilityId().equals(current.capabilityId())
                    || !module.draftId().equals(current.draftId())
                    || module.expectedRevision() != current.revision()
                    || !module.expectedDigest().equals(current.digest())
                    || !"DRAFT".equals(current.state())) {
                throw new IllegalArgumentException("Draft version drift for " + module.capabilityId());
            }
        }
    }

    private void requireReadback(ModuleActivationPlan plan, ModuleCommandReceipt receipt, RuntimeReadback readback) {
        if (receipt == null || readback == null
                || !plan.capabilityId().equals(receipt.capabilityId())
                || !plan.capabilityId().equals(readback.capabilityId())
                || receipt.commandReceiptId().isBlank() || readback.targetRuntimeId().isBlank()
                || receipt.activatedRevision() != readback.activeRevision()
                || !receipt.activatedDigest().equals(readback.activeDigest())
                || !"ACTIVE".equals(readback.runtimeState())) {
            throw new IllegalStateException("Runtime readback mismatch for " + plan.capabilityId());
        }
        // A module claiming success has to hand back something that can actually be recorded.
        // Identifiers are not trimmed to fit — a shortened receipt id is a different receipt id —
        // so an over-long or malformed one fails this module instead of corrupting its record or,
        // worse, failing the INSERT and taking every other module's receipt down with it.
        if (receipt.commandReceiptId().length() > MAX_IDENTIFIER
                || readback.targetRuntimeId().length() > MAX_IDENTIFIER
                || !readback.activeDigest().matches(DIGEST_PATTERN)) {
            throw new IllegalStateException("Module returned an unrecordable receipt for "
                    + plan.capabilityId() + ": identifiers must fit " + MAX_IDENTIFIER
                    + " characters and the active digest must be a 64-character hex digest");
        }
    }

    /**
     * A module's own recovery text, obtained defensively: this is called from inside the failure
     * handler, and a module that throws while being asked how to recover would otherwise replace
     * a recorded module failure with an unrecorded whole-execution failure.
     */
    private String recoveryOf(FactoryModuleActivationCommand command, ModuleCommandReceipt receipt,
                              ModuleActivationPlan module) {
        try {
            return FactoryBlueprintActivationModels.clean(command.recovery(receipt));
        } catch (RuntimeException broken) {
            return module.recoveryStrategy();
        }
    }

    /** Free text trimmed to its column, with the trim made visible rather than silent. */
    private String fitted(String value, int max) {
        String clean = FactoryBlueprintActivationModels.clean(value);
        if (clean.length() <= max) {
            return clean;
        }
        String marker = "...[truncated]";
        return clean.substring(0, max - marker.length()) + marker;
    }

    /** Empty unless it really is a digest; the column only accepts a digest or nothing. */
    private String storableDigest(String value) {
        String clean = FactoryBlueprintActivationModels.clean(value);
        return clean.matches(DIGEST_PATTERN) ? clean : "";
    }

    private List<ModuleActivationPlan> topological(List<ModuleActivationPlan> modules) {
        Map<String, ModuleActivationPlan> indexed = new LinkedHashMap<>();
        modules.forEach(module -> indexed.put(module.capabilityId(), module));
        List<ModuleActivationPlan> ordered = new ArrayList<>();
        Set<String> complete = new LinkedHashSet<>();
        for (String id : indexed.keySet().stream().sorted().toList()) order(id, indexed, complete, new LinkedHashSet<>(), ordered);
        return List.copyOf(ordered);
    }

    private void order(String id, Map<String, ModuleActivationPlan> indexed, Set<String> complete,
                       Set<String> active, List<ModuleActivationPlan> ordered) {
        if (complete.contains(id)) return;
        if (!active.add(id)) throw new IllegalArgumentException("Activation dependency cycle: " + active);
        for (String dependency : indexed.get(id).dependencies()) {
            if (indexed.containsKey(dependency)) order(dependency, indexed, complete, active, ordered);
        }
        active.remove(id);
        complete.add(id);
        ordered.add(indexed.get(id));
    }

    private ActivationCenterReceipt replayOrReject(ActivationCenterReceipt prior, String requestDigest) {
        if (!prior.requestDigest().equals(requestDigest)) {
            throw new IllegalArgumentException("Idempotency key belongs to another activation payload");
        }
        return replay(prior);
    }

    private ActivationCenterReceipt replay(ActivationCenterReceipt prior) {
        return new ActivationCenterReceipt(prior.schemaVersion(), prior.factoryId(), prior.planId(),
                prior.requestDigest(), prior.idempotencyKey(), prior.status(), prior.requestedBy(), prior.approvedBy(),
                prior.executedBy(), prior.modules(), prior.successfulCount(), prior.failedCount(), prior.blockedCount(),
                prior.originalBusinessWriteCount(), 0, true, prior.recoverySummary(),
                "NOT_PUBLISHED", "NOT_PUBLISHED", "NOT_PUBLISHED", "NOT_PUBLISHED",
                prior.requestedActor(), prior.approvedActor(), prior.executedActor());
    }

    /**
     * The request states which Blueprint it believes it is activating. Recompute that from the
     * server's own canonical Blueprint instead of trusting it: a caller-supplied digest proves
     * nothing about what the server currently holds.
     */
    private void verifyBlueprintAuthority(ActivationPlanRequest request, List<ActivationIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(request.factoryId());
        if (response == null || !request.blueprintId().equals(response.getId())
                || response.getVersion() == null
                || request.blueprintVersion() != response.getVersion()
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState())
                     || (!request.candidates().isEmpty() && request.candidates().stream().allMatch(candidate ->
                         "workflow".equals(candidate.capabilityId())
                         && com.cretas.aims.blueprint.draftentry.BlueprintDraftLifecycle.allows(
                             response.getLifecycleState(), candidate.capabilityId()))))) {
            issues.add(issue("BLUEPRINT_LINEAGE_MISMATCH", request.blueprintId(),
                    "服务端当前 Blueprint 与 activation 请求的身份或版本不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return;
        }
        if (!request.blueprintDigest().equals(serverBlueprintDigest(response))) {
            issues.add(issue("BLUEPRINT_DIGEST_DRIFT", request.blueprintId(),
                    "activation 请求携带的 Blueprint 摘要与服务端重算结果不一致",
                    "重新读取服务端 Blueprint 摘要后重新预览"));
        }
    }

    public String serverBlueprintDigest(BlueprintResponse response) {
        return digest("BLUEPRINT", response.getFactoryId(), response.getId(),
                String.valueOf(response.getVersion()), String.valueOf(response.getRowVersion()),
                response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
    }

    private int count(List<ModuleActivationReceipt> values, ModuleExecutionStatus status) {
        return (int) values.stream().filter(item -> item.status() == status).count();
    }

    private String activationPermission(String capabilityId) {
        return "factory-blueprint:activation:" + capabilityId;
    }

    private ActivationIssue issue(String code, String capabilityId, String message, String recovery) {
        return new ActivationIssue(code, capabilityId, message, recovery);
    }

    private String planDigest(ActivationPlanRequest request, List<ModuleActivationPlan> modules,
                              List<ActivationIssue> issues, PlanStatus status) {
        List<String> values = new ArrayList<>(List.of(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.blueprintDigest(),
                actorKey(request.requestedActor()), status.name()));
        request.requesterPermissions().stream().sorted().forEach(values::add);
        for (ModuleActivationPlan module : modules) {
            values.add(module.capabilityId()); values.add(module.draftId());
            values.add(String.valueOf(module.expectedRevision())); values.add(module.expectedDigest());
            values.addAll(module.dependencies());
        }
        for (ActivationIssue issue : issues) {
            values.add(issue.code()); values.add(issue.capabilityId()); values.add(issue.message());
        }
        return digest(values.toArray(String[]::new));
    }

    private String digest(String... values) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            String joined = String.join("\u001f", values);
            return HexFormat.of().formatHex(sha.digest(joined.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
