package com.cretas.aims.mapper;

import com.cretas.aims.dto.user.CreateUserRequest;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.util.BusinessDomainUtils;
import com.cretas.aims.utils.DepartmentDisplayNames;
import org.springframework.stereotype.Component;
/**
 * 用户实体映射器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Component
public class UserMapper {
    /**
     * Entity 转 DTO
     */
    public UserDTO toDTO(User user) {
        return toDTO(user, null);
    }

    /**
     * Entity 转 DTO，附带工厂类型
     */
    public UserDTO toDTO(User user, String factoryType) {
        if (user == null) {
            return null;
        }
        return UserDTO.builder()
                .id(user.getId())
                .factoryId(user.getFactoryId())
                .username(user.getUsername())
                .email(user.getEmail())
                .phone(user.getPhone())
                .fullName(user.getFullName())
                .isActive(user.getIsActive())
                .roleCode(user.getRoleEnum())
                .roleDisplayName(user.getPosition()) // 使用position作为显示名称
                .department(null) // department现在是String，不是枚举
                // 缺陷 15 (2026-09-01): 原来是**原样透传 code** —— 账号管理/HR/工牌/导出/
                // RN 个人信息与新员工屏都在把 `procurement`/`warehouse` 这种内部 code
                // 显示给一线。⚠️ 这是本字段**唯一**的构造点, 改这一处六个消费屏一起好。
                // ⛔ 认不出的值原样返回(department 是自由文本, 里面已有中文), 不回退空白。
                .departmentDisplayName(DepartmentDisplayNames.display(user.getDepartment()))
                .position(user.getPosition())
                .monthlySalary(user.getMonthlySalary())
                .expectedWorkMinutes(user.getExpectedWorkMinutes())
                .ccrRate(user.getCcrRate())
                .lastLogin(user.getLastLogin())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                // 调度员模块扩展字段
                .employeeCode(user.getEmployeeCode())
                .hireType(user.getHireType())
                .hireTypeDisplayName(user.getHireType() != null ? user.getHireType().getDisplayName() : null)
                .contractEndDate(user.getContractEndDate())
                .skillLevels(user.getSkillLevels())
                .hourlyRate(user.getHourlyRate())
                .avatarUrl(user.getAvatarUrl())
                .hireDate(user.getHireDate())
                .isTemporaryWorker(user.isTemporaryWorker())
                .workMonths(user.getWorkMonths())
                .contractRemainingDays(user.getContractRemainingDays())
                .isContractExpiringSoon(user.isContractExpiringSoon())
                .factoryType(factoryType != null ? factoryType : "FACTORY")
                .businessDomain(BusinessDomainUtils.resolveDomain(factoryType))
                .build();
    }

    /**
     * CreateRequest 转 Entity
     */
    public User toEntity(CreateUserRequest request, String factoryId) {
        if (request == null) {
            return null;
        }
        User user = new User();
        user.setFactoryId(factoryId);
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPhone(request.getPhone());
        user.setFullName(request.getFullName());
        // R-2 (2026-08-20): request.department 已从枚举改为自由文本(见 CreateUserRequest)，
        // 与 User.department(varchar) 同型，不再需要 .name() 转换。
        applyDepartment(user, request.getDepartment());
        // 设置 roleCode 和 position
        if (request.getRoleCode() != null) {
            user.setRoleCode(request.getRoleCode().name());
            user.setPosition(request.getRoleCode().name());
        } else if (request.getPosition() != null) {
            user.setRoleCode(request.getPosition());
            user.setPosition(request.getPosition());
        }
        user.setMonthlySalary(request.getMonthlySalary());
        user.setExpectedWorkMinutes(request.getExpectedWorkMinutes());
        user.setCcrRate(request.getCcrRate());
        if (request.getEmployeeCode() != null && !request.getEmployeeCode().isBlank()) {
            user.setEmployeeCode(request.getEmployeeCode());
        }
        // 员工表单的“在职”开关必须在创建路径生效；没送时仍保持原有的默认未激活。
        user.setIsActive(Boolean.TRUE.equals(request.getIsActive()));
        return user;
    }

    /**
     * 更新实体
     */
    public void updateEntity(User user, CreateUserRequest request) {
        if (request.getEmail() != null && !request.getEmail().isBlank()) {
            user.setEmail(request.getEmail());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getEmployeeCode() != null && !request.getEmployeeCode().isBlank()) {
            user.setEmployeeCode(request.getEmployeeCode());
        }
        // Apr 20 Bug BR-13 fix: roleCode 之前在此 mapper 被 ignore, 导致 /users/{id} PUT
        // 送 roleCode 无效 (用户 "角色变更保存, 更新失败"). 现补 update.
        if (request.getRoleCode() != null) {
            user.setRoleCode(request.getRoleCode().name());
        }
        if (request.getMonthlySalary() != null) {
            user.setMonthlySalary(request.getMonthlySalary());
        }
        if (request.getExpectedWorkMinutes() != null) {
            user.setExpectedWorkMinutes(request.getExpectedWorkMinutes());
        }
        if (request.getCcrRate() != null) {
            user.setCcrRate(request.getCcrRate());
        }
        // W-09 fix (Round 12, qa-prompt v2.4 Rule 17.2 sweep): `position` was
        // silently dropped on PUT /users/{id}. UI edit dialog doesn't expose
        // position today, so user-facing impact is low — but API consumers,
        // AI tools, and other code paths (e.g. UserServiceImpl.updateUserRole
        // line 250 uses setPosition) make this a real silent-drop gap.
        // isActive is intentionally handled in UserServiceImpl.updateUser
        // (Apr 18 Bug #57) and stays there.
        if (request.getPosition() != null) {
            user.setPosition(request.getPosition());
        }
        // R-2① (2026-08-20): department 之前**只在 toEntity(新建) 里写**, update 这一路
        // 一个字都没落库 —— HR 在编辑框里选了部门、点保存、看到「编辑成功」, 而库里没变。
        //
        // ⚠️ 这是本文件第三次同一个形状: roleCode(Apr 20 BR-13)、position(W-09)、
        //    现在是 department。三次都是「新建路径写了、更新路径漏了」, 症状都是
        //    **保存成功但没生效**(不报错, 所以没人发现)。
        //    ⇒ 有断言钉这件事: UserMapperUpdateFieldParityTest 逐字段比对两条路径。
        applyDepartment(user, request.getDepartment());
    }

    /**
     * 写 department 的唯一入口 —— 新建/更新两条路共用。
     *
     * <p>⛔ 不要在调用点各写一遍 null 判断: 这个文件已经因为「两条路各写一份」
     * 漏掉过三个字段(见 updateEntity 里的说明)。
     *
     * <p>空串按「清空部门」处理(HR 把部门删掉是合法动作), null 表示「这次没送这个字段」，
     * 保持原值不动。⚠️ 这两件事必须分开 —— 合并成一个就没法把部门改回空。
     */
    private void applyDepartment(User user, String department) {
        if (department == null) {
            return;
        }
        String trimmed = department.trim();
        user.setDepartment(trimmed.isEmpty() ? null : trimmed);
    }
}
