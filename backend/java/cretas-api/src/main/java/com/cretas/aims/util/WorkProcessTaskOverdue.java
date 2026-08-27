package com.cretas.aims.util;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.enums.DeliveryWarnLevel;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 「这道工序超期没有」—— {@code WorkProcessTaskDTO.overdue} 的<b>唯一</b>产出口。
 *
 * <h3>为什么要单独一个类</h3>
 *
 * <p>{@code WorkProcessTaskDTO} 在两个 service 里各有一处 builder
 * （{@code WorkProcessTaskServiceImpl} 走 REST 列表/详情，
 * {@code ProductProcessWorkflowRuntimeServiceImpl} 走 workflow runtime）。
 * 两处都要答这个问题；如果各写一遍判据，<b>它一定会漂</b> —— 而且漂了不报错、
 * 不变红，只是两个入口对同一道工序给出不同的答案。⇒ 判据在这里，两处都读它。
 *
 * <h3>三态，⛔ 不是布尔</h3>
 *
 * <table border="1">
 *   <caption>返回值</caption>
 *   <tr><th>值</th><th>含义</th></tr>
 *   <tr><td>{@code null}</td><td><b>判不了</b> —— 这道工序没设 {@code plannedEndAt}</td></tr>
 *   <tr><td>{@code false}</td><td>判过了，不在「还欠着的超期活」清单里</td></tr>
 *   <tr><td>{@code true}</td><td>还没干完，而计划完成日已经过去了</td></tr>
 * </table>
 *
 * <p>⛔ {@code null} 不许兜底成 {@code false}：「主管还没排期」和「排了且没拖」
 * 是两件完全不同的事，把前者说成后者就是编了一个结论。
 */
public final class WorkProcessTaskOverdue {

    /**
     * 业务日历日的时区。后端全线 GMT+8（{@code spring.jackson.time-zone=GMT+8}，
     * 服务器 Asia/Shanghai）——「今天」是业务口径，不跟 JVM 默认时区走。
     */
    public static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

    private WorkProcessTaskOverdue() {
        // 工具类禁止实例化
    }

    /** 业务上的「今天」（本地日历日，⛔ 不是 UTC 日）。 */
    public static LocalDate today() {
        return LocalDate.now(BUSINESS_ZONE);
    }

    /** 生产入口：按业务时区的今天判一条任务。 */
    public static Boolean of(WorkProcessTask task) {
        if (task == null) {
            return null;
        }
        return of(task.getPlannedEndAt(), task.getStatus(), today());
    }

    /**
     * <b>纯函数</b>本体 —— {@code today} 由调用方传入。
     *
     * <p>⚠️ 不在函数体里取 {@code LocalDate.now()}：那样判据就够不着这个行为，
     * 只能靠「跑测试的那天正好落在某一侧」碰运气，而<b>「仪器够不着被测行为」
     * 和「行为没生效」的读数一模一样、结论却相反</b>。参数化之后能用固定日期钉死。
     *
     * <p>档位判据来自 {@link DeliveryWarnLevel} —— 与 AI 的交货预警
     * （「哪些订单要超期了」）是<b>同一份</b>，⛔ 不是这里重新定的。
     *
     * <p>按<b>天</b>不按秒：今天 18:00 截止的活，上午 09:00 看不算超期。
     * 换成 {@code plannedEndAt.isBefore(now)} 会把「今天还没干完」当场判成超期。
     *
     * @param plannedEndAt 计划完成时间；{@code null} ⇒ 判不了，返回 {@code null}
     * @param status       任务状态；终态（COMPLETED/SKIPPED/CANCELLED）⇒ {@code false}
     *                     —— 活已经结束，不在「还欠着的超期活」清单里。
     *                     ⚠️ 它<b>不表示</b>这道工序当初是准时完成的：完成得晚也是 false。
     * @param today        业务上的「今天」
     */
    public static Boolean of(LocalDateTime plannedEndAt, WorkProcessTask.Status status, LocalDate today) {
        if (plannedEndAt == null) {
            return null;
        }
        if (status != null && status.isTerminal()) {
            return Boolean.FALSE;
        }
        DeliveryWarnLevel level = DeliveryWarnLevel.between(today, plannedEndAt.toLocalDate());
        return level == null ? null : level.isOverdue();
    }
}
