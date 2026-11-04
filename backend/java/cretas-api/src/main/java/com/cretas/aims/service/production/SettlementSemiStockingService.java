package com.cretas.aims.service.production;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * <p>数量只认报工事实: 剩余量 = 非成品道产出 − 本计划下道投入。超过剩余量、认不出是哪种半成品、
 * 根本没有剩余, 都拒绝, ⛔ 不夹到一个能过的数。
 *
 * <p>入库单位成本传 null: 这批料的成本已经算在本计划成品头上, 这里再记一份就是同一笔成本两处各算一次。
 */
@Service
@RequiredArgsConstructor
public class SettlementSemiStockingService {

    private final ProcessSheetRowRepository processSheetRowRepository;
    private final MaterialBatchRepository materialBatchRepository;
    private final WipInventoryService wipInventoryService;
    private final ObjectMapper objectMapper;

    /** 一道非成品产出里能转走的那部分。 */
    public record Slice(Long productionBatchId, String batchNumber, BigDecimal quantity) {
    }

    /** 校验通过的入库方案; {@code slices} 的数量合计 = {@code quantity}。 */
    public record Stocking(String productTypeId, String unit, Integer processOrder,
                           BigDecimal quantity, List<Slice> slices) {
    }

    /** 半成品库的行号: 每次结单一行, 整单撤回时凭结单 id 就能找回来。 */
    public static String anchor(String planId, String settlementId) {
        return WipInventoryService.clerkSemiAnchor(planId, settlementId);
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
        List<ProcessSheetRow> rows = processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, plan.getId());
        List<Producer> producers = new ArrayList<>();
        Map<String, BigDecimal> fedByBatchNumber = new HashMap<>();
        for (ProcessSheetRow row : rows == null ? List.<ProcessSheetRow>of() : rows) {
            if (!usableRow.test(row)) {
                continue;
            }
            ProcessSheetRowRequest req = parse(row);
            if (!req.isFinished() && row.getBatchId() != null && positive(req.getOutputQuantity())) {
                producers.add(new Producer(row, req));
            }
            for (ProcessSheetRowRequest.UpstreamRef ref
                    : req.getUpstreamSources() == null ? List.<ProcessSheetRowRequest.UpstreamRef>of()
                            : req.getUpstreamSources()) {
                if (!ref.isSemiFinished() && !ref.isFinishedGoods() && ref.getSourceBatchNumber() != null
                        && positive(ref.getFeedQuantityKg())) {
                    fedByBatchNumber.merge(ref.getSourceBatchNumber(), ref.getFeedQuantityKg(), BigDecimal::add);
                }
            }
        }
        producers.sort(Comparator.comparing(p -> p.row().getProcessOrder(),
                Comparator.nullsLast(Comparator.naturalOrder())));

        Set<String> productTypes = new LinkedHashSet<>();
        List<Slice> leftovers = new ArrayList<>();
        BigDecimal totalLeftover = BigDecimal.ZERO;
        String unit = null;
        Integer processOrder = null;
        for (Producer producer : producers) {
            ProcessSheetRow row = producer.row();
            ProcessSheetRowRequest req = producer.request();
            BigDecimal fed = fedByBatchNumber.getOrDefault(row.getBatchNumber(), BigDecimal.ZERO);
            BigDecimal leftover = req.getOutputQuantity().subtract(fed);
            // 同一批次号多行时, 投入量从第一行起依次抵掉
            fedByBatchNumber.put(row.getBatchNumber(), fed.subtract(req.getOutputQuantity()).max(BigDecimal.ZERO));
            if (leftover.signum() <= 0) {
                continue;
            }
            productTypes.add(req.getProductTypeId());
            leftovers.add(new Slice(row.getBatchId(), row.getBatchNumber(), leftover));
            totalLeftover = totalLeftover.add(leftover);
            unit = unit == null ? req.getUnit() : unit;
            processOrder = processOrder == null ? row.getProcessOrder() : processOrder;
        }

        if (leftovers.isEmpty()) {
            throw new BusinessException(409, "本计划没有可转入半成品库的剩余半成品")
                    .withCode("SETTLEMENT_SEMI_NO_LEFTOVER")
                    .withHint("实际半成品量请填 0; App 报工产出的半成品在报工时已入半成品库, 不用在结单时再填")
                    .withHintTarget("actualSemiFinishedQuantity");
        }
        if (productTypes.size() != 1 || productTypes.contains(null)) {
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
            slices.add(new Slice(leftover.productionBatchId(), leftover.batchNumber(), take));
            remaining = remaining.subtract(take);
        }
        return new Stocking(productTypes.iterator().next(), unit, processOrder, quantity, slices);
    }

    /** 入半成品库, 同量从在制批次扣掉。必须在结单事务内调用。 */
    public String stock(String factoryId, ProductionPlan plan, Stocking stocking, String settlementId) {
        for (Slice slice : stocking.slices()) {
            MaterialBatch wip = materialBatchRepository
                    .findByFactoryIdAndSourceDocTypeAndSourceDocId(
                            factoryId, "PRODUCTION_BATCH", String.valueOf(slice.productionBatchId()))
                    .orElseThrow(() -> new BusinessException(409, "在制批次 " + slice.batchNumber()
                            + " 没有对应的在制库存, 无法转入半成品库")
                            .withCode("SETTLEMENT_SEMI_WIP_NOT_FOUND")
                            .withHint("请核对该道报工是否已物化, 或把实际半成品量改为 0")
                            .withHintTarget("actualSemiFinishedQuantity"));
            BigDecimal used = wip.getUsedQuantity() == null ? BigDecimal.ZERO : wip.getUsedQuantity();
            wip.setUsedQuantity(used.add(slice.quantity()));
            wip.assertConsumptionInvariant();
            materialBatchRepository.save(wip);
        }
        String anchor = anchor(plan.getId(), settlementId);
        wipInventoryService.postClerkOutput(factoryId, anchor, stocking.productTypeId(), stocking.quantity(),
                stocking.unit(), null, null, stocking.processOrder());
        return anchor;
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
