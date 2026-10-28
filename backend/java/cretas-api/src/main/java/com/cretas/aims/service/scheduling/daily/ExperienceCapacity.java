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
 * 经验日产能 (系统从历史自己判断, Steve 2026-10-28): 最近 {@link #WINDOW_DAYS} 天里, 按自然日汇总的生产成品入库量,
 * 取「有产出的日子」的中位数。
 *
 * <ul>
 *   <li>有产出的日子 &lt; {@link #MIN_SAMPLE_DAYS} 天: 算不出 (⛔ 不拿默认值, 不拿 1、2 天的数凑)。</li>
 *   <li>窗口里有任何一笔入库的单位换不成计划单位: 算不出 (⛔ 不丢掉那几笔只算剩下的 —— 那样某天的合计就是假的少)。</li>
 * </ul>
 *
 * @param perDay     中位数 (计划单位/天); 算不出为 null
 * @param sampleDays 有产出的日子数
 * @param note       算得出时是口径说明, 算不出时是原因 (给人看)
 */
public record ExperienceCapacity(BigDecimal perDay, int sampleDays, String note) {

    public static final int WINDOW_DAYS = 90;
    public static final int MIN_SAMPLE_DAYS = 3;

    /** 一笔 (或一天一个单位合计的) 生产成品入库。 */
    public record OutputRow(LocalDate day, String unit, BigDecimal quantity) {
    }

    public boolean available() {
        return perDay != null;
    }

    public static ExperienceCapacity unavailable(String note) {
        return new ExperienceCapacity(null, 0, note);
    }

    public static ExperienceCapacity fromRows(List<OutputRow> rows, String planUnit) {
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
            return unavailable("近 " + WINDOW_DAYS + " 天的成品入库单位（" + String.join("、", unconvertible)
                    + "）与计划单位（" + planUnit + "）不一致，换算不了，算不出历史日产能");
        }
        List<BigDecimal> positive = new ArrayList<>();
        byDay.values().forEach(v -> {
            if (v.signum() > 0) {
                positive.add(v);
            }
        });
        if (positive.size() < MIN_SAMPLE_DAYS) {
            return new ExperienceCapacity(null, positive.size(), "近 " + WINDOW_DAYS + " 天只有 " + positive.size()
                    + " 天有成品入库，不足 " + MIN_SAMPLE_DAYS + " 天，算不出历史日产能");
        }
        positive.sort(BigDecimal::compareTo);
        int n = positive.size();
        BigDecimal median = n % 2 == 1
                ? positive.get(n / 2)
                : positive.get(n / 2 - 1).add(positive.get(n / 2)).divide(BigDecimal.valueOf(2));
        median = median.setScale(DailyCapacityEngine.QTY_SCALE, RoundingMode.HALF_UP);
        return new ExperienceCapacity(median, n, "近 " + WINDOW_DAYS + " 天 " + n + " 个有成品入库的日子的中位数");
    }
}
