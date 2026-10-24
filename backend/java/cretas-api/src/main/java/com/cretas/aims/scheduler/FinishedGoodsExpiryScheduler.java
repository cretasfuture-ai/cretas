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

    /**
     * 把到期日早于 {@code today} 且仍是 AVAILABLE 的成品批次置为 EXPIRED。
     *
     * @return 本次置位的批次数
     */
    public int markExpiredBatches(LocalDate today) {
        List<FinishedGoodsBatch> expired = finishedGoodsBatchRepository.findAllExpiredAvailableBatches(today);
        int marked = 0;
        for (FinishedGoodsBatch batch : expired) {
            // 查询之后再判一次: 查询口径与 InventoryExpiry 漂开时, 宁可不动也不误置
            if (!FinishedGoodsBatch.Status.AVAILABLE.equals(batch.getStatus())
                    || !InventoryExpiry.isExpired(batch.getExpireDate(), today)) {
                log.warn("[FinishedGoodsExpiryScheduler] 跳过不满足过期口径的批次: factoryId={}, batchNumber={}, status={}, expireDate={}",
                        batch.getFactoryId(), batch.getBatchNumber(), batch.getStatus(), batch.getExpireDate());
                continue;
            }
            batch.setStatus(FinishedGoodsBatch.Status.EXPIRED);
            finishedGoodsBatchRepository.save(batch);
            marked++;
            log.info("[FinishedGoodsExpiryScheduler] 成品批次置过期: factoryId={}, batchNumber={}, expireDate={}",
                    batch.getFactoryId(), batch.getBatchNumber(), batch.getExpireDate());
        }
        log.info("[FinishedGoodsExpiryScheduler] 完成成品过期检查: today={}, 候选={}, 置位={}",
                today, expired.size(), marked);
        return marked;
    }
}
