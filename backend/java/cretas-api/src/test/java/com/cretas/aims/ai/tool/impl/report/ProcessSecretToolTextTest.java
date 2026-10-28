package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.dto.report.ProductionByProductDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.impl.AIContextServiceImpl;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.reports.OperatingReportGrossMarginQuery;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 出口 5: AI 工具结果里直接进模型上下文的文本 (preformattedPrompt / message) 不写保密产品的成本、毛利。
 * 跑在工具的 doExecute 上 (同包可见), 断言打在工具返回的文本字段上。
 */
class ProcessSecretToolTextTest {

    private final List<ProductionBatch> batches = List.of(
            batch(1L, SECRET, SECRET_PLAN, SECRET_COST, new BigDecimal("100")),
            batch(2L, NORMAL, NORMAL_PLAN, NORMAL_COST, new BigDecimal("100")),
            batch(3L, NORMAL2, null, NORMAL2_COST, new BigDecimal("200")));

    // ---------------- ReportBomCostTool ----------------

    private ReportBomCostTool bomTool(ProcessSecretLlmCostScope scope) {
        return bomTool(scope, batches);
    }

    private ReportBomCostTool bomTool(ProcessSecretLlmCostScope scope, List<ProductionBatch> batches) {
        ReportService reports = mock(ReportService.class);
        when(reports.getProductionByProduct(eq(FACTORY), any(LocalDate.class), any(LocalDate.class))).thenReturn(List.of(
                stat(SECRET, SECRET_NAME, "100"), stat(NORMAL, "普通2", "100"), stat(NORMAL2, "普通3", "200")));
        BomService bom = mock(BomService.class);
        when(bom.getProductTypesWithBom(FACTORY)).thenReturn(List.of());
        ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
        when(batchRepository.findByFactoryIdAndCreatedAtBetween(eq(FACTORY), any(), any())).thenReturn(batches);
        when(batchRepository.findByFactoryIdAndCreatedAtAfter(eq(FACTORY), any())).thenReturn(batches);
        ProductTypeRepository productTypes = mock(ProductTypeRepository.class);
        AIContextServiceImpl contexts = new AIContextServiceImpl(bom, reports, batchRepository, productTypes, scope);
        ReportBomCostTool tool = new ReportBomCostTool();
        ReflectionTestUtils.setField(tool, "bomService", bom);
        ReflectionTestUtils.setField(tool, "aiContextService", contexts);
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        return tool;
    }

    @Test
    @SuppressWarnings("unchecked")
    void bomCostFactoryAnalysisTextAndTotalsLeaveOutTheProtectedProduct() throws Exception {
        Map<String, Object> result = bomTool(protectedScope()).doExecute(FACTORY,
                new HashMap<>(Map.of("analysisType", "detail", "includeZeroCost", true)), Map.of());

        BigDecimal expected = BigDecimal.ZERO;
        for (ProductionBatch b : batches) {
            if (!SECRET.equals(b.getProductTypeId())) expected = expected.add(b.getTotalCost());
        }
        String text = (String) result.get("preformattedPrompt");
        assertThat(text).contains("- 总成本: ¥" + expected.toPlainString(), "不含 1 个保密产品", "¥12.3456")
                .doesNotContain(SECRET_COST.toPlainString(), "77.7777");
        Map<String, Object> summary = (Map<String, Object>) result.get("summary");
        assertThat((BigDecimal) summary.get("totalCost")).isEqualByComparingTo(expected);
        assertThat((String) summary.get("note")).contains("不含 1 个保密产品");
        Map<String, Object> rankings = (Map<String, Object>) result.get("rankings");
        assertThat((List<String>) rankings.get("topByCost")).doesNotContain(SECRET_NAME);
        List<Map<String, Object>> details = (List<Map<String, Object>>) result.get("productDetails");
        Map<String, Object> secretRow = details.stream().filter(d -> SECRET.equals(d.get("productTypeId"))).findFirst().orElseThrow();
        assertThat(secretRow.get("costWithheld")).isEqualTo(true);
        assertThat(secretRow.get("avgActualUnitCost")).isNull();
        assertThat(secretRow.get("totalCost")).isNull();
    }

    @Test
    void bomCostNegativeControlShowsTheProductCostWhenNothingIsProtected() throws Exception {
        Map<String, Object> result = bomTool(unprotectedScope()).doExecute(FACTORY,
                new HashMap<>(Map.of("analysisType", "detail", "includeZeroCost", true)), Map.of());

        assertThat((String) result.get("preformattedPrompt")).contains("¥77.7777", "¥13333.33");
    }

    @Test
    void bomCostSingleProtectedProductReturnsNoCostAtAll() throws Exception {
        ReportBomCostTool tool = bomTool(protectedScope());

        Map<String, Object> secret = tool.doExecute(FACTORY, new HashMap<>(Map.of("productTypeId", SECRET)), Map.of());
        Map<String, Object> normal = tool.doExecute(FACTORY, new HashMap<>(Map.of("productTypeId", NORMAL)), Map.of());

        assertThat(secret.get("costWithheld")).isEqualTo(true);
        assertThat(secret).doesNotContainKeys("actualCost", "bomCost", "trend");
        assertThat(secret.toString()).doesNotContain("77.7777", SECRET_COST.toPlainString());
        assertThat((String) normal.get("preformattedPrompt")).contains("¥12.3456");
    }

    @Test
    void bomCostForTheOwnerCarriesTheProtectedProductCost() throws Exception {
        ReportBomCostTool tool = bomTool(protectedScope());
        Map<String, Object> factory;
        Map<String, Object> single;
        try (var ignored = as(owner())) {
            factory = tool.doExecute(FACTORY, new HashMap<>(Map.of("analysisType", "detail", "includeZeroCost", true)), Map.of());
            single = tool.doExecute(FACTORY, new HashMap<>(Map.of("productTypeId", SECRET)), Map.of());
        }

        assertThat((String) factory.get("preformattedPrompt")).contains("¥77.7777", "¥13333.33").doesNotContain("不含");
        assertThat((String) single.get("preformattedPrompt")).contains("¥77.7777");
    }

    @Test
    void bomCostOfAProductMadeOnTheProtectedProductsPlanIsWithheldForUnauthorizedCallers() throws Exception {
        // 普通产品, 但它的批次属于牵涉保密产品的计划 (联产): 与生产统计上下文同口径, 无权调用者整份不给
        List<ProductionBatch> coProduced = List.of(batch(5L, NORMAL, SECRET_PLAN, NORMAL_COST, new BigDecimal("100")));
        ReportBomCostTool tool = bomTool(protectedScope(), coProduced);

        Map<String, Object> unauthorized = tool.doExecute(FACTORY, new HashMap<>(Map.of("productTypeId", NORMAL)), Map.of());
        Map<String, Object> owner;
        try (var ignored = as(owner())) {
            owner = tool.doExecute(FACTORY, new HashMap<>(Map.of("productTypeId", NORMAL)), Map.of());
        }

        assertThat(unauthorized.get("costWithheld")).isEqualTo(true);
        assertThat(unauthorized.toString()).doesNotContain("12.3456");
        assertThat((String) owner.get("preformattedPrompt")).contains("¥12.3456");
    }

    // ---------------- SkuGrossMarginTool ----------------

    private Map<String, Object> skuRow(String id, String name, String revenue, BigDecimal cost) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("product_type_id", id);
        row.put("product_name", name);
        row.put("revenue", new BigDecimal(revenue));
        row.put("costed_revenue", new BigDecimal(revenue));
        row.put("cost", cost);
        row.put("costed_line_count", 2);
        row.put("line_count", 2);
        row.put("missing_price_line_count", 0);
        row.put("zero_cost_line_count", 0);
        return row;
    }

    private String skuMessage(ProcessSecretLlmCostScope scope) throws Exception {
        List<Map<String, Object>> rows = List.of(skuRow(SECRET, SECRET_NAME, "9999.99", SECRET_COST),
                skuRow(NORMAL, "普通甲", "3000.00", NORMAL_COST));
        OperatingReportGrossMarginQuery query = mock(OperatingReportGrossMarginQuery.class);
        when(query.fetch(eq(FACTORY), any(), any())).thenReturn(OperatingReportGrossMarginQuery.payload(rows, false));
        ProductTypeRepository types = mock(ProductTypeRepository.class);
        when(types.findByFactoryIdAndIsActiveTrue(FACTORY)).thenReturn(List.of());
        SkuGrossMarginTool tool = new SkuGrossMarginTool();
        ReflectionTestUtils.setField(tool, "realizedQuery", query);
        ReflectionTestUtils.setField(tool, "productTypeRepository", types);
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        return (String) tool.doExecute(FACTORY, new HashMap<>(), Map.of()).get("message");
    }

    @Test
    void skuMarginMessageDoesNotStateTheProtectedProductsCostOrMargin() throws Exception {
        String message = skuMessage(protectedScope());

        // 保密产品: 毛利 2222.22 / 毛利率 22.22% / 成本 7777.77 都不写; 普通产品照常 (阴性对照)
        assertThat(message).contains(SECRET_NAME, ProcessSecretLlmCostScope.WITHHELD_REASON)
                .doesNotContain(SECRET_COST.toPlainString(), "22.22%")
                .contains("成本 ¥" + NORMAL_COST.toPlainString(), "58.85%");
    }

    @Test
    void skuMarginMessageForTheOwnerStatesTheProtectedMargin() throws Exception {
        try (var ignored = as(owner())) {
            assertThat(skuMessage(protectedScope())).contains("成本 ¥" + SECRET_COST.toPlainString(), "22.22%");
        }
    }

    @Test
    void skuMarginNegativeControlStatesItWhenNothingIsProtected() throws Exception {
        assertThat(skuMessage(unprotectedScope())).contains("成本 ¥" + SECRET_COST.toPlainString(), "22.22%");
    }

    // ---------------- ReportCostVarianceTool ----------------

    private String varianceMessage(ProcessSecretLlmCostScope scope) throws Exception {
        ReportService reports = mock(ReportService.class);
        when(reports.getCostVarianceReport(eq(FACTORY), any(), any())).thenReturn(CostVarianceReportDTO.builder()
                .comparable(false).totalActualCost(new BigDecimal("13333.33")).batchCount(3).build());
        ReportCostVarianceTool tool = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(tool, "reportService", reports);
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        return (String) tool.doExecute(FACTORY, new HashMap<>(), Map.of()).get("message");
    }

    @Test
    void costVarianceMessageLeavesOutTheFactoryTotalThatIncludesProtectedProducts() throws Exception {
        assertThat(varianceMessage(protectedScope())).doesNotContain("13333.33").contains("批次数 3", "工艺保密");
        assertThat(varianceMessage(unprotectedScope())).contains("实际总成本 ¥13333.33");
        try (var ignored = as(owner())) {
            assertThat(varianceMessage(protectedScope())).contains("实际总成本 ¥13333.33");
        }
    }

    @Test
    void costVarianceMessageCarriesTheVisibleTotalWithTheNoteWhenTheServiceTrimmedIt() throws Exception {
        ReportService reports = mock(ReportService.class);
        when(reports.getCostVarianceReport(eq(FACTORY), any(), any())).thenReturn(CostVarianceReportDTO.builder()
                .comparable(false).totalActualCost(new BigDecimal("5555.56")).batchCount(3)
                .processSecretNote(ProcessSecretLlmCostScope.note(1)).build());
        ReportCostVarianceTool tool = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(tool, "reportService", reports);
        ReflectionTestUtils.setField(tool, "llmCostScope", protectedScope());

        String message = (String) tool.doExecute(FACTORY, new HashMap<>(), Map.of()).get("message");

        assertThat(message).contains("实际总成本 ¥5555.56", "不含 1 个保密产品");
    }

    // ---------------- ReportFinanceRatioTool ----------------

    private Map<String, Object> financeRatio(String ratioType, Map<String, Object> finance) throws Exception {
        ReportService reports = mock(ReportService.class);
        when(reports.getFinanceReport(eq(FACTORY), any(), any())).thenReturn(finance);
        ReportFinanceRatioTool tool = new ReportFinanceRatioTool();
        ReflectionTestUtils.setField(tool, "reportService", reports);
        return tool.doExecute(FACTORY, new HashMap<>(Map.of("ratioType", ratioType)), Map.of());
    }

    private static Map<String, Object> finance(String reasonKey) {
        Map<String, Object> finance = new HashMap<>();
        finance.put("totalRevenue", new BigDecimal("150000.00"));
        finance.put("totalCost", new BigDecimal("5555.56"));   // 只算了调用者能看的计划
        finance.put("totalAssets", new BigDecimal("1000000"));
        finance.put("totalEquity", new BigDecimal("500000"));
        if (reasonKey != null) finance.put(reasonKey, ProcessSecretLlmCostScope.note(1));
        return finance;
    }

    @Test
    void financeRatioDoesNotSynthesiseAProfitFromATrimmedCostAndAFullRevenue() throws Exception {
        for (String reasonKey : List.of("profitUnavailableReason", "processSecretNote")) {
            for (String ratio : List.of("ROA", "ROE", "DUPONT")) {
                Map<String, Object> result = financeRatio(ratio, finance(reasonKey));
                assertThat(result).as("%s / %s", reasonKey, ratio).containsEntry("netProfit", null)
                        .doesNotContainKeys("roa", "roe")
                        .containsKey("unavailableReason");
                assertThat((String) result.get("message")).contains("不提供", "不含 1 个保密产品");
                assertThat(result.toString()).doesNotContain("144444");   // 150000 − 5555.56 的假利润
            }
        }
    }

    @Test
    void financeRatioNegativeControlComputesWhenTheReportIsComplete() throws Exception {
        Map<String, Object> roa = financeRatio("ROA", finance(null));
        assertThat((Double) roa.get("netProfit")).isEqualTo(150000.00 - 5555.56);
        assertThat(roa).containsKey("roa").doesNotContainKey("unavailableReason");
        // 不依赖利润的比率在说明存在时照常给
        assertThat(financeRatio("SOLVENCY", finance("processSecretNote"))).containsKey("debtRatio");
    }

    @SuppressWarnings("unchecked")
    private List<String> skuOrder(ProcessSecretLlmCostScope scope) throws Exception {
        // 保密产品毛利率 90% (按毛利排本该第一), 普通产品 58.85%
        List<Map<String, Object>> rows = List.of(skuRow(SECRET, SECRET_NAME, "9999.99", new BigDecimal("999.99")),
                skuRow(NORMAL, "普通甲", "3000.00", NORMAL_COST));
        OperatingReportGrossMarginQuery query = mock(OperatingReportGrossMarginQuery.class);
        when(query.fetch(eq(FACTORY), any(), any())).thenReturn(OperatingReportGrossMarginQuery.payload(rows, false));
        ProductTypeRepository types = mock(ProductTypeRepository.class);
        when(types.findByFactoryIdAndIsActiveTrue(FACTORY)).thenReturn(List.of());
        SkuGrossMarginTool tool = new SkuGrossMarginTool();
        ReflectionTestUtils.setField(tool, "realizedQuery", query);
        ReflectionTestUtils.setField(tool, "productTypeRepository", types);
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        Map<String, Object> data = (Map<String, Object>) tool.doExecute(FACTORY, new HashMap<>(), Map.of()).get("data");
        return ((List<Map<String, Object>>) data.get("items")).stream().map(i -> (String) i.get("productTypeId")).toList();
    }

    @Test
    void skuMarginPutsTheMaskedProductLastForUnauthorizedCallersSoTheRankDoesNotLeak() throws Exception {
        assertThat(skuOrder(protectedScope())).containsExactly(NORMAL, SECRET);
        try (var ignored = as(owner())) {
            assertThat(skuOrder(protectedScope())).containsExactly(SECRET, NORMAL);   // 有权: 照毛利率排
        }
    }

    @Test
    void processTaskSummaryCarriesTheBatchsProductAndPlanForAttribution() throws Exception {
        com.cretas.aims.service.workprocess.WorkProcessTaskService tasks =
                mock(com.cretas.aims.service.workprocess.WorkProcessTaskService.class);
        com.cretas.aims.service.ProcessWorkReportingService reporting =
                mock(com.cretas.aims.service.ProcessWorkReportingService.class);
        ProductionBatchRepository batchRepository = mock(ProductionBatchRepository.class);
        when(tasks.getById(FACTORY, 7L)).thenReturn(com.cretas.aims.dto.WorkProcessTaskDTO.builder()
                .id(7L).productionBatchId(1L).processName("卤制").build());
        when(reporting.getWorkerSummaryByTask(FACTORY, "7")).thenReturn(List.of());
        when(reporting.getReportsByTask(FACTORY, "7")).thenReturn(List.of());
        when(batchRepository.findByIdAndFactoryId(1L, FACTORY))
                .thenReturn(java.util.Optional.of(batch(1L, SECRET, SECRET_PLAN, SECRET_COST, BigDecimal.TEN)));
        com.cretas.aims.ai.tool.impl.processing.ProcessTaskSummaryTool tool =
                new com.cretas.aims.ai.tool.impl.processing.ProcessTaskSummaryTool(tasks, reporting, batchRepository);

        java.lang.reflect.Method doExecute = tool.getClass().getDeclaredMethod("doExecute", String.class, Map.class, Map.class);
        doExecute.setAccessible(true);
        Map<?, ?> data = (Map<?, ?>) ((Map<?, ?>) doExecute.invoke(tool, FACTORY,
                new HashMap<>(Map.of("taskId", "7")), Map.of())).get("data");

        assertThat(data.get("productTypeId")).isEqualTo(SECRET);
        assertThat(data.get("productionPlanId")).isEqualTo(SECRET_PLAN);
    }

    private static ProductionByProductDTO stat(String productTypeId, String name, String quantity) {
        return ProductionByProductDTO.builder().productTypeId(productTypeId).productName(name)
                .totalQuantity(new BigDecimal(quantity)).unit("kg").build();
    }
}
