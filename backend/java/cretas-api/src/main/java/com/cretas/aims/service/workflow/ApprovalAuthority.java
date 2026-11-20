package com.cretas.aims.service.workflow;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

/**
 * 「谁能审批」按权限判断 (纪律排查 B1–B3/B11/B15, Steve 2026-11-10): 每类审批一个专门的权限码
 * ({@code approval_xxx:approve}, 见 {@code PermissionServiceImpl.APPROVAL_*}), 各厂可在权限配置里改授给谁;
 * 没配置时的默认 = 改动前写死的岗位名单 (默认写在权限兜底矩阵里, 不在这里)。
 *
 * <p>给 controller 注解之外的入口用 (服务层二次校验、AI 工具路径)。HTTP 入口直接用
 * {@code @RequirePermission("approval_xxx:approve")}, 走的是同一个 {@link PermissionService#hasPermission}。
 */
@Component
public class ApprovalAuthority {

    @Nullable
    private final PermissionService permissionService;
    @Nullable
    private final UserRepository userRepository;

    public ApprovalAuthority(@Nullable PermissionService permissionService, @Nullable UserRepository userRepository) {
        this.permissionService = permissionService;
        this.userRepository = userRepository;
    }

    /** 权限码: {@code <模块>:approve}。 */
    public static String code(String approvalModule) {
        return approvalModule + ":approve";
    }

    /**
     * @return 这个账号有没有该类审批的权限; 账号查不到 → 没有 (fail closed)。
     */
    public boolean canApprove(@Nullable Long userId, String approvalModule) {
        if (userId == null || permissionService == null || userRepository == null) {
            return false;
        }
        User user = userRepository.findById(userId).orElse(null);
        return user != null && permissionService.hasPermission(user, code(approvalModule));
    }
}
