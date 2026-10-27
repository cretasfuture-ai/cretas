package com.cretas.aims.controller.audit;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.service.tenantbackup.TenantBackupRepository;
import com.cretas.aims.service.tenantbackup.TenantBackupService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * 工厂查看本厂的数据备份 (口径 Q5: 只读; 平台端看全部)。
 *
 * <p>租户隔离: {@code JwtAuthInterceptor} 拒绝别的工厂的用户访问 {@code /api/mobile/{factoryId}/**},
 * 查询按路径里的工厂编号。角色与「平台访问记录」相同。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/data-backups")
public class FactoryDataBackupController {

    private final TenantBackupService service;

    public FactoryDataBackupController(TenantBackupService service) {
        this.service = service;
    }

    @GetMapping
    @RequireRole({"factory_super_admin", "permission_admin"})
    public ApiResponse<TenantBackupService.FactoryView> view(@PathVariable String factoryId) {
        return ApiResponse.success(service.factoryView(factoryId));
    }

    /** 只有「客户自持钥匙」的成功备份可以下载 (平台钥匙的件工厂解不开)。 */
    @GetMapping("/{runId}/download")
    @RequireRole({"factory_super_admin", "permission_admin"})
    public void download(@PathVariable String factoryId, @PathVariable long runId, HttpServletResponse response)
            throws IOException {
        TenantBackupRepository.Run run = service.downloadable(factoryId, runId);
        String name = run.objectKey().substring(run.objectKey().lastIndexOf('/') + 1);
        response.setContentType("application/octet-stream");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + name + "\"");
        if (run.encryptedBytes() != null) response.setContentLengthLong(run.encryptedBytes());
        service.copyTo(run.objectKey(), response.getOutputStream());
        response.flushBuffer();
    }
}
