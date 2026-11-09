package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
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
 * {@link OrderCostBreakdownService#computeByPlan} (与成本核算同一个内核)。结单转入半成品库的那部分
 * 按成本核算的同一比例从实际侧扣掉 (净额, 见 {@link SemiTransferShare}), 划转额未知时差异标不完整。</p>
 */
@Slf4j
@Service
public class PlanCostVarianceService {

    static final String RECOVERED_MATERIAL_NOTE = "回料 (半成品回投) 的差异本期不单列, 不在比较范围内";
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
                .recoveredMaterialNote(RECOVERED_MATERIAL_NOTE)
                .priceMasked(maskPrice)
                .allocationMethods(allocationMethods(factoryId, plan.getId()));

        List<PlanStandardCostLine> lines = lineRepository.findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, plan.getId());
        if (lines.isEmpty()) {
            return dto.comparable(false)
                    .comparabilityReason("这张计划没有标准成本快照 (开于快照功能上线前, 或没有锁定配方), 无法比较")
                    .build();
        }
        dto.snapshotCapturedAt(lines.getFirst().getCapturedAt());

        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndProductionPlanId(factoryId, plan.getId())
                .stream().filter(b -> b.getStatus() != ProductionBatchStatus.CANCELLED).toList();
        ActualSide actual = actualUsage(factoryId, plan.getId(), batches);
        OrderCostBreakdownDTO breakdown = batches.isEmpty() ? null
                : orderCostBreakdownService.computeByPlan(factoryId, plan.getId(), false);
        BigDecimal outputKg = breakdown == null ? null : breakdown.getConvertedOutputKg();
        BigDecimal outputQty = outputQuantityInPlanUnit(factoryId, plan, breakdown);
        if (outputKg == null && outputQty != null) {
            outputKg = unitConversionService.toKg(outputQty, plan.getPlannedUnit());
        }
        dto.outputQuantity(outputQty).outputUnit(plan.getPlannedUnit()).outputKg(outputKg);
        if (batches.isEmpty() || (actual.byMaterial().isEmpty() && (outputQty == null || outputQty.signum() <= 0))) {
            return dto.comparable(false)
                    .comparabilityReason("还没有实际领用和产出, 暂无可比较的实际成本")
                    .customerSuppliedItemCount(actual.customerSupplied())
                    .build();
        }

        // R5 (2026-11-09): 实际侧按净额 —— 结单转进半成品库的那部分成本随货走了, 不能留在成品的实际里
        //   跟按成品产量算的标准比 (投 100、成品 60、半成品 40 时会多出约 40 的假超支)。
        //   扣多少只看成本核算 (computeByPlan) 的划转额与净成本, ⛔ 不在这里另算一份; 划转额未知 ⇒ 不完整, ⛔ 不当 0。
        SemiTransferShare transfer = SemiTransferShare.of(breakdown);
        dto.semiTransferredCost(maskPrice ? null : transfer.credit());
        if (transfer.unknownReason() != null) {
            return dto.comparable(false)
                    .comparabilityReason(transfer.unknownReason())
                    .customerSuppliedItemCount(actual.customerSupplied())
                    .build();
        }
        Map<String, PlanCostVarianceCalculator.ActualUsage> netActual = transfer.net(actual.byMaterial());
        BigDecimal netLabor = transfer.net(breakdown == null ? null : breakdown.getLaborCost());

        PlanCostVarianceCalculator.Result result = PlanCostVarianceCalculator.compute(new PlanCostVarianceCalculator.Inputs(
                lines, netActual, outputKg, outputQty, netLabor,
                PlanBomItems.isMultiOutput(plan), this::convert));

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
                .comparableItemCount(result.comparableCount())
                .incomparableItemCount(result.incomparableCount())
                .customerSuppliedItemCount(actual.customerSupplied())
                .lines(outLines)
                .build();
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
     * 结单转入半成品库的成本在实际侧怎么扣 (R5)。
     *
     * <p>划转额与净成本都取自成本核算 {@link OrderCostBreakdownService#computeByPlan} —— 成品入库单价和毛利用的是
     * 同一个净成本。留给成品的比例 = 净成本 ÷ (净成本 + 划转额), 各项实际用量、金额和实际人工同比例扣
     * (单价不变, 所以价格差/用量差/替代料差的恒等式照旧成立)。这样成本差异的实际成本、成品入库单价 × 入库量、
     * 毛利里扣的成本是同一个数。
     *
     * @param credit        划转额; null = 没有划转
     * @param netFactor     留给成品的比例; 没有划转时为 1
     * @param unknownReason 非 null = 划转额或净成本未知, 整张差异如实标不完整
     */
    record SemiTransferShare(BigDecimal credit, BigDecimal netFactor, String unknownReason) {
        static final String TRANSFER_UNKNOWN_ITEM = "SETTLEMENT_SEMI_TRANSFER_COST";
        private static final SemiTransferShare NONE = new SemiTransferShare(null, BigDecimal.ONE, null);

        static SemiTransferShare of(OrderCostBreakdownDTO breakdown) {
            if (breakdown == null) {
                return NONE;
            }
            BigDecimal credit = breakdown.getSemiTransferredCost();
            if (breakdown.getMissingCostItems() != null
                    && breakdown.getMissingCostItems().contains(TRANSFER_UNKNOWN_ITEM)) {
                return new SemiTransferShare(credit, null,
                        "结单转入半成品库的那部分成本未知, 成品的实际成本分不出来, 差异不完整");
            }
            if (credit == null || credit.signum() == 0) {
                return NONE;
            }
            BigDecimal net = breakdown.getTotalCost();
            if (!breakdown.isCostComplete() || net == null || net.signum() < 0) {
                return new SemiTransferShare(credit, null,
                        "计划成本归集不完整, 结单转入半成品库的那部分无法按比例从实际里扣出, 差异不完整");
            }
            BigDecimal gross = net.add(credit);
            return new SemiTransferShare(credit, net.divide(gross, 12, RoundingMode.HALF_UP), null);
        }

        Map<String, PlanCostVarianceCalculator.ActualUsage> net(
                Map<String, PlanCostVarianceCalculator.ActualUsage> gross) {
            if (netFactor.compareTo(BigDecimal.ONE) == 0) {
                return gross;
            }
            Map<String, PlanCostVarianceCalculator.ActualUsage> out = new LinkedHashMap<>();
            gross.forEach((id, u) -> out.put(id, new PlanCostVarianceCalculator.ActualUsage(
                    u.materialTypeId(), u.materialName(), net(u.quantity()), u.unit(), net(u.amount()), u.unitMixed())));
            return out;
        }

        BigDecimal net(BigDecimal grossValue) {
            return grossValue == null || netFactor.compareTo(BigDecimal.ONE) == 0
                    ? grossValue : grossValue.multiply(netFactor);
        }
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
