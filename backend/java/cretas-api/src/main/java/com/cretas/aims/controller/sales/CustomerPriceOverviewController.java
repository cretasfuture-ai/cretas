package com.cretas.aims.controller.sales;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewRow;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.PriceScope;
import com.cretas.aims.security.PriceSensitive;
import com.cretas.aims.security.RowScopeSupported;
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
 * 按客户归属查询价目，并按来源销售单归属统计成交价。价格列遵循配置的销售读权限。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/customer-prices")
@RequiredArgsConstructor
@Tag(name = "客户价目总览")
public class CustomerPriceOverviewController {

    private final CustomerPriceOverviewService service;
    private final FactoryAccessValidator factoryAccessValidator;
    private final BusinessRowScope businessRowScope;

    @RequirePermission({"sales:read", "sales:read_write"})
    @PriceScope(PriceSensitive.SALES)
    @RowScopeSupported
    @GetMapping
    @Operation(summary = "跨客户的客户价目表 (含最近/平均成交价)")
    public ApiResponse<PageResponse<CustomerPriceOverviewRow>> list(
            @PathVariable @NotBlank String factoryId,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int size) {
        factoryAccessValidator.validateAccess(factoryId);
        return ApiResponse.success("查询成功",
                service.list(factoryId, keyword, page, size, businessRowScope.recordCreatorIds(factoryId)));
    }
}
