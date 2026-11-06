package com.cretas.aims.service.supplyimpact;

import com.cretas.aims.dto.supplyimpact.SupplyImpactBoxExposure;
import com.cretas.aims.dto.supplyimpact.SupplyImpactDTO;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.projection.SupplyImpactBoxExposureRow;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Computes box equivalents only from immutable FG/delivery snapshots and the
 * actual FINISHED_TO_SHIPMENT deductions.  It intentionally does not use
 * allocation intent or current product configuration as a fallback.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplyImpactBoxExposureService {

    private static final int MAX_SCOPE = 500;
    private static final String UNKNOWN_CUSTOMER = "__UNKNOWN_CUSTOMER__";

    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    public SupplyImpactBoxExposure evaluate(String factoryId,
                                            Collection<String> discoveredFinishedBatchIds,
                                            Collection<String> discoveredShipmentIds,
                                            boolean lineageComplete) {
        List<SupplyImpactDTO.Gap> gaps = new ArrayList<>();
        SupplyImpactBoxExposure.SupplyImpactBoxExposureBuilder result = SupplyImpactBoxExposure.builder()
                .scope(SupplyImpactBoxExposure.SCOPE)
                .inventoryBasis(SupplyImpactBoxExposure.INVENTORY_BASIS)
                .quantityMeaning(SupplyImpactBoxExposure.QUANTITY_MEANING);

        Set<String> finishedIds = cleanIds(discoveredFinishedBatchIds);
        Set<String> shipmentIds = cleanIds(discoveredShipmentIds);
        if (factoryId == null || factoryId.isBlank()) {
            gaps.add(gap("FACTORY_SCOPE_REQUIRED", "工厂范围缺失, 无法读取成品库存或发货事实"));
            return unknown(result, gaps, lineageComplete);
        }
        if (finishedIds.isEmpty()) {
            gaps.add(gap("FINISHED_BATCH_SCOPE_EMPTY", "追溯范围没有可核验的成品批次, 箱数保持未知"));
            return unknown(result, gaps, lineageComplete);
        }
        if (finishedIds.size() > MAX_SCOPE || shipmentIds.size() > MAX_SCOPE) {
            gaps.add(gap("LINEAGE_SCOPE_TOO_LARGE", "追溯范围超过单次箱数核验上限, 未截断计算"));
            return unknown(result, gaps, lineageComplete);
        }

        Collection<String> queryShipmentIds = shipmentIds.isEmpty()
                ? Set.of("__NO_SHIPMENT_SCOPE__") : shipmentIds;
        List<SupplyImpactBoxExposureRow> rows =
                finishedGoodsBatchRepository.findSupplyImpactBoxExposureRows(
                        factoryId, finishedIds, queryShipmentIds);
        Map<String, List<SupplyImpactBoxExposureRow>> rowsByFinished = new LinkedHashMap<>();
        for (SupplyImpactBoxExposureRow row : rows) {
            if (row != null && finishedIds.contains(row.getFinishedGoodsBatchId())
                    && factoryId.equals(row.getFactoryId())) {
                rowsByFinished.computeIfAbsent(row.getFinishedGoodsBatchId(), ignored -> new ArrayList<>()).add(row);
            }
        }

        List<SupplyImpactBoxExposure.Source> stockSources = new ArrayList<>();
        TotalsAccumulator stockTotals = new TotalsAccumulator();
        Set<String> propagatedSourceGaps = new LinkedHashSet<>();
        for (String finishedId : finishedIds) {
            List<SupplyImpactBoxExposureRow> matching = rowsByFinished.getOrDefault(finishedId, List.of());
            SupplyImpactBoxExposureRow row = matching.isEmpty() ? null : matching.get(0);
            SupplyImpactBoxExposure.Source source = stockSource(row, finishedId);
            stockSources.add(source);
            propagateSourceGaps(gaps, propagatedSourceGaps, source);
            stockTotals.add(source.getBoxQuantity() == null ? null : decimal(source.getBoxQuantity()));
        }

        List<SupplyImpactBoxExposure.Source> shipmentSources = new ArrayList<>();
        TotalsAccumulator shipmentTotals = new TotalsAccumulator();
        Map<String, TotalsAccumulator> customerTotals = new LinkedHashMap<>();
        Map<String, String> customerNames = new LinkedHashMap<>();
        Set<String> seenEdges = new LinkedHashSet<>();
        Set<String> seenShipmentIds = new LinkedHashSet<>();
        Map<String, BigDecimal> actualDeductionByFinished = new LinkedHashMap<>();
        Set<String> unconvertibleDeductions = new LinkedHashSet<>();
        for (SupplyImpactBoxExposureRow row : rows) {
            if (row == null || !factoryId.equals(row.getFactoryId())
                    || !finishedIds.contains(row.getFinishedGoodsBatchId())
                    || row.getEdgeId() == null || !shipmentIds.contains(row.getShipmentId())
                    || !seenEdges.add(row.getEdgeId())) {
                continue;
            }
            seenShipmentIds.add(row.getShipmentId());
            SupplyImpactBoxExposure.Source source = shipmentSource(row);
            shipmentSources.add(source);
            propagateSourceGaps(gaps, propagatedSourceGaps, source);
            BigDecimal boxes = source.getBoxQuantity() == null ? null : decimal(source.getBoxQuantity());
            shipmentTotals.add(boxes);
            if (row.getEdgeQuantity() != null && row.getEdgeQuantity().signum() >= 0) {
                BigDecimal nativeDeduction = FgQuantityUnitConverter.convertWithPackaging(
                        row.getEdgeQuantity(), row.getEdgeUnit(), row.getFinishedUnit(), null,
                        row.getFinishedPackagingUnit(), row.getFinishedPackagingBaseUnit(),
                        row.getFinishedPackagingFactor(), row.getFinishedPackagingUnit(),
                        row.getFinishedPackagingBaseUnit(), row.getFinishedPackagingFactor());
                if (nativeDeduction == null) {
                    unconvertibleDeductions.add(row.getFinishedGoodsBatchId());
                } else {
                    actualDeductionByFinished.merge(row.getFinishedGoodsBatchId(), nativeDeduction, BigDecimal::add);
                }
            }
            TotalsAccumulator customer = customerTotals.computeIfAbsent(
                    row.getCustomerId() == null ? UNKNOWN_CUSTOMER : row.getCustomerId(),
                    ignored -> new TotalsAccumulator());
            customerNames.putIfAbsent(row.getCustomerId() == null ? UNKNOWN_CUSTOMER : row.getCustomerId(),
                    row.getCustomerName());
            customer.add(boxes);
        }
        boolean shipmentCoverageComplete = !shipmentIds.isEmpty();
        for (String shipmentId : shipmentIds) {
            if (!seenShipmentIds.contains(shipmentId)) {
                shipmentCoverageComplete = false;
                gaps.add(gap("SHIPMENT_EDGE_FACT_MISSING", "发货范围 " + shipmentId
                        + " 没有可核验的实际成品扣减边"));
                shipmentTotals.addUnknown();
            }
        }
        for (String finishedId : finishedIds) {
            List<SupplyImpactBoxExposureRow> matching = rowsByFinished.getOrDefault(finishedId, List.of());
            SupplyImpactBoxExposureRow row = matching.isEmpty() ? null : matching.get(0);
            if (row == null || row.getShippedQuantity() == null || row.getShippedQuantity().signum() < 0
                    || unconvertibleDeductions.contains(finishedId)
                    || (row != null
                    && row.getShippedQuantity().signum() >= 0
                    && actualDeductionByFinished.getOrDefault(finishedId, BigDecimal.ZERO)
                    .compareTo(row.getShippedQuantity()) != 0)) {
                shipmentCoverageComplete = false;
                gaps.add(gap("SHIPMENT_DEDUCTION_INCOMPLETE", "成品批次 " + finishedId
                        + " 的实际扣减与库存已发量不一致, 发货箱数保持部分未知"));
                shipmentTotals.addUnknown();
            }
        }
        if (shipmentIds.isEmpty()) {
            gaps.add(gap("SHIPMENT_SCOPE_EMPTY", "追溯范围没有可核验的发货单, 发货箱数保持未知"));
        }

        List<SupplyImpactBoxExposure.CustomerTotals> customers = new ArrayList<>();
        for (Map.Entry<String, TotalsAccumulator> entry : customerTotals.entrySet()) {
            customers.add(SupplyImpactBoxExposure.CustomerTotals.builder()
                    .customerId(UNKNOWN_CUSTOMER.equals(entry.getKey()) ? null : entry.getKey())
                    .customerName(customerNames.get(entry.getKey()))
                    .totals(entry.getValue().totals(lineageComplete && shipmentCoverageComplete))
                    .build());
        }
        if (customerTotals.containsKey(UNKNOWN_CUSTOMER)) {
            gaps.add(gap("SHIPMENT_CUSTOMER_UNKNOWN", "部分实际发货扣减没有同厂客户事实"));
        }

        result.stock(stockTotals.totals(lineageComplete))
                .shipped(shipmentIds.isEmpty()
                        ? TotalsAccumulator.unknownTotals()
                        : shipmentTotals.totals(lineageComplete && shipmentCoverageComplete))
                .stockSources(stockSources)
                .shipmentSources(shipmentSources)
                .customers(customers)
                .gaps(gaps);
        return result.build();
    }

    private SupplyImpactBoxExposure.Source stockSource(SupplyImpactBoxExposureRow row, String fallbackId) {
        if (row == null) {
            return SupplyImpactBoxExposure.Source.builder().sourceId(fallbackId)
                    .finishedGoodsBatchId(fallbackId)
                    .gaps(List.of("FINISHED_BATCH_FACT_MISSING")).build();
        }
        BigDecimal physical = physicalQuantity(row);
        List<String> sourceGaps = new ArrayList<>();
        BigDecimal boxes = null;
        if (row.getReservedQuantity() != null && row.getReservedQuantity().signum() < 0) {
            sourceGaps.add("RESERVED_QUANTITY_UNKNOWN");
        }
        boolean validSnapshot = FgQuantityUnitConverter.hasPackagingSpec(row.getFinishedPackagingUnit(),
                row.getFinishedPackagingBaseUnit(), row.getFinishedPackagingFactor())
                && isBox(row.getFinishedPackagingUnit());
        if (!validSnapshot) sourceGaps.add("BOX_PACKAGING_SNAPSHOT_UNKNOWN");
        if (physical == null || physical.signum() < 0) {
            sourceGaps.add("PHYSICAL_QUANTITY_UNKNOWN");
        } else if (validSnapshot) {
            boxes = toBoxes(physical, row.getFinishedUnit(), row.getFinishedPackagingUnit(),
                    row.getFinishedPackagingBaseUnit(), row.getFinishedPackagingFactor(),
                    row.getFinishedPackagingUnit(), row.getFinishedPackagingBaseUnit(),
                    row.getFinishedPackagingFactor());
            if (boxes == null) sourceGaps.add("BOX_UNIT_CONVERSION_UNKNOWN");
        }
        return SupplyImpactBoxExposure.Source.builder()
                .sourceId(row.getFinishedGoodsBatchId())
                .finishedGoodsBatchId(row.getFinishedGoodsBatchId())
                .batchNumber(row.getBatchNumber())
                .sourceQuantity(format(physical == null || physical.signum() < 0 ? null : physical))
                .sourceUnit(row.getFinishedUnit())
                .packagingUnit(row.getFinishedPackagingUnit())
                .packagingBaseUnit(row.getFinishedPackagingBaseUnit())
                .packagingFactor(formatPackagingFactor(row.getFinishedPackagingFactor()))
                .boxQuantity(format(boxes))
                .reservedQuantity(formatNonNegative(row.getReservedQuantity()))
                .gaps(sourceGaps).build();
    }

    private SupplyImpactBoxExposure.Source shipmentSource(SupplyImpactBoxExposureRow row) {
        List<String> sourceGaps = new ArrayList<>();
        BigDecimal boxes = null;
        boolean validSnapshot = FgQuantityUnitConverter.hasPackagingSpec(row.getDeliveryPackagingUnit(),
                row.getDeliveryPackagingBaseUnit(), row.getDeliveryPackagingFactor())
                && isBox(row.getDeliveryPackagingUnit());
        if (!validSnapshot) sourceGaps.add("BOX_PACKAGING_SNAPSHOT_UNKNOWN");
        if (row.getEdgeQuantity() == null || row.getEdgeQuantity().signum() < 0) {
            sourceGaps.add("SHIPMENT_DEDUCTION_UNKNOWN");
        } else if (row.getDeliveryRecordId() == null || row.getDeliveryItemId() == null) {
            sourceGaps.add("SHIPMENT_LINE_FACT_MISSING");
        } else if (validSnapshot) {
            boxes = toBoxes(row.getEdgeQuantity(), row.getEdgeUnit(),
                    row.getFinishedPackagingUnit(), row.getFinishedPackagingBaseUnit(),
                    row.getFinishedPackagingFactor(), row.getDeliveryPackagingUnit(),
                    row.getDeliveryPackagingBaseUnit(), row.getDeliveryPackagingFactor());
            if (boxes == null) sourceGaps.add("BOX_UNIT_CONVERSION_UNKNOWN");
        }
        return SupplyImpactBoxExposure.Source.builder()
                .sourceId(row.getEdgeId())
                .finishedGoodsBatchId(row.getFinishedGoodsBatchId())
                .batchNumber(row.getBatchNumber())
                .sourceQuantity(format(row.getEdgeQuantity() == null || row.getEdgeQuantity().signum() < 0
                        ? null : row.getEdgeQuantity()))
                .sourceUnit(row.getEdgeUnit())
                .packagingUnit(row.getDeliveryPackagingUnit())
                .packagingBaseUnit(row.getDeliveryPackagingBaseUnit())
                .packagingFactor(formatPackagingFactor(row.getDeliveryPackagingFactor()))
                .boxQuantity(format(boxes))
                .deliveryRecordId(row.getDeliveryRecordId())
                .deliveryItemId(row.getDeliveryItemId())
                .customerId(row.getCustomerId())
                .customerName(row.getCustomerName())
                .gaps(sourceGaps).build();
    }

    private static BigDecimal physicalQuantity(SupplyImpactBoxExposureRow row) {
        if (row.getProducedQuantity() == null || row.getShippedQuantity() == null
                || row.getProducedQuantity().signum() < 0 || row.getShippedQuantity().signum() < 0) return null;
        BigDecimal physical = row.getProducedQuantity().subtract(row.getShippedQuantity());
        return physical.signum() < 0 ? null : physical;
    }

    private static BigDecimal toBoxes(BigDecimal quantity, String fromUnit,
                                      String fromPackagingUnit, String fromPackagingBaseUnit,
                                      BigDecimal fromPackagingFactor, String toPackagingUnit,
                                      String toPackagingBaseUnit, BigDecimal toPackagingFactor) {
        if (quantity == null
                || !FgQuantityUnitConverter.hasPackagingSpec(
                fromPackagingUnit, fromPackagingBaseUnit, fromPackagingFactor)
                || !FgQuantityUnitConverter.hasPackagingSpec(
                toPackagingUnit, toPackagingBaseUnit, toPackagingFactor)) return null;
        return FgQuantityUnitConverter.convertWithPackaging(quantity, fromUnit, toPackagingUnit, null,
                fromPackagingUnit, fromPackagingBaseUnit, fromPackagingFactor,
                toPackagingUnit, toPackagingBaseUnit, toPackagingFactor);
    }

    private static boolean isBox(String packagingUnit) {
        if (packagingUnit == null) return false;
        String normalized = UnitContractServiceImpl.crossLanguageCode(packagingUnit);
        return "箱".equals(normalized) || "case".equals(normalized) || "carton".equals(normalized);
    }

    private static Set<String> cleanIds(Collection<String> ids) {
        if (ids == null) return Collections.emptySet();
        Set<String> result = new LinkedHashSet<>();
        for (String id : ids) if (id != null && !id.isBlank()) result.add(id);
        return result;
    }

    private static SupplyImpactDTO.Gap gap(String code, String message) {
        return SupplyImpactDTO.Gap.builder().code(code).message(message).build();
    }

    private static void propagateSourceGaps(List<SupplyImpactDTO.Gap> gaps, Set<String> propagated,
                                            SupplyImpactBoxExposure.Source source) {
        String sourceId = source.getSourceId() == null ? "unknown" : source.getSourceId();
        for (String code : source.getGaps()) {
            String key = sourceId + "|" + code;
            if (propagated.add(key)) {
                gaps.add(gap(code, "来源 " + sourceId + " 的箱数事实不完整: " + code));
            }
        }
    }

    private static SupplyImpactBoxExposure unknown(SupplyImpactBoxExposure.SupplyImpactBoxExposureBuilder builder,
                                                   List<SupplyImpactDTO.Gap> gaps,
                                                   boolean lineageComplete) {
        return builder.stock(TotalsAccumulator.unknownTotals())
                .shipped(TotalsAccumulator.unknownTotals())
                .stockSources(List.of()).shipmentSources(List.of()).customers(List.of())
                .gaps(gaps).build();
    }

    private static String format(BigDecimal value) {
        if (value == null) return null;
        return value.stripTrailingZeros().toPlainString();
    }

    private static String formatNonNegative(BigDecimal value) {
        return value == null || value.signum() < 0 ? null : format(value);
    }

    private static String formatPackagingFactor(BigDecimal value) {
        return value == null || value.signum() <= 0 ? null : format(value);
    }

    private static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }

    private static final class TotalsAccumulator {
        private BigDecimal known;
        private int knownCount;
        private int unknownCount;

        private TotalsAccumulator() {}

        private TotalsAccumulator(List<SupplyImpactBoxExposure.Source> sources) {
            for (SupplyImpactBoxExposure.Source source : sources) {
                add(source.getBoxQuantity() == null ? null : decimal(source.getBoxQuantity()));
            }
        }

        private void add(BigDecimal boxes) {
            if (boxes == null) unknownCount++;
            else { knownCount++; known = known == null ? boxes : known.add(boxes); }
        }

        private void addUnknown() {
            unknownCount++;
        }

        private SupplyImpactBoxExposure.BoxTotals totals(boolean lineageComplete) {
            boolean complete = lineageComplete && knownCount > 0 && unknownCount == 0;
            String subtotal = format(known);
            return SupplyImpactBoxExposure.BoxTotals.builder().complete(complete)
                    .knownBoxSubtotal(subtotal)
                    .totalBoxQuantity(complete ? subtotal : null)
                    .knownSourceCount(knownCount).unknownSourceCount(unknownCount).build();
        }

        private static SupplyImpactBoxExposure.BoxTotals unknownTotals() {
            return SupplyImpactBoxExposure.BoxTotals.builder().complete(false)
                    .totalBoxQuantity(null).knownBoxSubtotal(null).knownSourceCount(0)
                    .unknownSourceCount(1).build();
        }
    }
}
