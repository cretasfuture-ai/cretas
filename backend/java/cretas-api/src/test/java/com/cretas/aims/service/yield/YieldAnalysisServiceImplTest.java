package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.ProcessYieldAggDTO;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.service.yield.impl.YieldAnalysisServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class YieldAnalysisServiceImplTest {

    @Mock
    ProductionReportRepository reportRepo;

    @Mock
    com.cretas.aims.service.processsecret.ProcessSecretAccessService processSecretAccessService;

    @InjectMocks
    YieldAnalysisServiceImpl service;

    /** Build a result row matching the native query projection keys. */
    private static Map<String, Object> row(String processName,
                                           String totalInput, String totalOutput,
                                           String inputUnit, String outputUnit,
                                           Long batchCount) {
        Map<String, Object> m = new HashMap<>();
        m.put("process_name", processName);
        m.put("total_input",  new BigDecimal(totalInput));
        m.put("total_output", new BigDecimal(totalOutput));
        m.put("input_unit",   inputUnit);
        m.put("output_unit",  outputUnit);
        m.put("batch_count",  batchCount);
        return m;
    }

    @Test
    void aggregate_sameUnit_computesConversionAndWastage() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection()))
                .thenReturn(List.of(row("处理", "300", "260", "kg", "kg", 2L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess(
                "F006",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getProcessName()).isEqualTo("处理");
        assertThat(dto.getUnitComparable()).isTrue();
        assertThat(dto.getConversionRate()).isNotNull();
        assertThat(dto.getConversionRate()).isEqualByComparingTo("86.7");
        assertThat(dto.getWastageRate()).isNotNull();
        assertThat(dto.getWastageRate()).isEqualByComparingTo("13.3");
        assertThat(dto.getBatchCount()).isEqualTo(2);
    }

    @Test
    void aggregate_crossUnit_conversionNull() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection()))
                .thenReturn(List.of(row("末道", "998", "3184", "kg", "盒", 1L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess(
                "F006",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 12, 31),
                null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getUnitComparable()).isFalse();
        assertThat(dto.getConversionRate()).isNull();
        assertThat(dto.getWastageRate()).isNull();
    }

    @Test
    void aggregate_nullOutputUnit_treatedAsSameUnit() {
        // 工序未配置 output_unit (绝大多数情况) → 视为与投入同单位 → 出成率应正常计算, 不显 "—"
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection()))
                .thenReturn(List.of(row("演示工序1", "998", "935.5", "kg", null, 1L)));

        List<ProcessYieldAggDTO> result = service.aggregateByProcess("F001", null, null, null);

        assertThat(result).hasSize(1);
        ProcessYieldAggDTO dto = result.get(0);
        assertThat(dto.getUnitComparable()).isTrue();
        assertThat(dto.getConversionRate()).isEqualByComparingTo("93.7"); // 935.5/998*100=93.73→93.7
        assertThat(dto.getWastageRate()).isEqualByComparingTo("6.3");      // 62.5/998*100=6.26→6.3
    }

    @Test
    void aggregate_nullParams_convertedToSentinels() {
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection()))
                .thenReturn(Collections.emptyList());

        List<ProcessYieldAggDTO> result = service.aggregateByProcess("F001", null, null, null);

        assertThat(result).isEmpty();
        verify(reportRepo).aggregateYieldByProcess(
                eq("F001"),
                eq(LocalDate.of(1900, 1, 1)),
                eq(LocalDate.of(2999, 12, 31)),
                eq(""),
                eq(List.of("__none__")));
    }

    // ---- 工艺保密 (Steve 第三问 A: 跨批汇总只给负责人和授权人) ----

    @Test
    void aggregate_excludesProtectedProductsTheCallerCannotSee() {
        when(processSecretAccessService.protectedProductTypeIds("F006")).thenReturn(java.util.Set.of("P17", "P18"));
        when(processSecretAccessService.filterPermitted(any(), eq("F006"), anyList(),
                eq(com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL), eq(false), anyString()))
                .thenReturn(List.of("P18"));
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.aggregateByProcess("F006", null, null, null);

        verify(reportRepo).aggregateYieldByProcess(eq("F006"), any(), any(), eq(""), eq(List.of("P17")));
    }

    @Test
    void aggregate_namedProductIsLeftToTheRouteGuard() {
        when(processSecretAccessService.protectedProductTypeIds("F006")).thenReturn(java.util.Set.of("P17"));
        when(reportRepo.aggregateYieldByProcess(anyString(), any(), any(), anyString(), anyCollection()))
                .thenReturn(Collections.emptyList());

        service.aggregateByProcess("F006", null, null, "P1");

        verify(reportRepo).aggregateYieldByProcess(eq("F006"), any(), any(), eq("P1"), eq(List.of("__none__")));
    }
}
