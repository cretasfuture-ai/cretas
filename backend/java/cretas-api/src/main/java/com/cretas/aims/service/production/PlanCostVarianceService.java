package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.InventoryOwnership;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.UnitConversionService;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划成本差异 (食品 DAG 缺口 09): 把开计划时的标准成本快照、计划实际领用、产出与人工凑到一起,
 * 交给 {@link PlanCostVarianceCalculator} 算价格差 / 用量差 / 替代料差 / 人工差。
 *
 * <p>实际侧口径: 本计划各批次的领用记录 (按领用批次的实际单价计价), 不含本计划自己产出的在制品再投入
 * (那是内部转移, 上游的钱已经算过), 客供料不计我方成本、不参与比较。成品 kg 与实际人工取
 * {@link OrderCostBreakdownService#computeByPlan} (与成本核算同一个内核)。</p>
 *
 * <p>回料 (缺口 09 补齐, 2026-11-09): 回料投料不写领用记录, 而是报工行 {@code upstreamSources} 里
 * {@code semiFinished=true} 指向回料池 ({@code is_rework}) 的那几条 —— 与成本核算
 * {@code OrderCostBreakdownService#accumulateStockFeedCost} 读同一份数据, 折算用同一个
 * {@link WipInventoryService#resolveSemiFeedQtyInSourceUnit}, 计价用同一个 {@link WipInventoryService#getSemiUnitCost}。
 * 来源批次取回料池先进先出归属 (#1395, {@code REWORK_POOL_FIFO}) 写下的追溯边, 与追溯页同一份。</p>
 */
@Slf4j
@Service
public class PlanCostVarianceService {

    static final String REWORK_NONE_NOTE = "本计划没有回投回料";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlanStandardCostLineRepository lineRepository;
    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final MaterialConsumptionRepository consumptionRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final RawMaterialTypeRepository rawMaterialTypeRepository;
    private final OrderCostBreakdownService orderCostBreakdownService;
    private final UnitConversionService unitConversionService;

    @Autowired(required = false)
    private ProcessSheetRowRepository processSheetRowRepository;
    @Autowired(required = false)
    private ProductTypeRepository productTypeRepository;
    /** 单位同一性问单位契约 (⛔ 不比字符串, BareUnitEqualityRatchet)。 */
    @Autowired(required = false)
    private com.cretas.aims.service.unit.UnitContractService unitContractService;
    /** 回料: 回料池、折算与单价 (与成本核算同源)、追溯边、锁定配方。required=false: 纯单测可不给, 给不齐时回料如实标无法比较。 */
    @Autowired(required = false)
    private SemiFinishedInventoryRepository sfiRepository;
    @Autowired(required = false)
    private WipInventoryService wipInventoryService;
    @Autowired(required = false)
    private BatchLineageEdgeRepository lineageEdgeRepository;
    @Autowired(required = false)
    private BomRecipeItemRepository bomRecipeItemRepository;

    public PlanCostVarianceService(PlanStandardCostLineRepository lineRepository,
                                   ProductionPlanRepository planRepository,
                                   ProductionBatchRepository batchRepository,
                                   MaterialConsumptionRepository consumptionRepository,
                                   MaterialBatchRepository materialBatchRepository,
                                   RawMaterialTypeRepository rawMaterialTypeRepository,
                                   OrderCostBreakdownService orderCostBreakdownService,
                                   UnitConversionService unitConversionService) {
        this.lineRepository = lineRepository;
        this.planRepository = planRepository;
        this.batchRepository = batchRepository;
        this.consumptionRepository = consumptionRepository;
        this.materialBatchRepository = materialBatchRepository;
        this.rawMaterialTypeRepository = rawMaterialTypeRepository;
        this.orderCostBreakdownService = orderCostBreakdownService;
        this.unitConversionService = unitConversionService;
    }

    @Transactional(readOnly = true)
    public PlanCostVarianceDTO computeForPlan(String factoryId, String planId, boolean maskPrice) {
        ProductionPlan plan = planRepository.findByIdAndFactoryId(planId, factoryId)
                .orElseThrow(() -> new BusinessException(404, "生产计划不存在: " + planId));
        return compute(factoryId, plan, maskPrice);
    }

    PlanCostVarianceDTO compute(String factoryId, ProductionPlan plan, boolean maskPrice) {
        PlanCostVarianceDTO.PlanCostVarianceDTOBuilder dto = PlanCostVarianceDTO.builder()
                .planId(plan.getId())
                .planNumber(plan.getPlanNumber())
                .productTypeId(plan.getProductTypeId())
                .productName(productName(factoryId, plan.getProductTypeId()))
                .planStatus(plan.getStatus() == null ? null : plan.getStatus().name())
                .pinnedBomRecipeIds(new ArrayList<>(PlanBomItems.pinnedRecipeIds(plan)))
                .priceMasked(maskPrice)
                .allocationMethods(allocationMethods(factoryId, plan.getId()));

        List<PlanStandardCostLine> lines = lineRepository.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, plan.getId());
        if (lines.isEmpty()) {
            return dto.comparable(false)
                    .comparabilityReason("这张计划没有标准成本快照 (开于快照功能上线前, 或没有锁定配方), 无法比较")
                    .recoveredMaterialNote("没有标准成本快照, 回料差也无法比较")
                    .build();
        }
        dto.snapshotCapturedAt(lines.getFirst().getCapturedAt());

        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndProductionPlanId(factoryId, plan.getId())
                .stream().filter(b -> b.getStatus() != ProductionBatchStatus.CANCELLED).toList();
        ActualSide actual = actualUsage(factoryId, plan.getId(), batches);
        ReworkSide rework = reworkUsage(factoryId, plan.getId(), batches);
        String reworkStandardUnknown = reworkStandardUnknownReason(factoryId, plan);
        if (rework.usages().isEmpty() && !RECIPE_DECLARES_REWORK_REASON.equals(reworkStandardUnknown)) {
            reworkStandardUnknown = null; // 没回投、配方也没要求回料: 没有回料这一项, 读不到配方的原因不适用
        }
        OrderCostBreakdownDTO breakdown = batches.isEmpty() ? null
                : orderCostBreakdownService.computeByPlan(factoryId, plan.getId(), false);
        BigDecimal outputKg = breakdown == null ? null : breakdown.getConvertedOutputKg();
        BigDecimal outputQty = outputQuantityInPlanUnit(factoryId, plan, breakdown);
        if (outputKg == null && outputQty != null) {
            outputKg = unitConversionService.toKg(outputQty, plan.getPlannedUnit());
        }
        dto.outputQuantity(outputQty).outputUnit(plan.getPlannedUnit()).outputKg(outputKg);
        if (batches.isEmpty() || (actual.byMaterial().isEmpty() && rework.usages().isEmpty()
                && (outputQty == null || outputQty.signum() <= 0))) {
            return dto.comparable(false)
                    .comparabilityReason("还没有实际领用和产出, 暂无可比较的实际成本")
                    .customerSuppliedItemCount(actual.customerSupplied() + rework.customerSupplied())
                    .reworkStatus(PlanCostVarianceDTO.REWORK_NONE)
                    .recoveredMaterialNote(REWORK_NONE_NOTE)
                    .build();
        }

        PlanCostVarianceCalculator.Result result = PlanCostVarianceCalculator.compute(new PlanCostVarianceCalculator.Inputs(
                lines, actual.byMaterial(), outputKg, outputQty,
                breakdown == null ? null : breakdown.getLaborCost(),
                PlanBomItems.isMultiOutput(plan), this::convert, rework.usages(), reworkStandardUnknown));

        List<PlanCostVarianceDTO.Line> outLines = result.lines();
        if (maskPrice) {
            outLines.forEach(PlanCostVarianceService::mask);
        }
        String reason = !result.comparable()
                ? "没有任何一项能完整比较: 见各项原因"
                : result.incomparableCount() > 0
                        ? result.incomparableCount() + " 项无法比较, 未计入合计 (见各项原因)"
                        : null;
        return dto.comparable(result.comparable())
                .comparabilityReason(reason)
                .standardCost(result.comparable() ? result.standardCost() : null)
                .actualCost(result.comparable() ? result.actualCost() : null)
                .totalVariance(result.totalVariance() == null ? null : result.totalVariance().setScale(2, RoundingMode.HALF_UP))
                .varianceRate(result.varianceRate())
                .priceVariance(result.comparable() ? result.priceVariance() : null)
                .usageVariance(result.comparable() ? result.usageVariance() : null)
                .substituteVariance(result.comparable() ? result.substituteVariance() : null)
                .laborVariance(result.comparable() ? result.laborVariance() : null)
                .reworkVariance(result.comparable() ? result.reworkVariance() : null)
                .reworkStatus(result.reworkStatus())
                .reworkQuantityText(reworkQuantityText(rework.usages()))
                .recoveredMaterialNote(reworkNote(result, rework.usages()))
                .comparableItemCount(result.comparableCount())
                .incomparableItemCount(result.incomparableCount())
                .customerSuppliedItemCount(actual.customerSupplied() + rework.customerSupplied())
                .lines(outLines)
                .build();
    }

    /** 回料口径说明: 回投了多少、按什么价、怎么计入 (给人看的话, 报表与导出同一句)。 */
    static String reworkNote(PlanCostVarianceCalculator.Result result, List<PlanCostVarianceCalculator.ReworkUsage> usages) {
        String status = result.reworkStatus();
        if (PlanCostVarianceDTO.REWORK_NONE.equals(status)) {
            return REWORK_NONE_NOTE;
        }
        if (usages == null || usages.isEmpty()) {
            return "本计划没有回投回料, 但标准回料定不下来, 回料差无法比较 (见回料行原因)";
        }
        if (PlanCostVarianceDTO.REWORK_INTERNAL_ONLY.equals(status)) {
            return "回投 " + reworkQuantityText(usages) + " 回料, 都是本计划自己产出的: 原料已计入本计划领用, 内部转移不另计";
        }
        StringBuilder note = new StringBuilder("回投 ").append(reworkQuantityText(usages))
                .append(" 回料, 按").append(PlanCostVarianceCalculator.REWORK_PRICE_SOURCE).append("计价");
        if (PlanCostVarianceDTO.REWORK_INCOMPARABLE.equals(status)) {
            note.append("; 有回料无法比较, 未计入合计 (见回料行原因)");
            if (result.reworkVariance() != null) {
                note.append("; 可比部分回料差 ").append(signed(result.reworkVariance())).append(" 已计入合计");
            }
            return note.toString();
        }
        note.append("; 配方不含回料, 标准回料用量为 0, 回料差 ").append(signed(result.reworkVariance()))
                .append(" 已计入合计 (回料顶替的原料体现为主料用量差的节约)");
        boolean rawIncomparable = result.lines().stream()
                .anyMatch(l -> PlanStandardCostLine.TYPE_MATERIAL.equals(l.getLineType())
                        && "RAW".equalsIgnoreCase(l.getMaterialCategory())
                        && l.getUsageVariance() == null);
        if (rawIncomparable) {
            note.append("; 注意: 主料用量差无法比较, 回料顶替原料的那部分节约看不到, 回料差单看会偏高");
        }
        return note.toString();
    }

    private static String signed(BigDecimal v) {
        return (v.signum() > 0 ? "+¥" : v.signum() < 0 ? "−¥" : "¥") + v.abs().setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** 回投量按单位分列 (同一单位相加; 有折算不了的写明, ⛔ 不当 0 加)。 */
    static String reworkQuantityText(List<PlanCostVarianceCalculator.ReworkUsage> usages) {
        if (usages == null || usages.isEmpty()) {
            return null;
        }
        Map<String, BigDecimal> byUnit = new LinkedHashMap<>();
        boolean unknown = false;
        for (PlanCostVarianceCalculator.ReworkUsage u : usages) {
            if (u.quantity() == null) {
                unknown = true;
                continue;
            }
            byUnit.merge(u.unit() == null ? "" : u.unit(), u.quantity(), BigDecimal::add);
        }
        List<String> parts = new ArrayList<>();
        byUnit.forEach((unit, q) -> parts.add(q.setScale(4, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
                + (unit.isEmpty() ? "" : " " + unit)));
        if (unknown) {
            parts.add("另有部分折算不了");
        }
        return String.join(" + ", parts);
    }

    record ReworkSide(List<PlanCostVarianceCalculator.ReworkUsage> usages, int customerSupplied) {
    }

    private static final class MutableRework {
        final String poolNo;
        final SemiFinishedInventory pool;
        BigDecimal quantity = BigDecimal.ZERO;
        final List<String> reasons = new ArrayList<>();

        MutableRework(String poolNo, SemiFinishedInventory pool) {
            this.poolNo = poolNo;
            this.pool = pool;
        }
    }

    /**
     * 本计划各批次报工行里回投的回料, 按回料池汇总。
     *
     * <p>只看 {@code semiFinished=true} 且来源是回料池 ({@code is_rework}) 的投料; 普通半成品与成品投料不在本项。
     * 折算/单价与成本核算同源 (见类注释)。客供回料不计我方成本 (与领用口径一致)。</p>
     */
    ReworkSide reworkUsage(String factoryId, String planId, List<ProductionBatch> batches) {
        if (processSheetRowRepository == null || sfiRepository == null || batches.isEmpty()) {
            return new ReworkSide(List.of(), 0);
        }
        Map<String, MutableRework> pools = new LinkedHashMap<>();
        Set<String> customerSupplied = new HashSet<>();
        List<Long> batchIds = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            batchIds.add(batch.getId());
            for (ProcessSheetRow row : processSheetRowRepository.findByFactoryIdAndBatchId(factoryId, batch.getId())) {
                for (JsonNode ref : upstreamSources(row)) {
                    if (!ref.path("semiFinished").asBoolean(false)) {
                        continue;
                    }
                    String rawNo = ref.path("sourceBatchNumber").asText(null);
                    BigDecimal feedKg = decimal(ref, "feedQuantityKg");
                    if (rawNo == null || rawNo.isBlank() || feedKg == null || feedKg.signum() <= 0) {
                        continue;
                    }
                    String poolNo = rawNo.trim();
                    SemiFinishedInventory pool = sfiRepository
                            .findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(factoryId, poolNo).orElse(null);
                    if (pool == null || !Boolean.TRUE.equals(pool.getIsRework())) {
                        continue; // 不是回料池 (普通半成品投料不在本项)
                    }
                    if (pool.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
                        customerSupplied.add(poolNo);
                        continue; // 客供回料不计我方成本 (owner 2026-09-04 口径 a)
                    }
                    MutableRework agg = pools.computeIfAbsent(poolNo, k -> new MutableRework(k, pool));
                    BigDecimal inPoolUnit = wipInventoryService == null ? null
                            : wipInventoryService.resolveSemiFeedQtyInSourceUnit(factoryId, poolNo, feedKg);
                    if (inPoolUnit == null) {
                        agg.reasons.add("有一笔回投 " + feedKg.stripTrailingZeros().toPlainString()
                                + " kg 折算不到回料池单位 " + pool.getUnit() + " (计数单位缺每件克重)");
                        agg.quantity = null;
                    } else if (agg.quantity != null) {
                        agg.quantity = agg.quantity.add(inPoolUnit);
                    }
                }
            }
        }
        if (pools.isEmpty()) {
            return new ReworkSide(List.of(), customerSupplied.size());
        }
        Map<String, List<PlanCostVarianceDTO.ReworkSource>> sources = reworkSources(factoryId, batchIds);
        List<PlanCostVarianceCalculator.ReworkUsage> out = new ArrayList<>();
        for (MutableRework agg : pools.values()) {
            Set<String> producingPlans = producingPlans(factoryId, agg.poolNo);
            boolean internal = producingPlans.size() == 1 && producingPlans.contains(planId);
            if (producingPlans.isEmpty()) {
                agg.reasons.add("找不到这个回料池的入账报工, 判断不了是不是本计划自产, 无法比较");
            } else if (producingPlans.contains(planId) && producingPlans.size() > 1) {
                agg.reasons.add("回料池里既有本计划又有别的计划产出的回料, 拆不开内部转移和外来回料, 无法比较");
            }
            BigDecimal unitCost = wipInventoryService == null ? null
                    : wipInventoryService.getSemiUnitCost(factoryId, agg.poolNo);
            String name = productName(factoryId, agg.pool.getProductTypeId());
            out.add(new PlanCostVarianceCalculator.ReworkUsage(agg.poolNo,
                    (name == null ? agg.poolNo : name) + " (回料)", agg.quantity, agg.pool.getUnit(), unitCost,
                    internal && agg.reasons.isEmpty(), sources.getOrDefault(agg.poolNo, List.of()), agg.reasons));
        }
        return new ReworkSide(out, customerSupplied.size());
    }

    /** 回料池的入账报工 (SAVED_SFI) 来自哪几张计划 —— 与先进先出归属读同一批入账行。 */
    private Set<String> producingPlans(String factoryId, String poolNo) {
        Set<String> plans = new HashSet<>();
        processSheetRowRepository.findByFactoryIdAndBatchNumberAndRowStatus(factoryId, poolNo, ProcessSheetRow.STATUS_SAVED_SFI)
                .forEach(r -> {
                    if (r.getPlanId() != null) {
                        plans.add(r.getPlanId());
                    }
                });
        return plans;
    }

    /**
     * 回料来源批次: 读回料池先进先出归属写下的追溯边 ({@code WIP_CONSUME}, meta.rework=true,
     * sourceResolvedBy=REWORK_POOL_FIFO), 按回料池、产出批次汇总。归属不了的那段本来就没有边, 这里也不补。
     */
    private Map<String, List<PlanCostVarianceDTO.ReworkSource>> reworkSources(String factoryId, List<Long> batchIds) {
        Map<String, List<PlanCostVarianceDTO.ReworkSource>> out = new LinkedHashMap<>();
        if (lineageEdgeRepository == null || batchIds.isEmpty()) {
            return out;
        }
        List<String> targets = batchIds.stream().map(String::valueOf).toList();
        Map<String, PlanCostVarianceDTO.ReworkSource> byKey = new LinkedHashMap<>();
        for (BatchLineageEdge edge : lineageEdgeRepository.findByFactoryIdAndTargetIdIn(factoryId, targets)) {
            if (!LineageEdgeRecorder.EDGE_WIP_CONSUME.equals(edge.getEdgeType())
                    || !LineageEdgeRecorder.NODE_PRODUCTION_BATCH.equals(edge.getTargetType())
                    || edge.getMeta() == null || !Boolean.TRUE.equals(edge.getMeta().get("rework"))) {
                continue;
            }
            Object pool = edge.getMeta().get("sourceWipNo");
            Long producerId = parseLong(edge.getSourceId());
            if (pool == null || producerId == null) {
                continue;
            }
            String poolNo = pool.toString().trim();
            PlanCostVarianceDTO.ReworkSource src = byKey.computeIfAbsent(poolNo + "|" + producerId, k -> {
                ProductionBatch producer = batchRepository.findByIdAndFactoryId(producerId, factoryId).orElse(null);
                String producerPlanId = producer == null ? null : producer.getProductionPlanId();
                String producerPlanNumber = producerPlanId == null ? null
                        : planRepository.findByIdAndFactoryId(producerPlanId, factoryId)
                                .map(ProductionPlan::getPlanNumber).orElse(null);
                PlanCostVarianceDTO.ReworkSource s = PlanCostVarianceDTO.ReworkSource.builder()
                        .producerBatchNumber(producer == null ? String.valueOf(producerId) : producer.getBatchNumber())
                        .producerPlanId(producerPlanId)
                        .producerPlanNumber(producerPlanNumber)
                        .quantity(BigDecimal.ZERO)
                        .unit(edge.getUnit())
                        .build();
                out.computeIfAbsent(poolNo, p -> new ArrayList<>()).add(s);
                return s;
            });
            if (edge.getQuantityUsed() != null) {
                src.setQuantity(src.getQuantity().add(edge.getQuantityUsed()));
            }
        }
        return out;
    }

    static final String RECIPE_DECLARES_REWORK_REASON =
            "锁定配方登记了回料用量, 但开计划时的快照没有回料标准价, 回料差无法比较";

    /**
     * 标准回料: 锁定配方 (不可变版本) 不含回料行 → null (标准回料用量就是 0, 配方说的);
     * 含回料行或读不到配方 → 原因 (⛔ 不当 0)。
     */
    private String reworkStandardUnknownReason(String factoryId, ProductionPlan plan) {
        if (bomRecipeItemRepository == null) {
            return "读不到锁定配方, 标准回料用量定不下来, 回料差无法比较";
        }
        List<BomRecipeItem> items = PlanBomItems.pinnedOnly(factoryId, plan, bomRecipeItemRepository);
        if (items.isEmpty()) {
            return "计划没有锁定配方明细, 标准回料用量定不下来, 回料差无法比较";
        }
        return items.stream().anyMatch(BomRecipeItem::isSemiFinishedRow) ? RECIPE_DECLARES_REWORK_REASON : null;
    }

    private static JsonNode upstreamSources(ProcessSheetRow row) {
        if (row.getRowPayload() == null || row.getRowPayload().isBlank()) {
            return MAPPER.createArrayNode();
        }
        try {
            JsonNode ups = MAPPER.readTree(row.getRowPayload()).path("upstreamSources");
            return ups.isArray() ? ups : MAPPER.createArrayNode();
        } catch (Exception e) {
            log.warn("[cost-variance] 报工行 {} payload 解析不了, 回料投料读不到: {}", row.getId(), e.getMessage());
            return MAPPER.createArrayNode();
        }
    }


    private static void mask(PlanCostVarianceDTO.Line line) {
        line.setSnapshotUnitPrice(null);
        line.setActualUnitPrice(null);
        line.setStandardAmount(null);
        line.setActualAmount(null);
    }

    record ActualSide(Map<String, PlanCostVarianceCalculator.ActualUsage> byMaterial, int customerSupplied) {
    }

    /**
     * 本计划的实际领用, 按物料汇总 (单位折到第一笔的单位; 折不过去标 unitMixed)。
     *
     * <p>与成本内核 {@code OrderCostBreakdownService#traceCost} 同一种走法: 从本计划各批次的领用出发,
     * 领的若是<b>本厂产出的在制品</b> ({@code source_doc_type=PRODUCTION_BATCH}), 就沿它的来源批次往上追到原料叶子,
     * 按「本次领用量 ÷ 那批在制品入库量」折算 —— 否则逐道报工的计划只看得到最后一道领的在制品, 看不到真正用的原料。
     * 来源批次本身也是本计划的批次时不再往上追 (它自己的领用已经直接算过, 防双计)。</p>
     */
    ActualSide actualUsage(String factoryId, String planId, List<ProductionBatch> batches) {
        Set<Long> planBatchIds = new HashSet<>();
        batches.forEach(b -> planBatchIds.add(b.getId()));
        Map<String, MutableUsage> agg = new LinkedHashMap<>();
        Set<String> customerSupplied = new HashSet<>();
        for (ProductionBatch batch : batches) {
            for (MaterialConsumption c : consumptionRepository.findByProductionBatchIdAndFactoryId(batch.getId(), factoryId)) {
                collect(factoryId, c, BigDecimal.ONE, planBatchIds, new HashSet<>(), 0, agg, customerSupplied);
            }
        }
        Map<String, PlanCostVarianceCalculator.ActualUsage> out = new LinkedHashMap<>();
        agg.values().forEach(u -> out.put(u.materialTypeId, new PlanCostVarianceCalculator.ActualUsage(
                u.materialTypeId, materialName(factoryId, u.materialTypeId), u.quantity, u.unit, u.amount, u.unitMixed)));
        return new ActualSide(out, customerSupplied.size());
    }

    private static final int MAX_DEPTH = 10;

    private void collect(String factoryId, MaterialConsumption c, BigDecimal factor, Set<Long> planBatchIds,
                         Set<Long> path, int depth, Map<String, MutableUsage> agg, Set<String> customerSupplied) {
        if (c.getQuantity() == null || c.getQuantity().signum() == 0) {
            return;
        }
        MaterialBatch mb = c.getBatchId() == null ? null
                : materialBatchRepository.findByIdAndFactoryId(c.getBatchId(), factoryId).orElse(null);
        if (mb != null && "BYPRODUCT".equalsIgnoreCase(mb.getSourceDocType())) {
            return; // 副产再投入: 内部转移
        }
        if (mb != null && "PRODUCTION_BATCH".equalsIgnoreCase(mb.getSourceDocType())) {
            Long upstream = parseLong(mb.getSourceDocId());
            if (upstream == null || planBatchIds.contains(upstream)) {
                return; // 本计划自己的上一道: 那一道的领用已直接计入
            }
            if (depth < MAX_DEPTH && path.add(upstream)) {
                List<MaterialConsumption> ups = consumptionRepository.findByProductionBatchIdAndFactoryId(upstream, factoryId);
                if (!ups.isEmpty()) {
                    BigDecimal receipt = mb.getReceiptQuantity();
                    BigDecimal share = receipt != null && receipt.signum() > 0
                            ? c.getQuantity().divide(receipt, 12, RoundingMode.HALF_UP) : BigDecimal.ONE;
                    for (MaterialConsumption u : ups) {
                        collect(factoryId, u, factor.multiply(share), planBatchIds, path, depth + 1, agg, customerSupplied);
                    }
                    path.remove(upstream);
                    return;
                }
                path.remove(upstream);
            }
        }
        if (mb != null && mb.getOwnership() == InventoryOwnership.CUSTOMER_OWNED) {
            customerSupplied.add(mb.getBatchNumber());
            return; // 客供料不计我方成本 (owner 2026-09-04 口径 a), 不参与比较
        }
        String materialTypeId = c.getMaterialTypeId() != null ? c.getMaterialTypeId()
                : mb == null ? null : mb.getMaterialTypeId();
        if (materialTypeId == null) {
            return;
        }
        String unit = mb != null && mb.getQuantityUnit() != null ? mb.getQuantityUnit() : materialUnit(factoryId, materialTypeId);
        // 有没有价看领用的那个批次 (与成本内核 actualPriceKnown 同一判据): 批次没单价时领用记录上的 0 不是真价
        boolean priceKnown = mb == null ? c.getUnitPrice() != null : mb.getUnitPrice() != null;
        BigDecimal price = !priceKnown ? null : c.getUnitPrice() != null ? c.getUnitPrice() : mb.getUnitPrice();
        BigDecimal quantity = c.getQuantity().multiply(factor);
        MutableUsage usage = agg.computeIfAbsent(materialTypeId, id -> new MutableUsage(id, unit));
        BigDecimal qty = usage.unit == null ? null : convert(quantity, unit, usage.unit);
        if (qty == null) {
            usage.unitMixed = true;
        } else {
            usage.quantity = usage.quantity.add(qty);
        }
        if (price == null || usage.amount == null) {
            usage.amount = null;
        } else {
            usage.amount = usage.amount.add(quantity.multiply(price));
        }
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static final class MutableUsage {
        final String materialTypeId;
        final String unit;
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        boolean unitMixed;

        MutableUsage(String materialTypeId, String unit) {
            this.materialTypeId = materialTypeId;
            this.unit = unit;
        }
    }

    /** 产量 (计划单位): 计划实际产量优先, 否则成本核算的产出量 (单位与计划单位一致时)。 */
    private BigDecimal outputQuantityInPlanUnit(String factoryId, ProductionPlan plan, OrderCostBreakdownDTO breakdown) {
        if (plan.getActualQuantity() != null && plan.getActualQuantity().signum() > 0) {
            return plan.getActualQuantity();
        }
        if (breakdown != null && breakdown.getOutputQuantity() != null && breakdown.getOutputQuantity().signum() > 0
                && sameUnit(factoryId, breakdown.getOutputUnit(), plan.getPlannedUnit())) {
            return breakdown.getOutputQuantity();
        }
        return null;
    }

    BigDecimal convert(BigDecimal quantity, String from, String to) {
        if (quantity == null || from == null || to == null) {
            return null;
        }
        BigDecimal converted = unitConversionService.convert(quantity, from, to);
        if (converted == null && sameUnit(null, from, to)) {
            return quantity; // 同一单位的不同写法 (盒 / box): 契约说同一个, 原量照用
        }
        return converted;
    }

    private boolean sameUnit(String factoryId, String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return unitContractService != null && unitContractService.areEquivalent(factoryId, left, right);
    }

    private String materialUnit(String factoryId, String materialTypeId) {
        return rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId)
                .map(RawMaterialType::getUnit).orElse(null);
    }

    private String materialName(String factoryId, String materialTypeId) {
        return rawMaterialTypeRepository.findByIdAndFactoryId(materialTypeId, factoryId)
                .map(RawMaterialType::getName).orElse(materialTypeId);
    }

    private String productName(String factoryId, String productTypeId) {
        if (productTypeId == null || productTypeRepository == null) {
            return null;
        }
        return productTypeRepository.findByIdAndFactoryId(productTypeId, factoryId)
                .map(p -> p.getName()).orElse(null);
    }

    /** 缺口 10: 报表注明每道多产出工序用的是哪种分法 (取报工落库的分摊凭证)。 */
    List<PlanCostVarianceDTO.AllocationMethod> allocationMethods(String factoryId, String planId) {
        if (processSheetRowRepository == null) {
            return List.of();
        }
        Map<String, PlanCostVarianceDTO.AllocationMethod> groups = new LinkedHashMap<>();
        List<ProcessSheetRow> rows = new ArrayList<>(processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId));
        rows.sort(Comparator.comparing(ProcessSheetRow::getProcessOrder, Comparator.nullsLast(Comparator.naturalOrder())));
        for (ProcessSheetRow row : rows) {
            if (!ProcessSheetRow.SUBMISSION_SUBMITTED.equals(row.getSubmissionStatus()) || row.getRowPayload() == null) {
                continue;
            }
            JsonNode p;
            try {
                p = MAPPER.readTree(row.getRowPayload());
            } catch (Exception e) {
                continue;
            }
            if (p == null || !p.path("multiOutputMember").asBoolean(false) || !p.hasNonNull("costAllocationBasis")) {
                continue;
            }
            String base = p.path("multiOutputBaseRowId").asText(row.getClientRowId());
            String basis = p.path("costAllocationBasis").asText();
            PlanCostVarianceDTO.AllocationMethod group = groups.computeIfAbsent(
                    row.getProcessCode() + "|" + row.getProcessOrder() + "|" + base,
                    key -> PlanCostVarianceDTO.AllocationMethod.builder()
                            .processCode(row.getProcessCode())
                            .processOrder(row.getProcessOrder())
                            .basis(basis)
                            .build());
            BigDecimal fixed = decimal(p, "workflowFixedCostShare");
            group.getOutputs().add(PlanCostVarianceDTO.AllocationOutput.builder()
                    .productTypeId(p.path("productTypeId").asText(null))
                    .productName(productName(factoryId, p.path("productTypeId").asText(null)))
                    .batchNumber(row.getBatchNumber())
                    .costAllocationRatio(decimal(p, "costAllocationRatio"))
                    .workflowFixedCostShare(fixed)
                    .allocatedCost(decimal(p, "allocatedCost"))
                    .build());
        }
        groups.values().forEach(g -> g.setBasisLabel(basisLabel(g)));
        return new ArrayList<>(groups.values());
    }

    static String basisLabel(PlanCostVarianceDTO.AllocationMethod group) {
        String basis = group.getBasis() == null ? "" : group.getBasis();
        boolean overridden = basis.startsWith("EXPLICIT_RATIO")
                && group.getOutputs().stream().anyMatch(o -> o.getWorkflowFixedCostShare() != null);
        String label = basis.startsWith("FIXED_RATIO") ? "固定比例 (工艺版本)"
                : overridden ? "报工时改了工艺固定比例 (手填)"
                : basis.startsWith("EXPLICIT_RATIO") ? "报工时手填比例"
                : basis.startsWith("MASS") ? "按重量"
                : basis.startsWith("QUANTITY") ? "按数量 (同单位)"
                : basis;
        return basis.contains("BYPRODUCT_NRV") ? label + ", 副产先按可变现净值扣除" : label;
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        try {
            return new BigDecimal(value.asText());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
