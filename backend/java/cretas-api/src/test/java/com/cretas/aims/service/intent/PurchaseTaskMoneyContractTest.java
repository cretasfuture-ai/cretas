package com.cretas.aims.service.intent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class PurchaseTaskMoneyContractTest {
    @Test
    void calculatesZeroTaxAndKeepsPurchaseOrderLineRounding() {
        PurchaseTaskMoneyContract.Amounts amounts = PurchaseTaskMoneyContract.calculate(
                new BigDecimal("2.3456"), new BigDecimal("10.0050"), BigDecimal.ZERO);

        assertAll(
                () -> assertEquals(new BigDecimal("23.47"), amounts.net()),
                () -> assertEquals(new BigDecimal("0.00"), amounts.tax()),
                () -> assertEquals(new BigDecimal("23.47"), amounts.gross()));
    }

    @Test
    void calculatesThirteenPercentFromRoundedNetWithHalfUpBoundary() {
        PurchaseTaskMoneyContract.Amounts amounts = PurchaseTaskMoneyContract.calculate(
                new BigDecimal("1.0000"), new BigDecimal("10.0050"), new BigDecimal("13.00"));

        assertAll(
                () -> assertEquals(new BigDecimal("10.01"), amounts.net()),
                () -> assertEquals(new BigDecimal("1.30"), amounts.tax()),
                () -> assertEquals(new BigDecimal("11.31"), amounts.gross()));
    }

    @Test
    void acceptsInputAndAmountPrecisionBoundaries() {
        PurchaseTaskMoneyContract.Amounts amounts = PurchaseTaskMoneyContract.calculate(
                new BigDecimal("99999999999.9999"), BigDecimal.ONE, BigDecimal.ZERO);

        assertEquals(new BigDecimal("100000000000.00"), amounts.net());
        assertEquals(new BigDecimal("100000000000.00"), amounts.gross());
    }

    @Test
    void rejectsNullZeroNegativeUnsupportedAndOverflowValues() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(null, BigDecimal.ONE, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(new BigDecimal("-1"), BigDecimal.ONE, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal("9"))),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(new BigDecimal("1.00001"), BigDecimal.ONE, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(new BigDecimal("100000000000.0000"), BigDecimal.ONE, BigDecimal.ZERO)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> PurchaseTaskMoneyContract.calculate(new BigDecimal("99999999999.9999"), new BigDecimal("99999999999.9999"), BigDecimal.ZERO)));
    }
}
