package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.permission.PermissionModuleCatalog;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本厂「谁能审批」一览 (纪律排查 B1–B3/B11/B15, Steve 2026-11-10「审批人按权限」)。
 *
 * <p>只读; 改授权走既有的工厂权限覆盖接口 {@code PUT /canvas/role-module-override/{role}/{module}?level=rw|-}
 * (不带 level = 恢复系统默认)。两者权限一致: {@code system:read_write}。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/approval-permissions")
@RequiredArgsConstructor
@Tag(name = "Approval Permissions", description = "各类审批授给了哪些岗位 (本厂)")
public class ApprovalPermissionController {

    private final PermissionService permissionService;

    @GetMapping
    @RequirePermission({"system:read_write"})
    @Operation(summary = "本厂各类审批的授权一览 (岗位 × 审批类型, 含来源)")
    public ApiResponse<Map<String, Object>> list(@PathVariable String factoryId) {
        Map<String, Map<String, String>> grants = permissionService.approvalGrants(factoryId);

        List<Map<String, String>> approvals = new ArrayList<>();
        for (String module : PermissionServiceImpl.APPROVAL_MODULES) {
            approvals.add(Map.of("code", module, "label", PermissionModuleCatalog.label(module)));
        }
        List<Map<String, String>> roles = new ArrayList<>();
        Map<String, Map<String, Map<String, Object>>> cells = new LinkedHashMap<>();
        grants.forEach((module, byRole) -> {
            Map<String, Map<String, Object>> row = new LinkedHashMap<>();
            byRole.forEach((role, value) -> {
                String[] parts = value.split("\\|", 2);
                row.put(role, Map.of("granted", "Y".equals(parts[0]), "source", parts.length > 1 ? parts[1] : "DEFAULT"));
            });
            cells.put(module, row);
        });
        if (!grants.isEmpty()) {
            for (String role : grants.values().iterator().next().keySet()) {
                FactoryUserRole r = FactoryUserRole.valueOf(role);
                roles.add(Map.of("code", role, "label", r.getDisplayName()));
            }
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("approvals", approvals);
        body.put("roles", roles);
        body.put("grants", cells);
        return ApiResponse.success(body);
    }
}
