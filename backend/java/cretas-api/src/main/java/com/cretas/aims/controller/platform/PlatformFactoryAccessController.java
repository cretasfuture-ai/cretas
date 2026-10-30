package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.platform.PlatformFactoryAccessDTO;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.platform.PlatformFactoryAccessService;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/platform/factories/{factoryId}/access")
@RequiredArgsConstructor
public class PlatformFactoryAccessController {
    private final PlatformFactoryAccessService service;
    private final PlatformFactoryControlAuthority authority;

    private ActorRef actor(HttpServletRequest request, String factoryId, boolean write) {
        return authority.require(request, factoryId, write);
    }

    @GetMapping
    public ApiResponse<PlatformFactoryAccessDTO.Access> access(@PathVariable String factoryId, HttpServletRequest request) {
        ActorRef actor = actor(request, factoryId, false); return ApiResponse.success(service.access(factoryId, actor));
    }

    @GetMapping("/users")
    public ApiResponse<PlatformFactoryAccessDTO.Users> users(@PathVariable String factoryId, @RequestParam(required = false) String keyword,
                                                              @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size, HttpServletRequest request) {
        return ApiResponse.success(service.users(factoryId, keyword, page, size, actor(request, factoryId, false)));
    }

    @GetMapping("/users/{userId}/permissions")
    public ApiResponse<PlatformFactoryAccessDTO.UserPermissions> permissions(@PathVariable String factoryId, @PathVariable long userId, HttpServletRequest request) {
        return ApiResponse.success(service.permissions(factoryId, userId, actor(request, factoryId, false)));
    }

    @PutMapping("/modules/{code}")
    public ApiResponse<PlatformFactoryAccessDTO.Access> module(@PathVariable String factoryId, @PathVariable String code, @RequestBody PlatformFactoryAccessDTO.ModuleUpdate body, HttpServletRequest request) {
        if (body.enabled() == null) throw new com.cretas.aims.exception.BusinessException(400, "必须指定功能开通状态");
        return ApiResponse.success(service.updateModule(factoryId, code, body.enabled(), actor(request, factoryId, true)));
    }

    @PutMapping("/roles/{role}/{module}")
    public ApiResponse<PlatformFactoryAccessDTO.Access> role(@PathVariable String factoryId, @PathVariable String role, @PathVariable String module, @RequestBody PlatformFactoryAccessDTO.LevelUpdate body, HttpServletRequest request) {
        return ApiResponse.success(service.updateRole(factoryId, role, module, body.level(), actor(request, factoryId, true)));
    }

    @PutMapping("/users/{userId}/permissions/{module}")
    public ApiResponse<PlatformFactoryAccessDTO.UserPermissions> employee(@PathVariable String factoryId, @PathVariable long userId, @PathVariable String module, @RequestBody PlatformFactoryAccessDTO.LevelUpdate body, HttpServletRequest request) {
        return ApiResponse.success(service.updateEmployee(factoryId, userId, module, body.level(), actor(request, factoryId, true)));
    }
}
