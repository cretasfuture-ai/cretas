package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.dto.report.CostVarianceReportDTO;
import com.cretas.aims.service.ReportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 🔒 AI 工具在成本差异不可比较时必须明说「无法比较：缺少独立标准成本」,
 * ⛔ 不让模型从 null 字段里自己补一个差异率。
 */
@DisplayName("AI 成本差异工具: 不可比较时回答「无法比较」")
class ReportCostVarianceToolNotComparableTest {

    private Map<String, Object> run(CostVarianceReportDTO dto) throws Exception {
        ReportService reportService = mock(ReportService.class);
        when(reportService.getCostVarianceReport(eq("F006"), any(), any())).thenReturn(dto);
        ReportCostVarianceTool tool = new ReportCostVarianceTool();
        ReflectionTestUtils.setField(tool, "reportService", reportService);
        return tool.doExecute("F006", new HashMap<>(), new HashMap<>());
    }

    @Test
    @DisplayName("comparable=false: message 说无法比较, 并且不含任何百分比")
    void notComparableSaysSoAndQuotesNoRate() throws Exception {
        CostVarianceReportDTO dto = CostVarianceReportDTO.builder()
                .comparable(false)
                .comparabilityReason(CostVarianceReportDTO.NO_INDEPENDENT_STANDARD_REASON)
                .totalActualCost(new BigDecimal("1000.00"))
                .batchCount(1)
                .build();

        Map<String, Object> result = run(dto);

        assertThat(result.get("comparable")).isEqualTo(false);
        assertThat((String) result.get("message"))
                .contains("无法比较")
                .contains("缺少独立标准成本")
                .doesNotContain("%")
                .doesNotContain("5.26");
    }

    @Test
    @DisplayName("阴性对照: 有独立标准(comparable=true 且差异率非空)时不发「无法比较」")
    void comparableReportDoesNotClaimIncomparable() throws Exception {
        CostVarianceReportDTO dto = CostVarianceReportDTO.builder()
                .comparable(true)
                .totalActualCost(new BigDecimal("1000.00"))
                .totalVarianceRate(new BigDecimal("3.00"))
                .batchCount(1)
                .build();

        Map<String, Object> result = run(dto);

        assertThat(result.get("comparable")).isEqualTo(true);
        assertThat(result.get("message")).isNull();
    }
}
