package com.cretas.aims.ai.tool.impl.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.impl.ReportServiceImpl;
import com.cretas.aims.service.report.impl.ProductionReportServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportTrendsToolContractTest {
    private static final String FACTORY = "F006";
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private ProcessingService processingService;
    @InjectMocks private ProductionReportServiceImpl productionReportService;
    private ReportTrendsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        var reportService = new ReportServiceImpl(null, null, productionReportService, null, null);
        tool = new ReportTrendsTool();
        var service = ReportTrendsTool.class.getDeclaredField("reportService");
        service.setAccessible(true); service.set(tool, reportService);
        var mapper = com.cretas.aims.ai.tool.AbstractTool.class.getDeclaredField("objectMapper");
        mapper.setAccessible(true); mapper.set(tool, objectMapper);
        when(processingService.getTrendAnalysis(eq(FACTORY), anyString(), anyInt())).thenReturn(Map.of("trendData", java.util.List.of()));
    }

    @Test
    void realExecuteCalculatesPositiveMomFromRepositoryValues() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode data = execute(Map.of("period", "month"), Map.of());
        assertThat(data.at("/data/comparison/comparisonType").asText()).isEqualTo("MOM");
        assertThat(data.at("/data/comparison/comparison/outputChangeRate").decimalValue()).isEqualByComparingTo("20.0000");
    }

    @Test
    void zeroOrMissingBaseProducesNullAndLocalizedReason() throws Exception {
        stubPeriods(BigDecimal.ZERO, null, new BigDecimal("12"), new BigDecimal("5"));
        JsonNode data = execute(Map.of("period", "month"), Map.of());
        assertThat(data.at("/data/comparison/comparison/outputChangeRate").isNull()).isTrue();
        assertThat(data.at("/data/comparison/comparison/costChangeRate").isNull()).isTrue();
        assertThat(data.at("/data/comparison/comparison/outputChangeMessage").asText()).isEqualTo("上期无数据，无法计算环比");
    }

    @Test
    void userInputYoyUsesLastYearAndPassesBaseThenCurrent() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode data = execute(Map.of("period", "month"), Map.of("userInput", "本月同比去年同期"));
        assertThat(data.at("/data/comparison/comparisonType").asText()).isEqualTo("YOY");
        assertThat(data.at("/data/comparison/comparison/outputChangeRate").decimalValue()).isEqualByComparingTo("20.0000");
        verify(productionPlanRepository).calculateOutputBetweenDates(eq(FACTORY),
                eq(LocalDate.now().withDayOfMonth(1).minusYears(1).atStartOfDay()),
                eq(LocalDate.now().minusYears(1).atTime(23, 59, 59)));
    }

    @Test
    void dailySequenceDoesNotCompareButPlainWeekDoes() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        assertThat(execute(Map.of("period", "week"), Map.of("userInput", "最近7天每日走势")).at("/data/comparison").isMissingNode()).isTrue();
        reset(productionPlanRepository); stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        assertThat(execute(Map.of("period", "week"), Map.of()).at("/data/comparison/comparisonType").asText()).isEqualTo("MOM");
    }

    private JsonNode execute(Map<String, Object> params, Map<String, Object> extra) throws Exception {
        var context = new java.util.HashMap<String, Object>(Map.of("factoryId", FACTORY, "userId", 1309L)); context.putAll(extra);
        return objectMapper.readTree(tool.execute(ToolCall.of("test-report", "report_trends", objectMapper.writeValueAsString(params)), context));
    }
    private void stubPeriods(BigDecimal baseOutput, BigDecimal baseCost, BigDecimal currentOutput, BigDecimal currentCost) {
        when(productionPlanRepository.calculateOutputBetweenDates(anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(baseOutput, currentOutput);
        when(productionPlanRepository.calculateTotalCostBetweenDates(anyString(), any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(baseCost, currentCost);
    }
}
