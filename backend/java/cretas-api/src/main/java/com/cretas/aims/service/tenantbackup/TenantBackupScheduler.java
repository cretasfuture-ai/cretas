package com.cretas.aims.service.tenantbackup;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 按工厂备份的定时任务。默认 01:20 / 04:20 / 05:20 各跑一次 (避开 03:00 的整库灾备); 每次只做到期的工厂,
 * 失败的在下一次自动重试。蓝绿两个 JVM 同时在时由 ShedLock 保证只有一个执行。
 * 只在 {@code cretas.tenant-backup.enabled=true} 时装配 (生产打开, 开发 / 测试默认关)。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "cretas.tenant-backup", name = "enabled", havingValue = "true")
public class TenantBackupScheduler {

    private final TenantBackupService service;

    public TenantBackupScheduler(TenantBackupService service) {
        this.service = service;
    }

    @Scheduled(cron = "${cretas.tenant-backup.cron:0 20 1,4,5 * * *}", zone = "Asia/Shanghai")
    @SchedulerLock(name = "tenantBackupTick", lockAtMostFor = "PT5H", lockAtLeastFor = "PT1M")
    public void tick() {
        try {
            service.tick();
        } catch (RuntimeException e) {
            // 配置不对 (没配调度管理员 / 不在导出白名单) 时整轮不跑: 必须让人看到
            log.error("{} 本轮工厂备份没有执行: {}", TenantBackupService.ALERT_MARKER, e.getMessage(), e);
        }
    }
}
