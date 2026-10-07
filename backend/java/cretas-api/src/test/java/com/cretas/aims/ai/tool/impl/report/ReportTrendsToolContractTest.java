package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.service.ReportService;
import com.cretas.aims.service.report.impl.ProductionReportServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReportTrendsToolContractTest {

    @Mock private ReportService reportService;

    @Test
    void defaultMonthlyTrendIncludesMomComparisonWithTwoPeriods() throws Exception {
        ExposedTool tool = new ExposedTool();
        tool.setReportService(reportService);
        Map<String, Object> serviceResult = new HashMap<>();
        serviceResult.put("period1", Map.of("output", BigDecimal.TEN));
        serviceResult.put("period2", Map.of("output", new BigDecimal("12")));
        serviceResult.put("comparison", new HashMap<>(Map.of("outputChangeRate", new BigDecimal("20.0000"))));
        when(reportService.getTrendsDashboard(eq("F006"), eq("month"), eq("PRODUCTION"), eq(30)))
                .thenReturn(Map.of("trendData", java.util.List.of(Map.of("date", "2026-10-01", "value", BigDecimal.ONE))));
        when(reportService.getPeriodComparisonReport(eq("F006"), any(), any(), any(), any())).thenReturn(serviceResult);

        Map<String, Object> result = tool.run("F006", Map.of("period", "month", "metric", "PRODUCTION", "days", 30));

        assertThat(result).containsKey("comparison");
        assertThat(((Map<?, ?>) result.get("comparison")).get("comparisonType")).isEqualTo("MOM");
        verify(reportService).getPeriodComparisonReport(eq("F006"), any(), any(), any(), any());
    }

    @Test
    void todayDailySeriesDoesNotImplicitlyTriggerComparison() throws Exception {
        ExposedTool tool = new ExposedTool();
        tool.setReportService(reportService);
        when(reportService.getTrendsDashboard(anyString(), anyString(), anyString(), anyInt())).thenReturn(Map.of());

        Map<String, Object> result = tool.run("F006", Map.of("period", "today", "days", 1));

        assertThat(result).doesNotContainKey("comparison");
        verify(reportService, never()).getPeriodComparisonReport(anyString(), any(), any(), any(), any());
    }

    @Test
    void explicitYoyAndZeroBaseAreDistinguishedFromZeroGrowth() throws Exception {
        ExposedTool tool = new ExposedTool();
        tool.setReportService(reportService);
        when(reportService.getTrendsDashboard(anyString(), anyString(), anyString(), anyInt())).thenReturn(Map.of());
        Map<String, Object> comparison = new HashMap<>();
        comparison.put("outputChangeRate", null);
        comparison.put("costChangeRate", null);
        when(reportService.getPeriodComparisonReport(anyString(), any(), any(), any(), any()))
                .thenReturn(new HashMap<>(Map.of("comparison", comparison)));

        Map<String, Object> result = tool.run("F006", Map.of("period", "month", "comparisonType", "YOY"));

        Map<?, ?> outputComparison = (Map<?, ?>) result.get("comparison");
        assertThat(outputComparison.get("comparisonType")).isEqualTo("YOY");
        Map<?, ?> values = (Map<?, ?>) outputComparison.get("comparison");
        assertThat(values.get("outputChangeMessage")).isEqualTo("上期无数据，无法计算同比");
        assertThat(values.get("costChangeMessage")).isEqualTo("上期无数据，无法计算同比");
    }

    @Mock private ProductionPlanRepository productionPlanRepository;
    @InjectMocks private ProductionReportServiceImpl productionReportService;

    @Test
    void periodComparisonReturnsNullRateForZeroBase() {
        when(productionPlanRepository.calculateOutputBetweenDates(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(BigDecimal.ZERO, new BigDecimal("12"));
        when(productionPlanRepository.calculateTotalCostBetweenDates(anyString(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(null, new BigDecimal("5"));

        Map<String, Object> result = productionReportService.getPeriodComparisonReport(
                "F006", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 7),
                LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 30));

        Map<?, ?> comparison = (Map<?, ?>) result.get("comparison");
        assertThat(comparison.get("outputChangeRate")).isNull();
        assertThat(comparison.get("costChangeRate")).isNull();
    }

    static final class ExposedTool extends ReportTrendsTool {
        void setReportService(ReportService service) {
            try {
                java.lang.reflect.Field field = ReportTrendsTool.class.getDeclaredField("reportService");
                field.setAccessible(true);
                field.set(this, service);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }

        Map<String, Object> run(String factoryId, Map<String, Object> params) throws Exception {
            return doExecute(factoryId, params, Map.of());
        }
    }
}
