package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.lineage.LineageEdgeOutboxEntry;
import com.cretas.aims.repository.lineage.LineageEdgeOutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 血缘边补写器 —— 把 {@code lineage_edge_outbox} 里写失败（或「已提交、还没来得及写」）的边补上。
 *
 * <p>每分钟一轮，最多 {@link #BATCH} 行，按登记时间从老到新；每行走与在线路径<b>同一个</b>
 * {@link LineageEdgeWriter#writeInNewTransaction}（同一份幂等判据 ⇒ 已经在库里的边判 ALREADY_EXISTS 结掉，不重写）。
 *
 * <p>只取登记超过 {@link #GRACE} 的行：刚登记的由提交后的即时写入负责，补写器不去抢。
 *
 * <p>每轮必打一条 {@code LINEAGE_OUTBOX_SWEEP} 心跳（含 pending / dead 存量）：
 * 「没有补写」有两种含义 —— 没东西可补 / 补写器根本没跑；心跳把它们分开。
 * 存量另有 Micrometer 读数 {@code cretas.lineage.outbox.pending} / {@code .dead}，
 * 以及 {@code GET /api/mobile/{factoryId}/traceability/lineage-write-backlog}。
 */
@Slf4j
@Component
public class LineageEdgeOutboxReconciler {

    static final int BATCH = 200;
    static final Duration GRACE = Duration.ofMinutes(2);

    private final LineageEdgeOutboxRepository repository;
    private final LineageEdgeOutbox outbox;
    private final LineageEdgeWriter edgeWriter;

    private final AtomicLong pendingGauge = new AtomicLong(-1);
    private final AtomicLong deadGauge = new AtomicLong(-1);
    private final AtomicReference<LocalDateTime> lastSweepAt = new AtomicReference<>();

    @Value("${cretas.lineage-outbox.enabled:true}")
    private boolean enabled = true;

    public LineageEdgeOutboxReconciler(LineageEdgeOutboxRepository repository,
                                       LineageEdgeOutbox outbox,
                                       LineageEdgeWriter edgeWriter) {
        this.repository = repository;
        this.outbox = outbox;
        this.edgeWriter = edgeWriter;
    }

    @Autowired(required = false)
    void registerGauges(MeterRegistry meterRegistry) {
        // -1 = 补写器还没跑过一轮（没量到），与 0（量了，没有积压）分开。
        Gauge.builder("cretas.lineage.outbox.pending", pendingGauge, AtomicLong::get).register(meterRegistry);
        Gauge.builder("cretas.lineage.outbox.dead", deadGauge, AtomicLong::get).register(meterRegistry);
    }

    @Scheduled(fixedDelayString = "${cretas.lineage-outbox.fixed-delay-ms:60000}",
            initialDelayString = "${cretas.lineage-outbox.initial-delay-ms:90000}")
    @SchedulerLock(name = "LineageEdgeOutboxReconciler.sweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT20S")
    public void scheduledSweep() {
        if (!enabled) {
            return;
        }
        sweep();
    }

    /** 一轮补写。独立于 {@code @Scheduled}，测试直接驱动。 */
    public SweepResult sweep() {
        LocalDateTime now = LocalDateTime.now();
        List<LineageEdgeOutboxEntry> due = repository.findDue(now, now.minus(GRACE), PageRequest.of(0, BATCH));
        int done = 0;
        int failed = 0;
        for (LineageEdgeOutboxEntry entry : due) {
            LineageRecordOutcome outcome = LineageRecordOutcome.FAILED;
            String error = null;
            try {
                outcome = edgeWriter.writeInNewTransaction(outbox.toEdge(entry), entry.getRefKey());
            } catch (Exception e) {
                error = e.toString();
            }
            try {
                outbox.settle(entry.getId(), outcome, error);
            } catch (Exception e) {
                log.warn("[lineage-outbox] 补写后结账失败, 下一轮按幂等键再结: id={}", entry.getId(), e);
            }
            if (outcome == LineageRecordOutcome.INSERTED || outcome == LineageRecordOutcome.ALREADY_EXISTS) {
                done++;
            } else {
                failed++;
            }
        }
        long pending = repository.countByStatus(LineageEdgeOutboxEntry.STATUS_PENDING);
        long dead = repository.countByStatus(LineageEdgeOutboxEntry.STATUS_DEAD);
        pendingGauge.set(pending);
        deadGauge.set(dead);
        lastSweepAt.set(now);
        if (failed > 0 || dead > 0) {
            log.warn("LINEAGE_OUTBOX_SWEEP picked={} done={} failed={} pending={} dead={}",
                    due.size(), done, failed, pending, dead);
        } else {
            log.info("LINEAGE_OUTBOX_SWEEP picked={} done={} failed={} pending={} dead={}",
                    due.size(), done, failed, pending, dead);
        }
        return new SweepResult(due.size(), done, failed, pending, dead);
    }

    /** 最近一轮跑完的时刻；本实例还没跑过为 null（多槽部署时另一槽可能在跑）。 */
    public LocalDateTime lastSweepAt() {
        return lastSweepAt.get();
    }

    public record SweepResult(int picked, int done, int failed, long pending, long dead) {}
}
