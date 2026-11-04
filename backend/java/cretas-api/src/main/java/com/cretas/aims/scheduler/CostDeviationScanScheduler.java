package com.cretas.aims.scheduler;

import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.service.shortage.CostDeviationScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每天扫一次配了告警阈值的工厂; 没配的工厂不扫、不写。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CostDeviationScanScheduler {

    private final FactorySettingsRepository settingsRepository;
    private final CostDeviationScanService scanService;

    @Scheduled(cron = "${cretas.cost-deviation-scan.cron:0 40 6 * * ?}")
    public void scanConfiguredFactories() {
        for (String factoryId : settingsRepository.findFactoryIdsWithCostDeviationThresholds()) {
            try {
                var r = scanService.scan(factoryId, "DAILY_SCAN");
                log.info("成本损耗异常扫描 factory={} evaluated={} created={} refreshed={} unknown={} failed={}",
                        factoryId, r.evaluated(), r.created(), r.refreshed(), r.unknown().size(), r.failed());
            } catch (RuntimeException e) {
                log.error("成本损耗异常扫描失败 factory={}", factoryId, e);
            }
        }
    }
}
