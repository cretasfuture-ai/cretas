package com.cretas.aims.controller.finance;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.finance.VoucherExportRequestDTO;
import com.cretas.aims.entity.enums.VoucherExportFileFormat;
import com.cretas.aims.entity.enums.VoucherTargetSystem;
import com.cretas.aims.service.finance.VoucherExportService;
import com.cretas.aims.service.finance.VoucherRowScopeContext;
import com.cretas.aims.service.finance.VoucherRowScopeQuery;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.RowScopeSupported;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * SP11: 凭证序时账/科目余额表导出 API.
 *
 * <pre>
 * POST /api/mobile/{factoryId}/finance/voucher-export           — 序时账导出 xlsx
 * GET  /api/mobile/{factoryId}/finance/subject-balance/export   — 科目余额表导出 xlsx
 * </pre>
 *
 * <p>RBAC: finance:read_write (财务人员), 仓管无权导出.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/finance")
@RequiredArgsConstructor
@RequirePermission({"finance:read_write"})
public class VoucherExportController {

    private final VoucherExportService voucherExportService;
    @Autowired(required = false)
    private BusinessRowScope businessRowScope;
    @Autowired(required = false)
    private VoucherRowScopeQuery voucherRowScopeQuery;

    /**
     * 导出凭证序时账 xlsx.
     *
     * <p>Request body: {@link VoucherExportRequestDTO} (startDate, endDate, targetSystem).
     */
    @PostMapping("/voucher-export")
    @RowScopeSupported
    public void exportSequentialLedger(
            @PathVariable String factoryId,
            @RequestBody @Valid VoucherExportRequestDTO req,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = (Long) request.getAttribute("userId");
        final Long exportUserId = userId == null ? 0L : userId;

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportSequentialLedger(
                factoryId, req, exportUserId, response.getOutputStream()));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[SP11] VoucherExport sequential: factoryId={} file={}", factoryId, fileName);
    }

    /**
     * 导出科目余额表 xlsx.
     *
     * <p>Query params: startDate, endDate, targetSystem.
     */
    @GetMapping("/subject-balance/export")
    @RowScopeSupported
    public void exportSubjectBalance(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = (Long) request.getAttribute("userId");
        final Long exportUserId = userId == null ? 0L : userId;

        VoucherExportRequestDTO req = VoucherExportRequestDTO.builder()
                .startDate(java.time.LocalDate.parse(startDate))
                .endDate(java.time.LocalDate.parse(endDate))
                .targetSystem(targetSystem)
                .build();

        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportSubjectBalance(
                factoryId, req, exportUserId, response.getOutputStream()));
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
        response.flushBuffer();
        log.info("[SP11] VoucherExport subjectBalance: factoryId={} file={}", factoryId, fileName);
    }

    /**
     * 金蝶导入模板导出 — 支持 云星空(KINGDEE_YXSKY, 默认, 向后兼容) 和 KIS/K3(KINGDEE_KIS) 两种列集.
     * 六膳门客户两套金蝶版本都要用, 前端加了格式选择器 (见 voucher-export/index.vue).
     */
    @GetMapping("/voucher-import-template")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read_write"})
    public void exportKingdeeImportTemplate(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE_YXSKY") VoucherTargetSystem targetSystem,
            @RequestParam(defaultValue = "XLSX") VoucherExportFileFormat format,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = VoucherExportRequestDTO.builder()
                .startDate(java.time.LocalDate.parse(startDate))
                .endDate(java.time.LocalDate.parse(endDate))
                .targetSystem(targetSystem)
                .exportFormat(format)
                .build();
        response.setContentType(contentTypeFor(format));
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportKingdeeImportTemplate(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Kingdee] import template export: factoryId={} target={} format={} file={}",
                factoryId, targetSystem, format, fileName);
    }

    private String contentTypeFor(VoucherExportFileFormat format) {
        return switch (format) {
            // .dbf 是二进制交换文件, 用通用 octet-stream 让浏览器直接下载
            case DBF -> "application/octet-stream";
            // .xls (BIFF) 金蝶 KIS专业版
            case XLS -> "application/vnd.ms-excel";
            // .xlsx 云星空
            case XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }

    @GetMapping("/chronological-ledger/export")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportChronologicalLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportChronologicalLedger(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] chronological export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/general-ledger/export")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportGeneralLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportGeneralLedger(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] general ledger export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/subsidiary-ledger/export")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportSubsidiaryLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportSubsidiaryLedger(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] subsidiary ledger export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/trial-balance/export")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportTrialBalance(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportTrialBalance(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[Ledger] trial balance export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/income-statement/export")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportIncomeStatement(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportIncomeStatement(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[FinanceReport] income statement export: factoryId={} file={}", factoryId, fileName);
    }

    @GetMapping("/quantity-amount-ledger/export")
    @RowScopeSupported
    @RequireModule("finance")
    @RequirePermission({"finance:read"})
    public void exportQuantityAmountLedger(
            @PathVariable String factoryId,
            @RequestParam String startDate,
            @RequestParam String endDate,
            @RequestParam(defaultValue = "KINGDEE") VoucherTargetSystem targetSystem,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {

        Long userId = currentUserId(request);
        VoucherExportRequestDTO req = buildRequest(startDate, endDate, targetSystem);
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        String fileName = runScoped(factoryId, response, () -> voucherExportService.exportQuantityAmountLedger(
                factoryId, req, userId, response.getOutputStream()));
        setAttachmentHeader(response, fileName);
        response.flushBuffer();
        log.info("[FinanceReport] quantity amount ledger export: factoryId={} file={}", factoryId, fileName);
    }

    private Long currentUserId(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute("userId");
        return userId == null ? 0L : userId;
    }

    private VoucherExportRequestDTO buildRequest(String startDate, String endDate, VoucherTargetSystem targetSystem) {
        return VoucherExportRequestDTO.builder()
                .startDate(java.time.LocalDate.parse(startDate))
                .endDate(java.time.LocalDate.parse(endDate))
                .targetSystem(targetSystem)
                .build();
    }

    private void setAttachmentHeader(HttpServletResponse response, String fileName) {
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8));
    }

    private String runScoped(String factoryId, HttpServletResponse response, Callable<String> operation) throws Exception {
        List<Long> creators = BusinessRowScope.creators(businessRowScope, factoryId);
        List<String> ids = null;
        if (creators != null) {
            if (voucherRowScopeQuery == null)
                throw new com.cretas.aims.exception.BusinessException(503, "财务行级范围组件未就绪，访问已停止");
            response.setHeader("X-Finance-Row-Scope", "RESTRICTED_SOURCE_OWNER_PROJECTION");
            response.setHeader("X-Finance-Row-Scope-Note",
                    "Visible source-owner vouchers only; unknown or damaged sources excluded; not a complete statutory report");
        }
        try (VoucherRowScopeContext ignored = VoucherRowScopeContext.open(ids, creators)) {
            return operation.call();
        }
    }
}
