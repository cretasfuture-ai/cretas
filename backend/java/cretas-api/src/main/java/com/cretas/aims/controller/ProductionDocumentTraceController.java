package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.ProductionDocumentTraceResponse;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.RowScopeSupported;
import com.cretas.aims.service.production.ProductionDocumentTraceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans")
@RequiredArgsConstructor
@RowScopeSupported
@Tag(name = "生产单据追踪", description = "按生产计划追踪真实关联业务单据")
public class ProductionDocumentTraceController {

    private final ProductionDocumentTraceService traceService;

    @Autowired(required = false)
    private BusinessRowScope businessRowScope;
    @Autowired(required = false)
    private ProductionPlanRepository productionPlanRepository;

    private void requirePlanScope(String factoryId, String planId) {
        if (productionPlanRepository == null) return;
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(403, "无权访问该计划"));
        BusinessRowScope.requireOwner(businessRowScope, factoryId, plan.getCreatedBy());
    }

    @GetMapping("/{planId}/document-trace")
    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    @Operation(summary = "生产计划关联单据追踪")
    public ApiResponse<ProductionDocumentTraceResponse> trace(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success("查询成功", traceService.trace(factoryId, planId));
    }
}
