package com.cretas.aims.scheduler;

import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.service.shortage.DailyReconcileRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 每天扫一次所有启用的工厂, 每个工厂留一条运行记录 (daily_reconcile_runs)。
 * 阈值没配的工厂不再被静默跳过: 记一条「没量到」及原因 (见 {@link DailyReconcileRunner})。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostDeviationScanScheduler {

    public static final String TRIGGER = "DAILY_SCAN";

    private final FactoryRepository factoryRepository;
    private final DailyReconcileRunner runner;

    @Scheduled(cron = "${cretas.cost-deviation-scan.cron:0 40 6 * * ?}")
    public void scanConfiguredFactories() {
        List<String> factoryIds = factoryRepository.findAllActiveFactoryIds();
        log.info("每日差异核对开始: 启用工厂 {} 个", factoryIds.size());
        for (String factoryId : factoryIds) {
            try {
                runner.run(factoryId, TRIGGER);
            } catch (RuntimeException e) {
                // runner 自己已把扫描失败记成 FAILED; 走到这里是连运行记录都没写成
                log.error("每日差异核对连运行记录都没写成 factory={}", factoryId, e);
            }
        }
        log.info("每日差异核对结束: 启用工厂 {} 个", factoryIds.size());
    }
}
