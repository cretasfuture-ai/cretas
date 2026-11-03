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
 *   <li>数值: 阿拉伯 / 全角数字 (贴着字母的也算: pH4、4.2pH、30min), 中文数字 (一百二十一、三十、三比一、万分之五,
 *       含大写 壹贰叁; 两个字以上, 或后面跟单位; 「一下」「一起」「半成品」这类常用词不算, 但它其实是数值的一部分时照算:
 *       「十分钟」「万分之五」);</li>
 *   <li>距离: 同一条记录里、相距不超过 {@value #WINDOW} 个字; 窗口里编号 / 日期 / 时刻都算数值, 只有每行开头的
 *       列表序号不算;</li>
 *   <li>记录: 一句话; 列表项连同缩进续行; 整张表 (表头列是工艺词、数据行同列有数值); 含工艺词的标题 (冒号结尾或
 *       Markdown {@code #} 标题) 连同其下内容 (紧跟的第一行、之后的列表 / 缩进行 / 冒号小标题, 可隔空行, 到下一个
 *       {@code #} 标题或下一段正文为止); 判断前统一换行并去掉 Markdown 加粗;</li>
 *   <li>条件词 (温度 / 时间 / 压力 / 转速 / 浓度): 后面 {@value #WINDOW} 个字内出现「数值 + 单位」才算。</li>
 * </ul>
 * 位置按 Unicode 码点计: 判断前把补充平面字符 (如 emoji) 换成一个 BMP 字符, UTF-16 下标就等于码点下标。
 */
public final class TrainingValueRules {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TrainingValueRules.class);
    static final int WINDOW = 12;
    static final String WORDS_RESOURCE = "process-secret/training_rule_words.json";

    /**
     * 词表。读不到 (jar 里缺了这个文件) 时为 null, ⛔ 不在类初始化时抛 —— 那会变成
     * {@code ExceptionInInitializerError}, 调用方的 {@code catch (RuntimeException)} 接不住。
     * 改为每个入口先 {@link #requireWords()}: 抛 {@link IllegalStateException}, 过滤器按「名单查不出来」挂起 / 不存。
     */
    static final JsonNode WORDS = loadWords(WORDS_RESOURCE);

    public static final List<String> CONDITIONAL_MARKERS = strings("conditional_markers", false);
    static final List<String> VALUE_UNITS = strings("value_units", true);
    static final List<String> CN_UNITS = strings("cn_units", true);
    static final List<String> CN_STOP_PHRASES = strings("cn_stop_phrases", true);
    static final List<String> GLUED_UNIT_PREFIXES = strings("glued_unit_prefixes", true);

    /** 中文数字, 含大写 (财务写法 壹贰叁 …, 复审第二轮 B1)。 */
    static final String CN = "零〇一二三四五六七八九十百千万两半壹贰叁肆伍陆柒捌玖拾佰仟萬貳參陸";
    static final String CN_NUM = "[" + CN + "]+(?:点[" + CN + "]+)?";
    static final Pattern CN_RATIO = Pattern.compile(CN_NUM + "比" + CN_NUM);
    /** 分数: 万分之五、百分之三 (自带单位: 条件词后面不必再跟单位)。 */
    static final Pattern CN_FRACTION = Pattern.compile(CN_NUM + "分之" + CN_NUM);
    static final Pattern CN_NUM_PATTERN = Pattern.compile(CN_NUM);
    static final Pattern DIGIT_NUM = Pattern.compile("[0-9０-９]+(?:[.．][0-9０-９]+)?");
    static final Pattern LINE_ORDINAL = Pattern.compile(
            "^[ \\t\\u3000]*(?:#+[ \\t\\u3000]*)?(?:[-*•][ \\t\\u3000]*)?(?:\\*\\*)?(?:[0-9]{1,3}|[一二三四五六七八九十]{1,3})[.、)）](?![0-9])",
            Pattern.MULTILINE | Pattern.UNIX_LINES);
    static final Pattern SENTENCE_SPLIT = Pattern.compile("[。！？!?；;]");
    static final Pattern LIST_LINE = Pattern.compile("^(?:[-*•]|[0-9]{1,3}[.、)）]|[一二三四五六七八九十]{1,3}、)");
    static final Pattern SEPARATOR_CELL = Pattern.compile("^[ \\t:：\\-—]*$");
    /** 全角数字也遮, 「<5」里的 5 也遮 (复审第二轮 B1); 编号 (F006 / Ｆ００６ / V2) 照旧保留。 */
    static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9Ａ-Ｚａ-ｚ０-９_.．])[0-9０-９]+(?:[.．][0-9０-９]+)?");
    static final Pattern EMPHASIS = Pattern.compile("\\*\\*|__");
    static final Pattern GLUED = Pattern.compile("(?<![A-Za-z])("
            + GLUED_UNIT_PREFIXES.stream().map(Pattern::quote).collect(Collectors.joining("|"))
            + ")([0-9]+(?:\\.[0-9]+)?)");
    static final String SPACE = " \t　";

    private TrainingValueRules() {
    }

    /** 读不到 / 读不了返回 null (只记一条 ERROR), 不抛。 */
    static JsonNode loadWords(String resource) {
        try (InputStream in = TrainingValueRules.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                LOG.error("{} missing from the classpath (pom copy-training-rule-words): training-sample rules fail closed",
                        resource);
                return null;
            }
            return new ObjectMapper().readTree(in);
        } catch (IOException | RuntimeException e) {
            LOG.error("cannot read {}: training-sample rules fail closed ({})", resource, e.getClass().getSimpleName());
            return null;
        }
    }

    /** 词表读不到时抛 {@link IllegalStateException} (运行时异常: 过滤器按名单查询失败处理, 挂起 / 不存)。 */
    static void requireWords() {
        requireWords(WORDS);
    }

    static void requireWords(JsonNode words) {
        if (words == null) {
            throw new IllegalStateException(WORDS_RESOURCE + " unavailable: training-sample rules cannot judge");
        }
    }

    /** 词表某一项; 词表读不到时返回空表 (各入口另有 {@link #requireWords()} 把关)。 */
    static List<String> strings(String key, boolean longestFirst) {
        List<String> out = new ArrayList<>();
        if (WORDS == null || WORDS.get(key) == null) {
            return List.of();
        }
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

    private static int leadingCn(String phrase) {
        int k = 0;
        while (k < phrase.length() && CN.indexOf(phrase.charAt(k)) >= 0) {
            k++;
        }
        return k;
    }

    /**
     * 常用词其实是数值的一部分时不抹掉 (复审第二轮 B1): 前面紧挨着中文数字 (「三十分钟」里的「十分」)、
     * 词头的数字后面跟着单位 (「十分钟」= 十 + 分钟)、或在分数里 (「万分之五」)。
     */
    private static boolean keepsStopPhrase(String s, int i, String phrase, List<int[]> fractions) {
        if (i > 0 && CN.indexOf(s.charAt(i - 1)) >= 0) {
            return true;
        }
        int k = leadingCn(phrase);
        if (k > 0 && startsWithAny(s, i + k, VALUE_UNITS)) {
            return true;
        }
        for (int[] f : fractions) {
            if (f[0] <= i && i < f[1]) {
                return true;
            }
        }
        return false;
    }

    static String prepared(String text) {
        String s = text;
        List<int[]> fractions = new ArrayList<>();
        Matcher fm = CN_FRACTION.matcher(text);
        while (fm.find()) {
            fractions.add(new int[]{fm.start(), fm.end()});
        }
        for (String phrase : CN_STOP_PHRASES) {
            int i = s.indexOf(phrase);
            while (i >= 0) {
                if (!keepsStopPhrase(s, i, phrase, fractions)) {
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
        Matcher fr = CN_FRACTION.matcher(s);
        while (fr.find()) {
            ratio.add(new int[]{fr.start(), fr.end()});
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
                    if (j <= sp[0] && sp[0] < j + WINDOW
                            && (startsWithAny(s, sp[1], VALUE_UNITS) || s.substring(sp[0], sp[1]).contains("分之"))) {
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

    /** 换行统一成 \n, 去掉 Markdown 加粗 ({@code **} / {@code __}) (复审第二轮 N1)。 */
    static String normalize(String text) {
        return EMPHASIS.matcher(text.replace("\r\n", "\n").replace("\r", "\n")).replaceAll("");
    }

    /** 标题行去掉 Markdown 的 # 之后的文字; [1] 为 "#" 表示是 Markdown 标题。 */
    private static String[] headingCore(String line) {
        String t = strip(line);
        if (t.startsWith("#")) {
            int k = 0;
            while (k < t.length() && t.charAt(k) == '#') {
                k++;
            }
            return new String[]{strip(t.substring(k)), "#"};
        }
        return new String[]{t, ""};
    }

    private static boolean endsWithColon(String t) {
        char last = t.charAt(t.length() - 1);
        return last == '：' || last == ':';
    }

    /** 含工艺词的标题: Markdown 标题 ({@code ### 配方}) 或冒号结尾的行。 */
    private static boolean isHeading(String line, Collection<String> words) {
        String[] core = headingCore(line);
        String t = core[0];
        if (t.isEmpty() || (core[1].isEmpty() && !endsWithColon(t))) {
            return false;
        }
        for (String w : words) {
            if (t.contains(w)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标题下内容的结尾 (不含) (复审第二轮 N1): 标题后第一行非空行一定算; 之后列表 / 缩进行 / 冒号小标题, 可隔空行;
     * 到下一个 Markdown 标题或下一段正文为止。
     */
    private static int headingEnd(List<String> lines, int i) {
        int j = i + 1;
        int end = j;
        boolean first = true;
        while (j < lines.size()) {
            String line = lines.get(j);
            String[] core = headingCore(line);
            if (core[0].isEmpty()) {
                j++;
                continue;
            }
            if (!core[1].isEmpty()) {
                break;
            }
            if (first || isListOrIndented(line) || endsWithColon(core[0])) {
                j++;
                end = j;
                first = false;
                continue;
            }
            break;
        }
        return end;
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
        requireWords();
        List<String> lines = Arrays.asList(normalize(bmp(text)).split("\n", -1));
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
                j = headingEnd(lines, i);
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
        requireWords();
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
