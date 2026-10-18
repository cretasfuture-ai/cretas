package com.cretas.aims.controller;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.workflow.WorkflowStatsDTO;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.RowScopeSupported;
import com.cretas.aims.service.workflow.WorkflowStatsService;
import com.cretas.aims.service.workflow.WorkflowStatsRowScopeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

/**
 * 业务流程图导航 (U-NAV-1) — 工作流统计接口.
 *
 * <p>5 个 endpoint, 每个返回该 module 下的 3 节点 PENDING / IN_PROGRESS / DONE 计数.
 * Service 层 {@link WorkflowStatsService} 走 Spring Cache (workflowStats, 5 min),
 * 因此首次调用走 DB COUNT, 之后 5 分钟内走 Redis/Caffeine.
 *
 * <p>租户隔离: factoryId 来自 path variable, 全部 JPQL 已 WHERE factoryId = :factoryId.
 *
 * <p>不加 {@code @RequireModule} — 本接口聚合 5 模块, 任何启用模块的工厂都应可见全景.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/workflow-stats")
@RequiredArgsConstructor
@Tag(name = "业务流程统计", description = "首页/列表页节点流程图数据 (U-NAV-1)")
public class WorkflowStatsController {

    private final WorkflowStatsService workflowStatsService;

    /** Optional field preserves the existing one-argument constructor used by internal tests. */
    @Autowired(required = false)
    private WorkflowStatsRowScopeService workflowStatsRowScopeService;

    @Autowired(required = false)
    private BusinessRowScope businessRowScope;

    @GetMapping("/sales")
    @Operation(summary = "销售工作流统计", description = "返回销售订单 3 节点 (待审/进行中/已完成) 数量")
    public ApiResponse<WorkflowStatsDTO> getSalesStats(
            @Parameter(description = "工厂 ID", required = true, example = "F006")
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(workflowStatsService.getSalesStats(factoryId));
    }

    @GetMapping("/purchase")
    @Operation(summary = "采购工作流统计")
    public ApiResponse<WorkflowStatsDTO> getPurchaseStats(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(workflowStatsService.getPurchaseStats(factoryId));
    }

    @GetMapping("/production")
    @RowScopeSupported
    @Operation(summary = "生产工作流统计")
    public ApiResponse<WorkflowStatsDTO> getProductionStats(
            @PathVariable @NotBlank String factoryId) {
        List<Long> creatorIds = BusinessRowScope.creators(businessRowScope, factoryId);
        return ApiResponse.success(creatorIds == null
                ? workflowStatsService.getProductionStats(factoryId)
                : requiredRowScopeService().getProductionStats(factoryId, creatorIds));
    }

    @GetMapping("/finance")
    @RowScopeSupported
    @Operation(summary = "财务工作流统计", description = "复合 InvoiceRecord + PaymentRecord (待开票/待回款/已收款)")
    public ApiResponse<WorkflowStatsDTO> getFinanceStats(
            @PathVariable @NotBlank String factoryId) {
        List<Long> creatorIds = BusinessRowScope.creators(businessRowScope, factoryId);
        return ApiResponse.success(creatorIds == null
                ? workflowStatsService.getFinanceStats(factoryId)
                : requiredRowScopeService().getFinanceStats(factoryId, creatorIds));
    }

    private WorkflowStatsRowScopeService requiredRowScopeService() {
        if (workflowStatsRowScopeService == null)
            throw new com.cretas.aims.exception.BusinessException(503,"行级统计组件未就绪，访问已停止");
        return workflowStatsRowScopeService;
    }

    @GetMapping("/inventory")
    @Operation(summary = "库存工作流统计", description = "MaterialBatch 状态聚合 (需关注/使用中/可用)")
    public ApiResponse<WorkflowStatsDTO> getInventoryStats(
            @PathVariable @NotBlank String factoryId) {
        return ApiResponse.success(workflowStatsService.getInventoryStats(factoryId));
    }
}
