package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 统一原型迁移 P10: 工厂端只读看外部软件 (授权状态、外部数据时点、报工对照)。
 * 工厂作用域由 JwtAuthInterceptor 的租户闸保证; 服务层再按 factoryId 过滤并校验授权行 (没授权 = 404)。
 * ⛔ 这里没有任何写入口: 授权只由平台改, 快照只由本机推送写。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/external-integrations")
@RequiredArgsConstructor
public class ExternalIntegrationController {

    private final ExternalIntegrationService service;

    @GetMapping
    @RequirePermission({"production:read"})
    public ApiResponse<List<ExternalIntegrationService.IntegrationView>> list(@PathVariable String factoryId) {
        return ApiResponse.success(service.factoryView(factoryId));
    }

    @GetMapping("/{system}/report-comparison")
    @RequirePermission({"production:read"})
    public ApiResponse<ExternalIntegrationService.Comparison> reportComparison(@PathVariable String factoryId,
                                                                               @PathVariable String system) {
        return ApiResponse.success(service.reportComparison(factoryId, system));
    }

    @GetMapping("/{system}/data-issues")
    @RequirePermission({"production:read"})
    public ApiResponse<ExternalIntegrationService.DataIssues> dataIssues(@PathVariable String factoryId,
                                                                         @PathVariable String system) {
        return ApiResponse.success(service.dataIssues(factoryId, system));
    }
}
