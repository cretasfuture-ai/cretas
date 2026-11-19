package com.cretas.aims.service.smartbi.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.dto.smartbi.FieldMappingResult;
import com.cretas.aims.entity.smartbi.postgres.SmartBiDynamicData;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgExcelUpload;
import com.cretas.aims.entity.smartbi.postgres.SmartBiPgFieldDefinition;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.smartbi.SmartBiExcelUploadRepository;
import com.cretas.aims.repository.smartbi.SmartBiFinanceDataRepository;
import com.cretas.aims.repository.smartbi.SmartBiSalesDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiDynamicDataRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgExcelUploadRepository;
import com.cretas.aims.repository.smartbi.postgres.SmartBiPgFieldDefinitionRepository;
import com.cretas.aims.service.smartbi.ChartTemplateService;
import com.cretas.aims.service.smartbi.DynamicAnalysisService;
import com.cretas.aims.service.smartbi.DynamicChartConfigBuilderService;
import com.cretas.aims.service.smartbi.DynamicDataPersistenceService;
import com.cretas.aims.service.smartbi.ExcelDataPersistenceService;
import com.cretas.aims.service.smartbi.ExcelDynamicParserService;
import com.cretas.aims.service.smartbi.LLMFieldMappingService;
import com.cretas.aims.entity.smartbi.SmartBiChartTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 味美 FOOD_0000_001 11-19 实测 B1/B2。
 *
 * B1: 字段映射认不出的列 (醒发分钟、温度℃、停机分钟、平均肉温、小料kg...) 入库时三个角色全 false,
 *     整表没有指标 → 保存 422「上传数据暂无可分析图表」。样本是 04 醒发蒸制 xlsx 前几行的真实形状,
 *     列名 / 映射形状照 prod smart_bi_pg_field_definitions (upload 5320/5327) 读出的。
 * B2: 保存失败后上传仍是 COMPLETED → 进 AI 问数据下拉并成为默认数据源。
 */
class WeimeiUploadFieldRoleContractTest {

    private static final String[] PROOF_COLS = {
            "日期", "批号", "车间", "品名", "品类", "趟次", "进醒发时间", "出蒸时间", "醒发分钟", "温度℃", "湿度%", "备注"};

    private static List<Map<String, Object>> proofingRows() {
        Object[][] raw = {
                {"2026-10-01", "261001", "发酵", "PANE CINESE", "馒头", 1, "05:54", "06:44", 50, 39.7, 88.5, null},
                {"2026-10-01", "261001", "发酵", "PANE CINESE", "馒头", 2, "06:10", "07:00", 50, 39.4, 81.4, null},
                {"2026-10-01", "261001", "发酵", "PANE CINESE", "馒头", 3, "06:24", "07:14", 50, 40, 79.8, null},
                {"2026-10-02", "261002", "发酵", "BAO", "包子", 1, "05:50", "06:45", 55, 38.9, 85.0, "换模"},
        };
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : raw) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (int i = 0; i < PROOF_COLS.length; i++) m.put(PROOF_COLS[i], r[i]);
            rows.add(m);
        }
        return rows;
    }

    /** 前端确认保存时传回的映射: 只有日期被认出, 其余列 standardField / dataType 都为空 (prod 形状)。 */
    private static List<FieldMappingResult> confirmedMappings() {
        return Arrays.stream(PROOF_COLS).map(c -> {
            FieldMappingResult m = new FieldMappingResult();
            m.setOriginalColumn(c);
            if ("日期".equals(c)) m.setStandardField("time_period");
            return m;
        }).collect(Collectors.toList());
    }

    private static Map<String, SmartBiPgFieldDefinition> byName(List<SmartBiPgFieldDefinition> defs) {
        return defs.stream().collect(Collectors.toMap(SmartBiPgFieldDefinition::getOriginalName, Function.identity()));
    }

    // ---------- FieldRoleInference ----------

    @Test
    void unmappedNumericColumnsBecomeMeasuresAndLowCardinalityTextBecomesDimensions() {
        List<SmartBiPgFieldDefinition> defs = Arrays.stream(PROOF_COLS)
                .map(c -> SmartBiPgFieldDefinition.builder().originalName(c)
                        .isMeasure(false).isDimension(false).isTime("日期".equals(c)).build())
                .collect(Collectors.toList());

        FieldRoleInference.refine(defs, FieldRoleInference.valuesByColumn(proofingRows()));
        Map<String, SmartBiPgFieldDefinition> d = byName(defs);

        for (String m : List.of("醒发分钟", "温度℃", "湿度%", "趟次")) {
            assertThat(d.get(m).getIsMeasure()).as(m).isTrue();
            assertThat(d.get(m).getFieldType()).as(m).isEqualTo("NUMERIC");
        }
        for (String dim : List.of("车间", "品名", "品类")) {
            assertThat(d.get(dim).getIsDimension()).as(dim).isTrue();
        }
        // 批号取值全是数字, 但是编号, 不能被求和成指标
        assertThat(d.get("批号").getIsMeasure()).isFalse();
        assertThat(d.get("批号").getIsDimension()).isTrue();
        // 时刻、备注不当分组维度, 也不是指标
        for (String none : List.of("进醒发时间", "出蒸时间", "备注")) {
            assertThat(d.get(none).getIsMeasure()).as(none).isFalse();
            assertThat(d.get(none).getIsDimension()).as(none).isFalse();
        }
        // 日期已是时间, 不动
        assertThat(d.get("日期").getIsTime()).isTrue();
        assertThat(d.get("日期").getIsMeasure()).isFalse();
    }

    @Test
    void alreadyClassifiedColumnsAreNeverOverridden() {
        SmartBiPgFieldDefinition dim = SmartBiPgFieldDefinition.builder()
                .originalName("趟次").isDimension(true).isMeasure(false).isTime(false).build();
        List<SmartBiPgFieldDefinition> changed = FieldRoleInference.refine(
                List.of(dim), Map.of("趟次", List.of(1, 2, 3)));
        assertThat(changed).isEmpty();
        assertThat(dim.getIsMeasure()).isFalse();
    }

    @Test
    void stoppageReasonIsADimensionEvenThoughItContains说明() {
        SmartBiPgFieldDefinition reason = SmartBiPgFieldDefinition.builder()
                .originalName("停机原因/说明").isDimension(false).isMeasure(false).isTime(false).build();
        FieldRoleInference.refine(List.of(reason),
                Map.of("停机原因/说明", List.of("面皮输送带", "伺服报警", "面皮输送带", "切刀")));
        assertThat(reason.getIsDimension()).isTrue();
    }

    // ---------- 接线: saveFieldDefinitions (确认保存路径的真实入口) ----------

    @Test
    void confirmPathSaveFieldDefinitionsInfersRolesFromPersistedRows() {
        SmartBiPgExcelUploadRepository uploadRepo = mock(SmartBiPgExcelUploadRepository.class);
        SmartBiDynamicDataRepository dataRepo = mock(SmartBiDynamicDataRepository.class);
        SmartBiPgFieldDefinitionRepository fieldRepo = mock(SmartBiPgFieldDefinitionRepository.class);
        DynamicDataPersistenceServiceImpl svc = new DynamicDataPersistenceServiceImpl(
                uploadRepo, dataRepo, fieldRepo, mock(PythonSmartBIClient.class));

        List<SmartBiDynamicData> persisted = new ArrayList<>();
        for (Map<String, Object> r : proofingRows()) {
            SmartBiDynamicData row = new SmartBiDynamicData();
            row.setRowData(r);
            persisted.add(row);
        }
        when(dataRepo.findByUploadIdOrderByRowIndex(eq(5331L), any(Pageable.class)))
                .thenReturn(new PageImpl<>(persisted));
        when(fieldRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        List<SmartBiPgFieldDefinition> saved = svc.saveFieldDefinitions(5331L, confirmedMappings());
        Map<String, SmartBiPgFieldDefinition> d = byName(saved);

        // 修复前: 只有日期有角色, 一个指标都没有 → 保存 422
        long measures = saved.stream().filter(f -> Boolean.TRUE.equals(f.getIsMeasure())).count();
        assertThat(measures).isGreaterThanOrEqualTo(3);
        assertThat(d.get("醒发分钟").getIsMeasure()).isTrue();
        assertThat(d.get("温度℃").getIsMeasure()).isTrue();
        assertThat(d.get("品名").getIsDimension()).isTrue();
    }

    @Test
    void firstUploadPathFallsBackToMappingSamplesWhenRowsNotYetPersisted() {
        SmartBiDynamicDataRepository dataRepo = mock(SmartBiDynamicDataRepository.class);
        SmartBiPgFieldDefinitionRepository fieldRepo = mock(SmartBiPgFieldDefinitionRepository.class);
        DynamicDataPersistenceServiceImpl svc = new DynamicDataPersistenceServiceImpl(
                mock(SmartBiPgExcelUploadRepository.class), dataRepo, fieldRepo, mock(PythonSmartBIClient.class));
        when(dataRepo.findByUploadIdOrderByRowIndex(anyLong(), any(Pageable.class))).thenReturn(Page0.empty());
        when(fieldRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));

        FieldMappingResult minutes = new FieldMappingResult();
        minutes.setOriginalColumn("停机分钟");
        minutes.setUniqueValues(List.of("0", "273", "45"));
        List<SmartBiPgFieldDefinition> saved = svc.saveFieldDefinitions(5320L, List.of(minutes));
        assertThat(saved.get(0).getIsMeasure()).isTrue();
    }

    private static com.cretas.aims.dto.smartbi.ExcelParseResponse parseResponse() {
        com.cretas.aims.dto.smartbi.ExcelParseResponse r = new com.cretas.aims.dto.smartbi.ExcelParseResponse();
        r.setRowCount(4);
        return r;
    }

    private static final class Page0 {
        static org.springframework.data.domain.Page<SmartBiDynamicData> empty() {
            return new PageImpl<>(List.of());
        }
    }

    // ---------- B2: 保存失败要标 FAILED ----------

    @Test
    void confirmFailureMarksUploadFailedSoItLeavesTheDataSourceDropdown() {
        ExcelDynamicParserService parser = mock(ExcelDynamicParserService.class);
        ChartTemplateService charts = mock(ChartTemplateService.class);
        DynamicDataPersistenceService dynamicPersistence = mock(DynamicDataPersistenceService.class);
        SmartBiPgExcelUploadRepository uploads = mock(SmartBiPgExcelUploadRepository.class);
        DynamicAnalysisService dynamic = mock(DynamicAnalysisService.class);
        SmartBIUploadFlowServiceImpl flow = spy(new SmartBIUploadFlowServiceImpl(
                parser, mock(ExcelDataPersistenceService.class), charts,
                mock(SmartBiSalesDataRepository.class), mock(SmartBiFinanceDataRepository.class),
                mock(LLMFieldMappingService.class), mock(DynamicChartConfigBuilderService.class)));
        ReflectionTestUtils.setField(flow, "postgresEnabled", true);
        ReflectionTestUtils.setField(flow, "dynamicPersistenceService", dynamicPersistence);
        ReflectionTestUtils.setField(flow, "pgUploadRepository", uploads);
        ReflectionTestUtils.setField(flow, "excelUploadRepository", mock(SmartBiExcelUploadRepository.class));
        ReflectionTestUtils.setField(flow, "dynamicAnalysisService", dynamic);

        when(uploads.findById(5331L)).thenReturn(Optional.of(
                SmartBiPgExcelUpload.builder().id(5331L).factoryId("FOOD_0000_001").build()));
        SmartBiChartTemplate template = new SmartBiChartTemplate();
        template.setTemplateCode("GENERAL_BAR");
        doReturn(List.of(template)).when(flow).recommendTemplates(eq("FOOD_0000_001"), any());
        // 没有图 → 422
        when(dynamic.analyzeDynamic(eq("FOOD_0000_001"), eq(5331L), anyString()))
                .thenReturn(new DynamicAnalysisService.DashboardResponse());

        assertThatThrownBy(() -> flow.confirmAndPersist(
                "FOOD_0000_001", 5331L, parseResponse(), confirmedMappings(), "GENERAL"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("暂无可分析图表");

        verify(dynamicPersistence).markUploadFailed(eq(5331L), contains("暂无可分析图表"));
    }
}
