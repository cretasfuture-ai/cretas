package com.cretas.aims.scheduler;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.util.InventoryExpiry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 成品批次每日过期置位 —— 仿照原料的 {@code MaterialBatchServiceImpl#autoCheckAndUpdateExpiredBatches}。
 *
 * <p>为什么在「出货/分配当下比日期」之外还要它: 动作时校验只拦得住动作, 拦不住<b>展示</b>。
 * 状态不变, 库存页、可售汇总、备货看板、成品作投料来源 ({@code findAvailableForFeedByFactory})、
 * 调拨候选都会继续把过期成品当成可售 AVAILABLE —— 这些读方都只看 {@code status='AVAILABLE'}。
 * 置成 {@link FinishedGoodsBatch.Status#EXPIRED} 后它们统一按状态排除, 而报损
 * ({@code DisposalRecordService}) 本就接受 EXPIRED 批次, 过期品有出路。
 *
 * <p>口径: {@link InventoryExpiry} —— 到期日当天仍可售, 次日起过期 (与原料一致);
 * 到期日为空的批次不动。东八区 02:05 执行 (原料 02:00 之后, 互不抢锁)。
 * 置位之后到下次执行之间的窗口由动作时校验
 * ({@code FinishedGoodsBatchSaleabilityGuard}) 兜住, 两层不互相依赖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinishedGoodsExpiryScheduler {

    private final FinishedGoodsBatchRepository finishedGoodsBatchRepository;

    @Value("${cretas.finished-goods.expiry-scheduler.enabled:true}")
    private boolean enabled = true;

    @Scheduled(cron = "${cretas.finished-goods.expiry-scheduler.cron:0 5 2 * * ?}", zone = "Asia/Shanghai")
    @SchedulerLock(name = "FinishedGoodsExpiryScheduler.markExpiredBatches",
            lockAtMostFor = "PT30M", lockAtLeastFor = "PT1M")
    public void dailyExpiryCheck() {
        if (!enabled) {
            log.info("[FinishedGoodsExpiryScheduler] 已禁用 (enabled=false), 本次不置位");
            return;
        }
        markExpiredBatches(InventoryExpiry.today());
    }

    /** 汇总日志里最多列出的失败批号个数。 */
    static final int MAX_FAILED_BATCH_NUMBERS_LOGGED = 20;

    /**
     * 一次置位的汇总。{@code failedBatchNumbers} 是<b>全部</b>失败批号 (日志里只列前
     * {@link #MAX_FAILED_BATCH_NUMBERS_LOGGED} 个)。
     */
    public record Result(int candidates, int marked, int skipped, int failed, List<String> failedBatchNumbers) {
    }

    /**
     * 把到期日早于 {@code today} 且仍是 AVAILABLE 的成品批次置为 EXPIRED。
     *
     * <p><b>逐批隔离</b>: 成品批带 {@code @Version}, 一批撞上乐观锁 (同一时刻有人在发货/调整它)
     * 或别的异常, 只记这一批失败, 其余批次、其余工厂照常置位 —— 不能让一批卡住全部。
     * 本方法<b>刻意不加</b> {@code @Transactional}: 每次 {@code save} 是仓库自己的独立事务,
     * 失败的那批回滚不牵连别的批; 若外层包了事务, 一次失败会把整个事务标成 rollback-only,
     * 逐批隔离就失效了。失败的批次下一次执行会再被选中 (状态仍是 AVAILABLE),
     * 期间由出货/分配闸按日期兜住。
     *
     * <p>失败不静默: 每批失败打 ERROR (带异常), 结束时汇总成功数 / 失败数 / 失败批号。
     */
    public Result markExpiredBatches(LocalDate today) {
        List<FinishedGoodsBatch> expired = finishedGoodsBatchRepository.findAllExpiredAvailableBatches(today);
        int marked = 0;
        int skipped = 0;
        List<String> failedBatchNumbers = new ArrayList<>();
        for (FinishedGoodsBatch batch : expired) {
            // 查询之后再判一次: 查询口径与 InventoryExpiry 漂开时, 宁可不动也不误置
            if (!FinishedGoodsBatch.Status.AVAILABLE.equals(batch.getStatus())
                    || !InventoryExpiry.isExpired(batch.getExpireDate(), today)) {
                skipped++;
                log.warn("[FinishedGoodsExpiryScheduler] 跳过不满足过期口径的批次: factoryId={}, batchNumber={}, status={}, expireDate={}",
                        batch.getFactoryId(), batch.getBatchNumber(), batch.getStatus(), batch.getExpireDate());
                continue;
            }
            try {
                batch.setStatus(FinishedGoodsBatch.Status.EXPIRED);
                finishedGoodsBatchRepository.save(batch);
                marked++;
                log.info("[FinishedGoodsExpiryScheduler] 成品批次置过期: factoryId={}, batchNumber={}, expireDate={}",
                        batch.getFactoryId(), batch.getBatchNumber(), batch.getExpireDate());
            } catch (RuntimeException e) {
                failedBatchNumbers.add(batch.getBatchNumber());
                log.error("[FinishedGoodsExpiryScheduler] 成品批次置过期失败, 继续处理其余批次: factoryId={}, batchNumber={}, expireDate={}",
                        batch.getFactoryId(), batch.getBatchNumber(), batch.getExpireDate(), e);
            }
        }
        Result result = new Result(expired.size(), marked, skipped, failedBatchNumbers.size(),
                List.copyOf(failedBatchNumbers));
        if (result.failed() > 0) {
            List<String> shown = failedBatchNumbers.subList(
                    0, Math.min(failedBatchNumbers.size(), MAX_FAILED_BATCH_NUMBERS_LOGGED));
            log.error("[FinishedGoodsExpiryScheduler] 完成成品过期检查(有失败): today={}, 候选={}, 成功={}, 跳过={}, 失败={}, 失败批号(前{}个)={}",
                    today, result.candidates(), result.marked(), result.skipped(), result.failed(),
                    MAX_FAILED_BATCH_NUMBERS_LOGGED, shown);
        } else {
            log.info("[FinishedGoodsExpiryScheduler] 完成成品过期检查: today={}, 候选={}, 成功={}, 跳过={}, 失败=0",
                    today, result.candidates(), result.marked(), result.skipped());
        }
        return result;
    }
}
