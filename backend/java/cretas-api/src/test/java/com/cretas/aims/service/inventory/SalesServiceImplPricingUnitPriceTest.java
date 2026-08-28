package com.cretas.aims.service.inventory;

import com.cretas.aims.service.inventory.impl.SalesServiceImpl;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression test for the 2026-05-30 pricing bug surfaced by the F006 UI E2E.
 *
 * <p>{@code PricingResult.finalPrice} is a LINE TOTAL ({@code unitPriceList × quantity − discounts}).
 * {@code SalesServiceImpl.createSalesOrder} previously assigned it straight to the per-unit
 * {@code resolvedUnitPrice}, so a 276 × ¥50 line was stored as {@code unitPrice = ¥13,800} and
 * {@code totalAmount = ¥3,808,800 (= 276² × 50)}. {@link SalesServiceImpl#lineTotalToUnitPrice}
 * now converts the line total back to per-unit; these cases pin that contract.
 */
class SalesServiceImplPricingUnitPriceTest {

    @Test
    void lineTotal_dividedByQuantity_yieldsPerUnit() {
        // The exact F006 repro: 276 units, ¥50 each → engine line total ¥13,800.
        BigDecimal lineTotal = new BigDecimal("13800");
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                lineTotal, new BigDecimal("276"), new BigDecimal("50"));
        assertEquals(0, new BigDecimal("50.00").compareTo(perUnit),
                "276 × ¥50 line total ¥13,800 must convert back to per-unit ¥50, not stay ¥13,800");
    }

    @Test
    void discountedLineTotal_convertsToDiscountedPerUnit() {
        // 100 units, list ¥50 = ¥5,000 line total; a 10% strategy discount → ¥4,500 final line total.
        // Per-unit after discount = ¥45.00.
        BigDecimal discountedLineTotal = new BigDecimal("4500");
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                discountedLineTotal, new BigDecimal("100"), new BigDecimal("50"));
        assertEquals(0, new BigDecimal("45.00").compareTo(perUnit));
    }

    @Test
    void nonDivisibleLineTotal_roundsHalfUpToFourScale() {
        // 3 units, ¥10.00 line total → 3.3333... → HALF_UP **scale 4** = ¥3.3333.
        //
        // 🔴 2026-08-28: 这条原来断言 ¥3.33（scale 2）。它不是恒真式、也不是变异没生效 ——
        //    它好好地守着一个**现在被判定为缺陷**的行为（形态 C‴：需求变了，不是我改错了）。
        //    scale 2 会把客户端提交的 4 位未税单价悄悄改写，见 lineTotalToUnitPrice 的 javadoc。
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("10.00"), new BigDecimal("3"), new BigDecimal("3"));
        assertEquals(0, new BigDecimal("3.3333").compareTo(perUnit),
                "per-unit 必须保留 4 位 —— 与 sales_order_items.unit_price numeric(15,4) 对齐");
        // 阴性对照: ⛔ 不许回到 2 位。
        assertNotEquals(0, new BigDecimal("3.33").compareTo(perUnit),
                "又被取整回 2 位了 —— 用户录入的含税价会重新被改写");
    }

    /**
     * 生产实测回归：SO-20260828-0001（F006）。
     *
     * <p>用户录入 含税单价 ¥25.00 × 10 盒 @13% ⇒ 应收 ¥250.00。
     * 定价引擎 <b>appliedStrategies=0</b>（什么都没做）、lineTotal 前后都是 221.2390，
     * 却把单价从 22.1239 改写成 22.12，实体再据此反推 ⇒ 应收变成 ¥249.96。
     *
     * <p>⚠️ 这条钉的是<b>行为</b>不是实现：per-unit 换算不许改变引擎给出的行总额。
     */
    @Test
    void noOpPricingMustNotRewriteThePostedUnitPrice() {
        BigDecimal posted = new BigDecimal("22.1239");
        BigDecimal quantity = new BigDecimal("10");
        BigDecimal engineLineTotal = posted.multiply(quantity);   // 221.2390 —— 引擎原样返回

        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(engineLineTotal, quantity, posted);

        assertEquals(0, posted.compareTo(perUnit),
                "一次没有应用任何策略的定价，改写了客户端提交的单价");
        // 判据递归到派生值: 单价对了不算, 派生的行总额也要对(形态 C⁶)。
        assertEquals(0, engineLineTotal.compareTo(perUnit.multiply(quantity)),
                "per-unit 反乘回去与引擎的行总额对不上 —— 差额会落在应收账款上");
    }

    @Test
    void nullLineTotal_returnsFallback() {
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(null, new BigDecimal("10"), fallback));
    }

    @Test
    void zeroQuantity_returnsFallback() {
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("500"), BigDecimal.ZERO, fallback));
    }

    @Test
    void nullQuantity_returnsFallback() {
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("500"), null, fallback));
    }

    @Test
    void zeroLineTotal_freeGoods_convertsToZeroPerUnit() {
        // A 100%-discount promotion: final line total ¥0 → per-unit ¥0.00 (valid, not fallback).
        BigDecimal perUnit = SalesServiceImpl.lineTotalToUnitPrice(
                BigDecimal.ZERO, new BigDecimal("10"), new BigDecimal("50"));
        assertEquals(0, BigDecimal.ZERO.compareTo(perUnit));
    }

    @Test
    void negativeLineTotal_returnsFallback_neverNegativeUnitPrice() {
        // Defensive: a negative line total (should not happen — engine clamps ≥0) must NOT become a
        // negative unit price; fall back to the caller's price instead.
        BigDecimal fallback = new BigDecimal("50");
        assertSame(fallback, SalesServiceImpl.lineTotalToUnitPrice(
                new BigDecimal("-100"), new BigDecimal("10"), fallback));
    }
}
