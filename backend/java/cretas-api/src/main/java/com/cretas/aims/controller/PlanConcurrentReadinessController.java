package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.production.PlanConcurrentReadinessDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.RowScopeSupported;
import com.cretas.aims.service.production.PlanConcurrentReadinessService;
import com.cretas.aims.utils.FactoryAccessValidator;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@Validated
@RestController
@RequiredArgsConstructor
@RowScopeSupported
@RequireModule("production_plan")
@RequestMapping("/api/mobile/{factoryId}/production-plans")
public class PlanConcurrentReadinessController {
    private final FactoryAccessValidator access;
    private final ProductionPlanRepository plans;
    private final BusinessRowScope businessRowScope;
    private final PlanConcurrentReadinessService readiness;

    @GetMapping("/{planId}/concurrent-readiness")
    @ProcessSecretGuard(action = ProcessSecretAction.VIEW_FULL, from = ProcessSecretGuard.Source.PLAN_PATH, name = "planId")
    @RequirePermission({"production:read", "production:read_write", "scheduling:read", "scheduling:read_write"})
    public ApiResponse<PlanConcurrentReadinessDTO> read(@PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId) {
        access.validateAccess(factoryId);
        // Partial creator scopes cannot safely expose or omit hidden competing demand.
        if (BusinessRowScope.creators(businessRowScope, factoryId) != null) {
            throw new BusinessException(403, "同期需求统筹需要全厂生产数据范围")
                    .withHint("请联系管理员核对账号的生产数据范围");
        }
        var plan = plans.findByIdAndFactoryId(planId, factoryId).filter(p -> p.getDeletedAt() == null)
                .orElseThrow(() -> new BusinessException(403, "无权访问该计划"));
        return ApiResponse.success("查询成功", readiness.read(factoryId, planId));
    }
}
