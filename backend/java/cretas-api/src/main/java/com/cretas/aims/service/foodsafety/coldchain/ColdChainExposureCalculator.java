package com.cretas.aims.service.foodsafety.coldchain;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 超温时段 × 批次在库时段 → 暴露时长 (食品 DAG 缺口 15)。纯函数, 不碰数据库。
 *
 * <p>三步:
 * <ol>
 *   <li>{@link #excursionAround}: 从读数里找出告警所在的那一段连续超温 [首个超温读数, 最后一个超温读数]。
 *       只看读数本身和设备的目标区间, 不信 {@code is_deviation} 标记 (scheduler 只在扫描窗口里补标)。</li>
 *   <li>{@link #segments}: 超温时段与设备的库位生效时段求交 —— 设备中途换库, 两段分别落到两个库。
 *       没登记库位的那部分时段单独返回 ({@link Segments#uncovered()}), 不当成「没有受影响批次」。</li>
 *   <li>{@link #expose}: 批次在库时段与这些段求交。在库的起止时刻可能只知道一个范围 (见 {@link Stay}),
 *       于是算最少 / 最多暴露: 最多为 0 ⇒ 不受影响; 最少 = 最多 ⇒ 确定; 否则 ⇒「在库时间不确定」并给出最多多少分钟。
 *       ⛔ 不在范围里挑一个时刻当真。</li>
 * </ol>
 */
public final class ColdChainExposureCalculator {

    private ColdChainExposureCalculator() {}

    public record Reading(LocalDateTime at, BigDecimal temperature) {}

    /** 一段连续超温. {@code ongoing} = 最后一个读数仍超温 (还没恢复)。 */
    public record Excursion(LocalDateTime start, LocalDateTime end, BigDecimal minTemp, BigDecimal maxTemp,
                            int readingCount, boolean ongoing) {
        public long minutes() {
            return Duration.between(start, end).toMinutes();
        }
    }

    /** 设备在某库的生效时段 {@code [from, to)}, {@code to = null} 表示至今。 */
    public record LocationSpan(String warehouseId, LocalDateTime from, LocalDateTime to) {}

    /** 超温时段落在某个库的那一段。 */
    public record Segment(String warehouseId, LocalDateTime start, LocalDateTime end) {}

    /** 超温时段里没登记库位的一段。 */
    public record Gap(LocalDateTime start, LocalDateTime end) {}

    public record Segments(List<Segment> covered, List<Gap> uncovered) {}

    /**
     * 批次在某库的在库时段。入库时刻 ∈ [inEarliest, inLatest]; 出库时刻 ∈ [outEarliest, outLatest],
     * {@code outEarliest = null} 表示仍在库。确定的时刻两端相等, 不确定时 {@code reason} 写明为什么。
     */
    public record Stay(LocalDateTime inEarliest, LocalDateTime inLatest,
                       LocalDateTime outEarliest, LocalDateTime outLatest, String reason) {

        public static Stay exact(LocalDateTime in, LocalDateTime out) {
            return new Stay(in, in, out, out, null);
        }

        public boolean stillInStock() {
            return outEarliest == null;
        }
    }

    public enum Verdict { AFFECTED, NOT_AFFECTED, UNCERTAIN }

    /**
     * 暴露结论。AFFECTED: {@code minutes} 确定; UNCERTAIN: 只知道最多 {@code maxMinutes};
     * {@code firstExposedAt / lastExposedAt} 是最大可能暴露的起止 (NOT_AFFECTED 时为 null)。
     */
    public record Exposure(Verdict verdict, Long minutes, long maxMinutes,
                           LocalDateTime firstExposedAt, LocalDateTime lastExposedAt, String reason) {}

    public static boolean deviates(BigDecimal t, BigDecimal targetMin, BigDecimal targetMax) {
        return t.compareTo(targetMin) < 0 || t.compareTo(targetMax) > 0;
    }

    /**
     * 找告警所在的那一段连续超温: 锚点 = 不晚于 {@code anchor} 的最后一个超温读数, 向前、向后扩到第一个不超温的读数为止。
     * 没有这样的读数 ⇒ empty (调用方如实报「读数里找不到这段超温」, 不拿告警的持续时长去倒推)。
     */
    public static Optional<Excursion> excursionAround(List<Reading> readings, BigDecimal targetMin,
                                                      BigDecimal targetMax, LocalDateTime anchor) {
        List<Reading> sorted = new ArrayList<>(readings);
        sorted.sort(Comparator.comparing(Reading::at));
        int pivot = -1;
        for (int i = 0; i < sorted.size(); i++) {
            Reading r = sorted.get(i);
            if (r.at().isAfter(anchor)) break;
            if (deviates(r.temperature(), targetMin, targetMax)) pivot = i;
        }
        if (pivot < 0) return Optional.empty();
        int lo = pivot;
        while (lo > 0 && deviates(sorted.get(lo - 1).temperature(), targetMin, targetMax)) lo--;
        int hi = pivot;
        while (hi < sorted.size() - 1 && deviates(sorted.get(hi + 1).temperature(), targetMin, targetMax)) hi++;
        BigDecimal min = null;
        BigDecimal max = null;
        for (int i = lo; i <= hi; i++) {
            BigDecimal t = sorted.get(i).temperature();
            if (min == null || t.compareTo(min) < 0) min = t;
            if (max == null || t.compareTo(max) > 0) max = t;
        }
        boolean ongoing = hi == sorted.size() - 1;
        return Optional.of(new Excursion(sorted.get(lo).at(), sorted.get(hi).at(), min, max, hi - lo + 1, ongoing));
    }

    /** 超温时段 × 设备库位时段。 */
    public static Segments segments(LocalDateTime start, LocalDateTime end, List<LocationSpan> spans) {
        List<LocationSpan> sorted = new ArrayList<>(spans);
        sorted.sort(Comparator.comparing(LocationSpan::from));
        List<Segment> covered = new ArrayList<>();
        for (LocationSpan s : sorted) {
            LocalDateTime a = later(start, s.from());
            LocalDateTime b = s.to() == null ? end : earlier(end, s.to());
            if (a.isBefore(b)) {
                covered.add(new Segment(s.warehouseId(), a, b));
            }
        }
        List<Gap> uncovered = new ArrayList<>();
        LocalDateTime cursor = start;
        for (Segment seg : covered) {
            if (seg.start().isAfter(cursor)) uncovered.add(new Gap(cursor, seg.start()));
            cursor = later(cursor, seg.end());
        }
        if (cursor.isBefore(end)) uncovered.add(new Gap(cursor, end));
        return new Segments(covered, uncovered);
    }

    /** 批次在库时段 × 该批次所在库的超温段。 */
    public static Exposure expose(Stay stay, List<Segment> segmentsInBatchWarehouse) {
        // 最多: 最早进、最晚出; 最少: 最晚进、最早出
        LocalDateTime maxIn = stay.inEarliest();
        LocalDateTime maxOut = stay.stillInStock() ? null : stay.outLatest();
        LocalDateTime minIn = stay.inLatest();
        LocalDateTime minOut = stay.stillInStock() ? null : stay.outEarliest();

        long maxMinutes = 0;
        long minMinutes = 0;
        LocalDateTime first = null;
        LocalDateTime last = null;
        for (Segment seg : segmentsInBatchWarehouse) {
            Overlap o = overlap(seg, maxIn, maxOut);
            if (o != null) {
                maxMinutes += o.minutes();
                if (first == null || o.from().isBefore(first)) first = o.from();
                if (last == null || o.to().isAfter(last)) last = o.to();
            }
            Overlap m = overlap(seg, minIn, minOut);
            if (m != null) minMinutes += m.minutes();
        }
        if (first == null) {
            return new Exposure(Verdict.NOT_AFFECTED, 0L, 0, null, null, null);
        }
        if (minMinutes == maxMinutes) {
            // 暴露对在库区间单调: 最少 = 最多 ⇒ 不管真实时刻落在范围哪里, 暴露时长都是这个数
            return new Exposure(Verdict.AFFECTED, maxMinutes, maxMinutes, first, last, null);
        }
        return new Exposure(Verdict.UNCERTAIN, null, maxMinutes, first, last,
                stay.reason() != null ? stay.reason() : "在库时间不确定");
    }

    private record Overlap(LocalDateTime from, LocalDateTime to) {
        long minutes() {
            return Duration.between(from, to).toMinutes();
        }
    }

    /** 段 [s, e] 与在库 [in, out) 求交; out = null 表示仍在库。 */
    private static Overlap overlap(Segment seg, LocalDateTime in, LocalDateTime out) {
        LocalDateTime a = later(seg.start(), in);
        LocalDateTime b = out == null ? seg.end() : earlier(seg.end(), out);
        if (a.isBefore(b)) return new Overlap(a, b);
        return null;
    }

    private static LocalDateTime later(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }
}
