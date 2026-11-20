package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.yield.costview.LaborProcessAllocator;
import com.cretas.aims.service.yield.costview.ProductCostViewService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 成本新视图 (口径 工单材料人工口径, 与现有 perBoxCost 并存, 不替换)。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production")
@Tag(name = "产品成本视图", description = "产品→工单加权单位成本 / 成本信号 / 工序人工分摊")
@RequiredArgsConstructor
@RequireModule("production_report")
public class ProductCostViewController {

    private final ProductCostViewService service;
    private final PriceMaskResolver priceMaskResolver;

    @RequirePermission({"production:read"})
    @GetMapping("/cost-summary/by-product")
    @Operation(summary = "产品→工单汇总与同产品加权单位成本 (含成本信号)")
    public ApiResponse<ProductCostViewService.ByProductResult> byProduct(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(service.byProduct(factoryId, startDate, endDate,
                priceMaskResolver.shouldMaskPrice(authorization)));
    }

    @RequirePermission({"production:read"})
    @GetMapping("/orders/{orderId}/labor-allocation")
    @Operation(summary = "工单人工按有效人时分摊到工序 (尾差归最后一道有人时的工序)")
    public ApiResponse<LaborProcessAllocator.Result> laborAllocation(
            @PathVariable String factoryId, @PathVariable String orderId,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        return ApiResponse.success(service.laborAllocation(factoryId, orderId,
                priceMaskResolver.shouldMaskPrice(authorization)));
    }
}
