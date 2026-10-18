package com.cretas.aims.controller.inventory;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.inventory.ReturnOrderLineageDTO;
import com.cretas.aims.service.inventory.ReturnOrderLineageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.NotBlank;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/return-orders")
@Tag(name = "退货来源追踪", description = "退货行来源与退回库存的只读追踪")
public class ReturnOrderLineageController {

    private final ReturnOrderLineageService returnOrderLineageService;

    @GetMapping("/{returnOrderId}/stock-lineage")
    @Operation(summary = "查询退货行来源与退回库存")
    @RequirePermission({"sales:read", "sales:read_write", "warehouse:read", "warehouse:read_write",
            "quality:read", "quality:read_write", "finance:read", "finance:read_write"})
    public ApiResponse<ReturnOrderLineageDTO> getStockLineage(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String returnOrderId) {
        return ApiResponse.success("查询成功",
                returnOrderLineageService.get(factoryId, returnOrderId));
    }
}
