package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.blueprintapproval.FactoryAssemblyApproval;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.blueprintapproval.FactoryAssemblyApprovalRepository;
import com.cretas.aims.security.ActorRef;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.web.bind.annotation.*;

/** The public request contains selections, never a client plan, actor, graph or permissions. */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@RestController
@RequestMapping("/api/platform/factory-assembly/factories/{factoryId}/ai-initialize/blueprints/{blueprintId}/formal-activation")
public class FactoryAssemblyActivationController {
    private static final Set<String> SELECTION_FIELDS = Set.of("capabilityId", "configDraftId",
            "workflowDraftId", "bomDraftId", "criticalControlsDraftId");
    private final FactoryAssemblyPlanFactory plans;
    private final FactoryBlueprintActivationCenter center;
    private final FactoryAssemblyApprovalLifecycle lifecycle;
    private final FactoryAssemblyApprovalRepository approvals;
    private final AssemblyActivationActorResolver actors;
    private final Map<String, FactoryFormalModuleActivationCommand> commands;
    private final ObjectMapper json;
    private final FactoryActivationExecutionStore executionStore;
    private final FactoryFormalRuntimeReadback committedReadback;
    private final com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService canonicalBlueprints;

    public FactoryAssemblyActivationController(FactoryAssemblyPlanFactory plans,
            FactoryBlueprintActivationCenter center, FactoryAssemblyApprovalLifecycle lifecycle,
            FactoryAssemblyApprovalRepository approvals, AssemblyActivationActorResolver actors,
            List<FactoryFormalModuleActivationCommand> commands, ObjectMapper json,
            FactoryActivationExecutionStore executionStore, FactoryFormalRuntimeReadback committedReadback,
            com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService canonicalBlueprints) {
        this.plans = plans; this.center = center; this.lifecycle = lifecycle;
        this.approvals = approvals; this.actors = actors; this.json = json;
        this.executionStore = executionStore;
        this.committedReadback = java.util.Objects.requireNonNull(committedReadback);
        this.canonicalBlueprints = java.util.Objects.requireNonNull(canonicalBlueprints);
        this.commands = commands.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                FactoryFormalModuleActivationCommand::capabilityId, command -> command));
        if (!this.commands.keySet().equals(Set.of("modules", "workflow"))) {
            throw new IllegalStateException("Only the two reviewed formal commands may be exposed");
        }
    }

    public record ApprovalView(String approvalId, String capabilityId, String draftId, String status,
            String planDigest, String approvalDigest, ActorRef requestedActor, ActorRef approvedActor,
            Instant approvedAt, Instant expiresAt) {}
    public record ExecutionView(String status, String idempotencyKey, boolean idempotentReplay,
            int invocationBusinessWriteCount, int originalBusinessWriteCount, boolean readonlyReadback,
            ActorRef requestedActor, ActorRef approvedActor,
            ActorRef executedActor, List<ModuleActivationReceipt> modules,
            List<RuntimeReadback> verifiedRuntime, ApprovalView approval) {}

    @PostMapping("/preview")
    public ApiResponse<ActivationPlan> preview(@PathVariable String factoryId,
            @PathVariable String blueprintId, @RequestBody JsonNode selection,
            HttpServletRequest login) {
        return ApiResponse.success(center.preview(serverPlan(factoryId, blueprintId, selection, login)));
    }

    @PostMapping("/requests")
    public ApiResponse<ApprovalView> request(@PathVariable String factoryId,
            @PathVariable String blueprintId, @RequestBody JsonNode body, HttpServletRequest login) {
        only(body, Set.of("selection", "confirmedPlanDigest"));
        var request = serverPlan(factoryId, blueprintId, body.get("selection"), login);
        return ApiResponse.success(view(lifecycle.request(login, request, text(body, "confirmedPlanDigest"))));
    }

    /** What a persisted approval may be used for now; only the first two continue the original flow. */
    public enum Recoverability { CONTINUE_APPROVAL, CONTINUE_EXECUTION, RECEIPT_ONLY, EXPIRED,
        HISTORICAL_VERSION, BLUEPRINT_CHANGED, SNAPSHOT_UNREADABLE }
    public record RecoverableApproval(ApprovalView approval, Integer blueprintVersion, Recoverability recoverability) {}
    public record RecoverableApprovals(Integer currentBlueprintVersion, List<RecoverableApproval> approvals) {}

    static final Set<String> RECOVERABLE_STATUSES = Set.of("REQUESTED", "APPROVED", "EXECUTED");
    static final int RECOVERABLE_LIMIT = 50;

    /**
     * This blueprint's persisted approvals so a request can be resumed after a reload without typing
     * its number. Listing never authorizes anything: approve and execute still run every server check
     * (re-preview, current blueprint identity, version and digest, expiry, separation of actors).
     */
    @GetMapping("/requests")
    public ApiResponse<RecoverableApprovals> recoverable(@PathVariable String factoryId,
            @PathVariable String blueprintId, @RequestParam(required = false) String capabilityId,
            HttpServletRequest login) {
        actors.authenticated(login, factoryId, Set.of(FactoryBlueprintActivationCenter.EXECUTE_PERMISSION));
        if (capabilityId != null && !commands.containsKey(capabilityId)) throw invalid("只支持配置应用和 Workflow 发布");
        var current = canonicalBlueprints.current(factoryId);
        boolean isCurrent = current != null && blueprintId.equals(current.getId()) && current.getVersion() != null;
        Integer currentVersion = isCurrent ? current.getVersion() : null;
        String currentDigest = isCurrent ? center.serverBlueprintDigest(current) : null;
        Instant now = Instant.now();
        var rows = approvals.findRecoverable(factoryId, blueprintId, RECOVERABLE_STATUSES,
                org.springframework.data.domain.PageRequest.of(0, RECOVERABLE_LIMIT));
        var items = rows.stream()
                .filter(row -> capabilityId == null || capabilityId.equals(row.getCapabilityId()))
                .map(row -> {
                    ActivationPlan snapshot = snapshot(row, json);
                    return new RecoverableApproval(view(row), snapshot == null ? null : snapshot.blueprintVersion(),
                            classify(row, snapshot, currentVersion, currentDigest, now));
                })
                .toList();
        return ApiResponse.success(new RecoverableApprovals(currentVersion, items));
    }

    /** The stored plan, only when it is this row's own: factory, blueprint, digest and single module match. */
    static ActivationPlan snapshot(FactoryAssemblyApproval row, ObjectMapper json) {
        try {
            ActivationPlan plan = json.readValue(row.getPlanJson(), ActivationPlan.class);
            boolean own = plan != null && row.getFactoryId().equals(plan.factoryId())
                    && row.getBlueprintId().equals(plan.blueprintId())
                    && row.getPlanDigest() != null && row.getPlanDigest().equals(plan.planDigest())
                    && plan.modules() != null && plan.modules().size() == 1
                    && row.getCapabilityId().equals(plan.modules().get(0).capabilityId());
            return own ? plan : null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException | NullPointerException unreadable) {
            return null;
        }
    }

    /**
     * An unreadable or foreign snapshot never continues; an executed request only shows its receipt; a
     * request for another blueprint version, or for this version after the blueprint changed, is
     * history; an approval continues only while its expiry exists and is in the future; a request
     * awaiting approval has no expiry yet, which is normal.
     */
    static Recoverability classify(FactoryAssemblyApproval row, ActivationPlan snapshot, Integer currentVersion,
            String currentDigest, Instant now) {
        if (snapshot == null) return Recoverability.SNAPSHOT_UNREADABLE;
        if ("EXECUTED".equals(row.getStatus())) return Recoverability.RECEIPT_ONLY;
        if (currentVersion == null || snapshot.blueprintVersion() != currentVersion) return Recoverability.HISTORICAL_VERSION;
        if (currentDigest == null || !currentDigest.equals(snapshot.blueprintDigest())) return Recoverability.BLUEPRINT_CHANGED;
        if ("APPROVED".equals(row.getStatus())) {
            return row.getExpiresAt() != null && now.isBefore(row.getExpiresAt())
                    ? Recoverability.CONTINUE_EXECUTION : Recoverability.EXPIRED;
        }
        return "REQUESTED".equals(row.getStatus()) ? Recoverability.CONTINUE_APPROVAL : Recoverability.SNAPSHOT_UNREADABLE;
    }

    @GetMapping("/requests/{approvalId}")
    public ApiResponse<ApprovalView> read(@PathVariable String factoryId,
            @PathVariable String blueprintId, @PathVariable String approvalId, HttpServletRequest login) {
        return ApiResponse.success(view(owned(factoryId, blueprintId, approvalId, login)));
    }

    @PostMapping("/requests/{approvalId}/approve")
    public ApiResponse<ApprovalView> approve(@PathVariable String factoryId,
            @PathVariable String blueprintId, @PathVariable String approvalId,
            @RequestBody JsonNode body, HttpServletRequest login) {
        only(body, Set.of("confirmedPlanDigest", "reason"));
        owned(factoryId, blueprintId, approvalId, login, FactoryBlueprintActivationCenter.APPROVE_PERMISSION);
        return ApiResponse.success(view(lifecycle.approve(login, factoryId, approvalId,
                text(body, "confirmedPlanDigest"), text(body, "reason"))));
    }

    @PostMapping("/requests/{approvalId}/execute")
    public ApiResponse<ExecutionView> execute(@PathVariable String factoryId,
            @PathVariable String blueprintId, @PathVariable String approvalId,
            @RequestBody JsonNode body, HttpServletRequest login) {
        only(body, Set.of("confirmedApprovalDigest", "idempotencyKey"));
        owned(factoryId, blueprintId, approvalId, login);
        String key = text(body, "idempotencyKey");
        approvalKey(approvalId, key);
        var receipt = lifecycle.execute(login, factoryId, approvalId,
                text(body, "confirmedApprovalDigest"), key);
        var row = owned(factoryId, blueprintId, approvalId, login);
        return ApiResponse.success(executionView(factoryId, receipt, row, false));
    }

    @GetMapping("/requests/{approvalId}/receipt")
    public ApiResponse<ExecutionView> receipt(@PathVariable String factoryId,
            @PathVariable String blueprintId, @PathVariable String approvalId,
            @RequestParam String idempotencyKey, HttpServletRequest login) {
        var row = owned(factoryId, blueprintId, approvalId, login);
        if (!"EXECUTED".equals(row.getStatus()) || idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > 128) throw invalid("确认申请尚未执行或执行键无效");
        approvalKey(approvalId, idempotencyKey);
        var receipt = executionStore.find(factoryId, idempotencyKey)
                .orElseThrow(() -> new BusinessException(404, "当前工厂不存在此执行回执")
                        .withCode("ASSEMBLY_EXECUTION_NOT_FOUND"));
        ActivationPlan saved;
        try { saved = json.readValue(row.getPlanJson(), ActivationPlan.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw invalid("保存的申请计划无法回读"); }
        if (!factoryId.equals(saved.factoryId()) || !blueprintId.equals(saved.blueprintId())
                || !row.getPlanDigest().equals(saved.planDigest())
                || saved.modules().size() != 1
                || !row.getCapabilityId().equals(saved.modules().get(0).capabilityId())
                || !row.getDraftId().equals(saved.modules().get(0).draftId())
                || receipt.modules().size() != 1
                || !row.getCapabilityId().equals(receipt.modules().get(0).capabilityId())
                || !factoryId.equals(receipt.factoryId()) || !saved.planId().equals(receipt.planId())
                || !idempotencyKey.equals(receipt.idempotencyKey())
                || !new ActorRef(row.getRequestedActorType(), row.getRequestedActorId()).equals(receipt.requestedActor())
                || row.getApprovedActorType() == null || row.getApprovedActorId() == null
                || receipt.executedActor() == null
                || !new ActorRef(row.getApprovedActorType(), row.getApprovedActorId()).equals(receipt.approvedActor())) {
            throw invalid("执行回执不属于此确认申请");
        }
        return ApiResponse.success(executionView(factoryId, receipt, row, true));
    }

    private ExecutionView executionView(String factoryId, ActivationCenterReceipt receipt,
            FactoryAssemblyApproval row, boolean readonlyReadback) {
        // Legacy center publication flags are intentionally absent here. Only persisted readbacks
        // from the owning formal command supply this endpoint's actual runtime result.
        List<RuntimeReadback> verified = committedReadback.read(() -> receipt.modules().stream()
                .filter(module -> module.status() == ModuleExecutionStatus.ACTIVATED)
                .map(module -> {
                    var current = commands.get(module.capabilityId()).readBack(factoryId,
                            new ModuleCommandReceipt(module.capabilityId(), module.commandReceiptId(),
                                    module.targetRuntimeId(), module.activeRevision(), module.activeDigest(),
                                    module.businessWriteCount()));
                    if (current == null || !"ACTIVE".equals(current.runtimeState())
                            || !module.capabilityId().equals(current.capabilityId())
                            || !module.targetRuntimeId().equals(current.targetRuntimeId())
                            || module.activeRevision() != current.activeRevision()
                            || !module.activeDigest().equals(current.activeDigest())) {
                        throw new BusinessException(409, "当前正式运行态与此执行回执不一致")
                                .withCode("ASSEMBLY_RUNTIME_READBACK_DRIFTED").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
                    }
                    return current;
                })
                .toList());
        return new ExecutionView(receipt.status(), receipt.idempotencyKey(),
                !readonlyReadback && receipt.idempotentReplay(),
                readonlyReadback ? 0 : receipt.invocationBusinessWriteCount(),
                receipt.originalBusinessWriteCount(), readonlyReadback, receipt.requestedActor(),
                receipt.approvedActor(), receipt.executedActor(), receipt.modules(), verified, view(row));
    }

    private ActivationPlanRequest serverPlan(String factoryId, String blueprintId,
            JsonNode body, HttpServletRequest login) {
        only(body, SELECTION_FIELDS);
        String capability = text(body, "capabilityId");
        if (!commands.containsKey(capability)) throw invalid("只支持配置应用和 Workflow 发布");
        ActorRef actor = actors.authenticated(login, factoryId,
                Set.of("factory-blueprint:activation:" + capability));
        FactoryAssemblyPlanFactory.Selection selection;
        try { selection = json.treeToValue(body, FactoryAssemblyPlanFactory.Selection.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException failure) {
            throw invalid("正式操作选择编号格式不正确");
        }
        var request = plans.build(factoryId, selection, actor);
        if (!blueprintId.equals(request.blueprintId())) throw invalid("请选择当前版本的蓝图");
        return request;
    }

    private FactoryAssemblyApproval owned(String factoryId, String blueprintId,
            String approvalId, HttpServletRequest login) {
        return owned(factoryId, blueprintId, approvalId, login, FactoryBlueprintActivationCenter.EXECUTE_PERMISSION);
    }

    private FactoryAssemblyApproval owned(String factoryId, String blueprintId,
            String approvalId, HttpServletRequest login, String permission) {
        // Check current login authority before reading any factory-owned approval.
        actors.authenticated(login, factoryId, Set.of(permission));
        var row = approvals.findByIdAndFactoryId(approvalId, factoryId)
                .filter(value -> blueprintId.equals(value.getBlueprintId()))
                .orElseThrow(() -> new BusinessException(404, "当前蓝图不存在此确认申请")
                        .withCode("ASSEMBLY_APPROVAL_NOT_FOUND"));
        return row;
    }

    private static ApprovalView view(FactoryAssemblyApproval row) {
        return new ApprovalView(row.getId(), row.getCapabilityId(), row.getDraftId(), row.getStatus(),
                row.getPlanDigest(), row.getApprovalDigest(),
                new ActorRef(row.getRequestedActorType(), row.getRequestedActorId()),
                row.getApprovedActorType() == null ? null : new ActorRef(row.getApprovedActorType(), row.getApprovedActorId()),
                row.getApprovedAt(), row.getExpiresAt());
    }

    private static void only(JsonNode body, Set<String> allowed) {
        if (body == null || !body.isObject()) throw invalid("请求必须是选择对象");
        body.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw invalid("请求不允许字段：" + name);
        });
    }
    private static String text(JsonNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw invalid("缺少字段：" + field);
        return value.asText();
    }
    private static void approvalKey(String approvalId, String key) {
        if (!("formal:" + approvalId).equals(key) || key.length() > 128) {
            throw invalid("执行键必须绑定当前确认申请");
        }
    }
    private static BusinessException invalid(String message) {
        return new BusinessException(409, message).withCode("ASSEMBLY_FORMAL_REQUEST_INVALID").withHint("请刷新建厂预览，核对草稿版本后重新确认操作");
    }
}
