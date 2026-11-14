package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.security.PriceFieldResponseAdvice;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 跑在真实工具入口 execute(ToolCall, context) 上; 数字手算对照。 */
class ReportCostQueryToolContractTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14); // 周六
    private final ObjectMapper mapper = new ObjectMapper();
    private ProductionBatchRepository repo;
    private ReportCostQueryTool tool;
    private PriceFieldResponseAdvice priceView;
    private final List<ProductionBatch> thisMonth = new ArrayList<>();
    private final List<ProductionBatch> lastPeriod = new ArrayList<>();

    @BeforeEach
    void setUp() {
        repo = mock(ProductionBatchRepository.class);
        priceView = mock(PriceFieldResponseAdvice.class);
        when(priceView.showsKey(any(), any())).thenReturn(true);
        tool = new ReportCostQueryTool() {
            @Override
            protected LocalDate today() {
                return TODAY;
            }
        };
        ReflectionTestUtils.setField(tool, "batchRepository", repo);
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        ReflectionTestUtils.setField(tool, "priceView", priceView);
        ReflectionTestUtils.setField(tool, "llmCostScope", ProcessSecretLlmCostScope.standalone());
        // 本期 11-01..11-14 -> thisMonth; 上一个等长周期 10-18..10-31 -> lastPeriod
        when(repo.findBatchesInDateRange(eq("F1"), eq(LocalDate.of(2026, 11, 1).atStartOfDay()), any())).thenReturn(thisMonth);
        when(repo.findBatchesInDateRange(eq("F1"), eq(LocalDate.of(2026, 10, 18).atStartOfDay()), any())).thenReturn(lastPeriod);
    }

    private ProductionBatch batch(String product, String mat, String lab, String eq, String oth, String good, String unit,
                                  ProductionBatchStatus status, boolean incomplete) {
        ProductionBatch b = new ProductionBatch();
        b.setProductName(product);
        b.setProductTypeId("PT-" + product);
        b.setStatus(status);
        b.setMaterialCost(new BigDecimal(mat));
        b.setLaborCost(new BigDecimal(lab));
        b.setEquipmentCost(new BigDecimal(eq));
        b.setOtherCost(new BigDecimal(oth));
        b.setTotalCost(new BigDecimal(mat).add(new BigDecimal(lab)).add(new BigDecimal(eq)).add(new BigDecimal(oth)));
        b.setGoodQuantity(new BigDecimal(good));
        b.setUnit(unit);
        b.setCostIncomplete(incomplete);
        b.setStartTime(LocalDateTime.of(2026, 11, 5, 8, 0));
        return b;
    }

    private JsonNode run(String args) throws Exception {
        String out = tool.execute(ToolCall.of("t1", "report_cost_query", args), Map.of("factoryId", "F1", "userId", 1L));
        return mapper.readTree(out);
    }

    @Test
    void isReadOnly_soConsultTabDoesNotBlockIt() {
        // 缺陷原样: 旧实现 AccessMode.WRITE -> 咨询页「请切换到操作页」, 成本问题一律答不出
        assertEquals(ToolExecutor.AccessMode.READ, tool.getAccessMode());
    }

    @Test
    void compositionUnitCostAndComparison_matchHandComputed() throws Exception {
        thisMonth.add(batch("卤牛肉", "600", "100", "50", "50", "80", "kg", ProductionBatchStatus.COMPLETED, false)); // 800
        thisMonth.add(batch("卤牛肉", "300", "60", "20", "20", "20", "kg", ProductionBatchStatus.COMPLETED, false));  // 400
        thisMonth.add(batch("酱鸭", "200", "40", "10", "0", "50", "盒", ProductionBatchStatus.COMPLETED, false));    // 250
        lastPeriod.add(batch("卤牛肉", "500", "100", "50", "50", "100", "kg", ProductionBatchStatus.COMPLETED, false)); // 700

        JsonNode r = run("{}");
        JsonNode data = r.get("data").get("data");
        assertEquals(1450.00, data.get("totals").get("totalCost").asDouble(), 0.001);
        assertEquals(1100.00, data.get("totals").get("materialCost").asDouble(), 0.001);
        // 原料占比 1100/1450 = 75.9%
        assertEquals(75.9, data.get("composition").get(0).get("sharePct").asDouble(), 0.001);
        JsonNode beef = data.get("products").get(0);
        assertEquals("卤牛肉", beef.get("productName").asText());
        assertEquals(1200.00, beef.get("totalCost").asDouble(), 0.001);
        // 单位成本 = 1200 / (80+20) = 12 元/kg
        assertEquals(12.0, beef.get("unitCosts").get(0).get("unitCost").asDouble(), 0.0001);
        // 对比: 总成本 1450 vs 700 -> +750 (+107.1%)
        JsonNode total = null;
        for (JsonNode c : data.get("comparison")) {
            if ("总成本".equals(c.get("name").asText())) total = c;
        }
        assertNotNull(total);
        assertEquals(750.0, total.get("change").asDouble(), 0.001);
        assertEquals(107.1, total.get("changePct").asDouble(), 0.001);
        // 出图: 饼 + 柱
        assertEquals(2, data.get("charts").size());
        assertTrue(r.get("data").get("message").asText().contains("总成本 ¥1450.00"));
    }

    @Test
    void incompleteAndUnfinishedBatches_areNotSummedAndAreSaid() throws Exception {
        thisMonth.add(batch("卤牛肉", "600", "100", "50", "50", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("卤牛肉", "999", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, true));      // 未算全
        thisMonth.add(batch("卤牛肉", "500", "0", "0", "0", "10", "kg", ProductionBatchStatus.IN_PROGRESS, false));   // 进行中
        JsonNode r = run("{}");
        JsonNode data = r.get("data").get("data");
        assertEquals(800.00, data.get("totals").get("totalCost").asDouble(), 0.001);
        assertEquals(1, data.get("costIncompleteBatchCount").asInt());
        assertEquals(2, data.get("batchCount").asInt());
        assertTrue(r.get("data").get("message").asText().contains("成本未算全"));
    }

    @Test
    void mixedUnits_areNeverAddedTogether() throws Exception {
        thisMonth.add(batch("酱鸭", "100", "0", "0", "0", "10", "盒", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("酱鸭", "100", "0", "0", "0", "5", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode unitCosts = run("{}").get("data").get("data").get("products").get(0).get("unitCosts");
        assertEquals(2, unitCosts.size());
        assertEquals(10.0, unitCosts.get(0).get("unitCost").asDouble(), 0.0001); // 100/10
        assertEquals(20.0, unitCosts.get(1).get("unitCost").asDouble(), 0.0001); // 100/5
    }

    @Test
    void noPriorData_saysSoInsteadOfInventingComparison() throws Exception {
        thisMonth.add(batch("卤牛肉", "100", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{}");
        assertEquals(0, r.get("data").get("data").get("comparison").size());
        assertTrue(r.get("data").get("message").asText().contains("无法给涨跌"));
    }

    @Test
    void noBatches_saysNoneNotZero() throws Exception {
        JsonNode r = run("{}");
        assertTrue(r.get("data").get("message").asText().contains("没有已完成的生产批次"));
        assertFalse(r.get("data").get("data").has("totals"));
    }

    @Test
    void amountsHiddenFromCallerWithoutPriceView_noMoneyInTextOrCharts() throws Exception {
        when(priceView.showsKey(any(), any())).thenReturn(false);
        thisMonth.add(batch("卤牛肉", "600", "100", "50", "50", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{}");
        String msg = r.get("data").get("message").asText();
        assertFalse(msg.contains("800"));
        assertTrue(msg.contains("按当前账号权限不显示"));
        assertFalse(r.get("data").get("data").has("charts"));
        assertFalse(r.get("data").get("data").has("totals"));
    }

    @Test
    void periodShortcutLastWeek_resolvesToPreviousMondayToSunday() {
        LocalDate[] range = ReportCostQueryTool.periodRange("last_week", TODAY); // 周六 11-14 -> 上周 11-02..11-08
        assertEquals(LocalDate.of(2026, 11, 2), range[0]);
        assertEquals(LocalDate.of(2026, 11, 8), range[1]);
    }

    @Test
    void productFilter_onlyThatProduct() throws Exception {
        thisMonth.add(batch("卤牛肉", "100", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("酱鸭", "900", "0", "0", "0", "10", "盒", ProductionBatchStatus.COMPLETED, false));
        JsonNode data = run("{\"productName\":\"牛肉\"}").get("data").get("data");
        assertEquals(100.00, data.get("totals").get("totalCost").asDouble(), 0.001);
    }
}
