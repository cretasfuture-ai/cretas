package com.cretas.aims.blueprint.activation;

import com.cretas.aims.blueprint.activation.FactoryBlueprintActivationModels.*;
import com.cretas.aims.entity.blueprintapproval.FactoryAssemblyApproval;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.blueprintapproval.FactoryAssemblyApprovalRepository;
import com.cretas.aims.security.ActorRef;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;

/**
 * Durable approval boundary. The plan request is constructed by server-owned module readers;
 * it is never an HTTP request DTO. Wiring is deliberately explicit until those readers exist.
 */
public class FactoryAssemblyApprovalLifecycle {
    private final FactoryAssemblyApprovalRepository approvals;
    private final FactoryBlueprintActivationCenter center;
    private final AssemblyActivationActorResolver actors;
    private final ObjectMapper json;
    private final Clock clock;
    private final FactoryActivationExecutionStore executionStore;

    public FactoryAssemblyApprovalLifecycle(FactoryAssemblyApprovalRepository approvals,
            FactoryBlueprintActivationCenter center, AssemblyActivationActorResolver actors,
            ObjectMapper json, Clock clock) {
        this(approvals, center, actors, json, clock, null);
    }

    public FactoryAssemblyApprovalLifecycle(FactoryAssemblyApprovalRepository approvals,
            FactoryBlueprintActivationCenter center, AssemblyActivationActorResolver actors,
            ObjectMapper json, Clock clock, FactoryActivationExecutionStore executionStore) {
        this.approvals = Objects.requireNonNull(approvals);
        this.center = Objects.requireNonNull(center);
        this.actors = Objects.requireNonNull(actors);
        this.json = Objects.requireNonNull(json);
        this.clock = Objects.requireNonNull(clock);
        this.executionStore = executionStore;
    }

    @Transactional
    public FactoryAssemblyApproval request(HttpServletRequest login, ActivationPlanRequest serverRequest,
            String confirmedPlanDigest) {
        if (serverRequest == null || serverRequest.candidates().size() != 1) {
            throw invalid("每次确认只能选择一项配置应用或 Workflow 发布");
        }
        DraftCandidate candidate = serverRequest.candidates().get(0);
        requireCapability(candidate.capabilityId());
        Set<String> required = Set.of(permission(candidate.capabilityId()));
        ActorRef requester = actors.authenticated(login, serverRequest.factoryId(), required);
        if (!requester.equals(serverRequest.requestedActor())) {
            throw invalid("服务器计划的操作人与当前登录人不一致");
        }
        // Persist freshly revalidated permissions, rather than any cached plan permission set.
        ActivationPlanRequest currentRequest = new ActivationPlanRequest(serverRequest.factoryId(),
                serverRequest.blueprintId(), serverRequest.blueprintVersion(), serverRequest.blueprintDigest(),
                requester, actors.authorize(requester, serverRequest.factoryId(), required),
                serverRequest.candidates());
        ActivationPlan plan = center.preview(currentRequest);
        requireReady(plan, confirmedPlanDigest);
        FactoryAssemblyApproval row = new FactoryAssemblyApproval();
        row.setId(UUID.randomUUID().toString());
        row.setFactoryId(plan.factoryId());
        row.setBlueprintId(plan.blueprintId());
        row.setCapabilityId(candidate.capabilityId());
        row.setDraftId(candidate.draftId());
        row.setPlanDigest(plan.planDigest());
        row.setPlanRequestJson(write(currentRequest));
        row.setPlanJson(write(plan));
        row.setRequestedActorType(requester.type());
        row.setRequestedActorId(requester.id());
        row.setStatus("REQUESTED");
        return approvals.saveAndFlush(row);
    }

    @Transactional
    public FactoryAssemblyApproval approve(HttpServletRequest login, String factoryId, String approvalId,
            String confirmedPlanDigest, String reason) {
        FactoryAssemblyApproval row = locked(factoryId, approvalId);
        if (!"REQUESTED".equals(row.getStatus())) throw invalid("此确认申请已处理，请刷新回执");
        ActorRef approver = actors.authenticated(login, factoryId,
                Set.of(FactoryBlueprintActivationCenter.APPROVE_PERMISSION));
        ActorRef requester = new ActorRef(row.getRequestedActorType(), row.getRequestedActorId());
        if (approver.equals(requester)) throw invalid("申请人与审批人必须是不同操作人");
        actors.authorize(requester, factoryId, Set.of(permission(row.getCapabilityId())));
        ActivationPlanRequest savedRequest = read(row.getPlanRequestJson(), ActivationPlanRequest.class);
        ActivationPlan savedPlan = read(row.getPlanJson(), ActivationPlan.class);
        requireBinding(row, savedRequest, savedPlan);
        // Re-read canonical authority before granting approval; the stored JSON is not authority.
        ActivationPlan currentPlan = center.preview(savedRequest);
        requireReady(currentPlan, row.getPlanDigest());
        requireReady(savedPlan, confirmedPlanDigest);
        if (!currentPlan.equals(savedPlan)) throw invalid("已保存的确认计划内容与服务器重算不一致");
        ApprovedActivationPlan approved = center.approve(savedPlan, approver,
                actors.authorize(approver, factoryId, Set.of(FactoryBlueprintActivationCenter.APPROVE_PERMISSION)),
                reason);
        if (approved.approvedAt() == null || approved.approvedAt().isAfter(clock.instant())) {
            throw invalid("服务器审批时间无效");
        }
        // PostgreSQL timestamps retain microseconds. Store the same precision in JSON and
        // columns so a genuine approval remains exactly bound after a fresh database read.
        approved = new ApprovedActivationPlan(approved.plan(), approved.approvedActor(),
                approved.approvalReason(), approved.approvalDigest(),
                approved.approvedAt().truncatedTo(ChronoUnit.MICROS), approved.approvalState(),
                approved.activationState());
        row.setApprovedActorType(approver.type());
        row.setApprovedActorId(approver.id());
        row.setApprovedJson(write(approved));
        row.setApprovalDigest(approved.approvalDigest());
        row.setApprovedAt(approved.approvedAt());
        row.setExpiresAt(approved.approvedAt().plus(FactoryBlueprintActivationCenter.DEFAULT_APPROVAL_TTL));
        row.setStatus("APPROVED");
        return approvals.saveAndFlush(row);
    }

    /** Only the stored, factory-owned approval may reach the durable execution center. */
    @Transactional
    public ActivationCenterReceipt execute(HttpServletRequest login, String factoryId, String approvalId,
            String confirmedApprovalDigest, String idempotencyKey) {
        if (executionStore == null) throw invalid("缺少持久执行台账，不能执行确认申请");
        FactoryAssemblyApproval row = locked(factoryId, approvalId);
        if (!"APPROVED".equals(row.getStatus()) && !"EXECUTED".equals(row.getStatus())) {
            throw invalid("此申请尚未获得人工审批");
        }
        Set<String> required = Set.of(FactoryBlueprintActivationCenter.EXECUTE_PERMISSION,
                permission(row.getCapabilityId()));
        ActorRef executor = actors.authenticated(login, factoryId, required);
        ActorRef requester = new ActorRef(row.getRequestedActorType(), row.getRequestedActorId());
        if (row.getApprovedActorType() == null || row.getApprovedActorId() == null) {
            throw invalid("已保存的审批身份不完整");
        }
        ActorRef approver = new ActorRef(row.getApprovedActorType(), row.getApprovedActorId());
        if (approver.equals(requester) || approver.equals(executor)) {
            throw invalid("审批人不能自审或执行自己的审批");
        }
        actors.authorize(requester, factoryId, Set.of(permission(row.getCapabilityId())));
        actors.authorize(approver, factoryId, Set.of(FactoryBlueprintActivationCenter.APPROVE_PERMISSION));
        Set<String> currentPermissions = actors.authorize(executor, factoryId, required);
        ActivationPlanRequest savedRequest = read(row.getPlanRequestJson(), ActivationPlanRequest.class);
        ActivationPlan savedPlan = read(row.getPlanJson(), ActivationPlan.class);
        requireBinding(row, savedRequest, savedPlan);
        ApprovedActivationPlan approved = read(row.getApprovedJson(), ApprovedActivationPlan.class);
        if (!approved.plan().equals(savedPlan) || !approved.approvedActor().equals(approver)
                || !Objects.equals(approved.approvalDigest(), row.getApprovalDigest())
                || !Objects.equals(confirmedApprovalDigest, row.getApprovalDigest())
                || !Objects.equals(approved.approvedAt(), row.getApprovedAt())
                || row.getApprovedAt() == null || row.getExpiresAt() == null
                || row.getApprovedAt().isAfter(clock.instant())
                || !row.getExpiresAt().equals(row.getApprovedAt().plus(FactoryBlueprintActivationCenter.DEFAULT_APPROVAL_TTL))) {
            throw invalid("审批内容、确认摘要或有效期已变化，请重新预览及审批");
        }
        String key = FactoryBlueprintActivationModels.clean(idempotencyKey);
        if (key.isBlank() || key.length() > 128) throw invalid("缺少有效执行幂等键");
        boolean hasTerminalReceipt = executionStore.find(factoryId, key).isPresent();
        if (!hasTerminalReceipt && !clock.instant().isBefore(row.getExpiresAt())) {
            throw invalid("审批内容、确认摘要或有效期已变化，请重新预览及审批");
        }
        if ("EXECUTED".equals(row.getStatus())) {
            // A processed approval cannot be executed with a fresh key. The center additionally
            // binds the existing receipt to this exact approval digest and authenticated actor.
            if (!hasTerminalReceipt) throw invalid("已执行的申请只能回放原执行键");
        } else if (!hasTerminalReceipt) {
            ActivationPlan currentPlan = center.preview(savedRequest);
            requireReady(currentPlan, row.getPlanDigest());
            if (!currentPlan.equals(savedPlan)) throw invalid("执行前计划或前置已变化，请重新预览及审批");
        }
        ActivationCenterReceipt receipt = center.execute(approved, confirmedApprovalDigest, key,
                executor, currentPermissions);
        if (!factoryId.equals(receipt.factoryId()) || !savedPlan.planId().equals(receipt.planId())
                || !requester.equals(receipt.requestedActor()) || !approver.equals(receipt.approvedActor())
                || !executor.equals(receipt.executedActor())) {
            throw invalid("持久执行回执与审批身份不一致");
        }
        if (!"EXECUTED".equals(row.getStatus())) {
            row.setStatus("EXECUTED");
            approvals.saveAndFlush(row);
        }
        return receipt;
    }

    private FactoryAssemblyApproval locked(String factoryId, String id) {
        if (factoryId == null || factoryId.isBlank() || id == null || id.isBlank()) throw invalid("缺少工厂或确认编号");
        return approvals.lockByIdAndFactoryId(id, factoryId)
                .orElseThrow(() -> new BusinessException(404, "当前工厂不存在此确认申请")
                        .withCode("ASSEMBLY_APPROVAL_NOT_FOUND"));
    }

    private void requireBinding(FactoryAssemblyApproval row, ActivationPlanRequest request, ActivationPlan plan) {
        if (!row.getFactoryId().equals(request.factoryId()) || !row.getFactoryId().equals(plan.factoryId())
                || !row.getBlueprintId().equals(request.blueprintId()) || !row.getBlueprintId().equals(plan.blueprintId())
                || !row.getPlanDigest().equals(plan.planDigest()) || request.candidates().size() != 1
                || plan.modules().size() != 1 || !row.getCapabilityId().equals(request.candidates().get(0).capabilityId())
                || !row.getDraftId().equals(request.candidates().get(0).draftId())
                || !row.getCapabilityId().equals(plan.modules().get(0).capabilityId())
                || !row.getDraftId().equals(plan.modules().get(0).draftId())
                || !new ActorRef(row.getRequestedActorType(), row.getRequestedActorId()).equals(request.requestedActor())
                || !request.requestedActor().equals(plan.requestedActor())) {
            throw invalid("已保存的确认申请与服务器计划不一致");
        }
    }

    private static void requireReady(ActivationPlan plan, String confirmedDigest) {
        if (plan == null || plan.status() != PlanStatus.READY_FOR_APPROVAL || !plan.issues().isEmpty()
                || confirmedDigest == null || !confirmedDigest.equals(plan.planDigest())) {
            throw invalid("前置条件或确认摘要已变化，请重新预览");
        }
    }

    private static String permission(String capability) {
        requireCapability(capability);
        return "factory-blueprint:activation:" + capability;
    }

    private static void requireCapability(String capability) {
        if (!"modules".equals(capability) && !"workflow".equals(capability)) {
            throw invalid("此确认申请只支持配置应用和 Workflow 发布");
        }
    }

    private String write(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException error) { throw invalid("服务器确认计划无法保存"); }
    }

    private <T> T read(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException | IllegalArgumentException error) { throw invalid("服务器确认计划无法回读"); }
    }

    private static BusinessException invalid(String message) {
        return new BusinessException(409, message).withCode("ASSEMBLY_APPROVAL_INVALID");
    }
}
