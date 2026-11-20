package com.cretas.aims.service.shortage;

import com.cretas.aims.entity.shortage.DailyReconcileRun;
import com.cretas.aims.entity.shortage.DailyReconcileRun.Status;
import com.cretas.aims.repository.shortage.DailyReconcileRunRepository;
import com.cretas.aims.service.shortage.CostDeviationScanService.ScanResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 每日差异核对: 扫描一个工厂并留一条运行记录。
 *
 * <p>三态不合并: 量到了 (MEASURED) / 部分没量到 (PARTIAL) / 没量到 (NOT_MEASURED, 必带原因) / 扫描失败 (FAILED)。
 * ⛔ 阈值没配、近 30 天没有可评估的计划、全部算不出, 都不能记成「正常」或静默跳过。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyReconcileRunner {

    private final CostDeviationScanService scanService;
    private final DailyReconcileRunRepository runRepository;

    public DailyReconcileRun run(String factoryId, String trigger) {
        DailyReconcileRun run = new DailyReconcileRun();
        run.setFactoryId(factoryId);
        run.setTriggerSource(trigger);
        run.setStartedAt(LocalDateTime.now());
        try {
            var eff = scanService.effectiveThresholds(factoryId);
            run.setThresholdSource(eff.source());
            run.setVarianceConfigured(eff.thresholds().variancePct() != null);
            run.setBalanceConfigured(eff.thresholds().balancePct() != null);
            if (eff.source() == null) {
                run.setStatus(Status.NOT_MEASURED);
                run.setNotMeasuredReason("本厂没有配置成本差异和物料平衡阈值, 系统也没有默认阈值, 本次没有扫描");
            } else {
                fill(run, scanService.scan(factoryId, trigger));
            }
        } catch (RuntimeException e) {
            log.error("每日差异核对失败 factory={}", factoryId, e);
            run.setStatus(Status.FAILED);
            run.setNotMeasuredReason("扫描失败: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        run.setFinishedAt(LocalDateTime.now());
        DailyReconcileRun saved = runRepository.save(run);
        log.info("每日差异核对 factory={} trigger={} status={} reason={} evaluated={} created={} notified={} notifyFailed={}",
                factoryId, trigger, saved.getStatus(), saved.getNotMeasuredReason(), saved.getEvaluatedCount(),
                saved.getCreatedCount(), saved.getNotifiedCount(), saved.getNotifyFailedCount());
        return saved;
    }

    private static void fill(DailyReconcileRun run, ScanResult r) {
        run.setEvaluatedCount(r.evaluated());
        run.setCreatedCount(r.created());
        run.setRefreshedCount(r.refreshed());
        run.setUnknownCount(r.unknown().size());
        run.setFailedCount(r.failed());
        run.setNotifiedCount(r.notified());
        run.setNotifyFailedCount(r.notifyFailed());
        List<String> notes = new ArrayList<>();
        if (r.evaluated() == 0) {
            run.setStatus(Status.NOT_MEASURED);
            run.setNotMeasuredReason("近 30 天没有可评估的生产计划, 没有量到任何读数");
            return;
        }
        if (r.unknown().size() + r.failed() >= r.evaluated()) {
            run.setStatus(Status.NOT_MEASURED);
            run.setNotMeasuredReason("评估了 " + r.evaluated() + " 张计划, 全部算不出或算失败 (算不出 "
                    + r.unknown().size() + ", 失败 " + r.failed() + "), 没有可用读数");
            return;
        }
        if (!r.varianceConfigured()) notes.add("成本差异阈值未配置, 未扫成本差异");
        if (!r.balanceConfigured()) notes.add("物料平衡阈值未配置, 未扫物料平衡");
        if (r.unknown().size() > 0) notes.add(r.unknown().size() + " 张计划算不出");
        if (r.failed() > 0) notes.add(r.failed() + " 张计划算失败");
        if (r.notifyFailed() > 0) notes.add(r.notifyFailed() + " 条新异常推送失败");
        if (notes.isEmpty()) {
            run.setStatus(Status.MEASURED);
        } else {
            run.setStatus(Status.PARTIAL);
            run.setNotMeasuredReason(String.join("; ", notes));
        }
    }
}
