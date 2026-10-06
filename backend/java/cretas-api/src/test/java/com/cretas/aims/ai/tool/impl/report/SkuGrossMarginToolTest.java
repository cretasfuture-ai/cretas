package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolRbacEnforcer;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.ToolEmbeddingRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.impl.PermissionServiceImpl;
import com.cretas.aims.service.impl.ToolRouterServiceImpl;
import com.cretas.aims.testutil.PermissionRepositoryFixtures;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.bom.CostVarianceService;
import com.cretas.aims.service.bom.StandardCostService;
import com.cretas.aims.service.reports.OperatingReportGrossMarginQuery;
import com.cretas.aims.service.yield.ThreePriceComparisonService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 跑在真实工具入口 {@code execute(ToolCall, context)} 上 (JSON 进 JSON 出)。
 * 口径 1 的数字由真实的 {@link OperatingReportGrossMarginQuery#payload} 算出 (只桩掉 JDBC 那一层行数据),
 * 口径 2 由真实的 {@link ThreePriceComparisonService#catalogMarginFor} 算出 (只桩掉仓库)。
 * 折扣用例则用 H2 真的跑那条 SQL。
 */
class SkuGrossMarginToolTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

    private OperatingReportGrossMarginQuery realizedQuery;
    private ProductTypeRepository productTypeRepository;
    private ProductionBatchRepository batchRepository;
    private SkuGrossMarginTool tool;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ProductType> catalogTypes = new ArrayList<>();
    private final List<Map<String, Object>> salesRows = new ArrayList<>();

    @BeforeEach
    void setUp() {
        realizedQuery = mock(OperatingReportGrossMarginQuery.class);
        productTypeRepository = mock(ProductTypeRepository.class);
        batchRepository = mock(ProductionBatchRepository.class);
        // fetch() 返回的是真实 payload() 对「SQL 行」的加工结果
        when(realizedQuery.fetch(eq("F1"), any(), any()))
                .thenAnswer(inv -> OperatingReportGrossMarginQuery.payload(new ArrayList<>(salesRows), false));
        when(productTypeRepository.findByFactoryIdAndIsActiveTrue("F1")).thenReturn(catalogTypes);
        tool = newTool(realizedQuery);
    }

    private SkuGrossMarginTool newTool(OperatingReportGrossMarginQuery query) {
        SkuGrossMarginTool t = new SkuGrossMarginTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ThreePriceComparisonService catalog = new ThreePriceComparisonService(
                productTypeRepository, batchRepository, mock(StandardCostService.class), mock(CostVarianceService.class));
        ReflectionTestUtils.setField(t, "realizedQuery", query);
        ReflectionTestUtils.setField(t, "catalogService", catalog);
        ReflectionTestUtils.setField(t, "productTypeRepository", productTypeRepository);
        ReflectionTestUtils.setField(t, "objectMapper", mapper);
        return t;
    }

    // ---- fixtures ----

    private ProductType type(String id, String name, String price) {
        ProductType t = new ProductType();
        t.setId(id);
        t.setName(name);
        t.setUnitPrice(price == null ? null : new BigDecimal(price));
        catalogTypes.add(t);
        return t;
    }

    private void pricedBatch(String productTypeId, String unitCost) {
        ProductionBatch b = new ProductionBatch();
        b.setBatchNumber("B-" + productTypeId);
        b.setUnitCost(new BigDecimal(unitCost));
        when(batchRepository.findRecentPricedBatches(eq("F1"), eq(productTypeId), any())).thenReturn(List.of(b));
    }

    /** 一行「销售毛利 SQL」的结果 (列名与 OperatingReportGrossMarginQuery.SQL 一致)。 */
    private void sales(String id, String name, int lines, int costedLines, int zeroCostLines,
                       String revenue, String costedRevenue, String cost) {
        sales(id, name, lines, costedLines, zeroCostLines, 0, revenue, costedRevenue, cost);
    }

    private void sales(String id, String name, int lines, int costedLines, int zeroCostLines, int missingPriceLines,
                       String revenue, String costedRevenue, String cost) {
        Map<String, Object> row = new HashMap<>();
        row.put("missing_price_line_count", missingPriceLines);
        row.put("product_type_id", id);
        row.put("product_name", name);
        row.put("unit", "kg");
        row.put("total_qty", new BigDecimal("10"));
        row.put("order_count", 1);
        row.put("line_count", lines);
        row.put("costed_line_count", costedLines);
        row.put("zero_cost_line_count", zeroCostLines);
        row.put("revenue", new BigDecimal(revenue));
        row.put("costed_revenue", costedRevenue == null ? null : new BigDecimal(costedRevenue));
        row.put("cost", cost == null ? null : new BigDecimal(cost));
        salesRows.add(row);
    }

    private JsonNode run(String argsJson) throws Exception {
        String out = tool.execute(ToolCall.of("t1", "report_sku_gross_margin", argsJson),
                Map.of("factoryId", "F1", "userId", 1L));
        return mapper.readTree(out);
    }

    private static JsonNode item(JsonNode result, String productName) {
        for (JsonNode i : result.get("data").get("data").get("items")) {
            if (productName.equals(i.get("productName").asText())) return i;
        }
        throw new AssertionError("item not found: " + productName + " in " + result);
    }

    private static String message(JsonNode result) {
        return result.get("data").get("message").asText();
    }

    private static void assertDecimal(String expected, JsonNode actual, String what) {
        assertTrue(actual != null && actual.isNumber(), what + " is not a number: " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo(actual.decimalValue()),
                what + " expected " + expected + " but was " + actual);
    }

    // ---- 两个口径都算得出 ----

    @Test
    void bothDefinitionsComputedAndLabelledSeparately() throws Exception {
        type("A", "牛肉酱", "100");
        pricedBatch("A", "60");
        sales("A", "牛肉酱", 2, 2, 0, "190", "190", "120");

        JsonNode result = run("{\"productName\":\"牛肉\"}");
        assertTrue(result.get("success").asBoolean(), result.toString());
        JsonNode a = item(result, "牛肉酱");

        assertEquals("OK", a.get("realized").get("status").asText());
        assertDecimal("36.84", a.get("realized").get("marginRate"), "realized.marginRate");   // 70 / 190
        assertDecimal("190.00", a.get("realized").get("revenue"), "realized.revenue");
        assertDecimal("120.00", a.get("realized").get("cost"), "realized.cost");
        assertDecimal("100.00", a.get("realized").get("costCoverage"), "realized.costCoverage");
        assertEquals("OK", a.get("catalog").get("status").asText());
        assertDecimal("40.00", a.get("catalog").get("marginRate"), "catalog.marginRate");     // (100-60)/100
        assertDecimal("100.00", a.get("catalog").get("price"), "catalog.price");
        assertDecimal("60.00", a.get("catalog").get("unitCost"), "catalog.unitCost");

        // 页面读的顶层 marginRate = 实际销售毛利, 不是目录价那个 (阴性对照: 两个数不同)
        assertDecimal("36.84", a.get("marginRate"), "top-level marginRate");
        assertEquals("realized", a.get("marginRateBasis").asText());
        assertNotEquals(0, a.get("marginRate").decimalValue().compareTo(a.get("catalog").get("marginRate").decimalValue()));

        String msg = message(result);
        assertTrue(msg.contains("实际销售毛利：36.84%"), msg);
        assertTrue(msg.contains("目录价理论毛利：40.00%"), msg);
        assertTrue(msg.contains("2026-09-07 至 2026-10-06"), "默认期间必须写进答案: " + msg);
        assertEquals("2026-09-07", result.get("data").get("data").get("realizedPeriod").get("startDate").asText());
        verify(realizedQuery).fetch("F1", LocalDate.of(2026, 9, 7), TODAY);
    }

    @Test
    void partialCostCoverageIsReportedNotHidden() throws Exception {
        type("P", "火锅底料", "50");
        sales("P", "火锅底料", 4, 2, 0, "2000", "1000", "900");
        JsonNode r = item(run("{\"productName\":\"火锅\"}"), "火锅底料").get("realized");
        assertDecimal("10.00", r.get("marginRate"), "marginRate on costed lines only");      // (1000-900)/1000
        assertDecimal("50.00", r.get("costCoverage"), "costCoverage");                       // 1000/2000
        assertDecimal("1000.00", r.get("revenue"), "revenue = costed-line revenue");
        assertDecimal("2000.00", r.get("totalRevenue"), "totalRevenue");
    }

    // ---- 只有一个口径 ----

    @Test
    void onlyRealized_catalogSaysWhichSideIsMissing() throws Exception {
        type("B", "无目录价产品", null);
        sales("B", "无目录价产品", 1, 1, 0, "200", "200", "100");
        type("C", "有价无批次", "80");
        sales("C", "有价无批次", 1, 1, 0, "160", "160", "80");

        JsonNode result = run("{}");
        JsonNode b = item(result, "无目录价产品");
        assertEquals("OK", b.get("realized").get("status").asText());
        assertEquals("NO_PRICE_AND_COST", b.get("catalog").get("status").asText());   // 没批次 + 没价
        assertTrue(b.get("catalog").get("marginRate").isNull());
        assertTrue(b.get("catalog").get("price").isNull());

        JsonNode c = item(result, "有价无批次");
        assertEquals("NO_BATCH_COST", c.get("catalog").get("status").asText());
        assertTrue(c.get("catalog").get("marginRate").isNull());
        assertDecimal("80.00", c.get("catalog").get("price"), "price is real, shown");
        assertTrue(message(result).contains("没有已算出单位成本的批次"), message(result));
        assertTrue(message(result).contains("没有目录价，也没有已算出单位成本的批次"), message(result));
    }

    @Test
    void onlyCatalog_noSalesSaysSoAndGivesNoNumber() throws Exception {
        type("D", "只有目录价", "100");
        pricedBatch("D", "70");

        JsonNode result = run("{\"productName\":\"只有目录价\"}");
        JsonNode d = item(result, "只有目录价");
        assertEquals("NO_SALES", d.get("realized").get("status").asText());
        assertEquals("所选期间没有销售记录", d.get("realized").get("reason").asText());
        assertTrue(d.get("realized").get("marginRate").isNull());
        assertTrue(d.get("marginRate").isNull(), "页面读的 marginRate 必须是 null, 不是 0");
        assertTrue(d.get("realized").get("revenue").isNull());
        assertTrue(d.get("realized").get("cost").isNull());
        assertDecimal("30.00", d.get("catalog").get("marginRate"), "catalog still computed");
        String msg = message(result);
        assertTrue(msg.contains("实际销售毛利：所选期间没有销售记录"), msg);
        assertTrue(msg.contains("目录价理论毛利：30.00%"), msg);
    }

    @Test
    void neither_bothSaySoAndNoPercentAppears() throws Exception {
        type("E", "什么都没有", null);

        JsonNode result = run("{\"productName\":\"什么都没有\"}");
        JsonNode e = item(result, "什么都没有");
        assertEquals("NO_SALES", e.get("realized").get("status").asText());
        assertEquals("NO_PRICE_AND_COST", e.get("catalog").get("status").asText());
        assertTrue(e.get("marginRate").isNull());
        assertTrue(e.get("catalog").get("marginRate").isNull());
        assertFalse(message(result).contains("%"), "没有任何可算的数, 文案里不能出现百分号: " + message(result));
    }

    @Test
    void salesWithoutAnyCostBackfillGivesNoMarginAndNoRevenueBase() throws Exception {
        type("F", "没回填成本", "100");
        sales("F", "没回填成本", 3, 0, 0, "600", null, null);

        JsonNode r = item(run("{\"productName\":\"没回填\"}"), "没回填成本").get("realized");
        assertEquals("NO_COST", r.get("status").asText());
        assertTrue(r.get("marginRate").isNull());
        assertTrue(r.get("cost").isNull());
        assertTrue(r.get("revenue").isNull(), "没有已回填成本的明细 → 毛利率分母不存在, 不是 0");
        assertDecimal("600.00", r.get("totalRevenue"), "all-line revenue is real");
        assertDecimal("0.00", r.get("costCoverage"), "coverage really is 0%");
        assertTrue(r.get("reason").asText().contains("没有回填成本"));
    }

    // ---- cost = 0 的提示 ----

    @Test
    void zeroCostLinesAreSurfacedButStillCounted_withControl() throws Exception {
        type("Z", "含零成本", "100");
        sales("Z", "含零成本", 2, 2, 1, "400", "400", "150");
        type("N", "无零成本", "100");
        sales("N", "无零成本", 2, 2, 0, "400", "400", "150");

        JsonNode result = run("{}");
        JsonNode z = item(result, "含零成本").get("realized");
        assertEquals("OK", z.get("status").asText());
        assertEquals("其中 1 条明细成本为 0，可能是成本没回填", z.get("note").asText());
        assertDecimal("62.50", z.get("marginRate"), "cost=0 line still counted as costed (query semantics unchanged)");

        JsonNode n = item(result, "无零成本").get("realized");
        assertTrue(n.get("note").isNull(), "对照: 没有零成本明细时不出提示");
        assertEquals(1, countOccurrences(message(result), "成本为 0"));
    }

    // ---- 价格权限门: 真实 enforcer + 真实 PermissionServiceImpl + 真实工具 (经 ToolRouter 执行入口) ----

    private Object runViaRouter(String role) throws Exception {
        PermissionServiceImpl permissions = new PermissionServiceImpl();
        PermissionRepositoryFixtures.installSuccessfulEmpty(permissions);
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(77L);
        user.setFactoryId("F1");
        user.setRoleCode(role);
        when(users.findById(77L)).thenReturn(Optional.of(user));
        ToolRbacGuard guard = new ToolRbacGuard();
        ReflectionTestUtils.setField(guard, "userRepository", users);
        ReflectionTestUtils.setField(guard, "permissionService", permissions);
        ToolRbacEnforcer enforcer = new ToolRbacEnforcer();
        ReflectionTestUtils.setField(enforcer, "rbacGuard", guard);

        ToolRegistry registry = mock(ToolRegistry.class);
        when(registry.getExecutor("report_sku_gross_margin")).thenReturn(Optional.of(tool));
        ToolRouterServiceImpl router = new ToolRouterServiceImpl();
        ReflectionTestUtils.setField(router, "toolRegistry", registry);
        ReflectionTestUtils.setField(router, "objectMapper", mapper);
        ReflectionTestUtils.setField(router, "toolEmbeddingRepository", mock(ToolEmbeddingRepository.class));
        ReflectionTestUtils.setField(router, "writeGuardService", new WriteGuardService());
        ReflectionTestUtils.setField(router, "toolRbacEnforcer", enforcer);

        Map<String, Object> context = new HashMap<>();
        context.put("factoryId", "F1");
        context.put("userId", 77L);
        context.put("userRole", role);
        java.lang.reflect.Method m = ToolRouterServiceImpl.class.getDeclaredMethod(
                "executeSingleTool", String.class, Map.class);
        m.setAccessible(true);
        try {
            return m.invoke(router, "report_sku_gross_margin", context);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    @Test
    void toolDeclaresThePriceViewPermissionTheAppUses() {
        assertTrue(tool.requiresPermission());
        assertEquals(Set.of("procurement:price:view"), tool.getRequiredPermissions());
    }

    @Test
    void rolesWithoutPriceViewGetADenialAndNoNumbers() throws Exception {
        type("A", "牛肉酱", "100");
        pricedBatch("A", "60");
        sales("A", "牛肉酱", 2, 2, 0, "190", "190", "120");
        for (String role : List.of("operator", "warehouse_manager", "quality_inspector")) {
            IllegalStateException denied = assertThrows(IllegalStateException.class, () -> runViaRouter(role), role);
            assertTrue(denied.getMessage().contains("没有权限"), denied.getMessage());
            assertFalse(denied.getMessage().contains("36.84"), "denial must carry no numbers");
        }
        // 拒绝发生在查库之前: 没有价格权限的角色连 SQL 都没跑
        verify(realizedQuery, org.mockito.Mockito.never()).fetch(any(), any(), any());
    }

    @Test
    void rolesWithPriceViewGetTheNumbers_control() throws Exception {
        type("A", "牛肉酱", "100");
        pricedBatch("A", "60");
        sales("A", "牛肉酱", 2, 2, 0, "190", "190", "120");
        for (String role : List.of("finance_manager", "factory_super_admin", "sales_manager")) {
            Object result = runViaRouter(role);
            JsonNode json = (JsonNode) result;
            assertTrue(json.get("success").asBoolean(), role + ": " + json);
            assertDecimal("36.84", item(json, "牛肉酱").get("realized").get("marginRate"), role + " realized.marginRate");
        }
    }

    // ---- 没有售价的明细: 不计入, 并明说 ----

    @Test
    void missingPriceLinesAreNamedInTheAnswerWithControl() throws Exception {
        type("M", "有缺价明细", "100");
        sales("M", "有缺价明细", 3, 2, 0, 1, "200", "200", "120");
        type("N", "无缺价明细", "100");
        sales("N", "无缺价明细", 2, 2, 0, 0, "200", "200", "120");

        JsonNode result = run("{}");
        JsonNode m = item(result, "有缺价明细").get("realized");
        assertEquals("OK", m.get("status").asText());
        assertEquals("其中 1 条明细没有售价，未计入", m.get("note").asText());
        assertEquals(1, m.get("missingPriceLineCount").asInt());
        assertTrue(message(result).contains("其中 1 条明细没有售价，未计入"), message(result));

        JsonNode n = item(result, "无缺价明细").get("realized");
        assertTrue(n.get("note").isNull(), "对照: 没有缺价明细时不出提示");
        assertEquals(0, n.get("missingPriceLineCount").asInt());
        assertEquals(1, countOccurrences(message(result), "没有售价"), message(result));
    }

    @Test
    void allLinesWithoutPriceGivesNoRevenueAndNoMarginNotZero() throws Exception {
        type("P", "全无售价", "100");
        sales("P", "全无售价", 2, 0, 0, 2, "0", null, null);

        JsonNode result = run("{\"productName\":\"全无售价\"}");
        JsonNode r = item(result, "全无售价").get("realized");
        assertEquals("NO_PRICE", r.get("status").asText());
        assertTrue(r.get("marginRate").isNull());
        assertTrue(r.get("revenue").isNull());
        assertTrue(r.get("totalRevenue").isNull(), "没有售价 → 收入是「不知道」, 不是 0");
        assertTrue(r.get("note").isNull(), "全部缺价时原因已在 reason 里, 不重复");
        assertTrue(message(result).contains("都没有售价"), message(result));
    }

    @Test
    void nullPriceLineFlowsThroughTheRealQueryIntoTheAnswer() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sku_tool_np_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sales_orders (id VARCHAR(40) PRIMARY KEY, factory_id VARCHAR(40),"
                + " order_date DATE, status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE sales_order_items (sales_order_id VARCHAR(40), product_type_id VARCHAR(40),"
                + " product_name VARCHAR(80), unit VARCHAR(10), quantity DECIMAL(15,4), unit_price DECIMAL(15,4),"
                + " discount_rate DECIMAL(5,2), cost_unit_price DECIMAL(15,4), deleted_at TIMESTAMP)");
        jdbc.update("INSERT INTO sales_orders VALUES ('o1','F1',DATE '2026-09-20','CONFIRMED',NULL)");
        jdbc.update("INSERT INTO sales_order_items VALUES ('o1','MIX','混合产品','kg',2,100,0,60,NULL)");
        jdbc.update("INSERT INTO sales_order_items VALUES ('o1','MIX','混合产品','kg',5,NULL,0,60,NULL)");
        SkuGrossMarginTool real = newTool(new OperatingReportGrossMarginQuery(jdbc));

        JsonNode result = mapper.readTree(real.execute(
                ToolCall.of("t3", "report_sku_gross_margin", "{}"), Map.of("factoryId", "F1", "userId", 1L)));
        JsonNode r = item(result, "混合产品").get("realized");
        assertDecimal("40.00", r.get("marginRate"), "(200-120)/200, 不是 (200-420)/200");
        assertDecimal("200.00", r.get("revenue"), "revenue");
        assertDecimal("120.00", r.get("cost"), "cost");
        assertTrue(message(result).contains("其中 1 条明细没有售价，未计入"), message(result));
    }

    // ---- 折扣 (百分数) 经由真实 SQL ----

    @Test
    void discountPercentFlowsThroughTheRealQuery_withZeroControl() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:sku_tool_" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE sales_orders (id VARCHAR(40) PRIMARY KEY, factory_id VARCHAR(40),"
                + " order_date DATE, status VARCHAR(20), deleted_at TIMESTAMP)");
        jdbc.execute("CREATE TABLE sales_order_items (sales_order_id VARCHAR(40), product_type_id VARCHAR(40),"
                + " product_name VARCHAR(80), unit VARCHAR(10), quantity DECIMAL(15,4), unit_price DECIMAL(15,4),"
                + " discount_rate DECIMAL(5,2), cost_unit_price DECIMAL(15,4), deleted_at TIMESTAMP)");
        jdbc.update("INSERT INTO sales_orders VALUES ('o1','F1',DATE '2026-09-20','CONFIRMED',NULL)");
        jdbc.update("INSERT INTO sales_order_items VALUES ('o1','DISC','打折产品','kg',2,100,5,60,NULL)");
        jdbc.update("INSERT INTO sales_order_items VALUES ('o1','FULL','不打折产品','kg',2,100,0,60,NULL)");
        SkuGrossMarginTool real = newTool(new OperatingReportGrossMarginQuery(jdbc));

        JsonNode result = mapper.readTree(real.execute(
                ToolCall.of("t2", "report_sku_gross_margin", "{}"), Map.of("factoryId", "F1", "userId", 1L)));
        JsonNode disc = item(result, "打折产品").get("realized");
        assertDecimal("190.00", disc.get("revenue"), "5% off 2*100 → 190");
        assertDecimal("36.84", disc.get("marginRate"), "(190-120)/190");
        JsonNode full = item(result, "不打折产品").get("realized");
        assertDecimal("200.00", full.get("revenue"), "discount 0 control");
        assertDecimal("40.00", full.get("marginRate"), "(200-120)/200");
    }

    // ---- 期间 / 筛选 / 入参 ----

    @Test
    void explicitPeriodIsPassedToTheQueryAndEchoedInTheAnswer() throws Exception {
        type("A", "牛肉酱", "100");
        sales("A", "牛肉酱", 1, 1, 0, "100", "100", "50");
        JsonNode result = run("{\"productName\":\"牛肉\",\"startDate\":\"2026-08-01\",\"endDate\":\"2026-08-31\"}");
        verify(realizedQuery).fetch("F1", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        assertTrue(message(result).contains("2026-08-01 至 2026-08-31"), message(result));
        assertFalse(message(result).contains("默认最近"), message(result));
        assertFalse(result.get("data").get("data").get("realizedPeriod").get("defaultPeriod").asBoolean());
    }

    @Test
    void productNameFiltersAndUnknownNameIsSaidPlainly() throws Exception {
        type("A", "牛肉酱", "100");
        sales("A", "牛肉酱", 1, 1, 0, "100", "100", "50");
        type("Q", "其他产品", "10");
        sales("Q", "其他产品", 1, 1, 0, "100", "100", "50");

        JsonNode only = run("{\"productName\":\"牛肉\"}");
        assertEquals(1, only.get("data").get("data").get("items").size());

        JsonNode none = run("{\"productName\":\"不存在的东西\"}");
        assertTrue(none.get("success").asBoolean());
        assertEquals(0, none.get("data").get("data").get("items").size());
        assertTrue(message(none).contains("没有找到名称含「不存在的东西」"), message(none));
    }

    @Test
    void badOrReversedDatesAreRejectedNotGuessed() throws Exception {
        assertFalse(run("{\"startDate\":\"昨天\"}").get("success").asBoolean());
        assertFalse(run("{\"startDate\":\"2026-09-30\",\"endDate\":\"2026-09-01\"}").get("success").asBoolean());
    }

    private static int countOccurrences(String text, String needle) {
        int n = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + needle.length())) n++;
        return n;
    }
}
