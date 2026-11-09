package com.cretas.aims.controller;

import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.config.RequireRole;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.shortage.CostDeviationCaseDetailRepository;
import com.cretas.aims.repository.shortage.ShortageCaseEventRepository;
import com.cretas.aims.repository.shortage.ShortageCaseRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.shortage.CostDeviationCaseWriter;
import com.cretas.aims.service.shortage.CostDeviationScanService;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成本 / 损耗异常: 异常 → 证据 → 影响 → 核查路径 → 负责人 → 处理结果。
 *
 * <p>与缺料同一种处理实例, 但入口分开: 缺料的读权限含仓库 / 销售, 这里只给财务、采购价、生产。
 * 无采购价查看权限时金额为 null 并带 priceMasked; 工艺保密产品对无授权调用者不列出, 详情 403。
 * 金额 null 且未脱敏 = 金额未知, 不是 0。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mobile/{factoryId}/cost-deviation-cases")
@Tag(name = "成本损耗异常", description = "成本差异 / 物料平衡偏离超阈值的处理实例")
public class CostDeviationCaseController {

    private final ShortageCaseRepository caseRepository;
    private final ShortageCaseEventRepository eventRepository;
    private final CostDeviationCaseDetailRepository detailRepository;
    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final UserRepository userRepository;
    private final PlanCostVarianceService varianceService;
    private final MaterialBalanceService balanceService;
    private final CostDeviationScanService scanService;
    private final CostDeviationCaseWriter writer;
    private final PriceMaskResolver priceMaskResolver;
    private final ProcessSecretLlmCostScope costScope;
    private final UnitContractService unitContractService;

    public record CaseView(String id, String sourceType, String sourceLabel, String planId, String planNumber,
                           String productName, String stage, String stageLabel, String ownerRole, Long ownerUserId,
                           String ownerName, BigDecimal metricPct, BigDecimal thresholdPct, BigDecimal amountVariance,
                           boolean priceMasked, int unknownItemCount, String summary, LocalDateTime belowThresholdAt,
                           String resolutionCode, String resolutionLabel, String resolutionNote,
                           LocalDateTime resolvedAt, LocalDateTime lastRefreshedAt, LocalDateTime createdAt) {
    }

    public record BatchView(String batchNumber, String status) {
    }

    /** 影响。amount / unitCostImpact 为 null 时看 amountNote。 */
    public record Impact(BigDecimal amount, String amountNote, BigDecimal unitCostImpact, String outputUnit,
                         int batchCount, BigDecimal unexplainedQty, String unexplainedUnit) {
    }

    public record ThresholdView(BigDecimal variancePct, BigDecimal balancePct) {
    }

    public record ThresholdRequest(BigDecimal variancePct, BigDecimal balancePct) {
    }

    public record AssignRequest(Long ownerUserId) {
    }

    public record ResolveRequest(CostDeviationCaseDetail.Resolution resolution, String note) {
    }

    @RequirePermission({"finance:read", "finance:read_write", "procurement:price:view", "production:read", "production:read_write"})
    @GetMapping("/thresholds")
    @Operation(summary = "告警阈值", description = "null = 未配置, 该类不告警")
    public ApiResponse<ThresholdView> getThresholds(@PathVariable String factoryId) {
        var t = scanService.thresholds(factoryId);
        return ApiResponse.success(new ThresholdView(t.variancePct(), t.balancePct()));
    }

    @RequireRole({"factory_super_admin", "permission_admin", "finance_manager"})
    @PutMapping("/thresholds")
    @Operation(summary = "设置告警阈值", description = "百分数, 0 < 阈值 <= 100; 传 null 清除")
    public ApiResponse<ThresholdView> putThresholds(@PathVariable String factoryId, @RequestBody ThresholdRequest body) {
        var t = scanService.saveThresholds(factoryId, body.variancePct(), body.balancePct());
        return ApiResponse.success(new ThresholdView(t.variancePct(), t.balancePct()));
    }

    @RequirePermission({"finance:read_write", "production:read_write"})
    @PostMapping("/scan")
    @Operation(summary = "按当前数据扫描一次", description = "近 30 天的计划; 没配阈值的那一类不扫")
    public ApiResponse<CostDeviationScanService.ScanResult> scan(@PathVariable String factoryId, HttpServletRequest http) {
        ShortageCaseActionController.actor(http, factoryId);
        return ApiResponse.success(scanService.scan(factoryId, "MANUAL_SCAN"));
    }

    @RequirePermission({"finance:read", "finance:read_write", "procurement:price:view", "production:read", "production:read_write"})
    @GetMapping
    @Operation(summary = "异常列表", description = "默认只看未关闭的, 按最近刷新排序")
    public ApiResponse<List<CaseView>> list(@PathVariable String factoryId,
                                            @RequestParam(defaultValue = "true") boolean openOnly,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "50") int size,
                                            @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean mask = priceMaskResolver.shouldMaskPrice(authorization);
        var rows = caseRepository.findForFactoryBySources(factoryId, openOnly, CostDeviationCaseWriter.SOURCES,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100))).getContent();
        Map<String, CostDeviationCaseDetail> details = detailRepository
                .findByCaseIdIn(rows.stream().map(ShortageCase::getId).toList()).stream()
                .collect(Collectors.toMap(CostDeviationCaseDetail::getCaseId, Function.identity()));
        List<CaseView> out = new ArrayList<>();
        for (ShortageCase c : rows) {
            CostDeviationCaseDetail d = details.get(c.getId());
            if (d == null || isHidden(factoryId, d.getPlanId())) continue;
            out.add(view(c, d, mask));
        }
        return ApiResponse.success("获取成功", out);
    }

    @RequirePermission({"finance:read", "finance:read_write", "procurement:price:view", "production:read", "production:read_write"})
    @GetMapping("/{caseId}")
    @Operation(summary = "异常详情", description = "证据 (差异构成 / 批次 / 工序)、影响、核查路径、留痕")
    public ApiResponse<Map<String, Object>> detail(@PathVariable String factoryId, @PathVariable String caseId,
                                                   @RequestHeader(value = "Authorization", required = false) String authorization) {
        boolean mask = priceMaskResolver.shouldMaskPrice(authorization);
        ShortageCase c = own(factoryId, caseId);
        CostDeviationCaseDetail d = detailRepository.findById(c.getId())
                .orElseThrow(() -> new BusinessException(404, "异常读数不存在"));
        if (isHidden(factoryId, d.getPlanId())) {
            throw new BusinessException(403, "该计划的产品工艺保密, 你没有查看其成本明细的授权")
                    .withCode("PROCESS_SECRET_COST_HIDDEN");
        }
        List<BatchView> batches = batchRepository.findByFactoryIdAndProductionPlanId(factoryId, d.getPlanId()).stream()
                .filter(b -> b.getStatus() != ProductionBatchStatus.CANCELLED)
                .map(CostDeviationCaseController::batchView).toList();

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("case", view(c, d, mask));
        body.put("batches", batches);
        // 证据按当前数据现算, 权限跟当前查看的人走; 算不出就说算不出, 不留空当没有
        PlanCostVarianceDTO variance = null;
        MaterialBalanceTableDTO.PlanBlock balance = null;
        try {
            if (c.getSourceType() == ShortageCase.SourceType.COST_VARIANCE) {
                variance = varianceService.computeForPlan(factoryId, d.getPlanId(), mask);
            } else {
                balance = balanceService.table(factoryId, null, null, d.getPlanId()).getPlans().stream()
                        .findFirst().orElse(null);
            }
        } catch (RuntimeException e) {
            log.warn("成本异常证据算不出 case={}: {}", caseId, e.getMessage());
            body.put("evidenceError", "证据暂时算不出: " + e.getMessage());
        }
        body.put("varianceEvidence", variance);
        body.put("balanceEvidence", balance);
        body.put("impact", impact(factoryId, d, variance, balance, batches.size(), mask));
        body.put("verifyPath", verifyPath(c.getSourceType(), factoryId, d.getPlanId(), balance));
        body.put("events", eventRepository.findByCaseIdOrderByIdAsc(c.getId()).stream()
                .map(ShortageCaseController.EventView::of).toList());
        return ApiResponse.success("获取成功", body);
    }

    @RequirePermission({"finance:read_write", "production:read_write"})
    @PostMapping("/{caseId}/owner")
    @Operation(summary = "指定负责人")
    public ApiResponse<Void> assign(@PathVariable String factoryId, @PathVariable String caseId,
                                    @RequestBody AssignRequest body, HttpServletRequest http) {
        if (body.ownerUserId() == null) throw new BusinessException(400, "请选择负责人");
        writer.assign(factoryId, caseId, body.ownerUserId(), ShortageCaseActionController.actor(http, factoryId));
        return ApiResponse.success("已指定", null);
    }

    @RequirePermission({"finance:read_write", "production:read_write"})
    @PostMapping("/{caseId}/start-verify")
    @Operation(summary = "开始核查", description = "要先有负责人")
    public ApiResponse<Void> startVerify(@PathVariable String factoryId, @PathVariable String caseId, HttpServletRequest http) {
        writer.startVerify(factoryId, caseId, ShortageCaseActionController.actor(http, factoryId));
        return ApiResponse.success("已开始核查", null);
    }

    @RequirePermission({"finance:read_write", "production:read_write"})
    @PostMapping("/{caseId}/resolve")
    @Operation(summary = "填写处理结果并关闭", description = "结果和说明必填")
    public ApiResponse<Void> resolve(@PathVariable String factoryId, @PathVariable String caseId,
                                     @RequestBody ResolveRequest body, HttpServletRequest http) {
        writer.resolve(factoryId, caseId, body.resolution(), body.note(), ShortageCaseActionController.actor(http, factoryId));
        return ApiResponse.success("已关闭", null);
    }

    private static BatchView batchView(ProductionBatch b) {
        return new BatchView(b.getBatchNumber(), b.getStatus() == null ? null : b.getStatus().name());
    }

    private Impact impact(String factoryId, CostDeviationCaseDetail d, PlanCostVarianceDTO v, MaterialBalanceTableDTO.PlanBlock b,
                          int batchCount, boolean mask) {
        BigDecimal amount = mask ? null : d.getAmountVariance();
        String note = mask ? "无采购价查看权限, 金额已隐藏"
                : amount == null ? "金额未知: 物料平衡只有数量, 没有单价口径" : null;
        BigDecimal unit = null;
        if (amount != null && v != null && v.getOutputQuantity() != null && v.getOutputQuantity().signum() > 0) {
            unit = amount.divide(v.getOutputQuantity(), 4, RoundingMode.HALF_UP);
        }
        BigDecimal qty = null;
        String qtyUnit = null;
        if (b != null) {
            for (MaterialBalanceTableDTO.Row r : b.getRows()) {
                if (r.getUnexplained() == null) continue;
                if (qtyUnit != null && !unitContractService.areEquivalent(factoryId, qtyUnit, r.getUnit())) { // 单位不同不相加
                    qty = null;
                    break;
                }
                qtyUnit = r.getUnit();
                qty = qty == null ? r.getUnexplained() : qty.add(r.getUnexplained());
            }
            if (qty == null) qtyUnit = null;
        }
        return new Impact(amount, note, unit, v == null ? null : v.getOutputUnit(), batchCount, qty, qtyUnit);
    }

    /** 核查路径只给方向, 不含金额; 成本差异按哪几项有差异决定先查什么。 */
    private List<String> verifyPath(ShortageCase.SourceType type, String factoryId, String planId,
                                    MaterialBalanceTableDTO.PlanBlock balance) {
        List<String> steps = new ArrayList<>();
        if (type == ShortageCase.SourceType.MATERIAL_BALANCE) {
            steps.add("到「工序投入产出对比」看偏离工序的投入、产出、副产和损耗");
            steps.add("核对这道工序的报工: 投入量、产出量和单位有没有填错");
            steps.add("确认损耗、副产、留样是否都登记了; 没登记的补上");
            if (balance != null && balance.getRows().stream().anyMatch(r -> r.getUnexplainedPct() == null)) {
                steps.add("有工序算不出差额: 先补投入或修正单位, 否则这些工序的损耗未知");
            }
            return steps;
        }
        PlanCostVarianceDTO v;
        try {
            v = varianceService.computeForPlan(factoryId, planId, false);
        } catch (RuntimeException e) {
            steps.add("到「成本差异」打开这张计划逐项核对");
            return steps;
        }
        if (nonZero(v.getPriceVariance())) steps.add("价格差: 核对领用批次的入库单价和开计划时的单价快照");
        if (nonZero(v.getUsageVariance())) steps.add("用量差: 核对领料记录和实际产出, 看是否多领、错领或产出少报");
        if (nonZero(v.getSubstituteVariance())) steps.add("替代料差: 确认替代料是否经过批准");
        if (nonZero(v.getLaborVariance())) steps.add("人工差: 核对工时和计件记录");
        if (nonZero(v.getReworkVariance())) steps.add("回料差: 核对回投的回料量和来源批次, 看回料比例是否超出产品上限");
        if (v.getIncomparableItemCount() > 0) {
            steps.add("有 " + v.getIncomparableItemCount() + " 项无法比较: 到「成本差异」看原因 (缺单价、缺标准用量或配方没有出成率)");
        }
        if (steps.isEmpty()) steps.add("到「成本差异」打开这张计划逐项核对");
        return steps;
    }

    private static boolean nonZero(BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    private boolean isHidden(String factoryId, String planId) {
        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId).orElse(null);
        return plan != null && costScope.isProtectedBatch(factoryId, plan.getProductTypeId(), planId);
    }

    private ShortageCase own(String factoryId, String caseId) {
        return caseRepository.findByIdAndFactoryIdAndSourceTypeIn(caseId, factoryId, CostDeviationCaseWriter.SOURCES)
                .orElseThrow(() -> new BusinessException(404, "异常不存在或不属于本厂"));
    }

    private CaseView view(ShortageCase c, CostDeviationCaseDetail d, boolean mask) {
        String owner = c.getOwnerUserId() == null ? null
                : userRepository.findByIdAndFactoryId(c.getOwnerUserId(), c.getFactoryId())
                        .map(u -> u.getFullName() != null ? u.getFullName() : u.getUsername()).orElse(null);
        boolean cost = c.getSourceType() == ShortageCase.SourceType.COST_VARIANCE;
        String stageLabel = switch (c.getStage()) {
            case EXECUTING -> "核查中";
            case CLOSED -> "已处理";
            default -> c.getStage().label();
        };
        return new CaseView(c.getId(), c.getSourceType().name(), cost ? "成本差异" : "物料损耗", d.getPlanId(),
                d.getPlanNumber(), d.getProductName(), c.getStage().name(), stageLabel, c.getOwnerRole(),
                c.getOwnerUserId(), owner, d.getMetricPct(), d.getThresholdPct(),
                mask ? null : d.getAmountVariance(), mask, d.getUnknownItemCount(), d.getSummary(),
                d.getBelowThresholdAt(), d.getResolutionCode() == null ? null : d.getResolutionCode().name(),
                d.getResolutionCode() == null ? null : d.getResolutionCode().label(), d.getResolutionNote(),
                d.getResolvedAt(), c.getLastRefreshedAt(), c.getCreatedAt());
    }
}
