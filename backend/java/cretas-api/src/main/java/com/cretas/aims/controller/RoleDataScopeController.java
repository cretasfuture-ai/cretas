package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.RoleDataScopeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/roles/{roleCode}/data-scope")
public class RoleDataScopeController {
    private final RoleDataScopeService service;
    private AuthenticatedActor requireActor(HttpServletRequest request,String factory) {
        AuthenticatedActor actor=AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401,"无法获取已认证操作者"));
        if (actor.isPlatformAdmin() || !factory.equals(actor.factoryId()))
            throw new BusinessException(403,"只能配置本工厂业务角色的数据范围");
        return actor;
    }
    @GetMapping
    @RequirePermission({"system:read","system:read_write"})
    public ApiResponse<RoleDataScopeService.View> read(@PathVariable String factoryId,@PathVariable String roleCode,HttpServletRequest request) {
        AuthenticatedActor actor=requireActor(request,factoryId);
        return ApiResponse.success(service.read(factoryId,roleCode,"factory_super_admin".equals(service.requireFactoryActor(actor,factoryId).getRoleCode())));
    }
    @PutMapping
    @RequirePermission("system:read_write")
    public ApiResponse<RoleDataScopeService.View> update(@PathVariable String factoryId,@PathVariable String roleCode,HttpServletRequest request,@RequestBody RoleDataScopeService.Update body) {
        AuthenticatedActor actor=requireActor(request,factoryId);
        if (!"factory_super_admin".equals(service.requireFactoryActor(actor,factoryId).getRoleCode())) throw new BusinessException(403,"仅工厂总监可修改角色数据范围");
        return ApiResponse.success("数据范围已保存",service.update(factoryId,roleCode,body,actor.id()));
    }
}
