package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewPage;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewRow;
import com.cretas.aims.service.inventory.TradeCaliberSettings.CustomerAverageMode;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 跨客户的客户价目表 (G7): 一次列出全厂所有客户的专属售价 (SELLING_PRICE + customer_id),
 * 并带上最近成交价 / 平均成交价 (来自 customer_price_history) 与产品规格。只读, 全部带 factory_id。
 */
@Service
@RequiredArgsConstructor
public class CustomerPriceOverviewService {

    private final TradeCaliberSettings tradeCaliberSettings;

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public PageResponse<CustomerPriceOverviewRow> list(String factoryId, String keyword, int page, int size,
                                                       List<Long> owners) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        CustomerAverageMode mode = tradeCaliberSettings.customerAverageMode(factoryId);
        if (owners != null && owners.isEmpty()) {
            return new CustomerPriceOverviewPage(List.of(), p, s, 0L, mode);
        }
        String kw = keyword == null || keyword.isBlank() ? null : "%" + keyword.trim().toLowerCase(Locale.ROOT) + "%";

        String from = " FROM PriceListItem i JOIN i.priceList pl JOIN Customer c ON c.id = pl.customerId "
                + "LEFT JOIN ProductType pt ON pt.id = i.productTypeId AND pt.factoryId = :f "
                + "WHERE pl.factoryId = :f AND pl.customerId IS NOT NULL AND pl.priceType = 'SELLING_PRICE' "
                + "AND i.productTypeId IS NOT NULL AND c.factoryId = :f"
                + (owners == null ? "" : " AND c.createdBy IN :owners")
                + (kw == null ? "" : " AND (LOWER(c.name) LIKE :kw OR LOWER(i.itemName) LIKE :kw "
                + "OR LOWER(pt.code) LIKE :kw)");

        var countQ = em.createQuery("SELECT COUNT(i)" + from, Long.class).setParameter("f", factoryId);
        var rowsQ = em.createQuery("SELECT pl.customerId, c.name, i.id, pl.id, pl.name, i.productTypeId, pt.code, "
                + "i.itemName, pt.specification, i.unit, i.standardPrice, pl.effectiveFrom, pl.effectiveTo, pl, pt.unit"
                + from + " ORDER BY c.name ASC, i.itemName ASC, i.id ASC", Object[].class).setParameter("f", factoryId);
        if (kw != null) {
            countQ.setParameter("kw", kw);
            rowsQ.setParameter("kw", kw);
        }
        if (owners != null) {
            countQ.setParameter("owners", owners);
            rowsQ.setParameter("owners", owners);
        }
        long total = countQ.getSingleResult();
        List<Object[]> rows = rowsQ.setFirstResult((p - 1) * s).setMaxResults(s).getResultList();

        Set<String> customerIds = new HashSet<>();
        Set<String> productIds = new HashSet<>();
        for (Object[] r : rows) {
            customerIds.add((String) r[0]);
            productIds.add((String) r[5]);
        }
        Map<String, List<Trade>> history = historyByPair(factoryId, customerIds, productIds, owners);

        List<CustomerPriceOverviewRow> out = new ArrayList<>();
        for (Object[] r : rows) {
            com.cretas.aims.entity.inventory.PriceList pl = (com.cretas.aims.entity.inventory.PriceList) r[13];
            CustomerPriceOverviewRow v = new CustomerPriceOverviewRow();
            v.setCustomerId((String) r[0]);
            v.setCustomerName((String) r[1]);
            v.setItemId((Long) r[2]);
            v.setPriceListId((String) r[3]);
            v.setPriceListName((String) r[4]);
            v.setProductTypeId((String) r[5]);
            v.setProductCode((String) r[6]);
            v.setProductName((String) r[7]);
            v.setSpecification((String) r[8]);
            v.setUnit((String) r[9]);
            v.setStandardPrice((BigDecimal) r[10]);
            v.setEffectiveFrom((LocalDate) r[11]);
            v.setEffectiveTo((LocalDate) r[12]);
            v.setEffective(pl.isCurrentlyEffective());
            List<Trade> trades = history.get(r[0] + "|" + r[5]);
            if (trades != null) {
                v.setTradedCount(trades.size());
                v.setLatestTradedPrice(trades.getFirst().unitPrice());
                v.setLatestTradedDate(trades.getFirst().orderDate());
                if (mode == CustomerAverageMode.SIMPLE) {
                    BigDecimal prices = trades.stream().map(Trade::unitPrice).reduce(BigDecimal.ZERO, BigDecimal::add);
                    v.setAverageTradedPrice(prices.divide(BigDecimal.valueOf(trades.size()), 4, RoundingMode.HALF_UP));
                } else {
                    String baseUnit = (String) r[14];
                    applyWeightedAverage(v, trades, baseUnit == null || baseUnit.isBlank() ? v.getUnit() : baseUnit);
                }
            }
            out.add(v);
        }
        return new CustomerPriceOverviewPage(out, p, s, total, mode);
    }

    private void applyWeightedAverage(CustomerPriceOverviewRow row, List<Trade> trades, String baseUnit) {
        BigDecimal quantity = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        long excluded = 0;
        for (Trade trade : trades) {
            BigDecimal baseQuantity = trade.quantityIn(baseUnit);
            if (baseQuantity == null || baseQuantity.signum() <= 0) {
                excluded++;
                continue;
            }
            quantity = quantity.add(baseQuantity);
            amount = amount.add(trade.unitPrice().multiply(trade.quantity()));
        }
        row.setExcludedTradeCount(excluded);
        if (quantity.signum() > 0) {
            row.setAverageTradedPrice(amount.divide(quantity, 4, RoundingMode.HALF_UP));
            row.setAverageTradedPriceUnit(baseUnit);
        }
    }

    private record Trade(BigDecimal unitPrice, LocalDate orderDate, BigDecimal quantity, String unit,
                         String packagingUnit, String packagingBaseUnit, BigDecimal packagingFactor) {
        BigDecimal quantityIn(String baseUnit) {
            if (unit == null || unit.isBlank() || baseUnit == null || baseUnit.isBlank()) return null;
            if (packagingUnit != null && !FgQuantityUnitConverter.hasPackagingSpec(
                    packagingUnit, packagingBaseUnit, packagingFactor)) return null;
            // Use the sale's packaging snapshot and fixed physical conversions. Current SKU specs
            // and net weight cannot reconstruct a different historical package.
            return FgQuantityUnitConverter.convertWithPackaging(quantity, unit, baseUnit, null,
                    packagingUnit, packagingBaseUnit, packagingFactor, null, null, null);
        }
    }

    /** key = customerId|productTypeId, 成交记录按订单日倒序。 */
    private Map<String, List<Trade>> historyByPair(String factoryId, Set<String> customerIds, Set<String> productIds,
                                                 List<Long> owners) {
        Map<String, List<Trade>> map = new HashMap<>();
        if (customerIds.isEmpty() || productIds.isEmpty()) {
            return map;
        }
        var query = em.createQuery("SELECT h.unitPrice, h.orderDate, h.customerId, h.productTypeId, h.quantity, "
                + "h.quantityUnit, h.packagingUnit, h.packagingBaseUnit, h.packagingFactor "
                + "FROM CustomerPriceHistory h WHERE h.factoryId = :f AND h.customerId IN :cs "
                + "AND h.productTypeId IN :ps AND h.unitPrice IS NOT NULL "
                + (owners == null ? "" : "AND EXISTS (SELECT 1 FROM SalesOrder o WHERE o.id = h.sourceSalesOrderId "
                    + "AND o.factoryId = :f AND o.customerId = h.customerId AND o.createdBy IN :owners) ")
                + "ORDER BY h.orderDate DESC, h.createdAt DESC", Object[].class)
                .setParameter("f", factoryId).setParameter("cs", customerIds).setParameter("ps", productIds);
        if (owners != null) query.setParameter("owners", owners);
        for (Object[] x : query.getResultList()) {
            map.computeIfAbsent(x[2] + "|" + x[3], k -> new ArrayList<>())
                    .add(new Trade((BigDecimal) x[0], (LocalDate) x[1], (BigDecimal) x[4], (String) x[5],
                            (String) x[6], (String) x[7], (BigDecimal) x[8]));
        }
        return map;
    }
}
