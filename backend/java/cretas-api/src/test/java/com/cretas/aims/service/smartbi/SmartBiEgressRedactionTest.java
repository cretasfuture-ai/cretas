package com.cretas.aims.service.smartbi;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.config.smartbi.FieldMappingDictionary;
import com.cretas.aims.dto.smartbi.chart.AdaptiveChartResponse;
import com.cretas.aims.dto.smartbi.chart.DataFeatures;
import com.cretas.aims.repository.smartbi.SmartBiExcelUploadRepository;
import com.cretas.aims.service.smartbi.chart.ChartFusionService;
import com.cretas.aims.service.smartbi.chart.ChartSufficiencyEvaluator;
import com.cretas.aims.service.smartbi.chart.DataFeatureExtractor;
import com.cretas.aims.service.smartbi.chart.impl.AdaptiveChartGeneratorImpl;
import com.cretas.aims.service.smartbi.chart.impl.ChartSufficiencyEvaluatorImpl;
import com.cretas.aims.service.smartbi.impl.LLMFieldMappingServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SmartBI 上传 Excel 后的三条外发: 字段映射 (列名 + 样本值)、自适应图表 (前 10 行)、图表评估 (列名)。
 * 联系人 / 客户列的样本值换代号, 手机 / 地址列的值不发; 生成的图表里换回真名。走各自的公开入口。
 */
class SmartBiEgressRedactionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void fieldMappingSamplesAreRedactedByColumnMeaning() {
        DashScopeClient llm = mock(DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        FieldMappingDictionary dictionary = mock(FieldMappingDictionary.class);
        when(dictionary.findStandardField(anyString())).thenReturn(Optional.empty());
        LLMFieldMappingServiceImpl service = new LLMFieldMappingServiceImpl();
        ReflectionTestUtils.setField(service, "dashScopeClient", llm);
        ReflectionTestUtils.setField(service, "fieldMappingDictionary", dictionary);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), prompt.capture())).thenReturn("[]");

        service.analyzeFields(List.of(
                new LLMFieldMappingService.FieldInfo("联系人", "STRING", List.of("王建国", "钱大海"), 2),
                new LLMFieldMappingService.FieldInfo("客户名称", "STRING", List.of("老街坊餐饮"), 1),
                new LLMFieldMappingService.FieldInfo("手机", "STRING", List.of("13812345678"), 1),
                new LLMFieldMappingService.FieldInfo("送货地址", "STRING", List.of("上海市松江区某路 88 号"), 1),
                new LLMFieldMappingService.FieldInfo("产品", "STRING", List.of("五香虎皮猪肘"), 1)));

        assertThat(prompt.getValue()).doesNotContain("王建国", "钱大海", "老街坊", "13812345678", "松江")
                .contains("联系人", "客户名称", "五香虎皮猪肘");
    }

    @Test
    void adaptiveChartRowsAreRedactedAndTheChartShowsRealNames() throws Exception {
        DashScopeClient llm = mock(DashScopeClient.class);
        AdaptiveChartGeneratorImpl generator = new AdaptiveChartGeneratorImpl(mock(DataFeatureExtractor.class),
                mock(ChartSufficiencyEvaluator.class), mock(ChartFusionService.class), mock(ChartTemplateService.class),
                mock(SmartBiExcelUploadRepository.class), llm, mapper);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), prompt.capture())).thenAnswer(inv -> {
            String p = inv.getArgument(1);
            String sales = p.replaceAll("(?s).*?(\\[人员[A-Z]+]).*", "$1");
            return "{\"xAxis\":{\"data\":[\"" + sales + "\"]},\"series\":[{\"data\":[12800]}]}";
        });
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("业务员", "周晓明");
        row.put("客户", "张记食品有限公司");
        row.put("联系电话", "13812345678");
        row.put("销售额", 12800);
        DataFeatures features = DataFeatures.builder().rowCount(1).columnCount(4)
                .columns(List.of("业务员", "客户", "联系电话", "销售额")).numericColumns(List.of("销售额"))
                .categoricalColumns(List.of("业务员", "客户")).timeColumns(List.of()).build();

        AdaptiveChartResponse.GeneratedChart chart = generator.generateChart(List.of(row), features, "bar", "业务员业绩");

        assertThat(prompt.getValue()).doesNotContain("周晓明", "张记食品", "13812345678").contains("12800");
        assertThat(mapper.writeValueAsString(chart.getEchartsOption())).contains("周晓明").doesNotContain("[人员");
    }

    @Test
    void sufficiencyEvaluationRedactsPersonNamedColumns() {
        DashScopeClient llm = mock(DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        ChartSufficiencyEvaluatorImpl evaluator = new ChartSufficiencyEvaluatorImpl(llm, mapper);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), prompt.capture())).thenReturn("{\"sufficient\":true,\"sufficiencyScore\":80}");
        DataFeatures features = DataFeatures.builder().rowCount(3).columnCount(3)
                .columns(List.of("月份", "李经理", "王师傅")).numericColumns(List.of("李经理", "王师傅"))
                .categoricalColumns(List.of("月份")).timeColumns(List.of()).build();

        evaluator.evaluateInitial(List.of(), features);

        assertThat(prompt.getValue()).doesNotContain("李经理", "王师傅");
    }
}
