package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.inventory.CustomerPriceOverviewRow;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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
public class CustomerPriceOverviewService {

    @PersistenceContext
    private EntityManager em;

    @Transactional(readOnly = true)
    public PageResponse<CustomerPriceOverviewRow> list(String factoryId, String keyword, int page, int size,
                                                       List<Long> owners) {
        int p = Math.max(page, 1);
        int s = Math.min(Math.max(size, 1), 200);
        if (owners != null && owners.isEmpty()) {
            return PageResponse.of(List.of(), p, s, 0L);
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
                + "i.itemName, pt.specification, i.unit, i.standardPrice, pl.effectiveFrom, pl.effectiveTo, pl"
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
        Map<String, List<Object[]>> history = historyByPair(factoryId, customerIds, productIds, owners);

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
            List<Object[]> h = history.get(r[0] + "|" + r[5]);
            if (h == null || h.isEmpty()) {
                v.setTradedCount(0);
            } else {
                BigDecimal sum = BigDecimal.ZERO;
                for (Object[] x : h) {
                    sum = sum.add((BigDecimal) x[0]);
                }
                v.setTradedCount(h.size());
                v.setAverageTradedPrice(sum.divide(BigDecimal.valueOf(h.size()), 4, RoundingMode.HALF_UP));
                v.setLatestTradedPrice((BigDecimal) h.get(0)[0]); // 已按订单日倒序
                v.setLatestTradedDate((LocalDate) h.get(0)[1]);
            }
            out.add(v);
        }
        return PageResponse.of(out, p, s, total);
    }

    /** key = customerId|productTypeId; 每个 value 是 [unitPrice, orderDate] 列表, 订单日倒序。 */
    private Map<String, List<Object[]>> historyByPair(String factoryId, Set<String> customerIds, Set<String> productIds,
                                                     List<Long> owners) {
        Map<String, List<Object[]>> map = new HashMap<>();
        if (customerIds.isEmpty() || productIds.isEmpty()) {
            return map;
        }
        var query = em.createQuery("SELECT h.unitPrice, h.orderDate, h.customerId, h.productTypeId "
                + "FROM CustomerPriceHistory h WHERE h.factoryId = :f AND h.customerId IN :cs "
                + "AND h.productTypeId IN :ps AND h.unitPrice IS NOT NULL "
                + (owners == null ? "" : "AND EXISTS (SELECT 1 FROM SalesOrder o WHERE o.id = h.sourceSalesOrderId "
                    + "AND o.factoryId = :f AND o.customerId = h.customerId AND o.createdBy IN :owners) ")
                + "ORDER BY h.orderDate DESC, h.createdAt DESC", Object[].class)
                .setParameter("f", factoryId).setParameter("cs", customerIds).setParameter("ps", productIds);
        if (owners != null) query.setParameter("owners", owners);
        for (Object[] x : query.getResultList()) {
            map.computeIfAbsent(x[2] + "|" + x[3], k -> new ArrayList<>()).add(new Object[]{x[0], x[1]});
        }
        return map;
    }
}
