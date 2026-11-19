package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.dto.smartbi.ExcelParseResponse;
import com.cretas.aims.dto.smartbi.FieldMappingResult;
import com.cretas.aims.entity.smartbi.SmartBiChartTemplate;
import com.cretas.aims.entity.smartbi.enums.UploadStatus;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgFieldDefinition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.SmartBiExcelUploadRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiDynamicDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgFieldDefinitionRepository;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.service.smartbi.ChartTemplateService;
import com.cretas.aims.service.smartbi.DynamicAnalysisService;
import com.cretas.aims.service.smartbi.DynamicChartConfigBuilderService;
import com.cretas.aims.service.smartbi.DynamicDataPersistenceService;
import com.cretas.aims.service.smartbi.ExcelDataPersistenceService;
import com.cretas.aims.service.smartbi.ExcelDynamicParserService;
import com.cretas.aims.service.smartbi.LLMFieldMappingService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PROTO-YDWM B1/B2 (Java 路径): 味美 04 醒发蒸制表。
 * B1 数值列(温度/分钟/架次/小料kg)要能当指标, 编码/编号/产线号仍是维度, 与 Python field_classifier 口径一致。
 * B2 confirm 保存 422 的上传要被标成 FAILED (独立事务), 不再留在数据源下拉里当默认源。
 */
class SmartBIUploadMeasureAndFailureB1B2ContractTest {

    // ---------------- B1: DynamicDataPersistenceServiceImpl ----------------

    private final SmartBiPgFieldDefinitionRepository fieldDefs = mock(SmartBiPgFieldDefinitionRepository.class);

    private DynamicDataPersistenceServiceImpl persistence() {
        return new DynamicDataPersistenceServiceImpl(
                mock(SmartBiPgExcelUploadRepository.class),
                mock(SmartBiDynamicDataRepository.class),
                fieldDefs,
                mock(PythonSmartBIClient.class));
    }

    private Map<String, SmartBiPgFieldDefinition> backfill(Map<String, Object> sampleRow) {
        ReflectionTestUtils.invokeMethod(persistence(), "backfillMissingFieldDefs",
                7L, List.<SmartBiPgFieldDefinition>of(), sampleRow);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SmartBiPgFieldDefinition>> cap = ArgumentCaptor.forClass(List.class);
        verify(fieldDefs).saveAll(cap.capture());
        return cap.getValue().stream().collect(Collectors.toMap(SmartBiPgFieldDefinition::getOriginalName, d -> d));
    }

    @Test
    void numericColumnsWithoutMoneyKeywordsAreMeasures() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("日期", "2026-10-01");
        row.put("产品", "A");
        row.put("面团温度", 25.5);
        row.put("醒发时间(分钟)", 40);
        row.put("架次", 3);
        row.put("小料kg", 1.5);
        Map<String, SmartBiPgFieldDefinition> defs = backfill(row);

        for (String n : List.of("面团温度", "醒发时间(分钟)", "架次", "小料kg")) {
            assertThat(defs.get(n).getIsMeasure()).as(n + " 应是指标").isTrue();
            assertThat(defs.get(n).getIsTime()).as(n + " 不应是时间轴").isFalse();
        }
        // 对照: 真时间/维度列不被误当指标
        assertThat(defs.get("日期").getIsTime()).isTrue();
        assertThat(defs.get("日期").getIsMeasure()).isFalse();
        assertThat(defs.get("产品").getIsMeasure()).isFalse();
    }

    @Test
    void numericCodeColumnsStayDimensions() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("批次编码", 20261001);
        row.put("产线号", 3);
        row.put("订单编号", 998877);
        Map<String, SmartBiPgFieldDefinition> defs = backfill(row);
        for (String n : List.of("批次编码", "产线号", "订单编号")) {
            assertThat(defs.get(n).getIsMeasure()).as(n + " 不应是指标").isFalse();
            assertThat(defs.get(n).getIsDimension()).as(n + " 应是维度").isTrue();
        }
    }

    @Test
    void confirmedMappingPathAgreesWithBackfillPath() {
        DynamicDataPersistenceServiceImpl svc = persistence();
        FieldMappingResult duration = mapping("醒发时间(分钟)", "醒发时间(分钟)", "NUMERIC");
        FieldMappingResult code = mapping("产线号", "产线号", "NUMERIC");
        FieldMappingResult temp = mapping("面团温度", "面团温度", "NUMERIC");

        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isMeasure", duration)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isTimeField", duration)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isMeasure", temp)).isTrue();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isMeasure", code)).isFalse();
        assertThat((Boolean) ReflectionTestUtils.invokeMethod(svc, "isDimension", code)).isTrue();
    }

    private static FieldMappingResult mapping(String original, String standard, String dataType) {
        FieldMappingResult m = new FieldMappingResult();
        m.setOriginalColumn(original);
        m.setStandardField(standard);
        m.setDataType(dataType);
        return m;
    }

    // ---------------- B2: confirm 422 -> upload FAILED ----------------

    @Test
    void confirmWith422MarksUploadFailedInSeparateMarkerAndRethrows() {
        SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
        ChartTemplateService charts = mock(ChartTemplateService.class);
        ExcelDataPersistenceService persist = mock(ExcelDataPersistenceService.class);
        DynamicDataPersistenceService dynamic = mock(DynamicDataPersistenceService.class);
        DynamicAnalysisService analysis = mock(DynamicAnalysisService.class);
        SmartBIUploadFailureMarker marker = mock(SmartBIUploadFailureMarker.class);

        SmartBIUploadFlowServiceImpl flow = new SmartBIUploadFlowServiceImpl(
                mock(ExcelDynamicParserService.class), persist, charts,
                null, null, mock(LLMFieldMappingService.class), mock(DynamicChartConfigBuilderService.class));
        ReflectionTestUtils.setField(flow, "postgresEnabled", true);
        ReflectionTestUtils.setField(flow, "dynamicPersistenceService", dynamic);
        ReflectionTestUtils.setField(flow, "pgUploadRepository", uploads);
        ReflectionTestUtils.setField(flow, "excelUploadRepository", mock(SmartBiExcelUploadRepository.class));
        ReflectionTestUtils.setField(flow, "dynamicAnalysisService", analysis);
        ReflectionTestUtils.setField(flow, "uploadFailureMarker", marker);

        when(uploads.findById(9L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(9L).factoryId("F006").build()));
        SmartBiChartTemplate template = new SmartBiChartTemplate();
        template.setTemplateCode("T1");
        when(charts.matchBestTemplate(any(), any(), eq("F006"))).thenReturn(template);
        when(charts.getTemplatesByCategory(any())).thenReturn(List.of(template));
        when(persist.detectDataType(any())).thenReturn(ExcelDataPersistenceService.DataType.GENERAL);
        when(analysis.analyzeDynamic("F006", 9L, "general"))
                .thenReturn(new DynamicAnalysisService.DashboardResponse());

        ExcelParseResponse parse = new ExcelParseResponse();
        assertThatThrownBy(() -> flow.confirmAndPersist(
                "F006", 9L, parse, List.of(mock(FieldMappingResult.class)), "general"))
                .isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", 422);
        verify(marker).markFailed(eq("F006"), eq(9L), any());
    }

    @Test
    void markerSetsFailedForOwnUploadAndRefusesForeignUpload() {
        SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
        SmartBIUploadFailureMarker marker = new SmartBIUploadFailureMarker();
        ReflectionTestUtils.setField(marker, "pgUploadRepository", uploads);
        SmartBiPgExcelUpload own = SmartBiPgExcelUpload.builder()
                .id(9L).factoryId("F006").uploadStatus(UploadStatus.COMPLETED).build();
        SmartBiPgExcelUpload foreign = SmartBiPgExcelUpload.builder()
                .id(10L).factoryId("F007").uploadStatus(UploadStatus.COMPLETED).build();
        when(uploads.findById(9L)).thenReturn(Optional.of(own));
        when(uploads.findById(10L)).thenReturn(Optional.of(foreign));

        marker.markFailed("F006", 9L, "上传数据暂无可分析图表");
        marker.markFailed("F006", 10L, "x");

        assertThat(own.getUploadStatus()).isEqualTo(UploadStatus.FAILED);
        assertThat(own.getErrorMessage()).contains("暂无可分析图表");
        assertThat(foreign.getUploadStatus()).isEqualTo(UploadStatus.COMPLETED);
        verify(uploads, never()).save(foreign);
        verify(uploads).save(own);
    }
}
