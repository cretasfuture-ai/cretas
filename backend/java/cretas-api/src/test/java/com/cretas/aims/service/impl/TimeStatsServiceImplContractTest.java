package com.cretas.aims.service.impl;

import com.cretas.aims.dto.TimeStatsDTO;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TimeStatsServiceImplContractTest {
    private static final String FACTORY_ID = "F006";
    private static final LocalDate START = LocalDate.of(2026, 10, 1);
    private static final LocalDate END = LocalDate.of(2026, 10, 2);

    @Mock
    private TimeClockRecordRepository timeClockRecordRepository;
    @Mock
    private UserRepository userRepository;
    @InjectMocks
    private TimeStatsServiceImpl service;

    @Test
    void range_usesFactoryScopedClockInWindowAndRealMinutes() {
        TimeClockRecord dayOneLong = record(1L, FACTORY_ID, START.atTime(8, 0), 600);
        TimeClockRecord dayOneShort = record(2L, FACTORY_ID, START.atTime(10, 0), 60);
        TimeClockRecord dayTwo = record(1L, FACTORY_ID, END.atTime(8, 0), 120);
        dayOneLong.setClockDate(START.minusDays(10));

        TimeClockRecord foreign = record(3L, "F007", START.atTime(8, 0), 600);
        TimeClockRecord deleted = record(4L, FACTORY_ID, START.atTime(8, 30), 600);
        deleted.setDeletedAt(START.atTime(12, 0));
        TimeClockRecord nullClockIn = record(5L, FACTORY_ID, null, 600);
        TimeClockRecord beforeWindow = record(6L, FACTORY_ID, START.minusDays(1).atTime(23, 59), 600);
        TimeClockRecord atEnd = record(7L, FACTORY_ID, END.plusDays(1).atStartOfDay(), 600);

        stubRecords(List.of(dayOneLong, dayOneShort, dayTwo, foreign, deleted,
                nullClockIn, beforeWindow, atEnd));
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(3L);

        TimeStatsDTO result = service.getDailyStatsRange(FACTORY_ID, START, END);

        assertThat(result.getTotalHours()).isEqualByComparingTo("13.00");
        assertThat(result.getRegularHours()).isEqualByComparingTo("11.00");
        assertThat(result.getOvertimeHours()).isEqualByComparingTo("2.00");
        assertThat(result.getActiveWorkers()).isEqualTo(2);
        assertThat(result.getTotalClockIns()).isEqualTo(3L);
        assertThat(result.getAttendanceRate()).isEqualByComparingTo("66.67");
        assertThat(result.getProductivity()).isNull();
        assertThat(result.getDailyStatsList()).hasSize(2);
        assertThat(result.getDailyStatsList().get(0).getTotalHours()).isEqualByComparingTo("11.00");
        assertThat(result.getDailyStatsList().get(1).getTotalHours()).isEqualByComparingTo("2.00");
        verify(timeClockRecordRepository, times(1))
                .findByFactoryIdAndClockDateBetween(eq(FACTORY_ID), any(), any());
    }

    @Test
    void emptyRange_hasNoSyntheticAggregateOrDepartmentRow() {
        stubRecords(Collections.emptyList());
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(23L);
        when(userRepository.countByDepartment(FACTORY_ID)).thenReturn(Collections.emptyList());

        TimeStatsDTO result = service.getDailyStatsRange(FACTORY_ID, START, END);
        TimeStatsDTO departmentResult = service.getStatsByDepartment(FACTORY_ID, START, END);
        TimeStatsDTO workTypeResult = service.getStatsByWorkType(FACTORY_ID, START, END);

        assertThat(result.getTotalHours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(result.getActiveWorkers()).isZero();
        assertThat(result.getTotalClockIns()).isZero();
        assertThat(result.getProductivity()).isNull();
        assertThat(departmentResult.getDepartmentStats()).isEmpty();
        assertThat(workTypeResult.getWorkTypeStats()).isEmpty();
    }

    @Test
    void dailyAndRange_shareClockInDateAndAggregationForOneDay() {
        TimeClockRecord first = record(1L, FACTORY_ID, START.atTime(8, 0), 480);
        first.setClockDate(START.plusDays(4));
        TimeClockRecord second = record(2L, FACTORY_ID, START.atTime(9, 30), 60);
        stubRecords(List.of(first, second));
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(4L);

        TimeStatsDTO daily = service.getDailyStats(FACTORY_ID, START);
        TimeStatsDTO range = service.getDailyStatsRange(FACTORY_ID, START, START);

        assertThat(range.getTotalHours()).isEqualByComparingTo(daily.getTotalHours());
        assertThat(range.getRegularHours()).isEqualByComparingTo(daily.getRegularHours());
        assertThat(range.getOvertimeHours()).isEqualByComparingTo(daily.getOvertimeHours());
        assertThat(range.getActiveWorkers()).isEqualTo(daily.getActiveWorkers());
        assertThat(range.getTotalClockIns()).isEqualTo(daily.getTotalClockIns());
        assertThat(range.getDailyStatsList().get(0).getTotalHours())
                .isEqualByComparingTo(daily.getTotalHours());
    }

    @Test
    void topN_limitsWorkerListWithoutChangingRangeAggregate() {
        TimeClockRecord first = record(1L, FACTORY_ID, START.atTime(8, 0), 600);
        TimeClockRecord second = record(2L, FACTORY_ID, START.atTime(8, 0), 120);
        stubRecords(List.of(first, second));
        when(userRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(
                user(1L, "一号", "生产部"), user(2L, "二号", "包装部")));
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(2L);

        List<TimeStatsDTO.WorkerTimeStats> top =
                service.getWorkerTimeStats(FACTORY_ID, START, START, 1);
        TimeStatsDTO range = service.getDailyStatsRange(FACTORY_ID, START, START);

        assertThat(top).hasSize(1);
        assertThat(top.get(0).getWorkerId()).isEqualTo(1);
        assertThat(range.getTotalHours()).isEqualByComparingTo("12.00");
        assertThat(range.getActiveWorkers()).isEqualTo(2);
    }

    @Test
    void workerAndDepartmentReads_ignoreForeignUserMetadataForSameUserId() {
        TimeClockRecord localClock = record(77L, FACTORY_ID, START.atTime(8, 0), 120);
        User foreignUser = user(77L, "外厂姓名", "外厂部门");
        foreignUser.setFactoryId("F007");
        stubRecords(List.of(localClock));
        when(userRepository.findByFactoryId(FACTORY_ID)).thenReturn(List.of(foreignUser));
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(1L);
        when(userRepository.countByDepartment(FACTORY_ID)).thenReturn(Collections.emptyList());

        List<TimeStatsDTO.WorkerTimeStats> workers =
                service.getWorkerTimeStats(FACTORY_ID, START, START, null);
        TimeStatsDTO departments = service.getStatsByDepartment(FACTORY_ID, START, START);

        assertThat(workers).hasSize(1);
        assertThat(workers.get(0).getWorkerName()).isNotEqualTo("外厂姓名");
        assertThat(workers.get(0).getDepartment()).isEqualTo("未分配");
        assertThat(departments.getDepartmentStats()).containsKey("未分配");
        assertThat(departments.getDepartmentStats().get("未分配").getDepartmentName())
                .isNotEqualTo("外厂部门");
    }

    @Test
    void periodWrappers_useRealRangeAndKeepExpectedDayCounts() {
        stubRecords(Collections.emptyList());
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(0L);

        TimeStatsDTO weekly = service.getWeeklyStats(FACTORY_ID, 2026, 40);
        TimeStatsDTO monthly = service.getMonthlyStats(FACTORY_ID, 2026, 10);
        TimeStatsDTO yearly = service.getYearlyStats(FACTORY_ID, 2026);

        assertThat(weekly.getPeriod()).isEqualTo("weekly");
        assertThat(weekly.getDailyStatsList()).hasSize(7);
        assertThat(monthly.getPeriod()).isEqualTo("monthly");
        assertThat(monthly.getDailyStatsList()).hasSize(31);
        assertThat(yearly.getPeriod()).isEqualTo("yearly");
        assertThat(yearly.getDailyStatsList()).hasSize(365);
        assertThat(yearly.getTotalHours()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(yearly.getProductivity()).isNull();
    }

    @Test
    void workTypeAndProductivity_doNotInventOutput() {
        TimeClockRecord record = record(1L, FACTORY_ID, START.atTime(8, 0), 480);
        record.setWorkTypeName("包装");
        stubRecords(List.of(record));
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(1L);

        TimeStatsDTO workType = service.getStatsByWorkType(FACTORY_ID, START, START);
        TimeStatsDTO.ProductivityAnalysis productivity =
                service.getProductivityAnalysis(FACTORY_ID, START, START);

        assertThat(workType.getWorkTypeStats().get("包装").getOutput()).isNull();
        assertThat(workType.getWorkTypeStats().get("包装").getEfficiency()).isNull();
        assertThat(productivity.getTotalOutput()).isNull();
        assertThat(productivity.getOutputPerHour()).isNull();
        assertThat(productivity.getOutputPerWorker()).isNull();
        assertThat(productivity.getMostEfficientDepartment()).isEqualTo("不可用");
        assertThat(productivity.getMostEfficientWorkType()).isEqualTo("不可用");
        assertThat(productivity.getImprovements()).contains("暂无足够数据生成改进建议");
    }

    @Test
    void productivity_emptyDenominator_isUnknownRatherThanOne() {
        stubRecords(Collections.emptyList());

        TimeStatsDTO.ProductivityAnalysis result =
                service.getProductivityAnalysis(FACTORY_ID, START, END);

        assertThat(result.getEfficiencyIndex()).isNull();
        assertThat(result.getTotalOutput()).isNull();
        assertThat(result.getOutputPerHour()).isNull();
        assertThat(result.getOutputPerWorker()).isNull();
    }

    @Test
    void workerById_requiresFactoryScopedIdentity_andUsesClockInDate() {
        when(userRepository.findByIdAndFactoryId(99L, FACTORY_ID)).thenReturn(java.util.Optional.empty());
        User foreignUser = user(98L, "其他工厂员工", "生产部");
        foreignUser.setFactoryId("F007");
        when(userRepository.findByIdAndFactoryId(98L, FACTORY_ID))
                .thenReturn(java.util.Optional.of(foreignUser));

        assertThatThrownBy(() -> service.getWorkerTimeStatsById(
                FACTORY_ID, 99, START, START))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getWorkerTimeStatsById(
                FACTORY_ID, 98, START, START))
                .isInstanceOf(ResourceNotFoundException.class);

        TimeClockRecord localRecord = record(1L, FACTORY_ID, START.atTime(8, 0), 480);
        localRecord.setClockDate(START.plusDays(10));
        stubRecords(List.of(localRecord));
        when(userRepository.findByIdAndFactoryId(1L, FACTORY_ID))
                .thenReturn(java.util.Optional.of(user(1L, "本厂员工", "生产部")));
        when(userRepository.findByFactoryId(FACTORY_ID))
                .thenReturn(List.of(user(1L, "本厂员工", "生产部")));

        TimeStatsDTO.WorkerTimeStats result = service.getWorkerTimeStatsById(
                FACTORY_ID, 1, START, START);

        assertThat(result.getWorkerName()).isEqualTo("本厂员工");
        assertThat(result.getAttendanceDays()).isEqualTo(1);
        assertThat(result.getTotalHours()).isEqualByComparingTo("8.00");
    }

    @Test
    void comparativeUnknownProductivity_remainsUnavailable() {
        stubRecords(Collections.emptyList());
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(0L);

        TimeStatsDTO result = service.getComparativeStats(
                FACTORY_ID, START, START, END, END);

        assertThat(result.getProductivity()).isNull();
    }

    @Test
    void exportsMarkUnavailableProductivityWithoutZeroPercent() {
        stubRecords(Collections.emptyList());
        when(userRepository.countActiveUsers(FACTORY_ID)).thenReturn(0L);

        String csv = service.exportStatsReport(FACTORY_ID, START, START, "csv");
        String json = service.exportStatsReport(FACTORY_ID, START, START, "json");
        String text = service.exportStatsReport(FACTORY_ID, START, START, "text");

        assertThat(csv).contains(",不可用");
        assertThat(json).contains("\"productivity\": null").doesNotContain("\"productivity\": 0.00");
        assertThat(text).contains("生产效率: 不可用").doesNotContain("生产效率: 0.00%");
    }

    private void stubRecords(List<TimeClockRecord> records) {
        when(timeClockRecordRepository.findByFactoryIdAndClockDateBetween(
                anyString(), any(), any())).thenReturn(new ArrayList<>(records));
    }

    private TimeClockRecord record(Long userId, String factoryId, LocalDateTime clockIn, int minutes) {
        TimeClockRecord record = new TimeClockRecord();
        record.setFactoryId(factoryId);
        record.setUserId(userId);
        record.setClockInTime(clockIn);
        record.setClockDate(clockIn == null ? null : clockIn.toLocalDate());
        record.setWorkDurationMinutes(minutes);
        record.setAttendanceStatus("NORMAL");
        return record;
    }

    private User user(Long id, String fullName, String department) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(FACTORY_ID);
        user.setFullName(fullName);
        user.setDepartment(department);
        return user;
    }
}
