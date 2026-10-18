package com.cretas.aims.service.yield;

import com.cretas.aims.service.yield.bom.BatchBomBinding;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.SourceCost;
import com.cretas.aims.dto.yield.StepYieldDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.ProductionSettlementConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.ByproductLine;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.PackagingItem;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.LaborDetail;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.PackagingDetail;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO.RawMaterialDetail;
import com.cretas.aims.entity.bom.BomVersion;
import com.cretas.aims.repository.bom.BomVersionRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 订单级单盒成本拆分 — 单一权威服务 (T1 谱系遍历 + T2 上游成本回溯 + T3 人工归集).
 *
 * <p>原料成本沿 {@link MaterialConsumption} 边回溯: 若上游 {@link MaterialBatch} 由某生产批次产出
 * ({@code source_doc_type='PRODUCTION_BATCH'}), 递归进其消耗 (多级); 否则该消耗即叶子, 成本=其
 * 实测 total_cost (= 该上游链累计成本)。混批按各批实测投料量×各自单价精确归集 (非按重量糊平均)。
 *
 * <p>人工/调料/包装来自该批 {@code ProductionReport} 逐道报工 (复用 {@link YieldReportService#getYield});
 * 首道(原料道)材料不计入 (原料由上游 traced 承载, 避免双计)。
 *
 * <p>价格脱敏: maskPrice 时金额字段全置 null, 仅保留投料量/重量占比 (红线: procurement:price:view)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCostBreakdownService {

    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final com.cretas.aims.repository.ProductionReportRepository productionReportRepository;
    private final YieldReportService yieldReportService;
    // ── R4 (2026-07-04): SFI/FG 投料成本补计 (投料边不写 MaterialConsumption → traceCost 失明) ──
    //   与小结 InterimSettleServiceImpl.computeOutputUnitCost 同口径同源服务。@Autowired(required=false)
    //   + null-guard: 既有纯 Mockito 单测 (@InjectMocks 不提供这些 mock) 下为 null → SFI 补计整体跳过
    //   (那些测试本就无 SFI/FG 投料, 零回归)。
    private final com.cretas.aims.repository.ProcessSheetRowRepository processSheetRowRepository;
    private final com.cretas.aims.service.wip.WipInventoryService wipInventoryService;
    private final com.cretas.aims.service.inventory.FinishedGoodsFeedService finishedGoodsFeedService;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final BomVersionRepository bomVersionRepository;

    /**
     * 结单领用行 —— 成本归集的第二个来源（2026-08-18，owner 拍板走「读侧接通」）。
     *
     * <p>⚠️ 故意用<b>字段注入</b>而不是加进构造参数：本类的构造器被多个测试直接 new，
     * 改签名会让并行线的测试编译不过。今天 main 已经因为同类语义冲突炸过一次
     * （git 自动合并零冲突，合完编译失败）。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.ProductionSettlementConsumptionRepository
            settlementConsumptionRepository;

    /**
     * pinned BOM 快照缺失时的回退来源 —— 画布那条路<b>不写</b> {@code bom_versions}。
     *
     * <p>字段注入 + {@code required=false}, 与上面同一条纪律: 本类构造器被多个测试直接 new,
     * 改签名会让并行线编译不过; 缺失时回退逻辑整体跳过(退回原来的「报快照缺失」)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomRecipeRepository bomRecipeFallbackRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomRecipeItemRepository bomRecipeItemFallbackRepository;

    /**
     * 辅料先估 (owner 裁定 2026-09-06) 的配比来源 —— {@code bom_seasoning_items}。
     *
     * <p>字段注入 + {@code required=false}, 与上面几个同一条纪律: 本类构造器被多个测试直接 new,
     * 改签名会让并行线编译不过; 缺失时整块估算跳过 (退回改前行为: 无实际耗用的辅料成本为 0 且
     * 一声不吭 —— 那正是本次要修的, 所以缺失时也照旧不喊, 不制造新的噪声)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.cretas.aims.repository.bom.BomSeasoningItemRepository bomSeasoningItemRepository;

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal THOUSAND = new BigDecimal("1000");
    private static final int MAX_DEPTH = 10;

    /**
     * 辅料先估的<b>唯一</b>标签字面量 —— 明细行 {@code basis} 与给用户的那句话都用它。
     *
     * <p>⛔ 不要在前端或别处再写一遍这四个字 (本仓形态 D)。
     */
    public static final String ESTIMATED_AUX_LABEL = "按配比估算";

    public OrderCostBreakdownDTO compute(String factoryId, String orderId, boolean maskPrice) {
        List<String> planIds = planRepository.findByFactoryIdAndSourceOrderId(factoryId, orderId).stream()
                .map(ProductionPlan::getId).filter(Objects::nonNull).collect(Collectors.toList());
        if (planIds.isEmpty()) {
            return empty(orderId, maskPrice);
        }
        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndProductionPlanIdIn(factoryId, planIds);
        if (batches.isEmpty()) {
            return empty(orderId, maskPrice);
        }
        return computeForBatches(factoryId, orderId, batches, maskPrice);
    }

    /**
     * 按生产计划查单盒成本 (结单族 CUSTOMER_ORDER 成品入库成本传导用).
     *
     * <p>{@link #compute}(by-order) 走 sourceOrderId→planIds→batches; 本方法直接以单个 planId 取
     * 该计划全部生产批次, 复用同一 {@link #computeForBatches} 内核 (单一权威成本: 原料沿
     * MaterialConsumption 边回溯 + 逐道人工/调料/包装, diamond-safe)。给
     * {@code createFinishedGoodsFromReceipt} 提供成品单位成本基准, 与 出厂核算/汇总页字节一致。
     *
     * <p>DTO.orderId 字段填 planId (仅作展示 label)。无批次 → hasData=false (诚实, 不造 0 行)。
     */
    public OrderCostBreakdownDTO computeByPlan(String factoryId, String planId, boolean maskPrice) {
        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndProductionPlanId(factoryId, planId);
        if (batches == null || batches.isEmpty()) {
            return empty(planId, maskPrice);
        }
        return computeForBatches(factoryId, planId, batches, maskPrice);
    }

    /**
     * SP-C: 按批次号查单盒成本 (存货生产无订单号场景).
     * findByFactoryIdAndBatchNumber 是 factory-scoped — 跨租户安全。
     * DTO.orderId 字段填 batchNumber (前端仅作展示 label)。
     */
    public OrderCostBreakdownDTO computeByBatch(String factoryId, String batchNumber, boolean maskPrice) {
        ProductionBatch b = batchRepository.findByFactoryIdAndBatchNumber(factoryId, batchNumber)
                .orElseThrow(() -> new com.cretas.aims.exception.BusinessException(404, "生产批次不存在: " + batchNumber));
        return computeForBatches(factoryId, batchNumber, List.of(b), maskPrice);
    }

    /**
     * 内核: 已有 batches 列表 → 成本归集 → OrderCostBreakdownDTO.
     * by-order 与 by-batch 共用此方法; {@code label} 填入 DTO.orderId (展示用, by-order=orderId, by-batch=batchNumber).
     * <p>纯 extract-method: 无任何逻辑变更, by-order 行为零回归。
     */
    private OrderCostBreakdownDTO computeForBatches(String factoryId, String label,
                                                     List<ProductionBatch> batches, boolean maskPrice) {
        BigDecimal labor = BigDecimal.ZERO;
        BigDecimal seasoning = BigDecimal.ZERO;
        BigDecimal packaging = BigDecimal.ZERO;
        BigDecimal equipment = BigDecimal.ZERO;
        BigDecimal other = BigDecimal.ZERO;
        BigDecimal raw = BigDecimal.ZERO;
        int boxCount = 0;
        int sampleRetain = 0;             // 留样数 (不可售; Σ各道, 通常仅末道)
        BigDecimal waste = BigDecimal.ZERO; // 损耗/料头量 (仅展示; 已体现在出成率, 不二次扣成本)
        List<SourceCost> sources = new ArrayList<>();
        // 副产物按 名称|单位 归集 (跨批次跨道)
        LinkedHashMap<String, ByproductLine> byproductAcc = new LinkedHashMap<>();
        // AUDIT-002 包装明细按 名称 归集成本 (跨批次跨道)
        LinkedHashMap<String, BigDecimal> packagingAcc = new LinkedHashMap<>();
        // AUDIT-004 辅料按锅分摊明细 (按 锅号 去重)
        LinkedHashMap<String, OrderCostBreakdownDTO.AuxiliaryAllocation> auxAllocAcc = new LinkedHashMap<>();
        // ★ 严格审计 2026-06-25: 上游 WIP 链传播来的副产回收 (本批自身副产已由 byproductAcc 计入)
        BigDecimal upstreamByproductCredit = BigDecimal.ZERO;
        // ── R4 (2026-07-04): 外部库存投料 (SFI/FG) 成本补计 ──
        //   semiFeedCost = Σ(feedInSourceUnit × 输入 SFI/FG.unitCost) 跨本次全部批次 process-row 投料边。
        //   anySemiFeed = 存在任一 SFI/FG 投料边 (决定 DTO.semiFeedCost 是否为 null=N/A)。
        //   semiFeedUnknown = 任一投料的 unitCost/折算未知 → 诚实 null (整批成本未知, 见 costComplete)。
        BigDecimal semiFeedCost = BigDecimal.ZERO;
        boolean anySemiFeed = false;
        boolean semiFeedUnknown = false;
        boolean auditEligible = false;
        boolean anyAuthoritativePackaging = false;
        boolean equipmentComplete = true;
        boolean otherComplete = true;
        List<String> missingCostItems = new ArrayList<>();
        // 🔴 客供主料成本口径 (a)（owner 裁定，2026-09-04）：CUSTOMER_OWNED 且无价的批次记在这里，
        // 显式记 0，⛔ 不进 missingCostItems（那会阻断 costComplete，见下方 RAW_MATERIAL 分支）。
        List<String> customerSuppliedItems = new ArrayList<>();
        // 🔴 辅料先估 (owner 裁定 2026-09-06): 无实际耗用记录的辅料, 按 BOM 配比 × 实际主料投入
        //    折算成本, 每条标「按配比估算」。金额进 seasoning 桶(它就是调料成本), 明细挂这里。
        List<OrderCostBreakdownDTO.EstimatedAuxItem> estimatedAuxItems = new ArrayList<>();
        // 🔴 部分记录留痕 (2026-09-06 终审): 本批【有】辅料实耗记录、但 BOM 里还有几种
        //    一条记录都没有 —— 按「信记录不补差」不估它们, 但必须说出来, 否则成本看起来完整
        //    而实际短了这几种。⛔ 非阻断, 独立清单, 不进 missingCostItems。
        List<String> partiallyRecordedAuxItems = new ArrayList<>();
        // 同一产品的多个批次, 各自绑的 BOM 版本 —— 用来发现「期间改过配方」。
        // 不一致时这几批的成本/毛利【不可横向比较】: 「这批毛利低」会被读成生产问题,
        // 而实际是换了配方。⇒ 必须提示, 不能让人默默地比。
        Map<String, java.util.Set<Integer>> bomVersionsByProduct = new java.util.LinkedHashMap<>();
        Map<String, String> productNameById = new java.util.LinkedHashMap<>();
        List<RawMaterialDetail> rawMaterialDetails = new ArrayList<>();
        List<PackagingDetail> packagingDetails = new ArrayList<>();
        List<LaborDetail> laborDetails = new ArrayList<>();
        List<ProductionPlan> auditPlans = new ArrayList<>();
        List<ProductionBatch> auditBatches = new ArrayList<>();
        BigDecimal auditOutputQuantity = BigDecimal.ZERO;
        BigDecimal auditConvertedOutputKg = BigDecimal.ZERO;
        boolean outputConversionComplete = true;
        String auditOutputUnit = null;

        // MaterialConsumption is also the authority for whether a submitted multi-output group
        // already recorded packaging. The input fact is written once (on the first output row),
        // so its sibling outputs must not synthesize a second packaging cost from their BOM.
        Map<Long, List<MaterialConsumption>> directConsumptionsByBatch = new LinkedHashMap<>();
        for (ProductionBatch batch : batches) {
            List<MaterialConsumption> rows = consumptionRepository
                    .findByProductionBatchIdAndFactoryId(batch.getId(), factoryId);
            directConsumptionsByBatch.put(batch.getId(), rows == null ? List.of() : rows);
        }
        MultiOutputPackagingScope multiOutputPackagingScope = resolveMultiOutputPackagingScope(
                factoryId, batches, directConsumptionsByBatch);

        // 结单领用行按【计划】存, 而本循环按【批次】走 —— 同一个计划的多个批次不能各加一遍,
        // 否则原料成本翻倍。用这个集合保证每个计划只并入一次。
        java.util.Set<String> settlementPlansMerged = new java.util.HashSet<>();
        for (ProductionBatch b : batches) {
            auditBatches.add(b);
            boolean unreportedMultiOutputOwnerPlaceholder =
                    isUnreportedMultiOutputOwnerPlaceholder(b, multiOutputPackagingScope);
            // ── 辅料先估的三个读数 (per batch, 见下方 estimateAuxByBomRatio) ──────────────
            //   ① 本批调料桶的起点: 循环结束时 seasoning 一点没涨 = 这批的辅料成本【一条都没归集】
            //      (既没有报工的 SEASONING materialCost, 也没有实际耗用行, 上游 WIP 链也没带来)
            //      —— 只有这种情况才估, 否则会在已有成本之上再加一份。
            //   ② 实际主料投入量 (kg): 估算的基数。⛔ 只认 sourceType=RAW_MATERIAL(或空, 与
            //      actualCategory 同一条默认), 不含 MATERIAL_RETURN/TRANSFER_OUT/SEMI_FINISHED
            //      —— 后两者不是「投进去的主料」, 而下面那个 raw 成本分支因为是 else 兜底会把它们
            //      一起算进原料【成本】(既有行为, 本次不动)。
            //   ③ 折算得出来吗: 任一主料行折不成 kg(计数单位/批次不可解析) → 整批拒绝估算,
            //      ⛔ 不用「能折的那部分」当基数 —— 那会给出一个偏低但看起来很精确的估值。
            BigDecimal seasoningAtBatchStart = seasoning;
            BigDecimal batchRawInputKg = BigDecimal.ZERO;
            boolean batchRawInputConvertible = true;
            // 本批任何类别下已经有耗用记录的物料 —— 有记录就不估 (含 quantity=0 的记录, 见
            // estimateAuxByBomRatio javadoc「什么叫没有实际耗用」)。
            java.util.Set<String> recordedMaterialTypeIds = new java.util.HashSet<>();
            List<MaterialConsumption> batchConsumptions = new ArrayList<>(
                    directConsumptionsByBatch.getOrDefault(b.getId(), List.of()));
            batchConsumptions.addAll(
                    settlementConsumptionsFor(factoryId, b, batchConsumptions, settlementPlansMerged));
            boolean hasActualPackaging = batchConsumptions.stream()
                    .anyMatch(c -> "PACKAGING".equalsIgnoreCase(c.getSourceType()))
                    || multiOutputPackagingScope.actualPackagingCoveredBatchIds().contains(b.getId());
            boolean hasActualSeasoning = batchConsumptions.stream()
                    .anyMatch(c -> "SEASONING".equalsIgnoreCase(c.getSourceType()));
            ProductionPlan plan = b.getProductionPlanId() == null ? null
                    : planRepository.findByIdAndFactoryId(b.getProductionPlanId(), factoryId).orElse(null);
            if (plan != null) {
                auditPlans.add(plan);
                auditEligible = auditEligible || plan.getSelectedBomRecipeId() != null
                        || plan.getPlannedNetWeightGrams() != null || plan.getSelectedWorkflowId() != null;
            }
            BatchYieldDTO y = yieldReportService.getYield(factoryId, b.getId());
            BigDecimal reportedPackagingBefore = packaging;
            if (y != null) {
                if (y.getTotalLaborCost() != null) {
                    labor = labor.add(y.getTotalLaborCost());
                }
                List<StepYieldDTO> steps = y.getSteps() == null ? List.of() : y.getSteps();
                for (int i = 0; i < steps.size(); i++) {
                    accumulateLaborDetails(laborDetails, missingCostItems, steps.get(i), auditEligible);
                    accumulateByproducts(byproductAcc, steps.get(i).getByproducts());
                    if (steps.get(i).getSampleRetainQuantity() != null) {
                        sampleRetain += steps.get(i).getSampleRetainQuantity();
                    }
                    if (steps.get(i).getWasteQuantity() != null) {
                        waste = waste.add(steps.get(i).getWasteQuantity());
                    }
                    accumulatePackaging(packagingAcc, steps.get(i).getPackagingDetail());
                    BigDecimal m = steps.get(i).getMaterialCost();
                    if (m == null) {
                        continue;
                    }
                    // CALC-003: 显式 costCategory 优先分类; null/未知 → 回退 step-index 启发式 (向后兼容)
                    String bucket = resolveCostBucket(steps.get(i).getCostCategory(), i, steps.size(), factoryId, label);
                    if ("PACKAGING".equals(bucket)) {
                        if (!hasActualPackaging) {
                            packaging = packaging.add(m);
                        }
                    } else if ("SEASONING".equals(bucket)) {
                        if (hasActualSeasoning) {
                            continue;
                        }
                        // AUDIT-004: 该道辅料若标了共享锅 → 按产出量分摊本批 share (替代报工 material_cost); 否则原样计
                        BigDecimal contribution = m;
                        StepYieldDTO step = steps.get(i);
                        if (step.getAuxPotNo() != null && step.getAuxPotTotalCost() != null) {
                            BigDecimal potOutput = nz(productionReportRepository.sumOutputByAuxPotNo(factoryId, step.getAuxPotNo()));
                            BigDecimal myOutput = nz(step.getTotalOutput());
                            BigDecimal share = potOutput.signum() > 0
                                    ? step.getAuxPotTotalCost().multiply(myOutput).divide(potOutput, 2, RoundingMode.HALF_UP)
                                    : step.getAuxPotTotalCost();
                            contribution = share;
                            auxAllocAcc.put(step.getAuxPotNo(), OrderCostBreakdownDTO.AuxiliaryAllocation.builder()
                                    .potNo(step.getAuxPotNo())
                                    .method(step.getAuxAllocMethod())
                                    .potTotalCost(step.getAuxPotTotalCost())
                                    .potTotalOutput(potOutput)
                                    .batchOutput(myOutput)
                                    .batchShare(share)
                                    .batchSharePct(potOutput.signum() > 0
                                            ? myOutput.multiply(HUNDRED).divide(potOutput, 1, RoundingMode.HALF_UP) : null)
                                    .build());
                        }
                        seasoning = seasoning.add(contribution);
                    }
                    // "SKIP" → 原料由上游 traced consumption 承载, 不计 (避免双计)
                }

                BigDecimal output = positiveOrNull(y.getLastStepOutput(), b.getActualQuantity(), b.getQuantity());
                if (output != null && !unreportedMultiOutputOwnerPlaceholder) {
                    auditOutputQuantity = auditOutputQuantity.add(output);
                    auditOutputUnit = mergeUnit(auditOutputUnit, y.getLastStepOutputUnit());
                    BigDecimal outputKg = toPinnedKilograms(output, y.getLastStepOutputUnit(), plan);
                    if (outputKg == null) {
                        outputConversionComplete = false;
                    } else {
                        auditConvertedOutputKg = auditConvertedOutputKg.add(outputKg);
                    }
                }

                BatchBomBinding.Resolved bomBinding = BatchBomBinding.resolve(b, plan);
                if (bomBinding.pinned() && b.getProductTypeId() != null) {
                    bomVersionsByProduct
                            .computeIfAbsent(b.getProductTypeId(), k -> new java.util.TreeSet<>())
                            .add(bomBinding.version());
                    productNameById.putIfAbsent(b.getProductTypeId(),
                            b.getProductName() != null ? b.getProductName() : b.getProductTypeId());
                }
                PinnedPackagingResult pinnedPackaging = resolvePinnedPackaging(
                        factoryId, plan, b, output, y.getLastStepOutputUnit());
                if (!hasActualPackaging && pinnedPackaging.pinned()) {
                    anyAuthoritativePackaging = true;
                    BigDecimal reportedContribution = packaging.subtract(reportedPackagingBefore);
                    packaging = packaging.subtract(reportedContribution).add(pinnedPackaging.knownCost());
                    packagingDetails.addAll(pinnedPackaging.details());
                    missingCostItems.addAll(pinnedPackaging.missingItems());
                }
            }
            if (b.getEquipmentCost() == null) {
                equipmentComplete = false;
                if (auditEligible) missingCostItems.add("EQUIPMENT_COST:" + b.getBatchNumber());
            } else {
                equipment = equipment.add(b.getEquipmentCost());
            }
            if (b.getOtherCost() == null) {
                otherComplete = false;
                if (auditEligible) missingCostItems.add("OTHER_COST:" + b.getBatchNumber());
            } else {
                other = other.add(b.getOtherCost());
            }
            if (b.getQuantity() != null && !unreportedMultiOutputOwnerPlaceholder) {
                boxCount += b.getQuantity().intValue();
            }
            for (MaterialConsumption c : batchConsumptions) {
                java.util.Set<Long> visited = new java.util.HashSet<>();
                visited.add(b.getId());   // 起点批次入环检测集
                BigDecimal[] leaf = traceCost(factoryId, c, 1, visited);
                BigDecimal cost = leaf[0];
                int depth = leaf[1].intValue();
                MaterialBatch mb = c.getBatchId() == null ? null
                        : materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
                String actualCategory = c.getSourceType() == null
                        ? "RAW_MATERIAL" : c.getSourceType().trim().toUpperCase();
                // 辅料先估 ②③: 记下「这个物料本批有耗用记录」并累计主料投入 kg。
                // ⛔ 放在所有 continue 之前 —— 包材/调料分支下面就 continue 了, 放后面会漏记。
                if (c.getMaterialTypeId() != null) {
                    recordedMaterialTypeIds.add(c.getMaterialTypeId());
                }
                if ("RAW_MATERIAL".equals(actualCategory)) {
                    BigDecimal kg = com.cretas.aims.service.bom.SeasoningRatioContract
                            .massToKg(c.getQuantity(), mb == null ? null : mb.getQuantityUnit());
                    if (kg == null) {
                        // 计数单位的主料 / 批次不可解析 / 量为空 —— 折不出 kg。
                        // ⛔ 不把它当 0 跳过: 那会让基数偏低而读起来毫无异样 (本仓形态 A¹⁰)。
                        batchRawInputConvertible = false;
                    } else {
                        batchRawInputKg = batchRawInputKg.add(kg);
                    }
                }
                boolean actualPriceKnown = mb == null
                        ? c.getUnitPrice() != null
                        : mb.getUnitPrice() != null;
                if ("PACKAGING".equals(actualCategory)) {
                    anyAuthoritativePackaging = true;
                    packaging = packaging.add(cost);
                    if (!actualPriceKnown && auditEligible) {
                        missingCostItems.add("PACKAGING_PRICE:" + c.getMaterialTypeId());
                    }
                    packagingDetails.add(PackagingDetail.builder()
                            .materialCode(c.getMaterialTypeId())
                            .materialName(mb != null ? mb.getBatchNumber() : c.getMaterialTypeId())
                            .quantity(c.getQuantity())
                            .unit(mb != null ? mb.getQuantityUnit() : null)
                            .unitPrice(actualPriceKnown ? c.getUnitPrice() : null)
                            .priceSource("ACTUAL_MATERIAL_BATCH")
                            .amount(actualPriceKnown ? cost : null)
                            .collectionStatus(actualPriceKnown ? "COLLECTED" : "MISSING_PRICE")
                            .missingReason(actualPriceKnown ? null : "实际包材批次缺少可验证单价")
                            .build());
                    continue;
                }
                if ("SEASONING".equals(actualCategory)) {
                    seasoning = seasoning.add(cost);
                    if (!actualPriceKnown && auditEligible) {
                        missingCostItems.add("SEASONING_PRICE:" + c.getMaterialTypeId());
                    }
                    continue;
                }
                // 🔴 客供主料成本口径 (a)（owner 裁定，2026-09-04，终审订正）：来料加工下，客供
                // (ownership=CUSTOMER_OWNED) 主料成本恒记 0，不管这个批次有没有已知单价。
                // ⛔ 上一版把「有没有价」当成了前置条件 (if (!rawPriceKnown) { if (customerSupplied) …}),
                // 导致「有价的客供批」会先被当成价格已知走正常计价——prod 实测 CUSTOMER_OWNED 20 个
                // 批次里 3 个有价，恰好会中这个漏洞（终审指出）。现在先按 ownership 判：客供就无条件
                // 把 cost 强制归零，再走后面的 raw 累加/sources/明细——有价也不用，因为那本就不是
                // 我方成本。
                // ⛔ 判 ownership 用【批次】自己的字段（与 CustomerSuppliedSettlementCoverageGuard
                // #filterCustomerOwned 同一条纪律）——不用物料类别、不用 plan.materialSupplyMode 推,
                // 同一计划里客供原料与工厂自有辅料混用是正常形态（prod 实测 PLAN-...-34F8CD82：
                // 客供 1399上脑 + 工厂自购冰水/L1纸箱）。mb==null（批次不可解析）按【真的不知道】处理，
                // ⛔ 不当客供——数据缺口不能被静默当成一个已知的业务决定。
                boolean customerSupplied = mb != null
                        && InventoryOwnership.CUSTOMER_OWNED == mb.getOwnership();
                if (customerSupplied) {
                    cost = BigDecimal.ZERO;
                    customerSuppliedItems.add("CUSTOMER_SUPPLIED:" + mb.getBatchNumber());
                }
                raw = raw.add(cost);
                sources.add(SourceCost.builder()
                        .batchId(c.getBatchId())
                        .batchName(mb != null ? mb.getBatchNumber() : c.getBatchId())
                        .quantity(c.getQuantity())
                        .unit(mb != null ? mb.getQuantityUnit() : "kg")
                        .unitPrice(c.getUnitPrice())
                        .cost(cost)
                        .depth(depth)
                        .build());
                // ⛔ 不在 customerSupplied 分支里用 rawPriceKnown 做判断——客供已经无条件赢了，
                // 这个标志只在非客供的分支里才有意义（是否真的缺价、要不要阻断）。
                boolean rawPriceKnown = c.getTotalCost() != null || c.getUnitPrice() != null
                        || cost.signum() != 0;
                if (!customerSupplied && !rawPriceKnown && auditEligible) {
                    missingCostItems.add("RAW_MATERIAL_PRICE:" + c.getBatchId());
                }
                rawMaterialDetails.add(RawMaterialDetail.builder()
                        .batchNumber(mb != null ? mb.getBatchNumber() : c.getBatchId())
                        .materialCode(mb != null ? mb.getMaterialTypeId() : null)
                        .materialName(mb != null ? mb.getBatchNumber() : c.getBatchId())
                        .quantity(c.getQuantity())
                        .unit(mb != null ? mb.getQuantityUnit() : "kg")
                        .unitPrice(c.getUnitPrice())
                        .priceSource(customerSupplied
                                ? "CUSTOMER_SUPPLIED" : "MATERIAL_CONSUMPTION_SNAPSHOT")
                        .amount(customerSupplied || rawPriceKnown ? cost : null)
                        .collectionStatus(customerSupplied ? "CUSTOMER_SUPPLIED"
                                : (rawPriceKnown ? "COLLECTED" : "MISSING_PRICE"))
                        .missingReason(customerSupplied || rawPriceKnown ? null
                                : "实际耗用记录缺少可验证单价/金额")
                        .build());

                // ★ SP-F ①b: 沿 WIP 链回溯上游 *生产批次* 的 人工/调料, 按与 traceCost 相同的
                //   consumedQty/upstreamReceiptQty 比例分摊累加。本批自身的人工/调料已由上方 getYield
                //   循环计入, 故只从上游(WIP 产出批)起累加, 不重复计本批。
                //   只有 clerk/逐工序录入链会产出 MaterialBatch(sourceDocType=PRODUCTION_BATCH)
                //   被 MaterialConsumption 消耗 —— 常规生产批不走此链, 故对常规批零影响 (见 traceCost javadoc)。
                java.util.Set<Long> chainVisited = new java.util.HashSet<>();
                chainVisited.add(b.getId());
                BigDecimal[] up = aggregateUpstreamLaborSeasoning(factoryId, c, 1, chainVisited);
                labor = labor.add(up[0]);
                seasoning = seasoning.add(up[1]);
                upstreamByproductCredit = upstreamByproductCredit.add(up[2]);
            }

            // ── R4: 本批 process-row 的 SFI/FG 投料成本 (MaterialConsumption 之外的边) ──
            //   返回 [knownCost, anyFlag(0/1), unknownFlag(0/1)]。null-guard: 新注入依赖缺失 → 全 0 (跳过)。
            BigDecimal[] feed = accumulateStockFeedCost(factoryId, b);
            semiFeedCost = semiFeedCost.add(feed[0]);
            anySemiFeed = anySemiFeed || feed[1].signum() > 0;
            semiFeedUnknown = semiFeedUnknown || feed[2].signum() > 0;

            // ── 🔴 辅料先估 (owner 裁定 2026-09-06) ────────────────────────────────────
            //   放在本批全部归集之后: seasoning 这时才是「本批调料成本的最终值」,
            //   报工侧、实际耗用行、上游 WIP 链三条来源都已经加完 (⛔ 提前判会把上游那份漏掉)。
            seasoning = seasoning.add(estimateAuxByBomRatio(
                    factoryId, b, plan, recordedMaterialTypeIds,
                    seasoning.subtract(seasoningAtBatchStart),
                    batchRawInputConvertible ? batchRawInputKg : null,
                    estimatedAuxItems, partiallyRecordedAuxItems, missingCostItems));
        }

        // 包装总额: 若无 PACKAGING materialCost 报工(packaging==0)但有包装明细 → 用明细总额。
        // 文员逐道录入(SP-F)的气调步只写 packaging_detail 明细不写 materialCost-PACKAGING;
        // M67 等有 materialCost-PACKAGING 的批 packaging>0 → 不重复加明细。严格测试 2026-06-24 抓到。
        if (!anyAuthoritativePackaging && packaging.signum() == 0 && !packagingAcc.isEmpty()) {
            packaging = packagingAcc.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        }
        // R4: total 含 SFI/FG 投料成本 (已知部分)。诚实 null: 有 SFI/FG 投料但任一成本未知 →
        //   costComplete=false, 下方派生成本字段 (total/perBox/net.../semiFeedCost) 全置 null (不伪造 ¥0)。
        if (semiFeedUnknown) {
            missingCostItems.add("SEMI_FINISHED_OR_FINISHED_FEED_PRICE");
        }
        missingCostItems = missingCostItems.stream().distinct().collect(Collectors.toCollection(ArrayList::new));
        customerSuppliedItems = customerSuppliedItems.stream().distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        partiallyRecordedAuxItems = partiallyRecordedAuxItems.stream().distinct()
                .collect(Collectors.toCollection(ArrayList::new));
        // ── 2026-08-25: 「不强制填写」这条裁定原来只落到了 knownCostSubtotal 上 ──────────────
        //   2026-07-30 owner 拍板「设备/其他成本不强制填写, 填了就算进去」。当时只让
        //   knownCostSubtotal 在 costComplete=false 时不被清空, 而 costComplete 自己
        //   仍然被这两项拉黑 ⇒ totalCost 置 null ⇒ 成品 unit_cost 恒为空。
        //   实测(2026-08-25 prod): 全生产库 61 个生产批次, equipment_cost 填了 0 个、
        //   other_cost 填了 1 个 ⇒ 任何有 Workflow/BOM 的计划都永远算不出单位成本。
        //   「不填就永远算不出」= 事实上强制, 与那条裁定相反。本仓硬约束 8: 同一条裁定
        //   要么两处都改, 要么显式登记哪处故意不改。
        //   ⛔ 没有隐藏: 这两项仍然进 missingCostItems, equipmentCostStatus 仍报 UNCOLLECTED,
        //      calculationStatus 仍是 PARTIAL —— 只是不再把整个 totalCost 拉成 null。
        List<String> blockingMissingItems = missingCostItems.stream()
                .filter(item -> !isOptionalCostItem(item))
                .collect(Collectors.toList());
        // ── 2026-08-25: 一个【已经有产出】的批次, 各成本桶全是 0 —— 那是「没归集」不是「零成本」 ──
        //   实测(部署后 prod): PB-PLAN-1787560061105 读出 totalCost=0.0 / costComplete=true,
        //   而它无领料、无人工、BOM 也没有包材行。结单侧本来就安全(usableTotalCost 要求
        //   signum>0), 但成本【页面】上「0.0 且完整」会让人以为这批货真的不花钱。
        //
        //   本仓形态 A¹¹: 算「缺了多少」之前先问「这里的 0 是不是一种合法状态」——
        //   一个已完工批次的全 0 几乎必然是没归集。
        //   ⛔ 只对 auditEligible(有 BOM/Workflow 的正式计划)判: 那种批次不可能真的零投入。
        boolean nothingCollected = auditEligible
                && raw.signum() == 0 && labor.signum() == 0 && seasoning.signum() == 0
                && packaging.signum() == 0 && semiFeedCost.signum() == 0;
        if (nothingCollected) {
            missingCostItems.add("NO_COST_COLLECTED");
            blockingMissingItems = new ArrayList<>(blockingMissingItems);
            blockingMissingItems.add("NO_COST_COLLECTED");
        }
        boolean costComplete = !semiFeedUnknown
                && (!auditEligible || blockingMissingItems.isEmpty());
        BigDecimal total = labor.add(seasoning).add(packaging).add(raw).add(semiFeedCost)
                .add(equipment).add(other);
        BigDecimal perBox = boxCount > 0 ? total.divide(BigDecimal.valueOf(boxCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        List<ByproductLine> byproducts = new ArrayList<>(byproductAcc.values());
        BigDecimal byproductCredit = byproducts.stream().map(l -> nz(l.getValue())).reduce(BigDecimal.ZERO, BigDecimal::add)
                .add(upstreamByproductCredit);   // ★ 加上游 WIP 链传播来的副产回收 (严格审计 2026-06-25)
        BigDecimal netTotal = total.subtract(byproductCredit);
        BigDecimal netPerBox = boxCount > 0 ? netTotal.divide(BigDecimal.valueOf(boxCount), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        // 可售成本: 留样产出但不可售 → 净成本摊到 (产出盒数 − 留样数), 每个售出盒真实承担留样成本。
        // 损耗/料头(waste)已体现在出成率(产出更低→盒数更少), 不二次扣, 仅展示。
        int sellableBoxCount = Math.max(0, boxCount - sampleRetain);
        BigDecimal sellablePerBox = sellableBoxCount > 0
                ? netTotal.divide(BigDecimal.valueOf(sellableBoxCount), 2, RoundingMode.HALF_UP) : netPerBox;

        // AUDIT-002 包装明细 (按名称归集成本; null=未拆)
        List<PackagingItem> packagingDetail;
        if (anyAuthoritativePackaging) {
            packagingDetail = packagingDetails.stream()
                    .map(item -> PackagingItem.builder()
                            .name(item.getMaterialName())
                            .cost(item.getAmount())
                            .build())
                    .collect(Collectors.toList());
        } else {
            packagingDetail = packagingAcc.isEmpty() ? null
                    : packagingAcc.entrySet().stream()
                            .map(e -> PackagingItem.builder().name(e.getKey()).cost(e.getValue()).build())
                            .collect(Collectors.toList());
        }

        // AUDIT-004 辅料按锅分摊明细 (null=无共享锅)
        List<OrderCostBreakdownDTO.AuxiliaryAllocation> auxiliaryAllocations =
                auxAllocAcc.isEmpty() ? null : new ArrayList<>(auxAllocAcc.values());

        BigDecimal totalQty = sources.stream().map(s -> nz(s.getQuantity())).reduce(BigDecimal.ZERO, BigDecimal::add);
        for (SourceCost s : sources) {
            s.setWeightSharePct(totalQty.signum() > 0
                    ? nz(s.getQuantity()).multiply(HUNDRED).divide(totalQty, 1, RoundingMode.HALF_UP) : null);
            s.setCostSharePct(raw.signum() > 0
                    ? nz(s.getCost()).multiply(HUNDRED).divide(raw, 1, RoundingMode.HALF_UP) : null);
        }

        ProductionPlan auditPlan = uniqueOrNull(auditPlans, ProductionPlan::getId);
        ProductionBatch auditBatch = uniqueOrNull(auditBatches, ProductionBatch::getId);
        String normalizedAuditOutputUnit = "__MIXED__".equals(auditOutputUnit) ? null : auditOutputUnit;
        BigDecimal netWeight = auditPlan == null ? null : auditPlan.getPlannedNetWeightGrams();
        String equipmentStatus = !auditEligible ? "NOT_EVALUATED"
                : (!equipmentComplete ? "UNCOLLECTED"
                : (equipment.signum() == 0 ? "CONFIRMED_ZERO" : "COLLECTED"));
        String otherStatus = !auditEligible ? "NOT_EVALUATED"
                : (!otherComplete ? "UNCOLLECTED"
                : (other.signum() == 0 ? "CONFIRMED_ZERO" : "COLLECTED"));

        OrderCostBreakdownDTO dto = OrderCostBreakdownDTO.builder()
                .orderId(label)
                .orderNumber(auditPlan == null ? null : auditPlan.getCustomerOrderNumber())
                .productionPlanId(auditPlan == null ? null : auditPlan.getId())
                .productionPlanNumber(auditPlan == null ? null : auditPlan.getPlanNumber())
                .finishedBatchNumber(auditBatch == null ? null : auditBatch.getBatchNumber())
                .productSku(auditPlan != null ? auditPlan.getProductTypeId()
                        : (auditBatch == null ? null : auditBatch.getProductTypeId()))
                .productName(auditBatch == null ? null : auditBatch.getProductName())
                .pinnedBomRecipeId(auditPlan == null ? null : auditPlan.getSelectedBomRecipeId())
                .pinnedBomVersion(auditPlan == null ? null : auditPlan.getSelectedBomVersion())
                .pinnedWorkflowId(auditPlan == null ? null : auditPlan.getSelectedWorkflowId())
                .pinnedWorkflowVersion(auditPlan == null ? null : auditPlan.getSelectedWorkflowVersion())
                .calculatedAt(LocalDateTime.now())
                // ⛔ 不跟 costComplete 走: 设备/其他未录时 totalCost 现在可用, 但核算口径
                //    仍然是「不完整」—— 说成 COMPLETE 会让人以为这两项确认为 0。
                .calculationStatus(costComplete && missingCostItems.isEmpty()
                        ? "COMPLETE" : "PARTIAL")
                .outputQuantity(auditOutputQuantity.signum() > 0 ? auditOutputQuantity : null)
                .outputUnit(normalizedAuditOutputUnit)
                .netWeightGramsPerUnit(netWeight)
                .convertedOutputKg(outputConversionComplete && auditOutputQuantity.signum() > 0
                        ? auditConvertedOutputKg : null)
                .costDenominatorQuantity(auditOutputQuantity.signum() > 0 ? auditOutputQuantity : null)
                .costDenominatorUnit(normalizedAuditOutputUnit)
                .boxCount(boxCount)
                .hasData(true)
                .priceMasked(maskPrice)
                .rawMaterialCost(raw)
                .laborCost(labor)
                .seasoningCost(seasoning)
                .packagingCost(packaging)
                .equipmentCost(equipmentComplete || equipment.signum() != 0 ? equipment : null)
                .equipmentCostStatus(equipmentStatus)
                .otherCost(otherComplete || other.signum() != 0 ? other : null)
                .otherCostStatus(otherStatus)
                .semiFeedCost(anySemiFeed ? semiFeedCost : null)   // null = 无 SFI/FG 投料 (N/A)
                .costComplete(costComplete)
                .missingCostItemCount(missingCostItems.size())
                .missingCostItems(missingCostItems)
                .customerSuppliedItemCount(customerSuppliedItems.size())
                .customerSuppliedItems(customerSuppliedItems)
                // 🔴 辅料先估 (2026-09-06): 这些金额已经在 seasoningCost/totalCost 里,
                //    这份清单是它们的【来源标记】—— 不摆出来就分不清估的和实际领用的。
                .estimatedAuxItemCount(estimatedAuxItems.size())
                .estimatedAuxItems(estimatedAuxItems)
                .partiallyRecordedAuxItemCount(partiallyRecordedAuxItems.size())
                .partiallyRecordedAuxItems(partiallyRecordedAuxItems)
                .rawMaterialDetails(rawMaterialDetails)
                .packagingDetails(packagingDetails)
                .laborDetails(laborDetails)
                .totalCost(total)
                .perBoxCost(perBox)
                // 已知成本合计: 与 totalCost 同一算式, 但 costComplete=false 时不被清空
                // (2026-07-30 Steve 拍板: 设备/其他成本不强制填写, 填了就算进去)。
                .knownCostSubtotal(total)
                .knownPerBoxCost(perBox)
                .byproductCredit(byproductCredit)
                .netTotalCost(netTotal)
                .netPerBoxCost(netPerBox)
                .byproducts(byproducts)
                .sampleRetainCount(sampleRetain)
                .wasteQuantity(waste.signum() > 0 ? waste : null)
                .sellableBoxCount(sellableBoxCount)
                .sellablePerBoxCost(sellablePerBox)
                .packagingDetail(packagingDetail)
                .auxiliaryAllocations(auxiliaryAllocations)
                .sources(sources)
                .build();
        // R4 诚实 null: 有 SFI/FG 投料但成本未知 → 派生成本字段全 null (不伪造 ¥0 稀释),
        //   与小结 computeOutputUnitCost null 传播对齐。已知的料/工/调/包分桶仍保留展示。
        if (!costComplete) {
            nullIncompleteCosts(dto);
        }
        if (maskPrice) {
            maskCosts(dto);
        }

        // 同产品跨批次 BOM 版本不一致 ⇒ 这几批不可横向比较。
        // ⚠️ 这是【提示】不是阻断: 成本本身仍然算得出, 只是可比性没了。
        for (Map.Entry<String, java.util.Set<Integer>> e : bomVersionsByProduct.entrySet()) {
            List<Integer> mixed = BatchBomBinding.mixedVersions(e.getValue());
            if (!mixed.isEmpty()) {
                missingCostItems.add("BOM_VERSION_MIXED:"
                        + productNameById.getOrDefault(e.getKey(), e.getKey())
                        + ":v" + mixed.stream().map(String::valueOf)
                                .collect(java.util.stream.Collectors.joining(",v")));
            }
        }
        return dto;
    }

    /**
     * Recognize only persisted, submitted multi-output siblings whose payload identity agrees with
     * their factory-scoped materialized batches. Packaging written on any member then covers the
     * whole report group. Draft/legacy rows, malformed JSON, foreign batches and single outputs do
     * not suppress the existing per-batch pinned-BOM guard.
     */
    private MultiOutputPackagingScope resolveMultiOutputPackagingScope(
            String factoryId,
            List<ProductionBatch> batches,
            Map<Long, List<MaterialConsumption>> directConsumptionsByBatch) {
        if (processSheetRowRepository == null || objectMapper == null || batches == null || batches.isEmpty()) {
            return MultiOutputPackagingScope.empty();
        }
        Map<Long, ProductionBatch> batchesById = batches.stream()
                .filter(Objects::nonNull)
                .filter(batch -> batch.getId() != null)
                .collect(Collectors.toMap(ProductionBatch::getId, batch -> batch, (left, right) -> left,
                        LinkedHashMap::new));
        Map<String, List<Long>> groups = new LinkedHashMap<>();
        java.util.Set<String> planIds = batches.stream()
                .map(ProductionBatch::getProductionPlanId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        for (String planId : planIds) {
            List<com.cretas.aims.entity.processentry.ProcessSheetRow> rows =
                    processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId);
            if (rows == null) {
                continue;
            }
            for (com.cretas.aims.entity.processentry.ProcessSheetRow row : rows) {
                if (row == null
                        || !com.cretas.aims.entity.processentry.ProcessSheetRow.SUBMISSION_SUBMITTED
                                .equals(row.getSubmissionStatus())
                        || !Objects.equals(factoryId, row.getFactoryId())
                        || !Objects.equals(planId, row.getPlanId())
                        || row.getBatchId() == null) {
                    continue;
                }
                ProductionBatch batch = batchesById.get(row.getBatchId());
                com.cretas.aims.dto.processentry.ProcessSheetRowRequest request =
                        parseRowPayload(row.getRowPayload());
                if (batch == null || !Objects.equals(factoryId, batch.getFactoryId())
                        || !Objects.equals(planId, batch.getProductionPlanId())
                        || com.cretas.aims.entity.enums.ProductionBatchStatus.CANCELLED
                                .equals(batch.getStatus())
                        || row.getBatchNumber() == null
                        || !Objects.equals(row.getBatchNumber(), batch.getBatchNumber())
                        || request == null || !Boolean.TRUE.equals(request.getMultiOutputMember())
                        || request.getMultiOutputBaseRowId() == null
                        || request.getMultiOutputBaseRowId().isBlank()
                        || !Objects.equals(batch.getProductTypeId(), request.getProductTypeId())) {
                    continue;
                }
                String groupKey = planId + "|" + row.getProcessCode() + "|" + row.getProcessOrder()
                        + "|" + request.getMultiOutputBaseRowId();
                groups.computeIfAbsent(groupKey, ignored -> new ArrayList<>()).add(batch.getId());
            }
        }
        java.util.Set<Long> covered = new java.util.HashSet<>();
        java.util.Set<String> verifiedPlanIds = new java.util.HashSet<>();
        for (List<Long> memberIds : groups.values()) {
            java.util.Set<String> products = memberIds.stream()
                    .map(batchesById::get)
                    .filter(Objects::nonNull)
                    .map(ProductionBatch::getProductTypeId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            if (memberIds.stream().distinct().count() < 2 || products.size() < 2) {
                continue;
            }
            ProductionBatch first = batchesById.get(memberIds.get(0));
            if (first != null && first.getProductionPlanId() != null) {
                verifiedPlanIds.add(first.getProductionPlanId());
            }
            boolean groupHasActualPackaging = memberIds.stream()
                    .flatMap(id -> directConsumptionsByBatch.getOrDefault(id, List.of()).stream())
                    .anyMatch(c -> "PACKAGING".equalsIgnoreCase(c.getSourceType()));
            if (groupHasActualPackaging) {
                covered.addAll(memberIds);
            }
        }
        return new MultiOutputPackagingScope(java.util.Set.copyOf(covered),
                java.util.Set.copyOf(verifiedPlanIds));
    }

    private static boolean isUnreportedMultiOutputOwnerPlaceholder(
            ProductionBatch batch, MultiOutputPackagingScope scope) {
        if (batch == null || batch.getProductTypeId() == null || batch.getProductionPlanId() == null
                || !scope.verifiedPlanIds().contains(batch.getProductionPlanId())) {
            return false;
        }
        Map<String, String> ids = batch.getSelectedBomRecipeIdsByProduct();
        Map<String, Integer> versions = batch.getSelectedBomVersionsByProduct();
        return ids != null && !ids.isEmpty() && versions != null && !versions.isEmpty()
                && Objects.equals(ids.keySet(), versions.keySet())
                && !ids.containsKey(batch.getProductTypeId());
    }

    private record MultiOutputPackagingScope(java.util.Set<Long> actualPackagingCoveredBatchIds,
                                              java.util.Set<String> verifiedPlanIds) {
        private static MultiOutputPackagingScope empty() {
            return new MultiOutputPackagingScope(java.util.Set.of(), java.util.Set.of());
        }
    }

    /**
     * 🔴 <b>辅料先估</b> (owner 裁定 2026-09-06) —— 无实际耗用记录的辅料/调料按
     * 「BOM 配比 × <b>实际</b>主料投入」折算成本, 每条标 {@value #ESTIMATED_AUX_LABEL}。
     *
     * <h2>为什么要有它</h2>
     * 生产库实测(2026-09-06, 只读探针): F006 有 29 个已结单计划、LIUSHANMEN 有 7 个, 它们绑定的
     * BOM 里都配了 {@code bom_seasoning_items}; 而<b>写进 {@code material_consumptions} 的
     * SEASONING 行只覆盖 F006 的 2 个计划、LIUSHANMEN 的 0 个</b>。也就是说这三十几个计划的
     * 辅料成本此前一律是 <b>¥0 且一声不吭</b> —— 不是「辅料不要钱」, 是根本没人录。
     * 按 0 计会系统性低估 COGS、虚增毛利, 正是本仓明令禁止的那种假数据。
     *
     * <h2>四条决定 (⛔ 改之前请连同这段一起改, 否则描述会先于代码过期)</h2>
     *
     * <h3>① 什么叫「没有实际耗用记录」</h3>
     * 两层, 任何一层有东西就不估:
     * <ul>
     *   <li><b>批级</b>: 本批的调料桶贡献必须<b>恰好为 0</b>({@code seasoningAlreadyCollected}) ——
     *       报工侧 SEASONING 桶的 {@code materialCost}、实际耗用行、上游 WIP 链带来的调料,
     *       三者任一非 0 就说明这批的辅料成本已经归集过了, 再估就是<b>双计</b>。
     *       报工侧不按物料拆分, 所以这一层只能是批级的。</li>
     *   <li><b>物料级</b>: 该辅料的 {@code materialTypeId} 不得出现在本批<b>任何类别</b>的耗用行里
     *       (辅料被人记成 {@code RAW_MATERIAL} 是存量里会有的形态)。</li>
     * </ul>
     * 🔴 <b>两层不是「串联的两道闸」——第二层的实际作用面比它读起来窄得多。</b>
     * 一条<b>有量</b>的辅料耗用行会先把调料桶顶成非 0, 批级那层就已经返回了, 物料级根本够不着。
     * ⇒ 物料级<b>独有</b>的作用面只剩两种形态:
     * <ol>
     *   <li>那条记录的 {@code quantity = 0}(成本贡献仍是 0, 批级放行) —— 见下一段;</li>
     *   <li>辅料被记在<b>别的 {@code sourceType}</b> 下(例如 {@code RAW_MATERIAL}),
     *       所以它没进调料桶、批级同样放行。</li>
     * </ol>
     * ⛔ 不要把这两层描述成「双保险」: 变异实测(去掉物料级过滤)只让 {@code quantity = 0}
     * 那个场景变红, 有量的实际记录场景<b>纹丝不动</b> —— 因为那一条本来就由批级挡着。
     * <b>⚠️ {@code quantity = 0} 的记录算记录 —— 不估。</b> 0 是操作员明确说的「这次没用」,
     * 而 null/无行才是「还不知道」。本仓已经就这条定过案(缺陷43 零值行不删「0 是明确的事实」、
     * {@code confirmPickingAuxDerive.ts}「0 = 明确这次不领, null = 还不知道」)。拿估算去覆盖一个
     * 明确的 0, 等于替操作员改口供。
     * <p>📌 生产库实测: {@code material_consumptions} 里 {@code quantity = 0} 或 {@code null} 的行
     * <b>各 0 条</b>(F006 337 行 / LIUSHANMEN 33 行) ⇒ 这一分支目前只在测试桩上到得了,
     * 生产行为不受它影响。
     *
     * <h3>① - b 部分记录 (有的记了、有的没记) → <b>按已记录处理, 不估余量; 但必须留痕</b></h3>
     * 「余量 = BOM 应耗 − 已记录」这个减法的前提是「BOM 配比是真值、记录是残缺的」。
     * 实际同样可能是「这次就是少放了」。在一条<b>真实记录</b>之上再加一段估算, 会把成本推高而且
     * 无法证伪 —— 保守读法是<b>信记录</b>, 只在<b>完全沉默</b>的地方补估。
     * <p>⚠️ 与 {@code settlementConsumptionsFor} 的 {@code max(0, 结单量 − 已耗用量)} 不同源:
     * 那里两侧都是<b>对同一件事的记录</b>(结单领用 vs 逐道耗用), 取差是对账; 这里一侧是记录、
     * 另一侧是<b>计划</b>, 取差是拿计划去否定记录。
     * <p>🔴 <b>不补差 ≠ 不出声。</b> 只做到「不补差」就还是一次<b>静默少算</b>:
     * BOM 里两种调料只记了一种时, 已记录的那种给出非 null 成本 ⇒ {@code costComplete=true}、
     * 成本看起来完整, 而它实际短了未记录那几种的钱。⇒ 批级门触发且<b>确实有辅料被逐物料记录过</b>时,
     * 给每一条无记录的辅料写一条 {@code AUX_PARTIALLY_RECORDED:<名称>} 进
     * {@link OrderCostBreakdownDTO#getPartiallyRecordedAuxItems()} ——
     * ⛔ 非阻断、⛔ 不进 {@code missingCostItems}、⛔ 不改策略, 只是让人看得见。
     *
     * <h3>② 单价用哪个</h3>
     * BOM 调料行自己的 {@code priceSource1}(优先) / {@code priceSource2}, 走
     * {@link com.cretas.aims.service.bom.SeasoningRatioContract#unitPricePerKg} ——
     * 与 {@code BomRecipeServiceImpl#seasoningCost} 是<b>同一条</b>规则(不是第二份)。
     * <p>为什么不用「该辅料库存批次的单价」(即实际耗用路径用的那个价): 估算这条线上<b>没有批次</b>,
     * 要用就得现发明一条「挑哪个批次」的规则, 那才是新的第二份定义。而且实测覆盖率也是 BOM 侧更高
     * (BOM {@code priceSource1|2}: F006 23/23 行、LIUSHANMEN 47/111 行;
     * {@code raw_material_types.unit_price}: F006 4/4 个物料、LIUSHANMEN <b>0/23</b>;
     * 有价库存批次: F006 2/4、LIUSHANMEN 4/23)。
     * <p>可比性: F006 的 {@code price_source2} 与 {@code raw_material_types.unit_price} <b>逐行相等</b>,
     * 即 BOM 价就是物料档案价的快照 —— 与实际耗用价(采购批次价)可能有差, 所以估算行
     * <b>单独成列并标 {@value #ESTIMATED_AUX_LABEL}</b>, ⛔ 不与实际行混在一起看。
     *
     * <h3>③ 缺配比 / 缺单价 → 报缺, ⛔ 绝不按 0 计</h3>
     * 分别记 {@code ESTIMATED_AUX_RATIO_MISSING:<名称>} / {@code ESTIMATED_AUX_PRICE_MISSING:<名称>},
     * 两个码都<b>不在</b> {@link #OPTIONAL_COST_ITEM_CODES} 里 ⇒ 会阻断 {@code costComplete},
     * 于是 {@code totalCost} 留 null 而不是给出一个偏低的数。本仓形态 A¹⁰。
     * <p>{@code estimatedAuxItems} 里<b>只放估出来了的行</b> —— 让这份清单只表达一件事
     * (「这些是估的」); 没估出来的那些在 {@code missingCostItems} 里, 由
     * {@code ProductionPlanServiceImpl#missingCostItemLabel} 翻成人话。
     * <p>📌 三个码在生产库上的实际触发面(2026-09-06 只读实测):
     * <ul>
     *   <li>{@code ESTIMATED_AUX_PRICE_MISSING} —— 全厂 {@code bom_seasoning_items} 134 行里
     *       <b>64 行无价</b>; 落到已结单计划上只命中 <b>1 个</b>
     *       ({@code LIUSHANMEN / PP-AUTO-20260829-0007}, 7 条辅料里 6 条无价)。</li>
     *   <li>{@code ESTIMATED_AUX_RATIO_MISSING} —— <b>当前触发不了</b>: 134 行
     *       {@code dosage_per_kg_g} <b>0 条缺失</b>。它守的是「以后有人把配比删空」,
     *       ⛔ 不要因为它现在不亮就把它删掉。</li>
     *   <li>{@code ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE} —— 同样触发不了:
     *       126 行主料耗用的批次单位<b>全是 kg</b>。</li>
     * </ul>
     *
     * <h3>④ 单位一致性</h3>
     * <ul>
     *   <li>配比 {@code dosagePerKgG} 的量纲是 <b>g/kg 投入主料</b> —— 由列名与
     *       {@code SeasoningRatioContract} 钉死;</li>
     *   <li>单价是 <b>¥/kg</b> —— 同样由 {@code SeasoningRatioContract} 钉死(依据:
     *       {@code BomRecipeServiceImpl} 的 {@code (dosage÷1000) × price} 只有在 price 是 ¥/kg 时
     *       量纲才成立; 且实测 27/27 个被引用辅料的 {@code raw_material_types.unit} 都是 kg);</li>
     *   <li>⇒ 整条路径上<b>唯一</b>需要换算的是「主料耗用量 → kg」, 它走
     *       {@code SeasoningRatioContract#massToKg}(只认质量单位, 计数单位一律 null)。
     *       任一主料行折不出 kg ⇒ 记 {@code ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE:<批次号>}
     *       并<b>整批不估</b> —— ⛔ 不拿「能折的那部分」当基数, 那会给出一个偏低却看起来精确的数。
     *       📌 实测生产库 126 行主料耗用的批次单位<b>全是 kg</b>, 该拒绝分支当前不会触发。</li>
     * </ul>
     *
     * @param recordedMaterialTypeIds     本批任何类别下已有耗用行的物料 (见①物料级)
     * @param seasoningAlreadyCollected   本批已归集的调料成本 (见①批级); 非 0 → 整批不估
     * @param rawInputKg                  实际主料投入 (kg); {@code null} = 折不出来(见④),
     *                                    {@code 0} = 本批没有主料投入(没有基数, 静默跳过 ——
     *                                    这种批次由 {@code NO_COST_COLLECTED} 等既有码去说)
     * @return 本批估算出的辅料成本合计 (等于 {@code out} 里各行 {@code amount} 之和; 没估则 0)
     */
    private BigDecimal estimateAuxByBomRatio(
            String factoryId,
            ProductionBatch batch,
            ProductionPlan plan,
            java.util.Set<String> recordedMaterialTypeIds,
            BigDecimal seasoningAlreadyCollected,
            BigDecimal rawInputKg,
            List<OrderCostBreakdownDTO.EstimatedAuxItem> out,
            List<String> partiallyRecordedOut,
            List<String> missingCostItems) {
        if (bomSeasoningItemRepository == null || batch == null) {
            return BigDecimal.ZERO;   // 依赖缺失(纯 Mockito 单测) → 整块跳过, 零回归
        }
        BatchBomBinding.Resolved binding = BatchBomBinding.resolve(batch, plan);
        if (!binding.pinned()) {
            return BigDecimal.ZERO;   // 不知道按哪一版配方估 —— 不猜
        }
        List<com.cretas.aims.entity.bom.BomSeasoningItem> rows;
        try {
            rows = bomSeasoningItemRepository.findByRecipeIdOrderBySeqAsc(binding.recipeId());
        } catch (RuntimeException ex) {
            // ⛔ 读不到调料明细不能让整个成本分解挂掉, 但也不装作没这回事 —— 留痕给排查用。
            log.warn("[M67CostBreakdown] BOM 调料明细读不出来(辅料先估跳过) factory={} recipe={}: {}",
                    factoryId, binding.recipeId(), ex.getMessage());
            return BigDecimal.ZERO;
        }
        if (rows == null || rows.isEmpty()) {
            return BigDecimal.ZERO;
        }
        // ⚠️ findByRecipeIdOrderBySeqAsc 不带 factoryId —— 这里自己收租户口径,
        //    与本类其它查询 (findByIdAndFactoryId / findByFactoryIdAnd…) 同一条纪律。
        // 这一步只做「本租户 + 算进调料成本 + 绑了物料档案」三个过滤, 先【不】剔已有耗用记录的 ——
        // 下面判「部分记录」要拿完整集合去问「这张 BOM 的辅料里有没有【任何一条】被逐物料记过」。
        // ⛔ 没绑物料档案的调料行只是配方文本, 领不出实物也估不出成本 —— 整条跳过且不报缺
        //    (与 appendSeasoningItems 同一条口径: 它本来就不是一笔可发生的成本)。
        List<com.cretas.aims.entity.bom.BomSeasoningItem> costCountingRows = rows.stream()
                .filter(Objects::nonNull)
                .filter(s -> factoryId.equals(s.getFactoryId()))
                .filter(com.cretas.aims.service.bom.SeasoningRatioContract::countsTowardSeasoningCost)
                .filter(s -> s.getMaterialTypeId() != null && !s.getMaterialTypeId().isBlank())
                .collect(Collectors.toList());
        List<com.cretas.aims.entity.bom.BomSeasoningItem> candidates = costCountingRows.stream()
                .filter(s -> !recordedMaterialTypeIds.contains(s.getMaterialTypeId()))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return BigDecimal.ZERO;   // 这张 BOM 的辅料条条都有记录 —— 没有缺口, 也没有可估的
        }
        String batchLabel = batch.getBatchNumber() != null
                ? batch.getBatchNumber() : String.valueOf(batch.getId());
        // ── ① 批级门: 本批已经归集过调料成本 ⇒ 不估 (再估就是双计) ──────────────────────
        //   🔴 2026-09-06 终审抠下来的洞: 这条 return 之前是【静默】的。
        //   BOM 里两种调料、实耗只记了一种 ⇒ 本门直接返回, 第二种既不估也不喊,
        //   而已记录的那一种给出非 null 成本 ⇒ 成本【看起来完整】、实际短了未记录的那几种。
        //   那正是本 PR 要消灭的「静默少算」, 在这条路上原样活着。
        //
        //   ⛔ 修法只加【留痕】, 不改策略: 仍然不补差(「信记录不补差」见上方 ①-b),
        //   仍然不阻断 costComplete —— marker 走独立的 partiallyRecordedAuxItems 清单,
        //   【不】进 missingCostItems。这样「它阻断不了」是【结构上】保证的, 而不是靠记得把它
        //   登记进 OPTIONAL_COST_ITEM_CODES(那个集合的 javadoc 明令禁止加「缺价」类的项)。
        //
        //   ⚠️ 只在【确实有辅料被逐物料记录过】时才写 marker。否则那不叫「部分记录」:
        //   调料成本也可能整个来自报工侧的一笔总额(或上游 WIP 链), 那是「按批次粒度记录」,
        //   对逐物料覆盖度无法断言 —— 给每一条辅料都挂 marker 就是噪声(本仓形态 E:
        //   宁可窄而可信, 不要宽而被关掉)。
        //   📌 实测(2026-09-06): 22 个已结单计划里 17 个有 SEASONING 记录, 这 17 个的
        //   BOM 辅料【条条都有记录】(would_get_marker 全 0) ⇒ 本 marker 当前零触发,
        //   它守的是缺口, 不是现状。
        boolean anyAuxRecorded = costCountingRows.stream()
                .anyMatch(s -> recordedMaterialTypeIds.contains(s.getMaterialTypeId()));
        if (nz(seasoningAlreadyCollected).signum() != 0) {
            if (anyAuxRecorded) {
                for (com.cretas.aims.entity.bom.BomSeasoningItem s : candidates) {
                    partiallyRecordedOut.add("AUX_PARTIALLY_RECORDED:" + auxLabel(s));
                }
                log.info("[M67CostBreakdown] 辅料部分记录: {} 项无耗用记录且按「信记录不补差」未估算"
                                + " factory={} batch={} recipe={}",
                        candidates.size(), factoryId, batchLabel, binding.recipeId());
            }
            return BigDecimal.ZERO;
        }
        if (rawInputKg == null) {
            missingCostItems.add("ESTIMATED_AUX_RAW_BASIS_UNVERIFIABLE:" + batchLabel);
            return BigDecimal.ZERO;
        }
        if (rawInputKg.signum() <= 0) {
            return BigDecimal.ZERO;   // 本批没有主料投入 —— 没有可估的基数
        }
        BigDecimal total = BigDecimal.ZERO;
        for (com.cretas.aims.entity.bom.BomSeasoningItem s : candidates) {
            String label = auxLabel(s);
            BigDecimal grams = com.cretas.aims.service.bom.SeasoningRatioContract
                    .requiredGrams(s, rawInputKg);
            if (grams == null) {
                // countsTowardSeasoningCost 与 rawInputKg>0 上面都过了 ⇒ 只可能是没配比。
                missingCostItems.add("ESTIMATED_AUX_RATIO_MISSING:" + label);
                continue;
            }
            BigDecimal unitPrice = com.cretas.aims.service.bom.SeasoningRatioContract
                    .unitPricePerKg(s);
            if (unitPrice == null) {
                missingCostItems.add("ESTIMATED_AUX_PRICE_MISSING:" + label);
                continue;   // ⛔ 绝不按 0 计
            }
            BigDecimal quantity = grams.divide(THOUSAND, 6, RoundingMode.HALF_UP);
            BigDecimal amount = quantity.multiply(unitPrice).setScale(4, RoundingMode.HALF_UP);
            out.add(OrderCostBreakdownDTO.EstimatedAuxItem.builder()
                    .materialCode(s.getMaterialTypeId())
                    .materialName(label)
                    .bomRecipeId(binding.recipeId())
                    .bomVersion(binding.version())
                    .batchNumber(batchLabel)
                    .dosagePerKgG(s.getDosagePerKgG())
                    .rawInputKg(rawInputKg)
                    .quantity(quantity)
                    .unit("kg")
                    .unitPrice(unitPrice)
                    .amount(amount)
                    .basis(ESTIMATED_AUX_LABEL)
                    .collectionStatus("ESTIMATED_BY_BOM_RATIO")
                    // ⚠️ 读的是【当前】的 bom_seasoning_items, 不是钉住的那一版快照:
                    //    bom_versions 上没有这些计划的快照 (实测 F006 13 个 ACTIVE recipe 里
                    //    有版本快照的是 0 个, 见 resolvePinnedPackaging 里那段实测)。
                    //    ⇒ 来源如实标成 LIVE, ⛔ 不冒充「锁定版本」。
                    .ratioSource("BOM_SEASONING_ITEMS_LIVE")
                    .priceSource(com.cretas.aims.service.bom.SeasoningRatioContract
                            .unitPriceSource(s))
                    .build());
            total = total.add(amount);
        }
        if (total.signum() > 0) {
            log.info("[M67CostBreakdown] 辅料先估 {} 行 合计={} factory={} batch={} recipe={} 主料投入={}kg",
                    out.size(), total, factoryId, batchLabel, binding.recipeId(), rawInputKg);
        }
        return total;
    }

    /**
     * 一条 BOM 调料行在提示里怎么称呼 —— 有名字用名字, 没有才退回 materialTypeId。
     *
     * <p>三个 {@code ESTIMATED_AUX_*} 码与 {@code AUX_PARTIALLY_RECORDED} 共用它, 保证同一条
     * 辅料在四种提示里是<b>同一个称呼</b> (本仓形态 D: 同一个东西有两份必定漂)。
     */
    private static String auxLabel(com.cretas.aims.entity.bom.BomSeasoningItem s) {
        return s.getName() != null && !s.getName().isBlank() ? s.getName() : s.getMaterialTypeId();
    }

    /**
     * R4 (2026-07-04): 读某物化批次 process-row 的 SFI/FG 投料边, 返回其成本贡献。
     * 返回 {@code [knownCost, anyFlag(0/1), unknownFlag(0/1)]}:
     * <ul>
     *   <li>knownCost = Σ(feedInSourceUnit × 输入 unitCost) — 仅成本已知的投料累加。</li>
     *   <li>anyFlag = 存在任一 SFI/FG 投料边 (feed&gt;0) → 1。</li>
     *   <li>unknownFlag = 任一投料的折算/unitCost 未知 → 1 (诚实 null 触发)。</li>
     * </ul>
     * 与小结 {@code computeOutputUnitCost} 同口径同源服务 (resolveFeedQtyInSourceUnit + getSemiUnitCost/
     * getFeedUnitCost)。null-guard: 新注入依赖任一缺失 (纯 Mockito 单测) → 全 0 (跳过, 零回归)。
     *
     * <p>不与 {@code traceCost} 双计: SFI/FG 投料边<b>不写</b> MaterialConsumption (投常驻外部库存, 非本计划
     * 在制 WIP), traceCost/aggregateUpstreamLaborSeasoning 只沿 MaterialConsumption→PRODUCTION_BATCH 链走,
     * 永远看不到这些边。扁平查表 (无递归), 无环风险。
     */
    private BigDecimal[] accumulateStockFeedCost(String factoryId, ProductionBatch b) {
        BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        if (processSheetRowRepository == null || wipInventoryService == null
                || finishedGoodsFeedService == null || objectMapper == null
                || b == null || b.getId() == null) {
            return zero;
        }
        BigDecimal known = BigDecimal.ZERO;
        boolean any = false;
        boolean unknown = false;
        for (com.cretas.aims.entity.processentry.ProcessSheetRow row :
                processSheetRowRepository.findByFactoryIdAndBatchId(factoryId, b.getId())) {
            com.cretas.aims.dto.processentry.ProcessSheetRowRequest req = parseRowPayload(row.getRowPayload());
            if (req == null || req.getUpstreamSources() == null) {
                continue;
            }
            for (com.cretas.aims.dto.processentry.ProcessSheetRowRequest.UpstreamRef ref : req.getUpstreamSources()) {
                boolean semi = ref.isSemiFinished();
                boolean fg = ref.isFinishedGoods();
                if (!semi && !fg) {
                    continue;   // 在制 WIP 投料成本已在 traceCost (MaterialConsumption 边) 内
                }
                BigDecimal feed = nz(ref.getFeedQuantityKg());
                if (feed.signum() <= 0) {
                    continue;
                }
                any = true;
                String srcBatchNo = ref.getSourceBatchNumber();
                // 盒⇄kg 折算 (计数单位来源缺每盒克重 → null → 诚实 unknown); 与扣减/小结成本同源。
                BigDecimal feedInSourceUnit = fg
                        ? finishedGoodsFeedService.resolveFeedQtyInSourceUnit(factoryId, srcBatchNo, feed)
                        : wipInventoryService.resolveSemiFeedQtyInSourceUnit(factoryId, srcBatchNo, feed);
                if (feedInSourceUnit == null) {
                    unknown = true;
                    continue;
                }
                BigDecimal inputUnitCost = fg
                        ? finishedGoodsFeedService.getFeedUnitCost(factoryId, srcBatchNo)
                        : wipInventoryService.getSemiUnitCost(factoryId, srcBatchNo);
                if (inputUnitCost == null) {
                    unknown = true;   // 输入 SFI/FG 无成本 → 本批产出成本未知
                    continue;
                }
                known = known.add(feedInSourceUnit.multiply(inputUnitCost));
            }
        }
        return new BigDecimal[]{known, any ? BigDecimal.ONE : BigDecimal.ZERO,
                unknown ? BigDecimal.ONE : BigDecimal.ZERO};
    }

    private com.cretas.aims.dto.processentry.ProcessSheetRowRequest parseRowPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload,
                    com.cretas.aims.dto.processentry.ProcessSheetRowRequest.class);
        } catch (Exception e) {
            log.warn("[M67CostBreakdown] 无法解析 process_sheet_row payload (SFI/FG 投料成本跳过): {}", e.getMessage());
            return null;
        }
    }

    /** R4 诚实 null: 成本不完整 (未计价 SFI/FG 投料) → 派生成本字段全置 null (料/工/调/包已知分桶保留)。 */
    /**
     * 这些缺项<b>不阻断</b>总成本 —— 2026-07-30 owner 裁定「不强制填写, 填了就算进去」。
     *
     * <p>⚠️ 它们<b>照常</b>进 {@link OrderCostBreakdownDTO#getMissingCostItems()} 和
     * {@code equipmentCostStatus/otherCostStatus=UNCOLLECTED}: 用户看得到「这两项没录」,
     * 只是不再因此把整个 {@code totalCost} 拉成 null。
     *
     * <p>⛔ 不要往这个集合里加「缺价」类的项(原料/包材/调料/半成品投料) —— 那些是
     * <b>真的不知道</b>, 按 0 计会系统性低估 COGS、虚增毛利。这两项是<b>可以不发生</b>的成本桶。
     */
    private static final java.util.Set<String> OPTIONAL_COST_ITEM_CODES =
            java.util.Set.of("EQUIPMENT_COST", "OTHER_COST",
                    // 人工【金额已知】只是缺工时段 —— 金额已经进了 laborCost/总成本,
                    // 抹掉 totalCost 等于把一个算得出来的数扔掉。⛔ 与 LABOR_COST_UNKNOWN
                    // (金额真的不知道) 严格区分, 后者照旧阻断。
                    "LABOR_TIME_DETAIL_MISSING");

    /** {@code CODE:detail} → 这个缺项是不是「不强制填写」那一类。 */
    public static boolean isOptionalCostItem(String item) {
        if (item == null) {
            return false;
        }
        int sep = item.indexOf(':');
        return OPTIONAL_COST_ITEM_CODES.contains(sep < 0 ? item : item.substring(0, sep));
    }

    private void nullIncompleteCosts(OrderCostBreakdownDTO dto) {
        dto.setSemiFeedCost(null);
        dto.setTotalCost(null);
        dto.setPerBoxCost(null);
        dto.setNetTotalCost(null);
        dto.setNetPerBoxCost(null);
        dto.setSellablePerBoxCost(null);
    }

    /**
     * T1 递归回溯: 返回 [该消耗回溯到的成本, 谱系深度].
     * 上游批次由生产批次产出且有消耗 → 递归取其上游成本和 (回溯到更上游); 否则叶子 = consumption.totalCost。
     *
     * <p><b>共享上游按消耗比例分摊 (SP-B1):</b> 若上游批次被多个下游消耗, 每个下游只取上游总成本的
     * {@code consumedQty / upstreamReceiptQty} 份额, 防止双重计数。
     * <br>向后兼容论证: 既有单链 1:1 全量消耗时 consumedQty == upstreamReceiptQty → 比例=1 →
     * apportioned == upstreamSum, 与改前一致。只有「部分消耗/共享上游」才缩放。
     * <br>缺量兜底: consumedQty 或 upstreamReceiptQty 为 null/0 时退回 own
     * (consumption.totalCost = 写入时 qty×单价 的切片, 已是合理估值)。
     */
    /**
     * 把<b>结单领用行</b>转成 {@link MaterialConsumption} 的形状，并入成本归集。
     *
     * <h3>🔴 为什么需要它（2026-08-18 prod 实测，owner 拍板走「读侧接通」）</h3>
     *
     * 结单把领用事实写进 {@code production_settlement_consumptions}（note「自动带入自逐道报工」），
     * 而本类一直按 {@code material_consumptions.production_batch_id} 归集 —— <b>两张表不相交</b>：
     * <pre>
     * production_settlement_consumptions   4 行 /  1 个计划
     * material_consumptions               69 行 /  6 个计划
     *   其中 production_batch_id 有值      22 行, 为空 47 行   ← 归集按这个键查, 47 行看不见
     * 只出现在结单表、归集完全看不到的计划   1 个
     * 两表都有的 (计划, 批次) 组合          0 个                ← 所以并入不会重复计
     * </pre>
     * 净效果：{@code totalRawInput=0} → {@code totalCost=0} → {@code resolvePlanTotalCost} 返回 null
     * → 结单产出的成品 {@code unit_cost} 恒为空。
     *
     * <h3>🔴 2026-09-04 缺陷订正: 「两表都有的组合 0 个」只在【当前批次自己】的维度上成立</h3>
     * prod 实测 (只读探针, plan {@code PLAN-1788305552728-D94F7E22}, 成品批次 10909) 打脸了这句：
     * {@code traceCost} 沿 {@code MaterialBatch.sourceDocId} 递归回溯的是<b>上游 9 个 CLERK_WIP
     * 批次(10910→10918)自己的</b> {@code material_consumptions}, 那些行的
     * {@code production_batch_id} 是上游批次自己的 id, 从不出现在当前(成品)批次自己的消耗行里 ——
     * 于是叶子物料批次 4cce21c0(冰水) 被 {@code traceCost} 算一次(0.3800)、被本方法从结单又并入
     * 一次(0.3738), 归集里出现两次。{@code covered} 参数只是「本批次自己」的消耗行, 不是「本计划」
     * 的消耗行, 需要额外并上 {@link com.cretas.aims.repository.MaterialConsumptionRepository
     * #findByFactoryIdAndPlanProductionBatchesIncludingWip}(见其 javadoc: 为什么不能直接按
     * production_plan_id 查) 才是真正「计划全量、含 CLERK_WIP」的去重集合。
     *
     * <h3>为什么是「转形状并入」而不是新写一套成本算法</h3>
     * 下游那一整套（{@code traceCost} 谱系回溯、包材/调料分类、缺价登记 {@code missingCostItems}、
     * 明细行）原样复用。⛔ 不复制第二份成本算法 —— 本仓形态 D：同一个东西有两份必定漂。
     *
     * <h3>价从哪来</h3>
     * {@code production_settlement_consumptions} <b>没有单价/成本列</b>（实测 0 列），
     * 回 {@code material_batches.unit_price} 取（实测 4/4 行都连得上且都有单价）。
     * ⛔ <b>取不到价时留 null，不填 0</b> —— 0 是个看起来很确定的假答案，
     * 而下游的 {@code missingCostItems} 正是靠 null 才报得出「缺价」。
     *
     * <h3>🔴 2026-09-04 缺陷订正 II: 去重键只到「批次存不存在」, 量盲</h3>
     * 上面「2026-09-04 缺陷订正」把去重集合从「本批次自己」扩到「计划全量」, 堵住了 #576 那个
     * 双计洞——但它仍然是<b>存在性</b>判断: 一个物料批次只要在 {@code material_consumptions}
     * 里出现过<b>任意一行</b>, 这一批结单领用行就<b>整行丢弃</b>, 不管结单量是不是比已耗用量更大。
     * prod 只读探针实测(2026-09-04): 88 组 (计划, 物料批次) 重叠, 其中 7 组结单量 &gt; 已耗用量
     * (合计风险量 0.026, 目前是舍入残差量级, 尚未流血, 但规则本身是错的)。
     * <p>改法: 去重键不再是「批次是否出现」, 而是「结单量超出已耗用量的部分」——
     * {@code contribution = max(0, settlementQty − consumedQty)}, 按结单行自己连出的
     * {@code material_batches.unit_price} 计价。
     * <ul>
     *   <li>已耗用量 &gt;= 结单量 → 贡献 0（整行等效丢弃, 覆盖 #576 原场景，
     *       含本方法 javadoc 上面那个冰水 0.3800+0.3738 双计案例 —— 见
     *       {@code settlementDedupExcessDoesNotResurrectTheOriginalDoubleCount} 回归测试）</li>
     *   <li>已耗用量 &lt; 结单量 → 只把超出的那一段(不是整个结单量)算作新贡献</li>
     *   <li>已耗用量本身未知(耗用行没有可读的 quantity —— 生产库 {@code material_consumptions
     *       .quantity} 是 {@code NOT NULL}, 这一分支只会在测试桩省略字段时触发) →
     *       保守按【已全额覆盖】处理(与订正前的存在即覆盖同向), <b>不</b>当 0 处理——
     *       当 0 处理会把「量不明」误判成「没消耗」重新计入, 复活双计风险</li>
     * </ul>
     * 同一物料批次可能对应多行结单领用: 按 {@code rows} 原有顺序逐行核销「已耗用量」这份预算
     * (每核销掉一部分, 预算相应减少), 使多行合计的贡献仍然等于
     * {@code max(0, Σ结单量 − 已耗用量)}, 不会因为拆行而多算。
     *
     * @param covered 本批次已经从 {@code material_consumptions} 取到的行（仅当前批次自己的维度，
     *                不含上游 CLERK_WIP 批次；本方法内部会另外并上计划全量去重集合）
     * @param plansMerged 已并入过的计划 id；同一计划的多个生产批次只并入一次
     */
    private List<MaterialConsumption> settlementConsumptionsFor(
            String factoryId,
            ProductionBatch batch,
            List<MaterialConsumption> covered,
            java.util.Set<String> plansMerged) {
        if (settlementConsumptionRepository == null || batch == null
                || batch.getProductionPlanId() == null) {
            return List.of();
        }
        String planId = batch.getProductionPlanId();
        if (!plansMerged.add(planId)) {
            return List.of();   // 这个计划已经并过了, 多批次不重复计
        }
        List<ProductionSettlementConsumption> rows;
        try {
            rows = settlementConsumptionRepository
                    .findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(factoryId, planId);
        } catch (RuntimeException ex) {
            // ⛔ 读不到结单领用不能让整个成本分解挂掉, 但也不装作没这回事 —— 留痕给排查用。
            log.warn("[M67CostBreakdown] 结单领用行读不出来 factory={} plan={}: {}",
                    factoryId, planId, ex.getMessage());
            return List.of();
        }
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        // 已耗用量预算(按物料批次 id 求和) + 耗用量未知的批次(保守视为已全额覆盖)。
        // 🔴 bug fix 2026-09-04: covered 只是【当前(成品)批次自己】的消耗行, 看不到 traceCost
        // 沿谱系回溯到的上游 CLERK_WIP 批次自己的消耗行 —— 补一个【计划全量、含 CLERK_WIP】的并集,
        // 见上方类 javadoc「2026-09-04 缺陷订正」与仓库方法自身 javadoc。covered 与 planWide 两个
        // 来源各自累加(不去重求并集的行本身) —— 即使 prod 上 covered ⊆ planWide 而被重复计入,
        // 后果只是「已耗用量预算」偏高、超出量偏保守(更容易判 0 不再计入), 与 #576 的双计缺陷方向相反,
        // 不会复活它。
        Map<String, BigDecimal> consumedQtyBudget = new java.util.HashMap<>();
        java.util.Set<String> unknownQtyBatches = new java.util.HashSet<>();
        accumulateConsumedQty(covered, consumedQtyBudget, unknownQtyBatches);
        accumulateConsumedQty(planWideConsumedMaterialConsumptions(factoryId, planId),
                consumedQtyBudget, unknownQtyBatches);

        List<MaterialConsumption> merged = new ArrayList<>();
        for (ProductionSettlementConsumption r : rows) {
            if (r == null || r.getMaterialBatchId() == null) {
                continue;
            }
            String materialBatchId = r.getMaterialBatchId();
            if (unknownQtyBatches.contains(materialBatchId)) {
                continue;   // 已消耗但耗用量未知 —— 保守按已全额覆盖处理, 不重新计入
            }
            BigDecimal settlementQty = nz(r.getQuantity());
            BigDecimal consumedBudget = consumedQtyBudget.getOrDefault(materialBatchId, BigDecimal.ZERO);
            BigDecimal excessQty = settlementQty.subtract(consumedBudget).max(BigDecimal.ZERO);
            // 核销预算: 这一行核掉了 min(结单量, 预算) 那么多, 剩余预算留给同一物料批次的下一行结单行。
            consumedQtyBudget.put(materialBatchId, consumedBudget.subtract(settlementQty.subtract(excessQty)));
            if (excessQty.signum() <= 0) {
                continue;   // 结单量已被耗用记录足额(或超额)覆盖 —— 贡献 0, 不重复计(#576 场景)
            }
            MaterialBatch mb = materialBatchRepository
                    .findByIdAndFactoryId(materialBatchId, factoryId).orElse(null);
            BigDecimal unitPrice = mb == null ? null : mb.getUnitPrice();
            MaterialConsumption c = new MaterialConsumption();
            c.setFactoryId(factoryId);
            c.setProductionPlanId(planId);
            c.setProductionBatchId(batch.getId());
            c.setBatchId(materialBatchId);
            c.setMaterialTypeId(r.getMaterialTypeId());
            c.setSourceType(r.getSourceType());
            c.setQuantity(excessQty);
            c.setUnitPrice(unitPrice);
            // ⛔ 价未知就让 totalCost 保持 null: 下游 traceCost 的 nz() 会当 0 算,
            //    而 missingCostItems 靠 unitPrice/totalCost 皆为 null 才报得出「缺价」。
            c.setTotalCost(unitPrice == null ? null : excessQty.multiply(unitPrice));
            c.setConsumptionTime(r.getCreatedAt());
            merged.add(c);
        }
        if (!merged.isEmpty()) {
            log.info("[M67CostBreakdown] 并入结单领用 {} 行(超出已耗用量部分) factory={} plan={}",
                    merged.size(), factoryId, planId);
        }
        return merged;
    }

    /**
     * 把一批 {@link MaterialConsumption} 累加进「已耗用量预算」——按 {@code batchId} 求和
     * {@code quantity}；{@code quantity} 读不到(null)的行, 把该 {@code batchId} 记进
     * {@code unknownBatches}(保守: 已消耗但量不明, 调用方按【已全额覆盖】处理, 见
     * {@link #settlementConsumptionsFor} javadoc「2026-09-04 缺陷订正 II」)。
     *
     * <p>生产库 {@code material_consumptions.quantity} 是 {@code NOT NULL} 列, 这条
     * unknown 分支只会在测试桩故意省略字段时触发, 不改变生产环境的行为。
     */
    private static void accumulateConsumedQty(List<MaterialConsumption> consumptions,
                                               Map<String, BigDecimal> budget,
                                               java.util.Set<String> unknownBatches) {
        if (consumptions == null) {
            return;
        }
        for (MaterialConsumption mc : consumptions) {
            if (mc == null || mc.getBatchId() == null) {
                continue;
            }
            if (mc.getQuantity() == null) {
                unknownBatches.add(mc.getBatchId());
            } else {
                budget.merge(mc.getBatchId(), mc.getQuantity(), BigDecimal::add);
            }
        }
    }

    /**
     * 计划全量(含 CLERK_WIP)已产生的 {@link MaterialConsumption} 行 —— {@link #settlementConsumptionsFor}
     * 「已耗用量预算」的第二个来源, 详见其上方类 javadoc「2026-09-04 缺陷订正」。
     *
     * <p>{@code consumptionRepository} 是构造器注入的 final 字段, 生产环境恒非 null；这里的
     * null-guard 只是为了兼容本类既有的
     * {@code mock(OrderCostBreakdownService.class, CALLS_REAL_METHODS)} 风格单测(不过构造器,
     * 字段默认 null) —— 与 {@code settlementConsumptionRepository == null} 同一条纪律。
     * 读挂了同样降级为「本轮不额外去重」(退回改前行为), 不让整个成本分解崩, 但留痕。
     */
    private List<MaterialConsumption> planWideConsumedMaterialConsumptions(String factoryId, String planId) {
        if (consumptionRepository == null) {
            return List.of();
        }
        List<MaterialConsumption> planWide;
        try {
            planWide = consumptionRepository
                    .findByFactoryIdAndPlanProductionBatchesIncludingWip(factoryId, planId);
        } catch (RuntimeException ex) {
            log.warn("[M67CostBreakdown] 计划全量消耗行读不出来(结单去重降级为仅当前批次) factory={} plan={}: {}",
                    factoryId, planId, ex.getMessage());
            return List.of();
        }
        return planWide == null ? List.of() : planWide;
    }

    private BigDecimal[] traceCost(String factoryId, MaterialConsumption c, int depth, java.util.Set<Long> visited) {
        BigDecimal own = nz(c.getTotalCost());
        if (c.getBatchId() == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        if (depth >= MAX_DEPTH) {
            log.warn("[M67CostBreakdown] traceCost 达 MAX_DEPTH={} (factory={}, batchId={}) — 按叶子截断, 疑似超深链/环", MAX_DEPTH, factoryId, c.getBatchId());
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
        if (mb == null || !"PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType()) || mb.getSourceDocId() == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};   // 叶子 (原料/外购)
        }
        Long upstreamBatchId = parseLong(mb.getSourceDocId());
        if (upstreamBatchId == null) {
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        if (!visited.add(upstreamBatchId)) {   // 真环检测: 该上游批次已在**当前回溯路径**(祖先链)
            log.warn("[M67CostBreakdown] 检测到批次谱系环 (factory={}, upstreamBatchId={}) — 截断防无限递归", factoryId, upstreamBatchId);
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        // ★ 严格审计 2026-06-25: visited 改**路径作用域**(出栈即移除) —— 否则 diamond(同上游被多个兄弟
        // 消耗, 如 焯水1-1+焯水1-2 都源自滚揉1-1) 第二条路径被误判为环 → 回退 own(WIP含labor) → labor
        // 漏进 raw。双计由下方 consumedQty/upstreamQty 分摊防, 不靠 visited, 故移除合法 diamond 不双计。
        List<MaterialConsumption> up = consumptionRepository.findByProductionBatchIdAndFactoryId(upstreamBatchId, factoryId);
        if (up.isEmpty()) {
            visited.remove(upstreamBatchId);
            return new BigDecimal[]{own, BigDecimal.valueOf(depth)};
        }
        BigDecimal sum = BigDecimal.ZERO;
        int maxChildDepth = depth;
        for (MaterialConsumption u : up) {
            BigDecimal[] r = traceCost(factoryId, u, depth + 1, visited);
            sum = sum.add(r[0]);
            maxChildDepth = Math.max(maxChildDepth, r[1].intValue());
        }
        // ★ SP-B1: 按消耗比例分摊上游成本, 防共享上游双重计数。
        // 向后兼容(Opus 终审收紧): 仅当 consumedQty 与 upstreamReceiptQty 都可用时才分摊;
        // 1:1 全量消耗时 consumedQty==upstreamReceiptQty → 比例=1 → apportioned==sum (无变化)。
        // 缺量兜底**严格保留改前行为** (sum>0?sum:own), 不切换到 own —— 避免影响 receiptQuantity
        // 缺失的既有订单成本数据。新 SP-B1 物化的 WIP 批必带 receiptQuantity, 故分摊分支总能命中。
        // ★ 严格审计 2026-06-25 (Edge G): 真实 0 消耗 (consumedQty 显式 = 0, 非 null) →
        // 本批未从该上游源取料, 贡献 0 成本。**显式 0 必须早返回**, 否则下方 legacy 兜底
        // (sum>0?sum:own) + L 末 (apportioned>0?apportioned:own) 两处都会把它推成"全量", 造成
        // 混批 feed=0 的源被全额计入 → 原料虚高 (Edge G: g1投50+g2投0, 原料应 466 实得 932)。
        // 仅对**显式 0** 生效; consumedQty 为 null (缺量旧数据) 仍走下方 legacy 兜底, 零回归。
        if (c.getQuantity() != null && c.getQuantity().signum() == 0) {
            visited.remove(upstreamBatchId);
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.valueOf(maxChildDepth)};
        }
        BigDecimal legacy = sum.signum() > 0 ? sum : own;
        BigDecimal consumedQty = nz(c.getQuantity());
        BigDecimal upstreamQty = nz(mb.getReceiptQuantity());
        BigDecimal apportioned;
        if (sum.signum() > 0 && consumedQty.signum() > 0 && upstreamQty.signum() > 0) {
            apportioned = sum.multiply(consumedQty)
                    .divide(upstreamQty, 4, java.math.RoundingMode.HALF_UP);
        } else {
            apportioned = legacy;   // 缺量数据 → 改前行为, 既有数据零回归
        }
        visited.remove(upstreamBatchId);   // 路径作用域出栈 (见上方 diamond 注释)
        return new BigDecimal[]{apportioned.signum() > 0 ? apportioned : own, BigDecimal.valueOf(maxChildDepth)};
    }

    /**
     * SP-F ①b: 沿 WIP 链回溯, 累加上游 *生产批次* 的 人工/调料 成本, 按与 {@link #traceCost} 完全相同的
     * {@code consumedQty/upstreamReceiptQty} 比例分摊。返回 {@code [labor, seasoning]}。
     *
     * <p><b>仅 WIP 链 (clerk/逐工序录入) 受影响:</b> 只有当某消耗的上游 {@link MaterialBatch} 是
     * {@code sourceDocType='PRODUCTION_BATCH'} 且该生产批有自己的消耗时, 才递归进上游并累加其
     * 人工/调料。常规生产批次不产出此类 MaterialBatch 被消耗 (它们经成品库存/SemiFinishedInventory 流转,
     * 非 MaterialConsumption→MaterialBatch(PRODUCTION_BATCH) 链), 故 {@code aggregate} 在常规批上
     * 命中 leaf-分支立即返回 [0,0] —— 对常规批成本拆分零回归。
     *
     * <p>本批自身的人工/调料<b>不</b>在此累加 (已由 computeForBatches 的 getYield 主循环计入);
     * 此方法从上游 (depth≥2) 起累加, 避免双计。叶子/缺量/环/超深 → 返回 [0,0]。
     *
     * <p>分摊镜像 traceCost: 上游成本 sum × consumedQty/upstreamReceiptQty。1:1 全量消耗时比例=1。
     * 缺量 (consumedQty/upstreamReceiptQty 任一为 null/0) → 不分摊, 取上游全额 (与 traceCost 的
     * legacy 兜底一致, WIP 物化批必带 receiptQuantity 故分摊分支总命中)。
     */
    private BigDecimal[] aggregateUpstreamLaborSeasoning(String factoryId, MaterialConsumption c,
                                                         int depth, java.util.Set<Long> visited) {
        BigDecimal[] zero = {BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        if (c.getBatchId() == null || depth >= MAX_DEPTH) {
            return zero;
        }
        MaterialBatch mb = materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
        if (mb == null || !"PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType()) || mb.getSourceDocId() == null) {
            return zero;   // 叶子 (原料/外购) — 无上游生产批, 无可累加的人工/调料
        }
        Long upstreamBatchId = parseLong(mb.getSourceDocId());
        if (upstreamBatchId == null || !visited.add(upstreamBatchId)) {
            return zero;   // 解析失败 或 环 → 截断
        }
        // 上游生产批自身的 人工/调料 (该批的 getYield)
        BigDecimal[] self = batchLaborSeasoning(factoryId, upstreamBatchId);
        BigDecimal labor = self[0];
        BigDecimal seasoning = self[1];
        BigDecimal byproductCredit = self[2];   // ★ 副产回收沿 WIP 链传播 (严格审计 2026-06-25)
        // 再递归该上游批的消耗 → 更上游的 人工/调料/副产
        for (MaterialConsumption u : consumptionRepository.findByProductionBatchIdAndFactoryId(upstreamBatchId, factoryId)) {
            BigDecimal[] r = aggregateUpstreamLaborSeasoning(factoryId, u, depth + 1, visited);
            labor = labor.add(r[0]);
            seasoning = seasoning.add(r[1]);
            byproductCredit = byproductCredit.add(r[2]);
        }
        // ★ 按消耗比例分摊 (镜像 traceCost): 上游(含更上游)的 人工/调料/副产 × consumedQty/upstreamReceiptQty。
        BigDecimal consumedQty = nz(c.getQuantity());
        BigDecimal upstreamQty = nz(mb.getReceiptQuantity());
        if (consumedQty.signum() > 0 && upstreamQty.signum() > 0) {
            labor = labor.multiply(consumedQty).divide(upstreamQty, 4, RoundingMode.HALF_UP);
            seasoning = seasoning.multiply(consumedQty).divide(upstreamQty, 4, RoundingMode.HALF_UP);
            byproductCredit = byproductCredit.multiply(consumedQty).divide(upstreamQty, 4, RoundingMode.HALF_UP);
        }
        // 缺量 → 取上游全额 (不分摊), 与 traceCost legacy 兜底一致。
        // ★ 严格审计 2026-06-25: 路径作用域出栈 — 允许兄弟 diamond 路径遍历同上游
        // (否则第二条路径被误判为环 → 返 zero → diamond 下 labor/调料/副产少计; 镜像 traceCost)。
        visited.remove(upstreamBatchId);
        return new BigDecimal[]{labor, seasoning, byproductCredit};
    }

    /**
     * SP-F ①b: 计算单个生产批次 *自身* (不含上游链) 的 人工/调料 成本, 返回 {@code [labor, seasoning]}。
     *
     * <p>人工 = {@code getYield(batchId).totalLaborCost}。调料 = Σ 该批各道 SEASONING 桶 materialCost
     * (按 {@link #resolveCostBucket} 分类; 与 computeForBatches 主循环同口径, 含 AUDIT-004 共享锅分摊)。
     * 这是为 WIP 上游批准备的 —— clerk WIP 批的调料报工不带共享锅 (auxPotNo=null), 故走原样累加分支。
     */
    private BigDecimal[] batchLaborSeasoning(String factoryId, Long batchId) {
        BatchYieldDTO y = yieldReportService.getYield(factoryId, batchId);
        if (y == null) {
            return new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        }
        BigDecimal labor = nz(y.getTotalLaborCost());
        BigDecimal seasoning = BigDecimal.ZERO;
        // ★ 严格审计 2026-06-25: 同时归集本批副产回收 (qty×unitPrice), 供上游 WIP 链传播
        // (多批链场景: 副产在上游半成品批回收, 之前不传播到成品批 byproductCredit → 回收额丢失)。
        java.util.LinkedHashMap<String, ByproductLine> bpAcc = new java.util.LinkedHashMap<>();
        List<StepYieldDTO> steps = y.getSteps() == null ? List.of() : y.getSteps();
        for (int i = 0; i < steps.size(); i++) {
            StepYieldDTO step = steps.get(i);
            accumulateByproducts(bpAcc, step.getByproducts());   // 副产不依赖 materialCost, 先于下方 null-check
            BigDecimal m = step.getMaterialCost();
            if (m == null) {
                continue;
            }
            String bucket = resolveCostBucket(step.getCostCategory(), i, steps.size(), factoryId, "WIP:" + batchId);
            if (!"SEASONING".equals(bucket)) {
                continue;   // 只取 SEASONING 桶; PACKAGING/SKIP 不计 (包装由末道成品计, 原料由 traceCost 承载)
            }
            BigDecimal contribution = m;
            if (step.getAuxPotNo() != null && step.getAuxPotTotalCost() != null) {
                BigDecimal potOutput = nz(productionReportRepository.sumOutputByAuxPotNo(factoryId, step.getAuxPotNo()));
                BigDecimal myOutput = nz(step.getTotalOutput());
                contribution = potOutput.signum() > 0
                        ? step.getAuxPotTotalCost().multiply(myOutput).divide(potOutput, 2, RoundingMode.HALF_UP)
                        : step.getAuxPotTotalCost();
            }
            seasoning = seasoning.add(contribution);
        }
        BigDecimal byproductCredit = bpAcc.values().stream().map(l -> nz(l.getValue())).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new BigDecimal[]{labor, seasoning, byproductCredit};
    }

    /**
     * 归集副产物明细 (名称|单位 维度跨批次累加)。变现价值 = quantity×unitPrice;
     * 报工 jsonb 可直接录 value/totalValue (优先), 否则按 quantity×unitPrice; 单价缺失 → value=null (诚实, 不臆造)。
     */
    private void accumulateByproducts(LinkedHashMap<String, ByproductLine> acc, List<Map<String, Object>> raws) {
        if (raws == null) {
            return;
        }
        for (Map<String, Object> r : raws) {
            if (r == null) {
                continue;
            }
            String name = r.get("name") == null ? "副产物" : String.valueOf(r.get("name")).trim();
            String unit = r.get("unit") == null ? "" : String.valueOf(r.get("unit")).trim();
            BigDecimal qty = toBigDecimal(r.get("quantity"));
            BigDecimal unitPrice = toBigDecimal(r.get("unitPrice"));
            BigDecimal value = toBigDecimal(r.get("value"));
            if (value == null) {
                value = toBigDecimal(r.get("totalValue"));
            }
            if (value == null && qty != null && unitPrice != null) {
                value = qty.multiply(unitPrice);
            }
            String key = name + "|" + unit;
            ByproductLine line = acc.get(key);
            if (line == null) {
                line = ByproductLine.builder().name(name).unit(unit)
                        .quantity(BigDecimal.ZERO).build();
                acc.put(key, line);
            }
            if (qty != null) {
                line.setQuantity(nz(line.getQuantity()).add(qty));
            }
            if (value != null) {
                line.setValue(nz(line.getValue()).add(value));
            }
            // 归集后 unitPrice 由 value/quantity 反推 (混录多单价时取加权), 仅 value 有值时
            if (line.getValue() != null && line.getQuantity() != null && line.getQuantity().signum() > 0) {
                line.setUnitPrice(line.getValue().divide(line.getQuantity(), 4, RoundingMode.HALF_UP));
            }
        }
    }

    /**
     * AUDIT-002: 归集包装明细 (名称维度跨批次累加成本)。jsonb [{name, cost}]; cost 缺失则按 quantity×unitPrice。
     */
    private void accumulatePackaging(LinkedHashMap<String, BigDecimal> acc, List<Map<String, Object>> raws) {
        if (raws == null) {
            return;
        }
        for (Map<String, Object> r : raws) {
            if (r == null) {
                continue;
            }
            String name = r.get("name") == null ? "其他" : String.valueOf(r.get("name")).trim();
            BigDecimal cost = toBigDecimal(r.get("cost"));
            if (cost == null) {
                BigDecimal qty = toBigDecimal(r.get("quantity"));
                BigDecimal unitPrice = toBigDecimal(r.get("unitPrice"));
                if (qty != null && unitPrice != null) {
                    cost = qty.multiply(unitPrice);
                }
            }
            if (cost == null) {
                continue;   // 无成本信息的明细项跳过 (诚实, 不臆造)
            }
            acc.merge(name, cost, BigDecimal::add);
        }
    }

    /**
     * 从 {@code bom_recipes} + {@code bom_recipe_items} 现拼一份与 {@code bom_versions}
     * 同形状的快照。
     *
     * @return {@code null} 表示回退不可用(仓储缺失 / recipe 不存在 / <b>版本号对不上</b>)
     */
    // ⚠️ 2026-08-27: 参数从 plan 换成 (recipeId, version) —— 主路径已改读【批次】绑定,
    //    兜底若仍读计划的, 两边会用不同的配方。同源才有意义。
    private Map<String, Object> pinnedBomSnapshotFromRecipe(String factoryId,
                                                            String pinnedRecipeId,
                                                            Integer pinnedBomVersion) {
        if (bomRecipeFallbackRepository == null || bomRecipeItemFallbackRepository == null) {
            return null;
        }
        com.cretas.aims.entity.bom.BomRecipe recipe;
        try {
            recipe = bomRecipeFallbackRepository.findById(pinnedRecipeId)
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .orElse(null);
        } catch (RuntimeException ex) {
            log.warn("[M67CostBreakdown] BOM 回退读取失败 factory={} recipe={}: {}",
                    factoryId, pinnedRecipeId, ex.getMessage());
            return null;
        }
        // 🔴 版本号必须对得上 —— 否则就是拿新版本的料冒充旧版本, 历史成本会悄悄变化。
        if (recipe == null || !Objects.equals(recipe.getVersion(), pinnedBomVersion)) {
            return null;
        }
        List<com.cretas.aims.entity.bom.BomRecipeItem> items;
        try {
            items = bomRecipeItemFallbackRepository.findByRecipeIdOrderBySortOrderAsc(recipe.getId());
        } catch (RuntimeException ex) {
            log.warn("[M67CostBreakdown] BOM 物料行回退读取失败 recipe={}: {}",
                    recipe.getId(), ex.getMessage());
            return null;
        }
        if (items == null) {
            return null;
        }
        // Multi-output CO_PRODUCT/BY_PRODUCT members may intentionally own no input rows: the
        // shared input/packaging fact belongs to the report group. Their zero-item recipe is still
        // a valid snapshot only when recipe identity, version and output basis above/below verify.
        // MAIN keeps the historical fail-closed behavior.
        if (items.isEmpty()
                && com.cretas.aims.entity.bom.BomRecipe.bearsOwnInputRows(recipe.getOutputRole())) {
            return null;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (com.cretas.aims.entity.bom.BomRecipeItem it : items) {
            if (it == null) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("materialCategory", it.getMaterialCategory());
            row.put("materialTypeId", it.getMaterialTypeId());
            row.put("materialName", it.getMaterialName());
            row.put("standardQuantity", it.getStandardQuantity());
            row.put("unit", it.getUnit());
            // ⛔ 与 bom_versions 快照同一个键名和同一个口径: itemCost 是缓存的行成本。
            //    它为 0 时下游会判为「不可信」(见 pinnedCostUsable), 这里不做特殊处理。
            row.put("itemCost", it.getItemCost());
            rows.add(row);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("outputQuantityPerUnit", recipe.getOutputQuantityPerUnit());
        snapshot.put("outputUnit", recipe.getOutputUnit());
        snapshot.put("items", rows);
        log.info("[M67CostBreakdown] bom_versions 无快照, 回退 bom_recipes 现拼 factory={} recipe={} v={}",
                factoryId, recipe.getId(), recipe.getVersion());
        return snapshot;
    }

    private PinnedPackagingResult resolvePinnedPackaging(String factoryId, ProductionPlan plan,
                                                          ProductionBatch batch,
                                                          BigDecimal outputQuantity, String outputUnit) {
        // 🔴 2026-08-27: 优先读【批次】创建时钉下的绑定, 而不是计划的。
        //    业务口径: 批次的账按它创建时绑定的那一版走 —— 9 月改配方不该让 8 月的账变。
        //    实测覆盖率: 批次侧 61/61 全填; 计划侧 43 个里只有 36 个有绑定。
        BatchBomBinding.Resolved binding = BatchBomBinding.resolve(batch, plan);
        if (!binding.pinned()) {
            return PinnedPackagingResult.notPinned();
        }
        final String pinnedRecipeId = binding.recipeId();
        final Integer pinnedBomVersion = binding.version();
        List<String> missing = new ArrayList<>();
        if (bomVersionRepository == null) {
            missing.add("PINNED_BOM_SNAPSHOT_UNAVAILABLE:" + plan.getId());
            return new PinnedPackagingResult(true, BigDecimal.ZERO, List.of(), missing);
        }
        BomVersion version = bomVersionRepository
                .findByFactoryIdAndBomRecipeIdOrderByVersionNumberDesc(factoryId, pinnedRecipeId)
                .stream()
                .filter(item -> Objects.equals(item.getVersionNumber(), pinnedBomVersion))
                .findFirst()
                .orElse(null);
        Map<String, Object> snapshot = version == null ? null : version.getSnapshotJson();
        if (snapshot == null) {
            // ── 2026-08-25: 画布那条路【不写】 bom_versions ────────────────────────
            //   实测(生产库): F006 13 个 ACTIVE bom_recipes 里, 在 bom_versions 有对应
            //   版本快照的是【0 个】; bom_versions 全库 9 行、最近写入停在 2026-08-02,
            //   只有 ECN 手工流程(BomVersionService)才写它。
            //   而本方法只读 bom_versions ⇒ 对画布建的 BOM 永远读不到 ⇒ 7/23 个生产批次
            //   卡在 PINNED_BOM_SNAPSHOT_MISSING, 包材成本被算成 0 且整单算不出单位成本。
            //   本仓形态 D: 同一个东西两份, 读的是不再维护的那份。
            //
            //   ⛔ 回退【只在版本号对得上时】才用: recipe.version == plan.selectedBomVersion
            //      保证 bom_recipe_items 里躺着的就是那个版本的内容。对不上说明 recipe 已经
            //      往前走了、那个版本的内容确实丢了 —— 那时仍然诚实报 SNAPSHOT_MISSING,
            //      不拿新版本的料去冒充旧版本(那会让历史成本悄悄变化)。
            snapshot = pinnedBomSnapshotFromRecipe(factoryId, pinnedRecipeId, pinnedBomVersion);
        }
        if (snapshot == null) {
            missing.add("PINNED_BOM_SNAPSHOT_MISSING:" + pinnedRecipeId
                    + ":v" + pinnedBomVersion);
            return new PinnedPackagingResult(true, BigDecimal.ZERO, List.of(), missing);
        }
        BigDecimal recipeOutput = toBigDecimal(snapshot.get("outputQuantityPerUnit"));
        String recipeOutputUnit = snapshot.get("outputUnit") == null ? null
                : String.valueOf(snapshot.get("outputUnit"));
        BigDecimal outputInRecipeUnit = convertPinnedOutputQuantity(
                outputQuantity, outputUnit, recipeOutputUnit, plan);
        if (outputInRecipeUnit == null || outputInRecipeUnit.signum() <= 0 || recipeOutput == null
                || recipeOutput.signum() <= 0) {
            missing.add("PINNED_BOM_OUTPUT_BASIS_UNVERIFIABLE:" + pinnedRecipeId
                    + ":v" + pinnedBomVersion);
            return new PinnedPackagingResult(true, BigDecimal.ZERO, List.of(), missing);
        }
        BigDecimal scale = outputInRecipeUnit.divide(recipeOutput, 12, RoundingMode.HALF_UP);
        Object rawItems = snapshot.get("items");
        if (!(rawItems instanceof List<?> items)) {
            missing.add("PINNED_BOM_ITEMS_MISSING:" + pinnedRecipeId
                    + ":v" + pinnedBomVersion);
            return new PinnedPackagingResult(true, BigDecimal.ZERO, List.of(), missing);
        }
        // ⚠️ version 在【走了 recipe 兜底】时仍然是 null —— 上面 snapshot==null 那个分支
        //    只救回了 snapshot, 没有救回 version 对象。2026-08-26 生产实测: 兜底【成功】时
        //    这里 version.getVersionNumber() 直接 NPE ⇒ /cost-breakdown 500,
        //    界面上是「系统处理异常, 请稍后重试」。
        //    也就是说 8-25 加的这条恢复路径, 把「诚实报 SNAPSHOT_MISSING」变成了崩溃,
        //    而且【只在它成功的时候崩】。
        //
        //    版本号本身是知道的: pinnedBomSnapshotFromRecipe 只在
        //    recipe.version == plan.selectedBomVersion 时才返回非 null(见 1034 行),
        //    所以兜底路径的版本号就是 pinnedBomVersion。
        //    ⛔ 但价格【来源】不能混为一谈: 来自快照和来自当前配方是两回事,
        //       前者是冻结的历史, 后者是「现在的配方恰好还是那个版本」。分别标注。
        Integer pinnedVersionNumber = version != null
                ? version.getVersionNumber()
                : pinnedBomVersion;
        String pinnedPriceSource = (version != null
                ? "PINNED_BOM_ITEM_COST:v"
                : "RECIPE_FALLBACK_ITEM_COST:v") + pinnedVersionNumber;
        List<PackagingDetail> details = new ArrayList<>();
        BigDecimal knownCost = BigDecimal.ZERO;
        for (Object rawItem : items) {
            if (!(rawItem instanceof Map<?, ?> item)
                    || !"PACKAGING".equalsIgnoreCase(String.valueOf(item.get("materialCategory")))) {
                continue;
            }
            String materialCode = stringValue(item.get("materialTypeId"));
            String materialName = stringValue(item.get("materialName"));
            BigDecimal standardQuantity = toBigDecimal(item.get("standardQuantity"));
            BigDecimal quantity = standardQuantity == null ? null : standardQuantity.multiply(scale);
            // ── 2026-08-25 owner 裁定: BOM 里的 itemCost=0 是【没填】, 不是「不要钱」 ──────
            //   实测(生产库) 同一批包材, 两处读数完全对不上:
            //
            //     包材            raw_material_types.unit_price  含税价   BOM itemCost
            //     成品盒                              0.01        0.01      0.0000
            //     外箱                                0.44        0.50      0.0000
            //     封膜                                8.85       10.00      0.0000
            //
            //   成本真值录在【物料 SKU】那边(raw_material_types / 采购→批次单价),
            //   BOM 的 itemCost 只是一份没同步上的冗余字段, 全 0。
            //   按 0 计入 = 把「没填」当成「零成本」, 会系统性低估 COGS、虚增毛利
            //   —— 本仓明令禁止的那种假数据。
            //
            //   ⛔ 注意 RAW 行的 itemCost 是 null 而 PACKAGING 行是 0(实测 16 : 5),
            //      同一个人录同一张表却是两种空值 ⇒ 这个 0 是默认值, 不是录入值。
            //
            //   ⚠️ 这里【只判不可信】, 不在本方法里替它找价: 找价的活已经有人干了 ——
            //      判为不可信后整份快照回退 notPinned(), 包材成本走实际领用
            //      (material_consumptions.unitPrice, 来自采购真实单价)。见下方 allPinnedCostsUnusable。
            BigDecimal pinnedItemCost = toBigDecimal(item.get("itemCost"));
            boolean pinnedCostUsable = pinnedItemCost != null && pinnedItemCost.signum() != 0;
            BigDecimal amount = pinnedCostUsable ? pinnedItemCost.multiply(scale) : null;
            BigDecimal effectiveUnitPrice = pinnedItemCost != null && standardQuantity != null
                    && standardQuantity.signum() > 0
                    ? pinnedItemCost.divide(standardQuantity, 8, RoundingMode.HALF_UP)
                    : null;
            boolean collected = quantity != null && amount != null;
            String missingKey = "PACKAGING_PRICE:" + (materialCode == null ? materialName : materialCode);
            if (!collected) missing.add(missingKey);
            if (amount != null) knownCost = knownCost.add(amount);
            details.add(PackagingDetail.builder()
                    .materialCode(materialCode)
                    .materialName(materialName)
                    .quantity(quantity)
                    .unit(stringValue(item.get("unit")))
                    .unitPrice(effectiveUnitPrice)
                    .priceSource(pinnedPriceSource)
                    .amount(amount)
                    .collectionStatus(collected ? "COLLECTED" : "MISSING_PRICE")
                    .missingReason(collected ? null : "pinned BOM 缺少可验证 itemCost/标准耗用量")
                    .build());
        }
        // ⛔ 【不要】在这里回退 notPinned(): 2026-08-25 实测那样做会丢掉缺项 ——
        //    回退后包材走实际领用, 而那些批次的实际包材领用行数是 0, 于是
        //    包材成本 0 且【一条缺项都不报】⇒ costComplete=true / totalCost=0.0,
        //    从「诚实报缺」变成了「报 0 并说它是完整的」。那比原来更糟。
        //    保持 pinned: amount=null → collected=false → 照常报 PACKAGING_PRICE,
        //    整单判 PARTIAL、totalCost 留 null。
        return new PinnedPackagingResult(true, knownCost, details, missing);
    }

    private void accumulateLaborDetails(List<LaborDetail> details, List<String> missing,
                                        StepYieldDTO step, boolean auditEligible) {
        List<Map<String, Object>> segments = step.getLaborSegments();
        if (segments == null || segments.isEmpty()) {
            if (step.getTotalWorkMinutes() == null && step.getTotalWorkers() == null
                    && step.getLaborCost() == null) {
                return;
            }
            int workers = step.getTotalWorkers() == null ? 0 : step.getTotalWorkers();
            int duration = step.getTotalWorkMinutes() == null ? 0 : step.getTotalWorkMinutes();
            addLaborDetail(details, missing, step, workers, null, null, duration,
                    workers * duration, step.getLaborCost(), auditEligible);
            return;
        }
        int totalLaborMinutes = 0;
        for (Map<String, Object> segment : segments) {
            Integer workers = toInteger(segment.get("headcount"));
            Integer duration = durationMinutes(stringValue(segment.get("startTime")),
                    stringValue(segment.get("endTime")));
            if (workers != null && duration != null) totalLaborMinutes += workers * duration;
        }
        BigDecimal rate = step.getLaborCost() != null && totalLaborMinutes > 0
                ? step.getLaborCost().multiply(BigDecimal.valueOf(60))
                        .divide(BigDecimal.valueOf(totalLaborMinutes), 8, RoundingMode.HALF_UP)
                : null;
        for (Map<String, Object> segment : segments) {
            Integer workers = toInteger(segment.get("headcount"));
            String start = stringValue(segment.get("startTime"));
            String end = stringValue(segment.get("endTime"));
            Integer duration = durationMinutes(start, end);
            int laborMinutes = workers == null || duration == null ? 0 : workers * duration;
            addLaborDetail(details, missing, step, workers == null ? 0 : workers, start, end,
                    duration == null ? 0 : duration, laborMinutes, rate == null ? null
                            : rate.multiply(BigDecimal.valueOf(laborMinutes))
                                    .divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP), auditEligible);
        }
    }

    private void addLaborDetail(List<LaborDetail> details, List<String> missing, StepYieldDTO step,
                                int workers, String start, String end, int duration, int laborMinutes,
                                BigDecimal amount, boolean auditEligible) {
        BigDecimal hours = laborMinutes > 0
                ? BigDecimal.valueOf(laborMinutes).divide(BigDecimal.valueOf(60), 4, RoundingMode.HALF_UP)
                : null;
        BigDecimal rate = amount != null && hours != null && hours.signum() > 0
                ? amount.divide(hours, 8, RoundingMode.HALF_UP) : null;
        boolean collected = workers > 0 && duration > 0 && rate != null;
        // ── 2026-08-25: 「明细不全」不等于「金额未知」 ──────────────────────────
        //   实测(prod, 批次 PB-PLAN-1787622818112-B3898275-15036):
        //     proc=2  workers=null  duration=null  rate=null  amount=26.00
        //   人工【金额是有的】(26.00, 已计入 laborCost 51.57 → knownCostSubtotal 93.12),
        //   缺的只是「这笔钱对应多少工时」。原来两种情形共用一个码 LABOR_RATE_OR_TIME,
        //   于是「金额齐了但没工时段」也把整个 totalCost 抹掉 —— 把可审计性问题
        //   误判成了金额缺口。本仓形态 A: 我量的这个数不是我真正想知道的那个数。
        //   ⛔ amount 为 null 才是真的不知道 —— 那个照旧阻断。
        if (!collected && auditEligible) {
            missing.add((amount == null
                    ? "LABOR_COST_UNKNOWN:process-"
                    : "LABOR_TIME_DETAIL_MISSING:process-") + step.getProcessOrder());
        }
        details.add(LaborDetail.builder()
                .processOrder(step.getProcessOrder())
                .processName(step.getProcessName())
                .workerCount(workers > 0 ? workers : null)
                .startTime(start)
                .endTime(end)
                .durationMinutes(duration > 0 ? duration : null)
                .laborMinutes(laborMinutes > 0 ? laborMinutes : null)
                .laborHours(hours)
                .hourlyRate(rate)
                .rateSource(rate == null ? null : "DERIVED_FROM_PERSISTED_REPORT_LABOR_COST")
                .amount(amount)
                .collectionStatus(collected ? "COLLECTED" : "UNCOLLECTED")
                // ⛔ 原来这句是「缺少可验证工时段【或】持久化人工成本」——
                //    两件事糊成一句, 而它们对总成本的后果完全不同。
                .missingReason(collected ? null
                        : (amount == null ? "本道人工成本未知(缺费率或工时)"
                                : "人工成本已知, 但缺可验证工时段(不影响总成本)"))
                .build());
    }

    private BigDecimal toPinnedKilograms(BigDecimal quantity, String unit, ProductionPlan plan) {
        if (quantity == null || unit == null) return null;
        String normalized = normalizeUnit(unit);
        if ("kg".equals(normalized)) return quantity;
        if ("g".equals(normalized)) {
            return quantity.divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
        }
        if (plan == null || plan.getPlannedNetWeightGrams() == null
                || plan.getPlannedNetWeightGrams().signum() <= 0
                || !sameUnit(unit, plan.getPlannedUnit())) {
            return null;
        }
        return quantity.multiply(plan.getPlannedNetWeightGrams())
                .divide(BigDecimal.valueOf(1000), 4, RoundingMode.HALF_UP);
    }

    private BigDecimal convertPinnedOutputQuantity(BigDecimal quantity, String fromUnit,
                                                    String toUnit, ProductionPlan plan) {
        if (quantity == null || fromUnit == null || toUnit == null) return null;
        if (sameUnit(fromUnit, toUnit)) return quantity;
        BigDecimal kilograms = toPinnedKilograms(quantity, fromUnit, plan);
        if (kilograms == null) return null;
        String normalizedTarget = normalizeUnit(toUnit);
        if ("kg".equals(normalizedTarget)) return kilograms;
        if ("g".equals(normalizedTarget)) return kilograms.multiply(BigDecimal.valueOf(1000));
        if (plan == null || plan.getPlannedNetWeightGrams() == null
                || plan.getPlannedNetWeightGrams().signum() <= 0
                || !sameUnit(toUnit, plan.getPlannedUnit())) {
            return null;
        }
        return kilograms.multiply(BigDecimal.valueOf(1000))
                .divide(plan.getPlannedNetWeightGrams(), 8, RoundingMode.HALF_UP);
    }

    private static String mergeUnit(String current, String next) {
        if (next == null) return current;
        if (current == null) return next;
        if ("__MIXED__".equals(current)) return current;
        return sameUnit(current, next) ? current : "__MIXED__";
    }

    private static boolean sameUnit(String left, String right) {
        return left != null && right != null && Objects.equals(normalizeUnit(left), normalizeUnit(right));
    }

    /**
     * 订单成本拆分的单位归一 —— 走权威表的<b>跨语言</b>归一。
     *
     * <p>原表与 {@code YieldReportServiceImpl.normalizeUnit} 是<b>逐字相同的两份拷贝</b>,
     * 都只认 5 组。判错时 {@code sameUnit} 返回 false → {@code toPinnedKilograms} 返回 null
     * → 该行成本在 M67 页显示<b>「未归集」</b> (不会算出错数, 但也拿不到)。</p>
     *
     * <p>用 {@code crossLanguageCode} 而非 {@code canonicalCodeOrRaw}: 后者会把 只/个/件
     * 并成 pcs, 违反 #1976「一只 ≠ 一件」。</p>
     */
    private static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) return null;
        return com.cretas.aims.service.unit.impl.UnitContractServiceImpl.crossLanguageCode(unit);
    }

    private static Integer durationMinutes(String start, String end) {
        if (start == null || end == null) return null;
        try {
            LocalTime from = LocalTime.parse(start);
            LocalTime to = LocalTime.parse(end);
            long minutes = ChronoUnit.MINUTES.between(from, to);
            if (minutes < 0) minutes += 24 * 60;
            return Math.toIntExact(minutes);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static Integer toInteger(Object value) {
        BigDecimal decimal = toBigDecimal(value);
        return decimal == null ? null : decimal.intValue();
    }

    private static String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static BigDecimal positiveOrNull(BigDecimal... values) {
        for (BigDecimal value : values) {
            if (value != null && value.signum() > 0) return value;
        }
        return null;
    }

    private static <T, K> T uniqueOrNull(List<T> values, java.util.function.Function<T, K> key) {
        if (values == null || values.isEmpty()) return null;
        T first = values.get(0);
        K firstKey = key.apply(first);
        return values.stream().allMatch(value -> Objects.equals(firstKey, key.apply(value))) ? first : null;
    }

    private record PinnedPackagingResult(boolean pinned, BigDecimal knownCost,
                                         List<PackagingDetail> details, List<String> missingItems) {
        private static PinnedPackagingResult notPinned() {
            return new PinnedPackagingResult(false, BigDecimal.ZERO, List.of(), List.of());
        }
    }

    /**
     * CALC-003: 解析本道材料成本归入哪个桶 — PACKAGING(包装) / SEASONING(调料) / SKIP(原料, 由上游 traced 承载不计)。
     * 显式 costCategory 优先 (分类不依赖工序顺序); null 或未知值 → 回退 step-index 启发式 (末道=包装, 中间道=调料, 首道=原料)。
     */
    private String resolveCostBucket(String costCategory, int idx, int stepCount, String factoryId, String orderId) {
        if (costCategory != null) {
            switch (costCategory.trim().toUpperCase()) {
                case "PACKAGING":
                    return "PACKAGING";
                case "SEASONING":
                case "AUXILIARY":
                case "OTHER":
                    return "SEASONING";
                case "RAW_MATERIAL":
                    return "SKIP";   // 原料由上游 traced consumption 承载, 不计
                default:
                    log.warn("[M67CostBreakdown] 未知 costCategory={} (factory={}, order={}) — 回退 step-index 启发式",
                            costCategory, factoryId, orderId);
                    // 落入下方启发式
            }
        }
        // 启发式 (向后兼容): 首道=原料(traced 承载, SKIP) > 末道=包装 > 中间道=调料。
        // 首道优先判断: 单工序批次(stepCount==1, 首道即末道)归 SKIP 而非 PACKAGING,
        // 否则其 materialCost 会与 traced consumption 的原料重复计 (单工序批次启发式双计 bug)。
        if (idx == 0) {
            return "SKIP";
        }
        if (idx == stepCount - 1) {
            return "PACKAGING";
        }
        return "SEASONING";
    }

    private static BigDecimal toBigDecimal(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof BigDecimal) {
            return (BigDecimal) o;
        }
        if (o instanceof Number) {
            return new BigDecimal(o.toString());
        }
        try {
            String s = o.toString().trim();
            return s.isEmpty() ? null : new BigDecimal(s);
        } catch (Exception e) {
            return null;
        }
    }

    private void maskCosts(OrderCostBreakdownDTO dto) {
        dto.setRawMaterialCost(null);
        dto.setLaborCost(null);
        dto.setSeasoningCost(null);
        dto.setPackagingCost(null);
        dto.setEquipmentCost(null);
        dto.setOtherCost(null);
        dto.setSemiFeedCost(null);
        dto.setTotalCost(null);
        dto.setPerBoxCost(null);
        dto.setKnownCostSubtotal(null);    // 脱敏一律清空: 展示口径也是金额
        dto.setKnownPerBoxCost(null);
        dto.setByproductCredit(null);
        dto.setNetTotalCost(null);
        dto.setNetPerBoxCost(null);
        dto.setSellablePerBoxCost(null);   // 成本派生; sampleRetainCount/sellableBoxCount/wasteQuantity 是物理量, 保留
        if (dto.getByproducts() != null) {
            for (ByproductLine l : dto.getByproducts()) {
                l.setUnitPrice(null);
                l.setValue(null);   // 价值/单价价格敏感; 保留 name/quantity/unit (物理量)
            }
        }
        if (dto.getSources() != null) {
            for (SourceCost s : dto.getSources()) {
                s.setUnitPrice(null);
                s.setCost(null);
                s.setCostSharePct(null);
            }
        }
        if (dto.getPackagingDetail() != null) {
            for (PackagingItem p : dto.getPackagingDetail()) {
                p.setCost(null);   // 成本敏感; 保留 name (包材项名是物理信息)
            }
        }
        if (dto.getAuxiliaryAllocations() != null) {
            for (OrderCostBreakdownDTO.AuxiliaryAllocation a : dto.getAuxiliaryAllocations()) {
                a.setPotTotalCost(null);
                a.setBatchShare(null);   // 金额敏感; 保留 potNo/method/产出量/占比 (物理量)
            }
        }
        if (dto.getRawMaterialDetails() != null) {
            for (RawMaterialDetail detail : dto.getRawMaterialDetails()) {
                detail.setUnitPrice(null);
                detail.setAmount(null);
            }
        }
        if (dto.getPackagingDetails() != null) {
            for (PackagingDetail detail : dto.getPackagingDetails()) {
                detail.setUnitPrice(null);
                detail.setAmount(null);
            }
        }
        if (dto.getLaborDetails() != null) {
            for (LaborDetail detail : dto.getLaborDetails()) {
                detail.setHourlyRate(null);
                detail.setAmount(null);
            }
        }
        if (dto.getEstimatedAuxItems() != null) {
            for (OrderCostBreakdownDTO.EstimatedAuxItem item : dto.getEstimatedAuxItems()) {
                item.setUnitPrice(null);
                item.setAmount(null);   // 金额敏感; 保留配比/基数/量/标签 (物理量与来源标记)
            }
        }
    }

    private OrderCostBreakdownDTO empty(String orderId, boolean maskPrice) {
        return OrderCostBreakdownDTO.builder()
                .orderId(orderId).boxCount(0).hasData(false).priceMasked(maskPrice)
                .calculatedAt(LocalDateTime.now()).calculationStatus("NO_DATA")
                .missingCostItemCount(0).missingCostItems(List.of())
                .customerSuppliedItemCount(0).customerSuppliedItems(List.of())
                .estimatedAuxItemCount(0).estimatedAuxItems(List.of())
                .partiallyRecordedAuxItemCount(0).partiallyRecordedAuxItems(List.of())
                .sources(List.of()).byproducts(List.of())
                .rawMaterialDetails(List.of()).packagingDetails(List.of()).laborDetails(List.of())
                .build();
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static Long parseLong(String s) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return null;
        }
    }
}
