package com.cretas.aims.ai.tool.impl.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.repository.ProductionPlanRepository;
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
        assertThat(data.at("/data/comparison/comparison/costChangeRate").decimalValue()).isEqualByComparingTo("10.0000");
        assertThat(data.at("/data/comparison/period1/output").decimalValue()).isEqualByComparingTo("10");
        assertThat(data.at("/data/comparison/period2/output").decimalValue()).isEqualByComparingTo("12");
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
    void provenancePassesRealOutputCostRateAndRejectsFabricatedRate() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode data = execute(Map.of("period", "month"), Map.of());
        var gate = new FactoryNumberProvenance();
        Object source = objectMapper.convertValue(data.get("data"), Object.class);
        var pass = gate.enforce("REPORT_TRENDS", "本期产量12，上期产量10，环比增长20%；本期成本110，上期成本100，环比增长10%。", null, source);
        var fake = gate.enforce("REPORT_TRENDS", "本期产量12，成本110，环比增长77%。", null, source);
        System.out.println("OBS provenance pass=" + pass.decision() + " fake=" + fake.decision() + " fakeUnsourced=" + fake.audit().unsourced().size());
        assertThat(pass.decision()).isEqualTo(FactoryNumberProvenance.Decision.PASS);
        assertThat(pass.audit().counted()).isEqualTo(6);
        assertThat(fake.decision()).isEqualTo(FactoryNumberProvenance.Decision.REDACTED);
        assertThat(fake.audit().unsourced()).hasSize(1);
    }

    @Test
    void explicitUserYoyOverridesExtractedMomParameter() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        assertThat(execute(Map.of("period", "month", "comparisonType", "MOM"),
                Map.of("userInput", "本月同比去年同期")).at("/data/comparison/comparisonType").asText()).isEqualTo("YOY");
    }

    @Test
    void explicitUserMomOverridesExtractedYoyParameter() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        assertThat(execute(Map.of("period", "month", "comparisonType", "YOY"),
                Map.of("userInput", "本月环比上期")).at("/data/comparison/comparisonType").asText()).isEqualTo("MOM");
    }

    @Test
    void todayComparesOneDayToYesterdayWithMonthPositive() throws Exception {
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode today = execute(Map.of("period", "today"), Map.of("userInput", "今天环比"));
        assertThat(today.at("/data/comparison/currentPeriodStart").asText()).isEqualTo(LocalDate.now().toString());
        assertThat(today.at("/data/comparison/basePeriodStart").asText()).isEqualTo(LocalDate.now().minusDays(1).toString());
        assertThat(today.at("/data/comparison/basePeriodEnd").asText()).isEqualTo(LocalDate.now().minusDays(1).toString());
        reset(productionPlanRepository);
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode month = execute(Map.of("period", "month"), Map.of());
        assertThat(month.at("/data/comparison/basePeriodStart").asText()).isEqualTo(LocalDate.now().withDayOfMonth(1).minusMonths(1).toString());
    }

    @Test
    void customSevenDayRangeComparesImmediatelyPreviousSevenDays() throws Exception {
        stubPeriods(new BigDecimal("8"), new BigDecimal("100"), new BigDecimal("3"), new BigDecimal("110"), LocalDate.of(2026, 9, 30));
        JsonNode data = execute(Map.of("startDate", "2026-09-24", "endDate", "2026-09-30"), Map.of());
        assertThat(data.at("/data/comparison/basePeriodStart").asText()).isEqualTo("2026-09-17");
        assertThat(data.at("/data/comparison/basePeriodEnd").asText()).isEqualTo("2026-09-23");
        assertThat(data.at("/data/comparison/comparison/outputChangeRate").decimalValue()).isEqualByComparingTo("-62.5000");
        verify(productionPlanRepository).calculateOutputBetweenDates(eq(FACTORY),
                eq(LocalDate.of(2026, 9, 17).atStartOfDay()), eq(LocalDate.of(2026, 9, 23).atTime(23, 59, 59)));
    }

    @Test
    void explicitDailyOnlyWinsWithoutFabricatingComparisonAndCostHasPositiveComparison() throws Exception {
        JsonNode daily = execute(Map.of("period", "month", "comparisonType", "YOY"),
                Map.of("userInput", "只要上期日序列，不比较"));
        assertThat(daily.at("/data/comparison").isMissingNode()).isTrue();
        verify(productionPlanRepository, never()).calculateOutputBetweenDates(anyString(), any(), any());
        assertThat(daily.at("/data/data/productionTrend").isMissingNode()).isFalse();
        stubPeriods(new BigDecimal("10"), new BigDecimal("100"), new BigDecimal("12"), new BigDecimal("110"));
        JsonNode cost = execute(Map.of("period", "month", "metric", "COST"), Map.of("userInput", "成本环比"));
        assertThat(cost.at("/data/comparison/comparison/costChangeRate").decimalValue()).isEqualByComparingTo("10.0000");
    }

    private JsonNode execute(Map<String, Object> params, Map<String, Object> extra) throws Exception {
        var context = new java.util.HashMap<String, Object>(Map.of("factoryId", FACTORY, "userId", 1309L)); context.putAll(extra);
        JsonNode result = objectMapper.readTree(tool.execute(ToolCall.of("test-report", "report_trends", objectMapper.writeValueAsString(params)), context));
        System.out.println("F_TOOL_RESULT=" + result);
        return result;
    }

    @Test
    void missingBothPeriodsExplainsMissingBaseWithoutInventingZero() throws Exception {
        stubPeriods(null, null, null, null);
        JsonNode data = execute(Map.of("period", "month"), Map.of());
        assertThat(data.at("/data/comparison/period1/output").isNull()).isTrue();
        assertThat(data.at("/data/comparison/period2/output").isNull()).isTrue();
        assertThat(data.at("/data/comparison/comparison/outputChangeRate").isNull()).isTrue();
        assertThat(data.at("/data/comparison/comparison/outputChangeMessage").asText()).isEqualTo("上期无数据，无法计算环比");
    }
    private void stubPeriods(BigDecimal baseOutput, BigDecimal baseCost, BigDecimal currentOutput, BigDecimal currentCost) {
        stubPeriods(baseOutput, baseCost, currentOutput, currentCost, LocalDate.now());
    }
    private void stubPeriods(BigDecimal baseOutput, BigDecimal baseCost, BigDecimal currentOutput, BigDecimal currentCost, LocalDate currentEnd) {
        when(productionPlanRepository.calculateOutputBetweenDates(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenAnswer(call -> call.<LocalDateTime>getArgument(2).toLocalDate().equals(currentEnd) ? currentOutput : baseOutput);
        when(productionPlanRepository.calculateTotalCostBetweenDates(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenAnswer(call -> call.<LocalDateTime>getArgument(2).toLocalDate().equals(currentEnd) ? currentCost : baseCost);
    }
}
