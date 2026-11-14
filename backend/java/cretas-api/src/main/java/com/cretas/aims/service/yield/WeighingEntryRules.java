package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.exception.BusinessException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 迁移 P5 称重录入的服务端校验 (前端在字段旁也提示, 这里是兜底, 不能只靠前端)。
 *
 * <ul>
 *   <li><b>R36</b> 开始 / 结束时间不能晚于现在 5 分钟以上 —— 报工日期 (businessDate, 空 = 今天) + 时段 HH:mm
 *       按北京时间拼成时刻; 结束早于开始视为跨零点 (结束算次日)。
 *       businessDate 为空时, 时段放在今天落在未来、放在昨天却在最近 {@link #NIGHT_SHIFT_LOOKBACK} 内
 *       ⇒ 按昨天开始的夜班算 (过了零点补报 23:00 开始的时段, 不误拦)。</li>
 *   <li><b>皮重</b> 只能 ≥ 0, 最多 2 位小数 (列是 NUMERIC(12,2), 多的位不静默舍入); 带皮重时良品单位必须是质量单位;
 *       皮重不能大于良品毛重 (毛重 = 良品净重 outputQuantity + 皮重, 所以等价于净重 ≥ 0, 净重为负的请求在这里拦)。</li>
 * </ul>
 */
public final class WeighingEntryRules {

    public static final ZoneId FACTORY_ZONE = ZoneId.of("Asia/Shanghai");
    public static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(5);
    /** businessDate 为空时, 时段最多可按「昨天开始的夜班」往回放多久。 */
    public static final Duration NIGHT_SHIFT_LOOKBACK = Duration.ofHours(12);
    public static final int TARE_SCALE = 2;
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("H:mm");

    private WeighingEntryRules() {
    }

    /**
     * @param outputIsMass 良品单位是不是质量单位 (皮重按 kg 记, 只能从质量里扣)
     */
    public static void assertTare(BigDecimal tareWeight, BigDecimal netOutput, boolean outputIsMass) {
        if (tareWeight == null) return;
        if (tareWeight.signum() < 0) {
            throw new BusinessException(400, "皮重不能是负数")
                    .withCode("WEIGHING_TARE_NEGATIVE")
                    .withHint("皮重是容器 / 托盘的重量，没有容器就不填或填 0")
                    .withHintTarget("tareWeight");
        }
        if (tareWeight.stripTrailingZeros().scale() > TARE_SCALE) {
            throw new BusinessException(400, "皮重最多 2 位小数")
                    .withCode("WEIGHING_TARE_SCALE")
                    .withHint("皮重按千克记到 0.01 kg，请改成 2 位小数，例如 " + tareWeight.setScale(TARE_SCALE, java.math.RoundingMode.HALF_UP).toPlainString())
                    .withHintTarget("tareWeight");
        }
        if (!outputIsMass) {
            throw new BusinessException(400, "带皮重时良品单位必须是重量单位")
                    .withCode("WEIGHING_TARE_UNIT_NOT_MASS")
                    .withHint("皮重按千克从良品毛重里扣；良品按个、盒等计数时请不要填皮重，或把良品单位改成 kg")
                    .withHintTarget("tareWeight");
        }
        if (netOutput != null && netOutput.signum() < 0) {
            throw new BusinessException(400, "皮重大于良品毛重，良品净重成了负数")
                    .withCode("WEIGHING_TARE_EXCEEDS_GROSS")
                    .withHint("请核对良品毛重和皮重：良品净重 = 良品毛重 − 皮重")
                    .withHintTarget("tareWeight");
        }
    }

    /** R36: 用工厂时区的当前时刻校验。 */
    public static void assertNotLaterThanNow(LocalDate businessDate,
                                             List<YieldReportRequest.LaborSegment> segments,
                                             Clock clock) {
        if (segments == null || segments.isEmpty()) return;
        LocalDateTime now = LocalDateTime.now(clock.withZone(FACTORY_ZONE));
        LocalDate day = businessDate != null ? businessDate : now.toLocalDate();
        LocalDateTime limit = now.plus(FUTURE_TOLERANCE);
        for (int i = 0; i < segments.size(); i++) {
            YieldReportRequest.LaborSegment s = segments.get(i);
            if (s == null) continue;
            LocalTime start = parse(s.getStartTime());
            LocalTime end = parse(s.getEndTime());
            LocalDateTime[] at = place(day, start, end);
            if (businessDate == null && inFuture(at, limit)) {
                // 没写报工日期: 夜班过了零点补报昨天开始的时段 ⇒ 放到昨天, 只要它在最近 12 小时内开始
                LocalDateTime[] y = place(day.minusDays(1), start, end);
                LocalDateTime first = y[0] != null ? y[0] : y[1];
                if (!inFuture(y, limit) && first != null && !first.isBefore(now.minus(NIGHT_SHIFT_LOOKBACK))) {
                    at = y;
                }
            }
            if (at[0] != null && at[0].isAfter(limit)) throw future("开始", i, "startTime");
            if (at[1] != null && at[1].isAfter(limit)) throw future("结束", i, "endTime");
        }
    }

    /** 时段放在某一天: [开始, 结束]; 结束早于开始 = 跨零点, 结束算次日。 */
    private static LocalDateTime[] place(LocalDate day, LocalTime start, LocalTime end) {
        LocalDateTime startAt = start == null ? null : day.atTime(start);
        LocalDateTime endAt = end == null ? null : day.atTime(end);
        if (startAt != null && endAt != null && endAt.isBefore(startAt)) endAt = endAt.plusDays(1);
        return new LocalDateTime[] {startAt, endAt};
    }

    private static boolean inFuture(LocalDateTime[] at, LocalDateTime limit) {
        return (at[0] != null && at[0].isAfter(limit)) || (at[1] != null && at[1].isAfter(limit));
    }

    private static BusinessException future(String which, int index, String field) {
        return new BusinessException(400, which + "时间晚于现在（超过 5 分钟）")
                .withCode("WEIGHING_TIME_IN_FUTURE")
                .withHint("请改成已经发生的时间；称重的开始、结束时间不能晚于现在 5 分钟以上")
                .withHintTarget("laborSegments[" + index + "]." + field);
    }

    private static LocalTime parse(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalTime.parse(v.trim(), HHMM);
        } catch (DateTimeParseException e) {
            return null;   // 格式问题交给原有的工时计算路径处理, 这里只管「是不是在未来」
        }
    }
}
