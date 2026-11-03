package com.cretas.aims.service.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 「词 + 具体数值」规则 (Steve 2026-10-29 选 A; 2026-11-03 Q2/Q3 + 安全复审 B1/N1) —— Java 侧唯一的一份。
 *
 * <p>与 Python {@code backend/python/common/training_values.py} 逐步相同 (两边跑同一份
 * {@code process_secret_filter_rule_cases.json}, Python 测试还逐字比对本类的正则);
 * 词表读同一个文件 {@code backend/python/common/training_rule_words.json} —— 构建时由 pom.xml
 * ({@code copy-training-rule-words}) 拷进 jar 的 {@code process-secret/training_rule_words.json}。
 *
 * <ul>
 *   <li>数值: 阿拉伯 / 全角数字 (贴着字母的也算: pH4、4.2pH、30min), 中文数字 (一百二十一、三十、三比一;
 *       两个字以上, 或后面跟单位; 「一下」「一起」「半成品」这类常用词不算);</li>
 *   <li>距离: 同一条记录里、相距不超过 {@value #WINDOW} 个字; 窗口里编号 / 日期 / 时刻都算数值, 只有每行开头的
 *       列表序号不算;</li>
 *   <li>记录: 一句话; 列表项连同缩进续行; 整张表 (表头列是工艺词、数据行同列有数值); 冒号结尾的工艺词标题行连同其下列表;</li>
 *   <li>条件词 (温度 / 时间 / 压力 / 转速 / 浓度): 后面 {@value #WINDOW} 个字内出现「数值 + 单位」才算。</li>
 * </ul>
 * 位置按 Unicode 码点计: 判断前把补充平面字符 (如 emoji) 换成一个 BMP 字符, UTF-16 下标就等于码点下标。
 */
public final class TrainingValueRules {

    static final int WINDOW = 12;
    static final String WORDS_RESOURCE = "process-secret/training_rule_words.json";

    static final JsonNode WORDS = loadWords();

    public static final List<String> CONDITIONAL_MARKERS = strings("conditional_markers", false);
    static final List<String> VALUE_UNITS = strings("value_units", true);
    static final List<String> CN_UNITS = strings("cn_units", true);
    static final List<String> CN_STOP_PHRASES = strings("cn_stop_phrases", true);
    static final List<String> GLUED_UNIT_PREFIXES = strings("glued_unit_prefixes", true);

    static final String CN = "零〇一二三四五六七八九十百千万两半";
    static final String CN_NUM = "[" + CN + "]+(?:点[" + CN + "]+)?";
    static final Pattern CN_RATIO = Pattern.compile(CN_NUM + "比" + CN_NUM);
    static final Pattern CN_NUM_PATTERN = Pattern.compile(CN_NUM);
    static final Pattern DIGIT_NUM = Pattern.compile("[0-9０-９]+(?:[.．][0-9０-９]+)?");
    static final Pattern LINE_ORDINAL = Pattern.compile(
            "^[ \\t\\u3000]*(?:#+[ \\t\\u3000]*)?(?:[-*•][ \\t\\u3000]*)?(?:\\*\\*)?(?:[0-9]{1,3}|[一二三四五六七八九十]{1,3})[.、)）](?![0-9])",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?；;]");
    static final Pattern LIST_LINE = Pattern.compile("^(?:[-*•]|[0-9]{1,3}[.、)）]|[一二三四五六七八九十]{1,3}、)");
    static final Pattern SEPARATOR_CELL = Pattern.compile("^[ \\t:：\\-—]*$");
    static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_.<])\\d+(?:\\.\\d+)?");
    static final Pattern GLUED = Pattern.compile("(?<![A-Za-z])("
            + GLUED_UNIT_PREFIXES.stream().map(Pattern::quote).collect(Collectors.joining("|"))
            + ")([0-9]+(?:\\.[0-9]+)?)");
    static final String SPACE = " \t　";

    private TrainingValueRules() {
    }

    private static JsonNode loadWords() {
        try (InputStream in = TrainingValueRules.class.getClassLoader().getResourceAsStream(WORDS_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(WORDS_RESOURCE + " missing from the classpath (pom copy-training-rule-words)");
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + WORDS_RESOURCE, e);
        }
    }

    static List<String> strings(String key, boolean longestFirst) {
        List<String> out = new ArrayList<>();
        WORDS.get(key).forEach(n -> out.add(n.asText()));
        if (longestFirst) {
            out.sort(Comparator.comparingInt(String::length).reversed());   // stable, like Python sorted()
        }
        return List.copyOf(out);
    }

    /** 补充平面字符换成一个 BMP 字符: 之后 UTF-16 下标 == 码点下标。 */
    static String bmp(String s) {
        if (s.codePointCount(0, s.length()) == s.length()) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        s.codePoints().forEach(cp -> sb.append(cp > 0xFFFF ? '�' : (char) cp));
        return sb.toString();
    }

    private static String blank(String s, int start, int end) {
        return s.substring(0, start) + " ".repeat(end - start) + s.substring(end);
    }

    static String prepared(String text) {
        String s = text;
        for (String phrase : CN_STOP_PHRASES) {
            int i = s.indexOf(phrase);
            while (i >= 0) {
                // 前面紧挨着中文数字时不是那个常用词 (「三十分钟」里的「十分」、「三万一」里的「万一」)
                if (i == 0 || CN.indexOf(s.charAt(i - 1)) < 0) {
                    s = blank(s, i, i + phrase.length());
                }
                i = s.indexOf(phrase, i + phrase.length());
            }
        }
        Matcher m = LINE_ORDINAL.matcher(s);
        List<int[]> ordinals = new ArrayList<>();
        while (m.find()) {
            ordinals.add(new int[]{m.start(), m.end()});
        }
        for (int[] o : ordinals) {
            s = blank(s, o[0], o[1]);
        }
        return s;
    }

    static boolean startsWithAny(String s, int at, Collection<String> words) {
        int j = at;
        while (j < s.length() && SPACE.indexOf(s.charAt(j)) >= 0) {
            j++;
        }
        for (String w : words) {
            if (s.startsWith(w, j)) {
                return true;
            }
        }
        return false;
    }

    /** 文本里的数值区间 [start, end) (下标即位置)。 */
    static List<int[]> valueSpans(String text, boolean cnOnly) {
        String s = prepared(text);
        TreeSet<int[]> spans = new TreeSet<>(Comparator.<int[]>comparingInt(a -> a[0]).thenComparingInt(a -> a[1]));
        if (!cnOnly) {
            Matcher d = DIGIT_NUM.matcher(s);
            while (d.find()) {
                spans.add(new int[]{d.start(), d.end()});
            }
        }
        List<int[]> ratio = new ArrayList<>();
        Matcher r = CN_RATIO.matcher(s);
        while (r.find()) {
            ratio.add(new int[]{r.start(), r.end()});
        }
        spans.addAll(ratio);
        Matcher c = CN_NUM_PATTERN.matcher(s);
        outer:
        while (c.find()) {
            for (int[] rr : ratio) {
                if (rr[0] <= c.start() && c.start() < rr[1]) {
                    continue outer;
                }
            }
            if (c.end() - c.start() >= 2 || startsWithAny(s, c.end(), CN_UNITS)) {
                spans.add(new int[]{c.start(), c.end()});
            }
        }
        return new ArrayList<>(spans);
    }

    static boolean windowHit(String s, Collection<String> markers, Collection<String> conditional) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        List<int[]> spans = null;
        for (String m : markers) {
            for (int i = s.indexOf(m); i >= 0; i = s.indexOf(m, i + 1)) {
                int j = i + m.length();
                spans = spans == null ? valueSpans(s, false) : spans;
                for (int[] sp : spans) {
                    if (sp[0] < j + WINDOW && sp[1] > i - WINDOW) {
                        return true;
                    }
                }
            }
        }
        for (String m : conditional) {
            for (int i = s.indexOf(m); i >= 0; i = s.indexOf(m, i + 1)) {
                int j = i + m.length();
                spans = spans == null ? valueSpans(s, false) : spans;
                for (int[] sp : spans) {
                    if (j <= sp[0] && sp[0] < j + WINDOW && startsWithAny(s, sp[1], VALUE_UNITS)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    static String strip(String s) {
        int a = 0;
        int b = s.length();
        while (a < b && SPACE.indexOf(s.charAt(a)) >= 0) {
            a++;
        }
        while (b > a && SPACE.indexOf(s.charAt(b - 1)) >= 0) {
            b--;
        }
        return s.substring(a, b);
    }

    private static boolean isTableLine(String line) {
        return strip(line).startsWith("|");
    }

    private static boolean isListOrIndented(String line) {
        if (line.isEmpty()) {
            return false;
        }
        return SPACE.indexOf(line.charAt(0)) >= 0 || LIST_LINE.matcher(strip(line)).find();
    }

    private static boolean isHeading(String line, Collection<String> words) {
        String t = strip(line);
        if (t.isEmpty()) {
            return false;
        }
        char last = t.charAt(t.length() - 1);
        if (last != '：' && last != ':') {
            return false;
        }
        for (String w : words) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }

    static List<String> cells(String line) {
        String t = strip(line);
        if (t.startsWith("|")) {
            t = t.substring(1);
        }
        if (t.endsWith("|")) {
            t = t.substring(0, t.length() - 1);
        }
        List<String> out = new ArrayList<>();
        for (String c : t.split(Pattern.quote("|"), -1)) {
            out.add(strip(c));
        }
        return out;
    }

    private static boolean tableHit(List<String> rows, Collection<String> markers, Collection<String> conditional) {
        for (String row : rows) {
            if (windowHit(row, markers, conditional)) {
                return true;
            }
        }
        List<String> header = cells(rows.get(0));
        List<List<String>> data = new ArrayList<>();
        for (String r : rows.subList(1, rows.size())) {
            List<String> cs = cells(r);
            if (!cs.stream().allMatch(c -> SEPARATOR_CELL.matcher(c).matches())) {
                data.add(cs);
            }
        }
        for (int k = 0; k < header.size(); k++) {
            String cell = header.get(k);
            boolean marked = markers.stream().anyMatch(cell::contains) || conditional.stream().anyMatch(cell::contains);
            if (!marked) {
                continue;
            }
            for (List<String> r : data) {
                if (k < r.size() && !valueSpans(r.get(k), false).isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 规则本体 (见类注释)。 */
    public static boolean hasWordWithValue(String text, Collection<String> markers, Collection<String> conditional) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        List<String> lines = Arrays.asList(bmp(text).split("\n", -1));
        List<String> headingWords = new ArrayList<>(markers);
        headingWords.addAll(conditional);
        int i = 0;
        while (i < lines.size()) {
            String line = lines.get(i);
            if (isTableLine(line)) {
                int j = i;
                while (j < lines.size() && isTableLine(lines.get(j))) {
                    j++;
                }
                if (tableHit(lines.subList(i, j), markers, conditional)) {
                    return true;
                }
                i = j;
                continue;
            }
            int j = i + 1;
            if (isHeading(line, headingWords)) {
                while (j < lines.size() && isListOrIndented(lines.get(j))) {
                    j++;
                }
                String body = String.join("\n", lines.subList(i + 1, j));
                if (!body.isEmpty() && !valueSpans(body, false).isEmpty()) {
                    return true;
                }
            } else {
                while (j < lines.size() && !lines.get(j).isEmpty() && SPACE.indexOf(lines.get(j).charAt(0)) >= 0) {
                    j++;
                }
            }
            String block = String.join("\n", lines.subList(i, j));
            for (String sentence : SENTENCE_SPLIT.split(block, -1)) {
                if (windowHit(sentence, markers, conditional)) {
                    return true;
                }
            }
            i = j;
        }
        return false;
    }

    /** 每个数值换成 {@code <数值>}: 阿拉伯数字 (F006 / V2 / B12 这类编号保留)、贴在单位后面的数字 (pH4)、中文数字。 */
    public static String maskValues(String text) {
        String out = GLUED.matcher(text).replaceAll(mr -> Matcher.quoteReplacement(mr.group(1) + "<数值>"));
        out = NUMBER.matcher(out).replaceAll("<数值>");
        List<int[]> spans = valueSpans(out, true);
        StringBuilder sb = new StringBuilder(out);
        for (int k = spans.size() - 1; k >= 0; k--) {
            sb.replace(spans.get(k)[0], spans.get(k)[1], "<数值>");
        }
        return sb.toString();
    }
}
