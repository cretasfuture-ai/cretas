package com.cretas.aims.service.reports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归: sales_order_items.discount_rate 是「百分数」(SalesOrderItem#getLineAmount 里 ÷100),
 * 毛利查询以前把它当小数写成 (1 - discount_rate) —— 折扣 5 会算出负收入。
 *
 * <p>这里用 H2 (PostgreSQL 模式) 真的执行 {@link OperatingReportGrossMarginQuery#SQL},
 * 不是断言 SQL 文本。折扣 0 的对照行证明: 折扣为 0 时数值与修复前一致。
 */
class OperatingReportGrossMarginDiscountPercentTest {

    private JdbcTemplate jdbc;
    private OperatingReportGrossMarginQuery query;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:gm_discount_" + System.nanoTime()
                        + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sales_orders (id VARCHAR(40) PRIMARY KEY, factory_id VARCHAR(40),"
                + " order_date DATE, status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE sales_order_items (sales_order_id VARCHAR(40), product_type_id VARCHAR(40),"
                + " product_name VARCHAR(80), unit VARCHAR(10), quantity DECIMAL(15,4), unit_price DECIMAL(15,4),"
                + " discount_rate DECIMAL(5,2), cost_unit_price DECIMAL(15,4), deleted_at TIMESTAMP)");
        jdbc.update("INSERT INTO sales_orders VALUES ('o1','F1',DATE '2026-09-15','CONFIRMED',NULL)");
        query = new OperatingReportGrossMarginQuery(jdbc);
    }

    private void line(String product, String discount, String cost) {
        jdbc.update("INSERT INTO sales_order_items VALUES ('o1',?,?,'kg',2,100,?,?,NULL)",
                product, product, discount == null ? null : new BigDecimal(discount),
                cost == null ? null : new BigDecimal(cost));
    }

    private void lineWith(String product, String qty, String price, String cost) {
        jdbc.update("INSERT INTO sales_order_items VALUES ('o1',?,?,'kg',?,?,0,?,NULL)",
                product, product, new BigDecimal(qty), price == null ? null : new BigDecimal(price),
                cost == null ? null : new BigDecimal(cost));
    }

    @Test
    void nullPriceLineIsExcludedFromRevenueAndCostAndCounted() {
        lineWith("MIX", "2", "100", "60");
        lineWith("MIX", "5", null, "60");   // 旧 SQL: 收入 0 但成本 300 照算 → 毛利 -110%
        Map<String, Object> r = row("MIX");
        assertNum("200", r.get("revenue"), "revenue excludes the unpriced line");
        assertNum("200", r.get("costedRevenue"), "costedRevenue");
        assertNum("120", r.get("cost"), "cost excludes the unpriced line's 5*60");
        assertNum("80", r.get("grossProfit"), "grossProfit");
        assertNum("0.4", r.get("marginRate"), "marginRate (80/200)");
        assertNum("2", r.get("lineCount"), "lineCount stays the total");
        assertNum("1", r.get("costedLineCount"), "costedLineCount counts priced+costed only");
        assertNum("1", r.get("missingPriceLineCount"), "missingPriceLineCount");
    }

    @Test
    void onlyNullPriceLinesGiveNoMarginAndNoCost() {
        lineWith("NOPRICE", "2", null, "60");
        Map<String, Object> r = row("NOPRICE");
        assertNum("1", r.get("missingPriceLineCount"), "missingPriceLineCount");
        assertNum("0", r.get("costedLineCount"), "costedLineCount");
        assertTrue(r.get("cost") == null && r.get("grossProfit") == null && r.get("marginRate") == null,
                "no price -> no cost/profit/margin, got " + r);
    }

    @Test
    void zeroPriceIsAPriceNotAMissingPriceControl() {
        lineWith("FREE", "2", "0", "60");
        Map<String, Object> r = row("FREE");
        assertNum("0", r.get("missingPriceLineCount"), "price 0 is not missing");
        assertNum("1", r.get("costedLineCount"), "costed");
        assertNum("120", r.get("cost"), "cost kept for a priced (free) line");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> row(String productTypeId) {
        Map<String, Object> payload = query.fetch("F1", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30));
        List<Map<String, Object>> rank = (List<Map<String, Object>>) payload.get("rank");
        return rank.stream().filter(r -> productTypeId.equals(r.get("productTypeId"))).findFirst()
                .orElseThrow(() -> new AssertionError("product row missing: " + productTypeId));
    }

    private static void assertNum(String expected, Object actual, String what) {
        assertTrue(actual instanceof BigDecimal || actual instanceof Number, what + " is not numeric: " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.toString())),
                what + " expected " + expected + " but was " + actual);
    }

    @Test
    void discountFivePercentTakesFivePercentOffRevenue() {
        line("DISC", "5", "60");
        Map<String, Object> r = row("DISC");
        assertNum("190", r.get("revenue"), "revenue (2*100*0.95)");
        assertNum("190", r.get("costedRevenue"), "costedRevenue");
        assertNum("120", r.get("cost"), "cost (2*60)");
        assertNum("70", r.get("grossProfit"), "grossProfit");
    }

    @Test
    void discountZeroControlIsUnchangedFromTheFractionEra() {
        line("PLAIN", "0", "60");
        line("NULLDISC", null, "60");
        assertNum("200", row("PLAIN").get("revenue"), "revenue with discount 0");
        assertNum("200", row("NULLDISC").get("revenue"), "revenue with NULL discount");
    }

    @Test
    void zeroCostLinesAreStillCostedButCountedSoTheyCanBeSurfaced() {
        line("ZERO", "0", "0");
        line("ZERO", "0", "50");
        Map<String, Object> r = row("ZERO");
        assertNum("2", r.get("costedLineCount"), "costedLineCount keeps counting cost=0 as costed");
        assertNum("1", r.get("zeroCostLineCount"), "zeroCostLineCount");
    }
}
