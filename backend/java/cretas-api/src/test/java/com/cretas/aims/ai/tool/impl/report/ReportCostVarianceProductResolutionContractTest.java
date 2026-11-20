package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PR #1739 M7 / N6: 成本差异工具按产品名收窄 (行为), 以及可选参数抽取交来的产品 ID / 差异阈值必须落地校验。
 */
class ReportCostVarianceProductResolutionContractTest {

    private ReportService reportService;
    private ReportCostVarianceTool tool;
    private final CostVarianceReportDTO dto = CostVarianceReportDTO.builder()
            .comparable(true).totalActualCost(new BigDecimal("100")).totalVarianceRate(new BigDecimal("3.00")).batchCount(1).build();

    private static ProductType product(String id, String name) {
        ProductType t = new ProductType();
        t.setId(id);
        t.setName(name);
        return t;
    }

    @BeforeEach
    void setUp() {
        reportService = mock(ReportService.class);
        when(reportService.getCostVarianceReport(eq("F1"), any(), any())).thenReturn(dto);
        when(reportService.getCostVarianceReport(eq("F1"), any(), any(), any())).thenReturn(dto);
        ProductTypeRepository types = mock(ProductTypeRepository.class);
        when(types.findByFactoryIdAndIsActiveTrue("F1")).thenReturn(List.of(
                product("P-1", "卤牛肉"), product("P-2", "卤牛肉片"), product("P-3", "五香卤牛肉片"), product("P-4", "酱鸭")));
        tool = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(tool, "reportService", reportService);
        ReflectionTestUtils.setField(tool, "productTypeRepository", types);
    }

    private Map<String, Object> run(Map<String, Object> params) throws Exception {
        return tool.doExecute("F1", new HashMap<>(params), new HashMap<>());
    }

    @Test
    void uniqueProductName_callsTheProductScopedOverload() throws Exception {
        run(Map.of("productName", "酱鸭"));
        verify(reportService).getCostVarianceReport(eq("F1"), any(), any(), eq("P-4"));
        verify(reportService, never()).getCostVarianceReport(eq("F1"), any(), any());
    }

    @Test
    void exactNameWinsOverContainsMatches() throws Exception {
        run(Map.of("productName", "卤牛肉"));
        verify(reportService).getCostVarianceReport(eq("F1"), any(), any(), eq("P-1"));
    }

    @Test
    void ambiguousProductName_listsCandidates_andQueriesNothing() throws Exception {
        Map<String, Object> r = run(Map.of("productName", "牛肉片"));
        assertTrue(String.valueOf(r.get("message")).contains("卤牛肉片") && String.valueOf(r.get("message")).contains("五香卤牛肉片"), r.toString());
        verify(reportService, never()).getCostVarianceReport(any(), any(), any());
        verify(reportService, never()).getCostVarianceReport(any(), any(), any(), any());
    }

    @Test
    void productNameStuffedIntoTheIdField_isResolvedAsAName_notQueriedAsAnId() throws Exception {
        run(Map.of("productTypeId", "酱鸭"));
        verify(reportService).getCostVarianceReport(eq("F1"), any(), any(), eq("P-4"));
        verify(reportService, never()).getCostVarianceReport(eq("F1"), any(), any(), eq("酱鸭"));
    }

    @Test
    void idFromAnotherFactoryOrInvented_isNotQueried() throws Exception {
        Map<String, Object> r = run(Map.of("productTypeId", "P-OTHER-FACTORY"));
        verify(reportService, never()).getCostVarianceReport(any(), any(), any(), any());
        assertTrue(String.valueOf(r.get("message")).contains("没有找到"), r.toString());
    }

    @Test
    void realCatalogIdIsAccepted() throws Exception {
        run(Map.of("productTypeId", "P-2"));
        verify(reportService).getCostVarianceReport(eq("F1"), any(), any(), eq("P-2"));
    }

    @Test
    void absurdVarianceThresholdFromExtractionIsIgnored_sensibleOneIsKept() throws Exception {
        Map<String, Object> ignored = run(Map.of("varianceThreshold", 0));
        assertFalse(((Map<?, ?>) ignored.get("queryConditions")).containsKey("varianceThreshold"), ignored.toString());
        Map<String, Object> text = run(Map.of("varianceThreshold", "很多"));
        assertFalse(((Map<?, ?>) text.get("queryConditions")).containsKey("varianceThreshold"), text.toString());
        Map<String, Object> kept = run(Map.of("varianceThreshold", 5));
        assertEquals(5.0, ((Number) ((Map<?, ?>) kept.get("queryConditions")).get("varianceThreshold")).doubleValue());
    }

    // 2026-11-15 复跑 Q14: 可比时没有 message, 通用格式化器把 DTO 字段打成 `comparedPlanCount: 13 | ...`; 且 totalBomCost(只含可比计划)
    // 和 totalActualCost(期间全部批次) 口径不同, 不能相减。必须给人话, 差额用「可比计划的实际成本」算
    @Test
    void comparableResult_hasHumanMessage_andDoesNotSubtractIncomparableActual() throws Exception {
        CostVarianceReportDTO d = CostVarianceReportDTO.builder()
                .comparable(true).comparedPlanCount(13).incomparablePlanCount(61)
                .totalBomCost(new BigDecimal("16.52")).comparedActualCost(new BigDecimal("18.00"))
                .totalVariance(new BigDecimal("1.48")).totalVarianceRate(new BigDecimal("8.96"))
                .totalActualCost(new BigDecimal("200426.82")).batchCount(74).build();
        when(reportService.getCostVarianceReport(eq("F1"), any(), any())).thenReturn(d);
        Map<String, Object> r = run(Map.of());
        String msg = String.valueOf(r.get("message"));
        assertFalse(msg.contains("comparedPlanCount") || msg.contains("totalBomCost"), msg);
        assertTrue(msg.contains("13 个能和标准成本比较") && msg.contains("61 个无法比较"), msg);
        assertTrue(msg.contains("标准成本 ¥16.52") && msg.contains("实际成本 ¥18.00") && msg.contains("差额 ¥1.48"), msg);
        assertTrue(msg.contains("不能拿它和可比部分的标准成本相减"), msg);
    }

    // R-M3: 可比分支的新文案也要走保密 fail-closed: 调用者对保密产品无权而数据没带说明时, 金额不写进文案
    @Test
    void comparableMessageWithholdsAmountsWhenCallerLacksSecretProductAccessAndNoteMissing() throws Exception {
        CostVarianceReportDTO d = CostVarianceReportDTO.builder()
                .comparable(true).comparedPlanCount(1).incomparablePlanCount(2)
                .totalBomCost(new BigDecimal("16.52")).comparedActualCost(new BigDecimal("18.00"))
                .totalVariance(new BigDecimal("1.48")).totalVarianceRate(new BigDecimal("8.96"))
                .totalActualCost(new BigDecimal("200426.82")).batchCount(3).build();
        when(reportService.getCostVarianceReport(eq("F1"), any(), any())).thenReturn(d);
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope scope =
                mock(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.class);
        when(scope.forCurrentCaller("F1")).thenReturn(new com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded(
                Set.of("P-9"), Set.of(), Set.of("P-9"), Map.of(), Set.of()));
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        String msg = String.valueOf(run(Map.of()).get("message"));
        assertFalse(msg.contains("200426.82") || msg.contains("18.00") || msg.contains("16.52"), msg);
        assertTrue(msg.contains("不提供给 AI"), msg);
        // 复审 R-M3: 同一响应的 summary 也不写金额
        String summary = String.valueOf(run(Map.of()).get("summary"));
        assertFalse(summary.contains("16.52") || summary.contains("1.48") || summary.contains("8.96"), summary);
        // 阴性对照: 数据带了保密说明 (已按调用者裁剪) 时照写
        d.setProcessSecretNote("（不含 1 个保密产品）");
        assertTrue(String.valueOf(run(Map.of()).get("message")).contains("¥200426.82"));
        assertTrue(String.valueOf(run(Map.of()).get("summary")).contains("16.52"));
    }

    // 第 3 轮表 C: 保密兜底命中且可比计划数为 null 时, summary 不写「可比计划 null 个」
    @Test
    void withheldSummary_doesNotPrintNullPlanCount() throws Exception {
        CostVarianceReportDTO d = CostVarianceReportDTO.builder()
                .comparable(true).comparedPlanCount(null).incomparablePlanCount(2)
                .totalBomCost(new BigDecimal("16.52")).comparedActualCost(new BigDecimal("18.00"))
                .totalVariance(new BigDecimal("1.48")).totalVarianceRate(new BigDecimal("8.96"))
                .totalActualCost(new BigDecimal("200426.82")).batchCount(3).build();
        when(reportService.getCostVarianceReport(eq("F1"), any(), any())).thenReturn(d);
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope scope =
                mock(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.class);
        when(scope.forCurrentCaller("F1")).thenReturn(new com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded(
                Set.of("P-9"), Set.of(), Set.of("P-9"), Map.of(), Set.of()));
        ReflectionTestUtils.setField(tool, "llmCostScope", scope);
        String summary = String.valueOf(run(Map.of()).get("summary"));
        assertTrue(summary.contains("不提供给 AI"), summary);   // 确认走的是兜底分支
        assertFalse(summary.contains("null"), summary);
        // 阳性对照: 计划数有值时照写
        d.setComparedPlanCount(4);
        assertTrue(String.valueOf(run(Map.of()).get("summary")).contains("可比计划 4 个"));
    }

    // organizer 终审口径: 问句里的名字多了字 (「酱鸭腿」) 时按最长完整命中「酱鸭」查, 回复开头写明按哪个产品 / 全厂统计
    @Test
    void longerNameInQuestion_answersTheCatalogProduct_andSaysWhichOne() throws Exception {
        Map<String, Object> r = run(Map.of("productName", "酱鸭腿成本差异"));
        verify(reportService).getCostVarianceReport(eq("F1"), any(), any(), eq("P-4"));
        String msg = String.valueOf(r.get("message"));
        assertTrue(msg.startsWith("按产品「酱鸭」统计："), msg);
        assertFalse(msg.contains("没有名为"), msg);
        // 直接给了本厂产品 ID 也写产品名; 不限产品写全厂
        assertTrue(String.valueOf(run(Map.of("productTypeId", "P-1")).get("message")).startsWith("按产品「卤牛肉」统计："));
        assertTrue(String.valueOf(run(Map.of()).get("message")).startsWith("按全厂统计："));
    }

    // 第 3 轮表 B: 同时问到两个产品时明确说, 不悄悄只按其中一个查
    @Test
    void twoProductsInOneQuestion_areSaidPlainly_andNothingIsQueried() throws Exception {
        Map<String, Object> r = run(Map.of("productName", "卤牛肉和酱鸭成本差异"));
        String msg = String.valueOf(r.get("message"));
        assertTrue(msg.contains("问到了 2 个产品") && msg.contains("卤牛肉") && msg.contains("酱鸭"), msg);
        verify(reportService, never()).getCostVarianceReport(any(), any(), any());
        verify(reportService, never()).getCostVarianceReport(any(), any(), any(), any());
    }
}
