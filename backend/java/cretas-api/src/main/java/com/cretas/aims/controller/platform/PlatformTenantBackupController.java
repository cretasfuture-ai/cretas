package com.cretas.aims.controller.platform;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository;
import com.cretas.aims.service.tenantbackup.TenantBackupService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
 * 按工厂备份套餐: 平台管理 (手册 docs/security/tenant-backup-plans-runbook.md)。
 * 只有平台超级管理员能看能改 (口径 Q6: 跟合同走, 改动留痕); 手动执行还要求在整厂导出的白名单里。
 */
@RestController
@RequestMapping("/api/platform/tenant-backups")
@Tag(name = "Platform Tenant Backups", description = "按工厂备份套餐")
public class PlatformTenantBackupController {

    private final TenantBackupService service;

    public PlatformTenantBackupController(TenantBackupService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"super_admin"})
    @Operation(summary = "全部工厂的备份套餐与最近执行情况")
    public ApiResponse<List<TenantBackupService.FactoryRow>> list() {
        return ApiResponse.success(service.overview());
    }

    @GetMapping("/{factoryId}")
    @RequireRole({"super_admin"})
    @Operation(summary = "一个工厂的套餐、执行记录、改动记录、OSS 上现存的备份件")
    public ApiResponse<TenantBackupService.FactoryDetail> detail(@PathVariable String factoryId) {
        return ApiResponse.success(service.detail(factoryId));
    }

    @PutMapping("/{factoryId}/plan")
    @RequireRole({"super_admin"})
    @Operation(summary = "开通 / 修改 / 关闭套餐 (必须填原因, 记进改动记录)")
    public ApiResponse<TenantBackupRepository.Plan> updatePlan(@PathVariable String factoryId,
                                                               @RequestBody TenantBackupService.PlanRequest body,
                                                               HttpServletRequest request) {
        return ApiResponse.success(service.updatePlan(actor(request), factoryId, body));
    }

    @PostMapping("/{factoryId}/run")
    @RequireRole({"super_admin"})
    @Operation(summary = "立即执行一次备份 (后台执行; 结果看执行记录)")
    public ApiResponse<String> runNow(@PathVariable String factoryId, HttpServletRequest request) {
        service.runNow(actor(request), factoryId);
        return ApiResponse.success("已开始, 稍后刷新执行记录查看结果");
    }

    private static AuthenticatedActor actor(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者"));
    }
}
