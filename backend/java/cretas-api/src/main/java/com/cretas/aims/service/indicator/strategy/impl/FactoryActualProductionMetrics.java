package com.cretas.aims.service.indicator.strategy.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.controller.dto.indicator.ProductProductionMetricsResponse;
import com.cretas.aims.controller.dto.indicator.ProductProductionMetricsResponse.ProductResult;
import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitUsageScene;
import com.cretas.aims.service.unit.UnitDimension;
import com.cretas.aims.service.yield.OrderCostBreakdownService;
import com.cretas.aims.service.yield.YieldReportService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/** Adapter to the authoritative reporting/cost services. Never uses planned quantities as actuals. */
@Component
@RequiredArgsConstructor
public class FactoryActualProductionMetrics {
    public static final String SOURCE_VERSION = "actual-flow-v2";
    public enum Metric { YIELD_RATE, GOOD_OUTPUT_RATE, UNIT_COST, TOTAL_COST }

    @PersistenceContext
    private EntityManager entityManager;
    private final YieldReportService yieldReportService;
    private final OrderCostBreakdownService costBreakdownService;
    private final UnitContractService units;

    @Transactional(readOnly = true)
    public BigDecimal compute(String factoryId, LocalDate from, LocalDate through, Metric metric) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = through == null ? LocalDate.now() : through;
        if (factoryId == null || factoryId.isBlank() || end.isBefore(start)) {
            throw new IllegalArgumentException("工厂与统计日期范围必须有效");
        }
        return aggregate(factoryId, completedRows(factoryId, start, end), metric);
    }

    @Transactional(readOnly = true)
    public ProductProductionMetricsResponse byProduct(String factoryId, LocalDate start, LocalDate end, boolean maskCost) {
        if (factoryId == null || factoryId.isBlank() || start == null || end == null || end.isBefore(start)) {
            throw new IllegalArgumentException("工厂与统计日期范围必须有效");
        }
        List<?> rows = completedRows(factoryId, start, end);
        Map<String, List<Object[]>> groups = new LinkedHashMap<>();
        for (Object raw : rows) {
            Object[] row = (Object[]) raw;
            String id = (String) row[12];
            groups.computeIfAbsent(id, ignored -> new ArrayList<>()).add(row);
        }
        List<ProductResult> products = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> group : groups.entrySet()) {
            List<Object[]> batches = group.getValue();
            String name = (String) batches.getFirst()[13];
            products.add(ProductResult.builder().productTypeId(group.getKey())
                    .productName(name == null || name.isBlank() ? "成品资料待补全" : name)
                    .batchCount(batches.size()).batchNumbers(batches.stream().map(r -> (String) r[1]).toList())
                    .yieldRate(aggregate(factoryId, batches, Metric.YIELD_RATE))
                    .goodOutputRate(aggregate(factoryId, batches, Metric.GOOD_OUTPUT_RATE))
                    .totalCost(maskCost ? null : aggregate(factoryId, batches, Metric.TOTAL_COST))
                    .unitCost(maskCost ? null : aggregate(factoryId, batches, Metric.UNIT_COST))
                    .costMasked(maskCost).build());
        }
        return ProductProductionMetricsResponse.builder().periodStart(start).periodEnd(end)
                .sourceVersion(SOURCE_VERSION).products(products).build();
    }

    private List<?> completedRows(String factoryId, LocalDate start, LocalDate end) {
        // Completion date is the business period. CLERK_WIP is an internal artifact, not another batch.
        return entityManager.createNativeQuery("""
                SELECT b.id, b.batch_number, b.actual_quantity, b.good_quantity, b.unit,
                       b.defect_quantity, s.id, s.defect_count_outcome, s.defect_quantity, s.quantity_unit,
                       (SELECT COUNT(*) FROM production_batches x WHERE x.factory_id = b.factory_id
                        AND x.production_plan_id = b.production_plan_id AND x.deleted_at IS NULL
                        AND (x.batch_type IS NULL OR x.batch_type <> 'CLERK_WIP')),
                       (SELECT COUNT(*) FROM production_settlement_output_lines o
                        WHERE o.factory_id = b.factory_id AND o.settlement_id = s.id AND o.deleted_at IS NULL),
                       b.product_type_id, pt.name
                FROM production_batches b
                LEFT JOIN product_types pt ON pt.id = b.product_type_id AND pt.factory_id = b.factory_id
                LEFT JOIN production_plans p ON p.id = b.production_plan_id AND p.factory_id = b.factory_id
                LEFT JOIN production_settlements s ON s.production_plan_id = p.id AND s.factory_id = b.factory_id
                    AND s.deleted_at IS NULL
                WHERE b.factory_id = ?1 AND b.status = 'COMPLETED' AND b.deleted_at IS NULL
                  AND (b.batch_type IS NULL OR b.batch_type <> 'CLERK_WIP')
                  AND (b.production_plan_id IS NULL OR (p.status = 'COMPLETED' AND p.deleted_at IS NULL))
                  AND b.end_time >= ?2 AND b.end_time < ?3
                ORDER BY b.id
                """).setParameter(1, factoryId).setParameter(2, start.atStartOfDay())
                .setParameter(3, end.plusDays(1).atStartOfDay()).getResultList();
    }

    BigDecimal aggregate(String factoryId, List<?> rows, Metric metric) {
        if (rows.isEmpty()) return null;
        BigDecimal inputKg = BigDecimal.ZERO, outputKg = BigDecimal.ZERO;
        BigDecimal inspectedKg = BigDecimal.ZERO, totalCost = BigDecimal.ZERO;
        for (Object raw : rows) {
            Object[] row = (Object[]) raw;
            Long id = ((Number) row[0]).longValue();
            String number = (String) row[1];
            BigDecimal actual = decimal(row[2]), good = decimal(row[3]);
            String outputUnit = (String) row[4];
            // A shared plan's first input cannot be counted once for every co-product batch.
            // Until a unique plan/output allocation is proven, do not publish a partial factory KPI.
            if (((Number) row[10]).longValue() > 1) return null;
            BatchYieldDTO flow = yieldReportService.getYield(factoryId, id);
            if (flow == null || !Boolean.TRUE.equals(flow.getComplete()) || Boolean.TRUE.equals(flow.getInProgress())
                    || !positive(flow.getFirstStepInput()) || flow.getLastStepOutputInFirstUnit() == null
                    || flow.getLastStepOutputInFirstUnit().signum() < 0) return null;
            BigDecimal input = kg(factoryId, flow.getFirstStepInput(), flow.getFirstStepInputUnit());
            BigDecimal output = kg(factoryId, flow.getLastStepOutputInFirstUnit(), flow.getFirstStepInputUnit());
            if (input == null || output == null) return null;
            inputKg = inputKg.add(input);
            outputKg = outputKg.add(output);
            if (metric == Metric.YIELD_RATE) continue;

            // Report output is ALREADY qualified output. Defects are extra rejected output,
            // not a quantity to subtract again. Settlement quality is explicit and tri-state;
            // batch fields filled by warehouse receipt must not fabricate a zero-defect count.
            BigDecimal defect;
            String defectUnit;
            if (row[6] != null) {
                if (!("COUNTED".equals(row[7]) || "NONE_CONFIRMED".equals(row[7]))
                        || ((Number) row[11]).longValue() != 1) return null;
                defect = decimal(row[8]);
                defectUnit = (String) row[9];
                if (defect == null || ("COUNTED".equals(row[7]) ? defect.signum() <= 0 : defect.signum() != 0)) return null;
            } else {
                defect = decimal(row[5]);
                defectUnit = outputUnit;
                if (!positive(actual) || good == null || defect == null
                        || actual.compareTo(good.add(defect)) != 0) return null;
                UnitConversionResult counted = units.convert(good, context(factoryId, outputUnit, flow.getLastStepOutputUnit()));
                if (counted == null || !counted.succeeded() || flow.getLastStepOutput() == null
                        || counted.quantity().compareTo(flow.getLastStepOutput()) != 0) return null;
            }
            if (defect == null || defect.signum() < 0 || !positive(flow.getLastStepOutput())) return null;
            UnitConversionResult rejected = units.convert(defect, context(factoryId, defectUnit, flow.getLastStepOutputUnit()));
            if (rejected == null || !rejected.succeeded()) return null;
            BigDecimal rejectedKg = output.multiply(rejected.quantity()).divide(flow.getLastStepOutput(), 10, RoundingMode.HALF_UP);
            inspectedKg = inspectedKg.add(output).add(rejectedKg);
            if (metric == Metric.GOOD_OUTPUT_RATE) continue;

            // Retain the existing missing-price, estimated-cost and WIP-transfer protections.
            OrderCostBreakdownDTO cost = costBreakdownService.computeByBatch(factoryId, number, false);
            if (cost == null || !cost.isHasData() || !cost.isCostComplete() || cost.isPriceMasked()
                    || !"COMPLETE".equals(cost.getCalculationStatus()) || cost.getTotalCost() == null
                    || cost.getTotalCost().signum() < 0 || cost.getConvertedOutputKg() == null
                    || cost.getConvertedOutputKg().subtract(output).abs().compareTo(new BigDecimal("0.000001")) > 0
                    || (cost.getEstimatedAuxItemCount() != null && cost.getEstimatedAuxItemCount() > 0)
                    || (cost.getPartiallyRecordedAuxItemCount() != null && cost.getPartiallyRecordedAuxItemCount() > 0)
                    || hasReferencePackagingCost(cost)) return null;
            totalCost = totalCost.add(cost.getTotalCost());
        }
        return switch (metric) {
            case YIELD_RATE -> divide(outputKg.multiply(BigDecimal.valueOf(100)), inputKg);
            case GOOD_OUTPUT_RATE -> divide(outputKg.multiply(BigDecimal.valueOf(100)), inspectedKg);
            case UNIT_COST -> divide(totalCost, outputKg);
            case TOTAL_COST -> totalCost.setScale(4, RoundingMode.HALF_UP);
        };
    }


    private boolean hasReferencePackagingCost(OrderCostBreakdownDTO cost) {
        return cost.getPackagingDetails() != null && cost.getPackagingDetails().stream()
                .anyMatch(detail -> detail != null && detail.getAmount() != null
                        && !"ACTUAL_MATERIAL_BATCH".equals(detail.getPriceSource()));
    }

    private BigDecimal kg(String factoryId, BigDecimal quantity, String unit) {
        if (unit == null || unit.isBlank()
                || units.describe(factoryId, unit).filter(value -> value.dimension() == UnitDimension.MASS).isEmpty()) return null;
        // productTypeId stays null: only universal mass conversions are allowed here.
        // Count-to-weight conversion was already proven from the historical plan by YieldReportService.
        UnitConversionResult result = units.convert(quantity, context(factoryId, unit, "kg"));
        return result != null && result.succeeded() ? result.quantity() : null;
    }

    private UnitConversionContext context(String factoryId, String from, String to) {
        return new UnitConversionContext(factoryId, null, from, to, null, UnitUsageScene.PRODUCTION, 10, RoundingMode.HALF_UP);
    }
    private static BigDecimal decimal(Object value) { return value == null ? null : new BigDecimal(value.toString()); }
    private static boolean positive(BigDecimal value) { return value != null && value.signum() > 0; }
    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) {
        return denominator.signum() > 0 ? numerator.divide(denominator, 4, RoundingMode.HALF_UP) : null;
    }
}
