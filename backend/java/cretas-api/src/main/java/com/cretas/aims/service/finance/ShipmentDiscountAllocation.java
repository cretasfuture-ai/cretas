package com.cretas.aims.service.finance;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Cumulative untaxed allocation shared by shipment receivables and shipped revenue. */
public final class ShipmentDiscountAllocation {
    private ShipmentDiscountAllocation() {}

    public static BigDecimal cumulative(BigDecimal discount, BigDecimal shippedValue, BigDecimal orderValue) {
        if (shippedValue == null || shippedValue.signum() <= 0) return BigDecimal.ZERO;
        BigDecimal ratio = shippedValue.divide(orderValue, 10, RoundingMode.HALF_UP).min(BigDecimal.ONE);
        return discount.multiply(ratio).setScale(2, RoundingMode.HALF_UP);
    }
}
