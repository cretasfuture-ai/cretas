package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.ReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
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
}
