package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import com.cretas.aims.service.secrecy.FactorySecrecySettingsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 平台管理工厂级保密配置 (Steve 2026-11-03)。与 {@link PlatformFactoryAccessController} 同一套权限:
 * {@link PlatformFactoryControlAuthority#require} (平台令牌、非查看模式、库里有效的平台超级管理员)。
 * ⛔ 工厂端没有任何写入口; 工厂端只能在 feature-config 里只读地看到生效值。
 */
@RestController
@RequestMapping("/api/platform/factories/{factoryId}/secrecy")
public class PlatformFactorySecrecyController {

    private final FactorySecrecySettingsService service;
    private final PlatformFactoryControlAuthority authority;

    public PlatformFactorySecrecyController(FactorySecrecySettingsService service,
                                            PlatformFactoryControlAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @GetMapping
    public ApiResponse<FactorySecrecySettingsService.SettingsView> list(@PathVariable String factoryId,
                                                                        HttpServletRequest request) {
        ActorRef actor = authority.require(request, factoryId, false);
        return ApiResponse.success(service.view(factoryId, actor));
    }

    @GetMapping("/{itemCode}")
    public ApiResponse<FactorySecrecySettingsService.ItemDetailView> get(@PathVariable String factoryId,
                                                                         @PathVariable String itemCode,
                                                                         HttpServletRequest request) {
        ActorRef actor = authority.require(request, factoryId, false);
        return ApiResponse.success(service.item(factoryId, itemCode, actor));
    }

    @PutMapping("/{itemCode}")
    public ApiResponse<FactorySecrecySettingsService.ItemDetailView> put(@PathVariable String factoryId,
                                                                         @PathVariable String itemCode,
                                                                         @RequestBody FactorySecrecySettingsService.Update body,
                                                                         HttpServletRequest request) {
        ActorRef actor = authority.require(request, factoryId, true);
        return ApiResponse.success(service.update(factoryId, itemCode, body, actor, role(request)));
    }

    /** 「全开 / 全关」: 逐项写入、逐项留痕, 本身不存值。 */
    @PostMapping("/bulk")
    public ApiResponse<FactorySecrecySettingsService.SettingsView> bulk(@PathVariable String factoryId,
                                                                        @RequestBody FactorySecrecySettingsService.BulkUpdate body,
                                                                        HttpServletRequest request) {
        ActorRef actor = authority.require(request, factoryId, true);
        return ApiResponse.success(service.bulk(factoryId, body, actor, role(request)));
    }

    private static String role(HttpServletRequest request) {
        return AuthenticatedActor.current(request).map(AuthenticatedActor::role).orElse("UNKNOWN");
    }
}
