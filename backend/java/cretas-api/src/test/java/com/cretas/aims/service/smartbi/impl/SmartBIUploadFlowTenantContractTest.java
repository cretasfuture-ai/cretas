package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.FieldMappingResult;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.repository.smartbi.SmartBiFinanceDataRepository;
import com.cretas.aims.repository.smartbi.SmartBiSalesDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.repository.smartbi.SmartBiExcelUploadRepository;
import com.cretas.aims.service.smartbi.DynamicAnalysisService;
import com.cretas.aims.service.smartbi.ChartTemplateService;
import com.cretas.aims.service.smartbi.DynamicChartConfigBuilderService;
import com.cretas.aims.service.smartbi.DynamicDataPersistenceService;
import com.cretas.aims.service.smartbi.ExcelDataPersistenceService;
import com.cretas.aims.service.smartbi.ExcelDynamicParserService;
import com.cretas.aims.service.smartbi.LLMFieldMappingService;
import com.cretas.aims.service.smartbi.SmartBIUploadFlowService;
import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SmartBIUploadFlowTenantContractTest {
    private final ExcelDynamicParserService parser = mock(ExcelDynamicParserService.class);
    private final ExcelDataPersistenceService persistence = mock(ExcelDataPersistenceService.class);
    private final ChartTemplateService charts = mock(ChartTemplateService.class);
    private final SmartBiSalesDataRepository sales = mock(SmartBiSalesDataRepository.class);
    private final SmartBiFinanceDataRepository finance = mock(SmartBiFinanceDataRepository.class);
    private final LLMFieldMappingService mapping = mock(LLMFieldMappingService.class);
    private final DynamicChartConfigBuilderService configBuilder = mock(DynamicChartConfigBuilderService.class);
    private final DynamicDataPersistenceService dynamicPersistence = mock(DynamicDataPersistenceService.class);
    private final SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
    private final SmartBiExcelUploadRepository legacyUploads = mock(SmartBiExcelUploadRepository.class);

    private SmartBIUploadFlowServiceImpl service() {
        SmartBIUploadFlowServiceImpl service = new SmartBIUploadFlowServiceImpl(
                parser, persistence, charts, sales, finance, mapping, configBuilder);
        ReflectionTestUtils.setField(service, "postgresEnabled", true);
        ReflectionTestUtils.setField(service, "dynamicPersistenceService", dynamicPersistence);
        ReflectionTestUtils.setField(service, "pgUploadRepository", uploads);
        ReflectionTestUtils.setField(service, "excelUploadRepository", legacyUploads);
        return service;
    }

    @Test
    void confirmRejectsForeignUploadBeforeFieldUpdate() {
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F007").build()));

        SmartBIUploadFlowService.UploadFlowResult result = service().confirmAndPersist(
                "F006", 9L, null, List.of(mock(FieldMappingResult.class)), "GENERAL");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("不属于当前工厂");
        verifyNoInteractions(dynamicPersistence, charts, sales, finance);
    }

    @Test
    void foreignUploadStopsStaticFallbackAndAiDataRead() {
        SmartBIUploadFlowServiceImpl flow = service();
        ReflectionTestUtils.setField(flow, "dynamicAnalysisService", mock(DynamicAnalysisService.class));
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F007").build()));

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES);
        assertThat(result).isEmpty();
        verifyNoInteractions(dynamicPersistence, charts, sales, finance);
    }

    @Test
    void postgresEnabledLegacyOnlyUploadUsesScopedStaticRows() {
        SmartBIUploadFlowServiceImpl flow = service();
        when(uploads.findById(9L)).thenReturn(Optional.empty());
        when(legacyUploads.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(
                new com.cretas.aims.entity.smartbi.SmartBiExcelUpload()));
        com.cretas.aims.entity.smartbi.SmartBiSalesData sale =
                new com.cretas.aims.entity.smartbi.SmartBiSalesData();
        sale.setAmount(BigDecimal.ONE);
        sale.setQuantity(BigDecimal.ONE);
        when(sales.findByFactoryIdAndUploadId("F006", 9L)).thenReturn(List.of(sale));

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES);

        assertThat(result).containsEntry("recordCount", 1);
        verify(sales).findByFactoryIdAndUploadId("F006", 9L);
        verifyNoInteractions(dynamicPersistence);
    }

    @Test
    void foreignPostgresUploadCannotUseCollidingLocalLegacyMetadata() {
        SmartBIUploadFlowServiceImpl flow = service();
        ReflectionTestUtils.setField(flow, "dynamicAnalysisService", mock(DynamicAnalysisService.class));
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F007").build()));
        when(legacyUploads.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(
                new com.cretas.aims.entity.smartbi.SmartBiExcelUpload()));

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES);

        assertThat(result).isEmpty();
        verifyNoInteractions(sales, finance);
    }

    @Test
    void emptyOwnedPostgresUploadNeverFallsBackToCollidingLegacyRows() {
        SmartBIUploadFlowServiceImpl flow = service();
        DynamicAnalysisService dynamic = mock(DynamicAnalysisService.class);
        ReflectionTestUtils.setField(flow, "dynamicAnalysisService", dynamic);
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F006").build()));
        when(legacyUploads.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(
                new com.cretas.aims.entity.smartbi.SmartBiExcelUpload()));
        when(dynamic.analyzeDynamic("F006", 9L, "sales"))
                .thenReturn(new DynamicAnalysisService.DashboardResponse());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可分析图表")
                .hasFieldOrPropertyWithValue("code", 422);
        verify(dynamic).analyzeDynamic("F006", 9L, "sales");
        verifyNoInteractions(sales, finance);
    }

    @Test
    void missingPostgresAnalysisServiceFails503WithoutStaticOrAiFallback() {
        SmartBIUploadFlowServiceImpl flow = service();
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F006").build()));

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("服务不可用")
                .hasFieldOrPropertyWithValue("code", 503);
        verifyNoInteractions(sales, finance, charts);
    }

    @Test
    void nullOrEmptyPostgresAnalysisFailsClearlyWithoutLegacyFallback() {
        DynamicAnalysisService dynamic = mock(DynamicAnalysisService.class);
        SmartBIUploadFlowServiceImpl flow = service();
        ReflectionTestUtils.setField(flow, "dynamicAnalysisService", dynamic);
        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F006").build()));

        when(dynamic.analyzeDynamic("F006", 9L, "sales")).thenReturn(null);
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("未返回结果")
                .hasFieldOrPropertyWithValue("code", 503);

        when(dynamic.analyzeDynamic("F006", 9L, "sales"))
                .thenReturn(new DynamicAnalysisService.DashboardResponse());
        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可分析图表")
                .hasFieldOrPropertyWithValue("code", 422);
        verifyNoInteractions(sales, finance, charts);
    }

    @Test
    void mysqlOnlyStaticFallbackUsesFactoryScopedUploadAndRows() {
        SmartBIUploadFlowServiceImpl flow = service();
        ReflectionTestUtils.setField(flow, "postgresEnabled", false);
        when(legacyUploads.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(
                new com.cretas.aims.entity.smartbi.SmartBiExcelUpload()));
        com.cretas.aims.entity.smartbi.SmartBiSalesData sale =
                new com.cretas.aims.entity.smartbi.SmartBiSalesData();
        sale.setAmount(BigDecimal.ONE);
        sale.setQuantity(BigDecimal.ONE);
        when(sales.findByFactoryIdAndUploadId("F006", 9L)).thenReturn(List.of(sale));

        Map<String, Object> result = ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES);

        assertThat(result).containsEntry("recordCount", 1);
        verify(sales).findByFactoryIdAndUploadId("F006", 9L);
        verify(sales, never()).findByUploadId(9L);
    }

    @Test
    void publicGenerateChartAcceptsOwnedMysqlUploadAndUsesScopedRows() {
        SmartBIUploadFlowServiceImpl flow = service();
        ReflectionTestUtils.setField(flow, "postgresEnabled", false);
        when(legacyUploads.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(
                new com.cretas.aims.entity.smartbi.SmartBiExcelUpload()));
        com.cretas.aims.entity.smartbi.SmartBiSalesData sale =
                new com.cretas.aims.entity.smartbi.SmartBiSalesData();
        sale.setAmount(BigDecimal.ONE);
        sale.setQuantity(BigDecimal.ONE);
        when(sales.findByFactoryIdAndUploadId("F006", 9L)).thenReturn(List.of(sale));
        when(charts.buildChartWithAnalysis(eq("sales_by_customer"), any(), eq("F006")))
                .thenReturn(Map.of("chartType", "BAR"));

        Map<String, Object> result = flow.generateChart("F006", 9L, "sales_by_customer");

        assertThat(result).containsEntry("chartType", "BAR");
        verify(sales).findByFactoryIdAndUploadId("F006", 9L);
        verify(sales, never()).findByUploadId(9L);
    }

    @Test
    void ownedLegacyUploadWithNoStaticRowsFails422() {
        SmartBIUploadFlowServiceImpl flow = service();
        ReflectionTestUtils.setField(flow, "postgresEnabled", false);
        when(legacyUploads.findByIdAndFactoryId(9L, "F006")).thenReturn(Optional.of(
                new com.cretas.aims.entity.smartbi.SmartBiExcelUpload()));
        when(sales.findByFactoryIdAndUploadId("F006", 9L))
                .thenReturn(List.<com.cretas.aims.entity.smartbi.SmartBiSalesData>of());

        assertThatThrownBy(() -> ReflectionTestUtils.invokeMethod(
                flow, "buildChartData", "F006", 9L,
                ExcelDataPersistenceService.DataType.SALES))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可分析数据")
                .hasFieldOrPropertyWithValue("code", 422);
    }
}
