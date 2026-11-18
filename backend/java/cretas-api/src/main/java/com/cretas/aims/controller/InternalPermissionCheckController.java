package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
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
 * Python 不许再抄一份角色表 (抄了会漂)。这里把「某工厂某岗位有没有这些权限码中的任意一个」交给
 * {@link PermissionService#roleHasPermission} —— 与 {@code @RequirePermission} 走同一条链。</p>
 *
 * <p>认证: {@code /api/internal/**} 由 JwtAuthInterceptor 校验 X-Internal-Key。</p>
 */
@RestController
@RequestMapping("/api/internal/permissions")
public class InternalPermissionCheckController {

    private final PermissionService permissionService;

    public InternalPermissionCheckController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** GET /api/internal/permissions/role-check?factoryId=F006&role=dispatcher&code=sales:read&code=finance:read */
    @GetMapping("/role-check")
    public ResponseEntity<ApiResponse<Map<String, Boolean>>> roleCheck(
            @RequestParam String factoryId,
            @RequestParam String role,
            @RequestParam List<String> code) {
        boolean allowed = code.stream()
                .anyMatch(c -> permissionService.roleHasPermission(factoryId, role, c));
        return ResponseEntity.ok(ApiResponse.success(Map.of("allowed", allowed)));
    }
}
