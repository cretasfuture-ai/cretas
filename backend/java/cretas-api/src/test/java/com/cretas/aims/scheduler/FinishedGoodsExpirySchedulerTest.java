package com.cretas.aims.scheduler;

import com.cretas.aims.entity.inventory.FinishedGoodsBatch;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

        int marked = scheduler.markExpiredBatches(TODAY);

        assertThat(marked).isEqualTo(1);
        assertThat(yesterday.getStatus()).isEqualTo(FinishedGoodsBatch.Status.EXPIRED);
        assertThat(todayBatch.getStatus()).isEqualTo(FinishedGoodsBatch.Status.AVAILABLE);
        verify(repository).save(yesterday);
        verify(repository, never()).save(todayBatch);
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
