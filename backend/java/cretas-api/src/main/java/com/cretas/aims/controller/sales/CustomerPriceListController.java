package com.cretas.aims.controller.sales;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.CustomerPriceItemView;
import com.cretas.aims.dto.inventory.UpsertCustomerPriceItemRequest;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.CustomerPriceListService;
import com.cretas.aims.utils.FactoryAccessValidator;
import com.cretas.aims.utils.TokenUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 客户档案「价格」页: 列出 / 维护该客户的专属售价。权限按权限配置声明 (sales 读写),
 * 价格列由 PriceFieldResponseAdvice 按 procurement:price:view 配置隐藏, 不写死角色名单。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/customers/{customerId}/price-list")
@RequiredArgsConstructor
@Tag(name = "客户专属价目")
public class CustomerPriceListController {

    private final CustomerPriceListService service;
    private final MobileService mobileService;
    private final FactoryAccessValidator factoryAccessValidator;

    @RequirePermission({"sales:read", "sales:read_write"})
    @GetMapping
    @Operation(summary = "客户专属价目列表")
    public ApiResponse<List<CustomerPriceItemView>> list(@PathVariable @NotBlank String factoryId,
                                                         @PathVariable @NotBlank String customerId) {
        factoryAccessValidator.validateAccess(factoryId);
        return ApiResponse.success("查询成功", service.list(factoryId, customerId));
    }

    @RequirePermission({"sales:read_write"})
    @PutMapping("/items")
    @Operation(summary = "新增或修改客户专属价 (按产品)")
    public ApiResponse<CustomerPriceItemView> upsert(@PathVariable @NotBlank String factoryId,
                                                     @PathVariable @NotBlank String customerId,
                                                     @RequestHeader("Authorization") String authorization,
                                                     @Valid @RequestBody UpsertCustomerPriceItemRequest request) {
        factoryAccessValidator.validateAccess(factoryId);
        Long userId = mobileService.getUserFromToken(TokenUtils.extractToken(authorization)).getId();
        return ApiResponse.success("保存成功", service.upsert(factoryId, customerId, userId,
                request.getProductTypeId(), request.getStandardPrice()));
    }

    @RequirePermission({"sales:read_write"})
    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "删除客户专属价")
    public ApiResponse<Void> delete(@PathVariable @NotBlank String factoryId,
                                    @PathVariable @NotBlank String customerId,
                                    @PathVariable Long itemId) {
        factoryAccessValidator.validateAccess(factoryId);
        service.delete(factoryId, customerId, itemId);
        return ApiResponse.success("删除成功", null);
    }
}
