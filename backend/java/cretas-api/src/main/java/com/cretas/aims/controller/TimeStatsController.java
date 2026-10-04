package com.cretas.aims.controller;

import com.cretas.aims.dto.TimeStatsDTO;
import com.cretas.aims.annotation.RequireModule;
import com.cretas.aims.annotation.RequirePermission;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TimeStatsService;
import com.cretas.aims.service.UserModuleAccessService;
import com.cretas.aims.service.config.FactoryConfigService;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

/**
 * 时间统计控制器
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-01-09
 */
@Slf4j
@RestController
@RequestMapping("/api/mobile/{factoryId}/time-stats")
@Tag(name = "时间统计管理", description = "工时统计和考勤分析相关接口，包括日/周/月/年度统计、日期范围统计、按工作类型/部门分析、生产力分析、员工工时排名、实时统计、期间对比分析、异常统计、趋势分析、报告导出及数据清理/重算等功能")
@RequiredArgsConstructor
public class TimeStatsController {

    private final TimeStatsService timeStatsService;
    private final PermissionService permissionService;
    private final UserModuleAccessService userModuleAccessService;
    private final FactoryConfigService factoryConfigService;
    private final UserRepository userRepository;

    private static final String HR_EMPLOYEE_MODULE = "hr_employee";
    private static final String[] HR_READ_PERMISSIONS = {"hr:read", "hr:read_write"};

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/daily")
    @Operation(summary = "获取日统计")
    public ApiResponse<TimeStatsDTO> getDailyStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "日期") LocalDate date) {
        log.debug("获取日统计: factoryId={}, date={}", factoryId, date);
        TimeStatsDTO stats = timeStatsService.getDailyStats(factoryId, date);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/daily/range")
    @Operation(summary = "获取日期范围统计")
    public ApiResponse<TimeStatsDTO> getDailyStatsRange(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.debug("获取日期范围统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO stats = timeStatsService.getDailyStatsRange(factoryId, startDate, endDate);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/weekly")
    @Operation(summary = "获取周统计")
    public ApiResponse<TimeStatsDTO> getWeeklyStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "年份") Integer year,
            @RequestParam @Parameter(description = "周数") Integer week) {
        log.debug("获取周统计: factoryId={}, year={}, week={}", factoryId, year, week);
        TimeStatsDTO stats = timeStatsService.getWeeklyStats(factoryId, year, week);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/monthly")
    @Operation(summary = "获取月统计")
    public ApiResponse<TimeStatsDTO> getMonthlyStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "年份") Integer year,
            @RequestParam @Parameter(description = "月份") Integer month) {
        log.debug("获取月统计: factoryId={}, year={}, month={}", factoryId, year, month);
        TimeStatsDTO stats = timeStatsService.getMonthlyStats(factoryId, year, month);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/yearly")
    @Operation(summary = "获取年统计")
    public ApiResponse<TimeStatsDTO> getYearlyStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @Parameter(description = "年份") Integer year) {
        log.debug("获取年统计: factoryId={}, year={}", factoryId, year);
        TimeStatsDTO stats = timeStatsService.getYearlyStats(factoryId, year);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/by-work-type")
    @Operation(summary = "按工作类型统计")
    public ApiResponse<TimeStatsDTO> getStatsByWorkType(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.debug("按工作类型统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO stats = timeStatsService.getStatsByWorkType(factoryId, startDate, endDate);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/by-department")
    @Operation(summary = "按部门统计")
    public ApiResponse<TimeStatsDTO> getStatsByDepartment(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.debug("按部门统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO stats = timeStatsService.getStatsByDepartment(factoryId, startDate, endDate);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/productivity")
    @Operation(summary = "获取生产力分析")
    public ApiResponse<TimeStatsDTO.ProductivityAnalysis> getProductivityAnalysis(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.debug("获取生产力分析: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO.ProductivityAnalysis analysis =
            timeStatsService.getProductivityAnalysis(factoryId, startDate, endDate);
        return ApiResponse.success(analysis);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/workers")
    @Operation(summary = "获取员工时间统计")
    public ApiResponse<List<TimeStatsDTO.WorkerTimeStats>> getWorkerTimeStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate,
            @RequestParam(required = false, defaultValue = "10")
            @Parameter(description = "排名前N") Integer topN) {
        log.debug("获取员工时间统计: factoryId={}, startDate={}, endDate={}, topN={}",
                factoryId, startDate, endDate, topN);
        List<TimeStatsDTO.WorkerTimeStats> stats =
            timeStatsService.getWorkerTimeStats(factoryId, startDate, endDate, topN);
        return ApiResponse.success(stats);
    }

    @GetMapping("/workers/{workerId}")
    @Operation(summary = "获取员工个人时间统计")
    public ResponseEntity<ApiResponse<TimeStatsDTO.WorkerTimeStats>> getWorkerTimeStatsById(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @PathVariable @Parameter(description = "员工ID") Integer workerId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate,
            HttpServletRequest request) {
        log.debug("获取员工个人时间统计: factoryId={}, workerId={}, startDate={}, endDate={}",
                factoryId, workerId, startDate, endDate);
        if (parseActorId(request.getAttribute("userId")) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), "未登录，无法访问员工个人时间统计"));
        }
        if (!canReadPersonalStats(factoryId, workerId, request)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(ApiResponse.error(HttpStatus.FORBIDDEN.value(), "无权访问该员工个人时间统计"));
        }
        TimeStatsDTO.WorkerTimeStats stats =
            timeStatsService.getWorkerTimeStatsById(factoryId, workerId, startDate, endDate);
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/realtime")
    @Operation(summary = "获取工时实时统计")
    public ApiResponse<TimeStatsDTO> getRealtimeTimeStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId) {
        log.debug("获取工时实时统计: factoryId={}", factoryId);
        TimeStatsDTO stats = timeStatsService.getRealtimeStats(factoryId);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/comparative")
    @Operation(summary = "获取对比分析")
    public ApiResponse<TimeStatsDTO> getComparativeStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间1开始日期") LocalDate period1Start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间1结束日期") LocalDate period1End,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间2开始日期") LocalDate period2Start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "期间2结束日期") LocalDate period2End) {
        log.debug("获取对比分析: factoryId={}, period1={} to {}, period2={} to {}",
                factoryId, period1Start, period1End, period2Start, period2End);
        TimeStatsDTO stats = timeStatsService.getComparativeStats(
            factoryId, period1Start, period1End, period2Start, period2End);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/anomaly")
    @Operation(summary = "获取异常统计")
    public ApiResponse<TimeStatsDTO> getAnomalyStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.debug("获取异常统计: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        TimeStatsDTO stats = timeStatsService.getAnomalyStats(factoryId, startDate, endDate);
        return ApiResponse.success(stats);
    }

    @RequirePermission({"hr:read", "hr:read_write"})
    @RequireModule(HR_EMPLOYEE_MODULE)
    @GetMapping("/trend")
    @Operation(summary = "获取统计趋势")
    public ApiResponse<List<TimeStatsDTO.DailyStats>> getStatsTrend(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate) {
        log.debug("获取统计趋势: factoryId={}, startDate={}, endDate={}",
                factoryId, startDate, endDate);
        List<TimeStatsDTO.DailyStats> trend =
            timeStatsService.getStatsTrend(factoryId, startDate, endDate);
        return ApiResponse.success(trend);
    }

    private boolean canReadPersonalStats(String factoryId, Integer workerId,
                                         HttpServletRequest request) {
        Long actorId = parseActorId(request.getAttribute("userId"));
        if (actorId == null || workerId == null) {
            return false;
        }

        // ⚠️ Branch on actor type BEFORE any users lookup: a platform admin's id is from
        // platform_admins, an independent sequence — it would pass the self check below against
        // the worker who happens to share that id, or borrow that worker's HR grants.
        AuthenticatedActor platformActor = AuthenticatedActor.current(request)
                .filter(AuthenticatedActor::isPlatformAdmin)
                .orElse(null);
        if (platformActor != null) {
            return platformActor.hasFactoryBypass()
                    && findActiveUser(workerId.longValue(), factoryId) != null;
        }

        User actor = findActiveUser(actorId, factoryId);
        User target = findActiveUser(workerId.longValue(), factoryId);
        if (actor == null || target == null) {
            return false;
        }

        // The personal attendance path is deliberately self-scoped for all employees and
        // does not require the HR module. It must still be same-factory and active.
        if (actorId.equals(workerId.longValue())) {
            return true;
        }

        // Reading another worker requires the canonical HR read permission plus both
        // account-level and factory-level hr_employee gates. A module GRANT alone
        // must not manufacture the canonical HR permission.
        return permissionService.hasAnyPermission(actor, HR_READ_PERMISSIONS)
                && userModuleAccessService.canAccessModule(actor, HR_EMPLOYEE_MODULE)
                && factoryConfigService.isModuleEnabled(factoryId, HR_EMPLOYEE_MODULE);
    }

    private User findActiveUser(Long userId, String factoryId) {
        return userRepository.findByIdAndFactoryId(userId, factoryId)
                .filter(user -> Boolean.TRUE.equals(user.getIsActive()))
                .orElse(null);
    }

    private Long parseActorId(Object actorId) {
        if (actorId == null) {
            return null;
        }
        try {
            Long parsed = Long.valueOf(String.valueOf(actorId));
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @RequirePermission({"hr:read_write"})
    @PostMapping("/export")
    @RequireModule(HR_EMPLOYEE_MODULE)
    @Operation(summary = "导出统计报告")
    public ApiResponse<String> exportStatsReport(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "开始日期") LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "结束日期") LocalDate endDate,
            @RequestParam(defaultValue = "CSV")
            @Parameter(description = "导出格式") String format) {
        log.info("导出统计报告: factoryId={}, startDate={}, endDate={}, format={}",
                factoryId, startDate, endDate, format);
        String report = timeStatsService.exportStatsReport(factoryId, startDate, endDate, format);
        return ApiResponse.success(report);
    }

    @RequirePermission({"hr:read_write"})
    @DeleteMapping("/cleanup")
    @Operation(summary = "清理过期统计数据")
    public ApiResponse<Void> cleanupOldStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam(defaultValue = "90")
            @Parameter(description = "保留天数") Integer retentionDays) {
        log.info("清理过期统计数据: factoryId={}, retentionDays={}", factoryId, retentionDays);
        timeStatsService.cleanupOldStats(factoryId, retentionDays);
        return ApiResponse.success();
    }

    @RequirePermission({"hr:read_write"})
    @PostMapping("/recalculate")
    @Operation(summary = "重新计算统计")
    public ApiResponse<Void> recalculateStats(
            @PathVariable @Parameter(description = "工厂ID") String factoryId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            @Parameter(description = "日期") LocalDate date) {
        log.info("重新计算统计: factoryId={}, date={}", factoryId, date);
        timeStatsService.recalculateStats(factoryId, date);
        return ApiResponse.success();
    }
}
