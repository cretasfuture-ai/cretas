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
import com.cretas.aims.service.yield.ProductionSummaryService;
import com.cretas.aims.service.indicator.MultiOutputActualMetricsService;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.dto.processentry.ProcessSheetInventoryItem;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
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
public class FactoryActualProductionMetrics {
    public static final String SOURCE_VERSION = "actual-flow-v3";
    public enum Metric { YIELD_RATE, GOOD_OUTPUT_RATE, UNIT_COST, TOTAL_COST }

    @PersistenceContext
    private EntityManager entityManager;
    private final YieldReportService yieldReportService;
    private final OrderCostBreakdownService costBreakdownService;
    private final UnitContractService units;
    private final ProductionSummaryService productionSummaryService;
    private final MultiOutputActualMetricsService multiOutputMetrics;
    private final ProcessSheetService processSheetService;

    /** Kept for source compatibility with strategy/unit tests that exercise the legacy path. */
    public FactoryActualProductionMetrics(YieldReportService yieldReportService,
                                          OrderCostBreakdownService costBreakdownService,
                                          UnitContractService units) {
        this(yieldReportService, costBreakdownService, units, null, new MultiOutputActualMetricsService(), null);
    }

    public FactoryActualProductionMetrics(YieldReportService yieldReportService,
                                          OrderCostBreakdownService costBreakdownService,
                                          UnitContractService units,
                                          ProductionSummaryService productionSummaryService,
                                          MultiOutputActualMetricsService multiOutputMetrics) {
        this(yieldReportService, costBreakdownService, units, productionSummaryService,
                multiOutputMetrics, null);
    }

    @Autowired
    public FactoryActualProductionMetrics(YieldReportService yieldReportService,
                                          OrderCostBreakdownService costBreakdownService,
                                          UnitContractService units,
                                          ProductionSummaryService productionSummaryService,
                                          MultiOutputActualMetricsService multiOutputMetrics,
                                          ProcessSheetService processSheetService) {
        this.yieldReportService = yieldReportService;
        this.costBreakdownService = costBreakdownService;
        this.units = units;
        this.productionSummaryService = productionSummaryService;
        this.multiOutputMetrics = multiOutputMetrics == null ? new MultiOutputActualMetricsService() : multiOutputMetrics;
        this.processSheetService = processSheetService;
    }

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
                    .yieldRate(aggregate(factoryId, batches, Metric.YIELD_RATE, rows))
                    .goodOutputRate(aggregate(factoryId, batches, Metric.GOOD_OUTPUT_RATE, rows))
                    .totalCost(maskCost ? null : aggregate(factoryId, batches, Metric.TOTAL_COST, rows))
                    .unitCost(maskCost ? null : aggregate(factoryId, batches, Metric.UNIT_COST, rows))
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
                       b.product_type_id, pt.name, b.production_plan_id
                FROM production_batches b
                LEFT JOIN product_types pt ON pt.id = b.product_type_id AND pt.factory_id = b.factory_id
                LEFT JOIN production_plans p ON p.id = b.production_plan_id AND p.factory_id = b.factory_id
                LEFT JOIN production_settlements s ON s.production_plan_id = p.id AND s.factory_id = b.factory_id
                    AND s.deleted_at IS NULL
                WHERE b.factory_id = ?1 AND b.status = 'COMPLETED' AND b.deleted_at IS NULL
                  AND (b.batch_type IS NULL OR b.batch_type <> 'CLERK_WIP')
                  AND (b.production_plan_id IS NULL OR (p.status = 'COMPLETED' AND p.deleted_at IS NULL))
                  AND b.end_time >= ?2 AND b.end_time < ?3
                """ + com.cretas.aims.service.production.FormalFinishedOutputScope.SQL_PREDICATE
                + " ORDER BY b.id").setParameter(1, factoryId).setParameter(2, start.atStartOfDay())
                .setParameter(3, end.plusDays(1).atStartOfDay()).getResultList();
    }

    BigDecimal aggregate(String factoryId, List<?> rows, Metric metric) {
        return aggregate(factoryId, rows, metric, rows);
    }

    private BigDecimal aggregate(String factoryId, List<?> rows, Metric metric, List<?> evidenceRows) {
        if (rows.isEmpty()) return null;
        if (rows.stream().map(r -> (Object[]) r).anyMatch(r -> ((Number) r[10]).longValue() > 1)) {
            return aggregateJointPlans(factoryId, rows, evidenceRows, metric);
        }
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

    /** Deduplicates shared plan input/cost and uses the persisted finished weight snapshot. */
    private BigDecimal aggregateJointPlans(String factoryId, List<?> rows, List<?> evidenceRows, Metric metric) {
        if (productionSummaryService == null) return null;
        boolean needsCost = metric == Metric.UNIT_COST || metric == Metric.TOTAL_COST;
        Map<String, List<Object[]>> byPlan = new LinkedHashMap<>();
        for (Object raw : rows) {
            Object[] row = (Object[]) raw;
            if (row.length <= 14 || row[14] == null || row[14].toString().isBlank()) return null;
            byPlan.computeIfAbsent(row[14].toString(), ignored -> new ArrayList<>()).add(row);
        }
        List<MultiOutputActualMetricsService.Plan> plans = new ArrayList<>();
        List<MultiOutputActualMetricsService.Output> outputs = new ArrayList<>();
        for (Map.Entry<String, List<Object[]>> entry : byPlan.entrySet()) {
            var summary = productionSummaryService.computeSummary(factoryId, entry.getKey(), true);
            if (summary == null || summary.getYieldRawDenominator() == null
                    || summary.getYieldRawDenominator().signum() <= 0
                    || summary.getTotalFinishedWeight() == null
                    || summary.getTotalFinishedWeight().signum() <= 0
                    || (metric == Metric.YIELD_RATE && summary.getYieldNote() != null)) return null;
            var cost = needsCost ? costBreakdownService.computeByPlan(factoryId, entry.getKey(), false) : null;
            if (needsCost && (cost == null || !cost.isHasData() || !cost.isCostComplete()
                    || cost.isPriceMasked() || !"COMPLETE".equals(cost.getCalculationStatus())
                    || cost.getTotalCost() == null || cost.getTotalCost().signum() < 0
                    || (cost.getEstimatedAuxItemCount() != null && cost.getEstimatedAuxItemCount() > 0)
                    || (cost.getPartiallyRecordedAuxItemCount() != null && cost.getPartiallyRecordedAuxItemCount() > 0)
                    || hasReferencePackagingCost(cost))) return null;
            plans.add(new MultiOutputActualMetricsService.Plan(entry.getKey(), summary.getYieldRawDenominator(), needsCost ? cost.getTotalCost() : null));
            List<?> lines = entityManager.createNativeQuery("""
                    SELECT o.reported_batch_number, o.product_type_id, o.reported_quantity,
                           o.quantity_unit, o.allocated_cost, s.defect_count_outcome,
                           s.defect_quantity, s.quantity_unit
                    FROM production_settlements s
                    JOIN production_settlement_output_lines o ON o.settlement_id=s.id
                       AND o.factory_id=s.factory_id AND o.production_plan_id=s.production_plan_id
                       AND o.deleted_at IS NULL
                    WHERE s.factory_id=?1 AND s.production_plan_id=?2 AND s.deleted_at IS NULL
                      AND COALESCE(o.status,'REPORTED') <> 'CANCELLED'
                      AND NULLIF(TRIM(o.reported_batch_number),'') IS NOT NULL
                    ORDER BY o.id
                    """).setParameter(1, factoryId).setParameter(2, entry.getKey()).getResultList();
            if (lines.isEmpty()) return null;
            List<ProcessSheetInventoryItem> card = processSheetService == null ? List.of()
                    : processSheetService.getInventoryYieldCard(factoryId, entry.getKey());
            BigDecimal matchedWeight = BigDecimal.ZERO;
            for (Object raw : lines) {
                Object[] line = (Object[]) raw;
                boolean inWindow = evidenceRows.stream().map(r -> (Object[]) r)
                        .anyMatch(r -> java.util.Objects.equals(r[1], line[0])
                                && java.util.Objects.equals(r[12], line[1])
                                && r.length > 14 && java.util.Objects.equals(r[14], entry.getKey()));
                if (!inWindow) return null;
                var matching = card.stream()
                        .filter(i -> java.util.Objects.equals(i.getBatchNumber(), line[0])
                                && java.util.Objects.equals(i.getProductTypeId(), line[1])
                                && i.getProductWeight() != null && i.getProductWeight().signum() > 0)
                        .toList();
                if (matching.size() != 1) return null;
                ProcessSheetInventoryItem snapshot = matching.getFirst();
                BigDecimal outputKg = snapshot.getProductWeight();
                matchedWeight = matchedWeight.add(outputKg);
                BigDecimal allocated = decimal(line[4]);
                if (outputKg == null || (needsCost && allocated == null) || line[0] == null || line[1] == null) return null;
                if (metric != Metric.YIELD_RATE && line[5] == null) return null;
                var quality = "NONE_CONFIRMED".equals(line[5])
                        ? MultiOutputActualMetricsService.QualityStatus.NONE_CONFIRMED
                        : "COUNTED".equals(line[5]) && lines.size() == 1 ? MultiOutputActualMetricsService.QualityStatus.COUNTED : null;
                if (metric != Metric.YIELD_RATE && quality == null) return null;
                BigDecimal reportedQuantity = decimal(line[2]);
                if (reportedQuantity == null || reportedQuantity.signum() <= 0) return null;
                if (snapshot.getProduced() == null || snapshot.getProduced().compareTo(reportedQuantity) != 0
                        || !java.util.Objects.equals(snapshot.getUnit(), line[3])) return null;
                if (metric != Metric.YIELD_RATE && quality == MultiOutputActualMetricsService.QualityStatus.NONE_CONFIRMED
                        && (decimal(line[6]) == null || decimal(line[6]).signum() != 0)) return null;
                if (quality == MultiOutputActualMetricsService.QualityStatus.COUNTED
                        && (decimal(line[6]) == null || decimal(line[6]).signum() <= 0)) return null;
                BigDecimal defectKg = metric == Metric.YIELD_RATE || quality == MultiOutputActualMetricsService.QualityStatus.NONE_CONFIRMED
                        ? BigDecimal.ZERO : outputKg(factoryId, decimal(line[6]), (String) line[7],
                        (String) line[3], snapshot.getProductWeight().divide(reportedQuantity, 10, RoundingMode.HALF_UP));
                if (defectKg == null || defectKg.signum() < 0) return null;
                outputs.add(new MultiOutputActualMetricsService.Output(entry.getKey(), (String) line[0],
                        (String) line[1], outputKg, outputKg, defectKg, quality, allocated));
            }
            if (matchedWeight.subtract(summary.getTotalFinishedWeight()).abs()
                    .compareTo(new BigDecimal("0.000001")) > 0) return null;
        }
        var productIds = rows.stream().map(r -> (String) ((Object[]) r)[12]).filter(java.util.Objects::nonNull).distinct().toList();
        String product = productIds.size() == 1 ? productIds.getFirst() : null;
        MultiOutputActualMetricsService.Metric adapted = MultiOutputActualMetricsService.Metric.valueOf(metric.name());
        return product == null ? multiOutputMetrics.aggregate(plans, outputs, adapted)
                : multiOutputMetrics.byProduct(plans, outputs, product, adapted);
    }

    private BigDecimal outputKg(String factoryId, BigDecimal quantity, String unit, String snapshotUnit, BigDecimal kgPerUnit) {
        if (quantity == null || quantity.signum() <= 0 || unit == null || unit.isBlank()) return null;
        var described = units.describe(factoryId, unit).orElse(null);
        if (described != null && described.dimension() == UnitDimension.MASS) return kg(factoryId, quantity, unit);
        // This factor belongs to the snapshot's unit only. Other count/package
        // units cannot borrow it (e.g. one case is not one box).
        if (kgPerUnit == null || kgPerUnit.signum() <= 0 || described == null) return null;
        var converted = units.convert(quantity, context(factoryId, unit, snapshotUnit));
        return converted != null && converted.succeeded() ? kgPerUnit.multiply(converted.quantity()) : null;
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
