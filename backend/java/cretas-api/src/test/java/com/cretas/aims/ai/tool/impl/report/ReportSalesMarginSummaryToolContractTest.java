package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.reports.OperatingReportGrossMarginQuery;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportSalesMarginSummaryToolContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private final ObjectMapper mapper = new ObjectMapper();
    private ReportSalesMarginSummaryTool tool;
    private OperatingReportGrossMarginQuery query;

    @AfterEach
    void priceContextMustNotLeak() {
        assertFalse(AiBiToolTestSupport.clearPriceContextReportingLeak(), "测试后价格可见性上下文仍是隐藏态 = 泄漏");
    }

    @BeforeEach
    void setUp() {
        query = mock(OperatingReportGrossMarginQuery.class);
        tool = new ReportSalesMarginSummaryTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ReflectionTestUtils.setField(tool, "marginQuery", query);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(true));
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
    }

    private static Map<String, Object> row(String id, String name, String revenue, String costedRevenue, String cost) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("product_type_id", id);
        r.put("product_name", name);
        r.put("unit", "kg");
        r.put("total_qty", new BigDecimal("10"));
        r.put("order_count", 1);
        r.put("line_count", 1);
        r.put("costed_line_count", cost == null ? 0 : 1);
        r.put("zero_cost_line_count", 0);
        r.put("missing_price_line_count", 0);
        r.put("revenue", new BigDecimal(revenue));
        r.put("costed_revenue", new BigDecimal(costedRevenue));
        r.put("cost", cost == null ? null : new BigDecimal(cost));
        return r;
    }

    private void stub(List<Map<String, Object>> rows) {
        when(query.fetch(eq("F1"), eq(LocalDate.of(2026, 11, 1)), eq(TODAY)))
                .thenReturn(OperatingReportGrossMarginQuery.payload(rows, false));
    }

    private JsonNode run() throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t1", "report_sales_margin_summary", "{}"), Map.of("factoryId", "F1", "userId", 1L)));
    }

    @Test
    void revenueProfitAndMargin_matchHandComputed_withCoverage() throws Exception {
        // A: 收入1000 成本600 → 毛利400; B: 收入500 成本300 → 200; C: 收入200 无成本 (不进毛利)
        stub(List.of(row("A", "卤牛肉", "1000", "1000", "600"), row("B", "酱鸭", "500", "500", "300"), row("C", "鸭脖", "200", "0", null)));
        JsonNode r = run();
        JsonNode d = r.get("data").get("data");
        assertEquals(1700.0, d.get("revenue").asDouble(), 0.001);
        assertEquals(900.0, d.get("cost").asDouble(), 0.001);
        assertEquals(600.0, d.get("grossProfit").asDouble(), 0.001);   // 1500-900
        assertEquals(40.0, d.get("marginRate").asDouble(), 0.001);     // 600/1500
        assertEquals(88.24, d.get("costCoverage").asDouble(), 0.01);   // 1500/1700
        assertEquals("卤牛肉", d.get("products").get(0).get("productName").asText());
        assertEquals(1, d.get("charts").size());
        assertTrue(r.get("data").get("message").asText().contains("未回填成本的明细没有计入毛利"));
    }

    @Test
    void noCostAtAll_saysCannotComputeMargin() throws Exception {
        stub(List.of(row("C", "鸭脖", "200", "0", null)));
        JsonNode r = run();
        assertEquals(200.0, r.get("data").get("data").get("revenue").asDouble(), 0.001);
        assertFalse(r.get("data").get("data").has("marginRate") && !r.get("data").get("data").get("marginRate").isNull());
        assertTrue(r.get("data").get("message").asText().contains("算不出"));
    }

    @Test
    void noSales_saysNoneNotZero() throws Exception {
        stub(List.of());
        JsonNode r = run();
        assertTrue(r.get("data").get("message").asText().contains("没有可统计的销售记录"));
        assertFalse(r.get("data").get("data").has("revenue"));
    }

    @Test
    void callerWithoutAmountView_getsNothingMonetary() throws Exception {
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(false));
        stub(List.of(row("A", "卤牛肉", "1000", "1000", "600")));
        JsonNode r = run();
        JsonNode d = r.get("data").get("data");
        assertFalse(d.has("revenue") || d.has("grossProfit") || d.has("marginRate") || d.has("charts"));
        assertFalse(r.get("data").get("message").asText().contains("1000"));
        assertTrue(r.get("data").get("message").asText().contains("按当前账号权限不显示"));
    }

    @Test
    void nonFinanceRoleIsDeniedByEnforcer_financeHolderAllowed() {
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertTrue(tool.requiresPermission());
        assertEquals(Set.of("finance:read", "finance:read_write"), tool.getRequiredPermissions());
        assertFalse(AiBiToolTestSupport.enforcerAllows(tool, Set.of("production:read", "analytics:read_write")));
        assertTrue(AiBiToolTestSupport.enforcerAllows(tool, Set.of("finance:read")));
    }

    @Test
    void protectedProductIsRemovedAndTotalsRecomputedOnRemainingRows_countIsRealProtectedOnly() throws Exception {
        ProcessSecretLlmCostScope scope = mock(ProcessSecretLlmCostScope.class);
        // B 是受保护产品; C 是与它同计划联产的普通产品 (withheld 但不是 protected, 不能计入保密产品个数)
        when(scope.forCurrentCaller("F1")).thenReturn(new ProcessSecretLlmCostScope.Excluded(
                Set.of("B"), Set.of(), Set.of("B"), Map.of(), Set.of("C")));
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        stub(List.of(row("A", "卤牛肉", "1000", "1000", "600"), row("B", "酱鸭", "500", "500", "300"), row("C", "鸭脖", "200", "200", "100")));
        JsonNode r = run();
        JsonNode d = r.get("data").get("data");
        assertEquals(1000.0, d.get("revenue").asDouble(), 0.001);       // 只剩 A
        assertEquals(400.0, d.get("grossProfit").asDouble(), 0.001);
        assertEquals(1, d.get("excludedProductCount").asInt());          // 只数 B, 不数联产的 C
        String all = r.toString();
        assertFalse(all.contains("酱鸭") || all.contains("鸭脖"));
        assertTrue(r.get("data").get("message").asText().contains("不含 1 个保密产品"));
    }
}
