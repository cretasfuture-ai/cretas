package com.cretas.aims.controller;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.quality.ProcessParamDeviationDTO;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.service.quality.ProcessParamDeviationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * 工艺参数偏离汇总 (只读): 报工时落库的参数判定快照 (偏高 / 偏低) 按工序 + 参数汇总, 并可下钻到明细。
 * 入口页: web「质量 → 工艺参数偏离」({@code /quality/param-deviation})。
 *
 * <p>权限与 CCP 监控的读接口同一组 (生产 / 质量读权限任一), 租户来自路径 factoryId (拦截器已校验与 token 一致),
 * 查询全部带 factory_id。时间范围不传时取最近 30 天, 最大跨度 92 天。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/quality/param-deviation")
@RequiredArgsConstructor
@Tag(name = "工艺参数偏离", description = "报工工艺参数偏离汇总与明细 (只读)")
public class ProcessParamDeviationController {

    private final ProcessParamDeviationService service;

    @Operation(summary = "按工序+参数汇总: 填了该参数的报工次数 / 偏离次数 / 偏离率 (不受 status 筛选影响)")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @ProcessSecretGuard(action = ProcessSecretAction.VIEW_FULL,
            from = ProcessSecretGuard.Source.PRODUCT_PARAM, name = "productTypeId")
    @GetMapping("/summary")
    public ApiResponse<ProcessParamDeviationDTO.SummaryView> summary(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String processName,
            @RequestParam(required = false) String productTypeId,
            @RequestParam(required = false) String batchNumber) {
        return ApiResponse.success(service.summary(factoryId,
                new ProcessParamDeviationService.Filter(startDate, endDate, processName, productTypeId, batchNumber)));
    }

    @Operation(summary = "偏离明细 (分页, 新的在前); status = LOW 偏低 / HIGH 偏高, 不传则两者都要")
    @RequirePermission({"production:read", "production:read_write", "quality:read", "quality:read_write"})
    @ProcessSecretGuard(action = ProcessSecretAction.VIEW_FULL,
            from = ProcessSecretGuard.Source.PRODUCT_PARAM, name = "productTypeId")
    @GetMapping("/details")
    public ApiResponse<PageResponse<ProcessParamDeviationDTO.DetailRow>> details(
            @PathVariable String factoryId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String processName,
            @RequestParam(required = false) String productTypeId,
            @RequestParam(required = false) String batchNumber,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return ApiResponse.success(service.details(factoryId,
                new ProcessParamDeviationService.Filter(startDate, endDate, processName, productTypeId, batchNumber),
                status, page, size));
    }
}
