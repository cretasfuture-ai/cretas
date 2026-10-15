package com.cretas.aims.blueprint.draftentry;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.ApplyAIInitRequest;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.impl.UserServiceImpl;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftCommand;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.Competency;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.Department;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.DraftRequest;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.PlatformRole;
import com.cretas.aims.service.roletrainingdraft.RoleTrainingDraftModels.PositionInput;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code accounts} proposal: positions for the default accounts the interview chose to create.
 *
 * <p>The interview's only accounts fact is {@code suggestedData.provisionDefaultUsers} ("create one
 * super admin, one workshop supervisor, one operator?"). It says which <em>roles</em> exist, nothing
 * about headcount, training cycle or competencies. So the proposal fills role, title, code and
 * department, plus what the command's own rules force for a food-handling department, and asks the
 * user for the rest. Notably it does not copy the interview's "one each": a privileged role needs
 * at least two people (segregation of duties), and the command would refuse one.</p>
 */
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true", matchIfMissing = false)
@Component
public class AccountsDraftProposer implements CapabilityDraftProposer {

    /**
     * {@link FactoryUserRole#getDepartment()} → the command's department. A role whose department is
     * not here gets no department and the user is asked — an unknown department is never mapped to
     * a legal-looking default.
     */
    private static final Map<String, Department> DEPARTMENTS = Map.of(
            "production", Department.PROCESSING,
            "workshop", Department.PROCESSING,
            "warehouse", Department.WAREHOUSE,
            "quality", Department.QUALITY,
            "equipment", Department.MAINTENANCE,
            "all", Department.MANAGEMENT);
    /** The command's limits ({@code ROLE_TRAINING_ENVELOPE_INVALID}, {@code ..._COMPETENCIES_INVALID}, segregation of duties). */
    static final int MIN_HEADCOUNT = 1;
    static final int MIN_PRIVILEGED_HEADCOUNT = 2;
    static final int MAX_HEADCOUNT = 500;
    static final int MIN_REFRESH_MONTHS = 1;
    static final int MAX_REFRESH_MONTHS = 60;
    static final int MAX_COMPETENCIES = Competency.values().length;

    static final List<Option> DEPARTMENT_OPTIONS = Option.ofEnum(Department.class, Map.of(
            Department.PROCESSING, "加工",
            Department.WAREHOUSE, "仓储",
            Department.QUALITY, "质量",
            Department.MAINTENANCE, "设备维护",
            Department.MANAGEMENT, "管理"));
    static final List<Option> COMPETENCY_OPTIONS = Option.ofEnum(Competency.class, Map.of(
            Competency.FOOD_SAFETY_BASIC, "食品安全基础",
            Competency.HACCP_AWARENESS, "HACCP 意识",
            Competency.ALLERGEN_CONTROL, "过敏原控制",
            Competency.SANITATION_SOP, "清洁消毒规程",
            Competency.EQUIPMENT_OPERATION, "设备操作",
            Competency.INTERNAL_AUDIT, "内部审核",
            Competency.FIRST_AID, "急救"));
    private static final String COMPETENCY_VALUES = String.join("、",
            Arrays.stream(Competency.values()).map(Enum::name).toList());

    /** The command's row limits ({@code positions} non-empty, at most {@code MAX_POSITIONS}) and field lengths. */
    static final int MIN_POSITIONS = 1;
    static final int MAX_POSITIONS = 300;
    static final int MAX_POSITION_CODE = 60;
    static final int MAX_POSITION_TITLE = 200;
    static final int MAX_CHANGE_REASON = 500;
    /** Every role the command accepts, labelled with the account role's display name (and its code where two names collide). */
    static final List<Option> PLATFORM_ROLE_OPTIONS = platformRoleOptions();
    private static final String WHY_NO_POSITIONS = "建厂访谈没有选择建立默认账号，蓝图里没有岗位资料";
    /** One row of {@code positions}: every component of {@link PositionInput}, with the command's own rules. */
    static final List<RequiredInput> POSITION_FIELDS = List.of(
            RequiredInput.text("positionCode", "岗位编码", WHY_NO_POSITIONS,
                    "大写字母或数字开头，只含大写字母、数字、下划线、点或连字符，最长 " + MAX_POSITION_CODE + " 位；草稿内不重复",
                    MAX_POSITION_CODE),
            RequiredInput.text("positionTitle", "岗位名称", WHY_NO_POSITIONS, "1–" + MAX_POSITION_TITLE + " 字", MAX_POSITION_TITLE),
            RequiredInput.oneOf("department", "所属部门", WHY_NO_POSITIONS,
                    String.join("、", DEPARTMENT_OPTIONS.stream().map(Option::label).toList())
                            + " 之一（加工、仓储、质量属于接触食品的岗位，必须要求健康证和食品安全基础）",
                    DEPARTMENT_OPTIONS),
            RequiredInput.oneOf("platformRole", "要求的账号角色", WHY_NO_POSITIONS,
                    String.join("、", PLATFORM_ROLE_OPTIONS.stream().map(Option::label).toList())
                            + " 之一（工厂总监、权限管理员是特权角色，最少在岗不能少于 2 人）",
                    PLATFORM_ROLE_OPTIONS),
            RequiredInput.integer("minimumHeadcount", "最少几人在岗", WHY_NO_POSITIONS,
                    MIN_HEADCOUNT + "–" + MAX_HEADCOUNT + " 人（特权角色不能少于 2 人）", MIN_HEADCOUNT, MAX_HEADCOUNT),
            RequiredInput.manyOf("requiredCompetencies", "需要哪些能力", WHY_NO_POSITIONS,
                    "至少选 1 项，最多 " + MAX_COMPETENCIES + " 项，可选：" + COMPETENCY_VALUES
                            + "（接触食品的岗位必须含 FOOD_SAFETY_BASIC）",
                    COMPETENCY_OPTIONS, 1, MAX_COMPETENCIES),
            RequiredInput.integer("trainingRefreshMonths", "多久复训一次", WHY_NO_POSITIONS,
                    MIN_REFRESH_MONTHS + "–" + MAX_REFRESH_MONTHS + " 个月", MIN_REFRESH_MONTHS, MAX_REFRESH_MONTHS),
            RequiredInput.bool("healthCertificateRequired", "是否需要健康证", WHY_NO_POSITIONS,
                    "是 / 否（接触食品的岗位必须选 是）"),
            RequiredInput.text("changeReason", "设这个岗位的依据", WHY_NO_POSITIONS,
                    "1–" + MAX_CHANGE_REASON + " 字", MAX_CHANGE_REASON));

    @Override
    public String capabilityId() {
        return RoleTrainingDraftCommand.CAPABILITY_ID;
    }

    @Override
    public Proposed propose(Source source) {
        var blueprint = source.blueprint();
        ApplyAIInitRequest payload = blueprint.getSourcePayload();
        Boolean provision = payload.getSuggestedData() == null ? null : payload.getSuggestedData().getProvisionDefaultUsers();
        List<PositionInput> positions = new ArrayList<>();
        List<RequiredInput> inputs = new ArrayList<>();
        List<Evidence> evidence = new ArrayList<>();

        if (!Boolean.TRUE.equals(provision)) {
            inputs.add(RequiredInput.list("positions", "工厂需要哪些岗位",
                    "建厂访谈没有选择建立默认账号，蓝图里没有岗位资料",
                    MIN_POSITIONS + "–" + MAX_POSITIONS + " 个岗位，岗位编码不能重复", "岗位",
                    MIN_POSITIONS, MAX_POSITIONS, POSITION_FIELDS));
        } else {
            evidence.add(new Evidence("sourcePayload.suggestedData.provisionDefaultUsers", "true"));
            List<String> roles = List.of("factory_super_admin", "workshop_supervisor", "operator");
            evidence.add(new Evidence("defaultAccountRoles", String.join(",", roles)));
            for (int i = 0; i < roles.size(); i++) {
                positions.add(position(i, roles.get(i), blueprint.getVersion(), inputs));
            }
        }

        DraftRequest request = new DraftRequest(source.factoryId(), blueprint.getId(), blueprint.getVersion(),
                source.sourceDigest(), positions, null, null, 0L);
        return Proposed.single(capabilityId(), "岗位与培训要求", request, inputs, evidence);
    }

    /**
     * Asked when a role's department has no mapping. Package-visible because no default account role
     * hits this today, and its options must still be proven against the real command.
     */
    static RequiredInput departmentInput(int index, String title, String roleDepartment) {
        return RequiredInput.oneOf("positions[" + index + "].department", "“" + title + "”属于哪个部门",
                "角色定义里的部门“" + roleDepartment + "”无法对应到岗位部门",
                String.join("、", DEPARTMENT_OPTIONS.stream().map(Option::label).toList()) + " 之一",
                DEPARTMENT_OPTIONS);
    }

    private static List<Option> platformRoleOptions() {
        Map<String, Integer> displayCount = new java.util.HashMap<>();
        for (PlatformRole role : PlatformRole.values()) {
            displayCount.merge(FactoryUserRole.valueOf(role.name()).getDisplayName(), 1, Integer::sum);
        }
        return Arrays.stream(PlatformRole.values()).map(role -> {
            String display = FactoryUserRole.valueOf(role.name()).getDisplayName();
            // dispatcher and production_manager are both 「调度」: the code tells them apart.
            return new Option(role.name(), displayCount.get(display) > 1 ? display + "（" + role.name() + "）" : display);
        }).toList();
    }

    private static PositionInput position(int index, String roleCode, Integer blueprintVersion, List<RequiredInput> inputs) {
        FactoryUserRole role = FactoryUserRole.valueOf(roleCode);
        PlatformRole platformRole = PlatformRole.valueOf(roleCode);
        String at = "positions[" + index + "].";
        String title = role.getDisplayName();

        Department department = DEPARTMENTS.get(role.getDepartment());
        if (department == null) {
            inputs.add(departmentInput(index, title, role.getDepartment()));
        }
        boolean foodHandling = department != null && RoleTrainingDraftModels.foodHandlingPosition(department);
        boolean privileged = RoleTrainingDraftModels.privilegedRole(platformRole);

        int minHeadcount = privileged ? MIN_PRIVILEGED_HEADCOUNT : MIN_HEADCOUNT;
        inputs.add(RequiredInput.integer(at + "minimumHeadcount", "“" + title + "”最少需要几个人在岗",
                "建厂访谈没有问人数",
                privileged ? minHeadcount + "–" + MAX_HEADCOUNT + " 人（特权岗位要求职责分离，不能少于 2 人）"
                        : minHeadcount + "–" + MAX_HEADCOUNT + " 人",
                minHeadcount, MAX_HEADCOUNT));
        inputs.add(RequiredInput.integer(at + "trainingRefreshMonths", "“" + title + "”多久复训一次",
                "建厂访谈没有问培训周期", MIN_REFRESH_MONTHS + "–" + MAX_REFRESH_MONTHS + " 个月",
                MIN_REFRESH_MONTHS, MAX_REFRESH_MONTHS));
        if (!foodHandling) {
            inputs.add(RequiredInput.manyOf(at + "requiredCompetencies", "“" + title + "”需要哪些能力",
                    "建厂访谈没有问能力要求", "至少选 1 项，最多 " + MAX_COMPETENCIES + " 项，可选：" + COMPETENCY_VALUES,
                    COMPETENCY_OPTIONS, 1, MAX_COMPETENCIES));
            inputs.add(RequiredInput.bool(at + "healthCertificateRequired", "“" + title + "”是否需要健康证",
                    "不接触食品的岗位由工厂自己决定", "是 / 否"));
        }

        return new PositionInput(
                "P-" + roleCode.toUpperCase(Locale.ROOT),
                title,
                department,
                platformRole,
                null,
                // A food-handling position must require food-safety basics (command rule); the user may add more.
                foodHandling ? List.of(Competency.FOOD_SAFETY_BASIC) : List.of(),
                null,
                foodHandling,
                "依据建厂蓝图 v" + blueprintVersion + "：访谈选择建立默认账号，含“" + title + "”");
    }
}
