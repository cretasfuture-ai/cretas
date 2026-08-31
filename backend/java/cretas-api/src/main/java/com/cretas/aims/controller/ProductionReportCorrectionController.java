package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.ProductionReportCorrectionDTO;
import com.cretas.aims.dto.production.ProductionReportCorrectionRequest;
import com.cretas.aims.service.ProductionReportCorrectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/mobile/{factoryId}/production-report-corrections")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProductionReportCorrectionController {
    private final ProductionReportCorrectionService correctionService;

    @GetMapping("/correctable")
    @RequirePermission(value = "production_report_correction:read_write",
            message = "您没有报工核对修正权限，请联系管理员配置后再试")
    public ApiResponse<Page<Map<String, Object>>> listCorrectable(
            @PathVariable String factoryId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(correctionService.listCorrectable(factoryId,
                PageRequest.of(Math.max(0, page - 1), Math.min(100, Math.max(1, size)),
                        Sort.by(Sort.Direction.DESC, "createdAt"))));
    }

    @PostMapping("/reports/{reportId}")
    @RequirePermission(value = "production_report_correction:read_write",
            message = "您没有报工核对修正权限，请联系管理员配置后再试")
    public ApiResponse<ProductionReportCorrectionDTO> submit(
            @PathVariable String factoryId,
            @PathVariable Long reportId,
            @Valid @RequestBody ProductionReportCorrectionRequest request,
            @RequestAttribute("userId") Long userId) {
        return ApiResponse.success(correctionService.submit(factoryId, reportId, request, userId));
    }

    @GetMapping("/reports/{reportId}")
    @RequirePermission({"production:read_write", "work_report:read"})
    public ApiResponse<List<ProductionReportCorrectionDTO>> history(
            @PathVariable String factoryId, @PathVariable Long reportId) {
        return ApiResponse.success(correctionService.history(factoryId, reportId));
    }
}
