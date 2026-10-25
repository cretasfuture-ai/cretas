package com.cretas.aims.scheduler;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("成品过期定时任务 — 仿原料, 到期次日置 EXPIRED")
class FinishedGoodsExpirySchedulerTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 24);

    @Mock private FinishedGoodsBatchRepository repository;
    @InjectMocks private FinishedGoodsExpiryScheduler scheduler;

    @Test
    @DisplayName("昨天到期的 AVAILABLE 批次置 EXPIRED 并保存; 查询漏进来的今天到期批不动")
    void marksOnlyBatchesStrictlyBeforeToday() {
        FinishedGoodsBatch yesterday = batch("FG-Y", TODAY.minusDays(1));
        FinishedGoodsBatch todayBatch = batch("FG-T", TODAY);   // 模拟查询口径漂移
        when(repository.findAllExpiredAvailableBatches(TODAY)).thenReturn(List.of(yesterday, todayBatch));

        FinishedGoodsExpiryScheduler.Result result = scheduler.markExpiredBatches(TODAY);

        assertThat(result.marked()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(yesterday.getStatus()).isEqualTo(FinishedGoodsBatch.Status.EXPIRED);
        assertThat(todayBatch.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        verify(repository).save(yesterday);
        verify(repository, never()).save(todayBatch);
    }

    @Test
    @DisplayName("逐批隔离: 中间一批乐观锁失败, 前后批次照常置过期, 汇总失败数 1 并带批号")
    void oneFailingBatchDoesNotStopTheRest() {
        FinishedGoodsBatch first = batch("FG-1", TODAY.minusDays(2));
        FinishedGoodsBatch locked = batch("FG-LOCKED", TODAY.minusDays(1));
        FinishedGoodsBatch last = batch("FG-3", TODAY.minusDays(1));
        when(repository.findAllExpiredAvailableBatches(TODAY)).thenReturn(List.of(first, locked, last));
        when(repository.save(org.mockito.ArgumentMatchers.any(FinishedGoodsBatch.class))).thenAnswer(call -> {
            FinishedGoodsBatch saved = call.getArgument(0);
            if (saved == locked) {
                throw new ObjectOptimisticLockingFailureException(FinishedGoodsBatch.class, "id-FG-LOCKED");
            }
            return saved;
        });

        FinishedGoodsExpiryScheduler.Result result = scheduler.markExpiredBatches(TODAY);

        assertThat(result.candidates()).isEqualTo(3);
        assertThat(result.marked()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.failedBatchNumbers()).containsExactly("FG-LOCKED");
        assertThat(first.getStatus()).isEqualTo(FinishedGoodsBatch.Status.EXPIRED);
        assertThat(last.getStatus()).isEqualTo(FinishedGoodsBatch.Status.EXPIRED);
        verify(repository).save(first);
        verify(repository).save(last);
    }

    @Test
    @DisplayName("逐批隔离的前提: 置位方法与类都不包外层事务 (否则一次失败把整个事务标成 rollback-only)")
    void markingIsNotWrappedInOneTransaction() throws Exception {
        assertThat(FinishedGoodsExpiryScheduler.class
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(FinishedGoodsExpiryScheduler.class.getMethod("markExpiredBatches", LocalDate.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(FinishedGoodsExpiryScheduler.class.getMethod("dailyExpiryCheck")
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("定时表达式按东八区执行, 与原料同在 02 点段")
    void cronRunsInShanghaiZone() throws Exception {
        Scheduled scheduled = FinishedGoodsExpiryScheduler.class
                .getMethod("dailyExpiryCheck").getAnnotation(Scheduled.class);
        assertThat(scheduled.zone()).isEqualTo("Asia/Shanghai");
        assertThat(scheduled.cron()).contains("0 5 2 * * ?");
    }

    private static FinishedGoodsBatch batch(String batchNumber, LocalDate expireDate) {
        FinishedGoodsBatch batch = new FinishedGoodsBatch();
        batch.setId("id-" + batchNumber);
        batch.setFactoryId("F006");
        batch.setBatchNumber(batchNumber);
        batch.setStatus(FinishedGoodsBatch.Status.AVAILABLE);
        batch.setExpireDate(expireDate);
        return batch;
    }
}
