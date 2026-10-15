package com.cretas.aims.service.roletrainingdraft;

import com.cretas.aims.blueprint.capabilityadapter.FactoryCapabilityAdapterContracts;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.BlueprintResponse;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintModels.CapabilityView;
import com.cretas.aims.blueprint.canonical.CanonicalFactoryBlueprintService;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraft;
import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraftEvidence;
import com.cretas.aims.entity.roletrainingdraft.RoleTrainingDraftPosition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.roletrainingdraft.RoleTrainingDraftEvidenceRepository;
import com.cretas.aims.repository.roletrainingdraft.RoleTrainingDraftPositionRepository;
import com.cretas.aims.repository.roletrainingdraft.RoleTrainingDraftRepository;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.AuthenticatedOperator;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.Competency;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.Department;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DerivedPosition;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftIssue;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftPreview;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftReadback;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftReceipt;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftRequest;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.PlatformRole;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.PositionInput;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.RecoveryPlan;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Module-owned factory position, role-requirement and training DRAFT command.
 *
 * <p>The immutable capability catalog v2 dependency is {@code factory} — a read-only identity
 * scope. The adapter contract previously declared {@code modules}; B16 converges it.</p>
 *
 * <p>A row states that a position <em>requires</em> a platform role and a set of competencies. No
 * user is created, no role or permission is granted, no invitation is sent, no electronic signature
 * is captured and no training record is written; all six counters are fixed at zero. Those actions
 * remain separate commands with their own authorisation.</p>
 *
 * <p>{@code accounts-role-training-policy/v1} derives whether the required role is privileged, the
 * resulting segregation-of-duties obligation, the access-review interval and whether the position
 * handles food.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Service
public class RoleTrainingDraftCommand {
    public static final String CAPABILITY_ID = "accounts";
    public static final String REQUIRED_PERMISSION = "account:role:draft";
    private static final String AUTHORITY_CODE = "ACCOUNTS_ROLE_TRAINING_CATALOG_AUTHORITY";
    private static final List<String> DEPENDENCIES =
            FactoryCapabilityAdapterContracts.dependenciesOf(CAPABILITY_ID);
    private static final int MAX_POSITIONS = 300;

    private final RoleTrainingDraftRepository drafts;
    private final RoleTrainingDraftPositionRepository positions;
    private final RoleTrainingDraftEvidenceRepository evidence;
    private final CanonicalFactoryBlueprintService canonicalBlueprints;
    private final UserRepository users;

    public RoleTrainingDraftCommand(
            RoleTrainingDraftRepository drafts,
            RoleTrainingDraftPositionRepository positions,
            RoleTrainingDraftEvidenceRepository evidence,
            CanonicalFactoryBlueprintService canonicalBlueprints,
            UserRepository users) {
        this.drafts = drafts;
        this.positions = positions;
        this.evidence = evidence;
        this.canonicalBlueprints = canonicalBlueprints;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public DraftPreview preview(DraftRequest request) {
        validateEnvelope(request, false);
        return evaluate(request).preview();
    }

    @Transactional
    public DraftReceipt materialize(DraftRequest request, AuthenticatedOperator operator) {
        validateEnvelope(request, true);
        authorize(request, operator);
        String requestDigest = requestDigest(request, operator);
        Optional<RoleTrainingDraft> prior = drafts
                .findByFactoryIdAndIdempotencyKey(request.factoryId(), request.idempotencyKey());
        if (prior.isPresent()) {
            if (!requestDigest.equals(prior.get().getRequestDigest())) {
                throw invalid(409, "幂等键对应不同岗位与培训要求请求",
                        "ROLE_TRAINING_IDEMPOTENCY_CONFLICT");
            }
            readBack(request.factoryId(), prior.get().getId());
            return receipt(prior.get(), true, 0, 0);
        }

        Evaluation evaluation = evaluate(request);
        DraftPreview plan = evaluation.preview();
        if (!"READY".equals(plan.status())) {
            throw invalid(409, "岗位与培训要求预览被阻断", "ROLE_TRAINING_PREVIEW_BLOCKED");
        }
        if (!request.confirmedPreviewDigest().equals(plan.previewDigest())) {
            throw invalid(409, "岗位与培训要求确认摘要已漂移", "ROLE_TRAINING_PREVIEW_DRIFT");
        }

        BlueprintAuthority authority = evaluation.authority();
        RoleTrainingDraft parent = RoleTrainingDraft.builder()
                .id(UUID.randomUUID().toString())
                .factoryId(request.factoryId())
                .blueprintId(request.blueprintId())
                .blueprintVersion(request.blueprintVersion())
                .sourceDigest(request.sourceDigest())
                .sourceSnapshotDigest(plan.sourceSnapshotDigest())
                .capabilityCatalogVersion(authority.catalogVersion())
                .capabilityCatalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authority.authoritySnapshotDigest())
                .desiredDigest(plan.desiredDigest())
                .previewDigest(plan.previewDigest())
                .requestDigest(requestDigest)
                .idempotencyKey(request.idempotencyKey())
                .operatorId(operator.actor().factoryUserId()).actorType(operator.actor().type()).actorId(operator.actor().id())
                .positionCount(plan.positions().size())
                .evidenceCount(1)
                .dependencyBasis(RoleTrainingDraftModels.DEPENDENCY_BASIS)
                .algorithmVersion(RoleTrainingDraftModels.ALGORITHM_VERSION)
                .dependencyClosureComplete(true)
                .status("DRAFT")
                .build();
        RoleTrainingDraft saved;
        try {
            saved = drafts.saveAndFlush(parent);
        } catch (DataIntegrityViolationException exception) {
            if (!isIdempotencyRace(exception)) throw exception;
            throw invalid(409, "岗位与培训要求幂等键发生并发竞争",
                    "ROLE_TRAINING_IDEMPOTENCY_RACE");
        }

        List<RoleTrainingDraftPosition> rows = plan.positions().stream()
                .map(position -> toRow(saved, position)).toList();
        positions.saveAllAndFlush(rows);
        evidence.saveAndFlush(RoleTrainingDraftEvidence.builder()
                .id(UUID.randomUUID().toString())
                .draftId(saved.getId())
                .factoryId(saved.getFactoryId())
                .authorityCode(AUTHORITY_CODE)
                .catalogVersion(authority.catalogVersion())
                .catalogDigest(authority.catalogDigest())
                .authoritySnapshotDigest(authorityEvidenceDigest(authority))
                .build());

        DraftReadback readback = readBack(saved.getFactoryId(), saved.getId());
        if (!plan.desiredDigest().equals(readback.desiredDigest())) {
            throw invalid(409, "岗位与培训要求回读摘要不一致",
                    "ROLE_TRAINING_READBACK_MISMATCH");
        }
        return receipt(saved, false, 1, 2 + rows.size());
    }

    @Transactional(readOnly = true)
    public DraftReadback readBack(String factoryId, String draftId) {
        RoleTrainingDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该岗位与培训要求草稿",
                        "ROLE_TRAINING_NOT_FOUND"));
        List<RoleTrainingDraftPosition> rows = positions
                .findByDraftIdAndFactoryIdOrderByOrdinal(draftId, factoryId);
        List<RoleTrainingDraftEvidence> evidenceRows = evidence
                .findByDraftIdAndFactoryIdOrderByAuthorityCode(draftId, factoryId);
        if (!"DRAFT".equals(parent.getStatus())
                || !Boolean.TRUE.equals(parent.getDependencyClosureComplete())
                || !RoleTrainingDraftModels.DEPENDENCY_BASIS.equals(parent.getDependencyBasis())
                || !RoleTrainingDraftModels.ALGORITHM_VERSION.equals(parent.getAlgorithmVersion())
                || parent.getPositionCount() == null || parent.getPositionCount() != rows.size()
                || parent.getEvidenceCount() == null || parent.getEvidenceCount() != 1
                || evidenceRows.size() != 1) {
            throw invalid(409, "岗位与培训要求父子行或依赖闭包不完整",
                    "ROLE_TRAINING_READBACK_INVARIANT_BROKEN");
        }

        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(factoryId, parent.getBlueprintId(),
                parent.getBlueprintVersion(), issues);
        if (!issues.isEmpty()
                || !parent.getAuthoritySnapshotDigest().equals(authority.authoritySnapshotDigest())
                || !parent.getCapabilityCatalogVersion().equals(authority.catalogVersion())
                || !parent.getCapabilityCatalogDigest().equals(authority.catalogDigest())) {
            throw invalid(409, "岗位与培训要求上游 Blueprint 已漂移",
                    "ROLE_TRAINING_UPSTREAM_DRIFT");
        }
        String currentSourceSnapshot = sourceSnapshot(authority);
        if (!currentSourceSnapshot.equals(parent.getSourceSnapshotDigest())) {
            throw invalid(409, "岗位与培训要求源快照摘要已漂移",
                    "ROLE_TRAINING_SOURCE_SNAPSHOT_DRIFT");
        }
        if (!sameEvidence(evidenceRows.get(0), authority)) {
            throw invalid(409, "岗位与培训要求目录依据摘要不一致",
                    "ROLE_TRAINING_EVIDENCE_MISMATCH");
        }

        List<DerivedPosition> derived = new ArrayList<>();
        for (RoleTrainingDraftPosition row : rows) {
            DerivedPosition position = fromRow(row);
            if (position.privilegedRole() != RoleTrainingDraftModels
                        .privilegedRole(position.platformRole())
                    || position.segregationOfDutiesRequired() != RoleTrainingDraftModels
                        .segregationOfDutiesRequired(position.platformRole())
                    || position.accessReviewIntervalMonths() != RoleTrainingDraftModels
                        .accessReviewIntervalMonths(position.platformRole())
                    || position.foodHandlingPosition() != RoleTrainingDraftModels
                        .foodHandlingPosition(position.department())) {
                throw invalid(409, "岗位权限与食品处理派生结论与服务端算法不一致",
                        "ROLE_TRAINING_POLICY_TAMPERED");
            }
            derived.add(position);
        }
        if (!desiredDigest(derived).equals(parent.getDesiredDigest())) {
            throw invalid(409, "岗位与培训要求逐字段回读摘要不一致",
                    "ROLE_TRAINING_READBACK_MISMATCH");
        }
        return new DraftReadback(RoleTrainingDraftModels.SCHEMA_VERSION, factoryId, draftId,
                parent.getStatus(), derived, parent.getSourceSnapshotDigest(),
                parent.getDesiredDigest(), 0, 0, 0, 0, 0, 0,
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    @Transactional(readOnly = true)
    public RecoveryPlan recoveryPlan(String factoryId, String draftId,
                                     String expectedDesiredDigest) {
        RoleTrainingDraft parent = drafts.findByIdAndFactoryId(draftId, factoryId)
                .orElseThrow(() -> invalid(404, "当前工厂没有该岗位与培训要求草稿",
                        "ROLE_TRAINING_NOT_FOUND"));
        if (!"DRAFT".equals(parent.getStatus()) || !isDigest(expectedDesiredDigest)
                || !expectedDesiredDigest.equals(parent.getDesiredDigest())) {
            throw invalid(409, "只能恢复摘要精确匹配的未发布岗位与培训要求草稿",
                    "ROLE_TRAINING_RECOVERY_NOT_ALLOWED");
        }
        return new RecoveryPlan(RoleTrainingDraftModels.SCHEMA_VERSION, factoryId, draftId,
                expectedDesiredDigest, "ARCHIVE_DRAFT_ONLY",
                "只归档未发布岗位要求草稿；不创建账号、不授予角色或权限、不发送邀请、不采集电子签名、不写培训记录",
                "NOT_PUBLISHED", "NOT_ACTIVATED");
    }

    private Evaluation evaluate(DraftRequest request) {
        List<DraftIssue> issues = new ArrayList<>();
        BlueprintAuthority authority = resolveBlueprint(request.factoryId(), request.blueprintId(),
                request.blueprintVersion(), issues);
        List<DerivedPosition> derived = new ArrayList<>();
        Set<String> codes = new HashSet<>();
        for (int ordinal = 0; ordinal < request.positions().size(); ordinal++) {
            PositionInput input = request.positions().get(ordinal);
            String target = "positions[" + ordinal + "]";
            if (!validatePosition(input, target, issues)) continue;
            if (!codes.add(input.positionCode())) {
                issues.add(issue("ROLE_TRAINING_DUPLICATE_POSITION_CODE", target,
                        "同一草稿内岗位编码不能重复", "合并重复岗位"));
                continue;
            }
            derived.add(derive(ordinal, input));
        }
        issues.sort(Comparator.comparing(DraftIssue::code).thenComparing(DraftIssue::target));

        String desired = desiredDigest(derived);
        String sourceSnapshot = sourceSnapshot(authority);
        String status = issues.isEmpty() ? "READY" : "BLOCKED";
        String previewDigest = digest("ROLE_TRAINING_PREVIEW",
                RoleTrainingDraftModels.SCHEMA_VERSION, RoleTrainingDraftModels.ALGORITHM_VERSION,
                status, sourceSnapshot, authority.authoritySnapshotDigest(), desired,
                issueDigest(issues));
        DraftPreview preview = new DraftPreview(RoleTrainingDraftModels.SCHEMA_VERSION,
                RoleTrainingDraftModels.ALGORITHM_VERSION, status, request.factoryId(),
                DEPENDENCIES, request, derived, sourceSnapshot,
                authority.authoritySnapshotDigest(), desired, previewDigest, issues,
                0, 0, 0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED");
        return new Evaluation(preview, authority);
    }

    private BlueprintAuthority resolveBlueprint(String factoryId, String blueprintId,
                                                long blueprintVersion, List<DraftIssue> issues) {
        BlueprintResponse response = canonicalBlueprints.current(factoryId);
        if (response == null || !blueprintId.equals(response.getId())
                || !Objects.equals(Math.toIntExact(blueprintVersion), response.getVersion())
                || !("DRAFT".equals(response.getLifecycleState())
                     || "VALIDATED".equals(response.getLifecycleState()))) {
            issues.add(issue("ROLE_TRAINING_BLUEPRINT_LINEAGE_MISMATCH", "blueprintId",
                    "当前工厂 Blueprint DRAFT/VALIDATED 版本与请求不一致",
                    "刷新当前 Blueprint 后重新预览"));
            return BlueprintAuthority.missing();
        }
        CapabilityView capability = response.getCapabilities() == null ? null
                : response.getCapabilities().stream()
                .filter(item -> CAPABILITY_ID.equals(item.getId())).findFirst().orElse(null);
        Set<String> dependencies = capability == null || capability.getDependsOn() == null
                ? Set.of() : Set.copyOf(capability.getDependsOn());
        if (capability == null || !capability.isIncluded() || !"READY".equals(capability.getStatus())
                || !dependencies.equals(Set.copyOf(DEPENDENCIES))) {
            issues.add(issue("ROLE_TRAINING_CAPABILITY_NOT_READY", "capability",
                    "accounts 或 factory 目录依赖尚未就绪",
                    "补齐工厂身份证据后重新编译 Blueprint"));
        }
        Set<String> refs = capability == null || capability.getAuthorityRefs() == null
                ? Set.of() : Set.copyOf(capability.getAuthorityRefs());
        if (response.getCapabilityCatalogVersion() == null
                || response.getCapabilityCatalogVersion().isBlank()
                || !isDigest(response.getCapabilityCatalogDigest()) || refs.isEmpty()) {
            issues.add(issue("ROLE_TRAINING_AUTHORITY_CATALOG_INVALID", "capabilityCatalog",
                    "服务端能力目录版本、摘要或人员依据无效",
                    "部署带 authority refs 的版本化能力目录"));
        }
        String authoritySnapshot = digest("ROLE_TRAINING_AUTHORITY",
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                refs.stream().sorted().reduce("", (left, right) -> left + "|" + right));
        return new BlueprintAuthority(response.getCapabilityCatalogVersion(),
                response.getCapabilityCatalogDigest(), refs, authoritySnapshot,
                digestBlueprint(response));
    }

    private boolean validatePosition(PositionInput input, String target, List<DraftIssue> issues) {
        if (input == null || isBlankOrTooLong(input.positionCode(), 60)
                || !input.positionCode().matches("[A-Z0-9][A-Z0-9_.-]{0,59}")
                || isBlankOrTooLong(input.positionTitle(), 200)
                || input.department() == null || input.platformRole() == null
                || isBlankOrTooLong(input.changeReason(), 500)) {
            issues.add(issue("ROLE_TRAINING_POSITION_FIELDS_REQUIRED", target,
                    "岗位编码、名称、部门、要求角色和变更原因均必填",
                    "补齐 typed 岗位字段"));
            return false;
        }
        boolean valid = true;
        if (input.minimumHeadcount() == null || input.minimumHeadcount() < 1
                || input.minimumHeadcount() > 500
                || input.trainingRefreshMonths() == null || input.trainingRefreshMonths() < 1
                || input.trainingRefreshMonths() > 60) {
            issues.add(issue("ROLE_TRAINING_ENVELOPE_INVALID", target,
                    "最少在岗人数必须为 1–500，培训复训周期必须为 1–60 个月",
                    "修正人数与复训周期"));
            valid = false;
        }
        if (input.requiredCompetencies().isEmpty() || input.requiredCompetencies().size() > 7
                || new HashSet<>(input.requiredCompetencies()).size()
                   != input.requiredCompetencies().size()) {
            issues.add(issue("ROLE_TRAINING_COMPETENCIES_INVALID", target,
                    "能力要求必须为非空、不重复的封闭枚举", "选择有效能力要求"));
            valid = false;
        }
        if (RoleTrainingDraftModels.foodHandlingPosition(input.department())) {
            if (!input.healthCertificateRequired()) {
                issues.add(issue("ROLE_TRAINING_FOOD_HANDLING_NEEDS_HEALTH_CERTIFICATE", target,
                        "接触食品的岗位必须要求健康证", "把健康证要求改为必需"));
                valid = false;
            }
            if (!input.requiredCompetencies().contains(Competency.FOOD_SAFETY_BASIC)) {
                issues.add(issue("ROLE_TRAINING_FOOD_HANDLING_NEEDS_FOOD_SAFETY_BASIC", target,
                        "接触食品的岗位必须要求食品安全基础培训",
                        "增加食品安全基础能力要求"));
                valid = false;
            }
        }
        if (RoleTrainingDraftModels.segregationOfDutiesRequired(input.platformRole())
                && input.minimumHeadcount() != null && input.minimumHeadcount() < 2) {
            issues.add(issue("ROLE_TRAINING_PRIVILEGED_ROLE_NEEDS_SEGREGATION", target,
                    "需要职责分离的特权岗位最少在岗人数不能少于 2 人",
                    "提高最少在岗人数或改用非特权角色"));
            valid = false;
        }
        return valid;
    }

    private DerivedPosition derive(int ordinal, PositionInput input) {
        List<Competency> competencies = input.requiredCompetencies().stream()
                .sorted(Comparator.comparing(Enum::name)).toList();
        PlatformRole role = input.platformRole();
        return new DerivedPosition(ordinal, input.positionCode(), input.positionTitle(),
                input.department(), role, input.minimumHeadcount(), competencies,
                input.trainingRefreshMonths(), input.healthCertificateRequired(),
                RoleTrainingDraftModels.privilegedRole(role),
                RoleTrainingDraftModels.segregationOfDutiesRequired(role),
                RoleTrainingDraftModels.accessReviewIntervalMonths(role),
                RoleTrainingDraftModels.foodHandlingPosition(input.department()),
                input.changeReason());
    }

    private RoleTrainingDraftPosition toRow(RoleTrainingDraft parent, DerivedPosition value) {
        return RoleTrainingDraftPosition.builder()
                .id(UUID.randomUUID().toString()).draftId(parent.getId())
                .factoryId(parent.getFactoryId()).ordinal(value.ordinal())
                .positionCode(value.positionCode()).positionTitle(value.positionTitle())
                .department(value.department().name())
                .platformRole(value.platformRole().name())
                .minimumHeadcount(value.minimumHeadcount())
                .requiredCompetencies(joinEnums(value.requiredCompetencies()))
                .trainingRefreshMonths(value.trainingRefreshMonths())
                .healthCertificateRequired(value.healthCertificateRequired())
                .privilegedRole(value.privilegedRole())
                .segregationOfDutiesRequired(value.segregationOfDutiesRequired())
                .accessReviewIntervalMonths(value.accessReviewIntervalMonths())
                .foodHandlingPosition(value.foodHandlingPosition())
                .changeReason(value.changeReason()).build();
    }

    private DerivedPosition fromRow(RoleTrainingDraftPosition row) {
        return new DerivedPosition(row.getOrdinal(), row.getPositionCode(),
                row.getPositionTitle(), Department.valueOf(row.getDepartment()),
                PlatformRole.valueOf(row.getPlatformRole()), row.getMinimumHeadcount(),
                parseEnums(row.getRequiredCompetencies(), Competency::valueOf),
                row.getTrainingRefreshMonths(),
                Boolean.TRUE.equals(row.getHealthCertificateRequired()),
                Boolean.TRUE.equals(row.getPrivilegedRole()),
                Boolean.TRUE.equals(row.getSegregationOfDutiesRequired()),
                row.getAccessReviewIntervalMonths(),
                Boolean.TRUE.equals(row.getFoodHandlingPosition()), row.getChangeReason());
    }

    private void authorize(DraftRequest request, AuthenticatedOperator operator) {
        if (operator == null || (operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.FACTORY_USER && operator.actor().type() != com.cretas.aims.security.AuthenticatedActorType.PLATFORM_ADMIN) || operator.actor().id() != request.operatorId()
                || !request.factoryId().equals(operator.factoryId())) {
            throw invalid(403, "操作人和工厂范围不一致",
                    "ROLE_TRAINING_OPERATOR_SCOPE_MISMATCH");
        }
        if (!operator.permissions().contains(REQUIRED_PERMISSION)) {
            throw invalid(403, "缺少岗位与培训要求草稿权限",
                    "ROLE_TRAINING_PERMISSION_REQUIRED");
        }
        Optional<User> member = users.findByIdAndFactoryId(operator.operatorId(),
                operator.factoryId());
        if (member.filter(user -> Boolean.TRUE.equals(user.getIsActive())).isEmpty()) {
            throw invalid(403, "操作人不是当前工厂有效成员", "ROLE_TRAINING_MEMBER_REQUIRED");
        }
    }

    private void validateEnvelope(DraftRequest request, boolean materialize) {
        if (request == null || request.factoryId().isBlank() || request.factoryId().length() > 50
                || request.blueprintId().isBlank() || request.blueprintId().length() > 100
                || request.blueprintVersion() <= 0 || !isDigest(request.sourceDigest())
                || request.positions().isEmpty() || request.positions().size() > MAX_POSITIONS) {
            throw invalid(400, "Blueprint envelope 或岗位数量无效",
                    "ROLE_TRAINING_SCOPE_REQUIRED");
        }
        if (materialize && (request.operatorId() <= 0 || request.idempotencyKey().isBlank()
                || request.idempotencyKey().length() > 128
                || !isDigest(request.confirmedPreviewDigest()))) {
            throw invalid(400, "操作人、幂等键或确认摘要无效",
                    "ROLE_TRAINING_MATERIALIZE_ENVELOPE_INVALID");
        }
    }

    private DraftReceipt receipt(RoleTrainingDraft row, boolean replayed, int moduleWrites,
                                 int persistedRows) {
        return new DraftReceipt(RoleTrainingDraftModels.SCHEMA_VERSION, row.getId(),
                row.getFactoryId(), row.getId(), row.getStatus(), row.getSourceSnapshotDigest(),
                row.getDesiredDigest(), row.getPreviewDigest(), row.getRequestDigest(),
                row.getPositionCount(), row.getEvidenceCount(),
                row.getLockVersion() == null ? 0L : row.getLockVersion(), replayed,
                moduleWrites, replayed ? 1 : moduleWrites, persistedRows,
                0, 0, 0, 0, 0, 0, "NOT_PUBLISHED", "NOT_ACTIVATED", "ARCHIVE_DRAFT_ONLY");
    }

    private boolean sameEvidence(RoleTrainingDraftEvidence row, BlueprintAuthority authority) {
        return AUTHORITY_CODE.equals(row.getAuthorityCode())
                && authority.catalogVersion().equals(row.getCatalogVersion())
                && authority.catalogDigest().equals(row.getCatalogDigest())
                && authorityEvidenceDigest(authority).equals(row.getAuthoritySnapshotDigest());
    }

    private String sourceSnapshot(BlueprintAuthority authority) {
        return digest("ROLE_TRAINING_SOURCE", authority.blueprintSnapshotDigest(),
                authority.authoritySnapshotDigest());
    }

    private String desiredDigest(List<DerivedPosition> derived) {
        List<String> values = new ArrayList<>(List.of(RoleTrainingDraftModels.SCHEMA_VERSION,
                RoleTrainingDraftModels.ALGORITHM_VERSION));
        derived.stream().sorted(Comparator.comparingInt(DerivedPosition::ordinal))
                .forEach(value -> values.add(value.ordinal() + "|" + value.positionCode() + "|"
                        + value.positionTitle() + "|" + value.department() + "|"
                        + value.platformRole() + "|" + value.minimumHeadcount() + "|"
                        + joinEnums(value.requiredCompetencies()) + "|"
                        + value.trainingRefreshMonths() + "|"
                        + value.healthCertificateRequired() + "|" + value.privilegedRole() + "|"
                        + value.segregationOfDutiesRequired() + "|"
                        + value.accessReviewIntervalMonths() + "|"
                        + value.foodHandlingPosition() + "|" + value.changeReason()));
        return digest(values.toArray(String[]::new));
    }

    private String requestDigest(DraftRequest request, AuthenticatedOperator operator) {
        DraftPreview preview = evaluate(request).preview();
        return digest(request.factoryId(), request.blueprintId(),
                String.valueOf(request.blueprintVersion()), request.sourceDigest(),
                operator.actor().type().name() + ":" + String.valueOf(operator.actor().id()), request.idempotencyKey(),
                request.confirmedPreviewDigest(), preview.desiredDigest());
    }

    private String digestBlueprint(BlueprintResponse response) {
        return digest("BLUEPRINT", response.getFactoryId(), response.getId(),
                String.valueOf(response.getVersion()), String.valueOf(response.getRowVersion()),
                response.getLifecycleState(), response.getSchemaVersion(),
                response.getCapabilityCatalogVersion(), response.getCapabilityCatalogDigest(),
                String.valueOf(response.getUpdatedAt()));
    }

    private String authorityEvidenceDigest(BlueprintAuthority authority) {
        return digest("CATALOG_AUTHORITY", authority.catalogVersion(), authority.catalogDigest(),
                AUTHORITY_CODE, authority.authoritySnapshotDigest());
    }

    private String issueDigest(List<DraftIssue> issues) {
        return digest(issues.stream().map(issue -> issue.code() + "|" + issue.target() + "|"
                + issue.message() + "|" + issue.recovery()).toArray(String[]::new));
    }

    private boolean isIdempotencyRace(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (String.valueOf(current.getMessage()).toLowerCase(Locale.ROOT)
                    .contains("uk_role_training_draft_idempotency")) return true;
        }
        return false;
    }

    private boolean isBlankOrTooLong(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private boolean isDigest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private String joinEnums(List<? extends Enum<?>> values) {
        return String.join("|", values.stream().map(Enum::name).toList());
    }

    private <E> List<E> parseEnums(String value, java.util.function.Function<String, E> parser) {
        if (value == null || value.isBlank()) return List.of();
        return List.of(value.split("\\|", -1)).stream().map(parser).toList();
    }

    private String digest(String... values) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
                md.update((byte) (bytes.length >>> 24));
                md.update((byte) (bytes.length >>> 16));
                md.update((byte) (bytes.length >>> 8));
                md.update((byte) bytes.length);
                md.update(bytes);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DraftIssue issue(String code, String target, String message, String recovery) {
        return new DraftIssue(code, target, message, recovery);
    }

    private BusinessException invalid(int status, String message, String code) {
        return new BusinessException(status, message).withCode(code);
    }

    private record Evaluation(DraftPreview preview, BlueprintAuthority authority) {
    }

    private record BlueprintAuthority(String catalogVersion, String catalogDigest,
                                      Set<String> authorityRefs, String authoritySnapshotDigest,
                                      String blueprintSnapshotDigest) {
        static BlueprintAuthority missing() {
            return new BlueprintAuthority("", digestStatic("MISSING_CATALOG"), Set.of(),
                    digestStatic("MISSING_AUTHORITY"), digestStatic("MISSING_BLUEPRINT"));
        }
    }

    private static String digestStatic(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
