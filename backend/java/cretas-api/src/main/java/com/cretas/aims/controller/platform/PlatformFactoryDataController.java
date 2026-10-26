package com.cretas.aims.controller.platform;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.factorydata.FactoryDataService;
import com.cretas.aims.service.factorydata.FactoryDataService.AttachmentMode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 整厂数据导出 / 删除预演 (手册 docs/security/factory-data-export-and-erasure-runbook.md)。
 *
 * <p>{@code @RequireRole} 放行所有平台角色; 真正的门槛在 {@link FactoryDataService}: 只有
 * {@code cretas.factory-data.operator-admin-ids} 登记过的平台超级管理员。路径带 {@code {factoryId}},
 * 所以每次调用也会被 {@code PlatformDataAccessLogInterceptor} 记进该厂可见的平台访问记录。
 */
@RestController
@RequestMapping("/api/platform/factory-data/{factoryId}")
@Tag(name = "Platform Factory Data", description = "整厂数据导出与删除")
public class PlatformFactoryDataController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FactoryDataService service;

    public PlatformFactoryDataController(FactoryDataService service) {
        this.service = service;
    }

    @GetMapping("/preview")
    @RequireRole({"super_admin"})
    @Operation(summary = "删除预演: 每个库每张表将删除的行数、跨租户命中、保留项 (只计数, 不删)")
    public ApiResponse<FactoryDataService.Preview> preview(@PathVariable String factoryId, HttpServletRequest request) {
        return ApiResponse.success(service.preview(actor(request), factoryId));
    }

    /**
     * 导出 ZIP (直接写响应流)。响应头 {@code X-Factory-Data-Request-Id} 是回执编号:
     * 包的 SHA256 与字节数在写完后记进回执, 用它核对下载到的文件。
     */
    @PostMapping("/export")
    @RequireRole({"super_admin"})
    @Operation(summary = "整厂数据导出 (ZIP: 每表一个 CSV + manifest.json + 附件)")
    public void export(@PathVariable String factoryId,
                       @RequestParam(defaultValue = "INCLUDE") AttachmentMode attachments,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthenticatedActor actor = actor(request);
        long requestId = service.startExport(actor, factoryId);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"factory-" + factoryId + "-"
                + STAMP.format(LocalDateTime.now()) + ".zip\"");
        response.setHeader("X-Factory-Data-Request-Id", String.valueOf(requestId));
        service.writeExport(requestId, actor, factoryId, attachments, response.getOutputStream());
        response.flushBuffer();
    }

    private static AuthenticatedActor actor(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者"));
    }
}
