package com.cretas.aims.service.lineage;

import com.cretas.aims.entity.lineage.BatchLineageEdge;
import com.cretas.aims.entity.lineage.LineageEdgeOutboxEntry;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.lineage.LineageEdgeOutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 🔴 食品 DAG 缺口 02 的回归：<b>业务已提交、写边失败</b>的那条边必须被补上。
 *
 * <p>修之前的行为：afterCommit 里写边失败只打一条 WARN，边永久丢失 —— 本测试第 ① 条在那时是红的
 * （没有台账行可补，补写之后边表里仍然没有这条边）。
 *
 * <p>走真实的 {@link LineageEdgeRecorder} → {@link LineageEdgeOutbox} → {@link LineageEdgeWriter}
 * → {@link LineageEdgeOutboxReconciler}，只把两个 repository 换成内存表；
 * 「库写不进去」由边表的 {@code saveAndFlush} 抛 {@link DataAccessResourceFailureException} 模拟。
 */
@DisplayName("血缘边补写台账 — 写失败的边被补上")
class LineageEdgeOutboxRecoveryContractTest {

    private static final String FACTORY = "F006";

    private final Map<String, LineageEdgeOutboxEntry> outboxTable = new LinkedHashMap<>();
    private final List<BatchLineageEdge> edgeTable = new ArrayList<>();
    private boolean edgeWritesFail;

    private LineageEdgeRecorder recorder;
    private LineageEdgeOutboxReconciler reconciler;
    private LineageEdgeOutboxRepository outboxRepository;

    @BeforeEach
    void setUp() {
        BatchLineageEdgeRepository edgeRepository = mock(BatchLineageEdgeRepository.class);
        when(edgeRepository.findMatchingEdges(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> edgeTable.stream()
                        .filter(e -> e.getEdgeType().equals(inv.getArgument(1))
                                && e.getSourceId().equals(inv.getArgument(3))
                                && e.getTargetId().equals(inv.getArgument(5)))
                        .toList());
        when(edgeRepository.saveAndFlush(any(BatchLineageEdge.class))).thenAnswer(inv -> {
            if (edgeWritesFail) {
                throw new DataAccessResourceFailureException("connection reset");
            }
            edgeTable.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        outboxRepository = mock(LineageEdgeOutboxRepository.class);
        when(outboxRepository.save(any(LineageEdgeOutboxEntry.class))).thenAnswer(inv -> {
            LineageEdgeOutboxEntry e = inv.getArgument(0);
            if (e.getId() == null) {
                e.setId("outbox-" + (outboxTable.size() + 1));
                e.setCreatedAt(LocalDateTime.now());
            }
            if (e.getNextAttemptAt() == null) {
                e.setNextAttemptAt(e.getCreatedAt());
            }
            outboxTable.put(e.getId(), e);
            return e;
        });
        when(outboxRepository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(outboxTable.get(inv.<String>getArgument(0))));
        when(outboxRepository.findDue(any(), any(), any())).thenAnswer(inv -> {
            LocalDateTime now = inv.getArgument(0);
            LocalDateTime createdBefore = inv.getArgument(1);
            return outboxTable.values().stream()
                    .filter(e -> LineageEdgeOutboxEntry.STATUS_PENDING.equals(e.getStatus()))
                    .filter(e -> !e.getNextAttemptAt().isAfter(now) && e.getCreatedAt().isBefore(createdBefore))
                    .toList();
        });
        when(outboxRepository.countByStatus(anyString())).thenAnswer(inv -> outboxTable.values().stream()
                .filter(e -> Objects.equals(e.getStatus(), inv.getArgument(0))).count());

        LineageEdgeWriter writer = new LineageEdgeWriter(edgeRepository);
        LineageEdgeOutbox outbox = new LineageEdgeOutbox(outboxRepository);
        recorder = new LineageEdgeRecorder(writer,
                new FinishedBatchProductionAligner(mock(ProductionBatchRepository.class)), outbox);
        reconciler = new LineageEdgeOutboxReconciler(outboxRepository, outbox, writer);
    }

    @AfterEach
    void clearSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("① 提交后写边失败 ⇒ 台账留 PENDING; 库恢复后补写器把同一条边补上并结掉")
    void edgeThatFailedAfterCommitIsRecoveredByTheReconciler() {
        LineageRecordOutcome outcome = recordInsideBusinessTransactionAndCommit();
        assertThat(outcome).isEqualTo(LineageRecordOutcome.SCHEDULED);

        // 提交后的即时写入失败: 边表空, 台账一行 PENDING
        assertThat(edgeTable).isEmpty();
        assertThat(outboxTable.values()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(LineageEdgeOutboxEntry.STATUS_PENDING);
            assertThat(e.getAttempts()).isEqualTo(1);
            assertThat(e.getRefKey()).isEqualTo("consumption:42");
        });

        // 库恢复, 时间走过宽限期和退避
        edgeWritesFail = false;
        ageOutboxRows();
        LineageEdgeOutboxReconciler.SweepResult sweep = reconciler.sweep();

        assertThat(sweep.done()).isEqualTo(1);
        assertThat(sweep.pending()).isZero();
        assertThat(edgeTable).singleElement().satisfies(e -> {
            assertThat(e.getEdgeType()).isEqualTo(LineageEdgeRecorder.EDGE_RAW_TO_PRODUCTION);
            assertThat(e.getSourceId()).isEqualTo("mb-1");
            assertThat(e.getTargetId()).isEqualTo("100");
            assertThat(e.getMeta()).containsEntry(LineageEdgeRecorder.META_REF_KEY, "consumption:42");
        });
        assertThat(outboxTable.values()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(LineageEdgeOutboxEntry.STATUS_DONE);
            assertThat(e.getResolvedOutcome()).isEqualTo("INSERTED");
        });
    }

    @Test
    @DisplayName("② 即时写入成功 ⇒ 台账当场结掉, 补写器不重写 (边表仍只有 1 条)")
    void successfulImmediateWriteIsSettledAndNotRewritten() {
        edgeWritesFail = false;
        recordInsideBusinessTransactionAndCommit();

        assertThat(edgeTable).hasSize(1);
        assertThat(outboxTable.values()).singleElement()
                .extracting(LineageEdgeOutboxEntry::getStatus).isEqualTo(LineageEdgeOutboxEntry.STATUS_DONE);

        ageOutboxRows();
        assertThat(reconciler.sweep().picked()).isZero();
        assertThat(edgeTable).hasSize(1);
    }

    @Test
    @DisplayName("③ 「已提交、结账前进程退出」⇒ 台账 PENDING 而边已在库: 补写器按幂等键判已存在, 不重写")
    void entryLeftPendingWithEdgeAlreadyWrittenSettlesAsAlreadyExists() {
        edgeWritesFail = false;
        TransactionSynchronizationManager.initSynchronization();
        recorder.recordRawToProduction(FACTORY, "mb-1", 100L, new BigDecimal("5"), "kg", 7L, "consumption:42", null);
        // 只写边、不结账: 模拟进程在两步之间退出
        edgeTable.add(recorderEdgeCopy());
        TransactionSynchronizationManager.clearSynchronization();

        ageOutboxRows();
        reconciler.sweep();

        assertThat(edgeTable).hasSize(1);
        assertThat(outboxTable.values()).singleElement().satisfies(e -> {
            assertThat(e.getStatus()).isEqualTo(LineageEdgeOutboxEntry.STATUS_DONE);
            assertThat(e.getResolvedOutcome()).isEqualTo("ALREADY_EXISTS");
        });
    }

    @Test
    @DisplayName("④ 一直写不进去 ⇒ 到上限转 DEAD, 仍计入积压 (不是放弃了就不算)")
    void persistentFailureEndsDeadAndStillCounts() {
        recordInsideBusinessTransactionAndCommit();
        for (int i = 0; i < LineageEdgeOutbox.MAX_ATTEMPTS; i++) {
            ageOutboxRows();
            reconciler.sweep();
        }

        assertThat(edgeTable).isEmpty();
        assertThat(outboxTable.values()).singleElement()
                .extracting(LineageEdgeOutboxEntry::getStatus).isEqualTo(LineageEdgeOutboxEntry.STATUS_DEAD);
        assertThat(reconciler.sweep().dead()).isEqualTo(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** 模拟一次业务事务: 事务内登记 → 提交 → 跑 afterCommit。写失败由 {@link #edgeWritesFail} 决定（缺省 true）。 */
    private LineageRecordOutcome recordInsideBusinessTransactionAndCommit() {
        TransactionSynchronizationManager.initSynchronization();
        LineageRecordOutcome outcome = recorder.recordRawToProduction(
                FACTORY, "mb-1", 100L, new BigDecimal("5"), "kg", 7L, "consumption:42", null);
        List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
        TransactionSynchronizationManager.clearSynchronization();
        syncs.forEach(TransactionSynchronization::afterCommit);
        return outcome;
    }

    private BatchLineageEdge recorderEdgeCopy() {
        LineageEdgeOutboxEntry entry = outboxTable.values().iterator().next();
        // 台账行里的 meta 已带 refKey (buildEdge 写进去的), 与在线写出的边同一个幂等键
        return new LineageEdgeOutbox(outboxRepository).toEdge(entry);
    }

    /** 把所有台账行推到「过了宽限期、退避已到」。 */
    private void ageOutboxRows() {
        LocalDateTime past = LocalDateTime.now().minusDays(2);
        outboxTable.values().forEach(e -> {
            e.setCreatedAt(past);
            e.setNextAttemptAt(past);
        });
    }

    @BeforeEach
    void writesFailByDefault() {
        edgeWritesFail = true;
    }
}
