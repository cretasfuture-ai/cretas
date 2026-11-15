package com.cretas.aims.controller.platform;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台在「工厂管理」里维护外部软件接口授权 (统一原型迁移 P10)。权限与保密配置同一套:
 * {@link PlatformFactoryControlAuthority#require} (平台令牌、非查看模式、库里有效的平台超级管理员)。
 * ⛔ 工厂端没有任何写入口; 工厂端只能在 feature-config 里只读地看到 moduleId = "integrations"。
 */
@RestController
@RequestMapping("/api/platform/factories/{factoryId}/integrations")
public class PlatformFactoryIntegrationController {

    private final ExternalIntegrationService service;
    private final PlatformFactoryControlAuthority authority;

    public PlatformFactoryIntegrationController(ExternalIntegrationService service,
                                                PlatformFactoryControlAuthority authority) {
        this.service = service;
        this.authority = authority;
    }

    @GetMapping
    public ApiResponse<List<ExternalIntegrationService.PlatformRow>> list(@PathVariable String factoryId,
                                                                         HttpServletRequest request) {
        authority.require(request, factoryId, false);
        return ApiResponse.success(service.platformView(factoryId));
    }

    @PutMapping("/{system}")
    public ApiResponse<List<ExternalIntegrationService.PlatformRow>> put(@PathVariable String factoryId,
                                                                        @PathVariable String system,
                                                                        @RequestBody ExternalIntegrationService.Update body,
                                                                        HttpServletRequest request) {
        ActorRef actor = authority.require(request, factoryId, true);
        return ApiResponse.success(service.upsert(factoryId, system, body, actor));
    }

    /** 签发 / 轮换本机推送凭证; 明文只在这次响应里出现一次。 */
    @PostMapping("/{system}/push-token")
    public ApiResponse<ExternalIntegrationService.IssuedToken> issueToken(@PathVariable String factoryId,
                                                                         @PathVariable String system,
                                                                         HttpServletRequest request) {
        ActorRef actor = authority.require(request, factoryId, true);
        return ApiResponse.success(service.issuePushToken(factoryId, system, actor));
    }
}
