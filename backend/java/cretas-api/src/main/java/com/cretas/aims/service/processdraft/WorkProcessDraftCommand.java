package com.cretas.aims.service.processdraft;

import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.WorkProcessOutputMaterialKind;
import com.cretas.aims.entity.workprocess.WorkProcessDraft;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessDraftRepository;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.DraftIssue;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.DraftPreview;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.DraftReceipt;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.DraftRequest;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.ProcessSnapshot;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.RecoveryPlan;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.RequestedProcess;
import com.cretas.aims.service.processdraft.WorkProcessDraftModels.ResolvedProcess;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Module-owned Factory Blueprint WorkProcess DRAFT command.
 *
 * <p>Only {@link WorkProcessDraft} is written. The live process catalog is read to resolve
 * canonical identities and categories; this command never saves a {@link WorkProcess}, route,
 * Workflow, BOM, production task or execution record.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class WorkProcessDraftCommand {

    public static final String CAPABILITY_ID = "processes";
    public static final String REQUIRED_PERMISSION = "process:draft:write";

    private static final BigDecimal MIN_YIELD = new BigDecimal("0.0001");
    private static final BigDecimal MAX_YIELD = new BigDecimal("99.9999");

    private final WorkProcessDraftRepository draftRepository;
    private final WorkProcessRepository workProcessRepository;

    public WorkProcessDraftCommand(
            WorkProcessDraftRepository draftRepository,
            WorkProcessRepository workProcessRepository) {
        this.draftRepository = Objects.requireNonNull(draftRepository, "draftRepository");
        this.workProcessRepository = Objects.requireNonNull(workProcessRepository, "workProcessRepository");
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        List<DraftIssue> issues = new ArrayList<>();
        List<WorkProcess> catalog = workProcessRepository.findByFactoryId(request.factoryId());
        ResolvedProcess desired = resolveDesired(request.targetWorkProcessId(), request.desired(), catalog, issues);
        WorkProcess current = resolveCurrent(request, desired, catalog, issues);
        ProcessSnapshot snapshot = snapshot(current);
        String sourceSnapshotDigest = digest(
                snapshot == null ? "ABSENT" : "PRESENT",
                snapshot == null ? "" : snapshot.workProcessId(),
                snapshot == null ? "" : snapshot.name(),
                snapshot == null ? "" : snapshot.category(),
                snapshot == null ? "" : snapshot.description(),
                snapshot == null ? "" : integer(snapshot.estimatedMinutes()),
                snapshot == null ? "" : String.valueOf(snapshot.needsInput()),
                snapshot == null ? "" : snapshot.defaultOutputMaterialKind(),
                snapshot == null ? "" : decimal(snapshot.standardYieldMin()),
                snapshot == null ? "" : decimal(snapshot.standardYieldMax()),
                snapshot == null ? "" : String.valueOf(snapshot.active()),
                snapshot == null ? "" : snapshot.mergedIntoId(),
                snapshot == null ? "" : String.valueOf(snapshot.lockVersion()),
                snapshot == null ? "" : snapshot.updatedAt());
        String desiredDigest = desiredDigest(desired);
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest(WorkProcessDraftModels.SCHEMA_VERSION,
                request.factoryId(), request.blueprintId(), String.valueOf(request.blueprintVersion()),
                request.sourceDigest(), request.targetWorkProcessId(), sourceSnapshotDigest,
                desiredDigest, status, issueDigest(issues));
        return new DraftPreview(WorkProcessDraftModels.SCHEMA_VERSION, status, request.factoryId(),
                snapshot, desired, sourceSnapshotDigest, desiredDigest, previewDigest, issues,
                0, "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, com.cretas.aims.service.processdraft.WorkProcessDraftModels.AuthenticatedOperator operator) {
        if (operator == null
                || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN)
                || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "建厂草稿操作人身份与工厂范围不匹配", "DRAFT_OPERATOR_SCOPE_MISMATCH",
                    "使用当前工厂的有效工厂用户身份重试", "operatorId");
        }
        return materialize(request, operator.permissions(), operator.actor());
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, Set<String> operatorPermissions) {
        return materialize(request, operatorPermissions, new com.cretas.aims.security.ActorRef(
                com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER, request.operatorId()));
    }

    private DraftReceipt materialize(DraftRequest request, Set<String> operatorPermissions,
            com.cretas.aims.security.ActorRef actor) {
        validateEnvelope(request, true);
        if (operatorPermissions == null || !operatorPermissions.contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少工序草稿权限", "PROCESS_DRAFT_PERMISSION_REQUIRED",
                    "由工序主数据负责人授予 process:draft:write 后重试", "permission");
        }
        String requestDigest = requestDigest(request, actor);
        Optional<WorkProcessDraft> prior = draftRepository.findByFactoryIdAndIdempotencyKey(
                request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) return replay(prior.get(), requestDigest);

        DraftPreview preview = preview(request);
        if (!"READY".equals(preview.status())) {
            throw invalid(409, "工序草稿预览仍有阻断项", "PROCESS_DRAFT_PREVIEW_BLOCKED",
                    preview.issues().isEmpty() ? "重新生成预览" : preview.issues().get(0).recovery(),
                    preview.issues().isEmpty() ? "request" : preview.issues().get(0).target());
        }
        if (!request.confirmedPreviewDigest().equals(preview.previewDigest())) {
            throw invalid(409, "工序草稿预览摘要已变化", "PROCESS_DRAFT_PREVIEW_DIGEST_CONFLICT",
                    "刷新当前工序和类别目录后重新查看并确认 ChangeSet", "confirmedPreviewDigest");
        }

        ResolvedProcess desired = preview.desired();
        WorkProcessDraft row = WorkProcessDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .targetWorkProcessId(preview.current() == null ? null : preview.current().workProcessId())
                .naturalKey(desired.naturalKey())
                .desiredProcessName(desired.name())
                .desiredProcessCategory(desired.category())
                .desiredCategoryResolution(desired.categoryResolution())
                .desiredCreateCategory(desired.createCategory())
                .desiredDescription(desired.description())
                .desiredEstimatedMinutes(desired.estimatedMinutes())
                .desiredNeedsInput(desired.needsInput())
                .desiredDefaultOutputMaterialKind(desired.defaultOutputMaterialKind().name())
                .desiredStandardYieldMin(desired.standardYieldMin())
                .desiredStandardYieldMax(desired.standardYieldMax())
                .sourceSnapshotDigest(preview.sourceSnapshotDigest())
                .desiredDigest(preview.desiredDigest())
                .previewDigest(preview.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(actor.factoryUserId()).actorType(actor.type()).actorId(actor.id())
                .status("DRAFT")
                .build();
        WorkProcessDraft saved;
        try {
            saved = draftRepository.saveAndFlush(row);
        } catch (DataIntegrityViolationException race) {
            if (!isScopedIdempotencyRace(race)) throw race;
            throw invalid(409, "工序草稿幂等键发生并发竞争", "PROCESS_DRAFT_IDEMPOTENCY_RACE",
                    "使用相同幂等键重试以读取胜出回执", "idempotencyKey");
        }
        requireExactReadback(saved, preview);
        return receipt(saved, false, 1);
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId) {
        WorkProcessDraft row = draftRepository.findByIdAndFactoryId(clean(draftId), clean(factoryId))
                .orElseThrow(() -> invalid(404, "当前工厂没有该工序草稿", "PROCESS_DRAFT_NOT_FOUND",
                        "刷新当前工厂的工序草稿回执", "draftId"));
        return new RecoveryPlan(WorkProcessDraftModels.SCHEMA_VERSION, row.getFactoryId(), row.getId(),
                row.getDesiredDigest(), "SOFT_DELETE_DRAFT_AFTER_MODULE_OWNER_CONFIRMATION",
                "只移除未发布工序草稿；不会修改 work_processes、路线、Workflow、BOM、计划或报工",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private ResolvedProcess resolveDesired(
            String targetWorkProcessId,
            RequestedProcess requested,
            List<WorkProcess> catalog,
            List<DraftIssue> issues) {
        if (requested == null) {
            issues.add(issue("PROCESS_DRAFT_DESIRED_REQUIRED", "desired",
                    "工序草稿目标不能为空", "补齐名称、类别、投入要求和默认产出类型"));
            return emptyResolved(targetWorkProcessId);
        }
        String name = canonicalText(requested.name());
        if (name.isBlank()) issues.add(required("name", "工序名称"));
        if (name.length() > 100) {
            issues.add(issue("PROCESS_DRAFT_NAME_TOO_LONG", "name",
                    "工序名称不能超过 100 字符", "缩短工序业务名称"));
        }
        String description = requested.description();
        if (description != null && description.length() > 500) {
            issues.add(issue("PROCESS_DRAFT_DESCRIPTION_TOO_LONG", "description",
                    "工序说明不能超过 500 字符", "精简说明，算法与表单规则留给专用模块"));
        }

        ResolvedCategory category = resolveCategory(requested.category(), requested.createCategory(), catalog, issues);
        Integer estimatedMinutes = requested.estimatedMinutes();
        if (estimatedMinutes != null && (estimatedMinutes < 1 || estimatedMinutes > 525600)) {
            issues.add(issue("PROCESS_DRAFT_ESTIMATED_MINUTES_INVALID", "estimatedMinutes",
                    "预计时长必须在 1 到 525600 分钟之间", "填写经工厂确认的正整数，未知时留空"));
        }
        if (requested.needsInput() == null) {
            issues.add(required("needsInput", "是否需要投入"));
        }
        if (requested.defaultOutputMaterialKind() == null) {
            issues.add(required("defaultOutputMaterialKind", "默认产出类型"));
        }
        validateYield(requested.standardYieldMin(), requested.standardYieldMax(), issues);

        String naturalKey = clean(targetWorkProcessId).isBlank()
                ? "NEW:" + normalizeIdentity(category.value()) + ":" + normalizeIdentity(name)
                : "EXISTING:" + clean(targetWorkProcessId);
        return new ResolvedProcess(naturalKey, name, category.value(), category.resolution(),
                category.createCategory(), description, estimatedMinutes, requested.needsInput(),
                requested.defaultOutputMaterialKind(), canonicalDecimal(requested.standardYieldMin()),
                canonicalDecimal(requested.standardYieldMax()));
    }

    private ResolvedCategory resolveCategory(
            String requestedCategory,
            boolean createCategory,
            List<WorkProcess> catalog,
            List<DraftIssue> issues) {
        String category = canonicalText(requestedCategory);
        if (category.isBlank()) {
            issues.add(required("category", "工序类别"));
            return new ResolvedCategory("", "EXISTING", false);
        }
        if (category.length() > 50) {
            issues.add(issue("PROCESS_DRAFT_CATEGORY_TOO_LONG", "category",
                    "工序类别不能超过 50 字符", "缩短类别名称"));
        }
        Optional<String> existing = catalog.stream().map(WorkProcess::getProcessCategory)
                .map(WorkProcessDraftCommand::canonicalText).filter(value -> !value.isBlank())
                .filter(value -> normalizeIdentity(value).equals(normalizeIdentity(category))).findFirst();
        if (existing.isPresent()) {
            if (createCategory) {
                issues.add(issue("PROCESS_DRAFT_CATEGORY_ALREADY_EXISTS", "createCategory",
                        "该类别已存在，不能再次声明新建", "选择现有类别并关闭新建类别确认"));
            }
            return new ResolvedCategory(existing.get(), "EXISTING", false);
        }
        if (!createCategory) {
            issues.add(issue("PROCESS_DRAFT_CATEGORY_NOT_AVAILABLE", "category",
                    "该类别不在当前工厂目录中", "从当前目录选择，或由用户明确确认创建新类别草稿"));
            return new ResolvedCategory(category, "EXISTING", false);
        }
        return new ResolvedCategory(category, "PROPOSED", true);
    }

    private WorkProcess resolveCurrent(
            DraftRequest request,
            ResolvedProcess desired,
            List<WorkProcess> catalog,
            List<DraftIssue> issues) {
        if (request.targetWorkProcessId().isBlank()) {
            if (!desired.name().isBlank() && catalog.stream().anyMatch(item ->
                    normalizeIdentity(item.getProcessName()).equals(normalizeIdentity(desired.name())))) {
                issues.add(issue("PROCESS_DRAFT_TARGET_REQUIRED", "targetWorkProcessId",
                        "当前工厂已有同名工序，不能把更新伪装成新建", "选择现有规范工序后重新预览"));
            }
            return null;
        }
        Optional<WorkProcess> found = workProcessRepository.findByFactoryIdAndId(
                request.factoryId(), request.targetWorkProcessId());
        if (found.isEmpty()) {
            issues.add(issue("PROCESS_DRAFT_TARGET_NOT_FOUND", "targetWorkProcessId",
                    "当前工厂没有可更新的目标工序", "刷新当前工厂工序目录并重新选择"));
            return null;
        }
        WorkProcess current = found.get();
        if (!current.isSelectableForNew()) {
            issues.add(issue("PROCESS_DRAFT_TARGET_NOT_CANONICAL", "targetWorkProcessId",
                    "目标工序已停用或已归并，不能作为新的规范工序草稿目标", "选择当前可用的规范工序"));
        }
        boolean conflict = catalog.stream().filter(item -> !Objects.equals(item.getId(), current.getId()))
                .anyMatch(item -> normalizeIdentity(item.getProcessName()).equals(normalizeIdentity(desired.name())));
        if (!desired.name().isBlank() && conflict) {
            issues.add(issue("PROCESS_DRAFT_NAME_CONFLICT", "name",
                    "当前工厂已有其他同名工序", "使用经业务确认的不同名称或选择该规范工序"));
        }
        return current;
    }

    private void validateYield(BigDecimal min, BigDecimal max, List<DraftIssue> issues) {
        if (outsideYield(min) || outsideYield(max)) {
            issues.add(issue("PROCESS_DRAFT_YIELD_OUT_OF_RANGE", "standardYield",
                    "产出率建议必须在 0.0001 到 99.9999 之间", "修正为经业务确认的比例，未知时留空"));
        }
        if (min != null && max != null && min.compareTo(max) > 0) {
            issues.add(issue("PROCESS_DRAFT_YIELD_RANGE_INVALID", "standardYield",
                    "产出率下限不能大于上限", "调整上下限后重新确认"));
        }
    }

    private boolean outsideYield(BigDecimal value) {
        return value != null && (value.compareTo(MIN_YIELD) < 0 || value.compareTo(MAX_YIELD) > 0);
    }

    private ResolvedProcess emptyResolved(String targetWorkProcessId) {
        return new ResolvedProcess(clean(targetWorkProcessId).isBlank()
                ? "NEW::" : "EXISTING:" + clean(targetWorkProcessId), "", "", "EXISTING",
                false, null, null, null, null, null, null);
    }

    private ProcessSnapshot snapshot(WorkProcess value) {
        if (value == null) return null;
        LocalDateTime updatedAt = value.getUpdatedAt();
        return new ProcessSnapshot(value.getId(), clean(value.getProcessName()), clean(value.getProcessCategory()),
                value.getDescription(), value.getEstimatedMinutes(), Boolean.TRUE.equals(value.getNeedsInput()),
                value.getDefaultOutputMaterialKind() == null ? "" : value.getDefaultOutputMaterialKind().name(),
                canonicalDecimal(value.getStandardYieldMin()), canonicalDecimal(value.getStandardYieldMax()),
                Boolean.TRUE.equals(value.getIsActive()), clean(value.getMergedIntoId()),
                Objects.requireNonNullElse(value.getLockVersion(), 0L), updatedAt == null ? "" : updatedAt.toString());
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null) throw invalid(400, "工序草稿请求不能为空", "PROCESS_DRAFT_REQUEST_REQUIRED",
                "从已确认的 processes ChangeSet 生成请求", "request");
        if (request.factoryId().isBlank() || request.blueprintId().isBlank() || request.blueprintVersion() <= 0) {
            throw invalid(400, "工厂与 Blueprint 版本不能为空", "PROCESS_DRAFT_SCOPE_REQUIRED",
                    "锁定 factoryId、blueprintId 和 blueprintVersion", "blueprint");
        }
        if (!request.sourceDigest().matches("[0-9a-f]{64}")) {
            throw invalid(400, "Blueprint sourceDigest 必须是 SHA-256", "PROCESS_DRAFT_SOURCE_DIGEST_INVALID",
                    "使用服务器编译工件的 sourceDigest", "sourceDigest");
        }
        if (materialize) {
            if (request.operatorId() <= 0) throw invalid(400, "工序草稿必须记录操作人",
                    "PROCESS_DRAFT_OPERATOR_REQUIRED", "使用已认证模块负责人身份", "operatorId");
            if (request.idempotencyKey().isBlank() || request.idempotencyKey().length() > 128) {
                throw invalid(400, "工序草稿幂等键不能为空且不能超过 128 字符",
                        "PROCESS_DRAFT_IDEMPOTENCY_KEY_INVALID", "为本次确认生成稳定幂等键", "idempotencyKey");
            }
            if (!request.confirmedPreviewDigest().matches("[0-9a-f]{64}")) {
                throw invalid(400, "必须确认服务器生成的 previewDigest",
                        "PROCESS_DRAFT_CONFIRMED_PREVIEW_REQUIRED", "重新预览并确认当前摘要",
                        "confirmedPreviewDigest");
            }
        }
    }

    private DraftReceipt replay(WorkProcessDraft row, String requestDigest) {
        if (!row.getRequestDigest().equals(requestDigest)) {
            throw invalid(409, "该幂等键已绑定另一份工序草稿载荷",
                    "PROCESS_DRAFT_IDEMPOTENCY_KEY_CONFLICT", "复用原请求或生成新幂等键", "idempotencyKey");
        }
        return receipt(row, true, 0);
    }

    private DraftReceipt receipt(WorkProcessDraft row, boolean replayed, int writes) {
        return new DraftReceipt(WorkProcessDraftModels.SCHEMA_VERSION, row.getId(), row.getFactoryId(),
                row.getId(), row.getStatus(), row.getNaturalKey(), clean(row.getTargetWorkProcessId()),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                Objects.requireNonNullElse(row.getLockVersion(), 0L), replayed, writes, 1,
                "NOT_PUBLISHED", "NOT_ACTIVATED",
                "由工序模块负责人确认后软删除该 DRAFT；正式工序、路线、Workflow、BOM、计划与报工不受影响");
    }

    private void requireExactReadback(WorkProcessDraft row, DraftPreview preview) {
        ResolvedProcess desired = preview.desired();
        String expectedTargetId = preview.current() == null ? null : preview.current().workProcessId();
        if (row == null || !"DRAFT".equals(row.getStatus())
                || !preview.factoryId().equals(row.getFactoryId())
                || !Objects.equals(expectedTargetId, row.getTargetWorkProcessId())
                || !desired.naturalKey().equals(row.getNaturalKey())
                || !desired.name().equals(row.getDesiredProcessName())
                || !desired.category().equals(row.getDesiredProcessCategory())
                || !desired.categoryResolution().equals(row.getDesiredCategoryResolution())
                || desired.createCategory() != Boolean.TRUE.equals(row.getDesiredCreateCategory())
                || !Objects.equals(desired.description(), row.getDesiredDescription())
                || !Objects.equals(desired.estimatedMinutes(), row.getDesiredEstimatedMinutes())
                || !Objects.equals(desired.needsInput(), row.getDesiredNeedsInput())
                || desired.defaultOutputMaterialKind() == null
                || !desired.defaultOutputMaterialKind().name().equals(row.getDesiredDefaultOutputMaterialKind())
                || !sameDecimal(desired.standardYieldMin(), row.getDesiredStandardYieldMin())
                || !sameDecimal(desired.standardYieldMax(), row.getDesiredStandardYieldMax())
                || !preview.sourceSnapshotDigest().equals(row.getSourceSnapshotDigest())
                || !preview.desiredDigest().equals(row.getDesiredDigest())
                || !preview.previewDigest().equals(row.getPreviewDigest())) {
            throw invalid(409, "工序草稿回读与已确认 ChangeSet 不一致",
                    "PROCESS_DRAFT_READBACK_MISMATCH", "保留事务回滚并重新预览", "draft");
        }
    }

    private String requestDigest(DraftRequest request, com.cretas.aims.security.ActorRef actor) {
        RequestedProcess desired = request.desired();
        return digest(WorkProcessDraftModels.SCHEMA_VERSION, request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(), request.targetWorkProcessId(),
                desired == null ? "" : desired.name(),
                desired == null ? "" : desired.category(),
                desired == null ? "" : String.valueOf(desired.createCategory()),
                desired == null ? "" : desired.description(),
                desired == null ? "" : integer(desired.estimatedMinutes()),
                desired == null ? "" : String.valueOf(desired.needsInput()),
                desired == null || desired.defaultOutputMaterialKind() == null
                        ? "" : desired.defaultOutputMaterialKind().name(),
                desired == null ? "" : decimal(desired.standardYieldMin()),
                desired == null ? "" : decimal(desired.standardYieldMax()),
                request.confirmedPreviewDigest(), request.idempotencyKey(), actor.type().name() + ":" + String.valueOf(actor.id()));
    }

    private String desiredDigest(ResolvedProcess desired) {
        return digest(desired.naturalKey(), desired.name(), desired.category(), desired.categoryResolution(),
                String.valueOf(desired.createCategory()), desired.description(), integer(desired.estimatedMinutes()),
                String.valueOf(desired.needsInput()), desired.defaultOutputMaterialKind() == null
                        ? "" : desired.defaultOutputMaterialKind().name(), decimal(desired.standardYieldMin()),
                decimal(desired.standardYieldMax()));
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "\u0000" + issue.target()
                + "\u0000" + issue.message()).toArray(String[]::new));
    }

    private boolean isScopedIdempotencyRace(Throwable failure) {
        Throwable cursor = failure;
        while (cursor != null) {
            if (clean(cursor.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_work_process_draft_idempotency")) return true;
            cursor = cursor.getCause();
        }
        return false;
    }

    private String digest(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = clean(value).getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24));
                digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8));
                digest.update((byte) bytes.length);
                digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static String canonicalText(String value) {
        return Normalizer.normalize(clean(value), Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim();
    }

    private static String normalizeIdentity(String value) {
        return canonicalText(value).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private static BigDecimal canonicalDecimal(BigDecimal value) {
        if (value == null) return null;
        BigDecimal normalized = value.stripTrailingZeros();
        return normalized.scale() < 0 ? normalized.setScale(0) : normalized;
    }

    private static String decimal(BigDecimal value) {
        BigDecimal normalized = canonicalDecimal(value);
        return normalized == null ? "" : normalized.toPlainString();
    }

    private static boolean sameDecimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private static String integer(Integer value) {
        return value == null ? "" : value.toString();
    }

    private static DraftIssue required(String target, String label) {
        return issue("PROCESS_DRAFT_FIELD_REQUIRED", target, label + "不能为空", "补齐并确认该字段");
    }

    private static DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private static String clean(String value) {
        return WorkProcessDraftModels.clean(value);
    }

    private static BusinessException invalid(
            int status, String message, String code, String recovery, String target) {
        return new BusinessException(status, message).withCode(code).withHint(recovery)
                .withHintTarget(target).withSeverity("warning");
    }

    private record ResolvedCategory(String value, String resolution, boolean createCategory) {
    }
}
