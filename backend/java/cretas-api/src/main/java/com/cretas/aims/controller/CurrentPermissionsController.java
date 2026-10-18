package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Read-only self snapshot using the same resolver as business authorization. */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/permissions/current")
public class CurrentPermissionsController {
    private final UserRepository users;
    private final PermissionService permissions;

    @GetMapping
    public ApiResponse<Map<String, String>> read(@PathVariable String factoryId, HttpServletRequest request) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者"));
        if (actor.isPlatformAdmin() || !factoryId.equals(actor.factoryId())) {
            throw new BusinessException(403, "只能读取本工厂当前账号权限");
        }
        User user = users.findById(actor.id())
                .filter(u -> factoryId.equals(u.getFactoryId()) && Boolean.TRUE.equals(u.getIsActive())
                        && u.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(403, "当前工厂账号不可用"));
        return ApiResponse.success(permissions.resolveModuleLevelsForAuthorization(user));
    }
}
