package com.cretas.aims.service;

import com.cretas.aims.entity.Factory;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.consent.TrainingConsentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * AI报告定时任务调度器
 *
 * 功能：
 * 1. 每天晚上20:00自动生成所有工厂的日报分析（生产、质量、成本、设备、库存）
 * 2. 每周一早上6点自动生成所有工厂的周报告
 * 3. 每月1日早上6点自动生成所有工厂的月报告
 * 4. 定期清理过期的报告和审计日志
 *
 * 注意：
 * - 定时任务生成的报告不消耗AI配额
 * - 日报保留7天，周报告保留30天，月报告保留90天
 * - 审计日志保留3年（ISO 27001合规）
 *
 * @author Cretas Team
 * @version 1.1.0
 * @since 2025-11-04
 */
@Component
public class AIReportScheduler {
    private static final Logger log = LoggerFactory.getLogger(AIReportScheduler.class);

    @Autowired
    private AIEnterpriseService aiEnterpriseService;

    @Autowired
    private FactoryRepository factoryRepository;

    @Autowired
    private TrainingConsentService trainingConsentService;

    /**
     * 不生成定时 AI 报告的工厂 (逗号分隔)。2026-10-29 MOCK_REST AI writes stopped, owner-approved:
     * 废弃的餐饮模拟租户撤回了训练授权, 其 AI 副本已清理, 不再往 ai_analysis_results 写。
     * 清单与「训练授权已撤回」两条规则取并集; 设为空串只剩撤回规则。
     */
    @Value("${cretas.ai.report.excluded-factories:MOCK_REST}")
    private String excludedFactories = "MOCK_REST";

    /**
     * 过滤掉不该生成定时 AI 报告的工厂: 在排除清单里的, 或训练授权已撤回 (withdrawn_at 非空) 的。
     *
     * <p>授权查不到 ({@code UNKNOWN}: SmartBI 数据源不可用 / 查询出错, 或查询本身抛异常) 时
     * <b>照常生成</b>, 只记一条 warn。理由: 数据源一挂, 每家工厂都会读成 UNKNOWN, 按「查不到就跳过」
     * 会让所有工厂的日报同时停掉; 而必须停的 MOCK_REST 由排除清单确定性兜住, 不依赖这次查询。
     */
    List<Factory> reportableFactories(List<Factory> activeFactories, String kind) {
        Set<String> excluded = new HashSet<>();
        if (excludedFactories != null) {
            for (String id : excludedFactories.split(",")) {
                if (!id.isBlank()) {
                    excluded.add(id.trim());
                }
            }
        }
        List<Factory> result = new ArrayList<>(activeFactories.size());
        for (Factory factory : activeFactories) {
            String factoryId = factory.getId();
            if (excluded.contains(factoryId)) {
                log.info("工厂 {} 在排除清单 cretas.ai.report.excluded-factories 内, 跳过{}", factoryId, kind);
                continue;
            }
            TrainingConsentService.Status status;
            try {
                status = trainingConsentService.status(factoryId);
            } catch (RuntimeException e) {
                status = TrainingConsentService.Status.UNKNOWN;
                log.warn("工厂 {} 训练授权查询异常, 照常生成{}: {}", factoryId, kind, e.toString());
            }
            if (status == TrainingConsentService.Status.WITHDRAWN) {
                log.info("工厂 {} 训练授权已撤回, 跳过{}", factoryId, kind);
                continue;
            }
            if (status == TrainingConsentService.Status.UNKNOWN) {
                log.warn("工厂 {} 训练授权查不到 (UNKNOWN), 照常生成{}", factoryId, kind);
            }
            result.add(factory);
        }
        return result;
    }

    /**
     * 每周一早上6点生成周报告
     * Cron表达式: 0 0 6 * * MON
     * - 秒: 0
     * - 分: 0
     * - 时: 6
     * - 日: *（任意）
     * - 月: *（任意）
     * - 星期: MON（周一）
     */
    @Scheduled(cron = "0 0 6 * * MON")
    @SchedulerLock(name = "AIReportScheduler.generateWeeklyReportsForAllFactories", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateWeeklyReportsForAllFactories() {
        log.info("========== 开始生成周报告 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 获取所有活跃的工厂（直接使用findByIsActiveTrue，避免全表扫描后过滤）
            List<Factory> activeFactories = reportableFactories(factoryRepository.findByIsActiveTrue(), "周报告");

            log.info("找到 {} 个活跃工厂，开始生成周报告", activeFactories.size());

            // 2. 计算上周的时间范围（上周一到上周日）
            LocalDate lastMonday = LocalDate.now().minusWeeks(1).with(java.time.DayOfWeek.MONDAY);
            LocalDate lastSunday = lastMonday.plusDays(6);

            log.info("报告周期: {} 至 {}", lastMonday, lastSunday);

            // 3. 为每个工厂生成周报告
            int successCount = 0;
            int failureCount = 0;

            for (Factory factory : activeFactories) {
                try {
                    aiEnterpriseService.generateWeeklyReport(
                            factory.getId(),
                            lastMonday,
                            lastSunday
                    );
                    successCount++;
                    log.info("✅ 工厂 {} 周报告生成成功", factory.getId());

                    // 避免同时请求过多，间隔2秒
                    Thread.sleep(2000);

                } catch (Exception e) {
                    failureCount++;
                    log.error("❌ 工厂 {} 周报告生成失败: {}", factory.getId(), e.getMessage(), e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 周报告生成完成 ==========");
            log.info("总计: {} 个工厂 | 成功: {} | 失败: {} | 耗时: {}ms",
                    activeFactories.size(), successCount, failureCount, duration);

        } catch (Exception e) {
            log.error("周报告生成任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 每天晚上20:00自动生成所有工厂的日报分析
     * Cron表达式: 0 0 20 * * *
     * - 秒: 0
     * - 分: 0
     * - 时: 20
     * - 日: *（每天）
     * - 月: *（任意）
     * - 星期: *（任意）
     *
     * 分析当天的生产、质量、成本、设备、库存数据
     */
    @Scheduled(cron = "0 0 20 * * *")
    @SchedulerLock(name = "AIReportScheduler.generateDailyReportsForAllFactories", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateDailyReportsForAllFactories() {
        log.info("========== 开始生成日报分析 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 获取所有活跃的工厂
            List<Factory> activeFactories = reportableFactories(factoryRepository.findByIsActiveTrue(), "日报");

            log.info("找到 {} 个活跃工厂，开始生成日报分析", activeFactories.size());

            // 2. 日报分析当天数据
            LocalDate today = LocalDate.now();
            log.info("报告日期: {}", today);

            // 3. 为每个工厂生成日报
            int successCount = 0;
            int failureCount = 0;

            for (Factory factory : activeFactories) {
                try {
                    aiEnterpriseService.generateDailyReport(factory.getId());
                    successCount++;
                    log.info("工厂 {} 日报生成成功", factory.getId());

                    // 避免同时请求过多，间隔1秒
                    Thread.sleep(1000);

                } catch (Exception e) {
                    failureCount++;
                    log.error("工厂 {} 日报生成失败: {}", factory.getId(), e.getMessage(), e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 日报分析生成完成 ==========");
            log.info("总计: {} 个工厂 | 成功: {} | 失败: {} | 耗时: {}ms",
                    activeFactories.size(), successCount, failureCount, duration);

        } catch (Exception e) {
            log.error("日报批量生成任务失败: {}", e.getMessage(), e);
        }
    }

    /**
     * 每月1日早上6点生成月报告
     * Cron表达式: 0 0 6 1 * *
     * - 秒: 0
     * - 分: 0
     * - 时: 6
     * - 日: 1（每月1日）
     * - 月: *（任意）
     * - 星期: *（任意）
     */
    @Scheduled(cron = "0 0 6 1 * *")
    @SchedulerLock(name = "AIReportScheduler.generateMonthlyReportsForAllFactories", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void generateMonthlyReportsForAllFactories() {
        log.info("========== 开始生成月报告 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 1. 获取所有活跃的工厂（直接使用findByIsActiveTrue，避免全表扫描后过滤）
            List<Factory> activeFactories = reportableFactories(factoryRepository.findByIsActiveTrue(), "月报告");

            log.info("找到 {} 个活跃工厂，开始生成月报告", activeFactories.size());

            // 2. 计算上月的时间范围（上月1日到上月最后一天）
            LocalDate lastMonthStart = LocalDate.now().minusMonths(1).withDayOfMonth(1);
            LocalDate lastMonthEnd = lastMonthStart.with(TemporalAdjusters.lastDayOfMonth());

            log.info("报告月份: {} 至 {}", lastMonthStart, lastMonthEnd);

            // 3. 为每个工厂生成月报告
            int successCount = 0;
            int failureCount = 0;

            for (Factory factory : activeFactories) {
                try {
                    aiEnterpriseService.generateMonthlyReport(
                            factory.getId(),
                            lastMonthStart,
                            lastMonthEnd
                    );
                    successCount++;
                    log.info("✅ 工厂 {} 月报告生成成功", factory.getId());

                    // 避免同时请求过多，间隔3秒
                    Thread.sleep(3000);

                } catch (Exception e) {
                    failureCount++;
                    log.error("❌ 工厂 {} 月报告生成失败: {}", factory.getId(), e.getMessage(), e);
                }
            }

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 月报告生成完成 ==========");
            log.info("总计: {} 个工厂 | 成功: {} | 失败: {} | 耗时: {}ms",
                    activeFactories.size(), successCount, failureCount, duration);

        } catch (Exception e) {
            log.error("月报告生成任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 每天凌晨2点清理过期报告
     * Cron表达式: 0 0 2 * * *
     * - 秒: 0
     * - 分: 0
     * - 时: 2
     * - 日: *（每天）
     * - 月: *（任意）
     * - 星期: *（任意）
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "AIReportScheduler.cleanExpiredReports", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void cleanExpiredReports() {
        log.info("========== 开始清理过期报告 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 使用Repository的deleteExpiredReports方法清理
            // 该方法在AIAnalysisResultRepository中定义
            int deletedCount = 0; // TODO: 调用repository.deleteExpiredReports(LocalDateTime.now());

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 过期报告清理完成 ==========");
            log.info("删除 {} 条过期报告 | 耗时: {}ms", deletedCount, duration);

        } catch (Exception e) {
            log.error("过期报告清理任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 每周日凌晨3点清理旧配额记录（保留最近26周即半年）
     * Cron表达式: 0 0 3 * * SUN
     * - 秒: 0
     * - 分: 0
     * - 时: 3
     * - 日: *（任意）
     * - 月: *（任意）
     * - 星期: SUN（周日）
     */
    @Scheduled(cron = "0 0 3 * * SUN")
    @SchedulerLock(name = "AIReportScheduler.cleanOldQuotaRecords", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void cleanOldQuotaRecords() {
        log.info("========== 开始清理旧配额记录 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 保留最近26周（半年）的配额记录
            LocalDate cutoffDate = LocalDate.now().minusWeeks(26);
            int deletedCount = 0; // TODO: 调用quotaUsageRepository.deleteOldQuotaRecords(cutoffDate);

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 旧配额记录清理完成 ==========");
            log.info("删除 {} 条旧配额记录（早于{}） | 耗时: {}ms",
                    deletedCount, cutoffDate, duration);

        } catch (Exception e) {
            log.error("旧配额记录清理任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 每月1日凌晨4点清理旧审计日志（保留3年用于合规）
     * Cron表达式: 0 0 4 1 * *
     * - 秒: 0
     * - 分: 0
     * - 时: 4
     * - 日: 1（每月1日）
     * - 月: *（任意）
     * - 星期: *（任意）
     */
    @Scheduled(cron = "0 0 4 1 * *")
    @SchedulerLock(name = "AIReportScheduler.cleanOldAuditLogs", lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void cleanOldAuditLogs() {
        log.info("========== 开始清理旧审计日志 ==========");
        long startTime = System.currentTimeMillis();

        try {
            // 保留3年的审计日志（ISO 27001合规）
            java.time.LocalDateTime cutoffDate = java.time.LocalDateTime.now().minusYears(3);
            int deletedCount = 0; // TODO: 调用auditLogRepository.deleteOldAuditLogs(cutoffDate);

            long duration = System.currentTimeMillis() - startTime;
            log.info("========== 旧审计日志清理完成 ==========");
            log.info("删除 {} 条旧审计日志（早于{}） | 耗时: {}ms",
                    deletedCount, cutoffDate, duration);

        } catch (Exception e) {
            log.error("旧审计日志清理任务异常: {}", e.getMessage(), e);
        }
    }

    /**
     * 手动触发日报生成（用于测试或补生成）
     */
    public void manualGenerateDailyReport(String factoryId) {
        log.info("手动触发日报生成: factoryId={}", factoryId);
        try {
            aiEnterpriseService.generateDailyReport(factoryId);
            log.info("手动日报生成成功: factoryId={}", factoryId);
        } catch (Exception e) {
            log.error("手动日报生成失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 手动触发周报告生成（用于测试或补生成）
     */
    public void manualGenerateWeeklyReport(String factoryId, LocalDate weekStart, LocalDate weekEnd) {
        log.info("手动触发周报告生成: factoryId={}, week={} to {}", factoryId, weekStart, weekEnd);
        try {
            aiEnterpriseService.generateWeeklyReport(factoryId, weekStart, weekEnd);
            log.info("✅ 手动周报告生成成功: factoryId={}", factoryId);
        } catch (Exception e) {
            log.error("❌ 手动周报告生成失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            throw e;
        }
    }

    /**
     * 手动触发月报告生成（用于测试或补生成）
     */
    public void manualGenerateMonthlyReport(String factoryId, LocalDate monthStart, LocalDate monthEnd) {
        log.info("手动触发月报告生成: factoryId={}, month={} to {}", factoryId, monthStart, monthEnd);
        try {
            aiEnterpriseService.generateMonthlyReport(factoryId, monthStart, monthEnd);
            log.info("✅ 手动月报告生成成功: factoryId={}", factoryId);
        } catch (Exception e) {
            log.error("❌ 手动月报告生成失败: factoryId={}, error={}", factoryId, e.getMessage(), e);
            throw e;
        }
    }
}
