package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportBomCostRankToolContractTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private ReportBomCostRankTool tool;
    private BomService bom;
    private ProductTypeRepository types;

    @AfterEach
    void priceContextMustNotLeak() {
        assertFalse(AiBiToolTestSupport.clearPriceContextReportingLeak(), "测试后价格可见性上下文仍是隐藏态 = 泄漏");
    }

    private static ProductType type(String id, String name) {
        ProductType t = new ProductType();
        t.setId(id);
        t.setName(name);
        return t;
    }

    private static BomCostSummaryDTO cost(String id, String total, boolean missing) {
        return cost(id, total, missing, "元/盒", true);
    }

    private static BomCostSummaryDTO cost(String id, String total, boolean missing, String unit, boolean packagingRow) {
        List<BomCostSummaryDTO.MaterialCostItem> items = packagingRow
                ? List.of(BomCostSummaryDTO.MaterialCostItem.builder().materialName("包材").subtotal(new BigDecimal(total)).build())
                : List.of(BomCostSummaryDTO.MaterialCostItem.builder().materialName("原料").build());   // 非归集行 subtotal=null
        return BomCostSummaryDTO.builder().productTypeId(id).totalCost(new BigDecimal(total)).costUnit(unit)
                .materialCosts(items).hasMissingPrice(missing).missingPriceCount(missing ? 2 : 0).build();
    }

    private static ProcessSecretLlmCostScope scopeProtecting(String... ids) {
        ProcessSecretLlmCostScope scope = mock(ProcessSecretLlmCostScope.class);
        java.util.Set<String> set = java.util.Set.of(ids);
        when(scope.forCurrentCaller("F1")).thenReturn(new ProcessSecretLlmCostScope.Excluded(set, java.util.Set.of(), set, Map.of(), java.util.Set.of()));
        return scope;
    }

    @BeforeEach
    void setUp() {
        bom = mock(BomService.class);
        types = mock(ProductTypeRepository.class);
        tool = new ReportBomCostRankTool();
        ReflectionTestUtils.setField(tool, "bomService", bom);
        ReflectionTestUtils.setField(tool, "productTypeRepository", types);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(true));
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        when(types.findByFactoryIdAndIsActiveTrue("F1")).thenReturn(List.of(
                type("P1", "卤牛肉礼盒"), type("P2", "酱鸭"), type("P3", "卤牛肉散装"), type("P4", "无BOM产品")));
        when(bom.getProductTypesWithBom("F1")).thenReturn(List.of("P1", "P2", "P3"));
        when(bom.calculateProductCost("F1", "P1")).thenReturn(cost("P1", "12.50", false));
        when(bom.calculateProductCost("F1", "P2")).thenReturn(cost("P2", "3.20", true));
        when(bom.calculateProductCost("F1", "P3")).thenReturn(cost("P3", "7.00", false));
    }

    private JsonNode run(String args) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t1", "report_bom_cost_rank", args), Map.of("factoryId", "F1", "userId", 1L)));
    }

    @Test
    void ranksHighestFirst_onlyProductsWithBom_andFlagsMissingPrice() throws Exception {
        JsonNode r = run("{}");
        JsonNode rows = r.get("data").get("data").get("rows");
        assertEquals(3, rows.size());
        assertEquals("卤牛肉礼盒", rows.get(0).get("productName").asText());
        assertEquals("P1", rows.get(0).get("productTypeId").asText());
        assertEquals(12.5, rows.get(0).get("totalCost").asDouble(), 0.001);
        assertEquals("酱鸭", rows.get(2).get("productName").asText());
        assertTrue(rows.get(2).get("hasMissingPrice").asBoolean());
        assertEquals(1, r.get("data").get("data").get("charts").size());
        String msg = r.get("data").get("message").asText();
        assertTrue(msg.contains("缺单价") && msg.contains("只归集包材"));
    }

    @Test
    void productNameResolvesToTypeIds_onlyMatchingRanked() throws Exception {
        JsonNode rows = run("{\"productName\":\"牛肉\"}").get("data").get("data").get("rows");
        assertEquals(2, rows.size());
        assertEquals("P1", rows.get(0).get("productTypeId").asText());
        assertEquals("P3", rows.get(1).get("productTypeId").asText());
    }

    @Test
    void unknownProduct_saysNotFound() throws Exception {
        JsonNode r = run("{\"productName\":\"不存在\"}");
        assertTrue(r.get("data").get("message").asText().contains("没有找到"));
    }

    @Test
    void callerWithoutCostView_getsNoAmountsNoChart_andBomIsNotEvenQueried() throws Exception {
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(false));
        JsonNode r = run("{}");
        assertFalse(r.get("data").get("data").has("rows"));
        assertFalse(r.get("data").get("data").has("charts"));
        String msg = r.get("data").get("message").asText();
        assertTrue(msg.contains("按当前账号权限不显示"));
        assertFalse(msg.contains("12.5"));
    }

    @Test
    void roleWithoutCostPermissionIsDeniedByEnforcer_analyticsHolderAllowed() {
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertEquals(Set.of("analytics:read_write"), tool.getRequiredPermissions());
        assertFalse(AiBiToolTestSupport.enforcerAllows(tool, Set.of("production:read")));
        assertTrue(AiBiToolTestSupport.enforcerAllows(tool, Set.of("analytics:read_write")));
    }

    @Test
    void productWithNoAggregatableRowIsNotRankedAsZero_andIsCounted() throws Exception {
        // P2 的 BOM 没有包材行: calculateProductCost 给 0 不是 null, 以前会以 0.00 排第一 (升序) 并进图
        when(bom.calculateProductCost("F1", "P2")).thenReturn(cost("P2", "0.00", false, "元/盒", false));
        JsonNode r = run("{\"order\":\"lowest\"}");
        JsonNode rows = r.get("data").get("data").get("rows");
        assertEquals(2, rows.size());
        assertEquals("P3", rows.get(0).get("productTypeId").asText());   // 7.00 最低, 不是 0.00 的酱鸭
        assertEquals(1, r.get("data").get("data").get("noPackagingRowProductCount").asInt());
        assertTrue(r.get("data").get("message").asText().contains("没有可归集成本的行"));
        assertFalse(r.get("data").get("data").get("charts").toString().contains("酱鸭"));
    }

    @Test
    void differentUnitsAreNotMixed_mainUnitRanked_othersSaid_axisNamedAfterUnit() throws Exception {
        when(bom.calculateProductCost("F1", "P2")).thenReturn(cost("P2", "99.00", false, "元/kg", true));
        JsonNode r = run("{}");
        JsonNode d = r.get("data").get("data");
        assertEquals(2, d.get("rows").size());                           // 两个 元/盒 产品
        assertEquals("元/盒", d.get("costUnit").asText());
        assertEquals(1, d.get("otherUnitProductCounts").get("元/kg").asInt());
        assertEquals("元/盒", d.get("charts").get(0).get("option").get("yAxis").get("name").asText());
        String msg = r.get("data").get("message").asText();
        assertTrue(msg.contains("元/kg") && msg.contains("不能一起排"));
        assertFalse(msg.contains("99.00"));
    }

    @Test
    void protectedProductIsNotInRowsChartOrText_andOnlyRealProtectedCountIsReported() throws Exception {
        ReflectionTestUtils.setField(tool, "llmCostScope", scopeProtecting("P1"));
        JsonNode r = run("{}");
        JsonNode d = r.get("data").get("data");
        assertEquals(2, d.get("rows").size());
        assertFalse(d.toString().contains("卤牛肉礼盒") || d.toString().contains("12.5"));
        String msg = r.get("data").get("message").asText();
        assertFalse(msg.contains("卤牛肉礼盒") || msg.contains("12.5"));
        assertTrue(msg.contains("不含 1 个保密产品"), msg);
        assertEquals(1, d.get("withheldProductCount").asInt());
        org.mockito.Mockito.verify(bom, org.mockito.Mockito.never()).calculateProductCost("F1", "P1");
    }

    @Test
    void callerWithoutCostView_neverQueriesBomAtAll() throws Exception {
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(false));
        run("{}");
        org.mockito.Mockito.verify(bom, org.mockito.Mockito.never()).calculateProductCost(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
