package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Python -> Java 权限判定 (内部接口)。
 *
 * <p>Python 服务里有些接口 (如 /api/client-requirement/completeness/missing) 要和 Java 名册接口「同一个权限」,
 * 但 Python 拿到的 JWT 里只有岗位, 没有权限码; 岗位 -> 权限由 Java 的 L2 工厂覆盖 → L1 → 兜底三层配置决定,
 * 还要叠加按人的 GRANT/REVOKE。Python 不许再抄一份, 这里取真实用户后走
 * {@link PermissionService#hasPermission(User, String)} —— 与 {@code @RequirePermission} 同一条链。</p>
 *
 * <p>找不到该厂的该用户、或账号已停用 → 一律 {@code allowed=false}。
 * 判定结果在 PermissionService 里最多缓存 5 分钟, 工厂/个人改权限最多 5 分钟后生效。</p>
 *
 * <p>认证: {@code /api/internal/**} 由 JwtAuthInterceptor 校验 X-Internal-Key; 公网 nginx 对该前缀返回 404。</p>
 */
@RestController
@RequestMapping("/api/internal/permissions")
public class InternalPermissionCheckController {

    private final PermissionService permissionService;
    private final UserRepository userRepository;

    public InternalPermissionCheckController(PermissionService permissionService, UserRepository userRepository) {
        this.permissionService = permissionService;
        this.userRepository = userRepository;
    }

    /** GET /api/internal/permissions/user-check?factoryId=F006&userId=12&code=sales:read&code=finance:read */
    @GetMapping("/user-check")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> userCheck(
            @RequestParam String factoryId,
            @RequestParam Long userId,
            @RequestParam List<String> code) {
        User user = userRepository.findByIdAndFactoryId(userId, factoryId).orElse(null);
        boolean allowed = user != null && Boolean.TRUE.equals(user.getIsActive())
                && code.stream().anyMatch(c -> permissionService.hasPermission(user, c));
        return ResponseEntity.ok(ApiResponse.success(Map.of("allowed", allowed)));
    }
}
