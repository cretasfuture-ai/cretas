package com.cretas.aims.config;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 时间表达归一化规则配置
 *
 * 将用户输入中的相对时间表达式（如"今天"、"上周"、"最近3天"）
 * 转换为具体的时间范围，用于查询预处理。
 *
 * 支持的时间表达式类型：
 * - 单日：今天、昨天、前天、大前天、明天
 * - 周：这周/本周、上周、上上周、下周
 * - 月：这个月/本月、上个月/上月、上上个月
 * - 模糊时间：最近N天、刚才/刚刚、最近
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Slf4j
@Component
public class TimeNormalizationRules {

    /**
     * 时间范围类
     */
    @Data
    @AllArgsConstructor(staticName = "of")
    public static class TimeRange {
        private LocalDateTime start;
        private LocalDateTime end;

        /**
         * 创建从指定时间到当前时间的范围
         */
        public static TimeRange until(LocalDateTime start, LocalDateTime now) {
            return of(start, now);
        }

        /**
         * 检查时间范围是否有效
         */
        public boolean isValid() {
            return start != null && end != null && !start.isAfter(end);
        }

        @Override
        public String toString() {
            return String.format("[%s ~ %s]", start, end);
        }
    }

    /**
     * 时间模式映射：正则表达式 -> 时间范围计算函数
     * 使用 BiFunction 支持动态参数提取
     */
    private static final String YEAR_PREFIX = "(今年|本年|明年|下一年|去年|上一年|前年)";
    private static final String MONTH_TEXT = "(\\d{1,2}|[一二三四五六七八九十]{1,3})";
    /** 「N月之前/以前/前」(前后接 年/天/后 的不算: 前年/前天/前后)。 */
    private static final String BEFORE_WORD = "(?:以前|之前|前(?![年天后]))";
    /** 环比对比基准「和上月比 / 比上月 / 环比上月」里的「上月」: 不是本期, 不参与时间提取。与 ReportCostQueryTool.MONTH_OVER_MONTH 同口径。 */
    private static final Pattern MOM_BASE = Pattern.compile(
            "(?:和|与|跟|同|比)(上个月|上月)(?:比|相比|对比)?|比(上个月|上月)|环比(上个月|上月)");
    private static final char MOM_PLACEHOLDER = '\uE000';
    private static final Map<Pattern, BiFunction<LocalDateTime, Matcher, TimeRange>> TIME_PATTERNS = new LinkedHashMap<>();

    static {
        // === 单日 ===
        TIME_PATTERNS.put(
                Pattern.compile("今天|今日"),
                (now, m) -> dayRange(now, 0)
        );
        TIME_PATTERNS.put(
                Pattern.compile("昨天|昨日"),
                (now, m) -> dayRange(now, -1)
        );
        TIME_PATTERNS.put(
                Pattern.compile("前天"),
                (now, m) -> dayRange(now, -2)
        );
        TIME_PATTERNS.put(
                Pattern.compile("大前天"),
                (now, m) -> dayRange(now, -3)
        );
        TIME_PATTERNS.put(
                Pattern.compile("明天|明日"),
                (now, m) -> dayRange(now, 1)
        );
        TIME_PATTERNS.put(
                Pattern.compile("后天"),
                (now, m) -> dayRange(now, 2)
        );

        // === 周 ===
        TIME_PATTERNS.put(
                Pattern.compile("这周|本周|这个星期|这个礼拜"),
                (now, m) -> weekRange(now, 0)
        );
        TIME_PATTERNS.put(
                Pattern.compile("上周|上一周|上个星期|上个礼拜"),
                (now, m) -> weekRange(now, -1)
        );
        TIME_PATTERNS.put(
                Pattern.compile("上上周|上上个星期"),
                (now, m) -> weekRange(now, -2)
        );
        TIME_PATTERNS.put(
                Pattern.compile("下周|下一周|下个星期|下个礼拜"),
                (now, m) -> weekRange(now, 1)
        );

        // === 月 ===
        TIME_PATTERNS.put(
                Pattern.compile("这个月|本月|当月"),
                (now, m) -> monthRange(now, 0)
        );
        TIME_PATTERNS.put(
                Pattern.compile("上个月|上月"),
                (now, m) -> monthRange(now, -1)
        );
        TIME_PATTERNS.put(
                Pattern.compile("上上个月|上上月"),
                (now, m) -> monthRange(now, -2)
        );
        TIME_PATTERNS.put(
                Pattern.compile("下个月|下月"),
                (now, m) -> monthRange(now, 1)
        );

        // === 年 ===
        // 「明年一月」「去年3月」「今年十二月」: 带年份前缀的具体月份。必须排在下面整年规则之前,
        // 否则「明年」先被吃成整年 (2027-01-01~2027-12-31), 「一月」被丢掉 (2026-11-15 财务复跑 Q17)。
        // 区间 / 「以来」先于单月规则整体识别 (否则「今年3月份到5月份」「去年12月到今年2月」「今年3月起」只剩一个月, 静默丢数据):
        //   「今年3月(份)到5月(份)」「去年12月到今年2月」→ 首月 1 号 ~ 末月月底; 「今年3月(份)以来/起/开始/至今」→ 该月 1 号 ~ 现在。
        TIME_PATTERNS.put(
                Pattern.compile(YEAR_PREFIX + MONTH_TEXT + "月(?:份)?\\s*(?:到|至|-|~|—)\\s*" + YEAR_PREFIX + "?" + MONTH_TEXT + "月(?:份)?"),
                (now, m) -> {
                    int y1 = now.getYear() + yearOffsetOf(m.group(1));
                    int m1 = parseMonthNumber(m.group(2));
                    int m2 = parseMonthNumber(m.group(4));
                    // 第二个月没写年份: 月份比起始月小就是跨年 (「去年12月到2月」= 去年12月~今年2月)
                    int y2 = m.group(3) != null ? now.getYear() + yearOffsetOf(m.group(3)) : (m2 < m1 ? y1 + 1 : y1);
                    if (m1 < 1 || m1 > 12 || m2 < 1 || m2 > 12) {
                        throw new IllegalArgumentException("月份超出范围: " + m.group(2) + "/" + m.group(4));
                    }
                    LocalDateTime first = LocalDateTime.of(y1, m1, 1, 0, 0).with(LocalTime.MIN);
                    LocalDateTime last = LocalDateTime.of(y2, m2, 1, 0, 0).with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX);
                    if (last.isBefore(first)) {
                        throw new IllegalArgumentException("月份区间倒置");
                    }
                    return TimeRange.of(first, last);
                }
        );
        TIME_PATTERNS.put(
                Pattern.compile(YEAR_PREFIX + MONTH_TEXT + "月(?:份)?\\s*(?:以来|以后|之后|后(?![天年])|起|开始|至今)"),
                (now, m) -> {
                    int month = parseMonthNumber(m.group(2));
                    if (month < 1 || month > 12) {
                        throw new IllegalArgumentException("月份超出范围: " + m.group(2));
                    }
                    LocalDateTime first = LocalDateTime.of(now.getYear() + yearOffsetOf(m.group(1)), month, 1, 0, 0);
                    return TimeRange.of(first, now.isBefore(first) ? first.with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX) : now);
                }
        );
        // 「今年3月之前/以前/前」= 年初 ~ 上个月月底 (3月本身不含); 「今年1月之前」之前没有可统计的月份 -> 如实认不出, 不猜 (见下面整年规则的排除)。
        TIME_PATTERNS.put(
                Pattern.compile(YEAR_PREFIX + MONTH_TEXT + "月(?:份)?\\s*" + BEFORE_WORD),
                (now, m) -> {
                    int month = parseMonthNumber(m.group(2));
                    if (month < 2 || month > 12) {
                        throw new IllegalArgumentException("月份之前没有可统计的月份: " + m.group(2));
                    }
                    int year = now.getYear() + yearOffsetOf(m.group(1));
                    LocalDateTime first = LocalDateTime.of(year, 1, 1, 0, 0);
                    LocalDateTime last = LocalDateTime.of(year, month - 1, 1, 0, 0)
                            .with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX);
                    return TimeRange.of(first, last);
                }
        );
        // 单月: 「今年10月1日」这种具体日期不是单月, 照旧落到整年规则 (偏宽但不丢数据)。
        // 「份」用占有量词, 否则回溯成空后前瞻看到「份」就放行。上一个词是「到/至」的年月已被上面的区间规则吃掉。
        TIME_PATTERNS.put(
                Pattern.compile("(?<![到至\\-~—])" + YEAR_PREFIX + MONTH_TEXT + "月(?:份)?+(?!\\s*(?:到|至|-|~|—|以来|以后|之后|后|以前|之前|前|[和、]\\s*" + MONTH_TEXT + "月|起|开始|\\d{1,2}[日号]|[一二三四五六七八九十]{1,3}[日号]))"),
                (now, m) -> {
                    int yearOffset = yearOffsetOf(m.group(1));
                    int month = parseMonthNumber(m.group(2));
                    if (month < 1 || month > 12) {
                        throw new IllegalArgumentException("月份超出范围: " + m.group(2));
                    }
                    LocalDateTime first = now.plusYears(yearOffset).withMonth(month).with(TemporalAdjusters.firstDayOfMonth())
                            .with(LocalTime.MIN);
                    return TimeRange.of(first, first.with(TemporalAdjusters.lastDayOfMonth()).with(LocalTime.MAX));
                }
        );
        TIME_PATTERNS.put(
                Pattern.compile("(?:今年|本年)(?!" + MONTH_TEXT + "月(?:份)?\\s*" + BEFORE_WORD + ")"),
                (now, m) -> yearRange(now, 0)
        );
        TIME_PATTERNS.put(
                Pattern.compile("(?:去年|上一年)(?!" + MONTH_TEXT + "月(?:份)?\\s*" + BEFORE_WORD + ")"),
                (now, m) -> yearRange(now, -1)
        );
        TIME_PATTERNS.put(
                Pattern.compile("(?:明年|下一年)(?!" + MONTH_TEXT + "月(?:份)?\\s*" + BEFORE_WORD + ")"),
                (now, m) -> yearRange(now, 1)
        );

        // === 动态模糊时间 (需要提取数字) ===
        TIME_PATTERNS.put(
                Pattern.compile("最近(\\d+)天"),
                (now, m) -> {
                    int days = Integer.parseInt(m.group(1));
                    return TimeRange.of(now.minusDays(days).with(LocalTime.MIN), now);
                }
        );
        TIME_PATTERNS.put(
                Pattern.compile("最近(\\d+)周"),
                (now, m) -> {
                    int weeks = Integer.parseInt(m.group(1));
                    return TimeRange.of(now.minusWeeks(weeks).with(LocalTime.MIN), now);
                }
        );
        TIME_PATTERNS.put(
                Pattern.compile("最近(\\d+)个月"),
                (now, m) -> {
                    int months = Integer.parseInt(m.group(1));
                    return TimeRange.of(now.minusMonths(months).with(LocalTime.MIN), now);
                }
        );
        TIME_PATTERNS.put(
                Pattern.compile("(\\d+)天前"),
                (now, m) -> {
                    int days = Integer.parseInt(m.group(1));
                    return dayRange(now, -days);
                }
        );
        TIME_PATTERNS.put(
                Pattern.compile("(\\d+)天内"),
                (now, m) -> {
                    int days = Integer.parseInt(m.group(1));
                    return TimeRange.of(now.minusDays(days).with(LocalTime.MIN), now);
                }
        );

        // === 固定模糊时间 ===
        TIME_PATTERNS.put(
                Pattern.compile("刚才|刚刚|刚"),
                (now, m) -> TimeRange.of(now.minusHours(2), now)
        );
        TIME_PATTERNS.put(
                Pattern.compile("最近"),
                (now, m) -> TimeRange.of(now.minusDays(7).with(LocalTime.MIN), now)
        );
        TIME_PATTERNS.put(
                Pattern.compile("近期"),
                (now, m) -> TimeRange.of(now.minusDays(14).with(LocalTime.MIN), now)
        );
        TIME_PATTERNS.put(
                Pattern.compile("早上|上午"),
                (now, m) -> TimeRange.of(
                        now.with(LocalTime.of(6, 0)),
                        now.with(LocalTime.of(12, 0))
                )
        );
        TIME_PATTERNS.put(
                Pattern.compile("下午"),
                (now, m) -> TimeRange.of(
                        now.with(LocalTime.of(12, 0)),
                        now.with(LocalTime.of(18, 0))
                )
        );
        TIME_PATTERNS.put(
                Pattern.compile("晚上"),
                (now, m) -> TimeRange.of(
                        now.with(LocalTime.of(18, 0)),
                        now.with(LocalTime.of(23, 59, 59))
                )
        );
    }

    /**
     * 将时间表达式归一化为具体时间范围
     *
     * @param expression 用户输入的时间表达式
     * @param now        当前时间（通常为 LocalDateTime.now()）
     * @return 归一化后的时间范围，如果无法识别则返回 Optional.empty()
     */
    public Optional<TimeRange> normalize(String expression, LocalDateTime now) {
        if (expression == null || expression.trim().isEmpty()) {
            return Optional.empty();
        }

        String input = expression.trim();

        for (Map.Entry<Pattern, BiFunction<LocalDateTime, Matcher, TimeRange>> entry : TIME_PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(input);
            if (matcher.find()) {
                try {
                    TimeRange range = entry.getValue().apply(now, matcher);
                    log.debug("时间归一化: '{}' -> {}", expression, range);
                    return Optional.of(range);
                } catch (Exception e) {
                    log.warn("时间归一化失败: expression={}, error={}", expression, e.getMessage());
                }
            }
        }

        return Optional.empty();
    }

    /**
     * 从文本中提取并替换所有时间表达式
     *
     * @param text 原始文本
     * @param now  当前时间
     * @return 替换结果（包含替换后文本和提取的时间范围列表）
     */
    public NormalizationResult extractAndReplace(String text, LocalDateTime now) {
        if (text == null || text.trim().isEmpty()) {
            return new NormalizationResult(text, java.util.Collections.emptyList());
        }

        // 环比对比基准「和上月比」的「上月」: 句子里除它之外还有别的时间表达时, 它不是本期 (先换成占位符, 提取完再还原,
        // 否则「今年3月的成本和上月比」本期被取成上月)。它是句子里唯一的时间词时保持原行为 (primary=上月), 不隐藏:
        // 没有环比模式的工具拿不到本期会回落到各自默认期间, 静默放宽数据。
        java.util.List<String> protectedBases = new java.util.ArrayList<>();
        String protectedText = protectMonthOverMonthBase(text, protectedBases);
        if (!protectedBases.isEmpty()) {
            java.util.List<TimeRange> others = new java.util.ArrayList<>();
            String protectedResult = extractAll(protectedText, now, others);
            if (!others.isEmpty()) {
                return new NormalizationResult(restoreMonthOverMonthBase(protectedResult, protectedBases), others);
            }
        }
        java.util.List<TimeRange> extractedRanges = new java.util.ArrayList<>();
        return new NormalizationResult(extractAll(text, now, extractedRanges), extractedRanges);
    }

    private String extractAll(String text, LocalDateTime now, java.util.List<TimeRange> extractedRanges) {
        String result = text;

        for (Map.Entry<Pattern, BiFunction<LocalDateTime, Matcher, TimeRange>> entry : TIME_PATTERNS.entrySet()) {
            Matcher matcher = entry.getKey().matcher(result);
            while (matcher.find()) {
                try {
                    TimeRange range = entry.getValue().apply(now, matcher);
                    extractedRanges.add(range);
                    // 替换为标准化时间范围描述
                    String replacement = formatTimeRange(range);
                    result = result.substring(0, matcher.start()) + replacement + result.substring(matcher.end());
                    // 重新匹配（因为文本已变化）
                    matcher = entry.getKey().matcher(result);
                } catch (Exception e) {
                    log.warn("时间提取失败: pattern={}, error={}", entry.getKey().pattern(), e.getMessage());
                }
            }
        }

        return result;
    }

    private static String protectMonthOverMonthBase(String text, java.util.List<String> bases) {
        Matcher m = MOM_BASE.matcher(text);
        StringBuilder sb = new StringBuilder();
        int last = 0;
        while (m.find()) {
            int g = m.start(1) >= 0 ? 1 : (m.start(2) >= 0 ? 2 : 3);
            bases.add(m.group(g));
            sb.append(text, last, m.start(g)).append(MOM_PLACEHOLDER);
            last = m.end(g);
            // 替换只覆盖「上月」本身, 其余部分 (和 / 比) 原样保留: 继续从 g 组之后扫描
            m.region(Math.max(last, m.end()), text.length());
        }
        sb.append(text, last, text.length());
        return sb.toString();
    }

    private static String restoreMonthOverMonthBase(String text, java.util.List<String> bases) {
        if (bases.isEmpty()) return text;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (char c : text.toCharArray()) {
            if (c == MOM_PLACEHOLDER && i < bases.size()) sb.append(bases.get(i++)); else sb.append(c);
        }
        return sb.toString();
    }

    /**
     * 检查文本是否包含时间表达式
     */
    public boolean containsTimeExpression(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (Pattern pattern : TIME_PATTERNS.keySet()) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    // ==================== 时间范围计算辅助方法 ====================

    /**
     * 计算指定偏移天数的日期范围
     */
    private static TimeRange dayRange(LocalDateTime now, int daysOffset) {
        LocalDateTime targetDay = now.plusDays(daysOffset);
        return TimeRange.of(
                targetDay.with(LocalTime.MIN),
                targetDay.with(LocalTime.MAX)
        );
    }

    /**
     * 计算指定偏移周数的周范围（周一到周日）
     */
    private static TimeRange weekRange(LocalDateTime now, int weeksOffset) {
        LocalDateTime targetWeek = now.plusWeeks(weeksOffset);
        LocalDateTime startOfWeek = targetWeek.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                .with(LocalTime.MIN);
        LocalDateTime endOfWeek = targetWeek.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
                .with(LocalTime.MAX);
        return TimeRange.of(startOfWeek, endOfWeek);
    }

    /**
     * 计算指定偏移月数的月范围
     */
    private static TimeRange monthRange(LocalDateTime now, int monthsOffset) {
        LocalDateTime targetMonth = now.plusMonths(monthsOffset);
        LocalDateTime startOfMonth = targetMonth.with(TemporalAdjusters.firstDayOfMonth())
                .with(LocalTime.MIN);
        LocalDateTime endOfMonth = targetMonth.with(TemporalAdjusters.lastDayOfMonth())
                .with(LocalTime.MAX);
        return TimeRange.of(startOfMonth, endOfMonth);
    }

    /** 年份前缀 → 相对今年的偏移; null (没写年份) 视为今年。 */
    static int yearOffsetOf(String prefix) {
        if (prefix == null) return 0;
        return switch (prefix) {
            case "明年", "下一年" -> 1;
            case "去年", "上一年" -> -1;
            case "前年" -> -2;
            default -> 0;
        };
    }

    /** 「3」「十二」「一」→ 月份数字; 认不出返回 -1。 */
    static int parseMonthNumber(String text) {
        if (text.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(text);
        }
        String digits = "一二三四五六七八九";
        if (text.equals("十")) return 10;
        if (text.length() == 1) return digits.indexOf(text) + 1 > 0 ? digits.indexOf(text) + 1 : -1;
        if (text.length() == 2 && text.charAt(0) == '十') {
            int u = digits.indexOf(text.charAt(1)) + 1;
            return u > 0 ? 10 + u : -1;
        }
        return -1;
    }

    /**
     * 计算指定偏移年数的年范围
     */
    private static TimeRange yearRange(LocalDateTime now, int yearsOffset) {
        LocalDateTime targetYear = now.plusYears(yearsOffset);
        LocalDateTime startOfYear = targetYear.with(TemporalAdjusters.firstDayOfYear())
                .with(LocalTime.MIN);
        LocalDateTime endOfYear = targetYear.with(TemporalAdjusters.lastDayOfYear())
                .with(LocalTime.MAX);
        return TimeRange.of(startOfYear, endOfYear);
    }

    /**
     * 格式化时间范围为标准描述
     */
    private String formatTimeRange(TimeRange range) {
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        return String.format("[%s 至 %s]",
                range.getStart().format(formatter),
                range.getEnd().format(formatter));
    }

    // ==================== 结果类 ====================

    /**
     * 归一化结果
     */
    @Data
    @AllArgsConstructor
    public static class NormalizationResult {
        /** 处理后的文本 */
        private String processedText;
        /** 提取出的时间范围列表 */
        private java.util.List<TimeRange> extractedRanges;

        /**
         * 获取第一个时间范围（如果存在）
         */
        public Optional<TimeRange> getFirstRange() {
            return extractedRanges != null && !extractedRanges.isEmpty()
                    ? Optional.of(extractedRanges.get(0))
                    : Optional.empty();
        }

        /**
         * 是否提取到时间范围
         */
        public boolean hasTimeRange() {
            return extractedRanges != null && !extractedRanges.isEmpty();
        }
    }
}
