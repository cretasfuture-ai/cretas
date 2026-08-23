package com.cretas.aims.scheduler;

import com.cretas.aims.scheduler.AsyncTaskFailureBacklogScheduler.SweepOutcome;
import com.cretas.aims.service.asyncops.AsyncTaskFailureLedger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 三态跑批, 尤其是<b>第三态</b>。
 *
 * <p>本仓硬约束: {@code rc=0 没问题 / rc=1 有问题 / rc=2 这次没量到}。
 * 两态跑批会把「没量到」折叠进「没问题」—— 一个连不上库的跑批
 * <b>安静地天天绿</b>, 而它一条样本都没看过。
 *
 * <p>⛔ 「三态的价值全在第三态, 而第三态平时永远不会自然发生 —— 不构造就不知道
 * 它能不能到达。」所以下面<b>主动构造</b>了它: 让台账查询抛,
 * 断言跑批落在 {@code INSTRUMENT_DEAD} 而不是 {@code QUIET}。
 */
class AsyncTaskFailureBacklogSchedulerContractTest {

    @Test
    @DisplayName("台账为空 -> QUIET")
    void emptyLedgerIsQuiet() {
        AsyncTaskFailureBacklogScheduler scheduler =
                new AsyncTaskFailureBacklogScheduler(new StubLedger(0, List.of()));

        assertEquals(SweepOutcome.QUIET, scheduler.sweep());
    }

    @Test
    @DisplayName("有未处理条目 -> BACKLOG")
    void openRowsProduceBacklog() {
        AsyncTaskFailureBacklogScheduler scheduler = new AsyncTaskFailureBacklogScheduler(
                new StubLedger(7, List.of(Map.of(
                        "method_signature", "com.example.Listener.onEvent",
                        "rows_open", 7,
                        "occurrences", 19,
                        "latest", "2026-08-19 09:00:00"))));

        assertEquals(SweepOutcome.BACKLOG, scheduler.sweep());
    }

    /**
     * 🔴 第三态, 主动构造。
     *
     * <p>这一条同时是对 {@link AsyncTaskFailureLedger#countOpen()} 那个设计的钉子:
     * 如果哪天有人「顺手」给 countOpen 加个 try/catch 返回 0, 本测试会立刻红 ——
     * 因为那样跑批就再也到不了 INSTRUMENT_DEAD, 会落回 QUIET。
     */
    @Test
    @DisplayName("🔴 查不到台账 -> INSTRUMENT_DEAD, ⛔ 绝不能是 QUIET")
    void unreachableLedgerIsInstrumentDeadNotQuiet() {
        AsyncTaskFailureBacklogScheduler scheduler =
                new AsyncTaskFailureBacklogScheduler(new ExplodingLedger());

        SweepOutcome outcome = scheduler.sweep();

        assertEquals(SweepOutcome.INSTRUMENT_DEAD, outcome);
        assertNotEquals(SweepOutcome.QUIET, outcome,
                "「查不出来」被折叠成了「没问题」—— 连不上库的跑批会安静地天天绿, "
                        + "而它一条样本都没看过。三态的全部价值就在这一态。");
    }

    @Test
    @DisplayName("总数查得到、明细查不到 -> 仍然是 BACKLOG (结论已成立, 明细只是好用)")
    void breakdownFailureDoesNotDowngradeTheConclusion() {
        AsyncTaskFailureBacklogScheduler scheduler =
                new AsyncTaskFailureBacklogScheduler(new BreakdownOnlyFailsLedger());

        assertEquals(SweepOutcome.BACKLOG, scheduler.sweep(),
                "明细查询失败不该把「有 3 条没人处理」这个已经成立的结论抹掉");
    }

    @Test
    @DisplayName("总数 > 0 但分组为空 -> 仍然是 BACKLOG, 不粉饰自相矛盾的读数")
    void contradictoryReadingStillReportsBacklog() {
        AsyncTaskFailureBacklogScheduler scheduler =
                new AsyncTaskFailureBacklogScheduler(new StubLedger(4, List.of()));

        assertEquals(SweepOutcome.BACKLOG, scheduler.sweep());
    }

    // ---- 桩 ----------------------------------------------------------------

    private static class StubLedger extends AsyncTaskFailureLedger {
        private final int openCount;
        private final List<Map<String, Object>> breakdown;

        StubLedger(int openCount, List<Map<String, Object>> breakdown) {
            super(null);
            this.openCount = openCount;
            this.breakdown = breakdown;
        }

        @Override
        public int countOpen() {
            return openCount;
        }

        @Override
        public List<Map<String, Object>> openBreakdownByMethod() {
            return breakdown;
        }
    }

    /**
     * 仪器整个起不来。
     *
     * <p>⚠️ 真实那一侧真的会给出这个形状吗? 会 ——
     * {@code JdbcTemplate} 在库不可达 / 表不存在时抛的正是
     * {@code DataAccessResourceFailureException} 这一族 ({@code DataAccessException} 子类)。
     */
    private static class ExplodingLedger extends AsyncTaskFailureLedger {
        ExplodingLedger() {
            super(null);
        }

        @Override
        public int countOpen() {
            throw new DataAccessResourceFailureException("connection refused");
        }

        @Override
        public List<Map<String, Object>> openBreakdownByMethod() {
            throw new DataAccessResourceFailureException("connection refused");
        }
    }

    private static class BreakdownOnlyFailsLedger extends AsyncTaskFailureLedger {
        BreakdownOnlyFailsLedger() {
            super(null);
        }

        @Override
        public int countOpen() {
            return 3;
        }

        @Override
        public List<Map<String, Object>> openBreakdownByMethod() {
            throw new DataAccessResourceFailureException("detail query failed");
        }
    }
}
