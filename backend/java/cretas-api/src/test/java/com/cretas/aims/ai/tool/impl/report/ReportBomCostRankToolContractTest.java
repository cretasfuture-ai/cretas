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
        return BomCostSummaryDTO.builder().productTypeId(id).totalCost(new BigDecimal(total)).costUnit("元/盒")
                .hasMissingPrice(missing).missingPriceCount(missing ? 2 : 0).build();
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
}
