package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.controller.dto.indicator.ProductProductionMetricsResponse;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.indicator.strategy.impl.FactoryActualProductionMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/production/actual-metrics")
@RequireModule("production_report")
public class ProductProductionMetricsController {
    private final FactoryActualProductionMetrics metrics;
    private final PriceMaskResolver priceMaskResolver;

    @GetMapping("/by-product")
    @RequirePermission({"production:read"})
    public ApiResponse<ProductProductionMetricsResponse> byProduct(
            @PathVariable String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate periodEnd,
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (periodEnd.isBefore(periodStart) || periodEnd.isAfter(periodStart.plusDays(89))) {
            throw new BusinessException(400, "请选择不超过90天的有效统计期间");
        }
        return ApiResponse.success(metrics.byProduct(factoryId, periodStart, periodEnd,
                priceMaskResolver.shouldMaskOperationalCost(authorization)));
    }
}
