package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.decision.DecisionSnapshotComparison;
import com.cretas.aims.service.DecisionSnapshotComparisonService;
import com.cretas.aims.utils.FactoryAccessValidator;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/decision-history")
@RequireRole({"factory_super_admin", "permission_admin"})
@RequirePermission({"system:read"})
public class DecisionHistoryController {
    private final FactoryAccessValidator factoryAccess;
    private final DecisionSnapshotComparisonService comparison;

    @GetMapping("/{decisionId}/comparison")
    @Operation(summary = "对照同输入的历史决策记录", description = "仅作持久化记录的语义对照，不执行规则、审批或业务动作。校验和不匹配时主状态为不可比较，记录语义关系单独返回，不能作为完整性已验证的证据")
    public ApiResponse<DecisionSnapshotComparison> compare(
            @PathVariable String factoryId, @PathVariable String decisionId,
            @RequestParam String baselineId) {
        factoryAccess.validateAccess(factoryId);
        return ApiResponse.success(comparison.compare(factoryId, decisionId, baselineId));
    }
}
