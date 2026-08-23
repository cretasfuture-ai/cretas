package com.cretas.aims.dto.user;

import com.cretas.aims.entity.enums.FactoryUserRole;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 创建用户请求对象
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Data
@Schema(description = "创建用户请求")
public class CreateUserRequest {

    /**
     * Apr 20 Bug BR-13 fix: 去 @NotBlank. DTO 同时用于 create + update
     * (UserController.updateUser 第 78 行: @Valid @RequestBody CreateUserRequest).
     * 更新场景 frontend 只送 email/fullName/phone/roleCode, username=null 导致 @NotBlank
     * 误报. Create 场景改由 UserServiceImpl.createUser 显式校验 (line 67+ 已有 existsByUsername
     * 调用, 补 null/empty 判断即可).
     * Pattern 保留 — 如果 update 场景 frontend 真送了 username, 也该过校验.
     */
    @Schema(description = "用户名", required = true)
    @Pattern(regexp = "^[a-zA-Z0-9_]{3,20}$", message = "用户名只能包含字母、数字和下划线，长度3-20")
    private String username;

    @Schema(description = "密码（创建时必填，更新时可选）")
    @Size(max = 128, message = "密码长度不能超过128个字符")
    private String password;

    @Schema(description = "邮箱（创建时必填，更新时可选）")
    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱长度不能超过100个字符")
    private String email;

    @Schema(description = "手机号")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Schema(description = "全名")
    @Size(max = 100, message = "全名长度不能超过100个字符")
    private String fullName;

    @Schema(description = "角色代码", required = true)
    private FactoryUserRole roleCode = FactoryUserRole.unactivated;

    /**
     * 部门 —— 自由文本, ⛔ 不是枚举。
     *
     * <p>R-2① (2026-08-20): 这里原来是 {@code Department} 枚举(5 个值)。
     * prod 实测 {@code users.department} 已经有 <b>15 种</b>取值:
     * {@code sales/finance/production/warehouse/hr/quality/procurement/dispatch/
     * equipment/management/workshop/system/none/PROCESSING/''} ——
     * <b>只有 PROCESSING 这一个在枚举里</b>(3 人), 其余全是枚举表达不了的。
     *
     * <p>后果是: 编辑一个 department=&quot;sales&quot; 的员工时前端把这个值原样回传,
     * Jackson 反序列化枚举失败 → 整个 PUT 400, 而报错跟部门八竿子打不着。
     * ⚠️ 数据早就长过了这个枚举, 枚举侧才是那份过期的副本。
     *
     * <p>⛔ 反方向(把 15 种值迁成 5 种)不可行 —— 那是**丢真实信息**:
     * &quot;财务&quot;和&quot;采购&quot;在这 5 个值里没有对应项, 只能被塞进 MANAGEMENT。
     *
     * <p>枚举挡在 Jackson {@code @RequestBody} 反序列化这一层 —— 请求体的 department
     * 不是那 5 个字面量之一就<b>直接 400，根本进不到业务代码</b>。同理，「部门管理」里
     * 新建的部门是自由命名的（见 {@code DepartmentServiceImpl.createDepartment}），
     * 几乎不可能撞上这 5 个值，所以表单一旦接上真实部门列表，提交就会 400。
     *
     * <p>存储侧本来就是 {@code varchar}({@code User.department} 是 String),
     * 所以这一步是让**入参**跟上存储和数据, 不是放宽约束。
     */
    @Schema(description = "部门（自由文本，与「部门管理」里的部门名称对齐，非枚举）")
    @Size(max = 50, message = "部门名称长度不能超过50个字符")
    private String department;

    @Schema(description = "职位")
    @Size(max = 100, message = "职位长度不能超过100个字符")
    private String position;

    @Schema(description = "月薪")
    private BigDecimal monthlySalary;

    @Schema(description = "预期工作分钟数")
    private Integer expectedWorkMinutes;

    @Schema(description = "CCR费率")
    private BigDecimal ccrRate;

    @Schema(description = "工号", maxLength = 10)
    @Size(max = 10, message = "工号长度不能超过 10 个字符")
    private String employeeCode;

    /**
     * Apr 18 2026 Bug #57: 员工编辑时切换在职/离职 UI 操作不生效 — 前端已送 isActive,
     * 但 DTO 之前没这个字段导致 Jackson 直接丢弃。create 场景该字段可选 (默认
     * entity 用 true), update 场景 service 需按非 null 才更新 (不能 false 覆盖为 null)。
     */
    @Schema(description = "是否在职")
    private Boolean isActive;
}