package com.cretas.aims.service.execution;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 抽出来的参数值, 只有「落了地」的才收 —— ToolDispatchService 里**每一条** LLM 产参路径
 * (抽参 {@code extractParametersWithLLM} 的执行 / 预览两个调用点, 以及 CRITIC 纠错的两处重试)
 * 都经过这里, 规则只有这一份。
 *
 * <p><b>为什么</b> (2026-10-06 prod F006 工序任务 1827): 用户只说了「1827」, LLM 抽参把必填的
 * 实际产量填成 0, 还在「备注」里写了它自己的话「用户仅提供了数字1827，未明确实际产量，需补充确认」,
 * 确认后两样都进了库。#929 之后 0 是合法产量, 「不知道」于是安静地变成一个合法的 0。
 *
 * <p><b>规则 (两条, 都很窄)</b>:
 * <ol>
 *   <li><b>数字</b>: schema 类型是 integer/number 的参数, 或值本身是数字的参数 —— 这个数必须在
 *       用户这句话里出现过 (允许「十公斤」=10、「零」/0、小数、带单位), 或者是请求上下文
 *       (按钮带来的参数 / lastAction / 填槽答案) 里的一个数值。<b>一处出现只能给一个参数作证</b>:
 *       「1827」当了任务编号, 就不能再当产量。缺参的参数先认领 (按 schema required 顺序)。
 *       与该参数已有值相等时视为没改, 直接收。</li>
 *   <li><b>自由文本</b> (备注 / 原因 / 说明 / 描述 …, 按参数名判定, 见 {@link #isFreeTextParam};
 *       带 enum 的不算): 只收用户原话 (或上下文里的字符串) 的子串 —— 忽略空白与标点;
 *       纯数字不算备注。</li>
 * </ol>
 * 没落地的参数被丢掉 (不是改成别的值), 于是它保持「缺」—— 必填的会由工具走正常的
 * NEED_MORE_INFO 追问。
 *
 * <p>⚠️ 已知不覆盖: 「没做出来 / 一个都没有」这种语义上的 0 不算出处 (会追问一次);
 * 字符串型的非数字参数 (编号、状态、日期) 不在本规则内。
 *
 * <p>⚠️ 自由文本按<b>参数名</b>判定是代理判据: 本仓 Tool schema 没有「这是自由文本」的声明位。
 * 名字表只有一处 ({@link #FREE_TEXT_NAME_SUFFIXES}), 要扩就扩这里。
 */
public final class LlmParameterGrounding {

    private LlmParameterGrounding() {
    }

    /** accepted: 收下的参数 (数字已规整成 Number); dropped: 丢掉的参数名 → 原因 (不含值, 可直接进日志)。 */
    public record Result(Map<String, Object> accepted, Map<String, String> dropped) { }

    /** 参数名 (小写) 以这些结尾就算自由文本: notes / rejectionReason / ackRemark / description …… */
    public static final List<String> FREE_TEXT_NAME_SUFFIXES = List.of(
            "note", "notes", "remark", "remarks", "comment", "comments",
            "reason", "reasondetail", "memo", "description", "feedback");

    /** 不进出处池的上下文键: 主体身份、原话本身 (单独按文本处理)、内部元数据。 */
    private static final Set<String> NON_EVIDENCE_KEYS = Set.of(
            "factoryId", "factory_id", "tenantId", "tenant_id",
            "userId", "user_id", "actorUserId", "actor_user_id",
            "userRole", "user_role", "role",
            "userInput", "intentCode", "sessionId");

    private static final int MAX_CONTEXT_DEPTH = 4;

    // 阿拉伯数字: 前面不能紧挨字母/数字/小数点 (排除 B001、eb0aa47b 这类编号里的数字)
    private static final Pattern ARABIC = Pattern.compile("(?<![A-Za-z0-9_.])-?\\d+(?:\\.\\d+)?(?![0-9_]|\\.\\d)");
    private static final Pattern CHINESE = Pattern.compile(
            "[零〇一二两三四五六七八九十百千万]+(?:点[零〇一二三四五六七八九]+)?");
    /** 单独一个「零」只有后面跟单位或不是汉字时才算 0 —— 「零件 / 零售 / 零下」不是数。 */
    private static final Pattern ZERO_UNIT_AFTER = Pattern.compile(
            "^(?:公斤|千克|斤|克|吨|箱|袋|个|包|桶|瓶|盒|份|筐|盘|块|只|条|张|台|次|分|元)");
    private static final Pattern WHOLE_ARABIC_VALUE = Pattern.compile(
            "^-?\\d+(?:\\.\\d+)?\\s*[A-Za-z\\u4e00-\\u9fa5]{0,4}$");
    private static final Pattern WHOLE_CHINESE_VALUE = Pattern.compile(
            "^([零〇一二两三四五六七八九十百千万]+(?:点[零〇一二三四五六七八九]+)?)\\s*[\\u4e00-\\u9fa5A-Za-z]{0,4}$");
    private static final Pattern PURE_ARABIC = Pattern.compile("^-?\\d+(?:\\.\\d+)?$");
    private static final Pattern NOT_TEXT = Pattern.compile("[\\s\\p{P}\\p{S}\\u3000]+");
    private static final Pattern HAS_WORD = Pattern.compile("[A-Za-z\\u4e00-\\u9fa5]");

    /**
     * @param llmValues      LLM 给出的参数 (原样)
     * @param existingParams 抽参前已有的参数 —— 已含请求上下文 (按钮参数 / lastAction / 填槽答案)
     * @param userInput      用户这一轮说的话 (LLM 看到的那句)
     * @param parametersSchema 工具的 JSON schema (取 properties 的 type / enum 与 required 顺序)
     */
    @SuppressWarnings("unchecked")
    public static Result ground(Map<String, Object> llmValues, Map<String, Object> existingParams,
                         String userInput, Map<String, Object> parametersSchema) {
        Map<String, Object> accepted = new LinkedHashMap<>();
        Map<String, String> dropped = new LinkedHashMap<>();
        if (llmValues == null || llmValues.isEmpty()) {
            return new Result(accepted, dropped);
        }
        Map<String, Object> existing = existingParams != null ? existingParams : Map.of();
        Map<String, Object> properties = parametersSchema != null
                && parametersSchema.get("properties") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : Map.of();
        List<String> required = parametersSchema != null
                && parametersSchema.get("required") instanceof List<?> r
                ? (List<String>) r : List.of();

        List<Evidence> pool = numberMentions(userInput);
        collectContextNumbers(existing, properties.keySet(), pool, 0, true);
        String textCorpus = normalizeText(userInput) + "\u0000" + contextText(existing);

        for (String key : groundingOrder(llmValues.keySet(), existing, required)) {
            Object value = llmValues.get(key);
            Map<String, Object> prop = properties.get(key) instanceof Map<?, ?> m
                    ? (Map<String, Object>) m : Map.of();
            String type = prop.get("type") instanceof String t ? t.toLowerCase(Locale.ROOT) : null;
            boolean hasEnum = prop.get("enum") instanceof Collection<?>;
            boolean numericType = "integer".equals(type) || "number".equals(type);

            // Relative periods belong to the tool's calendar logic, not invented LLM dates.
            if ("date".equals(prop.get("format")) && value instanceof String date
                    && !date.equals(existing.get(key))
                    && !dateMentioned(userInput, date)) {
                dropped.put(key, "日期不在用户原话中");
                continue;
            }

            if (value == null || value instanceof Boolean || value instanceof Map || value instanceof Collection) {
                accepted.put(key, value);
                continue;
            }
            if (!numericType && !hasEnum && isFreeTextParam(key)
                    && (value instanceof String || value instanceof Number)) {
                String text = String.valueOf(value);
                String norm = normalizeText(text);
                if (norm.isEmpty() || value.equals(existing.get(key))) {
                    accepted.put(key, value);
                } else if (!HAS_WORD.matcher(norm).find()) {
                    dropped.put(key, "纯数字不是备注");
                } else if (!textCorpus.contains(norm)) {
                    dropped.put(key, "不是用户原话");
                } else {
                    accepted.put(key, value);
                }
                continue;
            }
            BigDecimal number = numericType ? parseWholeNumber(value) : parseStrictNumber(value);
            if (numericType && number == null) {
                dropped.put(key, "不是数字");
                continue;
            }
            if (number != null) {
                BigDecimal current = LastActionContract.toDecimal(existing.get(key));
                if (current != null && current.compareTo(number) == 0) {
                    accepted.put(key, existing.get(key));       // 与已有值相同 = 没改
                } else if (claim(pool, number)) {
                    accepted.put(key, numericType && value instanceof String
                            ? LastActionContract.normalize(number) : value);
                } else {
                    dropped.put(key, "数字 " + number.toPlainString() + " 不在用户原话或上下文里");
                }
                continue;
            }
            accepted.put(key, value);
        }
        return new Result(accepted, dropped);
    }

    /** 参数名是否算自由文本业务字段 (备注 / 原因 / 说明 / 描述 …)。 */
    public static boolean isFreeTextParam(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String suffix : FREE_TEXT_NAME_SUFFIXES) {
            if (lower.endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    /** 用户这句话里出现过的每一个数 (按出现次数, 一处一条)。 */
    static List<Evidence> numberMentions(String text) {
        List<Evidence> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String t = halfWidth(text);
        Matcher a = ARABIC.matcher(t);
        while (a.find()) {
            BigDecimal v = LastActionContract.toDecimal(a.group());
            if (v != null) {
                out.add(new Evidence(v));
            }
        }
        Matcher c = CHINESE.matcher(t);
        while (c.find()) {
            String run = c.group();
            if (run.equals("零") || run.equals("〇")) {
                String after = t.substring(c.end());
                boolean standalone = after.isEmpty()
                        || !Character.isIdeographic(after.codePointAt(0))
                        || ZERO_UNIT_AFTER.matcher(after).find();
                if (standalone) {
                    out.add(new Evidence(BigDecimal.ZERO));
                }
                continue;
            }
            BigDecimal v = parseChineseNumber(run);
            if (v != null) {
                out.add(new Evidence(v));
            }
        }
        return out;
    }

    /** 「十」=10、「一百五」=150、「三点五」=3.5、「两万」=20000; 不合规返回 null。 */
    static BigDecimal parseChineseNumber(String run) {
        if (run == null || run.isEmpty()) {
            return null;
        }
        String intPart = run;
        String fraction = null;
        int dot = run.indexOf('点');
        if (dot >= 0) {
            intPart = run.substring(0, dot);
            fraction = run.substring(dot + 1);
        }
        Long whole;
        if (intPart.isEmpty()) {
            return null;
        } else if (intPart.equals("零") || intPart.equals("〇")) {
            whole = 0L;
        } else {
            int wan = intPart.indexOf('万');
            if (wan >= 0) {
                Long high = wan == 0 ? null : FollowUpResolver.parseChinese(intPart.substring(0, wan));
                String rest = intPart.substring(wan + 1);
                Long low = rest.isEmpty() ? Long.valueOf(0L) : FollowUpResolver.parseChinese(rest);
                whole = high == null || low == null ? null : high * 10_000 + low;
            } else {
                whole = FollowUpResolver.parseChinese(intPart);
            }
        }
        if (whole == null) {
            return null;
        }
        if (fraction == null) {
            return BigDecimal.valueOf(whole);
        }
        StringBuilder digits = new StringBuilder();
        for (char ch : fraction.toCharArray()) {
            int d = ch == '〇' ? 0 : "零一二三四五六七八九".indexOf(ch);
            if (d < 0) {
                return null;
            }
            digits.append(d);
        }
        return new BigDecimal(whole + "." + digits);
    }

    // ------------------------------------------------------------------

    record Evidence(BigDecimal value, boolean[] used) {
        Evidence(BigDecimal value) {
            this(value, new boolean[1]);
        }
    }

    private static boolean claim(List<Evidence> pool, BigDecimal number) {
        for (Evidence e : pool) {
            if (!e.used()[0] && e.value().compareTo(number) == 0) {
                e.used()[0] = true;
                return true;
            }
        }
        return false;
    }

    /** 缺参的先认领 (required 顺序在前), 再轮到覆盖已有值的; 同档按名字排, 结果稳定。 */
    private static List<String> groundingOrder(Collection<String> keys, Map<String, Object> existing,
                                               List<String> required) {
        List<String> ordered = new ArrayList<>(keys);
        ordered.sort(Comparator
                .comparing((String k) -> isMissing(existing.get(k)) ? 0 : 1)
                .thenComparing(k -> required.contains(k) ? required.indexOf(k) : Integer.MAX_VALUE)
                .thenComparing(k -> k));
        return ordered;
    }

    private static boolean isMissing(Object v) {
        return v == null || (v instanceof String s && s.isBlank());
    }

    /** 上下文里的数值才算出处 (整值是数字的字符串也算); 顶层是本工具参数的键只给它自己作证。 */
    private static void collectContextNumbers(Object node, Set<String> ownParamKeys, List<Evidence> pool,
                                              int depth, boolean topLevel) {
        if (depth > MAX_CONTEXT_DEPTH || node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (NON_EVIDENCE_KEYS.contains(k) || k.startsWith("_") || (topLevel && ownParamKeys.contains(k))) {
                    continue;
                }
                collectContextNumbers(e.getValue(), ownParamKeys, pool, depth + 1, false);
            }
        } else if (node instanceof Collection<?> list) {
            for (Object item : list) {
                collectContextNumbers(item, ownParamKeys, pool, depth + 1, false);
            }
        } else {
            BigDecimal v = parseStrictNumber(node);
            if (v != null) {
                pool.add(new Evidence(v));
            }
        }
    }

    private static String contextText(Map<String, Object> existing) {
        StringBuilder sb = new StringBuilder();
        appendText(existing, sb, 0);
        return sb.toString();
    }

    private static void appendText(Object node, StringBuilder sb, int depth) {
        if (depth > MAX_CONTEXT_DEPTH || node == null) {
            return;
        }
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!NON_EVIDENCE_KEYS.contains(k) && !k.startsWith("_")) {
                    appendText(e.getValue(), sb, depth + 1);
                }
            }
        } else if (node instanceof Collection<?> list) {
            for (Object item : list) {
                appendText(item, sb, depth + 1);
            }
        } else if (node instanceof String s) {
            sb.append(normalizeText(s)).append('\u0000');
        }
    }

    /** Number 或整串就是一个阿拉伯数字的字符串 → 数值; 其它 → null (编号、日期、文字不当数)。 */
    private static BigDecimal parseStrictNumber(Object value) {
        if (value instanceof Number n) {
            return LastActionContract.toDecimal(n);
        }
        if (value instanceof String s) {
            String t = halfWidth(s.trim());
            return PURE_ARABIC.matcher(t).matches() ? LastActionContract.toDecimal(t) : null;
        }
        return null;
    }

    /** 数值型参数的值: Number, 或「10」「10公斤」「十」「三点五斤」这样整串是一个数 (可带单位)。 */
    private static BigDecimal parseWholeNumber(Object value) {
        if (value instanceof Number n) {
            return LastActionContract.toDecimal(n);
        }
        if (!(value instanceof String s)) {
            return null;
        }
        String t = halfWidth(s.trim());
        if (WHOLE_ARABIC_VALUE.matcher(t).matches()) {
            Matcher m = Pattern.compile("^-?\\d+(?:\\.\\d+)?").matcher(t);
            return m.find() ? LastActionContract.toDecimal(m.group()) : null;
        }
        Matcher cn = WHOLE_CHINESE_VALUE.matcher(t);
        return cn.matches() ? parseChineseNumber(cn.group(1)) : null;
    }

    private static String halfWidth(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '０' && ch <= '９') {
                sb.append((char) (ch - '０' + '0'));
            } else if (ch == '．') {
                sb.append('.');
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    private static boolean dateMentioned(String input, String date) {
        if (input == null) return false;
        if (input.contains(date)) return true;
        Matcher explicit = Pattern.compile("(\\d{4})年(\\d{1,2})月(\\d{1,2})日(?:\\s*(?:至|到)\\s*(\\d{1,2})日)?").matcher(input);
        while (explicit.find()) {
            try {
                int year = Integer.parseInt(explicit.group(1));
                int month = Integer.parseInt(explicit.group(2));
                if (java.time.LocalDate.of(year, month, Integer.parseInt(explicit.group(3))).toString().equals(date)) return true;
                if (explicit.group(4) != null
                        && java.time.LocalDate.of(year, month, Integer.parseInt(explicit.group(4))).toString().equals(date)) return true;
            } catch (java.time.DateTimeException ignored) {
                // An invalid explicit date is not evidence for a different date.
            }
        }
        return false;
    }

    private static String normalizeText(String s) {
        return s == null ? "" : NOT_TEXT.matcher(s).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
