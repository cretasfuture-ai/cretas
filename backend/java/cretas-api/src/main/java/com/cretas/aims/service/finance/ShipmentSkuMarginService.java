package com.cretas.aims.service.finance;

import com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO;
import com.cretas.aims.dto.finance.report.ShippedGrossProfitDTO;
import com.cretas.aims.dto.finance.report.ShippedRevenueBasis;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
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
    private final FactorySettingsRepository factorySettings;
    private final ShipmentNetRevenueService netRevenue;

    static final String CONFIRMED_LINE_FILTER = """
        COALESCE(r.record_role,'LEGACY')<>'MASTER' AND r.status IN ('SHIPPED','DELIVERED')
          AND i.deleted_at IS NULL AND r.deleted_at IS NULL AND i.delivered_quantity>0
        """;
    // Delivery unitPrice is the saved untaxed shipment price (same basis as netShippedValue).
    static final String LINES_SQL = """
        SELECT i.id, i.product_type_id, COALESCE(NULLIF(i.product_name,''),p.name) AS product_name,
               i.unit, i.delivered_quantity, i.unit_price
        FROM sales_delivery_items i JOIN sales_delivery_records r ON r.id=i.delivery_record_id
        LEFT JOIN product_types p ON p.id=i.product_type_id AND p.factory_id=r.factory_id AND p.deleted_at IS NULL
        WHERE r.factory_id=? AND r.delivery_date BETWEEN ? AND ? AND
        """ + CONFIRMED_LINE_FILTER + """
        ORDER BY i.id
        """;

    @Transactional(readOnly = true)
    public ShipmentSkuMarginDTO generate(String factoryId, LocalDate start, LocalDate end) {
        validatePeriod(factoryId, start, end);
        // Serialize against shipment, cost correction and closing, as the existing cost/indicator queries do.
        ProductionCostConsistency.lockFactory(entityManager, factoryId);
        Core core = margin(factoryId, start, end, shippedLines(factoryId, start, end));
        ShipmentSkuMarginDTO result = core.result();
        if (start.getDayOfMonth() == 1 && end.getDayOfMonth() == end.lengthOfMonth()) {
            reconcile(result, factoryId, start, end, core.revenue(), core.rawCost());
        }
        return result;
    }

    /** 经营分析系统毛利取本期发货实际出库成本，净额口径另扣本期销售退货回库成本。 */
    @Transactional(readOnly = true)
    public ShippedGrossProfitDTO summarize(String factoryId, LocalDate start, LocalDate end) {
        validatePeriod(factoryId, start, end);
        ProductionCostConsistency.lockFactory(entityManager, factoryId);
        ShippedRevenueBasis basis = ShippedRevenueBasisSettings.read(
                factoryId, factorySettings.findProductionSettingsByFactoryId(factoryId));
        List<Line> lines = shippedLines(factoryId, start, end);
        boolean net = basis == ShippedRevenueBasis.NET;
        ShippedGrossProfitDTO.ShippedGrossProfitDTOBuilder out = ShippedGrossProfitDTO.builder()
                .periodStart(start).periodEnd(end).shipmentLineCount(lines.size())
                .basis(basis).revenueBasis(basis.description())
                .costBasis(net ? ShippedGrossProfitDTO.NET_COST_BASIS : ShippedGrossProfitDTO.COST_BASIS);
        if (lines.isEmpty()) {
            return out.status(ShippedGrossProfitDTO.NO_SHIPMENT)
                    .unavailableReason("缺少发货确认数据：所选期间没有已确认发货（已发货 / 已签收）的发运单，毛利无法计算；未发货的订单金额不计入收入")
                    .build();
        }
        long missingPrice = lines.stream().filter(l -> l.price == null).count();
        if (missingPrice > 0) {
            return out.status(ShippedGrossProfitDTO.PRICE_MISSING)
                    .unavailableReason("有 " + missingPrice + " 行已发货明细缺少售价，发货收入无法计算；请在发运单补齐售价")
                    .build();
        }
        BigDecimal revenue = lines.stream().map(ShipmentSkuMarginService::lineRevenue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (net) {
            try {
                var adjustments = netRevenue.adjustments(factoryId, start, end);
                revenue = revenue.subtract(adjustments.discount()).subtract(adjustments.returns());
            } catch (BusinessException e) {
                return out.status(ShippedGrossProfitDTO.ADJUSTMENT_INCOMPLETE)
                        .unavailableReason(e.getMessage()).build();
            }
        }
        revenue = money(revenue);
        out.revenue(revenue);
        BigDecimal cost;
        try {
            cost = margin(factoryId, start, end, lines).rawCost();
        } catch (BusinessException e) {
            return out.status(ShippedGrossProfitDTO.COST_INCOMPLETE)
                    .unavailableReason("发货收入已算出，但出库批次成本不完整，毛利无法计算：" + e.getMessage())
                    .build();
        }
        if (net) {
            try {
                cost = cost.subtract(salesReturnCosts.returnedCost(factoryId, start, end));
            } catch (BusinessException e) {
                return out.status(ShippedGrossProfitDTO.COST_INCOMPLETE)
                        .unavailableReason("发货净收入已算出，但退货回库批次成本不完整，毛利无法计算：" + e.getMessage())
                        .build();
            }
        }
        cost = money(cost);
        BigDecimal profit = revenue.subtract(cost);
        return out.status(ShippedGrossProfitDTO.OK).cost(cost).grossProfit(profit)
                .marginRate(rate(profit, revenue)).build();
    }

    private static void validatePeriod(String factoryId, LocalDate start, LocalDate end) {
        if (factoryId == null || factoryId.isBlank() || start == null || end == null || start.isAfter(end)) {
            throw new IllegalArgumentException("请选择正确的工厂和统计期间");
        }
    }

    private List<Line> shippedLines(String factoryId, LocalDate start, LocalDate end) {
        return jdbc.query(LINES_SQL, (rs, n) -> new Line(rs.getLong("id"),
                rs.getString("product_type_id"), rs.getString("product_name"), rs.getString("unit"),
                rs.getBigDecimal("delivered_quantity"), rs.getBigDecimal("unit_price")),
                factoryId, java.sql.Date.valueOf(start), java.sql.Date.valueOf(end));
    }

    /** 一行发货收入 = 实发数量 × 发货不含税单价, 分位取整。generate 与 summarize 共用这一处。 */
    private static BigDecimal lineRevenue(Line line) {
        return lineRevenue(line.quantity, line.price);
    }

    static BigDecimal lineRevenue(BigDecimal quantity, BigDecimal price) {
        return money(quantity.multiply(price));
    }

    private record Core(ShipmentSkuMarginDTO result, BigDecimal revenue, BigDecimal rawCost) {}

    private Core margin(String factoryId, LocalDate start, LocalDate end, List<Line> lines) {
        List<Object[]> movements = shipments.findShippedMovementsForLedger(factoryId, start, end);
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
            g.revenue = g.revenue.add(lineRevenue(line));
            g.rawCost = g.rawCost.add(costByLine.get(line.id));
            g.lines++;
            g.batches.addAll(batchesByLine.get(line.id));
            g.sources.add(ShipmentSkuMarginDTO.SourceLine.builder().shipmentLineId(line.id)
                    .deliveryNumber(numberByLine.get(line.id)).deliveryDate(dateByLine.get(line.id))
                    .quantity(line.quantity).unit(line.unit).revenue(lineRevenue(line))
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
        return new Core(result, revenue, rawCost);
    }

    private void reconcile(ShipmentSkuMarginDTO result, String factoryId, LocalDate start, LocalDate end,
                           BigDecimal revenue, BigDecimal rawCost) {
        {
            var financial = incomeStatements.generate(factoryId, start.getYear(), start.getMonthValue(), end.getYear(), end.getMonthValue());
            result.setFinancialStatement(financial);
            if (financial.getPendingDraftVoucherCount() > 0) {
                result.setReconciliationStatus("PENDING_VOUCHERS");
                result.setReconciliationMessage("仍有凭证待过账，请完成过账后核对。");
                return;
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
