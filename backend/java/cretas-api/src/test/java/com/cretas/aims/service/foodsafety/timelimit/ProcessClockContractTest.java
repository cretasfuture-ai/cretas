package com.cretas.aims.service.foodsafety.timelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 缺口 14: 拆批 / 混批 / 回料不许偷偷重置计时 —— 回放和截止时刻的性质。 */
class ProcessClockContractTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 4, 8, 0);

    private static ProcessClock.Txn in(int minute, String ref, double qty) {
        return new ProcessClock.Txn("IN", ref, null, BigDecimal.valueOf(qty), T0.plusMinutes(minute));
    }

    private static ProcessClock.Txn report(int minute, long reportId, double qty) {
        return new ProcessClock.Txn("IN", "W-1", reportId, BigDecimal.valueOf(qty), T0.plusMinutes(minute));
    }

    private static ProcessClock.Txn out(int minute, double qty) {
        return new ProcessClock.Txn("OUT", "next", null, BigDecimal.valueOf(-qty), T0.plusMinutes(minute));
    }

    private static List<ProcessClock.Lot> replay(double balance, ProcessClock.Txn... txns) {
        return ProcessClock.replay(List.of(txns), BigDecimal.valueOf(balance), T0.minusDays(1), null);
    }

    private static void assertLot(ProcessClock.Lot lot, int minute, double open) {
        assertEquals(T0.plusMinutes(minute), lot.at());
        assertEquals(0, BigDecimal.valueOf(open).compareTo(lot.open()), "open=" + lot.open());
    }

    @Test
    @DisplayName("拆批: 领走一部分后, 剩下的料还是原来的起算时刻")
    void partialDrawKeepsRemainderOnItsOriginalClock() {
        List<ProcessClock.Lot> lots = replay(60, report(0, 1, 100), out(90, 40));
        assertEquals(1, lots.size());
        assertLot(lots.get(0), 0, 60);
    }

    @Test
    @DisplayName("混批: 新料进同一行, 旧料的笔不被盖掉; 领用先扣最早的")
    void mixedRowKeepsEachLotAndConsumesOldestFirst() {
        List<ProcessClock.Lot> lots = replay(80, report(0, 1, 50), report(60, 2, 50), out(90, 20));
        assertEquals(2, lots.size());
        assertLot(lots.get(0), 0, 30);
        assertLot(lots.get(1), 60, 50);
    }

    @Test
    @DisplayName("按笔: 超时的笔没人动, 新料被领走后账上留下的必须是旧料")
    void perLotDrawSkipsExpiredLotSoOldStockIsNotRelabelledAsNew() {
        List<ProcessClock.Lot> lots = ProcessClock.replay(
                List.of(report(0, 1, 50), report(60, 2, 50), out(90, 50)),
                BigDecimal.valueOf(50), T0, (lot, at) -> lot.at().equals(T0));
        assertEquals(1, lots.size());
        assertLot(lots.get(0), 0, 50);
    }

    @Test
    @DisplayName("判不合格转出的就是超时那笔, 不跳过")
    void rejectWriteOffConsumesExpiredLot() {
        ProcessClock.Txn reject = new ProcessClock.Txn("OUT", ProcessClock.REJECT_REF_PREFIX + "7", null,
                BigDecimal.valueOf(-50), T0.plusMinutes(100));
        List<ProcessClock.Lot> lots = ProcessClock.replay(List.of(report(0, 1, 50), report(60, 2, 50), reject),
                BigDecimal.valueOf(50), T0, (lot, at) -> lot.at().equals(T0));
        assertEquals(1, lots.size());
        assertLot(lots.get(0), 60, 50);
    }

    @Test
    @DisplayName("重存: 同一入账凭据冲销后再入账, 沿用第一次入账时刻")
    void resaveWithSameRefKeepsFirstPostingTime() {
        ProcessClock.Txn reverse = new ProcessClock.Txn("REVERSE", "CLK-IN:W-1", null,
                BigDecimal.valueOf(-30), T0.plusMinutes(50));
        List<ProcessClock.Lot> lots = replay(30, in(0, "CLK-IN:W-1", 30), reverse, in(55, "CLK-IN:W-1", 30));
        assertEquals(1, lots.size());
        assertLot(lots.get(0), 0, 30);
    }

    @Test
    @DisplayName("撤回领用: 量退回原来的笔, 不生成新笔")
    void restoredDrawGoesBackToOldLot() {
        ProcessClock.Txn restore = new ProcessClock.Txn("REVERSE", "REVERSE-1", null,
                BigDecimal.valueOf(40), T0.plusMinutes(200));
        List<ProcessClock.Lot> lots = replay(100, report(0, 1, 100), out(90, 40), restore);
        assertEquals(1, lots.size());
        assertLot(lots.get(0), 0, 100);
    }

    @Test
    @DisplayName("撤回某次报工: 只冲那一笔")
    void reversedReportOnlyRemovesItsOwnLot() {
        ProcessClock.Txn reverse = new ProcessClock.Txn("REVERSE", "REVERSE-REPORT-1", 1L,
                BigDecimal.valueOf(-50), T0.plusMinutes(70));
        List<ProcessClock.Lot> lots = replay(50, report(0, 1, 50), report(60, 2, 50), reverse);
        assertEquals(1, lots.size());
        assertLot(lots.get(0), 60, 50);
    }

    @Test
    @DisplayName("盘盈算进最老的笔, 盘亏从最新的笔扣: 都往更早算")
    void stocktakeLeansTowardEarlierClock() {
        ProcessClock.Txn gain = new ProcessClock.Txn("ADJUST", "ST-1", null, BigDecimal.valueOf(10), T0.plusMinutes(120));
        List<ProcessClock.Lot> gained = replay(110, report(0, 1, 50), report(60, 2, 50), gain);
        assertLot(gained.get(0), 0, 60);

        ProcessClock.Txn loss = new ProcessClock.Txn("ADJUST", "ST-2", null, BigDecimal.valueOf(-10), T0.plusMinutes(120));
        List<ProcessClock.Lot> lost = replay(90, report(0, 1, 50), report(60, 2, 50), loss);
        assertLot(lost.get(0), 0, 50);
        assertLot(lost.get(1), 60, 40);
    }

    @Test
    @DisplayName("账上解释不了的余额算进最老的笔; 没有流水的旧行按建行时刻")
    void unexplainedBalanceNeverLooksFresh() {
        List<ProcessClock.Lot> lots = replay(70, report(60, 2, 50));
        assertLot(lots.get(0), 60, 70);

        List<ProcessClock.Lot> legacy = ProcessClock.replay(List.of(), BigDecimal.TEN, T0, null);
        assertEquals(T0, legacy.get(0).at());
    }

    @Test
    @DisplayName("余额比账上少: 从最新的笔扣")
    void balanceBelowLedgerTrimsNewestLot() {
        List<ProcessClock.Lot> lots = replay(60, report(0, 1, 50), report(60, 2, 50));
        assertLot(lots.get(0), 0, 50);
        assertLot(lots.get(1), 60, 10);
    }

    @Test
    @DisplayName("截止时刻: 没有暂停 = 起算 + 时限")
    void deadlineWithoutPause() {
        assertEquals(T0.plusMinutes(120), ProcessClock.deadline(T0, 120, List.of(), null, T0));
    }

    @Test
    @DisplayName("截止时刻: 暂停多久顺延多久")
    void pauseExtendsDeadline() {
        List<ProcessClock.Span> pause = List.of(new ProcessClock.Span(T0.plusMinutes(30), T0.plusMinutes(90)));
        assertEquals(T0.plusMinutes(180), ProcessClock.deadline(T0, 120, pause, null, T0.plusHours(5)));
    }

    @Test
    @DisplayName("超时之后才开始的暂停不算数: 暂停救不回超时的料")
    void pauseAfterExpiryDoesNotRescue() {
        List<ProcessClock.Span> pause = List.of(new ProcessClock.Span(T0.plusMinutes(130), null));
        assertEquals(T0.plusMinutes(120), ProcessClock.deadline(T0, 120, pause, null, T0.plusHours(9)));
    }

    @Test
    @DisplayName("进行中的暂停算到现在; 累计暂停有上限")
    void openPauseCountsToNowAndIsCapped() {
        List<ProcessClock.Span> pause = List.of(new ProcessClock.Span(T0.plusMinutes(30), null));
        LocalDateTime now = T0.plusMinutes(100);
        assertEquals(T0.plusMinutes(190), ProcessClock.deadline(T0, 120, pause, null, now));
        assertEquals(T0.plusMinutes(150), ProcessClock.deadline(T0, 120, pause, 30, now));
    }

    @Test
    @DisplayName("起算之前的暂停不多算: 母批暂停早于子批起算时只算重叠部分")
    void pauseBeforeStartOnlyCountsOverlap() {
        List<ProcessClock.Span> pause = List.of(new ProcessClock.Span(T0.minusMinutes(60), T0.plusMinutes(10)));
        assertEquals(T0.plusMinutes(130), ProcessClock.deadline(T0, 120, pause, null, T0.plusHours(5)));
    }

    @Test
    @DisplayName("配置: 缺时限 / 起算点不合法 / 提醒不小于时限 一律拒")
    void configRejectsIncompleteValues() {
        assertThrows(IllegalArgumentException.class, () -> ProcessTimeLimitConfig.parse(Map.of("startEvent", "OUTPUT_POSTED")));
        assertThrows(IllegalArgumentException.class, () -> ProcessTimeLimitConfig.parse(Map.of("maxMinutes", 60, "startEvent", "NOPE")));
        assertThrows(IllegalArgumentException.class, () -> ProcessTimeLimitConfig.parse(
                Map.of("maxMinutes", 60, "warnMinutes", 60, "startEvent", "OUTPUT_POSTED")));
        assertThrows(IllegalArgumentException.class, () -> ProcessTimeLimitConfig.parse(
                Map.of("maxMinutes", 1.5, "startEvent", "OUTPUT_POSTED")));
    }

    @Test
    @DisplayName("配置: 规则没写取保守默认, 且 toMap 可再解析回来")
    void configDefaultsAreConservativeAndRoundTrip() {
        ProcessTimeLimitConfig config = ProcessTimeLimitConfig.parse(Map.of("maxMinutes", 60, "startEvent", "OUTPUT_POSTED"));
        assertEquals(ProcessTimeLimitConfig.MixRule.EARLIEST, config.mixRule());
        assertEquals(ProcessTimeLimitConfig.SplitRule.INHERIT, config.splitRule());
        assertEquals(ProcessTimeLimitConfig.ReworkRule.INHERIT_BATCH_START, config.reworkRule());
        assertEquals(config, ProcessTimeLimitConfig.parse(config.toMap()));
    }

    @Test
    @DisplayName("配置: 起算点要和料的种类对得上")
    void startEventMustFitMaterialKind() {
        ProcessTimeLimitConfig thaw = ProcessTimeLimitConfig.parse(Map.of("maxMinutes", 60, "startEvent", "THAW_MARKED"));
        thaw.assertFitsMaterialKind("RAW_MATERIAL");
        assertThrows(IllegalArgumentException.class, () -> thaw.assertFitsMaterialKind("SEMI_FINISHED"));
        ProcessTimeLimitConfig done = ProcessTimeLimitConfig.parse(Map.of("maxMinutes", 60, "startEvent", "UPSTREAM_COMPLETED"));
        assertThrows(IllegalArgumentException.class, () -> done.assertFitsMaterialKind("RAW_MATERIAL"));
    }
}
