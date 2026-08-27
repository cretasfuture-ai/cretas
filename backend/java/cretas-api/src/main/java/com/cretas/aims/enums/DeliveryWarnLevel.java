package com.cretas.aims.enums;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.stream.Stream;

/**
 * 交期预警等级 —— 「距交期还有几天」到「超期/紧急/预警/正常」的<b>唯一</b>一份判据。
 *
 * <h3>为什么要抽出来</h3>
 *
 * <p>这套四档原来只写在 {@code ProductionPlanServiceImpl.classifyDeliveryWarn} 的
 * 私有方法里，而<b>等级名字的清单</b>又在
 * {@code ProductionPlanDeliveryWarnTool.VALID_WARN_LEVELS} 里手抄了一份。
 * 现在工序任务卡片也要判「这道工序超期没有」—— 如果再手写一遍判据，
 * 同一个业务口径就有三份，<b>它一定会漂</b>：改了其中一处的阈值，
 * AI 说的和卡片上画的就不是一件事，而且不报错、不变红。
 *
 * <p>⇒ 判据抽成这一份，所有消费方都读它。
 *
 * <h3>档位（与抽出前逐字一致，不是重新定的）</h3>
 * <ul>
 *   <li>{@link #OVERDUE}: daysUntilDeadline &lt; 0（已超期）</li>
 *   <li>{@link #URGENT}:  0 &le; daysUntilDeadline &lt; 3</li>
 *   <li>{@link #WARN}:    3 &le; daysUntilDeadline &lt; 7</li>
 *   <li>{@link #NORMAL}:  daysUntilDeadline &ge; 7</li>
 * </ul>
 *
 * <p>⚠️ <b>声明顺序有语义</b>：{@code ProductionPlanDeliveryWarnTool} 靠
 * {@link #names()} 的顺序把分组排成 OVERDUE→URGENT→WARN→NORMAL。重排会改 AI 输出。
 *
 * <h3>⛔ 这里不取「今天」</h3>
 *
 * <p>{@code today} 一律由调用方传进来，本类不碰时钟。两个理由：
 * 一是时区归调用方决定（后端全线 GMT+8，但「哪一天」是业务口径不是工具类的事）；
 * 二是<b>不碰时钟才测得动</b> —— 判据能用固定日期直接断言，
 * 不需要 mock 静态方法，也不会因为跑测试的时刻不同而飘。
 */
public enum DeliveryWarnLevel {

    /** 已超期：交期比今天早。 */
    OVERDUE,
    /** 紧急：今天到期，或 3 天内到期。 */
    URGENT,
    /** 预警：3~7 天内到期。 */
    WARN,
    /** 正常：还有 7 天以上。 */
    NORMAL;

    /** URGENT 上界（天，不含）。 */
    public static final int URGENT_WITHIN_DAYS = 3;
    /** WARN 上界（天，不含）。 */
    public static final int WARN_WITHIN_DAYS = 7;

    /**
     * 按「距交期天数」分档。
     *
     * @param daysUntilDeadline 距交期天数，负数 = 已过期
     */
    public static DeliveryWarnLevel ofDaysUntilDeadline(long daysUntilDeadline) {
        if (daysUntilDeadline < 0) {
            return OVERDUE;
        } else if (daysUntilDeadline < URGENT_WITHIN_DAYS) {
            return URGENT;
        } else if (daysUntilDeadline < WARN_WITHIN_DAYS) {
            return WARN;
        } else {
            return NORMAL;
        }
    }

    /**
     * 按两个<b>日历日</b>分档（按天粒度，与抽出前的 {@code ChronoUnit.DAYS.between} 同口径）。
     *
     * <p>⚠️ 按天不按秒是有意的：今天 18:00 截止的活，上午 09:00 看<b>不算超期</b>。
     * 换成 {@code LocalDateTime.isBefore(now)} 会把「今天还没干完」当场判成超期。
     *
     * @param today    业务上的「今天」（调用方按自己的时区取）
     * @param deadline 交期 / 计划完成日
     * @return 档位；{@code today} 或 {@code deadline} 为 null 时返回 {@code null}
     *         —— ⛔ 不兜底成 NORMAL，「不知道」和「正常」不是一回事
     */
    public static DeliveryWarnLevel between(LocalDate today, LocalDate deadline) {
        if (today == null || deadline == null) {
            return null;
        }
        return ofDaysUntilDeadline(ChronoUnit.DAYS.between(today, deadline));
    }

    /** 是不是已超期。 */
    public boolean isOverdue() {
        return this == OVERDUE;
    }

    /** 等级名清单，顺序即展示顺序（OVERDUE→URGENT→WARN→NORMAL）。 */
    public static List<String> names() {
        return Stream.of(values()).map(Enum::name).toList();
    }
}
