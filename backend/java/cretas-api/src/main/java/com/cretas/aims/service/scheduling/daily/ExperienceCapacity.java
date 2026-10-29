package com.cretas.aims.service.scheduling.daily;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 经验日产能 (系统从历史自己判断, Steve 2026-10-28 / 2026-10-29): 最近 {@link #WINDOW_DAYS} 天里, 按自然日汇总的量,
 * 取「有量的日子」的中位数。量从哪来看 {@link Basis}:
 * <ul>
 *   <li>{@link Basis#FINISHED_OUTPUT}: 普通产品, 生产成品入库量; 算得出就自动用 (标「按历史估算」)。</li>
 *   <li>{@link Basis#RAW_INPUT}: 联产计划, 实际投入计划主原料的量; ⛔ 只作建议, 由人确认后保存为人工值。</li>
 * </ul>
 *
 * <ul>
 *   <li>有量的日子 &lt; {@link #MIN_SAMPLE_DAYS} 天: 算不出 (⛔ 不拿默认值, 不拿 1、2 天的数凑);
 *       但把那几天的实际量放进 {@link #samples()}, 让问的时候看得到 (Steve 2026-10-29)。</li>
 *   <li>窗口里有任何一笔的单位换不成计划单位: 算不出 (⛔ 不丢掉那几笔只算剩下的 —— 那样某天的合计就是假的少);
 *       此时 samples 为空 (换不成计划单位的量没法按计划单位摆出来)。</li>
 * </ul>
 *
 * @param perDay     中位数 (计划单位/天); 算不出为 null
 * @param sampleDays 有量的日子数
 * @param note       算得出时是口径说明, 算不出时是原因 (给人看)
 * @param samples    有量的日子不足 {@link #MIN_SAMPLE_DAYS} 天时, 那几天的量 (计划单位, 按日期升序); 其余情况为空
 * @param basis      量的来源
 */
public record ExperienceCapacity(BigDecimal perDay, int sampleDays, String note, List<DaySample> samples,
                                 Basis basis) {

    public static final int WINDOW_DAYS = 90;
    public static final int MIN_SAMPLE_DAYS = 3;

    /** 量的来源。 */
    public enum Basis {
        /** 生产成品入库 (finished_goods_batches)。 */
        FINISHED_OUTPUT,
        /** 实际投入的原料 (material_consumptions)。 */
        RAW_INPUT
    }

    /** 一笔 (或一天一个单位合计的) 量: 成品入库或原料投入。 */
    public record OutputRow(LocalDate day, String unit, BigDecimal quantity) {
    }

    /** 某一天的合计 (已换成计划单位)。 */
    public record DaySample(LocalDate date, BigDecimal quantity, String unit) {
    }

    public ExperienceCapacity {
        samples = samples == null ? List.of() : List.copyOf(samples);
        basis = basis == null ? Basis.FINISHED_OUTPUT : basis;
    }

    public boolean available() {
        return perDay != null;
    }

    public static ExperienceCapacity unavailable(String note) {
        return unavailable(note, Basis.FINISHED_OUTPUT);
    }

    public static ExperienceCapacity unavailable(String note, Basis basis) {
        return new ExperienceCapacity(null, 0, note, List.of(), basis);
    }

    /** 普通产品: 成品入库。 */
    public static ExperienceCapacity fromRows(List<OutputRow> rows, String planUnit) {
        return fromRows(rows, planUnit, Basis.FINISHED_OUTPUT, null);
    }

    /**
     * @param subject {@link Basis#RAW_INPUT} 时是原料名 (说明里用, 如「西装鸡」); 成品入库时不用
     */
    public static ExperienceCapacity fromRows(List<OutputRow> rows, String planUnit, Basis basis, String subject) {
        Basis b = basis == null ? Basis.FINISHED_OUTPUT : basis;
        boolean input = b == Basis.RAW_INPUT;
        String what = input ? "投入" + (subject == null || subject.isBlank() ? "原料" : subject) : "成品入库";
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        Set<String> unconvertible = new LinkedHashSet<>();
        for (OutputRow row : rows) {
            if (row.quantity() == null || row.day() == null) {
                continue;
            }
            var converted = CapacityUnits.convert(row.quantity(), row.unit(), planUnit);
            if (converted.isEmpty()) {
                unconvertible.add(row.unit() == null ? "未填单位" : row.unit());
                continue;
            }
            byDay.merge(row.day(), converted.get(), BigDecimal::add);
        }
        if (!unconvertible.isEmpty()) {
            return unavailable("近 " + WINDOW_DAYS + " 天" + what + "的单位（" + String.join("、", unconvertible)
                    + "）与计划单位（" + planUnit + "）不一致，换算不了，"
                    + (input ? "给不出建议值" : "算不出历史日产能"), b);
        }
        List<DaySample> positiveDays = new ArrayList<>();
        byDay.forEach((day, v) -> {
            if (v.signum() > 0) {
                positiveDays.add(new DaySample(day,
                        v.setScale(DailyCapacityEngine.QTY_SCALE, RoundingMode.HALF_UP), planUnit));
            }
        });
        int n = positiveDays.size();
        if (n < MIN_SAMPLE_DAYS) {
            return new ExperienceCapacity(null, n, "近 " + WINDOW_DAYS + " 天只有 " + n + " 天有" + what
                    + "，不足 " + MIN_SAMPLE_DAYS + " 天，" + (input ? "给不出建议值" : "算不出历史日产能"),
                    positiveDays, b);
        }
        List<BigDecimal> positive = new ArrayList<>();
        positiveDays.forEach(d -> positive.add(d.quantity()));
        positive.sort(BigDecimal::compareTo);
        BigDecimal median = n % 2 == 1
                ? positive.get(n / 2)
                : positive.get(n / 2 - 1).add(positive.get(n / 2)).divide(BigDecimal.valueOf(2));
        median = median.setScale(DailyCapacityEngine.QTY_SCALE, RoundingMode.HALF_UP);
        String note = input
                ? "按近 " + WINDOW_DAYS + " 天里 " + n + " 天实际" + what + "的中位数"
                : "近 " + WINDOW_DAYS + " 天 " + n + " 个有成品入库的日子的中位数";
        return new ExperienceCapacity(median, n, note, List.of(), b);
    }
}
