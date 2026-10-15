package com.cretas.aims.service.equipmentplandraft;

import com.cretas.aims.entity.EquipmentMaintenance;
import com.cretas.aims.entity.FactoryEquipment;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraft;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraftEntry;
import com.cretas.aims.entity.equipmentplan.EquipmentCalibrationPlanDraftProcessRef;
import com.cretas.aims.entity.workprocess.WorkProcessDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.EquipmentMaintenanceRepository;
import com.cretas.aims.repository.EquipmentRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftEntryRepository;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftProcessRefRepository;
import com.cretas.aims.repository.equipmentplan.EquipmentCalibrationPlanDraftRepository;
import com.cretas.aims.repository.workprocess.WorkProcessDraftRepository;
import com.cretas.aims.service.equipmentplandraft.EquipmentCalibrationPlanDraftModels.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class EquipmentCalibrationPlanDraftCommand {
    public static final String CAPABILITY_ID = "equipment-calibration";
    public static final String REQUIRED_PERMISSION = "equipment:plan:draft";
    private static final int MAX_PLANS = 200;
    private static final int MAX_PROCESS_REFS_PER_PLAN = 50;
    private static final int MAX_FREQUENCY_VALUE = 1_000_000;
    private static final int MAX_DURATION_MINUTES = 10_080;

    private final EquipmentCalibrationPlanDraftRepository drafts;
    private final EquipmentCalibrationPlanDraftEntryRepository entries;
    private final EquipmentCalibrationPlanDraftProcessRefRepository refs;
    private final EquipmentRepository equipment;
    private final EquipmentMaintenanceRepository maintenance;
    private final WorkProcessRepository processes;
    private final WorkProcessDraftRepository processDrafts;
    private final UserRepository users;

    public EquipmentCalibrationPlanDraftCommand(
            EquipmentCalibrationPlanDraftRepository drafts,
            EquipmentCalibrationPlanDraftEntryRepository entries,
            EquipmentCalibrationPlanDraftProcessRefRepository refs,
            EquipmentRepository equipment,
            EquipmentMaintenanceRepository maintenance,
            WorkProcessRepository processes,
            WorkProcessDraftRepository processDrafts,
            UserRepository users) {
        this.drafts = drafts;
        this.entries = entries;
        this.refs = refs;
        this.equipment = equipment;
        this.maintenance = maintenance;
        this.processes = processes;
        this.processDrafts = processDrafts;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        Set<String> seenPlans = new HashSet<>();
        StringBuilder sourceSnapshot = new StringBuilder();

        for (int planIndex = 0; planIndex < request.plans().size(); planIndex++) {
            PlanEntry plan = request.plans().get(planIndex);
            String target = "plans[" + planIndex + "]";
            validateEntry(plan, target, issues);
            if (plan.equipmentId() == null || plan.equipmentId() <= 0) {
                issues.add(issue("EQUIPMENT_PLAN_EQUIPMENT_REQUIRED", target,
                        "设备必须指定", "选择当前工厂设备"));
                sourceSnapshot.append("missing-equipment|");
                continue;
            }

            FactoryEquipment currentEquipment = equipment
                    .findByIdAndFactoryId(plan.equipmentId(), request.factoryId()).orElse(null);
            if (currentEquipment == null) {
                issues.add(issue("EQUIPMENT_PLAN_EQUIPMENT_NOT_FOUND", target,
                        "设备不属于当前工厂或不存在", "重新读取当前工厂设备"));
                sourceSnapshot.append("unknown-equipment:").append(plan.equipmentId()).append('|');
                continue;
            }

            String equipmentSnapshot = equipmentDigest(currentEquipment);
            sourceSnapshot.append("equipment:").append(equipmentSnapshot).append('|');
            sourceSnapshot.append("maintenance:")
                    .append(latestMaintenanceDigest(currentEquipment.getId())).append('|');
            if (isUnavailable(currentEquipment.getStatus())) {
                issues.add(issue("EQUIPMENT_PLAN_EQUIPMENT_UNUSABLE", target,
                        "报废或离线设备不能建立新计划", "改选可用设备"));
            }
            if (!isDigest(plan.equipmentSnapshotDigest())
                    || !equipmentSnapshot.equals(plan.equipmentSnapshotDigest())) {
                issues.add(issue("EQUIPMENT_PLAN_EQUIPMENT_SNAPSHOT_CONFLICT", target,
                        "设备快照已变化", "重新生成设备计划预览"));
            }

            validateProcessRefs(request, plan, target, issues, sourceSnapshot);
            if (!seenPlans.add(planIdentity(plan))) {
                issues.add(issue("EQUIPMENT_PLAN_DUPLICATE", target,
                        "同一设备、计划类型和工序范围不能重复", "合并或删除重复计划"));
            }
        }

        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String sourceSnapshotDigest = digest(sourceSnapshot.toString());
        String desiredDigest = desiredDigest(request.plans());
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(
                schemaVersion(), request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                sourceSnapshotDigest, desiredDigest, status);
        return new DraftPreview(
                schemaVersion(), status, request.factoryId(), List.of("processes"),
                request.plans(), sourceSnapshotDigest, desiredDigest, previewDigest,
                issues, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<EquipmentCalibrationPlanDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同请求", "EQUIPMENT_PLAN_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "设备计划预览被阻断", "EQUIPMENT_PLAN_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "预览摘要已变化", "EQUIPMENT_PLAN_PREVIEW_DIGEST_CONFLICT");
        }

        EquipmentCalibrationPlanDraft parent = EquipmentCalibrationPlanDraft.builder()
                .id(UUID.randomUUID().toString()).factoryId(request.factoryId())
                .blueprintId(request.blueprintId()).blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest()).sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest()).previewDigest(preview.previewDigest())
                .requestDigest(requestDigest).idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id()).planCount(request.plans().size())
                .processRefCount(request.plans().stream().mapToInt(p -> p.processRefs().size()).sum())
                .dependencyBasis(EquipmentCalibrationPlanDraftModels.DEPENDENCY_BASIS)
                .dependencyClosureComplete(true).status("DRAFT").build();
        EquipmentCalibrationPlanDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "设备计划幂等键发生并发竞争", "EQUIPMENT_PLAN_IDEMPOTENCY_RACE");
        }

        List<EquipmentCalibrationPlanDraftEntry> entryRows = new ArrayList<>();
        List<EquipmentCalibrationPlanDraftProcessRef> refRows = new ArrayList<>();
        int refOrdinal = 0;
        for (int planIndex = 0; planIndex < request.plans().size(); planIndex++) {
            PlanEntry plan = request.plans().get(planIndex);
            entryRows.add(toEntry(saved.getId(), request.factoryId(), planIndex, plan));
            for (ProcessRef processRef : plan.processRefs()) {
                refRows.add(toProcessRef(saved.getId(), request.factoryId(),
                        refOrdinal++, planIndex, processRef));
            }
        }
        entries.saveAllAndFlush(entryRows);
        refs.saveAllAndFlush(refRows);
        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!preview.desiredDigest().equals(desiredDigest(readback.plans()))) {
            throw invalid(409, "设备计划回读摘要不一致", "EQUIPMENT_PLAN_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 1 + entryRows.size() + refRows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        EquipmentCalibrationPlanDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "设备计划不存在", "EQUIPMENT_PLAN_NOT_FOUND"));
        if (!Set.of("DRAFT", "PLAN").contains(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !EquipmentCalibrationPlanDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())) {
            throw invalid(409, "设备计划依赖闭包或状态无效",
                    "EQUIPMENT_PLAN_READBACK_INVARIANT_BROKEN");
        }
        List<EquipmentCalibrationPlanDraftProcessRef> allRefs = refs
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<PlanEntry> plans = new ArrayList<>();
        for (EquipmentCalibrationPlanDraftEntry entry : entries
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId)) {
            List<ProcessRef> processRefs = allRefs.stream()
                    .filter(ref -> ref.getEquipmentEntryOrdinal().equals(entry.getOrdinal()))
                    .map(ref -> new ProcessRef(
                            ref.getProcessId(), ref.getProcessDraftId(), ref.getProcessSnapshotDigest()))
                    .toList();
            plans.add(fromEntry(entry, processRefs));
        }
        if (plans.size() != parent.getPlanCount()
                || allRefs.size() != parent.getProcessRefCount()
                || allRefs.size() < plans.size()
                || !desiredDigest(plans).equals(parent.getDesiredDigest())) {
            throw invalid(409, "设备计划回读不完整", "EQUIPMENT_PLAN_READBACK_MISMATCH");
        }
        return new DraftReadback(schemaVersion(), factoryId, draftId, parent.getStatus(), plans,
                0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        EquipmentCalibrationPlanDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "设备计划不存在", "EQUIPMENT_PLAN_NOT_FOUND"));
        if (!Set.of("DRAFT", "PLAN").contains(parent.getStatus())) {
            throw invalid(409, "只有未发布计划可恢复", "EQUIPMENT_PLAN_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(schemaVersion(), factoryId, draftId, parent.getDesiredDigest(),
                "ARCHIVE_DRAFT_AFTER_EQUIPMENT_OWNER_CONFIRMATION",
                "只归档未发布计划；不改变设备、维护历史、IoT、告警或工序",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "EQUIPMENT_PLAN_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少设备计划草稿权限", "EQUIPMENT_PLAN_PERMISSION_REQUIRED");
        }
        if (operator.actor().type() == com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER
                && users.findByIdAndFactoryId(operator.actor().id(), operator.factoryId())
                .filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员",
                    "EQUIPMENT_PLAN_OPERATOR_MEMBERSHIP_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.plans().isEmpty() || request.plans().size() > MAX_PLANS) {
            throw invalid(400, "Blueprint envelope 或计划数量无效", "EQUIPMENT_PLAN_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "EQUIPMENT_PLAN_OPERATOR_REQUIRED");
        }
    }

    private void validateEntry(PlanEntry plan, String target, List<DraftIssue> issues) {
        if (plan.planType() == null || plan.frequencyKind() == null || plan.duePolicy() == null
                || plan.frequencyValue() == null || plan.frequencyValue() <= 0
                || plan.frequencyValue() > MAX_FREQUENCY_VALUE) {
            issues.add(issue("EQUIPMENT_PLAN_SCHEDULE_INVALID", target,
                    "计划类型、频率和策略必须有效", "补充受支持的计划类型和频率"));
        }
        if (isBlankOrTooLong(plan.method(), 500) || isBlankOrTooLong(plan.responsibleRole(), 100)
                || isBlankOrTooLong(plan.requiredEvidence(), 500)
                || isBlankOrTooLong(plan.verificationMethod(), 500)
                || isBlankOrTooLong(plan.changeReason(), 500)) {
            issues.add(issue("EQUIPMENT_PLAN_METHOD_REQUIRED", target,
                    "方法、负责人、证据、验证方式和变更原因均必填", "补全计划字段"));
        }
        if (plan.estimatedDurationMinutes() != null
                && (plan.estimatedDurationMinutes() <= 0
                || plan.estimatedDurationMinutes() > MAX_DURATION_MINUTES)) {
            issues.add(issue("EQUIPMENT_PLAN_DURATION_INVALID", target,
                    "预计时长必须在 1 到 10080 分钟之间", "修正预计时长"));
        }
        if (plan.planType() == PlanType.CALIBRATION
                && (plan.toleranceValue() == null || plan.toleranceValue().signum() <= 0
                || plan.toleranceValue().precision() > 18 || plan.toleranceValue().scale() > 6
                || isBlankOrTooLong(plan.toleranceUnit(), 20))) {
            issues.add(issue("EQUIPMENT_PLAN_CALIBRATION_TOLERANCE_REQUIRED", target,
                    "校准计划必须有正容差和单位", "补充校准容差"));
        }
        if (plan.planType() == PlanType.MAINTENANCE
                && (plan.toleranceValue() != null || !plan.toleranceUnit().isBlank())) {
            issues.add(issue("EQUIPMENT_PLAN_MAINTENANCE_TOLERANCE_FORBIDDEN", target,
                    "维护计划不能携带校准容差", "删除容差字段或改为校准计划"));
        }
        if (plan.processRefs().isEmpty() || plan.processRefs().size() > MAX_PROCESS_REFS_PER_PLAN) {
            issues.add(issue("EQUIPMENT_PLAN_PROCESS_REF_REQUIRED", target + ".processRefs",
                    "每项计划必须引用 1 到 50 个工序", "补充受影响的正式工序或 A3 工序草稿"));
        }
    }

    private void validateProcessRefs(DraftRequest request, PlanEntry plan, String target,
            List<DraftIssue> issues, StringBuilder sourceSnapshot) {
        Set<String> seenRefs = new HashSet<>();
        for (int refIndex = 0; refIndex < plan.processRefs().size(); refIndex++) {
            ProcessRef processRef = plan.processRefs().get(refIndex);
            String refTarget = target + ".processRefs[" + refIndex + "]";
            boolean hasLive = !processRef.processId().isBlank();
            boolean hasDraft = !processRef.processDraftId().isBlank();
            if (hasLive == hasDraft) {
                issues.add(issue(hasLive ? "EQUIPMENT_PLAN_PROCESS_REF_AMBIGUOUS"
                                : "EQUIPMENT_PLAN_PROCESS_REF_REQUIRED",
                        refTarget, "工序引用必须且只能指向正式工序或 A3 草稿",
                        "只保留 processId 或 processDraftId 中的一项"));
                sourceSnapshot.append("invalid-process-ref|");
                continue;
            }
            String refIdentity = hasLive ? "live:" + processRef.processId()
                    : "draft:" + processRef.processDraftId();
            if (!seenRefs.add(refIdentity)) {
                issues.add(issue("EQUIPMENT_PLAN_PROCESS_REF_DUPLICATE", refTarget,
                        "同一计划不能重复引用工序", "删除重复工序引用"));
            }
            if (!isDigest(processRef.processSnapshotDigest())) {
                issues.add(issue("EQUIPMENT_PLAN_PROCESS_SNAPSHOT_REQUIRED", refTarget,
                        "工序摘要无效", "重新读取工序并生成摘要"));
            }
            if (hasLive) {
                WorkProcess current = processes
                        .findByFactoryIdAndId(request.factoryId(), processRef.processId()).orElse(null);
                if (current == null) {
                    issues.add(issue("EQUIPMENT_PLAN_PROCESS_NOT_FOUND", refTarget,
                            "工序不属于当前工厂或不存在", "重新读取工序"));
                    sourceSnapshot.append("missing-live-process:")
                            .append(processRef.processId()).append('|');
                    continue;
                }
                String currentDigest = processDigest(current);
                sourceSnapshot.append("live-process:").append(currentDigest).append('|');
                if (!currentDigest.equals(processRef.processSnapshotDigest())) {
                    issues.add(issue("EQUIPMENT_PLAN_PROCESS_SNAPSHOT_CONFLICT", refTarget,
                            "工序快照已变化", "重新生成计划预览"));
                }
                if (!Boolean.TRUE.equals(current.getIsActive()) || current.getMergedIntoId() != null) {
                    issues.add(issue("EQUIPMENT_PLAN_PROCESS_UNAVAILABLE", refTarget,
                            "停用或已合并工序不能用于新计划", "改选当前有效工序"));
                }
                continue;
            }

            WorkProcessDraft current = processDrafts
                    .findByIdAndFactoryId(processRef.processDraftId(), request.factoryId()).orElse(null);
            if (current == null) {
                issues.add(issue("EQUIPMENT_PLAN_PROCESS_DRAFT_NOT_FOUND", refTarget,
                        "工序草稿不属于当前工厂或不存在", "重新读取 A3 工序草稿"));
                sourceSnapshot.append("missing-draft-process:")
                        .append(processRef.processDraftId()).append('|');
                continue;
            }
            sourceSnapshot.append("draft-process:").append(current.getDesiredDigest()).append('|');
            if (!"DRAFT".equals(current.getStatus())
                    || !Objects.equals(current.getBlueprintId(), request.blueprintId())
                    || !Objects.equals(current.getBlueprintVersion(), request.blueprintVersion())
                    || !Objects.equals(current.getSourceDigest(), request.sourceDigest())) {
                issues.add(issue("EQUIPMENT_PLAN_PROCESS_DRAFT_LINEAGE_CONFLICT", refTarget,
                        "A3 工序草稿不属于同一 Blueprint lineage",
                        "重新选择同一蓝图版本的工序草稿"));
            } else if (!Objects.equals(current.getDesiredDigest(), processRef.processSnapshotDigest())) {
                issues.add(issue("EQUIPMENT_PLAN_PROCESS_DRAFT_SNAPSHOT_CONFLICT", refTarget,
                        "工序草稿摘要已变化", "重新生成计划预览"));
            }
        }
    }

    private EquipmentCalibrationPlanDraftEntry toEntry(
            String draftId, String factoryId, int ordinal, PlanEntry plan) {
        return EquipmentCalibrationPlanDraftEntry.builder()
                .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factoryId).ordinal(ordinal)
                .equipmentId(plan.equipmentId()).equipmentSnapshotDigest(plan.equipmentSnapshotDigest())
                .planType(plan.planType().name()).frequencyKind(plan.frequencyKind().name())
                .frequencyValue(plan.frequencyValue()).duePolicy(plan.duePolicy().name())
                .method(plan.method()).toleranceValue(plan.toleranceValue())
                .toleranceUnit(blankToNull(plan.toleranceUnit()))
                .responsibleRole(plan.responsibleRole()).requiredEvidence(plan.requiredEvidence())
                .verificationMethod(plan.verificationMethod())
                .estimatedDurationMinutes(plan.estimatedDurationMinutes())
                .changeReason(plan.changeReason()).build();
    }

    private EquipmentCalibrationPlanDraftProcessRef toProcessRef(String draftId, String factoryId,
            int ordinal, int entryOrdinal, ProcessRef processRef) {
        return EquipmentCalibrationPlanDraftProcessRef.builder()
                .id(UUID.randomUUID().toString()).draftId(draftId).factoryId(factoryId)
                .ordinal(ordinal).equipmentEntryOrdinal(entryOrdinal)
                .processId(blankToNull(processRef.processId()))
                .processDraftId(blankToNull(processRef.processDraftId()))
                .processSnapshotDigest(processRef.processSnapshotDigest()).build();
    }

    private PlanEntry fromEntry(EquipmentCalibrationPlanDraftEntry entry, List<ProcessRef> processRefs) {
        return new PlanEntry(entry.getEquipmentId(), entry.getEquipmentSnapshotDigest(),
                PlanType.valueOf(entry.getPlanType()),
                FrequencyKind.valueOf(entry.getFrequencyKind()), entry.getFrequencyValue(),
                DuePolicy.valueOf(entry.getDuePolicy()), entry.getMethod(), entry.getToleranceValue(),
                entry.getToleranceUnit(), entry.getResponsibleRole(), entry.getRequiredEvidence(),
                entry.getVerificationMethod(), entry.getEstimatedDurationMinutes(),
                entry.getChangeReason(), processRefs);
    }

    private DraftReceipt receipt(EquipmentCalibrationPlanDraft parent, boolean replayed,
            int businessWriteCount, int persistedRows) {
        return new DraftReceipt(schemaVersion(), parent.getId(), parent.getFactoryId(), parent.getId(),
                parent.getStatus(), parent.getSourceSnapshotDigest(), parent.getDesiredDigest(),
                parent.getPreviewDigest(), parent.getRequestDigest(), parent.getPlanCount(),
                parent.getProcessRefCount(), Objects.requireNonNullElse(parent.getLockVersion(), 0L),
                replayed, businessWriteCount, replayed ? 0 : 1, persistedRows,
                0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED",
                "ARCHIVE_DRAFT_AFTER_EQUIPMENT_OWNER_CONFIRMATION");
    }

    private String latestMaintenanceDigest(Long equipmentId) {
        List<EquipmentMaintenance> rows = maintenance
                .findLatestByEquipmentId(equipmentId, PageRequest.of(0, 1));
        if (rows == null || rows.isEmpty()) return digest("NONE");
        EquipmentMaintenance row = rows.get(0);
        return digest(String.valueOf(row.getId()), String.valueOf(row.getEquipmentId()),
                row.getMaintenanceType(), String.valueOf(row.getMaintenanceDate()),
                String.valueOf(row.getStartTime()), String.valueOf(row.getEndTime()),
                row.getDescription(), row.getPerformedBy(), String.valueOf(row.getNextMaintenanceDate()),
                row.getNotes(), String.valueOf(row.getVersion()), String.valueOf(row.getUpdatedAt()));
    }

    private String equipmentDigest(FactoryEquipment row) {
        return digest(String.valueOf(row.getId()), row.getFactoryId(), row.getEquipmentCode(),
                row.getEquipmentName(), row.getType(), row.getModel(), row.getManufacturer(),
                String.valueOf(row.getStatus()), row.getLocation(),
                String.valueOf(row.getMaintenanceIntervalHours()),
                String.valueOf(row.getLastMaintenanceDate()), String.valueOf(row.getNextMaintenanceDate()),
                String.valueOf(row.getTotalRunningHours()), String.valueOf(row.getVersion()),
                String.valueOf(row.getUpdatedAt()));
    }

    private String processDigest(WorkProcess row) {
        return digest(row.getId(), row.getFactoryId(), row.getProcessName(), row.getProcessCategory(),
                String.valueOf(row.getIsActive()), row.getMergedIntoId(),
                String.valueOf(row.getLockVersion()), String.valueOf(row.getUpdatedAt()));
    }

    private String desiredDigest(List<PlanEntry> plans) {
        List<String> values = new ArrayList<>();
        values.add(schemaVersion());
        for (PlanEntry plan : plans) {
            values.add(String.valueOf(plan.equipmentId())); values.add(plan.equipmentSnapshotDigest());
            values.add(String.valueOf(plan.planType())); values.add(String.valueOf(plan.frequencyKind()));
            values.add(String.valueOf(plan.frequencyValue())); values.add(String.valueOf(plan.duePolicy()));
            values.add(plan.method());
            values.add(plan.toleranceValue() == null ? ""
                    : plan.toleranceValue().stripTrailingZeros().toPlainString());
            values.add(plan.toleranceUnit()); values.add(plan.responsibleRole());
            values.add(plan.requiredEvidence()); values.add(plan.verificationMethod());
            values.add(String.valueOf(plan.estimatedDurationMinutes())); values.add(plan.changeReason());
            for (ProcessRef ref : plan.processRefs()) {
                values.add(ref.processId()); values.add(ref.processDraftId());
                values.add(ref.processSnapshotDigest());
            }
        }
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), desiredDigest(request.plans()));
    }

    private String planIdentity(PlanEntry plan) {
        List<String> identities = plan.processRefs().stream()
                .map(ref -> ref.processId().isBlank()
                        ? "draft:" + ref.processDraftId() : "live:" + ref.processId())
                .sorted().toList();
        return plan.equipmentId() + "|" + plan.planType() + "|" + String.join(",", identities);
    }

    private boolean isUnavailable(String status) {
        String normalized = status == null ? "" : status.toLowerCase(Locale.ROOT);
        return Set.of("scrapped", "offline", "retired").contains(normalized);
    }
    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }
    private boolean isDigest(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    private String blankToNull(String value) { return value == null || value.isBlank() ? null : value; }
    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_equipment_plan_draft_idempotency")) return true;
        }
        return false;
    }
    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24)); md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8)); md.update((byte) bytes.length); md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception exception) { throw new IllegalStateException(exception); }
    }
    private DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }
    private String schemaVersion() { return EquipmentCalibrationPlanDraftModels.SCHEMA_VERSION; }
    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }
}
