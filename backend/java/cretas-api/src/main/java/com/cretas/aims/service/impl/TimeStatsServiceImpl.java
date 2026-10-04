package com.cretas.aims.service.impl;

import com.cretas.aims.dto.TimeStatsDTO;
import com.cretas.aims.entity.TimeClockRecord;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.exception.ResourceNotFoundException;
import com.cretas.aims.repository.TimeClockRecordRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.TimeStatsService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.WeekFields;
import java.util.*;
import java.util.stream.Collectors;
/**
 * 时间统计服务实现
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Service
@RequiredArgsConstructor
public class TimeStatsServiceImpl implements TimeStatsService {
    private static final Logger log = LoggerFactory.getLogger(TimeStatsServiceImpl.class);

    // 标准工作时长（分钟）
    private static final int STANDARD_WORK_MINUTES = 8 * 60;
    // 迟到阈值（早上9:00后打卡视为迟到）
    private static final int LATE_THRESHOLD_HOUR = 9;
    private static final int LATE_THRESHOLD_MINUTE = 0;
    // 早退阈值（下午5:30前打卡视为早退）
    private static final int EARLY_LEAVE_THRESHOLD_HOUR = 17;
    private static final int EARLY_LEAVE_THRESHOLD_MINUTE = 30;

    private final TimeClockRecordRepository timeClockRecordRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getDailyStats(String factoryId, LocalDate date) {
        log.info("获取日统计: factoryId={}, date={}", factoryId, date);
        TimeStatsDTO stats = new TimeStatsDTO();
        stats.setPeriod("daily");
        stats.setStartDate(date);
        stats.setEndDate(date);

        // 查询当日打卡记录
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, startOfDay, endOfDay);

        // 获取工厂总员工数
        long totalEmployees = userRepository.countActiveUsers(factoryId);

        // 计算统计数据
        calculateStatsFromRecords(stats, records, totalEmployees);

        return stats;
    }

    /**
     * 从打卡记录计算统计数据
     */
    private void calculateStatsFromRecords(TimeStatsDTO stats, List<TimeClockRecord> records, long totalEmployees) {
        if (records.isEmpty()) {
            stats.setTotalHours(BigDecimal.ZERO);
            stats.setRegularHours(BigDecimal.ZERO);
            stats.setOvertimeHours(BigDecimal.ZERO);
            stats.setActiveWorkers(0);
            stats.setTotalClockIns(0L);
            stats.setLateCount(0L);
            stats.setEarlyLeaveCount(0L);
            stats.setAbsentCount(totalEmployees);
            stats.setAverageHours(BigDecimal.ZERO);
            stats.setAttendanceRate(BigDecimal.ZERO);
            // 工时统计没有生产批次产出来源，生产力指数不可用。
            stats.setProductivity(null);
            return;
        }

        // 计算总工时（分钟转小时）
        BigDecimal totalMinutes = records.stream()
                .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalHours = totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        stats.setTotalHours(totalHours);

        // 计算正常工时和加班工时
        BigDecimal regularMinutes = BigDecimal.ZERO;
        BigDecimal overtimeMinutes = BigDecimal.ZERO;
        for (TimeClockRecord record : records) {
            int workMinutes = record.getWorkDurationMinutes() != null ? record.getWorkDurationMinutes() : 0;
            if (workMinutes > STANDARD_WORK_MINUTES) {
                regularMinutes = regularMinutes.add(BigDecimal.valueOf(STANDARD_WORK_MINUTES));
                overtimeMinutes = overtimeMinutes.add(BigDecimal.valueOf(workMinutes - STANDARD_WORK_MINUTES));
            } else {
                regularMinutes = regularMinutes.add(BigDecimal.valueOf(workMinutes));
            }
        }
        stats.setRegularHours(regularMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        stats.setOvertimeHours(overtimeMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

        // 统计活跃员工数（去重）
        Set<Long> activeUserIds = records.stream()
                .map(TimeClockRecord::getUserId)
                .collect(Collectors.toSet());
        stats.setActiveWorkers(activeUserIds.size());

        // 打卡次数
        stats.setTotalClockIns((long) records.size());

        // 统计迟到、早退次数
        long lateCount = records.stream()
                .filter(this::isLate)
                .count();
        long earlyLeaveCount = records.stream()
                .filter(this::isEarlyLeave)
                .count();
        stats.setLateCount(lateCount);
        stats.setEarlyLeaveCount(earlyLeaveCount);

        // 缺勤次数
        long absentCount = Math.max(0, totalEmployees - activeUserIds.size());
        stats.setAbsentCount(absentCount);

        // 平均工时
        if (!activeUserIds.isEmpty()) {
            stats.setAverageHours(totalHours.divide(BigDecimal.valueOf(activeUserIds.size()), 2, RoundingMode.HALF_UP));
        } else {
            stats.setAverageHours(BigDecimal.ZERO);
        }

        // 出勤率
        if (totalEmployees > 0) {
            BigDecimal attendanceRate = BigDecimal.valueOf(activeUserIds.size())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalEmployees), 2, RoundingMode.HALF_UP);
            stats.setAttendanceRate(attendanceRate);
        } else {
            stats.setAttendanceRate(BigDecimal.ZERO);
        }

        // 工时/出勤事实不能推导真实产出，生产力指数保持不可用。
        stats.setProductivity(null);
    }

    /**
     * 使用一次工厂范围查询，并在服务层再次守住租户、软删除和时间窗口边界。
     * Repository 查询也有这些条件，但二次过滤避免测试替身或未来查询变更把脏记录带入统计。
     */
    private List<TimeClockRecord> loadRecordsForRange(String factoryId, LocalDateTime start, LocalDateTime end) {
        List<TimeClockRecord> records = timeClockRecordRepository.findByFactoryIdAndClockDateBetween(
                factoryId, start, end);
        if (records == null || records.isEmpty()) {
            return Collections.emptyList();
        }
        return records.stream()
                .filter(Objects::nonNull)
                .filter(record -> factoryId.equals(record.getFactoryId()))
                .filter(record -> record.getDeletedAt() == null)
                .filter(record -> record.getUserId() != null)
                .filter(record -> record.getClockInTime() != null)
                .filter(record -> !record.getClockInTime().isBefore(start)
                        && record.getClockInTime().isBefore(end))
                .collect(Collectors.toList());
    }

    private Map<Long, User> loadFactoryUserMap(String factoryId) {
        List<User> users = userRepository.findByFactoryId(factoryId);
        if (users == null || users.isEmpty()) {
            return Collections.emptyMap();
        }
        return users.stream()
                .filter(Objects::nonNull)
                .filter(user -> user.getId() != null)
                .filter(user -> factoryId.equals(user.getFactoryId()))
                .collect(Collectors.toMap(User::getId, user -> user, (first, ignored) -> first));
    }

    private TimeStatsDTO.DailyStats toDailyStats(LocalDate date, TimeStatsDTO dayStats) {
        TimeStatsDTO.DailyStats daily = new TimeStatsDTO.DailyStats();
        daily.setDate(date);
        daily.setDayOfWeek(date.getDayOfWeek().toString());
        daily.setIsWorkday(!isWeekend(date));
        daily.setTotalHours(dayStats.getTotalHours());
        daily.setActiveWorkers(dayStats.getActiveWorkers());
        daily.setClockIns(dayStats.getTotalClockIns());
        daily.setAttendanceRate(dayStats.getAttendanceRate());
        return daily;
    }

    private void populateRangeAggregate(TimeStatsDTO stats, List<TimeClockRecord> records,
                                        long totalEmployees) {
        BigDecimal totalMinutes = records.stream()
                .map(record -> record.getWorkDurationMinutes() != null
                        ? BigDecimal.valueOf(record.getWorkDurationMinutes()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal regularMinutes = BigDecimal.ZERO;
        BigDecimal overtimeMinutes = BigDecimal.ZERO;
        for (TimeClockRecord record : records) {
            int workMinutes = record.getWorkDurationMinutes() != null ? record.getWorkDurationMinutes() : 0;
            if (workMinutes > STANDARD_WORK_MINUTES) {
                regularMinutes = regularMinutes.add(BigDecimal.valueOf(STANDARD_WORK_MINUTES));
                overtimeMinutes = overtimeMinutes.add(BigDecimal.valueOf(workMinutes - STANDARD_WORK_MINUTES));
            } else {
                regularMinutes = regularMinutes.add(BigDecimal.valueOf(workMinutes));
            }
        }
        BigDecimal totalHours = totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        Set<Long> activeUserIds = records.stream()
                .map(TimeClockRecord::getUserId)
                .collect(Collectors.toSet());
        stats.setTotalHours(totalHours);
        stats.setRegularHours(regularMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        stats.setOvertimeHours(overtimeMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
        stats.setActiveWorkers(activeUserIds.size());
        stats.setTotalClockIns((long) records.size());
        stats.setLateCount(records.stream().filter(this::isLate).count());
        stats.setEarlyLeaveCount(records.stream().filter(this::isEarlyLeave).count());
        stats.setAbsentCount(Math.max(0, totalEmployees - activeUserIds.size()));
        stats.setAverageHours(activeUserIds.isEmpty()
                ? BigDecimal.ZERO
                : totalHours.divide(BigDecimal.valueOf(activeUserIds.size()), 2, RoundingMode.HALF_UP));
        stats.setAttendanceRate(totalEmployees > 0
                ? BigDecimal.valueOf(activeUserIds.size()).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalEmployees), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO);
        stats.setProductivity(null);
    }

    private TimeStatsDTO buildRangeStats(String factoryId, LocalDate startDate, LocalDate endDate,
                                         List<TimeClockRecord> records) {
        TimeStatsDTO stats = new TimeStatsDTO();
        stats.setPeriod("range");
        stats.setStartDate(startDate);
        stats.setEndDate(endDate);
        long totalEmployees = userRepository.countActiveUsers(factoryId);
        List<TimeStatsDTO.DailyStats> dailyStatsList = new ArrayList<>();
        LocalDate currentDate = startDate;
        while (!currentDate.isAfter(endDate)) {
            LocalDate day = currentDate;
            List<TimeClockRecord> dayRecords = records.stream()
                    .filter(record -> record.getClockInTime().toLocalDate().equals(day))
                    .collect(Collectors.toList());
            TimeStatsDTO dayStats = new TimeStatsDTO();
            calculateStatsFromRecords(dayStats, dayRecords, totalEmployees);
            dailyStatsList.add(toDailyStats(day, dayStats));
            currentDate = currentDate.plusDays(1);
        }
        stats.setDailyStatsList(dailyStatsList);
        populateRangeAggregate(stats, records, totalEmployees);
        return stats;
    }

    /**
     * 判断是否迟到
     */
    private boolean isLate(TimeClockRecord record) {
        if (record.getClockInTime() == null) {
            return false;
        }
        // 检查attendanceStatus字段
        if ("LATE".equalsIgnoreCase(record.getAttendanceStatus()) ||
            "LATE_AND_EARLY_LEAVE".equalsIgnoreCase(record.getAttendanceStatus())) {
            return true;
        }
        // 基于时间判断
        int hour = record.getClockInTime().getHour();
        int minute = record.getClockInTime().getMinute();
        return hour > LATE_THRESHOLD_HOUR ||
               (hour == LATE_THRESHOLD_HOUR && minute > LATE_THRESHOLD_MINUTE);
    }

    /**
     * 判断是否早退
     */
    private boolean isEarlyLeave(TimeClockRecord record) {
        if (record.getClockOutTime() == null) {
            return false;
        }
        // 检查attendanceStatus字段
        if ("EARLY_LEAVE".equalsIgnoreCase(record.getAttendanceStatus()) ||
            "LATE_AND_EARLY_LEAVE".equalsIgnoreCase(record.getAttendanceStatus())) {
            return true;
        }
        // 基于时间判断
        int hour = record.getClockOutTime().getHour();
        int minute = record.getClockOutTime().getMinute();
        return hour < EARLY_LEAVE_THRESHOLD_HOUR ||
               (hour == EARLY_LEAVE_THRESHOLD_HOUR && minute < EARLY_LEAVE_THRESHOLD_MINUTE);
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getDailyStatsRange(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取日期范围统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        validateDateRange(startDate, endDate);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);
        return buildRangeStats(factoryId, startDate, endDate, records);
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getWeeklyStats(String factoryId, Integer year, Integer week) {
        log.info("获取周统计: factoryId={}, year={}, week={}", factoryId, year, week);
        WeekFields weekFields = WeekFields.of(DayOfWeek.MONDAY, 1);
        LocalDate startOfWeek = LocalDate.of(year, 1, 1)
                .with(weekFields.weekOfYear(), week)
                .with(DayOfWeek.MONDAY);
        LocalDate endOfWeek = startOfWeek.plusDays(6);
        TimeStatsDTO stats = getDailyStatsRange(factoryId, startOfWeek, endOfWeek);
        stats.setPeriod("weekly");
        return stats;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getMonthlyStats(String factoryId, Integer year, Integer month) {
        log.info("获取月统计: factoryId={}, year={}, month={}", factoryId, year, month);
        LocalDate startOfMonth = LocalDate.of(year, month, 1);
        LocalDate endOfMonth = startOfMonth.withDayOfMonth(startOfMonth.lengthOfMonth());
        TimeStatsDTO stats = getDailyStatsRange(factoryId, startOfMonth, endOfMonth);
        stats.setPeriod("monthly");
        return stats;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getYearlyStats(String factoryId, Integer year) {
        log.info("获取年统计: factoryId={}, year={}", factoryId, year);
        LocalDate startOfYear = LocalDate.of(year, 1, 1);
        LocalDate endOfYear = LocalDate.of(year, 12, 31);
        TimeStatsDTO stats = getDailyStatsRange(factoryId, startOfYear, endOfYear);
        stats.setPeriod("yearly");
        return stats;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getStatsByWorkType(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("按工作类型统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        validateDateRange(startDate, endDate);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);
        TimeStatsDTO stats = buildRangeStats(factoryId, startDate, endDate, records);

        // 按workTypeName分组统计
        Map<String, TimeStatsDTO.WorkTypeStats> workTypeStatsMap = new HashMap<>();

        // 按工作类型分组
        Map<String, List<TimeClockRecord>> recordsByWorkType = records.stream()
                .filter(r -> r.getWorkTypeName() != null && !r.getWorkTypeName().isEmpty())
                .collect(Collectors.groupingBy(TimeClockRecord::getWorkTypeName));

        int typeIndex = 1;
        for (Map.Entry<String, List<TimeClockRecord>> entry : recordsByWorkType.entrySet()) {
            String workTypeName = entry.getKey();
            List<TimeClockRecord> typeRecords = entry.getValue();

            TimeStatsDTO.WorkTypeStats typeStats = new TimeStatsDTO.WorkTypeStats();
            typeStats.setWorkTypeId(typeIndex++);
            typeStats.setWorkTypeName(workTypeName);

            // 计算总工时
            BigDecimal totalMinutes = typeRecords.stream()
                    .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            typeStats.setTotalHours(totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 参与人数（去重）
            Set<Long> uniqueUsers = typeRecords.stream()
                    .map(TimeClockRecord::getUserId)
                    .collect(Collectors.toSet());
            typeStats.setWorkerCount(uniqueUsers.size());

            // 平均工时
            if (!uniqueUsers.isEmpty()) {
                typeStats.setAverageHours(typeStats.getTotalHours().divide(
                        BigDecimal.valueOf(uniqueUsers.size()), 2, RoundingMode.HALF_UP));
            } else {
                typeStats.setAverageHours(BigDecimal.ZERO);
            }

            // 工时记录没有生产批次产出来源，产出和效率指数不可用。
            typeStats.setOutput(null);
            typeStats.setEfficiency(null);

            workTypeStatsMap.put(workTypeName, typeStats);
        }

        // 如果没有工作类型数据，使用默认分类
        if (workTypeStatsMap.isEmpty() && !records.isEmpty()) {
            workTypeStatsMap.put("常规工作", createDefaultWorkTypeStats("常规工作", 1, records));
        }

        stats.setWorkTypeStats(workTypeStatsMap);
        return stats;
    }

    /**
     * 创建默认工作类型统计
     */
    private TimeStatsDTO.WorkTypeStats createDefaultWorkTypeStats(String typeName, Integer typeId, List<TimeClockRecord> records) {
        TimeStatsDTO.WorkTypeStats stats = new TimeStatsDTO.WorkTypeStats();
        stats.setWorkTypeId(typeId);
        stats.setWorkTypeName(typeName);

        BigDecimal totalMinutes = records.stream()
                .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        stats.setTotalHours(totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

        Set<Long> uniqueUsers = records.stream()
                .map(TimeClockRecord::getUserId)
                .collect(Collectors.toSet());
        stats.setWorkerCount(uniqueUsers.size());

        if (!uniqueUsers.isEmpty()) {
            stats.setAverageHours(stats.getTotalHours().divide(
                    BigDecimal.valueOf(uniqueUsers.size()), 2, RoundingMode.HALF_UP));
        } else {
            stats.setAverageHours(BigDecimal.ZERO);
        }

        stats.setOutput(null);
        stats.setEfficiency(null);

        return stats;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getStatsByDepartment(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("按部门统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        validateDateRange(startDate, endDate);
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);
        TimeStatsDTO stats = buildRangeStats(factoryId, startDate, endDate, records);

        // 获取所有用户ID
        Set<Long> userIds = records.stream()
                .map(TimeClockRecord::getUserId)
                .collect(Collectors.toSet());

        // 查询用户部门信息
        Map<Long, String> userDepartmentMap = new HashMap<>();
        if (!userIds.isEmpty()) {
            for (Map.Entry<Long, User> entry : loadFactoryUserMap(factoryId).entrySet()) {
                if (userIds.contains(entry.getKey())) {
                    String dept = entry.getValue().getDepartment();
                    userDepartmentMap.put(entry.getKey(), dept != null ? dept : "未分配");
                }
            }
        }

        // 按部门分组打卡记录
        Map<String, List<TimeClockRecord>> recordsByDept = records.stream()
                .collect(Collectors.groupingBy(r -> userDepartmentMap.getOrDefault(r.getUserId(), "未分配")));

        // 按部门统计员工总数
        List<Object[]> deptCounts = userRepository.countByDepartment(factoryId);
        Map<String, Long> deptTotalEmployees = new HashMap<>();
        for (Object[] row : deptCounts) {
            String dept = row[0] != null ? row[0].toString() : "未分配";
            Long count = (Long) row[1];
            deptTotalEmployees.put(dept, count);
        }

        // 计算各部门统计
        Map<String, TimeStatsDTO.DepartmentStats> departmentStatsMap = new HashMap<>();

        for (Map.Entry<String, List<TimeClockRecord>> entry : recordsByDept.entrySet()) {
            String department = entry.getKey();
            List<TimeClockRecord> deptRecords = entry.getValue();

            TimeStatsDTO.DepartmentStats deptStats = new TimeStatsDTO.DepartmentStats();
            deptStats.setDepartmentName(department);

            // 计算总工时
            BigDecimal totalMinutes = deptRecords.stream()
                    .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            deptStats.setTotalHours(totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 员工数（去重）
            Set<Long> uniqueUsers = deptRecords.stream()
                    .map(TimeClockRecord::getUserId)
                    .collect(Collectors.toSet());
            deptStats.setWorkerCount(uniqueUsers.size());

            // 平均工时
            if (!uniqueUsers.isEmpty()) {
                deptStats.setAverageHours(deptStats.getTotalHours().divide(
                        BigDecimal.valueOf(uniqueUsers.size()), 2, RoundingMode.HALF_UP));
            } else {
                deptStats.setAverageHours(BigDecimal.ZERO);
            }

            // 加班工时
            BigDecimal overtimeMinutes = BigDecimal.ZERO;
            for (TimeClockRecord record : deptRecords) {
                int workMinutes = record.getWorkDurationMinutes() != null ? record.getWorkDurationMinutes() : 0;
                if (workMinutes > STANDARD_WORK_MINUTES) {
                    overtimeMinutes = overtimeMinutes.add(BigDecimal.valueOf(workMinutes - STANDARD_WORK_MINUTES));
                }
            }
            deptStats.setOvertimeHours(overtimeMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 出勤率
            long deptTotal = deptTotalEmployees.getOrDefault(department, (long) uniqueUsers.size());
            if (deptTotal > 0) {
                BigDecimal attendanceRate = BigDecimal.valueOf(uniqueUsers.size())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(deptTotal), 2, RoundingMode.HALF_UP);
                deptStats.setAttendanceRate(attendanceRate);
            } else {
                deptStats.setAttendanceRate(BigDecimal.ZERO);
            }

            departmentStatsMap.put(department, deptStats);
        }

        stats.setDepartmentStats(departmentStatsMap);
        return stats;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO.ProductivityAnalysis getProductivityAnalysis(String factoryId,
                                                                    LocalDate startDate,
                                                                    LocalDate endDate) {
        log.info("获取生产力分析: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO.ProductivityAnalysis analysis = new TimeStatsDTO.ProductivityAnalysis();
        analysis.setPeriod(String.format("%s to %s", startDate, endDate));

        // 查询日期范围内的打卡记录
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);

        // 计算总投入工时
        BigDecimal totalMinutes = records.stream()
                .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalInputHours = totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);
        analysis.setTotalInputHours(totalInputHours);

        // 参与员工数
        Set<Long> uniqueUsers = records.stream()
                .map(TimeClockRecord::getUserId)
                .collect(Collectors.toSet());
        int workerCount = uniqueUsers.size();

        // 工时记录没有生产批次产出来源，产出字段保持不可用。
        analysis.setTotalOutput(null);

        // 时均产出
        if (totalInputHours.compareTo(BigDecimal.ZERO) > 0) {
            analysis.setOutputPerHour(null);
        } else {
            analysis.setOutputPerHour(null);
        }

        // 人均产出
        if (workerCount > 0) {
            analysis.setOutputPerWorker(null);
        } else {
            analysis.setOutputPerWorker(null);
        }

        // 计算效率指数（实际工时/标准工时）
        long workingDays = countWorkingDays(startDate, endDate);
        BigDecimal expectedHours = BigDecimal.valueOf(workingDays)
                .multiply(BigDecimal.valueOf(workerCount))
                .multiply(BigDecimal.valueOf(8)); // 每天8小时

        if (expectedHours.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal efficiencyIndex = totalInputHours.divide(expectedHours, 4, RoundingMode.HALF_UP);
            analysis.setEfficiencyIndex(efficiencyIndex);
        } else {
            analysis.setEfficiencyIndex(null);
        }

        // 计算趋势（与前一周期对比）
        long periodDays = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate) + 1;
        LocalDate prevEnd = startDate.minusDays(1);
        LocalDate prevStart = prevEnd.minusDays(periodDays - 1);

        List<TimeClockRecord> prevRecords = loadRecordsForRange(
                factoryId, prevStart.atStartOfDay(), startDate.atStartOfDay());

        BigDecimal prevMinutes = prevRecords.stream()
                .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal prevHours = prevMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP);

        if (prevHours.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal growthRate = totalInputHours.subtract(prevHours)
                    .divide(prevHours, 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
            analysis.setGrowthRate(growthRate.setScale(2, RoundingMode.HALF_UP));
            analysis.setTrend(growthRate.compareTo(BigDecimal.ZERO) > 0 ? "上升" :
                              growthRate.compareTo(BigDecimal.ZERO) < 0 ? "下降" : "持平");
        } else {
            analysis.setGrowthRate(BigDecimal.ZERO);
            analysis.setTrend("无历史数据");
        }

        // 当前没有真实产出/效率来源，不能宣称最高效部门或工作类型。
        analysis.setMostEfficientDepartment("不可用");
        analysis.setMostEfficientWorkType("不可用");

        // 生成改进建议
        List<String> improvements = generateImprovementSuggestions(analysis, records);
        analysis.setImprovements(improvements);

        return analysis;
    }

    /**
     * 计算工作日数量（排除周末）
     */
    private long countWorkingDays(LocalDate start, LocalDate end) {
        long workingDays = 0;
        LocalDate current = start;
        while (!current.isAfter(end)) {
            if (!isWeekend(current)) {
                workingDays++;
            }
            current = current.plusDays(1);
        }
        return workingDays;
    }

    private void validateDateRange(LocalDate startDate, LocalDate endDate) {
        if (startDate.isAfter(endDate)) {
            throw new BusinessException(400, "开始日期不能晚于结束日期")
                    .withHint("请调整查询日期范围").withHintTarget("startDate");
        }
    }

    /**
     * 生成改进建议
     */
    private List<String> generateImprovementSuggestions(TimeStatsDTO.ProductivityAnalysis analysis, List<TimeClockRecord> records) {
        List<String> suggestions = new ArrayList<>();

        // 基于效率指数
        if (analysis.getEfficiencyIndex() != null && analysis.getEfficiencyIndex().compareTo(BigDecimal.ONE) < 0) {
            suggestions.add("当前效率指数低于标准，建议优化工作流程，减少非生产时间");
        }

        // 基于增长率
        if (analysis.getGrowthRate() != null && analysis.getGrowthRate().compareTo(BigDecimal.ZERO) < 0) {
            suggestions.add("生产力呈下降趋势，建议分析原因并采取改进措施");
        }

        // 基于迟到/早退情况
        long lateCount = records.stream().filter(this::isLate).count();
        long earlyLeaveCount = records.stream().filter(this::isEarlyLeave).count();
        if (lateCount > records.size() * 0.1) {
            suggestions.add("迟到率较高，建议加强考勤管理或调整上班时间");
        }
        if (earlyLeaveCount > records.size() * 0.1) {
            suggestions.add("早退率较高，建议关注员工工作负荷和满意度");
        }

        // 默认建议
        if (suggestions.isEmpty()) {
            suggestions.add("暂无足够数据生成改进建议");
        }

        return suggestions;
    }
    @Override
    @Transactional(readOnly = true)
    public List<TimeStatsDTO.WorkerTimeStats> getWorkerTimeStats(String factoryId,
                                                                 LocalDate startDate,
                                                                 LocalDate endDate,
                                                                 Integer topN) {
        log.info("获取员工时间统计: factoryId={}, startDate={}, endDate={}, topN={}",
                factoryId, startDate, endDate, topN);

        // 查询日期范围内的打卡记录
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);

        // 按用户ID分组
        Map<Long, List<TimeClockRecord>> recordsByUser = records.stream()
                .collect(Collectors.groupingBy(TimeClockRecord::getUserId));

        // 查询用户信息
        Map<Long, User> userMap = loadFactoryUserMap(factoryId);

        // 计算每个员工的统计数据
        List<TimeStatsDTO.WorkerTimeStats> workerStatsList = new ArrayList<>();

        for (Map.Entry<Long, List<TimeClockRecord>> entry : recordsByUser.entrySet()) {
            Long userId = entry.getKey();
            List<TimeClockRecord> userRecords = entry.getValue();
            User user = userMap.get(userId);

            TimeStatsDTO.WorkerTimeStats workerStats = new TimeStatsDTO.WorkerTimeStats();
            workerStats.setWorkerId(userId.intValue());
            workerStats.setWorkerName(user != null ? user.getFullName() : "员工" + userId);
            workerStats.setDepartment(user != null ? user.getDepartment() : "未分配");

            // 计算总工时
            BigDecimal totalMinutes = userRecords.stream()
                    .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            workerStats.setTotalHours(totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 计算正常工时和加班工时
            BigDecimal regularMinutes = BigDecimal.ZERO;
            BigDecimal overtimeMinutes = BigDecimal.ZERO;
            for (TimeClockRecord record : userRecords) {
                int workMinutes = record.getWorkDurationMinutes() != null ? record.getWorkDurationMinutes() : 0;
                if (workMinutes > STANDARD_WORK_MINUTES) {
                    regularMinutes = regularMinutes.add(BigDecimal.valueOf(STANDARD_WORK_MINUTES));
                    overtimeMinutes = overtimeMinutes.add(BigDecimal.valueOf(workMinutes - STANDARD_WORK_MINUTES));
                } else {
                    regularMinutes = regularMinutes.add(BigDecimal.valueOf(workMinutes));
                }
            }
            workerStats.setRegularHours(regularMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
            workerStats.setOvertimeHours(overtimeMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 出勤天数
            Set<LocalDate> attendanceDates = userRecords.stream()
                    .map(record -> record.getClockInTime().toLocalDate())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            workerStats.setAttendanceDays(attendanceDates.size());

            // 迟到次数
            int lateCount = (int) userRecords.stream().filter(this::isLate).count();
            workerStats.setLateCount(lateCount);

            // 早退次数
            int earlyLeaveCount = (int) userRecords.stream().filter(this::isEarlyLeave).count();
            workerStats.setEarlyLeaveCount(earlyLeaveCount);

            // 出勤率
            long expectedWorkDays = countWorkingDays(startDate, endDate);
            if (expectedWorkDays > 0) {
                BigDecimal attendanceRate = BigDecimal.valueOf(attendanceDates.size())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(expectedWorkDays), 2, RoundingMode.HALF_UP);
                workerStats.setAttendanceRate(attendanceRate.min(BigDecimal.valueOf(100)));
            } else {
                workerStats.setAttendanceRate(BigDecimal.ZERO);
            }

            // 工作效率
            BigDecimal expectedHours = BigDecimal.valueOf(attendanceDates.size()).multiply(BigDecimal.valueOf(8));
            if (expectedHours.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal efficiency = workerStats.getTotalHours()
                        .divide(expectedHours, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                workerStats.setEfficiency(efficiency.min(BigDecimal.valueOf(150)).setScale(2, RoundingMode.HALF_UP));
            } else {
                workerStats.setEfficiency(BigDecimal.ZERO);
            }

            // 统计周期
            TimeStatsDTO.Period period = new TimeStatsDTO.Period();
            period.setStartDate(startDate);
            period.setEndDate(endDate);
            workerStats.setPeriod(period);

            workerStatsList.add(workerStats);
        }

        // 按总工时降序排序
        workerStatsList.sort((a, b) -> b.getTotalHours().compareTo(a.getTotalHours()));

        // 设置排名
        for (int i = 0; i < workerStatsList.size(); i++) {
            workerStatsList.get(i).setRanking(i + 1);
        }

        // 限制返回数量
        int limit = topN != null ? topN : workerStatsList.size();
        return workerStatsList.stream().limit(limit).collect(Collectors.toList());
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO.WorkerTimeStats getWorkerTimeStatsById(String factoryId,
                                                              Integer workerId,
                                                              LocalDate startDate,
                                                              LocalDate endDate) {
        log.info("获取员工个人时间统计: factoryId={}, workerId={}, startDate={}, endDate={}",
                factoryId, workerId, startDate, endDate);

        // 查询用户信息
        Optional<User> userOpt = userRepository.findByIdAndFactoryId(workerId.longValue(), factoryId);
        if (!userOpt.isPresent()) {
            throw new ResourceNotFoundException("员工不存在: " + workerId);
        }
        User user = userOpt.get();
        if (user.getId() == null || !Long.valueOf(workerId.longValue()).equals(user.getId())
                || !factoryId.equals(user.getFactoryId())) {
            throw new ResourceNotFoundException("员工不存在: " + workerId);
        }

        // 查询日期范围内的打卡记录
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);

        // 过滤出该员工的记录
        List<TimeClockRecord> userRecords = records.stream()
                .filter(r -> r.getUserId() != null && r.getUserId().equals(workerId.longValue()))
                .collect(Collectors.toList());

        TimeStatsDTO.WorkerTimeStats workerStats = new TimeStatsDTO.WorkerTimeStats();
        workerStats.setWorkerId(workerId);
        workerStats.setWorkerName(user.getFullName());
        workerStats.setDepartment(user.getDepartment());

        if (userRecords.isEmpty()) {
            // 无打卡记录
            workerStats.setTotalHours(BigDecimal.ZERO);
            workerStats.setRegularHours(BigDecimal.ZERO);
            workerStats.setOvertimeHours(BigDecimal.ZERO);
            workerStats.setAttendanceDays(0);
            workerStats.setLateCount(0);
            workerStats.setEarlyLeaveCount(0);
            workerStats.setAttendanceRate(BigDecimal.ZERO);
            workerStats.setEfficiency(BigDecimal.ZERO);
            workerStats.setRanking(0);
        } else {
            // 计算总工时
            BigDecimal totalMinutes = userRecords.stream()
                    .map(r -> r.getWorkDurationMinutes() != null ? BigDecimal.valueOf(r.getWorkDurationMinutes()) : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            workerStats.setTotalHours(totalMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 计算正常工时和加班工时
            BigDecimal regularMinutes = BigDecimal.ZERO;
            BigDecimal overtimeMinutes = BigDecimal.ZERO;
            for (TimeClockRecord record : userRecords) {
                int workMinutes = record.getWorkDurationMinutes() != null ? record.getWorkDurationMinutes() : 0;
                if (workMinutes > STANDARD_WORK_MINUTES) {
                    regularMinutes = regularMinutes.add(BigDecimal.valueOf(STANDARD_WORK_MINUTES));
                    overtimeMinutes = overtimeMinutes.add(BigDecimal.valueOf(workMinutes - STANDARD_WORK_MINUTES));
                } else {
                    regularMinutes = regularMinutes.add(BigDecimal.valueOf(workMinutes));
                }
            }
            workerStats.setRegularHours(regularMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));
            workerStats.setOvertimeHours(overtimeMinutes.divide(BigDecimal.valueOf(60), 2, RoundingMode.HALF_UP));

            // 出勤天数
            Set<LocalDate> attendanceDates = userRecords.stream()
                    .map(record -> record.getClockInTime().toLocalDate())
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
            workerStats.setAttendanceDays(attendanceDates.size());

            // 迟到/早退次数
            workerStats.setLateCount((int) userRecords.stream().filter(this::isLate).count());
            workerStats.setEarlyLeaveCount((int) userRecords.stream().filter(this::isEarlyLeave).count());

            // 出勤率
            long expectedWorkDays = countWorkingDays(startDate, endDate);
            if (expectedWorkDays > 0) {
                BigDecimal attendanceRate = BigDecimal.valueOf(attendanceDates.size())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(expectedWorkDays), 2, RoundingMode.HALF_UP);
                workerStats.setAttendanceRate(attendanceRate.min(BigDecimal.valueOf(100)));
            } else {
                workerStats.setAttendanceRate(BigDecimal.ZERO);
            }

            // 工作效率
            BigDecimal expectedHours = BigDecimal.valueOf(attendanceDates.size()).multiply(BigDecimal.valueOf(8));
            if (expectedHours.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal efficiency = workerStats.getTotalHours()
                        .divide(expectedHours, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
                workerStats.setEfficiency(efficiency.min(BigDecimal.valueOf(150)).setScale(2, RoundingMode.HALF_UP));
            } else {
                workerStats.setEfficiency(BigDecimal.ZERO);
            }

            // 计算排名（相对于所有员工）
            List<TimeStatsDTO.WorkerTimeStats> allWorkerStats = getWorkerTimeStats(factoryId, startDate, endDate, null);
            int ranking = 1;
            for (TimeStatsDTO.WorkerTimeStats ws : allWorkerStats) {
                if (ws.getWorkerId().equals(workerId)) {
                    ranking = ws.getRanking();
                    break;
                }
            }
            workerStats.setRanking(ranking);
        }

        // 统计周期
        TimeStatsDTO.Period period = new TimeStatsDTO.Period();
        period.setStartDate(startDate);
        period.setEndDate(endDate);
        workerStats.setPeriod(period);

        return workerStats;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getRealtimeStats(String factoryId) {
        log.info("获取实时统计: factoryId={}", factoryId);
        LocalDate today = LocalDate.now();
        return getDailyStats(factoryId, today);
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getComparativeStats(String factoryId,
                                           LocalDate period1Start,
                                           LocalDate period1End,
                                           LocalDate period2Start,
                                           LocalDate period2End) {
        log.info("获取对比分析: factoryId={}, period1={} to {}, period2={} to {}",
                factoryId, period1Start, period1End, period2Start, period2End);

        // 获取两个周期的统计数据
        TimeStatsDTO stats1 = getDailyStatsRange(factoryId, period1Start, period1End);
        TimeStatsDTO stats2 = getDailyStatsRange(factoryId, period2Start, period2End);

        // 创建对比统计结果
        TimeStatsDTO comparativeStats = new TimeStatsDTO();
        comparativeStats.setPeriod("comparative");
        comparativeStats.setStartDate(period1Start);
        comparativeStats.setEndDate(period2End);

        // 计算对比指标（使用period2作为基准，计算与period1的差异）
        // 总工时变化
        BigDecimal hoursChange = safeSubtract(stats2.getTotalHours(), stats1.getTotalHours());
        comparativeStats.setTotalHours(hoursChange);

        // 正常工时变化
        BigDecimal regularChange = safeSubtract(stats2.getRegularHours(), stats1.getRegularHours());
        comparativeStats.setRegularHours(regularChange);

        // 加班工时变化
        BigDecimal overtimeChange = safeSubtract(stats2.getOvertimeHours(), stats1.getOvertimeHours());
        comparativeStats.setOvertimeHours(overtimeChange);

        // 活跃员工数变化
        int activeWorkersChange = safeSubtract(stats2.getActiveWorkers(), stats1.getActiveWorkers());
        comparativeStats.setActiveWorkers(activeWorkersChange);

        // 迟到次数变化
        long lateChange = safeSubtract(stats2.getLateCount(), stats1.getLateCount());
        comparativeStats.setLateCount(lateChange);

        // 早退次数变化
        long earlyLeaveChange = safeSubtract(stats2.getEarlyLeaveCount(), stats1.getEarlyLeaveCount());
        comparativeStats.setEarlyLeaveCount(earlyLeaveChange);

        // 出勤率变化
        BigDecimal attendanceChange = safeSubtract(stats2.getAttendanceRate(), stats1.getAttendanceRate());
        comparativeStats.setAttendanceRate(attendanceChange);

        // 生产效率变化
        BigDecimal productivityChange = safeSubtractNullable(stats2.getProductivity(), stats1.getProductivity());
        comparativeStats.setProductivity(productivityChange);

        // 平均工时变化
        BigDecimal avgHoursChange = safeSubtract(stats2.getAverageHours(), stats1.getAverageHours());
        comparativeStats.setAverageHours(avgHoursChange);

        return comparativeStats;
    }

    private BigDecimal safeSubtract(BigDecimal a, BigDecimal b) {
        BigDecimal valA = a != null ? a : BigDecimal.ZERO;
        BigDecimal valB = b != null ? b : BigDecimal.ZERO;
        return valA.subtract(valB);
    }

    private BigDecimal safeSubtractNullable(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return null;
        }
        return a.subtract(b);
    }

    private int safeSubtract(Integer a, Integer b) {
        int valA = a != null ? a : 0;
        int valB = b != null ? b : 0;
        return valA - valB;
    }

    private long safeSubtract(Long a, Long b) {
        long valA = a != null ? a : 0L;
        long valB = b != null ? b : 0L;
        return valA - valB;
    }
    @Override
    @Transactional(readOnly = true)
    public TimeStatsDTO getAnomalyStats(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取异常统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);

        // 获取基础统计数据
        TimeStatsDTO stats = getDailyStatsRange(factoryId, startDate, endDate);

        // 查询日期范围内的打卡记录
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = loadRecordsForRange(factoryId, start, end);

        // 异常检测阈值
        int MAX_DAILY_WORK_MINUTES = 12 * 60; // 每天最大工作12小时
        int MIN_DAILY_WORK_MINUTES = 4 * 60;  // 每天最少工作4小时
        double LATE_RATE_THRESHOLD = 0.2;      // 迟到率阈值20%
        double ATTENDANCE_RATE_THRESHOLD = 80; // 出勤率阈值80%

        // 检测异常工时记录
        List<TimeClockRecord> abnormalWorkHours = records.stream()
                .filter(r -> {
                    int minutes = r.getWorkDurationMinutes() != null ? r.getWorkDurationMinutes() : 0;
                    return minutes > MAX_DAILY_WORK_MINUTES || (minutes > 0 && minutes < MIN_DAILY_WORK_MINUTES);
                })
                .collect(Collectors.toList());

        // 异常记录数统计
        long abnormalCount = abnormalWorkHours.size();

        // 迟到率异常检测
        long lateCount = records.stream().filter(this::isLate).count();
        double lateRate = records.isEmpty() ? 0 : (double) lateCount / records.size();
        boolean highLateRate = lateRate > LATE_RATE_THRESHOLD;

        // 出勤率异常检测
        boolean lowAttendance = stats.getAttendanceRate() != null &&
                stats.getAttendanceRate().compareTo(BigDecimal.valueOf(ATTENDANCE_RATE_THRESHOLD)) < 0;

        // 设置异常相关字段（使用现有字段存储异常信息）
        // 使用lateCount存储异常记录总数
        stats.setLateCount(abnormalCount);

        // 使用earlyLeaveCount存储高迟到率标记（1=异常，0=正常）
        stats.setEarlyLeaveCount(highLateRate ? 1L : 0L);

        // 使用absentCount存储低出勤率标记（1=异常，0=正常）
        stats.setAbsentCount(lowAttendance ? 1L : 0L);

        // 添加异常分析到日统计列表
        if (stats.getDailyStatsList() != null) {
            for (TimeStatsDTO.DailyStats daily : stats.getDailyStatsList()) {
                // 标记异常日
                if (daily.getAttendanceRate() != null &&
                    daily.getAttendanceRate().compareTo(BigDecimal.valueOf(ATTENDANCE_RATE_THRESHOLD)) < 0) {
                    daily.setIsWorkday(false); // 重新利用isWorkday标记异常日
                }
            }
        }

        log.info("异常统计完成: 异常记录数={}, 高迟到率={}, 低出勤率={}",
                abnormalCount, highLateRate, lowAttendance);

        return stats;
    }
    @Override
    public String exportStatsReport(String factoryId, LocalDate startDate, LocalDate endDate, String format) {
        log.info("导出统计报告: factoryId={}, startDate={}, endDate={}, format={}",
                factoryId, startDate, endDate, format);

        // 获取统计数据
        TimeStatsDTO stats = getDailyStatsRange(factoryId, startDate, endDate);
        List<TimeStatsDTO.WorkerTimeStats> workerStats = getWorkerTimeStats(factoryId, startDate, endDate, null);

        StringBuilder report = new StringBuilder();

        if ("csv".equalsIgnoreCase(format)) {
            // CSV格式导出
            report.append("工时统计报告\n");
            report.append("统计周期:,").append(startDate).append(",至,").append(endDate).append("\n\n");

            // 汇总数据
            report.append("汇总统计\n");
            report.append("总工时,正常工时,加班工时,活跃员工数,出勤率,生产效率\n");
            report.append(formatValue(stats.getTotalHours())).append(",");
            report.append(formatValue(stats.getRegularHours())).append(",");
            report.append(formatValue(stats.getOvertimeHours())).append(",");
            report.append(stats.getActiveWorkers()).append(",");
            report.append(formatPercentageValue(stats.getAttendanceRate())).append(",");
            report.append(formatPercentageValue(stats.getProductivity())).append("\n\n");

            // 员工明细
            report.append("员工工时明细\n");
            report.append("排名,员工姓名,部门,总工时,正常工时,加班工时,出勤天数,迟到次数,出勤率\n");
            for (TimeStatsDTO.WorkerTimeStats ws : workerStats) {
                report.append(ws.getRanking()).append(",");
                report.append(ws.getWorkerName()).append(",");
                report.append(ws.getDepartment()).append(",");
                report.append(formatValue(ws.getTotalHours())).append(",");
                report.append(formatValue(ws.getRegularHours())).append(",");
                report.append(formatValue(ws.getOvertimeHours())).append(",");
                report.append(ws.getAttendanceDays()).append(",");
                report.append(ws.getLateCount()).append(",");
                report.append(formatPercentageValue(ws.getAttendanceRate())).append("\n");
            }

        } else if ("json".equalsIgnoreCase(format)) {
            // JSON格式导出（简化版）
            report.append("{\n");
            report.append("  \"period\": \"").append(startDate).append(" - ").append(endDate).append("\",\n");
            report.append("  \"totalHours\": ").append(formatValue(stats.getTotalHours())).append(",\n");
            report.append("  \"regularHours\": ").append(formatValue(stats.getRegularHours())).append(",\n");
            report.append("  \"overtimeHours\": ").append(formatValue(stats.getOvertimeHours())).append(",\n");
            report.append("  \"activeWorkers\": ").append(stats.getActiveWorkers()).append(",\n");
            report.append("  \"attendanceRate\": ").append(formatValue(stats.getAttendanceRate())).append(",\n");
            report.append("  \"productivity\": ").append(formatJsonValue(stats.getProductivity())).append(",\n");
            report.append("  \"workerCount\": ").append(workerStats.size()).append("\n");
            report.append("}");

        } else {
            // 默认文本格式
            report.append("===============================\n");
            report.append("       工时统计报告\n");
            report.append("===============================\n\n");
            report.append("统计周期: ").append(startDate).append(" 至 ").append(endDate).append("\n\n");
            report.append("【汇总数据】\n");
            report.append("总工时: ").append(formatValue(stats.getTotalHours())).append(" 小时\n");
            report.append("正常工时: ").append(formatValue(stats.getRegularHours())).append(" 小时\n");
            report.append("加班工时: ").append(formatValue(stats.getOvertimeHours())).append(" 小时\n");
            report.append("活跃员工数: ").append(stats.getActiveWorkers()).append(" 人\n");
            report.append("出勤率: ").append(formatPercentageValue(stats.getAttendanceRate())).append("\n");
            report.append("生产效率: ").append(formatPercentageValue(stats.getProductivity())).append("\n\n");
            report.append("【员工排名Top10】\n");
            int count = 0;
            for (TimeStatsDTO.WorkerTimeStats ws : workerStats) {
                if (++count > 10) break;
                report.append(ws.getRanking()).append(". ")
                      .append(ws.getWorkerName())
                      .append(" (").append(ws.getDepartment()).append(") - ")
                      .append(formatValue(ws.getTotalHours())).append("小时\n");
            }
        }

        log.info("统计报告导出完成: 格式={}, 长度={}", format, report.length());
        return report.toString();
    }

    private String formatValue(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP).toString() : "0.00";
    }

    private String formatPercentageValue(BigDecimal value) {
        return value != null ? value.setScale(2, RoundingMode.HALF_UP) + "%" : "不可用";
    }

    private String formatJsonValue(BigDecimal value) {
        return value != null ? formatValue(value) : "null";
    }
    @Override
    @Transactional(readOnly = true)
    public List<TimeStatsDTO.DailyStats> getStatsTrend(String factoryId, LocalDate startDate, LocalDate endDate) {
        log.info("获取统计趋势: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO stats = getDailyStatsRange(factoryId, startDate, endDate);
        return stats.getDailyStatsList();
    }
    @Override
    @Transactional
    public void cleanupOldStats(String factoryId, Integer retentionDays) {
        log.info("清理过期统计数据: factoryId={}, retentionDays={}", factoryId, retentionDays);
        LocalDate cutoffDate = LocalDate.now().minusDays(retentionDays);
        LocalDateTime cutoffDateTime = cutoffDate.atStartOfDay();

        // 删除过期的打卡记录（软删除方式，设置deleted_at）
        List<TimeClockRecord> oldRecords = timeClockRecordRepository.findByFactoryIdAndClockDateBetween(
                factoryId, LocalDateTime.MIN, cutoffDateTime);

        int deletedCount = 0;
        for (TimeClockRecord record : oldRecords) {
            if (record.getDeletedAt() == null) {
                record.setDeletedAt(LocalDateTime.now());
                timeClockRecordRepository.save(record);
                deletedCount++;
            }
        }

        log.info("已清理{}之前的统计数据，删除记录数: {}", cutoffDate, deletedCount);
    }
    @Override
    @Transactional
    public void recalculateStats(String factoryId, LocalDate date) {
        log.info("重新计算统计: factoryId={}, date={}", factoryId, date);

        // 查询指定日期的打卡记录
        LocalDateTime startOfDay = date.atStartOfDay();
        LocalDateTime endOfDay = date.plusDays(1).atStartOfDay();
        List<TimeClockRecord> records = timeClockRecordRepository.findByFactoryIdAndClockDateBetween(
                factoryId, startOfDay, endOfDay);

        int updatedCount = 0;
        for (TimeClockRecord record : records) {
            boolean needsUpdate = false;

            // 重新计算工作时长
            if (record.getClockInTime() != null && record.getClockOutTime() != null) {
                long minutes = java.time.Duration.between(
                        record.getClockInTime(), record.getClockOutTime()).toMinutes();
                if (minutes > 0 && (record.getWorkDurationMinutes() == null ||
                        record.getWorkDurationMinutes() != (int) minutes)) {
                    record.setWorkDurationMinutes((int) minutes);
                    needsUpdate = true;
                }
            }

            // 重新计算考勤状态
            String newStatus = calculateAttendanceStatus(record);
            if (newStatus != null && !newStatus.equals(record.getAttendanceStatus())) {
                record.setAttendanceStatus(newStatus);
                needsUpdate = true;
            }

            if (needsUpdate) {
                record.setUpdatedAt(LocalDateTime.now());
                timeClockRecordRepository.save(record);
                updatedCount++;
            }
        }

        log.info("已重新计算{}的统计数据，更新记录数: {}", date, updatedCount);
    }

    /**
     * 计算考勤状态
     */
    private String calculateAttendanceStatus(TimeClockRecord record) {
        boolean late = isLate(record);
        boolean earlyLeave = isEarlyLeave(record);

        if (late && earlyLeave) {
            return "LATE_AND_EARLY_LEAVE";
        } else if (late) {
            return "LATE";
        } else if (earlyLeave) {
            return "EARLY_LEAVE";
        } else {
            return "NORMAL";
        }
    }
    // ========== 私有辅助方法 ==========
    private boolean isWeekend(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY;
    }
}
