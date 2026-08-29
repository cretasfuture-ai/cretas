package com.cretas.aims.dto.user;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户改**自己**的资料。
 *
 * <h3>为什么单独一个 DTO，而不是复用 {@code CreateUserRequest}</h3>
 *
 * 复用它等于把「角色 / 工厂 / 启用状态 / 用户名 / 密码」这些字段一起开放给自助修改 ——
 * 那是一条**提权路径**。这个 DTO 里<b>只有</b>两个字段，加字段必须有人动这个类，
 * 而这个类的 review 面比 {@code CreateUserRequest} 小得多。
 *
 * <p>⛔ 不要往这里加 {@code roleCode} / {@code factoryId} / {@code isActive} /
 * {@code username} / {@code password}。手机号也不在这里 —— App 上写着
 * 「请联系管理员更换绑定手机号」，它是登录凭证的一部分。
 *
 * <h3>背景（2026-08-29 逐屏走查实测）</h3>
 *
 * 「我的 → 编辑 → 保存」对每个角色都可点，而它调的是
 * {@code PUT /users/{userId}}，那个接口由 {@code @RequireModule("hr_employee")}
 * + {@code @RequirePermission("hr:read_write")} 守着。仓储主管点保存拿到：
 *
 * <pre>403 您的角色 [仓储主管] 在 [人事管理] 模块无 [读写] 权限
 *     请联系工厂管理员…开通 [人事管理] 的 [读写] 权限</pre>
 *
 * ▎按那句提示去做是**错的** —— 为了让人改自己的名字而开通整个人事模块的写权限，
 * ▎等于把员工名册的增删改一起给出去。改自己的名字是自助行为，不是人事管理。
 */
@Data
@Schema(description = "修改本人资料（仅姓名与邮箱）")
public class UpdateMyProfileRequest {

    @Size(max = 50, message = "姓名不能超过 50 个字符")
    @Schema(description = "真实姓名", example = "张三")
    private String fullName;

    @Email(message = "邮箱格式不正确")
    @Size(max = 100, message = "邮箱不能超过 100 个字符")
    @Schema(description = "邮箱", example = "zhangsan@example.com")
    private String email;
}
