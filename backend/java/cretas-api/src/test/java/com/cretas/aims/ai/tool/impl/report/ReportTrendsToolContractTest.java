package com.cretas.aims.ai.tool.impl.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.impl.ProcessingServiceImpl;
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
    @Mock private com.cretas.aims.repository.ProductionBatchRepository productionBatchRepository;
    @InjectMocks private ProcessingServiceImpl processingService;
    @InjectMocks private ProductionReportServiceImpl productionReportService;
    private ReportTrendsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        var processing = productionReportService.getClass().getDeclaredField("processingService");
        processing.setAccessible(true); processing.set(productionReportService, processingService);
        var reportService = new ReportServiceImpl(null, null, productionReportService, null, null);
        tool = new ReportTrendsTool();
        var service = ReportTrendsTool.class.getDeclaredField("reportService");
        service.setAccessible(true); service.set(tool, reportService);
        var mapper = com.cretas.aims.ai.tool.AbstractTool.class.getDeclaredField("objectMapper");
        mapper.setAccessible(true); mapper.set(tool, objectMapper);
        lenient().when(productionBatchRepository.calculateDailyOutput(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ONE);
        lenient().when(productionBatchRepository.calculateDailyYieldRate(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("95"));
        lenient().when(productionBatchRepository.calculateDailyCost(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(new BigDecimal("2"));
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

    @Test
    void dailyProductionWithExplicitYoyStillCompares() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode data = execute(Map.of("period", "week"), Map.of("userInput", "每天产量同比去年同期"));
        System.out.println("OBS daily-production-yoy=" + data.at("/data/comparison/comparisonType") + " base="
                + data.at("/data/comparison/basePeriodStart") + " current=" + data.at("/data/comparison/currentPeriodStart"));
        assertThat(data.at("/data/comparison/comparisonType").asText()).isEqualTo("YOY");
    }

    @Test
    void currentPeriodMissingDataReturnsNullWithoutSubtractFailure() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), null, null);
        JsonNode data = execute(Map.of("period", "month"), Map.of());
        System.out.println("OBS current-missing outputRate=" + data.at("/data/comparison/comparison/outputChangeRate")
                + " message=" + data.at("/data/comparison/comparison/outputChangeMessage"));
        assertThat(data.at("/data/comparison/comparison/outputChangeRate").isNull()).isTrue();
        assertThat(data.at("/data/comparison/comparison/outputChangeMessage").asText()).isEqualTo("本期无数据，无法计算环比");
    }

    @Test
    void provenancePassesRealOutputCostRateAndRejectsFabricatedRate() {
        var gate = new FactoryNumberProvenance();
        Map<String, Object> source = Map.of("output", new BigDecimal("12"), "cost", new BigDecimal("110"), "rate", new BigDecimal("20"));
        var pass = gate.enforce("report-trends", "本期产量12，成本110，环比增长20%。", null, source);
        var fake = gate.enforce("report-trends", "本期产量12，成本110，环比增长77%。", null, source);
        System.out.println("OBS provenance pass=" + pass.decision() + " fake=" + fake.decision() + " fakeUnsourced=" + fake.audit().unsourced().size());
        assertThat(pass.decision()).isEqualTo(FactoryNumberProvenance.Decision.PASS);
        assertThat(fake.decision()).isEqualTo(FactoryNumberProvenance.Decision.REDACTED);
        assertThat(fake.audit().unsourced()).hasSize(1);
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
