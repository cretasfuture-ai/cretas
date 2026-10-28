package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import com.cretas.aims.dto.processentry.ProcessSheetRowHistoryView;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.service.processentry.ProductionStockAllocationService;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.processentry.ProcessSheetRowView;
import com.cretas.aims.dto.processentry.ProductionStockShortageDTO;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.processentry.ProductionStockShortageException;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.security.BusinessRowScope;
import com.cretas.aims.security.RowScopeSupported;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * SP-F 逐工序电子表格端点 (spec §4)。
 * 每行 upsert 一次 (saveRow)，支持软删 (deleteRow)。
 * 读端点 (inventory、rows 列表) 见后续任务。
 */
@RestController
@RequestMapping("/api/mobile/{factoryId}/production-plans/{planId}/process-sheet")
@RequiredArgsConstructor
@RowScopeSupported
public class ProcessSheetController {

    private final ProcessSheetService service;
    private final WorkflowClerkSheetService workflowClerkSheetService;
    private final ProductionStockAllocationService stockAllocationService;

    /**
     * This controller is a nested production-plan entry point, so the route
     * interceptor allows it through and the plan owner is checked here before
     * any row id or stock operation is accepted. Optional injection preserves
     * the existing standalone controller tests that do not build Spring.
     */
    @Autowired(required = false)
    private BusinessRowScope businessRowScope;
    @Autowired(required = false)
    private ProductionPlanRepository productionPlanRepository;
    /** 缺口 13 (审查中 7): 正式报工与 CCP 实测值同一事务 (挂 CCP 的工序缺值整笔拒绝)。 */
    @Autowired(required = false)
    private com.cretas.aims.service.foodsafety.ccp.CcpReportFacade ccpReportFacade;

    private void requirePlanScope(String factoryId, String planId) {
        if (productionPlanRepository == null) return;
        ProductionPlan plan = productionPlanRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(403, "无权访问该计划"));
        BusinessRowScope.requireOwner(businessRowScope, factoryId, plan.getCreatedBy());
    }

    @RequirePermission({"production:read_write"})
    @PostMapping("/row")
    public ApiResponse<ProcessSheetRowResult> saveRow(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username,
            @Valid @RequestBody ProcessSheetRowRequest req) {
        requirePlanScope(factoryId, planId);
        if (ccpReportFacade != null) {
            return ApiResponse.success("保存成功",
                    ccpReportFacade.saveProcessSheetRow(factoryId, planId, userId, username, req));
        }
        return ApiResponse.success("保存成功", service.saveRow(factoryId, planId, req, userId));
    }

    /** 新报工契约：草稿保存永不分摊或扣减生产库。 */
    @RequirePermission({"production:read_write"})
    @PostMapping("/row/draft")
    public ApiResponse<ProcessSheetRowResult> saveDraft(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestAttribute("userId") Long userId,
            @Valid @RequestBody ProcessSheetRowRequest req) {
        requirePlanScope(factoryId, planId);
        if (req.getPots() != null && !req.getPots().isEmpty()) {
            // S3-16: 锅次只随正式报工落库; 草稿静默丢掉锅次会让人以为已经记了
            throw new com.cretas.aims.exception.BusinessException(400, "草稿不保存锅次")
                    .withCode("POT_NOT_IN_DRAFT")
                    .withHint("请在正式报工时一起提交锅次, 或报工后在生产批次详情里补录");
        }
        return ApiResponse.success("草稿保存成功", service.saveDraft(factoryId, planId, req, userId));
    }

    /** 新报工契约：正式提交并自动分摊生产库批次。 */
    @RequirePermission({"production:read_write"})
    @PostMapping("/row/submit")
    public ResponseEntity<ApiResponse<?>> submitRow(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestAttribute("userId") Long userId,
            @RequestAttribute(value = "username", required = false) String username,
            @Valid @RequestBody ProcessSheetRowRequest req) {
        requirePlanScope(factoryId, planId);
        try {
            return ResponseEntity.ok(ApiResponse.success(
                    "正式提交成功", ccpReportFacade != null
                            ? ccpReportFacade.submitProcessSheetRow(factoryId, planId, userId, username, req)
                            : service.submitRow(factoryId, planId, req, userId)));
        } catch (ProductionStockShortageException error) {
            ApiResponse<ProductionStockShortageDTO> response = ApiResponse.of(
                    409, error.getMessage(), error.getShortage());
            response.setErrorCode(error.getErrorCode());
            response.setActionHint(error.getActionHint());
            response.setSeverity(error.getSeverity());
            response.setHintTarget(error.getHintTarget());
            return ResponseEntity.status(409).body(response);
        }
    }

    @RequirePermission({"production:read_write"})
    @DeleteMapping("/row/{clientRowId}")
    public ApiResponse<Void> deleteRow(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @PathVariable @NotBlank String clientRowId,
            @RequestAttribute("userId") Long userId) {
        requirePlanScope(factoryId, planId);
        service.deleteRow(factoryId, planId, clientRowId, userId);
        return ApiResponse.success("删除成功", null);
    }

    /**
     * SP-F Task 2.1: 读取指定工序的 WIP 在制品库存视图。
     *
     * <p>只读端点 — 权限 "production:read"，与 OrderCostBreakdownController / OrderYieldController
     * 等生产读端点一致 (比写端点 "production:read_write" 宽松一级，让只读角色也可访问)。
     *
     * <p>URL 示例: GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/inventory?process=chaoshui&processOrder=3
     *
     * <p>processOrder (可选): SP-F role-mode fix —— role-mode 下多道普通工序共享同一 archetype
     * process_code (如 'chaoshui'), 传 processOrder (链内唯一) 隔离各道库存; 不传则 code-only 回退。
     */
    /**
     * 报工投入端口在**生产仓**的当前可投量 (只读, 供录入行显示边界)。
     *
     * <p>客户 2026-07-31: 界面显示「可用 10kg」而提交时后端说「可用 0kg」。根因是前端自己算了
     * 一遍, 而后端口径含三样它拿不到的东西 —— 只认一个生产仓、要扣其它草稿行已占用的量、
     * 还要过客供料归属守卫。所以这里由后端返回权威值, 前端只负责显示。</p>
     *
     * <p>响应里的 {@code elsewhere} 是同物料在别的仓的存量, 用来把**「真没货」和「有货但没调
     * 过来」**分开 —— 刘山门实测生产仓里一条原料都没有 (全是 WIP), 原料都在主仓。</p>
     *
     * <p>权限与同 Controller 其它只读接口一致 ({@code production:read}) —— 能打开这个报工屏,
     * 就该看得到自己要填的那几个数。</p>
     */
    @RequirePermission({"production:read"})
    @PostMapping("/input-availability")
    public ApiResponse<List<ProductionStockAllocationService.PortAvailability>> getInputAvailability(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestBody List<ProcessSheetRowRequest.MaterialInputTotal> ports) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success(stockAllocationService.availability(factoryId, planId, ports));
    }

    @RequirePermission({"production:read"})
    @GetMapping("/inventory")
    public ApiResponse<List<ProcessSheetInventoryItem>> getInventory(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestParam @NotBlank String process,
            @RequestParam(required = false) Integer processOrder) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success(service.getInventory(factoryId, planId, process, processOrder));
    }

    /**
     * SP-F Task 2.2: 读回指定工序下已保存的行列表 (供前端电子表格重载)。
     *
     * <p>URL 示例: GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/rows?process=chaoshui&processOrder=3
     *
     * <p>processOrder (可选): 同 inventory —— role-mode 下隔离同 archetype 多工序的行; 不传则 code-only 回退。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/rows")
    public ApiResponse<List<ProcessSheetRowView>> getRows(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @RequestParam @NotBlank String process,
            @RequestParam(required = false) Integer processOrder) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success(service.getRows(factoryId, planId, process, processOrder));
    }

    /**
     * F006 双出成率: 计划级半成品库存卡 (跨工序汇总视图, 含 stepYieldRate / cumulativeYieldRate)。
     *
     * <p>URL 示例: GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/inventory/yield-card
     *
     * <p>无需 ?process= 参数 — 返回该计划所有工序的 WIP 行, 按 processOrder 升序。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/inventory/yield-card")
    public ApiResponse<List<ProcessSheetInventoryItem>> getInventoryYieldCard(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success(service.getInventoryYieldCard(factoryId, planId));
    }

    /**
     * SP-G P3: 读取某一行的操作记录时间线 (行级 diff 审计)。
     *
     * <p>按 (factory, plan, processCode, clientRowId) 定位行的全部变更, 时间倒序 (最新在前)。
     *
     * <p>URL 示例:
     * GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/row/{clientRowId}/history?process=xiuyou
     */
    @RequirePermission({"production:read"})
    @GetMapping("/row/{clientRowId}/history")
    public ApiResponse<List<ProcessSheetRowHistoryView>> getRowHistory(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId,
            @PathVariable @NotBlank String clientRowId,
            @RequestParam @NotBlank String process) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success(service.getRowHistory(factoryId, planId, process, clientRowId));
    }

    /**
     * 2B Task B2: 该计划关联的 workflow 批次快照投影 (供文员过程单 {@code resolveProcesses()} 消费)。
     *
     * <p>URL 示例: GET /api/mobile/{factoryId}/production-plans/{planId}/process-sheet/workflow-config
     *
     * <p>计划没有 workflow 批次 (legacy 计划) 时 {@code data} 为 {@code null}, FE 据此回落原
     * {@code getProductWorkProcesses} 路径 — additive, 不改变 legacy 行为。
     */
    @RequirePermission({"production:read"})
    @GetMapping("/workflow-config")
    public ApiResponse<WorkflowClerkSheetConfigDTO> getWorkflowConfig(
            @PathVariable @NotBlank String factoryId,
            @PathVariable @NotBlank String planId) {
        requirePlanScope(factoryId, planId);
        return ApiResponse.success(workflowClerkSheetService.getWorkflowSheetConfig(factoryId, planId));
    }
}
