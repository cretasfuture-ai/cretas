package com.cretas.aims.ai.tool.impl.scheduling;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 急单原话里的数量 / 单位 / 交期 (纯函数, 不读库)。
 *
 * <p>模型抽出来的参数优先; 没有时才从原话里按规则读, 读不出就是「缺」, ⛔ 不猜默认值。
 * 单位换算只做同一量纲的确定换算 (吨 / 斤 → 千克); 跨量纲 (箱 → 千克) 一律不换, 由调用方说「缺换算」。
 */
public final class RushOrderRequestParser {

    private RushOrderRequestParser() {
    }

    /** 数量 + 用户说的单位 (原样); unit 为 null 表示没说单位。 */
    public record SaidQuantity(BigDecimal value, String unit) {
    }

    private static final Pattern QUANTITY = Pattern.compile(
            "(\\d+(?:\\.\\d+)?|[一二两三四五六七八九十百]+)\\s*(吨|公吨|t(?![a-z])|千克|公斤|kg|斤|件|个|箱|批|袋|盒|包)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WEEKDAY = Pattern.compile("(下下|下个?|这个?|本)?(?:周|星期|礼拜)([一二三四五六日天1-7])");
    private static final Pattern MONTH_DAY = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})\\s*[日号]?");
    private static final Pattern ISO_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

    public static Optional<SaidQuantity> quantityFromText(String text) {
        if (text == null) return Optional.empty();
        Matcher m = QUANTITY.matcher(text);
        if (!m.find()) return Optional.empty();
        BigDecimal v = parseNumber(m.group(1));
        if (v == null || v.signum() <= 0) return Optional.empty();
        return Optional.of(new SaidQuantity(v, m.group(2)));
    }

    /** 参数里的数量: 数字或数字字符串, 其它返回 null。 */
    public static BigDecimal number(Object raw) {
        if (raw == null) return null;
        if (raw instanceof BigDecimal b) return b;
        if (raw instanceof Number n) return new BigDecimal(n.toString());
        String s = raw.toString().trim();
        if (s.isEmpty()) return null;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return parseNumber(s);
        }
    }

    static BigDecimal parseNumber(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
            try {
                return new BigDecimal(s);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // 简单中文数字: 两 / 十二 / 二十 / 三百
        int total = 0;
        int current = 0;
        for (char c : s.toCharArray()) {
            int d = "零一二三四五六七八九".indexOf(c);
            if (c == '两') d = 2;
            if (d >= 0) {
                current = d;
            } else if (c == '十') {
                total += (current == 0 ? 1 : current) * 10;
                current = 0;
            } else if (c == '百') {
                total += (current == 0 ? 1 : current) * 100;
                current = 0;
            } else {
                return null;
            }
        }
        total += current;
        return total == 0 ? null : BigDecimal.valueOf(total);
    }

    /** 千克族单位 (产品单位或用户说的单位)。 */
    static boolean isKgFamily(String unit) {
        if (unit == null) return false;
        String u = unit.trim().toLowerCase(Locale.ROOT);
        return u.equals("kg") || u.equals("公斤") || u.equals("千克");
    }

    private static final Map<String, BigDecimal> TO_KG = Map.of(
            "吨", new BigDecimal("1000"), "公吨", new BigDecimal("1000"), "t", new BigDecimal("1000"),
            "斤", new BigDecimal("0.5"));

    /**
     * 把用户说的数量换成产品自带单位的数量。用户没说单位 = 按产品单位理解; 同名单位直接用;
     * 产品按千克计而用户说吨 / 斤时做确定换算; 其余返回 empty (调用方说「缺换算」)。
     */
    public static Optional<BigDecimal> toProductUnit(SaidQuantity said, String productUnit) {
        if (said == null || said.value() == null || productUnit == null || productUnit.isBlank()) {
            return Optional.empty();
        }
        String saidUnit = said.unit();
        if (saidUnit == null || saidUnit.isBlank()) return Optional.of(said.value());
        String pu = productUnit.trim();
        String su = saidUnit.trim();
        if (pu.equalsIgnoreCase(su)) return Optional.of(said.value());
        if (isKgFamily(pu) && isKgFamily(su)) return Optional.of(said.value());
        if (isKgFamily(pu)) {
            BigDecimal factor = TO_KG.get(su.toLowerCase(Locale.ROOT));
            if (factor != null) return Optional.of(said.value().multiply(factor).stripTrailingZeros());
        }
        if (("件".equals(pu) || "个".equals(pu)) && ("件".equals(su) || "个".equals(su))) {
            return Optional.of(said.value());
        }
        return Optional.empty();
    }

    /**
     * 交期日: 参数 (yyyy-MM-dd 或 ISO 日期时间) 优先, 否则读原话里的「周五 / 下周三 / 明天 / 11月20日 / 2026-11-20」。
     * 「周X」指今天起 (含今天) 最近的那个周X; 「下周X」指下一个自然周的周X。
     */
    public static Optional<LocalDate> deadline(Object param, String text, LocalDate today) {
        if (param != null && !param.toString().isBlank()) {
            String p = param.toString().trim();
            try {
                return Optional.of(p.length() > 10 ? LocalDateTime.parse(p).toLocalDate() : LocalDate.parse(p));
            } catch (DateTimeParseException ignored) {
                Optional<LocalDate> fromParamText = deadlineFromText(p, today);
                if (fromParamText.isPresent()) return fromParamText;
            }
        }
        return deadlineFromText(text, today);
    }

    static Optional<LocalDate> deadlineFromText(String text, LocalDate today) {
        if (text == null) return Optional.empty();
        Matcher iso = ISO_DATE.matcher(text);
        if (iso.find()) {
            try {
                return Optional.of(LocalDate.of(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)),
                        Integer.parseInt(iso.group(3))));
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        Matcher md = MONTH_DAY.matcher(text);
        if (md.find()) {
            try {
                LocalDate d = LocalDate.of(today.getYear(), Integer.parseInt(md.group(1)), Integer.parseInt(md.group(2)));
                return Optional.of(d.isBefore(today) ? d.plusYears(1) : d);
            } catch (RuntimeException e) {
                return Optional.empty();
            }
        }
        Matcher w = WEEKDAY.matcher(text);
        if (w.find()) {
            DayOfWeek dow = dayOfWeek(w.group(2));
            String prefix = w.group(1);
            if (prefix != null && prefix.startsWith("下")) {
                int weeks = prefix.startsWith("下下") ? 2 : 1;
                LocalDate monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusWeeks(weeks);
                return Optional.of(monday.with(TemporalAdjusters.nextOrSame(dow)));
            }
            return Optional.of(today.with(TemporalAdjusters.nextOrSame(dow)));
        }
        if (text.contains("大后天")) return Optional.of(today.plusDays(3));
        if (text.contains("后天")) return Optional.of(today.plusDays(2));
        if (text.contains("明天")) return Optional.of(today.plusDays(1));
        if (text.contains("今天")) return Optional.of(today);
        return Optional.empty();
    }

    private static DayOfWeek dayOfWeek(String c) {
        return switch (c) {
            case "一", "1" -> DayOfWeek.MONDAY;
            case "二", "2" -> DayOfWeek.TUESDAY;
            case "三", "3" -> DayOfWeek.WEDNESDAY;
            case "四", "4" -> DayOfWeek.THURSDAY;
            case "五", "5" -> DayOfWeek.FRIDAY;
            case "六", "6" -> DayOfWeek.SATURDAY;
            default -> DayOfWeek.SUNDAY;
        };
    }
}
