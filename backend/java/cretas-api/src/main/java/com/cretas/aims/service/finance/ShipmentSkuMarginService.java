package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.inventory.SalesDeliveryItemRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Read-only view of the same physical movements used by COGS. Order cost snapshots are never consulted. */
@Service @RequiredArgsConstructor
public class ShipmentSkuMarginService {
    private final SalesDeliveryItemRepository shipments;
    private final JdbcTemplate jdbc;
    private final IncomeStatementService incomeStatements;
    private final EntityManager entityManager;
    private final SalesReturnCostService salesReturnCosts;

    // Delivery unitPrice is the saved untaxed shipment price (same basis as netShippedValue).
    static final String LINES_SQL = """
        SELECT i.id, i.product_type_id, COALESCE(NULLIF(i.product_name,''),p.name) AS product_name,
               i.unit, i.delivered_quantity, i.unit_price
        FROM sales_delivery_items i JOIN sales_delivery_records r ON r.id=i.delivery_record_id
        LEFT JOIN product_types p ON p.id=i.product_type_id AND p.factory_id=r.factory_id AND p.deleted_at IS NULL
        WHERE r.factory_id=? AND r.delivery_date BETWEEN ? AND ?
          AND COALESCE(r.record_role,'LEGACY')<>'MASTER' AND r.status IN ('SHIPPED','DELIVERED')
          AND i.deleted_at IS NULL AND r.deleted_at IS NULL AND i.delivered_quantity>0
        ORDER BY i.id
        """;

    @Transactional(readOnly = true)
    public ShipmentSkuMarginDTO generate(String factoryId, LocalDate start, LocalDate end) {
        if (factoryId == null || factoryId.isBlank() || start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("请选择正确的工厂和统计期间");
        }
        // Serialize against shipment, cost correction and closing, as the existing cost/indicator queries do.
        ProductionCostConsistency.lockFactory(entityManager, factoryId);
        List<Object[]> movements = shipments.findShippedMovementsForLedger(factoryId, start, end);
        List<Line> lines = jdbc.query(LINES_SQL, (rs, n) -> new Line(rs.getLong("id"),
                rs.getString("product_type_id"), rs.getString("product_name"), rs.getString("unit"),
                rs.getBigDecimal("delivered_quantity"), rs.getBigDecimal("unit_price")),
                factoryId, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
        Map<Long, BigDecimal> costByLine = new HashMap<>();
        Map<Long, Set<String>> batchesByLine = new HashMap<>();
        Map<Long, String> productByLine = new HashMap<>();
        Map<Long, List<ShipmentSkuMarginDTO.BatchSource>> sourcesByLine = new LinkedHashMap<>();
        Map<Long, String> numberByLine = new HashMap<>(), dateByLine = new HashMap<>();
        for (Object[] m : movements) {
            if (m[2] == null) throw incomplete("实际出货批次缺少成本，请补齐批次成本后重试");
            Long lineId = ((Number) m[8]).longValue();
            BigDecimal batchCost = decimal(m[1]).multiply(decimal(m[2]));
            costByLine.merge(lineId, batchCost, BigDecimal::add);
            sourcesByLine.computeIfAbsent(lineId, k -> new ArrayList<>()).add(ShipmentSkuMarginDTO.BatchSource.builder()
                    .batchNumber(Objects.toString(m[4], "")).quantity(decimal(m[1])).unit(Objects.toString(m[7], ""))
                    .unitCost(decimal(m[2])).rawCost(batchCost).cost(money(batchCost)).build());
            numberByLine.put(lineId, Objects.toString(m[6], ""));
            dateByLine.put(lineId, Objects.toString(m[0], ""));
            batchesByLine.computeIfAbsent(lineId, k -> new HashSet<>()).add(Objects.toString(m[4], ""));
            String product = Objects.toString(m[3], "");
            String old = productByLine.putIfAbsent(lineId, product);
            if (old != null && !old.equals(product)) throw incomplete("出货行与实际批次产品不一致");
        }
        Map<Key, Group> groups = new LinkedHashMap<>();
        Set<Long> lineIds = new HashSet<>();
        for (Line line : lines) {
            if (!lineIds.add(line.id) || line.product == null || line.unit == null
                    || !line.product.equals(productByLine.get(line.id)) || !costByLine.containsKey(line.id)) {
                throw incomplete("出货行与实际扣库来源不完整，请核对出货批次");
            }
            if (line.price == null) throw incomplete("实际出货行缺少售价，请补齐出货售价后重试");
            Group g = groups.computeIfAbsent(new Key(line.product, line.unit),
                    k -> new Group(line.product, line.name, line.unit));
            g.quantity = g.quantity.add(line.quantity);
            g.revenue = g.revenue.add(money(line.quantity.multiply(line.price)));
            g.rawCost = g.rawCost.add(costByLine.get(line.id));
            g.lines++;
            g.batches.addAll(batchesByLine.get(line.id));
            g.sources.add(ShipmentSkuMarginDTO.SourceLine.builder().shipmentLineId(line.id)
                    .deliveryNumber(numberByLine.get(line.id)).deliveryDate(dateByLine.get(line.id))
                    .quantity(line.quantity).unit(line.unit).revenue(money(line.quantity.multiply(line.price)))
                    .rawCost(costByLine.get(line.id)).cost(money(costByLine.get(line.id)))
                    .batches(sourcesByLine.get(line.id)).build());
        }
        if (!lineIds.equals(costByLine.keySet())) throw incomplete("出货收入与成本来源不一致，请核对出货行");
        List<ShipmentSkuMarginDTO.Row> rows = new ArrayList<>();
        BigDecimal revenue = BigDecimal.ZERO, rawCost = BigDecimal.ZERO, displayedCost = BigDecimal.ZERO;
        for (Group g : groups.values()) {
            BigDecimal cost = money(g.rawCost), profit = g.revenue.subtract(cost);
            rows.add(ShipmentSkuMarginDTO.Row.builder().productTypeId(g.product).productName(g.name)
                    .unit(g.unit).quantity(g.quantity).shipmentLineCount(g.lines).batchCount(g.batches.size())
                    .sources(g.sources)
                    .revenue(g.revenue).rawCost(g.rawCost).cost(cost).grossProfit(profit)
                    .marginRate(rate(profit, g.revenue)).build());
            revenue = revenue.add(g.revenue); rawCost = rawCost.add(g.rawCost); displayedCost = displayedCost.add(cost);
        }
        rows.sort(Comparator.comparing(ShipmentSkuMarginDTO.Row::getProductTypeId)
                .thenComparing(ShipmentSkuMarginDTO.Row::getUnit));
        BigDecimal cost = money(rawCost), profit = revenue.subtract(cost);
        var result = ShipmentSkuMarginDTO.builder().periodStart(start).periodEnd(end).rows(rows)
                .totalRevenue(money(revenue)).totalCost(cost).grossProfit(money(profit))
                .marginRate(rate(profit, revenue)).roundingAdjustment(cost.subtract(displayedCost))
                .reconciliationStatus("PARTIAL_PERIOD")
                .reconciliationMessage("当前不是完整会计月，仅展示实际出货；选择完整会计月后可与已过账利润表核对。").build();
        if (start.getDayOfMonth() == 1 && end.getDayOfMonth() == end.lengthOfMonth()) {
            var financial = incomeStatements.generate(factoryId, start.getYear(), start.getMonthValue(), end.getYear(), end.getMonthValue());
            result.setFinancialStatement(financial);
            if (financial.getPendingDraftVoucherCount() > 0) {
                result.setReconciliationStatus("PENDING_VOUCHERS");
                result.setReconciliationMessage("仍有凭证待过账，请完成过账后核对。");
                return result;
            }
            BigDecimal returnedRevenue = salesReturnCosts.returnedRevenue(factoryId, start, end);
            BigDecimal returnedCost = salesReturnCosts.returnedCost(factoryId, start, end);
            BigDecimal netRevenue = money(revenue.subtract(returnedRevenue));
            BigDecimal netCost = money(rawCost.subtract(returnedCost));
            BigDecimal netGrossProfit = netRevenue.subtract(netCost);
            result.setReconciliationReturnedRevenue(money(returnedRevenue));
            result.setReconciliationReturnedCost(money(returnedCost));
            result.setReconciliationNetRevenue(netRevenue);
            result.setReconciliationNetCost(netCost);
            result.setReconciliationNetGrossProfit(netGrossProfit);
            boolean equal = netRevenue.compareTo(financial.getTotalRevenue()) == 0
                    && netCost.compareTo(financial.getTotalCost()) == 0
                    && netGrossProfit.compareTo(financial.getGrossProfit()) == 0;
            result.setReconciliationStatus(equal ? "MATCH" : "MISMATCH");
            result.setReconciliationMessage(equal ? "实际出货扣除本期退货后，净收入、净销售成本和毛利与同期间已过账利润表一致。"
                    : "与同期间已过账利润表存在差异，请核对未过账业务、退货折让及结转记录。");
        }
        return result;
    }

    private static BigDecimal decimal(Object v) { return v instanceof BigDecimal d ? d : new BigDecimal(v.toString()); }
    private static BigDecimal money(BigDecimal v) { return v.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal rate(BigDecimal profit, BigDecimal revenue) {
        return revenue.signum() == 0 ? null : profit.multiply(BigDecimal.valueOf(100)).divide(revenue, 2, RoundingMode.HALF_UP);
    }
    private static BusinessException incomplete(String message) {
        return new BusinessException(409, message).withCode("SHIPMENT_MARGIN_SOURCE_INCOMPLETE")
                    .withHint("请在出货来源明细核对缺失的批次、成本或售价，联系仓库和财务管理员确认原始证据后重新查询");
    }
    private record Key(String product, String unit) {}
    private record Line(Long id, String product, String name, String unit, BigDecimal quantity, BigDecimal price) {}
    private static class Group {
        final String product, name, unit;
        BigDecimal quantity = BigDecimal.ZERO, revenue = BigDecimal.ZERO, rawCost = BigDecimal.ZERO;
        int lines; Set<String> batches = new HashSet<>();
        List<ShipmentSkuMarginDTO.SourceLine> sources = new ArrayList<>();
        Group(String product, String name, String unit) {
            this.product = product; this.name = name == null ? "未命名成品" : name; this.unit = unit;
        }
    }
}
