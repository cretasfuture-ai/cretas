package com.cretas.aims.util;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.entity.workprocess.WorkProcessTask.Status;
import com.cretas.aims.enums.DeliveryWarnLevel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守两件事:
 * <ol>
 *   <li>「超期」是<b>三态</b>: true / false / null(判不了) —— null ⛔ 不许兜底成 false;</li>
 *   <li>档位判据只有<b>一份</b>({@link DeliveryWarnLevel}), 工序卡片和 AI 交货预警读同一个。</li>
 * </ol>
 *
 * <p>⚠️ 全部用<b>固定日期</b>断言, 不碰时钟 —— 靠「跑测试那天正好落在某一侧」的判据
 * 在别的日子会给出别的读数, 而那种红/绿都不说明问题。
 */
@DisplayName("工序任务超期判据 (三态 + 单一判据)")
class WorkProcessTaskOverdueTest {

    /** 钉死的「今天」。任何一条断言都不依赖真实时钟。 */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 26);

    private static LocalDateTime at(int year, int month, int day, int hour, int minute) {
        return LocalDateTime.of(year, month, day, hour, minute);
    }

    // ──────────────────────────────────────────────────────────────
    // 三态
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 没设 plannedEndAt ⇒ null(判不了), ⛔ 不是 false")
    void nullPlannedEndAtIsUnknownNotOnTime() {
        Boolean result = WorkProcessTaskOverdue.of(null, Status.PENDING, TODAY);

        assertNull(result, "没排期被答成了一个确定的判断 —— 那是编了一个结论");
        // 写成 assertNotEquals 而不只是 assertNull: 兜底成 false 时这两条一起红,
        // 判据说的就是「这两个值不许混为一谈」。
        assertNotEquals(Boolean.FALSE, result);
    }

    @Test
    @DisplayName("计划完成日在今天之前, 且活没干完 ⇒ true")
    void pastDeadlineUnfinishedIsOverdue() {
        assertEquals(Boolean.TRUE,
                WorkProcessTaskOverdue.of(at(2026, 8, 25, 17, 30), Status.PENDING, TODAY));
        assertEquals(Boolean.TRUE,
                WorkProcessTaskOverdue.of(at(2026, 8, 20, 9, 0), Status.IN_PROGRESS, TODAY));
    }

    @Test
    @DisplayName("🔴 今天 18:00 截止的活, 今天上午看【不算】超期 —— 按天不按秒")
    void dueLaterTodayIsNotOverdue() {
        // 这一条是阴性对照, 也是最容易被写坏的一条:
        // 换成 plannedEndAt.isBefore(LocalDateTime.now()) 会把一屏「今天要做的活」
        // 在上午 9 点全部染成超期。
        assertEquals(Boolean.FALSE,
                WorkProcessTaskOverdue.of(at(2026, 8, 26, 18, 0), Status.IN_PROGRESS, TODAY));
        // 连当天 00:00 截止的也不算 —— 日历日相等就是 0 天, 不是负数
        assertEquals(Boolean.FALSE,
                WorkProcessTaskOverdue.of(at(2026, 8, 26, 0, 0), Status.PENDING, TODAY));
    }

    @Test
    @DisplayName("将来到期 ⇒ false")
    void futureDeadlineIsNotOverdue() {
        assertEquals(Boolean.FALSE,
                WorkProcessTaskOverdue.of(at(2026, 9, 30, 17, 30), Status.PENDING, TODAY));
    }

    @Test
    @DisplayName("终态(已完成/已跳过/已取消) ⇒ false, 即使早就过了计划完成日")
    void terminalStatusIsNotOnTheOverdueList() {
        LocalDateTime longPast = at(2026, 7, 1, 17, 30);
        for (Status terminal : new Status[] { Status.COMPLETED, Status.SKIPPED, Status.CANCELLED }) {
            assertEquals(Boolean.FALSE, WorkProcessTaskOverdue.of(longPast, terminal, TODAY),
                    terminal + " 的活已经结束了, 不该出现在「还欠着的超期活」里");
        }
        // 阳性对照: 同一个日期 + 非终态 ⇒ true。
        // 没有这一条, 上面那组「全 false」也可能是因为判据根本不看日期。
        assertEquals(Boolean.TRUE,
                WorkProcessTaskOverdue.of(longPast, Status.IN_PROGRESS, TODAY));
    }

    @Test
    @DisplayName("终态但没设计划完成日 ⇒ 仍然是 null(判不了), 不是 false")
    void terminalWithoutDeadlineStaysUnknown() {
        assertNull(WorkProcessTaskOverdue.of(null, Status.COMPLETED, TODAY));
    }

    @Test
    @DisplayName("整条任务为 null ⇒ null")
    void nullTaskIsUnknown() {
        assertNull(WorkProcessTaskOverdue.of((WorkProcessTask) null));
    }

    @Test
    @DisplayName("实体入口与纯函数同结论 (生产走的是实体这条)")
    void entityOverloadAgreesWithPureFunction() {
        WorkProcessTask task = new WorkProcessTask();
        task.setStatus(Status.IN_PROGRESS);
        task.setPlannedEndAt(at(2020, 1, 1, 0, 0));  // 铁定超期, 与今天是哪天无关

        assertEquals(Boolean.TRUE, WorkProcessTaskOverdue.of(task));

        task.setPlannedEndAt(null);
        assertNull(WorkProcessTaskOverdue.of(task), "没排期 ⇒ 判不了");
    }

    // ──────────────────────────────────────────────────────────────
    // 单一判据 —— 与 AI 交货预警读同一份
    // ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("🔴 判据只有一份: 超期 ⟺ DeliveryWarnLevel.OVERDUE")
    void overdueIsExactlyTheOverdueTier() {
        // 逐天扫过四个档位的边界, 断言「工序卡片说超期」和「AI 说 OVERDUE」永远同进同出。
        // 如果哪天有人在其中一侧改了阈值, 这条会红 —— 这正是它要守的。
        for (int offset = -5; offset <= 10; offset++) {
            LocalDate deadline = TODAY.plusDays(offset);
            DeliveryWarnLevel level = DeliveryWarnLevel.between(TODAY, deadline);
            Boolean overdue = WorkProcessTaskOverdue.of(deadline.atTime(12, 0), Status.PENDING, TODAY);

            assertEquals(level.isOverdue(), overdue,
                    "offset=" + offset + " 时两侧判据不一致: level=" + level + " overdue=" + overdue);
        }
    }

    @Test
    @DisplayName("阳性对照: 上面那个循环真的两边都出现过 true 和 false")
    void theLoopActuallyCoversBothOutcomes() {
        // ⚠️ 没有这一条, 上面的循环可能是恒真式 —— 比如判据两侧都恒为 false 时,
        //    「两侧一致」照样成立, 而它一个超期样本都没看过。
        boolean sawOverdue = false;
        boolean sawNotOverdue = false;
        for (int offset = -5; offset <= 10; offset++) {
            Boolean overdue = WorkProcessTaskOverdue.of(
                    TODAY.plusDays(offset).atTime(12, 0), Status.PENDING, TODAY);
            if (Boolean.TRUE.equals(overdue)) {
                sawOverdue = true;
            } else if (Boolean.FALSE.equals(overdue)) {
                sawNotOverdue = true;
            }
        }
        assertTrue(sawOverdue, "整个循环里一个超期样本都没有 ⇒ 上一条断言是恒真式");
        assertTrue(sawNotOverdue, "整个循环里一个不超期样本都没有 ⇒ 上一条断言是恒真式");
    }

    @Test
    @DisplayName("DeliveryWarnLevel 四档阈值与抽出前逐字一致")
    void tiersMatchTheOriginalThresholds() {
        assertSame(DeliveryWarnLevel.OVERDUE, DeliveryWarnLevel.ofDaysUntilDeadline(-1));
        assertSame(DeliveryWarnLevel.URGENT, DeliveryWarnLevel.ofDaysUntilDeadline(0));
        assertSame(DeliveryWarnLevel.URGENT, DeliveryWarnLevel.ofDaysUntilDeadline(2));
        assertSame(DeliveryWarnLevel.WARN, DeliveryWarnLevel.ofDaysUntilDeadline(3));
        assertSame(DeliveryWarnLevel.WARN, DeliveryWarnLevel.ofDaysUntilDeadline(6));
        assertSame(DeliveryWarnLevel.NORMAL, DeliveryWarnLevel.ofDaysUntilDeadline(7));
        assertSame(DeliveryWarnLevel.NORMAL, DeliveryWarnLevel.ofDaysUntilDeadline(365));
    }

    @Test
    @DisplayName("🔴 等级名与顺序不许动 —— AI 的分组展示顺序靠它")
    void levelNamesAndOrderAreStable() {
        // ProductionPlanDeliveryWarnTool 用 names() 建 LinkedHashMap, 顺序即展示顺序。
        assertEquals(java.util.List.of("OVERDUE", "URGENT", "WARN", "NORMAL"),
                DeliveryWarnLevel.names());
    }

    @Test
    @DisplayName("between 收到 null ⇒ null, ⛔ 不兜底成 NORMAL")
    void betweenWithNullIsUnknown() {
        assertNull(DeliveryWarnLevel.between(null, TODAY));
        assertNull(DeliveryWarnLevel.between(TODAY, null));
        assertFalse(DeliveryWarnLevel.NORMAL.isOverdue());
        assertTrue(DeliveryWarnLevel.OVERDUE.isOverdue());
    }
}
