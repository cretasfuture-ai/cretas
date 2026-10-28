package com.cretas.aims.service.scheduling.daily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按产品日产能按天排 (APS v2, Steve 2026-10-28 裁定口径)。纯计算, 不读库不写库。
 *
 * <h3>规则</h3>
 * <ul>
 *   <li>一个作业 (一个生产计划) 要做 {@code quantity}, 它的产品一天能做 {@code capacityPerDay}。
 *       从第一天开始做, 一天做不完顺延到下一天 (日历日连续, 不跳周末 —— 没有工厂日历数据, ⛔ 不猜)。</li>
 *   <li>同一条产线一天的产能被多个作业按传入顺序分着用: 用量按「占当天的比例」记 (做 40 袋 / 日产能 100 袋 = 0.4 天),
 *       下一个作业从这天剩下的比例开始 (剩 0.6 天 × 它自己的日产能)。</li>
 *   <li>日产能为空或 &le; 0: 不排、不占产能, 标 {@link Status#NEEDS_CAPACITY} (调用方让人补, ⛔ 不拿默认值猜)。</li>
 *   <li>要做的量 &le; 0: {@link Status#NOTHING_REMAINING}。</li>
 *   <li>超过 {@link #MAX_DAYS} 天还排不完: 整个作业不排、不占产能 ({@link Status#EXCEEDS_HORIZON}) ——
 *       多半是日产能填错了数量级, 让人看一眼, 而不是落一份排到明年的排程。</li>
 * </ul>
 *
 * <p>数量保留 2 位小数 (与计划量同精度), 占当天比例保留 6 位。
 */
public final class DailyCapacityEngine {

    public static final int MAX_DAYS = 366;
    static final int QTY_SCALE = 2;
    static final int SHARE_SCALE = 6;

    private DailyCapacityEngine() {
    }

    public enum Status { SCHEDULED, NEEDS_CAPACITY, NOTHING_REMAINING, EXCEEDS_HORIZON }

    public record JobIn(String key, BigDecimal quantity, BigDecimal capacityPerDay) {
    }

    /** 某一天给这个作业的量, 以及它占这天产线产能的比例 (0, 1]。 */
    public record DayOut(LocalDate date, BigDecimal quantity, BigDecimal shareOfDay) {
    }

    public record JobOut(String key, Status status, List<DayOut> days) {
        public LocalDate firstDay() {
            return days.isEmpty() ? null : days.get(0).date();
        }

        public LocalDate lastDay() {
            return days.isEmpty() ? null : days.get(days.size() - 1).date();
        }

        public BigDecimal totalQuantity() {
            return days.stream().map(DayOut::quantity).reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    static boolean missingCapacity(BigDecimal capacity) {
        return capacity == null || capacity.signum() <= 0;
    }

    public static List<JobOut> allocate(List<JobIn> jobs, LocalDate firstDay) {
        Objects.requireNonNull(firstDay, "firstDay");
        Map<LocalDate, BigDecimal> usedShare = new HashMap<>();
        List<JobOut> out = new ArrayList<>();
        for (JobIn job : jobs) {
            if (missingCapacity(job.capacityPerDay())) {
                out.add(new JobOut(job.key(), Status.NEEDS_CAPACITY, List.of()));
                continue;
            }
            if (job.quantity() == null || job.quantity().signum() <= 0) {
                out.add(new JobOut(job.key(), Status.NOTHING_REMAINING, List.of()));
                continue;
            }
            BigDecimal capacity = job.capacityPerDay();
            BigDecimal remaining = job.quantity().setScale(QTY_SCALE, RoundingMode.HALF_UP);
            Map<LocalDate, BigDecimal> tentative = new HashMap<>();
            List<DayOut> days = new ArrayList<>();
            LocalDate day = firstDay;
            boolean exceeded = false;
            while (remaining.signum() > 0) {
                if (day.isAfter(firstDay.plusDays(MAX_DAYS - 1L))) {
                    exceeded = true;
                    break;
                }
                BigDecimal used = usedShare.getOrDefault(day, BigDecimal.ZERO);
                BigDecimal free = BigDecimal.ONE.subtract(used);
                BigDecimal producible = free.signum() <= 0 ? BigDecimal.ZERO
                        : free.multiply(capacity).setScale(QTY_SCALE, RoundingMode.DOWN);
                if (producible.signum() <= 0) {
                    day = day.plusDays(1);
                    continue;
                }
                BigDecimal take;
                BigDecimal share;
                if (remaining.compareTo(producible) < 0) {
                    take = remaining;
                    share = take.divide(capacity, SHARE_SCALE, RoundingMode.UP).min(free);
                } else {
                    take = producible;
                    share = free; // 用掉这天剩下的全部
                }
                days.add(new DayOut(day, take, share));
                tentative.put(day, used.add(share));
                remaining = remaining.subtract(take);
                if (remaining.signum() > 0) {
                    day = day.plusDays(1);
                }
            }
            if (exceeded) {
                out.add(new JobOut(job.key(), Status.EXCEEDS_HORIZON, List.of()));
                continue;
            }
            usedShare.putAll(tentative);
            out.add(new JobOut(job.key(), Status.SCHEDULED, List.copyOf(days)));
        }
        return out;
    }
}
