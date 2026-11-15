package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 真实入口 execute(ToolCall, context); 数字手算; 口径同成本分析页 (按结束时间半开区间)。 */
class ReportLaborCostShareToolContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);
    private final ObjectMapper mapper = new ObjectMapper();
    private ReportLaborCostShareTool tool;
    private final List<ProductionBatch> batches = new ArrayList<>();
    private ProductionBatchRepository repo;

    @AfterEach
    void priceContextMustNotLeak() {
        assertFalse(AiBiToolTestSupport.clearPriceContextReportingLeak(), "测试后价格可见性上下文仍是隐藏态 = 泄漏");
    }

    @BeforeEach
    void setUp() {
        repo = mock(ProductionBatchRepository.class);
        tool = new ReportLaborCostShareTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ReflectionTestUtils.setField(tool, "batchRepository", repo);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(true));
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        when(repo.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(eq("F1"), eq(LocalDate.of(2026, 11, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 11, 15).atStartOfDay()))).thenReturn(batches);
    }

    private ProductionBatch batch(String product, String labor, String total, boolean incomplete, ProductionBatchStatus st) {
        ProductionBatch b = new ProductionBatch();
        b.setProductName(product);
        b.setProductTypeId("PT-" + product);
        b.setStatus(st);
        b.setLaborCost(new BigDecimal(labor));
        b.setMaterialCost(new BigDecimal(total).subtract(new BigDecimal(labor)));
        b.setEquipmentCost(BigDecimal.ZERO);
        b.setOtherCost(BigDecimal.ZERO);
        b.setTotalCost(new BigDecimal(total));
        b.setActualQuantity(BigDecimal.TEN);
        b.setUnit("kg");
        b.setCostIncomplete(incomplete);
        return b;
    }

    private JsonNode run(String args) throws Exception {
        return mapper.readTree(tool.execute(ToolCall.of("t1", "report_labor_cost_share", args), Map.of("factoryId", "F1", "userId", 1L)));
    }

    @Test
    void sharesMatchHandComputed_andIncompleteAndUnfinishedAreExcluded() throws Exception {
        ProductionBatch withPeople = batch("卤牛肉", "100", "800", false, ProductionBatchStatus.COMPLETED);
        withPeople.setSupervisorName("张三");
        withPeople.setSupervisorId(7L);
        batches.add(withPeople);
        batches.add(batch("卤牛肉", "50", "200", false, ProductionBatchStatus.COMPLETED));
        batches.add(batch("酱鸭", "20", "100", false, ProductionBatchStatus.COMPLETED));
        batches.add(batch("酱鸭", "999", "999", true, ProductionBatchStatus.COMPLETED));      // 未算全
        batches.add(batch("酱鸭", "999", "999", false, ProductionBatchStatus.IN_PROGRESS));   // 进行中
        JsonNode r = run("{}");
        JsonNode d = r.get("data").get("data");
        // 人工 170 / 总 1100 = 15.5%
        assertEquals(170.0, d.get("laborCost").asDouble(), 0.001);
        assertEquals(1100.0, d.get("totalCost").asDouble(), 0.001);
        assertEquals(15.5, d.get("laborSharePct").asDouble(), 0.001);
        assertEquals(1, d.get("costIncompleteBatchCount").asInt());
        assertEquals(20.0, d.get("products").get(0).get("laborSharePct").asDouble(), 0.001); // 酱鸭 20/100 占比最高排第一
        assertEquals(15.0, d.get("products").get(1).get("laborSharePct").asDouble(), 0.001); // 牛肉 150/1000
        assertEquals(1, d.get("charts").size());
        assertTrue(r.get("data").get("message").asText().contains("15.5%"));
        // 只出金额汇总: 不含人员字段
        assertFalse(r.toString().contains("张三") || r.toString().contains("supervisor"), "人员字段不得出现在结果里");
    }

    @Test
    void noCostedBatches_saysCannotComputeNotZero() throws Exception {
        batches.add(batch("酱鸭", "1", "1", true, ProductionBatchStatus.COMPLETED));
        JsonNode r = run("{}");
        assertFalse(r.get("data").get("data").has("laborSharePct"));
        assertTrue(r.get("data").get("message").asText().contains("算不出"));
    }

    @Test
    void callerWithoutCostView_getsNoAmountsNoShareNoChart() throws Exception {
        ReflectionTestUtils.setField(tool, "priceView", AiBiToolTestSupport.priceView(false));
        batches.add(batch("卤牛肉", "100", "800", false, ProductionBatchStatus.COMPLETED));
        JsonNode r = run("{}");
        JsonNode d = r.get("data").get("data");
        assertFalse(d.has("laborCost"));
        assertFalse(d.has("laborSharePct"));
        assertFalse(d.has("charts"));
        String msg = r.get("data").get("message").asText();
        assertTrue(msg.contains("按当前账号权限不显示"));
        assertFalse(msg.contains("800") || msg.contains("12.5"));
    }

    @Test
    void roleWithoutCostPermissionIsDeniedByEnforcer_analyticsHolderAllowed() {
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
        assertEquals(Set.of("analytics:read_write"), tool.getRequiredPermissions());
        assertFalse(AiBiToolTestSupport.enforcerAllows(tool, Set.of("production:read", "production:read_write")));
        assertTrue(AiBiToolTestSupport.enforcerAllows(tool, Set.of("analytics:read_write")));
    }

    @Test
    void rowsAreOrderedByLaborShare_soASmallHighShareProductIsNotCutOff() throws Exception {
        // 10 个大产品占比 5%, 1 个小产品占比 80%: 取前 8 个若按总成本排会漏掉它
        for (int i = 0; i < 10; i++) {
            batches.add(batch("大产品" + i, "50", "1000", false, ProductionBatchStatus.COMPLETED));
        }
        batches.add(batch("小产品", "80", "100", false, ProductionBatchStatus.COMPLETED));
        JsonNode products = run("{}").get("data").get("data").get("products");
        assertEquals("小产品", products.get(0).get("productName").asText());
        assertEquals(80.0, products.get(0).get("laborSharePct").asDouble(), 0.001);
    }

    @Test
    void protectedProductBatchesAreDroppedFromTotalsRowsAndText() throws Exception {
        ProcessSecretLlmCostScope scope = mock(ProcessSecretLlmCostScope.class);
        when(scope.forCurrentCaller("F1")).thenReturn(new ProcessSecretLlmCostScope.Excluded(
                Set.of("PT-酱鸭"), Set.of(), Set.of("PT-酱鸭"), Map.of(), Set.of()));
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        batches.add(batch("卤牛肉", "100", "800", false, ProductionBatchStatus.COMPLETED));
        batches.add(batch("酱鸭", "900", "1000", false, ProductionBatchStatus.COMPLETED));
        JsonNode r = run("{}");
        JsonNode d = r.get("data").get("data");
        assertEquals(12.5, d.get("laborSharePct").asDouble(), 0.001);   // 只剩卤牛肉 100/800
        assertEquals(1, d.get("withheldBatchCount").asInt());
        assertFalse(r.toString().contains("酱鸭"));
    }
}
