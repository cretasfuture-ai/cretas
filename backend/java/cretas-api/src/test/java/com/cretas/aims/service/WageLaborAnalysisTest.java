package com.cretas.aims.service;

import com.cretas.aims.entity.PayrollRecord;
import com.cretas.aims.repository.PayrollRecordRepository;
import com.cretas.aims.repository.PieceRateRuleRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.WorkerDailyEfficiencyRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class WageLaborAnalysisTest {
    private final PayrollRecordRepository payroll=mock(PayrollRecordRepository.class);
    private final WorkerDailyEfficiencyRepository efficiency=mock(WorkerDailyEfficiencyRepository.class);
    private final WageCalculationService service=new WageCalculationService(mock(PieceRateRuleRepository.class),payroll,efficiency,mock(UserRepository.class));
    private final LocalDate start=LocalDate.of(2026,10,1), end=LocalDate.of(2026,10,31);
    private PayrollRecord row(Long id) {var r=new PayrollRecord();r.setWorkerId(id);r.setTotalWage(new BigDecimal("200"));r.setTotalPieceCount(20);return r;}

    @Test void usesDistinctWorkersAcrossTheSelectedPayrollPeriodInsteadOfTheFinalDay() {
        when(payroll.findByDateRange("T",start,end)).thenReturn(List.of(row(1L),row(1L),row(2L)));
        when(payroll.sumTotalWageByPeriod("T",start,end)).thenReturn(new BigDecimal("600"));
        when(payroll.sumPieceCountByPeriod("T",start,end)).thenReturn(60);
        var result=service.analyzeLaborCost("T",start,end);
        assertEquals(2L,result.get("workerCount"));assertEquals(new BigDecimal("300.00"),result.get("avgWagePerWorker"));
        assertEquals(new BigDecimal("30.00"),result.get("avgPiecesPerWorker"));
        verify(efficiency,never()).countWorkersByDate(anyString(),any());
        verify(payroll,never()).save(any());
    }
    @Test void doesNotTreatIncompleteStaffAsTheWholeWagePopulation() {
        when(payroll.findByDateRange("T",start,end)).thenReturn(List.of(row(1L),row(null)));
        when(payroll.sumTotalWageByPeriod("T",start,end)).thenReturn(new BigDecimal("400"));
        var result=service.analyzeLaborCost("T",start,end);
        assertEquals(false,result.get("workerCountComplete"));assertNull(result.get("avgWagePerWorker"));
    }
    @Test void doesNotAveragePartialWagesOverAllWorkers() {
        var incomplete=row(2L);incomplete.setTotalWage(null);
        when(payroll.findByDateRange("T",start,end)).thenReturn(List.of(row(1L),incomplete));
        when(payroll.sumTotalWageByPeriod("T",start,end)).thenReturn(new BigDecimal("200"));
        assertNull(service.analyzeLaborCost("T",start,end).get("avgWagePerWorker"));
    }
    @Test void emptyPayrollDoesNotBecomeZeroPersonCost() {
        when(payroll.findByDateRange("T",start,end)).thenReturn(List.of());
        var result=service.analyzeLaborCost("T",start,end);
        assertEquals(0L,result.get("workerCount"));assertNull(result.get("avgWagePerWorker"));assertNull(result.get("avgPiecesPerWorker"));
    }
}
