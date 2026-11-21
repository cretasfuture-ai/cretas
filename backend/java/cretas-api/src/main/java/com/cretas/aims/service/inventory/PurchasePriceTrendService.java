package com.cretas.aims.service.inventory;

import com.cretas.aims.exception.BusinessException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 采购均价趋势 (G8 采购线第 8 项): 按 原料 × 单位 × 月份 的数量加权平均单价, 服务端聚合。
 *
 * <p>口径:
 * <ul>
 *   <li>只算已提交且未取消的采购单 (草稿、已取消不算); 月份取下单日期。</li>
 *   <li>加权均价按计价单位数量加权。缺价的行不参与均价，单独计数。</li>
 *   <li>不同数量单位/计价单位不混算: 分组键含 unit 与 priceUnit。</li>
 *   <li>价格可见性由调用方 (PurchaseController.requirePurchasePrices) 与现有采购价权限配置保持一致。</li>
 * </ul>
 */
@Service
public class PurchasePriceTrendService {

    static final int MAX_MONTHS = 36;

    @PersistenceContext
    private EntityManager em;

    public record Row(String materialTypeId, String materialName, String unit, String priceUnit, String month,
                      BigDecimal totalQuantity, BigDecimal weightedAveragePrice, long orderCount,
                      long lineCount, long missingPriceLines) {
    }

    private record GroupKey(String materialTypeId, String unit, String priceUnit, String month) {
    }

    private static final String PO = "com.cretas.aims.entity.enums.PurchaseOrderStatus.";
    private static final String COMMITTED = "(" + PO + "SUBMITTED," + PO + "WORKFLOW_RUNNING," + PO + "APPROVED,"
            + PO + "PENDING_FINANCE_REVIEW," + PO + "FINANCE_APPROVED," + PO + "PARTIAL_RECEIVED," + PO + "COMPLETED,"
            + PO + "CLOSED)";

    @Transactional(readOnly = true)
    public List<Row> trend(String factoryId, LocalDate from, LocalDate to, String materialTypeId, String supplierId) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new BusinessException(400, "请提供有效的起止日期 (from ≤ to)");
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(from), YearMonth.from(to)) + 1;
        if (months > MAX_MONTHS) {
            throw new BusinessException(400, "时间跨度不能超过 " + MAX_MONTHS + " 个月");
        }
        StringBuilder jpql = new StringBuilder("SELECT o.id, o.orderDate, i.materialTypeId, i.materialName, i.unit, i.priceUnit, "
                + "i.quantity, i.unitPrice, i.quantityToPriceFactor FROM PurchaseOrderItem i JOIN i.purchaseOrder o "
                + "WHERE o.factoryId = :f AND o.status IN " + COMMITTED + " AND o.orderDate >= :from AND o.orderDate <= :to");
        if (materialTypeId != null && !materialTypeId.isBlank()) {
            jpql.append(" AND i.materialTypeId = :m");
        }
        if (supplierId != null && !supplierId.isBlank()) {
            jpql.append(" AND o.supplierId = :s");
        }
        var q = em.createQuery(jpql.toString(), Object[].class)
                .setParameter("f", factoryId).setParameter("from", from).setParameter("to", to);
        if (materialTypeId != null && !materialTypeId.isBlank()) q.setParameter("m", materialTypeId);
        if (supplierId != null && !supplierId.isBlank()) q.setParameter("s", supplierId);

        Map<GroupKey, Acc> groups = new LinkedHashMap<>();
        for (Object[] r : q.getResultList()) {
            String month = YearMonth.from((LocalDate) r[1]).toString();
            GroupKey key = new GroupKey((String) r[2], (String) r[4], (String) r[5], month);
            Acc a = groups.computeIfAbsent(key, ignored -> new Acc((String) r[3]));
            a.orders.add((String) r[0]);
            a.lines++;
            BigDecimal qty = (BigDecimal) r[6];
            BigDecimal price = (BigDecimal) r[7];
            BigDecimal factor = (BigDecimal) r[8];
            if (price == null || qty == null || factor == null) {
                a.missing++;
            } else {
                BigDecimal priceQuantity = qty.multiply(factor);
                a.qty = a.qty.add(qty);
                a.priceQuantity = a.priceQuantity.add(priceQuantity);
                a.amount = a.amount.add(priceQuantity.multiply(price));
            }
        }
        List<Row> out = new ArrayList<>();
        for (var entry : groups.entrySet()) {
            GroupKey key = entry.getKey();
            Acc a = entry.getValue();
            BigDecimal avg = a.priceQuantity.signum() > 0 ? a.amount.divide(a.priceQuantity, 4, RoundingMode.HALF_UP) : null;
            out.add(new Row(key.materialTypeId(), a.materialName, key.unit(), key.priceUnit(), key.month(),
                    a.qty, avg, a.orders.size(), a.lines, a.missing));
        }
        out.sort(Comparator.comparing(Row::materialName, Comparator.nullsLast(String::compareTo))
                .thenComparing(Row::materialTypeId)
                .thenComparing(Row::unit)
                .thenComparing(Row::priceUnit)
                .thenComparing(Row::month));
        return out;
    }

    /** CSV (UTF-8 带 BOM, Excel 直接打开); 没有均价的月份留空而不是 0。 */
    public byte[] exportCsv(List<Row> rows) {
        StringBuilder sb = new StringBuilder("﻿原料,单位,计价单位,月份,数量合计,加权均价,采购单数,明细行数,缺单价行数\r\n");
        for (Row r : rows) {
            sb.append(csv(r.materialName())).append(',').append(csv(r.unit())).append(',').append(csv(r.priceUnit())).append(',')
                    .append(r.month()).append(',').append(r.totalQuantity().stripTrailingZeros().toPlainString()).append(',')
                    .append(r.weightedAveragePrice() == null ? "" : r.weightedAveragePrice().toPlainString()).append(',')
                    .append(r.orderCount()).append(',').append(r.lineCount()).append(',').append(r.missingPriceLines()).append("\r\n");
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String csv(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s; // 防 CSV 公式注入
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static final class Acc {
        final String materialName;
        BigDecimal qty = BigDecimal.ZERO;
        BigDecimal priceQuantity = BigDecimal.ZERO;
        BigDecimal amount = BigDecimal.ZERO;
        long lines;
        long missing;
        final Set<String> orders = new HashSet<>();

        Acc(String materialName) {
            this.materialName = materialName;
        }
    }
}
