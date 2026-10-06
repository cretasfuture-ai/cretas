package com.cretas.aims.controller;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService.GrantItem;
import com.cretas.aims.service.intent.FrontlineAiWriteGrantService.GrantView;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * 管理端「一线角色 AI 操作权限」(owner 2026-10-06: 先收窄, 管理端可开放)。
 *
 * <p>列出本工厂的 AI 写操作, 以及每个一线角色是否开放 (默认开放 / 本厂开放 / 未开放);
 * 工厂管理员按「工厂 × 角色 × 意图」开放或关闭。查询类操作不在这里, 一线角色查询不受限。
 * 编辑权限与「功能配置」同一组角色。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/ai-frontline-grants")
@RequiredArgsConstructor
public class AiFrontlineGrantController {

    private final FrontlineAiWriteGrantService grantService;

    @GetMapping
    public ApiResponse<GrantView> list(@PathVariable String factoryId) {
        return ApiResponse.success(grantService.list(factoryId));
    }

    @PutMapping("/{roleCode}/{intentCode}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<GrantItem> grant(@PathVariable String factoryId,
                                        @PathVariable String roleCode,
                                        @PathVariable String intentCode,
                                        @Parameter(hidden = true) @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(grantService.grant(factoryId, roleCode, intentCode, userId));
    }

    @DeleteMapping("/{roleCode}/{intentCode}")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<GrantItem> revoke(@PathVariable String factoryId,
                                         @PathVariable String roleCode,
                                         @PathVariable String intentCode,
                                         @Parameter(hidden = true) @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(grantService.revoke(factoryId, roleCode, intentCode, userId));
    }
}
