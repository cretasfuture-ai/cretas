package com.cretas.aims.controller.sales;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewRow;
import com.cretas.aims.service.inventory.CustomerPriceOverviewService;
import com.cretas.aims.utils.FactoryAccessValidator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 跨客户的客户价目表 (客户 × 产品, 带最近成交价 / 平均成交价 / 规格)。只读;
 * 价格列由 PriceFieldResponseAdvice 按权限配置隐藏, 与单客户价目页同一口径。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/customer-prices")
@RequiredArgsConstructor
@Tag(name = "客户价目总览")
public class CustomerPriceOverviewController {

    private final CustomerPriceOverviewService service;
    private final FactoryAccessValidator factoryAccessValidator;

    @RequirePermission({"sales:read", "sales:read_write"})
    @GetMapping
    @Operation(summary = "跨客户的客户价目表 (含最近/平均成交价)")
    public ApiResponse<PageResponse<CustomerPriceOverviewRow>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        factoryAccessValidator.validateAccess(factoryId);
        return ApiResponse.success("查询成功", service.list(factoryId, keyword, page, size));
    }
}
