package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.decision.ProposalComparisonRequest;
import com.cretas.aims.dto.decision.ProposalComparisonResult;
import com.cretas.aims.service.ProposalComparisonService;
import com.cretas.aims.utils.FactoryAccessValidator;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/proposal-comparisons")
@RequireRole({"factory_super_admin", "permission_admin"})
@RequirePermission({"system:read"})
public class ProposalComparisonController {
    private final FactoryAccessValidator factoryAccess;
    private final ProposalComparisonService comparison;

    @PostMapping("/evaluate")
    @Operation(summary = "对照同口径候选方案的成本、交期与资源取舍", description = "仅计算调用方提供的未验证事实；最小化成本、完成时间和资源需求，返回非支配集合而非排序或赢家。缺失/混合口径时不生成集合；不查业务数据、不执行方案或预留资源。POST不持久化任何业务数据。")
    public ApiResponse<ProposalComparisonResult> evaluate(@PathVariable String factoryId,
                                                         @RequestBody ProposalComparisonRequest request) {
        factoryAccess.validateAccess(factoryId);
        return ApiResponse.success(comparison.compare(request));
    }
}
