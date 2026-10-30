package com.cretas.aims.controller.platform;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.platform.PlatformFactoryMenuDTO;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.platform.PlatformFactoryMenuService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/factories")
@RequireRole({"super_admin", "platform_admin"})
@RequiredArgsConstructor
public class PlatformFactoryMenuController {
    private final PlatformFactoryMenuService service;
    @GetMapping("/{factoryId}/menu-config")
    public ApiResponse<PlatformFactoryMenuDTO.Config> get(@PathVariable String factoryId,
                                                           HttpServletRequest request) {
        requireManagementActor(request);
        return ApiResponse.success(service.get(factoryId));
    }

    @PutMapping("/{factoryId}/menu-config/{moduleId}")
    public ApiResponse<PlatformFactoryMenuDTO.Config> update(@PathVariable String factoryId,
                                                              @PathVariable String moduleId,
                                                              @Valid @RequestBody PlatformFactoryMenuDTO.Update input,
                                                              HttpServletRequest request) {
        requireManagementActor(request);
        return ApiResponse.success("菜单配置已更新", service.update(factoryId, moduleId, input));
    }

    @PostMapping("/{factoryId}/view-context")
    public ApiResponse<PlatformFactoryMenuDTO.ViewContext> view(@PathVariable String factoryId,
                                                                  HttpServletRequest request) {
        AuthenticatedActor actor = requireManagementActor(request);
        return ApiResponse.success(service.issueViewContext(factoryId, actor.id(),
                String.valueOf(request.getAttribute("username")), actor.role()));
    }

    private AuthenticatedActor requireManagementActor(HttpServletRequest request) {
        AuthenticatedActor actor = AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "未授权"));
        String role = actor.role() == null ? "" : actor.role().toLowerCase(java.util.Locale.ROOT);
        if (!actor.isPlatformAdmin() || !("super_admin".equals(role) || "platform_admin".equals(role))) {
            throw new BusinessException(403, "仅平台管理员可执行平台管理操作");
        }
        return actor;
    }
}
