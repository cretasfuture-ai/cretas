package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 结单时把本计划剩下的半成品转进半成品库 (D-S315-02)。
 *
 * <p>逐道录入的非成品道产出落在在制批次 (CLK-W), 结单族以前不进半成品库, 结单请求里的
 * {@code actualSemiFinishedQuantity} 也只记一个数。这里按请求的量把它搬进半成品库:
 * 半成品库入账, 同一量从在制批次扣掉 —— 两边都留着就是同一批货算两次。
 *
 * <p>剩余量看在制批次本身, ⛔ 不用「产出 − 逐道行投料」现算: 投料量的单位可能和产出不同,
 * 别的计划也可能从这批领过料, 这两件事只有在制批次的消耗记录全都算上了 (单位是库存单位)。
 * 超过剩余量、认不出是哪种半成品、根本没有剩余, 都拒绝, ⛔ 不夹到一个能过的数。
 *
 * <p>每批在制一行半成品库记录 ({@link #anchor}), 行上的 {@code materialBatchRefs} 记着来源生产批次,
 * 从这行往回追溯才接得上。入库明细存在结单上 ({@code semiStockingDetail}), 整单撤回照着它把两边都还回去。
 *
 * <p>成本随货走 (Owner 2026-11-04): 转走的量按在制批次单价带走成本, 记在入库明细上,
 * {@code OrderCostBreakdownService} 从本计划总成本里扣掉这笔, 账不重复; 下游用这批半成品返工时成本算得出来。
 * 单价未知就保持未知 (⛔ 不当 0): 半成品成本为空, 成品那边也算不准, 本计划成本标记不完整。
 */
@Service
@RequiredArgsConstructor
public class SettlementSemiStockingService {

    private static final ObjectMapper DETAIL_MAPPER = new ObjectMapper();

    private final ProcessSheetRowRepository processSheetRowRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final MaterialConsumptionRepository materialConsumptionRepository;
    private final WipInventoryService wipInventoryService;
    private final UnitContractService unitContractService;
    private final ObjectMapper objectMapper;

    /** 一批在制里转走的量: {@code quantity} 是半成品单位, {@code storageQuantity} 是在制批次的库存单位。 */
    public record Slice(Long productionBatchId, String batchNumber, String materialBatchId,
                        BigDecimal quantity, BigDecimal storageQuantity, BigDecimal feedUnitCost) {
    }

    /** 校验通过的入库方案; {@code slices} 的数量合计 = {@code quantity}。 */
    public record Stocking(String productTypeId, String unit, Integer processOrder,
                           BigDecimal quantity, List<Slice> slices) {
    }

    /** 落在结单上的一条入库明细, 整单撤回照着还。{@code transferredCost} 为 null = 成本未知。 */
    public record StockedSlice(String anchor, Long productionBatchId, String materialBatchId,
                               BigDecimal quantity, BigDecimal storageQuantity, BigDecimal transferredCost) {
    }

    /** 半成品库的行号: 每次结单、每批在制各一行, 这样一行只有一个来源批。 */
    public static String anchor(String planId, String settlementId, int sliceIndex) {
        return WipInventoryService.clerkSemiAnchor(planId, settlementId) + "-" + sliceIndex;
    }

    /** 结单上的入库明细; 空 = 这次结单没有入库 (量为 0, 或这个功能上线前的结单)。 */
    public static List<StockedSlice> parseDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return List.of();
        }
        try {
            return DETAIL_MAPPER.readValue(detail, new TypeReference<List<StockedSlice>>() { });
        } catch (JsonProcessingException e) {
            throw new BusinessException(409, "结单的半成品入库明细无法解析, 不能撤回")
                    .withCode("SETTLEMENT_SEMI_DETAIL_INVALID")
                    .withHint("请联系管理员核对这张结单的半成品入库明细");
        }
    }

    /**
     * 只读校验: 这次要入库的量对不对。不写任何数据, 预览和正式结单走同一份判定。
     *
     * @param usableRow 哪些逐道行算数 (与结单同一判据)
     * @return 入库方案; 数量不大于 0 时返回 null (这次没有半成品要入库)
     */
    public Stocking resolve(String factoryId, ProductionPlan plan, BigDecimal quantity,
                            Predicate<ProcessSheetRow> usableRow) {
        if (quantity == null || quantity.signum() <= 0) {
            return null;
        }
        Map<Long, Producer> producers = new LinkedHashMap<>();
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        for (ProcessSheetRow row : rows == null ? List.<ProcessSheetRow>of() : rows) {
            if (!usableRow.test(row) || row.getBatchId() == null) {
                continue;
            }
            ProcessSheetRowRequest req = parse(row);
            if (!req.isFinished() && positive(req.getOutputQuantity())) {
                producers.putIfAbsent(row.getBatchId(), new Producer(row, req));
            }
        }

        Set<String> productTypes = new LinkedHashSet<>();
        Set<String> units = new HashSet<>();
        List<Slice> leftovers = new ArrayList<>();
        BigDecimal totalLeftover = BigDecimal.ZERO;
        String unit = null;
        Integer processOrder = null;
        List<Producer> ordered = new ArrayList<>(producers.values());
        ordered.sort(Comparator.comparing(p -> p.row().getProcessOrder(),
                Comparator.nullsLast(Comparator.naturalOrder())));
        for (Producer producer : ordered) {
            ProcessSheetRow row = producer.row();
            MaterialBatch wip = materialBatchRepository
                    .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                            factoryId, "PRODUCTION_BATCH", String.valueOf(row.getBatchId()))
                    .orElse(null);
            if (wip == null) {
                continue;
            }
            BigDecimal storageLeft = leftoverInStorageUnit(factoryId, wip);
            if (storageLeft.signum() <= 0) {
                continue;
            }
            String rowUnit = producer.request().getUnit();
            BigDecimal left = convert(factoryId, storageLeft, wip.getQuantityUnit(), rowUnit);
            productTypes.add(producer.request().getProductTypeId());
            units.add(unitContractService.storageUnit(factoryId, rowUnit));
            leftovers.add(new Slice(row.getBatchId(), row.getBatchNumber(), wip.getId(), left, storageLeft,
                    stockFeedUnitCost(factoryId, producer.request())));
            totalLeftover = totalLeftover.add(left);
            unit = unit == null ? rowUnit : unit;
            processOrder = processOrder == null ? row.getProcessOrder() : processOrder;
        }

        if (leftovers.isEmpty()) {
            throw new BusinessException(409, "本计划没有可转入半成品库的剩余半成品")
                    .withCode("SETTLEMENT_SEMI_NO_LEFTOVER")
                    .withHint("实际半成品量请填 0; App 报工产出的半成品在报工时已入半成品库, 不用在结单时再填")
                    .withHintTarget("actualSemiFinishedQuantity");
        }
        if (productTypes.size() != 1 || productTypes.contains(null) || units.size() != 1) {
            throw new BusinessException(409, "本计划剩余的半成品不止一种, 无法判断这次要入库的是哪一种")
                    .withCode("SETTLEMENT_SEMI_AMBIGUOUS")
                    .withHint("请把多余的半成品在下道报工里投完, 或联系主管核对后再结单")
                    .withHintTarget("actualSemiFinishedQuantity");
        }
        if (quantity.compareTo(totalLeftover) > 0) {
            throw new BusinessException(409, "实际半成品量超过本计划剩余量: 填 "
                    + plain(quantity) + ", 剩余 " + plain(totalLeftover))
                    .withCode("SETTLEMENT_SEMI_EXCEEDS_LEFTOVER")
                    .withHint("最多可入半成品库 " + plain(totalLeftover) + ", 请改小后再提交")
                    .withHintTarget("actualSemiFinishedQuantity");
        }

        List<Slice> slices = new ArrayList<>();
        BigDecimal remaining = quantity;
        for (Slice leftover : leftovers) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = leftover.quantity().min(remaining);
            BigDecimal storageTake = take.compareTo(leftover.quantity()) == 0 ? leftover.storageQuantity()
                    : convert(factoryId, take, unit, storageUnitOf(leftover, factoryId));
            slices.add(new Slice(leftover.productionBatchId(), leftover.batchNumber(), leftover.materialBatchId(),
                    take, storageTake, leftover.feedUnitCost()));
            remaining = remaining.subtract(take);
        }
        return new Stocking(productTypes.iterator().next(), unit, processOrder, quantity, slices);
    }

    /**
     * 入半成品库, 同量从在制批次扣掉。必须在结单事务内调用。
     *
     * @return 入库明细 (JSON), 存到结单上给整单撤回用
     */
    public String stock(String factoryId, ProductionPlan plan, Stocking stocking, String settlementId) {
        List<StockedSlice> stocked = new ArrayList<>();
        for (int i = 0; i < stocking.slices().size(); i++) {
            Slice slice = stocking.slices().get(i);
            MaterialBatch wip = materialBatchRepository
                    .findByIdAndFactoryIdForUpdate(slice.materialBatchId(), factoryId)
                    .orElseThrow(() -> new BusinessException(409, "在制批次 " + slice.batchNumber()
                            + " 没有对应的在制库存, 无法转入半成品库")
                            .withCode("SETTLEMENT_SEMI_WIP_NOT_FOUND")
                            .withHint("请核对该道报工是否已物化, 或把实际半成品量改为 0")
                            .withHintTarget("actualSemiFinishedQuantity"));
            // 锁内再量一次: 校验到这里之间, 别的计划可能又从这批领了料
            if (slice.storageQuantity().compareTo(leftoverInStorageUnit(factoryId, wip)) > 0) {
                throw new BusinessException(409, "在制批次 " + slice.batchNumber() + " 的剩余量刚被领用, 不够转入半成品库")
                        .withCode("SETTLEMENT_SEMI_EXCEEDS_LEFTOVER")
                        .withHint("请刷新后按最新的剩余量重新填写实际半成品量")
                        .withHintTarget("actualSemiFinishedQuantity");
            }
            // 下道投料只写消耗记录、不进 used; 这里按「已用 + 消耗」的大者接着加, 批次余量才等于真实剩余
            wip.setUsedQuantity(consumedInStorageUnit(factoryId, wip).add(slice.storageQuantity()));
            wip.assertConsumptionInvariant();
            materialBatchRepository.save(wip);

            String anchor = anchor(plan.getId(), settlementId, i);
            Map<String, Object> ref = new LinkedHashMap<>();
            ref.put("materialBatchId", slice.materialBatchId());
            ref.put("productionBatchId", slice.productionBatchId());
            ref.put("quantity", slice.quantity());
            BigDecimal unitCost = semiUnitCost(wip, slice);
            wipInventoryService.postClerkOutput(factoryId, anchor, stocking.productTypeId(), slice.quantity(),
                    stocking.unit(), unitCost, List.of(ref), stocking.processOrder());
            stocked.add(new StockedSlice(anchor, slice.productionBatchId(), slice.materialBatchId(),
                    slice.quantity(), slice.storageQuantity(),
                    unitCost == null ? null : unitCost.multiply(slice.quantity())));
        }
        try {
            return DETAIL_MAPPER.writeValueAsString(stocked);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("半成品入库明细无法写入结单", e);
        }
    }

    /**
     * 这批半成品的单位成本 (半成品单位)。在制批次单价已含本道及上游在制的成本, 但不含常驻库存投料
     * (见 InterimSettleServiceImpl 的说明), 那部分单独按产出摊。任一项未知 ⇒ null。
     */
    private static BigDecimal semiUnitCost(MaterialBatch wip, Slice slice) {
        if (wip.getUnitPrice() == null || slice.feedUnitCost() == null) {
            return null;
        }
        return wip.getUnitPrice().multiply(slice.storageQuantity())
                .divide(slice.quantity(), 6, RoundingMode.HALF_UP)
                .add(slice.feedUnitCost())
                .setScale(6, RoundingMode.HALF_UP);
    }

    /** 这一道吃的常驻库存投料摊到每单位产出上是多少; 没有投料是 0, 算不出来是 null。 */
    private BigDecimal stockFeedUnitCost(String factoryId, ProcessSheetRowRequest req) {
        BigDecimal total = BigDecimal.ZERO;
        for (ProcessSheetRowRequest.UpstreamRef ref
                : req.getUpstreamSources() == null ? List.<ProcessSheetRowRequest.UpstreamRef>of()
                        : req.getUpstreamSources()) {
            if (ref.isFinishedGoods()) {
                return null;
            }
            if (!ref.isSemiFinished() || !positive(ref.getFeedQuantityKg())) {
                continue;
            }
            BigDecimal quantity = wipInventoryService.resolveSemiFeedQtyInSourceUnit(
                    factoryId, ref.getSourceBatchNumber(), ref.getFeedQuantityKg());
            BigDecimal unitCost = wipInventoryService.getSemiUnitCost(factoryId, ref.getSourceBatchNumber());
            if (quantity == null || unitCost == null) {
                return null;
            }
            total = total.add(quantity.multiply(unitCost));
        }
        return total.divide(req.getOutputQuantity(), 6, RoundingMode.HALF_UP);
    }

    /** 在制批次还剩多少 (库存单位)。消耗记录含别的计划领的料; 已过账的消耗同时在 used 里, 取大的那个不重复减。 */
    private BigDecimal leftoverInStorageUnit(String factoryId, MaterialBatch wip) {
        BigDecimal reserved = wip.getReservedQuantity() == null ? BigDecimal.ZERO : wip.getReservedQuantity();
        BigDecimal receipt = wip.getReceiptQuantity() == null ? BigDecimal.ZERO : wip.getReceiptQuantity();
        return receipt.subtract(consumedInStorageUnit(factoryId, wip)).subtract(reserved);
    }

    private BigDecimal consumedInStorageUnit(String factoryId, MaterialBatch wip) {
        BigDecimal consumed = BigDecimal.ZERO;
        for (MaterialConsumption consumption
                : materialConsumptionRepository.findByFactoryIdAndBatchId(factoryId, wip.getId())) {
            if (positive(consumption.getQuantity())) {
                consumed = consumed.add(consumption.getQuantity());
            }
        }
        BigDecimal used = wip.getUsedQuantity() == null ? BigDecimal.ZERO : wip.getUsedQuantity();
        return used.max(consumed);
    }

    private String storageUnitOf(Slice slice, String factoryId) {
        return materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceDocId(
                        factoryId, "PRODUCTION_BATCH", String.valueOf(slice.productionBatchId()))
                .map(MaterialBatch::getQuantityUnit)
                .orElse(null);
    }

    /** 同一单位原样; kg⇄g 换算; 其它组合没有普适换算, 拒绝, ⛔ 不当成相等。 */
    private BigDecimal convert(String factoryId, BigDecimal quantity, String fromUnit, String toUnit) {
        String from = unitContractService.storageUnit(factoryId, fromUnit);
        String to = unitContractService.storageUnit(factoryId, toUnit);
        if (from != null && !from.isBlank() && from.equals(to)) {
            return quantity;
        }
        if ("kg".equals(from) && "g".equals(to)) {
            return quantity.movePointRight(3);
        }
        if ("g".equals(from) && "kg".equals(to)) {
            return quantity.movePointLeft(3);
        }
        throw new BusinessException(409, "在制批次单位与半成品单位不一致, 无法换算: "
                + com.cretas.aims.service.unit.UnitDisplayNames.display(fromUnit) + " / "
                + com.cretas.aims.service.unit.UnitDisplayNames.display(toUnit))
                .withCode("SETTLEMENT_SEMI_UNIT_MISMATCH")
                .withHint("请核对该道工序的产出单位, 或把实际半成品量改为 0")
                .withHintTarget("actualSemiFinishedQuantity");
    }

    private ProcessSheetRowRequest parse(ProcessSheetRow row) {
        try {
            return objectMapper.readValue(row.getRowPayload(), ProcessSheetRowRequest.class);
        } catch (JsonProcessingException e) {
            throw new BusinessException(409, "逐道报工行内容无法解析, 不能判断半成品剩余量")
                    .withCode("PROCESS_SHEET_PAYLOAD_INVALID")
                    .withHint("请联系管理员核对该报工行后再结单")
                    .withHintTarget("processSheetRows");
        }
    }

    private record Producer(ProcessSheetRow row, ProcessSheetRowRequest request) {
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static String plain(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
