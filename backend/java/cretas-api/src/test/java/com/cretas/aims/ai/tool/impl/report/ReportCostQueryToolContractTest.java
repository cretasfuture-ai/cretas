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

    /** 价格可见性 ThreadLocal 不得被本测试留在线程上 (污染同一 fork 里后续测试, 如 PriceSensitiveContextScopeContractTest)。 */
    @org.junit.jupiter.api.AfterEach
    void priceContextMustNotLeak() {
        boolean leaked = com.cretas.aims.security.PriceSensitiveContext.shouldHide("procurement:price:view");
        com.cretas.aims.security.PriceSensitiveContext.clear();
        org.junit.jupiter.api.Assertions.assertFalse(leaked, "测试后价格可见性上下文仍是隐藏态 = 泄漏");
    }

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
        when(repo.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(eq("F1"), eq(LocalDate.of(2026, 11, 1).atStartOfDay()), any())).thenReturn(thisMonth);
        when(repo.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(eq("F1"), eq(LocalDate.of(2026, 10, 18).atStartOfDay()), any())).thenReturn(lastPeriod);
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
        b.setActualQuantity(new BigDecimal(good));
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

    @Test
    void unitCostDenominatorIsActualQuantityLikeTheCostAnalysisPage_notGoodQuantity() throws Exception {
        // 页面口径: 成本已算全批次总成本 ÷ 实际产量。good(90) < actual(100): 1000/100 = 10, 不是 1000/90 = 11.11
        ProductionBatch b = batch("卤牛肉", "1000", "0", "0", "0", "90", "kg", ProductionBatchStatus.COMPLETED, false);
        b.setActualQuantity(new BigDecimal("100"));
        thisMonth.add(b);
        JsonNode unitCost = run("{}").get("data").get("data").get("products").get(0).get("unitCosts").get(0);
        assertEquals(10.0, unitCost.get("unitCost").asDouble(), 0.0001);
        assertEquals(100.0, unitCost.get("quantity").asDouble(), 0.0001);
    }

    @Test
    void productsGroupByTypeIdAndRowsCarryIt_andPreviousIncompleteIsSaid() throws Exception {
        ProductionBatch a = batch("卤牛肉", "100", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false);
        ProductionBatch renamed = batch("卤牛肉(新名)", "100", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false);
        renamed.setProductTypeId(a.getProductTypeId());   // 同一产品改过名: 仍是一行
        thisMonth.add(a);
        thisMonth.add(renamed);
        lastPeriod.add(batch("卤牛肉", "50", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        lastPeriod.add(batch("卤牛肉", "50", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, true));   // 上期未算全
        JsonNode r = run("{}");
        JsonNode products = r.get("data").get("data").get("products");
        assertEquals(1, products.size());
        assertEquals(a.getProductTypeId(), products.get(0).get("productTypeId").asText());
        assertEquals(2, products.get(0).get("batchCount").asInt());
        assertEquals(1, r.get("data").get("data").get("previousCostIncompleteBatchCount").asInt());
        assertTrue(r.get("data").get("message").asText().contains("另有 1 个成本未算全未计入"));
    }

    @Test
    void compareToLastMonth_comparesAgainstLastMonthSameDays_andNamesTheBiggestRiser() throws Exception {
        List<ProductionBatch> lastMonthSameDays = new ArrayList<>();
        when(repo.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(eq("F1"),
                eq(LocalDate.of(2026, 10, 1).atStartOfDay()), eq(LocalDate.of(2026, 10, 15).atStartOfDay()))).thenReturn(lastMonthSameDays);
        // 本月 1~14: 原料600 人工100 设备50 其他50; 上月 1~14: 原料500 人工80 设备50 其他50
        thisMonth.add(batch("卤牛肉", "600", "100", "50", "50", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        lastMonthSameDays.add(batch("卤牛肉", "500", "80", "50", "50", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{\"compareTo\":\"last_month\"}");
        assertEquals("2026-10-01", r.get("data").get("data").get("previousPeriod").get("startDate").asText());
        assertEquals("2026-10-14", r.get("data").get("data").get("previousPeriod").get("endDate").asText());
        String msg = r.get("data").get("message").asText();
        assertTrue(msg.contains("对比上月同期"), msg);
        assertTrue(msg.contains("涨得最多的是原料：+1.25 元/kg"), msg);   // 单位成本: 7.5 - 6.25 (产量同为 80kg)
    }

    @Test
    void defaultComparisonStillPreviousEqualLengthPeriod_andSaysNothingRoseWhenNothingRose() throws Exception {
        thisMonth.add(batch("卤牛肉", "100", "10", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        lastPeriod.add(batch("卤牛肉", "500", "80", "50", "50", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{}");
        String msg = r.get("data").get("message").asText();
        assertTrue(msg.contains("对比上一个等长周期"), msg);
        assertTrue(msg.contains("各分项都没有上涨"), msg);
    }

    private void productCatalog(String... names) {
        com.cretas.aims.repository.ProductTypeRepository types = mock(com.cretas.aims.repository.ProductTypeRepository.class);
        List<com.cretas.aims.entity.ProductType> list = new ArrayList<>();
        for (String n : names) {
            com.cretas.aims.entity.ProductType t = new com.cretas.aims.entity.ProductType();
            t.setId("PT-" + n);
            t.setName(n);
            list.add(t);
        }
        when(types.findByFactoryIdAndIsActiveTrue("F1")).thenReturn(list);
        ReflectionTestUtils.setField(tool, "productTypeRepository", types);
    }

    private List<ProductionBatch> lastMonthSameDaysStub() {
        List<ProductionBatch> lastMonthSameDays = new ArrayList<>();
        when(repo.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(eq("F1"),
                eq(LocalDate.of(2026, 10, 1).atStartOfDay()), eq(LocalDate.of(2026, 10, 15).atStartOfDay()))).thenReturn(lastMonthSameDays);
        return lastMonthSameDays;
    }

    @Test
    void doubledOutputWithSameUnitCost_isNotReportedAsARise() throws Exception {
        List<ProductionBatch> lastMonthSameDays = lastMonthSameDaysStub();
        thisMonth.add(batch("卤牛肉", "1200", "0", "0", "0", "160", "kg", ProductionBatchStatus.COMPLETED, false)); // 7.5/kg
        lastMonthSameDays.add(batch("卤牛肉", "600", "0", "0", "0", "80", "kg", ProductionBatchStatus.COMPLETED, false)); // 7.5/kg
        String msg = run("{\"compareTo\":\"last_month\"}").get("data").get("message").asText();
        assertTrue(msg.contains("各分项都没有上涨"), msg);
        assertFalse(msg.contains("涨得最多的是原料"), msg);
    }

    @Test
    void ambiguousProductName_listsCandidatesAndDoesNotSumThem() throws Exception {
        productCatalog("卤牛肉", "卤牛肉片", "五香卤牛肉片", "酱鸭");
        thisMonth.add(batch("卤牛肉片", "100", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("五香卤牛肉片", "900", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        // 「牛肉片」没有完全同名: 匹配到 2 个 -> 候选, 没有合计的 1000
        JsonNode r = run("{\"productName\":\"牛肉片\"}").get("data");
        assertTrue(r.get("message").asText().contains("卤牛肉片") && r.get("message").asText().contains("五香卤牛肉片"), r.toString());
        assertEquals(2, r.get("data").get("candidates").size());
        assertNull(r.get("data").get("totals"));
        assertFalse(r.toString().contains("1000"), r.toString());
    }

    @Test
    void exactProductName_winsOverContainsMatches_andNarrowsTheTotals() throws Exception {
        productCatalog("卤牛肉", "卤牛肉片");
        thisMonth.add(batch("卤牛肉", "100", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("卤牛肉片", "900", "0", "0", "0", "10", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{\"productName\":\"卤牛肉\"}").get("data");
        JsonNode data = r.get("data");
        assertEquals(100.00, data.get("totals").get("totalCost").asDouble(), 0.001);   // 只答「卤牛肉」, 不含「卤牛肉片」
        // N2: 回复和 data 里写明是哪个产品, 不能读起来像全厂数
        assertEquals("卤牛肉", data.get("productFilter").asText());
        assertTrue(r.get("message").asText().contains("卤牛肉"), r.get("message").asText());
    }

    @Test
    void resolvedProductWithNoBatches_saysThatProductHasNone_notTheWholeFactory() throws Exception {
        productCatalog("卤牛肉", "酱鸭");
        thisMonth.add(batch("酱鸭", "900", "0", "0", "0", "10", "盒", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{\"productName\":\"卤牛肉\"}").get("data");
        assertTrue(r.get("message").asText().contains("「卤牛肉」这个产品没有"), r.get("message").asText());
        assertEquals("卤牛肉", r.get("data").get("productFilter").asText());
    }

    // N3: 识别路径上 userInput 是预处理后的文本; 工具读调度层带来的原话 originalUserInput, 且「上月」是对比基准不是本期
    @Test
    void monthOverMonthFromOriginalSentence_whenPreprocessingTurnedLastMonthIntoThePeriod() throws Exception {
        lastMonthSameDaysStub().add(batch("卤牛肉", "450", "0", "0", "0", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("卤牛肉", "600", "0", "0", "0", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-31\",\"timeRangeFromSentence\":\"2026-10-01~2026-10-31\","
                + "\"userInput\":\"卤牛肉成本和[2026-10-01 00:00 至 2026-10-31 23:59]比\",\"originalUserInput\":\"卤牛肉成本和上月比\"}").get("data");
        assertEquals("2026-11-01", r.get("data").get("period").get("startDate").asText());
        assertEquals("2026-11-14", r.get("data").get("period").get("endDate").asText());
        assertEquals("2026-10-01", r.get("data").get("previousPeriod").get("startDate").asText());
        assertEquals("2026-10-14", r.get("data").get("previousPeriod").get("endDate").asText());
        assertTrue(r.get("message").asText().contains("对比上月同期"), r.get("message").asText());
    }

    @Test
    void ownTimeWordInTheSentenceKeepsThePeriod_negativeControl() throws Exception {
        // 「本周成本和上月比」: 句子里有自己的时间词 (本周), 本期不被改回本月
        assertTrue(ReportCostQueryTool.hasOwnTimeWord("本周成本和上月比"));
        assertFalse(ReportCostQueryTool.hasOwnTimeWord("卤牛肉成本和上月比"));
        assertTrue(ReportCostQueryTool.hasOwnTimeWord("10月卤牛肉成本比上月"));
        // R3 New-3: 产品名里的月/年/季/日/数字不是时间词
        for (String p : new String[]{"月饼", "年糕", "四季豆", "日式咖喱", "500g卤牛肉"}) {
            assertFalse(ReportCostQueryTool.hasOwnTimeWord(p + "成本和上月比"), p);
        }
        assertTrue(ReportCostQueryTool.hasOwnTimeWord("近7天成本和上月比"));
        assertTrue(ReportCostQueryTool.hasOwnTimeWord("第3季度成本和上月比"));
        assertTrue(ReportCostQueryTool.hasOwnTimeWord("今年成本和上月比"));
        assertTrue(ReportCostQueryTool.hasOwnTimeWord("3月份成本和上月比"));
    }

    @Test
    void monthOverMonth_productNamesWithTimeLikeChars_keepThisMonthAsThePeriod_andFallBackToUserInput() throws Exception {
        for (String p : new String[]{"月饼", "年糕", "四季豆", "日式咖喱", "500g卤牛肉"}) {
            productCatalog(p);
            // 无 originalUserInput: 回退读 userInput (R3 New-3)
            JsonNode r = run("{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-31\",\"timeRangeFromSentence\":\"2026-10-01~2026-10-31\","
                    + "\"userInput\":\"" + p + "成本和上月比\"}").get("data");
            assertEquals("2026-11-01", r.get("data").get("period").get("startDate").asText(), p);
            assertEquals("2026-11-14", r.get("data").get("period").get("endDate").asText(), p);
        }
    }

    // N4: 有一批缺产量时, 分项金额含它、产量不含它 -> 不能按单位成本比, 退回总额口径并明说
    @Test
    void riserFallsBackToTotals_whenABatchHasNoQuantity() throws Exception {
        List<ProductionBatch> lastMonthSameDays = lastMonthSameDaysStub();
        ProductionBatch noQty = batch("卤牛肉", "300", "0", "0", "0", "40", "kg", ProductionBatchStatus.COMPLETED, false);
        noQty.setActualQuantity(null);
        thisMonth.add(batch("卤牛肉", "300", "0", "0", "0", "40", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(noQty);
        lastMonthSameDays.add(batch("卤牛肉", "300", "0", "0", "0", "40", "kg", ProductionBatchStatus.COMPLETED, false));
        String msg = run("{\"compareTo\":\"last_month\"}").get("data").get("message").asText();
        assertTrue(msg.contains("按总额比"), msg);
        assertFalse(msg.contains("元/kg"), msg);
    }

    @Test
    void riserSaysWhenTheProductMixDiffers() throws Exception {
        List<ProductionBatch> lastMonthSameDays = lastMonthSameDaysStub();
        thisMonth.add(batch("卤牛肉", "600", "0", "0", "0", "40", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("酱鸭", "100", "0", "0", "0", "40", "kg", ProductionBatchStatus.COMPLETED, false));
        lastMonthSameDays.add(batch("卤牛肉", "300", "0", "0", "0", "40", "kg", ProductionBatchStatus.COMPLETED, false));
        String msg = run("{\"compareTo\":\"last_month\"}").get("data").get("message").asText();
        assertTrue(msg.contains("产品构成不同"), msg);
    }

    @Test
    void unknownProductName_saysNoSuchProduct_notFactoryWideNumbers() throws Exception {
        productCatalog("酱鸭");
        thisMonth.add(batch("酱鸭", "900", "0", "0", "0", "10", "盒", ProductionBatchStatus.COMPLETED, false));
        JsonNode r = run("{\"productName\":\"卤牛肉\"}").get("data");
        assertTrue(r.get("message").asText().contains("没有找到"), r.toString());
        assertNull(r.get("data").get("totals"));
    }

    @Test
    void monthOverMonthFallback_readsUserInput_andPlainHuanbiIsNotLastMonth() throws Exception {
        lastMonthSameDaysStub().add(batch("卤牛肉", "450", "0", "0", "0", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        thisMonth.add(batch("卤牛肉", "600", "0", "0", "0", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        lastPeriod.add(batch("卤牛肉", "500", "0", "0", "0", "80", "kg", ProductionBatchStatus.COMPLETED, false));
        // 抽参没填 compareTo, 但原话 userInput 里有「和上月比」
        String msg = run("{\"userInput\":\"卤牛肉成本和上月比\"}").get("data").get("message").asText();
        assertTrue(msg.contains("对比上月同期"), msg);
        // 「环比」单独出现不是上月: 仍然是上一个等长周期
        String msg2 = run("{\"userInput\":\"成本环比\"}").get("data").get("message").asText();
        assertTrue(msg2.contains("对比上一个等长周期"), msg2);
    }
}
