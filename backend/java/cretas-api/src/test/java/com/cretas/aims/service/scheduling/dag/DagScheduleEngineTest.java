package com.cretas.aims.service.scheduling.dag;

import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.JobIn;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.JobOut;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.Status;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.TaskIn;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.TaskOut;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class DagScheduleEngineTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 29, 8, 0);

    private static TaskIn task(long id, String station, int order, Integer minutes, Long... preds) {
        return new TaskIn(id, station, "工序" + id, order, minutes, Set.of(preds), false);
    }

    private static TaskOut find(JobOut job, long id) {
        return job.tasks().stream().filter(t -> t.taskId() == id).findFirst().orElseThrow();
    }

    @Test
    void serialChainStartsEachStepWhenItsUpstreamEnds() {
        JobOut job = DagScheduleEngine.schedule(List.of(new JobIn("P1", List.of(
                task(1, "A", 1, 30),
                task(2, "B", 2, 45, 1L),
                task(3, "C", 3, 15, 2L)))), T0).get(0);

        assertThat(job.complete()).isTrue();
        assertThat(find(job, 1).start()).isEqualTo(T0);
        assertThat(find(job, 2).start()).isEqualTo(T0.plusMinutes(30));
        assertThat(find(job, 3).start()).isEqualTo(T0.plusMinutes(75));
        assertThat(find(job, 3).end()).isEqualTo(T0.plusMinutes(90));
        assertThat(job.start()).isEqualTo(T0);
        assertThat(job.end()).isEqualTo(T0.plusMinutes(90));
    }

    @Test
    void parallelBranchesRunAtTheSameTimeAndJoinWaitsForTheSlowerOne() {
        // 1 -> {2 (60min), 3 (20min)} -> 4
        JobOut job = DagScheduleEngine.schedule(List.of(new JobIn("P1", List.of(
                task(1, "A", 1, 10),
                task(2, "B", 2, 60, 1L),
                task(3, "C", 3, 20, 1L),
                task(4, "D", 4, 5, 2L, 3L)))), T0).get(0);

        assertThat(find(job, 2).start()).isEqualTo(T0.plusMinutes(10));
        assertThat(find(job, 3).start()).isEqualTo(T0.plusMinutes(10)); // 并行, 不等 2
        assertThat(find(job, 4).start()).isEqualTo(T0.plusMinutes(70)); // 等慢的那支
        assertThat(job.end()).isEqualTo(T0.plusMinutes(75));
    }

    @Test
    void sameStationRunsOneTaskAtATimeFirstComeFirstServed() {
        JobIn first = new JobIn("P1", List.of(task(1, "MIX", 1, 40)));
        JobIn second = new JobIn("P2", List.of(task(2, "MIX", 1, 30), task(3, "PACK", 2, 10, 2L)));
        List<JobOut> out = DagScheduleEngine.schedule(List.of(first, second), T0);

        assertThat(find(out.get(0), 1).start()).isEqualTo(T0);
        assertThat(find(out.get(1), 2).start()).isEqualTo(T0.plusMinutes(40)); // 等 P1 让出工位
        assertThat(find(out.get(1), 3).start()).isEqualTo(T0.plusMinutes(70));

        // 交换顺序: 先到先排
        List<JobOut> swapped = DagScheduleEngine.schedule(List.of(second, first), T0);
        assertThat(find(swapped.get(0), 2).start()).isEqualTo(T0);
        assertThat(find(swapped.get(1), 1).start()).isEqualTo(T0.plusMinutes(30));
    }

    @Test
    void differentStationsDoNotWaitForEachOther() {
        List<JobOut> out = DagScheduleEngine.schedule(List.of(
                new JobIn("P1", List.of(task(1, "MIX", 1, 40))),
                new JobIn("P2", List.of(task(2, "CUT", 1, 30)))), T0);
        assertThat(find(out.get(1), 2).start()).isEqualTo(T0);
    }

    @Test
    void missingDurationBlocksDownstreamAndTheWholeJobReservesNoStation() {
        // P1: 1(MIX 30) -> 2(COOK 缺工时) -> 3(PACK 10); 4(CUT 20) 只吃原料
        JobIn broken = new JobIn("P1", List.of(
                task(1, "MIX", 1, 30),
                task(2, "COOK", 2, null, 1L),
                task(3, "PACK", 3, 10, 2L),
                task(4, "CUT", 4, 20)));
        JobIn next = new JobIn("P2", List.of(task(5, "MIX", 1, 25)));
        List<JobOut> out = DagScheduleEngine.schedule(List.of(broken, next), T0);
        JobOut b = out.get(0);

        assertThat(b.complete()).isFalse();
        assertThat(b.start()).isNull();
        assertThat(b.end()).isNull();
        assertThat(find(b, 2).status()).isEqualTo(Status.MISSING_DURATION);
        assertThat(find(b, 3).status()).isEqualTo(Status.BLOCKED_BY_MISSING);
        assertThat(find(b, 3).waitingOn()).isEqualTo("工序2");
        assertThat(find(b, 3).start()).isNull();
        assertThat(b.missingDurationNames()).containsExactly("工序2");
        // 不占工位: P2 的 MIX 不被 P1 的 MIX 挤后
        assertThat(find(out.get(1), 5).start()).isEqualTo(T0);
    }

    @Test
    void everyStepMissingMinutesIsNamedNotOnlyTheFirstInTheChain() {
        // 1(A 30) -> 2(B 缺) -> 3(C 缺) -> 4(D 10): B 和 C 都要报, D 只是被卡住
        JobOut job = DagScheduleEngine.schedule(List.of(new JobIn("P1", List.of(
                task(1, "A", 1, 30),
                task(2, "B", 2, null, 1L),
                task(3, "C", 3, null, 2L),
                task(4, "D", 4, 10, 3L)))), T0).get(0);
        assertThat(job.missingDurationNames()).containsExactly("工序2", "工序3");
        assertThat(find(job, 3).status()).isEqualTo(Status.MISSING_DURATION);
        assertThat(find(job, 4).status()).isEqualTo(Status.BLOCKED_BY_MISSING);
    }

    @Test
    void zeroMinutesCountsAsMissingNotAsInstant() {
        JobOut job = DagScheduleEngine.schedule(List.of(new JobIn("P1", List.of(
                task(1, "A", 1, 0)))), T0).get(0);
        assertThat(job.complete()).isFalse();
        assertThat(find(job, 1).status()).isEqualTo(Status.MISSING_DURATION);
    }

    @Test
    void finishedStepsTakeNoTimeAndDoNotBlockDownstream() {
        JobOut job = DagScheduleEngine.schedule(List.of(new JobIn("P1", List.of(
                new TaskIn(1L, "A", "已完工", 1, null, Set.of(), true),
                task(2, "B", 2, 30, 1L)))), T0).get(0);

        assertThat(job.complete()).isTrue();
        assertThat(find(job, 1).status()).isEqualTo(Status.DONE);
        assertThat(find(job, 1).start()).isNull();
        assertThat(find(job, 2).start()).isEqualTo(T0);
        assertThat(job.start()).isEqualTo(T0);
        assertThat(job.end()).isEqualTo(T0.plusMinutes(30));
    }

    @Test
    void finishedStepDoesNotReserveItsStation() {
        List<JobOut> out = DagScheduleEngine.schedule(List.of(
                new JobIn("P1", List.of(new TaskIn(1L, "MIX", "已完工", 1, 50, Set.of(), true))),
                new JobIn("P2", List.of(task(2, "MIX", 1, 10)))), T0);
        assertThat(find(out.get(1), 2).start()).isEqualTo(T0);
    }
}
