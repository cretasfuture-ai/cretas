package com.cretas.aims.controller.platform;

import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.factorydata.FactoryDataRequestLedger;
import com.cretas.aims.service.factorydata.FactoryDataService;
import com.cretas.aims.service.factorydata.FactoryDataService.AttachmentMode;
import com.cretas.aims.service.factorydata.FactoryDataService.Format;
import com.cretas.aims.service.factorydata.FactoryErasureService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 整厂数据导出 / 删除 (手册 docs/security/factory-data-export-and-erasure-runbook.md)。
 *
 * <p>{@code @RequireRole} 放行所有平台角色; 真正的门槛在服务里: 只有
 * {@code cretas.factory-data.operator-admin-ids} 登记过的平台超级管理员。路径带 {@code {factoryId}},
 * 所以每次调用也会被 {@code PlatformDataAccessLogInterceptor} 记进该厂可见的平台访问记录。
 */
@RestController
@RequestMapping("/api/platform/factory-data/{factoryId}")
@Tag(name = "Platform Factory Data", description = "整厂数据导出与删除")
public class PlatformFactoryDataController {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final FactoryDataService service;
    private final FactoryErasureService erasure;
    private final FactoryDataRequestLedger ledger;

    public PlatformFactoryDataController(FactoryDataService service, FactoryErasureService erasure,
                                         FactoryDataRequestLedger ledger) {
        this.service = service;
        this.erasure = erasure;
        this.ledger = ledger;
    }

    @GetMapping("/preview")
    @RequireRole({"super_admin"})
    @Operation(summary = "删除预演: 每个库每张表将删除的行数、跨租户命中、保留项、阻塞项 (只计数, 不删)")
    public ApiResponse<FactoryDataService.Preview> preview(@PathVariable String factoryId, HttpServletRequest request) {
        return ApiResponse.success(service.preview(actor(request), factoryId));
    }

    /**
     * 导出 ZIP (直接写响应流)。{@code formats} 逗号分隔, 可组合: CSV / XLSX / JSON (默认 CSV)。
     * 响应头 {@code X-Factory-Data-Request-Id} 是回执编号: 包的 SHA256 与字节数在写完后记进回执。
     */
    @PostMapping("/export")
    @RequireRole({"super_admin"})
    @Operation(summary = "整厂数据导出 (ZIP: CSV / Excel / JSON 可组合 + manifest.json + 附件)")
    public void export(@PathVariable String factoryId,
                       @RequestParam(defaultValue = "INCLUDE") AttachmentMode attachments,
                       @RequestParam(defaultValue = "CSV") String formats,
                       HttpServletRequest request, HttpServletResponse response) throws IOException {
        AuthenticatedActor actor = actor(request);
        Set<Format> fmt = parseFormats(formats);
        long requestId = service.startExport(actor, factoryId);
        response.setContentType("application/zip");
        response.setHeader("Content-Disposition", "attachment; filename=\"factory-" + factoryId + "-"
                + STAMP.format(LocalDateTime.now()) + ".zip\"");
        response.setHeader("X-Factory-Data-Request-Id", String.valueOf(requestId));
        service.writeExport(requestId, actor, factoryId, attachments, fmt, response.getOutputStream());
        response.flushBuffer();
    }

    @PostMapping("/erasure-requests")
    @RequireRole({"super_admin"})
    @Operation(summary = "登记删除申请: 当场停用工厂, 冷静期 7 天 (必须先有一次成功导出)")
    public ApiResponse<FactoryErasureService.RequestView> requestErasure(
            @PathVariable String factoryId, @RequestBody FactoryErasureService.ErasureRequestBody body,
            HttpServletRequest request) {
        return ApiResponse.success(erasure.request(actor(request), factoryId, body));
    }

    @PostMapping("/erasure-requests/{requestId}/cancel")
    @RequireRole({"super_admin"})
    @Operation(summary = "冷静期内撤销删除申请, 恢复工厂")
    public ApiResponse<FactoryErasureService.RequestView> cancelErasure(
            @PathVariable String factoryId, @PathVariable long requestId, HttpServletRequest request) {
        return ApiResponse.success(erasure.cancel(actor(request), factoryId, requestId));
    }

    @PostMapping("/erase")
    @RequireRole({"super_admin"})
    @Operation(summary = "执行彻底删除 (冷静期满; 带申请编号、预演编号, 再输一遍工厂编号)")
    public ApiResponse<FactoryErasureService.Receipt> erase(
            @PathVariable String factoryId, @RequestBody FactoryErasureService.EraseBody body,
            HttpServletRequest request) {
        return ApiResponse.success(erasure.erase(actor(request), factoryId, body));
    }

    @GetMapping("/requests")
    @RequireRole({"super_admin"})
    @Operation(summary = "该厂的全部回执 (预演 / 导出 / 删除申请 / 撤销 / 删除), 新的在前")
    public ApiResponse<List<FactoryDataRequestLedger.Row>> requests(@PathVariable String factoryId,
                                                                    HttpServletRequest request) {
        service.requireOperator(actor(request));
        return ApiResponse.success(ledger.list(factoryId));
    }

    static Set<Format> parseFormats(String formats) {
        try {
            Set<Format> out = EnumSet.noneOf(Format.class);
            Arrays.stream(formats.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                    .map(s -> Format.valueOf(s.toUpperCase(Locale.ROOT))).forEach(out::add);
            if (out.isEmpty()) throw new IllegalArgumentException();
            return out;
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, "formats 只能是 CSV / XLSX / JSON 的组合, 逗号分隔");
        }
    }

    private static AuthenticatedActor actor(HttpServletRequest request) {
        return AuthenticatedActor.current(request)
                .orElseThrow(() -> new BusinessException(401, "无法获取已认证操作者"));
    }
}
