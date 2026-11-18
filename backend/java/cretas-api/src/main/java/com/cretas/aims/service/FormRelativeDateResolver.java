package com.cretas.aims.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI 录入里的相对日期换算（确定性，不交给模型）。
 *
 * <p>事故（2026-11-18）：销售订单录入「下周五交货」，今天周三 11-18，模型给出 11-26（周四）；
 * 采购「下周三」给出 12-01（周二）。模型对星期做加减不可靠，所以这里用 java.time 算，
 * 结果覆盖模型给的日期字段。周以周一为起点：本周X = 本周一 + (X-1) 天；下周X = 本周X + 7；
 * 下下周X = + 14。单独说「周X」= 本周X，但已过去（早于今天）则取下周X。
 */
public final class FormRelativeDateResolver {

    private FormRelativeDateResolver() {}

    /** 一个被识别的日期短语：在原文里的位置 + 换算结果。 */
    public record Hit(int start, int end, String phrase, LocalDate date) {}

    private static final String WD = "一二三四五六日天末";

    private static final Pattern WEEK = Pattern.compile(
            "(?<!上)(下下(?:个)?(?:周|星期|礼拜)|下(?:个)?(?:周|星期|礼拜)|(?:本|这)(?:个)?(?:周|星期|礼拜)|(?:周|星期|礼拜))([" + WD + "])");
    private static final Pattern DAYS = Pattern.compile("(大后天|后天|明天|明日|今天|今日)");
    private static final Pattern N_DAYS_LATER =
            Pattern.compile("([0-9]{1,3}|[一二两三四五六七八九十]{1,3})\\s*(?:天|日)(?:后|之后)");
    private static final Pattern MONTH_DAY =
            Pattern.compile("(?:(下)(?:个)?月|([0-9]{1,2})月)\\s*([0-9]{1,2})\\s*(?:号|日)");
    private static final Pattern DELIVERY_KW = Pattern.compile("交货|到货|送达|交付|交期|送到|发货");

    public static List<Hit> findAll(String text, LocalDate today) {
        List<Hit> hits = new ArrayList<>();
        if (text == null || text.isBlank() || today == null) return hits;

        Matcher m = WEEK.matcher(text);
        while (m.find()) {
            String prefix = m.group(1);
            int dow = weekdayIndex(m.group(2).charAt(0));
            LocalDate d = today.with(DayOfWeek.MONDAY).plusDays(dow - 1L);
            if (prefix.startsWith("下下")) {
                d = d.plusWeeks(2);
            } else if (prefix.startsWith("下")) {
                d = d.plusWeeks(1);
            } else if (!(prefix.startsWith("本") || prefix.startsWith("这")) && d.isBefore(today)) {
                d = d.plusWeeks(1);
            }
            hits.add(new Hit(m.start(), m.end(), m.group(), d));
        }
        m = DAYS.matcher(text);
        while (m.find()) {
            int off;
            switch (m.group(1)) {
                case "大后天": off = 3; break;
                case "后天": off = 2; break;
                case "明天": case "明日": off = 1; break;
                default: off = 0;
            }
            hits.add(new Hit(m.start(), m.end(), m.group(), today.plusDays(off)));
        }
        m = N_DAYS_LATER.matcher(text);
        while (m.find()) {
            Integer n = parseSmallInt(m.group(1));
            if (n != null) hits.add(new Hit(m.start(), m.end(), m.group(), today.plusDays(n)));
        }
        m = MONTH_DAY.matcher(text);
        while (m.find()) {
            int day = Integer.parseInt(m.group(3));
            LocalDate d;
            try {
                if (m.group(1) != null) {
                    d = today.withDayOfMonth(1).plusMonths(1).withDayOfMonth(day);
                } else {
                    d = LocalDate.of(today.getYear(), Integer.parseInt(m.group(2)), day);
                    if (d.isBefore(today)) d = d.plusYears(1);
                }
            } catch (RuntimeException e) {
                continue; // 2 月 30 日之类：不是日期，不换算
            }
            hits.add(new Hit(m.start(), m.end(), m.group(), d));
        }
        hits.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return hits;
    }

    /**
     * 取「交货日期」对应的那一个。只有一个日期时就是它；有多个（如「明天下单，下周五交货」）
     * 时取紧贴交货/到货/送达/交期字样的那个；认不准返回 empty（不覆盖模型，也不猜）。
     */
    public static Optional<LocalDate> resolveDelivery(String text, LocalDate today) {
        List<Hit> flat = new ArrayList<>();
        for (Hit h : findAll(text, today)) {
            if (flat.isEmpty() || h.start() >= flat.get(flat.size() - 1).end()) flat.add(h);
        }
        if (flat.isEmpty()) return Optional.empty();
        Set<LocalDate> distinct = new LinkedHashSet<>();
        flat.forEach(h -> distinct.add(h.date()));
        if (distinct.size() == 1) return Optional.of(flat.get(0).date());
        for (Hit h : flat) {
            String after = text.substring(h.end(), Math.min(text.length(), h.end() + 6));
            if (DELIVERY_KW.matcher(after).find()) return Optional.of(h.date());
        }
        for (Hit h : flat) {
            String before = text.substring(Math.max(0, h.start() - 6), h.start());
            if (DELIVERY_KW.matcher(before).find()) return Optional.of(h.date());
        }
        return Optional.empty();
    }

    private static int weekdayIndex(char c) {
        switch (c) {
            case '一': return 1;
            case '二': return 2;
            case '三': return 3;
            case '四': return 4;
            case '五': return 5;
            case '六': return 6;
            default: return 7; // 日/天/末
        }
    }

    private static Integer parseSmallInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ignore) {
            // 中文数字
        }
        String t = s.replace('两', '二');
        String digits = "零一二三四五六七八九";
        if (t.equals("十")) return 10;
        if (t.length() == 1) {
            int i = digits.indexOf(t.charAt(0));
            return i >= 0 ? i : null;
        }
        if (t.length() == 2 && t.charAt(0) == '十') {
            int i = digits.indexOf(t.charAt(1));
            return i >= 0 ? 10 + i : null;
        }
        if (t.length() == 2 && t.charAt(1) == '十') {
            int i = digits.indexOf(t.charAt(0));
            return i >= 0 ? i * 10 : null;
        }
        if (t.length() == 3 && t.charAt(1) == '十') {
            int a = digits.indexOf(t.charAt(0));
            int b = digits.indexOf(t.charAt(2));
            return a >= 0 && b >= 0 ? a * 10 + b : null;
        }
        return null;
    }

    /** 给 prompt 用的日历：今天起 3 周的「周X = 日期」，让模型直接查表而不是算。 */
    public static String calendarForPrompt(LocalDate today) {
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        StringBuilder sb = new StringBuilder();
        sb.append("今天是周").append(names[today.getDayOfWeek().getValue() - 1])
          .append("。周以周一为起点。日期对照表（直接查表，不要自己推算）：\n");
        LocalDate monday = today.with(DayOfWeek.MONDAY);
        String[] labels = {"本周", "下周", "下下周"};
        for (int w = 0; w < 3; w++) {
            sb.append(labels[w]).append(": ");
            for (int i = 0; i < 7; i++) {
                if (i > 0) sb.append("，");
                sb.append("周").append(names[i]).append("=").append(monday.plusWeeks(w).plusDays(i));
            }
            sb.append("\n");
        }
        sb.append("明天=").append(today.plusDays(1)).append("，后天=").append(today.plusDays(2)).append("\n");
        return sb.toString();
    }
}
