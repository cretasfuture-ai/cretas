package com.cretas.aims.repository.inventory;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.inventory.FgQuantityUnitConverter;
import java.math.BigDecimal;
import java.util.*;

/** One shared cost basis for COGS and the quantity/amount ledger. Never use selling prices. */
final class ShipmentCostRows {
    private ShipmentCostRows() {}

    static List<Object[]> movements(List<Object[]> source) {
        List<Object[]> result = new ArrayList<>();
        Map<Object, BigDecimal> quantities = new HashMap<>();
        Map<Object, BigDecimal> expected = new HashMap<>();
        for (Object[] r : source) {
            BigDecimal delivered = decimal(r[8]);
            if (delivered == null || delivered.signum() <= 0) continue;
            boolean actual = r[18] != null;
            if (!actual) throw incomplete(); // Neither allocation intent nor a legacy first-batch FK proves physical quantities.
            BigDecimal nativeQty = actual ? decimal(r[1]) : convert(delivered, r, false);
            if (nativeQty == null || nativeQty.signum() <= 0) throw incomplete();
            BigDecimal inDeliveryUnit = actual ? convert(nativeQty, r, true) : delivered;
            if (inDeliveryUnit == null || (actual && !Objects.equals(r[20], r[10]))) throw incomplete();
            quantities.merge(r[7], inDeliveryUnit, BigDecimal::add);
            expected.put(r[7], delivered);
            // A missing/deleted/cross-tenant batch remains visible as unknown cost, never zero.
            Object date = r[0] instanceof java.sql.Date d ? d.toLocalDate() : r[0];
            result.add(new Object[]{date, nativeQty, r[2], r[3], r[4], r[5], r[6], r[10], r[7]});
        }
        for (var e : expected.entrySet()) {
            if (quantities.get(e.getKey()).subtract(e.getValue()).abs().compareTo(new BigDecimal("0.01")) > 0) {
                throw incomplete();
            }
        }
        return result;
    }

    private static BigDecimal convert(BigDecimal qty, Object[] r, boolean toDelivery) {
        String from = (String) r[toDelivery ? 10 : 9], to = (String) r[toDelivery ? 9 : 10];
        // Missing legacy batch: keep the quantity visible with an unknown cost.
        if (!toDelivery && to == null) return qty;
        return FgQuantityUnitConverter.convertWithPackaging(qty, from, to, decimal(r[11]),
                (String) r[toDelivery ? 15 : 12], (String) r[toDelivery ? 16 : 13], decimal(r[toDelivery ? 17 : 14]),
                (String) r[toDelivery ? 12 : 15], (String) r[toDelivery ? 13 : 16], decimal(r[toDelivery ? 14 : 17]));
    }

    static Object[] aggregate(List<Object[]> rows, boolean missing) {
        BigDecimal amount = BigDecimal.ZERO, qty = BigDecimal.ZERO;
        Set<Object> items = new HashSet<>();
        for (Object[] r : rows) {
            if ((r[2] == null) != missing) continue;
            BigDecimal q = decimal(r[1]);
            qty = qty.add(q); items.add(r[8]);
            if (!missing) amount = amount.add(q.multiply(decimal(r[2])));
        }
        return missing ? new Object[]{qty, (long)items.size()} : new Object[]{amount, qty, (long)items.size()};
    }

    private static BigDecimal decimal(Object value) {
        return value == null ? null : value instanceof BigDecimal d ? d : new BigDecimal(value.toString());
    }
    private static BusinessException incomplete() {
        return new BusinessException(409, "出货批次扣库记录或单位不完整，本次未结转，请核对实际出货批次和数量")
                .withCode("SHIPMENT_COST_SOURCE_INCOMPLETE")
                .withHint("请联系仓库和财务管理员核对发货详情、扣库记录和单位，确认原始证据完整后重新结转");
    }
}
