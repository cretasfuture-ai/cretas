package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldEstimateDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.dto.orchestration.BomTreeResult;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.bom.BomYieldEstimateService;
import com.cretas.aims.service.orchestration.RecursiveBomExpansionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * BOM成本管理控制器
 * 提供BOM物料清单和成本配置管理接口
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-13
 */
/**
 * Bug #318 fix: method-level @RequirePermission on write methods only.
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/bom")
@RequiredArgsConstructor
@Tag(name = "BOM成本管理", description = "BOM物料清单和成本配置管理")
@RequireModule("bom")
public class BomController {

    private final BomService bomService;
    private final RecursiveBomExpansionService recursiveBomExpansionService;
    private final BomYieldEstimateService bomYieldEstimateService;
    private final com.cretas.aims.service.bom.WorkflowBomExportService workflowBomExportService;

    /** P1-9 BOM 变更痕迹查询 Repository (可选注入, 老环境未部署 migration 时不阻塞) */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomChangeLogRepository bomChangeLogRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private BomYieldSuggestionRepository bomYieldSuggestionRepository;

    // ========== BOM Items (原辅料配方) ==========

    /**
     * M-MATTREE-1 (Sprint 4 W2): 多级 BOM 树展开 + 叶子节点库存短缺计算.
     *
     * <p>基于当前 ACTIVE/current BomRecipe 递归展开 sub-BOM
     * (半成品 → 原料), 返回完整树结构 + maxDepth / leafCount / shortfallLeafCount 统计 +
     * 循环检测 (cycleDetected + cycleTypeIds)。</p>
     */
    @GetMapping("/tree/{productTypeId}")
    @Operation(summary = "多级 BOM 树展开 (M-MATTREE-1)",
            description = "递归展开成品 BOM 到原料叶子, 叶子节点附加库存可用量 + 短缺数量")
    public ApiResponse<BomTreeResult> getBomTree(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID (树根)") String productTypeId,
            @RequestParam(value = "quantity", defaultValue = "1")
            @Parameter(description = "计划生产数量 (默认 1)") java.math.BigDecimal quantity) {
        log.info("Expanding BOM tree: factoryId={}, productTypeId={}, quantity={}",
                factoryId, productTypeId, quantity);
        return ApiResponse.success(
                recursiveBomExpansionService.expandTree(factoryId, productTypeId, quantity));
    }

    /**
     * P1-9 BOM 变更痕迹查询 (v1 §2.2.6).
     * 返回指定产品 BOM 的所有变更历史 (CREATE/UPDATE/DELETE), 按时间倒序.
     */
    @GetMapping("/change-logs/{recipeId}")
    @Operation(summary = "获取 BOM 变更痕迹 (P1-9)")
    public ApiResponse<List<BomChangeLog>> getBomChangeLogs(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "BOM Recipe ID") String recipeId) {
        if (bomChangeLogRepository == null) {
            return ApiResponse.success(java.util.Collections.emptyList());
        }
        return ApiResponse.success(bomChangeLogRepository
                .findByFactoryIdAndBomRecipeIdAndDeletedAtIsNullOrderByCreatedAtDesc(factoryId, recipeId));
    }

    // ========== Cost Calculation (成本计算) ==========

    @GetMapping("/cost-summary/{productTypeId}")
    @Operation(summary = "计算产品成本汇总")
    public ApiResponse<BomCostSummaryDTO> calculateCost(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "产品类型ID") String productTypeId) {
        log.info("Calculating product cost: factoryId={}, productTypeId={}", factoryId, productTypeId);
        return ApiResponse.success(bomService.calculateProductCost(factoryId, productTypeId));
    }

    @RequirePermission({"production:read_write", "rd:read_write", "finance:read_write"})
    @PostMapping("/cost-summary/batch")
    @Operation(summary = "批量计算产品成本")
    public ApiResponse<List<BomCostSummaryDTO>> calculateCostsBatch(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody List<String> productTypeIds) {
        log.info("Calculating batch product costs: factoryId={}, count={}", factoryId, productTypeIds.size());
        return ApiResponse.success(bomService.calculateProductCosts(factoryId, productTypeIds));
    }

    @GetMapping("/products-with-bom")
    @Operation(summary = "获取有BOM配置的产品列表")
    public ApiResponse<List<String>> getProductTypesWithBom(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.info("Getting product types with BOM: factoryId={}", factoryId);
        return ApiResponse.success(bomService.getProductTypesWithBom(factoryId));
    }

    // ========== BOM 出成率评估 (yield-estimate) ==========

    /**
     * 单产品出成率评估 (只读).
     * GET /api/mobile/{factoryId}/bom/yield-estimate?productTypeId={id}&materialCategory=RAW
     */
    @GetMapping("/yield-estimate")
    @Operation(summary = "BOM 出成率评估 (单产品)",
            description = "基于最近 10 个已完成批次的 cumulativeYieldRate 中位数推荐出成率. 样本 <3 时返回 null + reason.")
    public ApiResponse<BomYieldEstimateDTO> getYieldEstimate(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "产品类型ID") String productTypeId,
            @RequestParam(defaultValue = "RAW") @Parameter(description = "物料分类 (默认 RAW)") String materialCategory) {
        log.info("[BomYieldEstimate] estimate: factoryId={}, productTypeId={}, materialCategory={}",
                factoryId, productTypeId, materialCategory);
        return ApiResponse.success(
                bomYieldEstimateService.estimateForProduct(factoryId, productTypeId, materialCategory));
    }

    /**
     * 批量预览 (只读, 不写).
     * POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-preview
     * body: {} 或 { productTypeIds: [...] }
     */
    @PostMapping("/yield-estimate/recalculate-preview")
    @Operation(summary = "BOM 出成率批量预览 (只读)",
            description = "对全部或指定产品评估出成率, 返回 UPDATABLE/INSUFFICIENT_SAMPLES/SKIP 三种状态行. 不修改任何数据.")
    public ApiResponse<List<BomYieldPreviewItemDTO>> recalculatePreview(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        @SuppressWarnings("unchecked")
        List<String> productTypeIds = (body != null && body.containsKey("productTypeIds"))
                ? (List<String>) body.get("productTypeIds")
                : Collections.emptyList();
        log.info("[BomYieldEstimate] preview: factoryId={}, productTypeIds size={}",
                factoryId, productTypeIds == null ? 0 : productTypeIds.size());
        return ApiResponse.success(
                bomYieldEstimateService.recalculatePreview(factoryId,
                        (productTypeIds != null && !productTypeIds.isEmpty()) ? productTypeIds : null));
    }

    /**
     * 工艺 / BOM 导出 Excel (只读)。
     * GET /api/mobile/{factoryId}/bom/workflow-export?productTypeIds=a,b
     *
     * <p>会上被问了三次 ({@code [14:18]}/{@code [14:48]}/{@code [28:07]}):
     * 「好多客户他是要一个这种 Excel 表格，自己作为线下的存档备份」。</p>
     *
     * <p>⛔ 只读投影, 不接受回写 —— 会上还提过「再复制一个模块」,
     * 那会做出两份可编辑的配方, 而同一个东西有两份一定会漂。</p>
     */
    @GetMapping("/workflow-export")
    @Operation(summary = "工艺/BOM 导出 Excel (只读)",
            description = "把工艺画布与 BOM 摊成一张平表: 成品/工序号/工序/投入产出/物料/标准用量/份额%/单位/标准出成率/工艺版本/备注。"
                    + "只导已发布的工艺版本 (草稿不导)。算不出的项留空并在备注写明, 不填 0。")
    public void exportWorkflowBom(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "成品ID, 逗号分隔; 不传=全厂") List<String> productTypeIds,
            jakarta.servlet.http.HttpServletResponse response) throws java.io.IOException {
        log.info("[WorkflowBomExport] factoryId={}, productTypeIds size={}",
                factoryId, productTypeIds == null ? 0 : productTypeIds.size());
        byte[] data = workflowBomExportService.export(factoryId, productTypeIds);
        String filename = java.net.URLEncoder.encode(
                "工艺与BOM.xlsx", java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"workflow-bom.xlsx\"; filename*=UTF-8''" + filename);
        response.getOutputStream().write(data);
        response.getOutputStream().flush();
    }

    /**
     * 批量应用用户选中行 (写操作, 需 bom:yield_standard:write).
     * POST /api/mobile/{factoryId}/bom/yield-estimate/recalculate-apply
     * body: [ { recipeId, yieldRate }, ... ]
     *
     * <p>🔴 2026-09-05: 权限从 {@code production:read_write} 收窄到
     * {@code bom:yield_standard:write}。前者实测<b>9 个角色</b>都有 (含 operator /
     * yield_operator / 班组长) —— 用它守「标准出成率」等于没有门。白名单与理由见
     * {@code PermissionServiceImpl#YIELD_STANDARD_WRITE_ROLES}。
     *
     * <p>这是标准出成率<b>唯一</b>的写入口 (自动回写已于同日删除, 见
     * {@code BomYieldSuggestionServiceImpl#generateForProduct} 的说明)。
     */
    @RequirePermission({"bom:yield_standard:write"})
    @PostMapping("/yield-estimate/recalculate-apply")
    @Operation(summary = "BOM 出成率批量应用 (写)",
            description = "将用户选中的出成率写入 bom_recipes.overall_yield_rate, 每个配方记录 BomChangeLog. "
                    + "需 bom:yield_standard:write (仅超管; production:read_write 不再够)。")
    public ApiResponse<BomYieldApplyResultDTO> recalculateApply(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @Valid @RequestBody List<@Valid BomYieldApplyRequest> requests) {
        log.info("[BomYieldEstimate] apply: factoryId={}, requests count={}", factoryId, requests.size());
        BomYieldApplyResultDTO result = bomYieldEstimateService.recalculateApply(factoryId, requests);
        return ApiResponse.success(
                "已更新 " + result.getApplied() + " 条出成率配置", result);
    }

    @GetMapping("/yield-suggestions")
    @Operation(summary = "BOM 出成率自学习建议列表",
            description = "只读展示 PENDING/APPLIED/REJECTED 建议。生成建议不写 BOM —— "
                    + "⚠️ 这句话在 2026-09-05 之前是假的: 当时 BomYieldSuggestionServiceImpl "
                    + "会自动把建议写回 bom_recipes.overall_yield_rate (生产上改过 12 次, "
                    + "appliedBy=SYSTEM), 而这段描述一直写着不会覆盖, 没人去核。自动回写已删除, "
                    + "现在它才成立。要改标准只有一条路: recalculate-apply (需 bom:yield_standard:write)。")
    public ApiResponse<List<BomYieldSuggestion>> listYieldSuggestions(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(required = false) @Parameter(description = "状态: PENDING/APPLIED/REJECTED; 空返回全部") String status) {
        if (bomYieldSuggestionRepository == null) {
            return ApiResponse.success(Collections.emptyList());
        }
        if (status == null || status.isBlank()) {
            return ApiResponse.success(
                    bomYieldSuggestionRepository.findByFactoryIdAndDeletedAtIsNullOrderByGeneratedAtDesc(factoryId));
        }
        BomYieldSuggestion.Status parsed = BomYieldSuggestion.Status.valueOf(status.trim().toUpperCase());
        return ApiResponse.success(
                bomYieldSuggestionRepository.findByFactoryIdAndStatusAndDeletedAtIsNullOrderByGeneratedAtDesc(
                        factoryId, parsed));
    }
}
