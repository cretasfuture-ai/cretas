package com.cretas.aims.service.intent;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Deterministic money arithmetic for the controlled purchase-task boundary.
 *
 * <p>Inputs use the database precision of quantity and unit price
 * ({@code NUMERIC(15,4)}).  The purchase line is rounded to two decimals
 * before tax is applied, matching {@code PurchaseOrderItem#getLineAmount()}.
 * Tax is expressed as percentage points, and the only supported rates are
 * 0% and 13%.</p>
 */
public final class PurchaseTaskMoneyContract {
    private static final int INPUT_PRECISION = 15;
    private static final int INPUT_SCALE = 4;
    private static final int TAX_PRECISION = 5;
    private static final int TAX_SCALE = 2;
    private static final int AMOUNT_PRECISION = 18;
    private static final int AMOUNT_SCALE = 2;
    private static final BigDecimal ONE_HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal ZERO_RATE = BigDecimal.ZERO.setScale(TAX_SCALE);
    private static final BigDecimal THIRTEEN_RATE = new BigDecimal("13.00");

    private PurchaseTaskMoneyContract() {
    }

    /**
     * Calculates one purchase line's net, tax, and gross amounts.
     *
     * @throws IllegalArgumentException if an input is null, outside its
     *         database precision, non-positive, or uses an unsupported rate
     */
    public static Amounts calculate(BigDecimal quantity, BigDecimal unitPrice, BigDecimal taxRate) {
        BigDecimal q = input(quantity, "quantity");
        BigDecimal price = input(unitPrice, "unitPrice");
        BigDecimal rate = tax(taxRate);

        BigDecimal net = amount(q.multiply(price).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP), "net");
        BigDecimal multiplier = BigDecimal.ONE.add(rate.divide(ONE_HUNDRED, TAX_SCALE, RoundingMode.UNNECESSARY));
        BigDecimal gross = amount(net.multiply(multiplier).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP), "gross");
        BigDecimal taxAmount = amount(gross.subtract(net).setScale(AMOUNT_SCALE, RoundingMode.HALF_UP), "tax");
        return new Amounts(net, taxAmount, gross);
    }

    public record Amounts(BigDecimal net, BigDecimal tax, BigDecimal gross) {
        public Amounts {
            Objects.requireNonNull(net, "net");
            Objects.requireNonNull(tax, "tax");
            Objects.requireNonNull(gross, "gross");
        }
    }

    private static BigDecimal input(BigDecimal value, String name) {
        if (value == null) {
            throw invalid(name + " is required");
        }
        BigDecimal normalized;
        try {
            normalized = value.setScale(INPUT_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw invalid(name + " has more than " + INPUT_SCALE + " decimal places");
        }
        if (normalized.signum() <= 0) {
            throw invalid(name + " must be positive");
        }
        if (normalized.precision() - normalized.scale() > INPUT_PRECISION - INPUT_SCALE) {
            throw invalid(name + " exceeds NUMERIC(" + INPUT_PRECISION + "," + INPUT_SCALE + ")");
        }
        return normalized;
    }

    private static BigDecimal tax(BigDecimal value) {
        if (value == null) {
            throw invalid("taxRate is required");
        }
        BigDecimal normalized;
        try {
            normalized = value.setScale(TAX_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw invalid("taxRate has more than " + TAX_SCALE + " decimal places");
        }
        if (normalized.precision() - normalized.scale() > TAX_PRECISION - TAX_SCALE) {
            throw invalid("taxRate exceeds NUMERIC(" + TAX_PRECISION + "," + TAX_SCALE + ")");
        }
        if (normalized.compareTo(ZERO_RATE) != 0 && normalized.compareTo(THIRTEEN_RATE) != 0) {
            throw invalid("taxRate must be exactly 0 or 13 percentage points");
        }
        return normalized;
    }

    private static BigDecimal amount(BigDecimal value, String name) {
        if (value.precision() - value.scale() > AMOUNT_PRECISION - AMOUNT_SCALE) {
            throw invalid(name + " exceeds NUMERIC(" + AMOUNT_PRECISION + "," + AMOUNT_SCALE + ")");
        }
        return value;
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
