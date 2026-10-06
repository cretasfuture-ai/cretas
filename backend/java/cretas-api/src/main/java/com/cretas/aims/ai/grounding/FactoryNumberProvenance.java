package com.cretas.aims.ai.grounding;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工厂 AI 回答的数字出处闸 —— 模型写出来给用户看的每一个数, 都得能在产生这次回答的
 * 工具 / 技能产出里找到。找不到的数, 用户看不到。
 *
 * <h2>和 {@link GroundedNumberValidator} 的关系</h2>
 * 那一个是「行动建议」专用的<b>严格</b>闸: 提示词禁止编号、禁止派生, 所以可以不留任何例外,
 * 不通过就拒绝整段生成。本类服务的是<b>自由文本</b>的摘要 / 分析: 模型会写日期、列表序号、
 * 「12.3万」「85%」这类合法的改写, 严格闸会把正确答案判红, 所以这里的匹配规则多了下面
 * 四条形式改写 —— 且<b>只有这四条</b>。行动建议那条路仍走严格闸, 两者故意不合并
 * (合并会让严格闸被这四条放松)。
 *
 * <h2>不算「数字」的东西 (先遮掉再找数) —— 定义写死在这里, 不在调用方</h2>
 * <ol>
 *   <li><b>编码 / 标识符</b>: 字母打头且连着数字的串 ({@code F006}、{@code SL-CATTLE-005}、
 *       {@code MT-20260903-4578-WKS-03b44cd6}、{@code Q3}、{@code Top5});
 *       以及 11 位及以上的纯数字整数 (耳标、手机号、单号)。</li>
 *   <li><b>日期 / 时刻</b>: {@code 2026-10-03}、{@code 2026/10/03 08:00}、{@code 2026年10月}、
 *       {@code 10月3日}、{@code 9月}、{@code 2026-09}、{@code 08:30}。
 *       (Python 侧同名闸第一次校准时把日期切成三个"数字", 命中率中位数被压到 5.6% ——
 *       这一步就是那次的解药。)</li>
 *   <li><b>时间窗口与 TopN 口径</b>: {@code 近7天}、{@code 最近30天}、{@code 过去3个月}、
 *       {@code 7天内}、{@code 前5名}、{@code Top 10}。它们是查询口径, 不是查出来的数。</li>
 *   <li><b>列表序号</b>: 行首的 {@code 1.} / {@code 2、} / {@code (3)} / {@code ## 4.}、
 *       冒号后的行内编号 {@code 建议：1. …}、{@code 第2步 / 第3点 / 第1条}。
 *       {@code 1.5kg} 不是序号 (点后紧跟数字)。</li>
 * </ol>
 *
 * <h2>算「有出处」的四条形式改写 (除此之外一律不认)</h2>
 * <ol>
 *   <li><b>同值</b>: 数值相等 ({@code 38} = {@code 38.0} = {@code 38.00}), 千分位不影响。</li>
 *   <li><b>舍入</b>: 正文按它<b>写出来的小数位</b>对源数做四舍五入或截断后相等
 *       ({@code 12.3456 → 12.35 / 12.3 / 12})。容差由正文自己的精度决定, 不是全局常数。</li>
 *   <li><b>比率 ↔ 百分数</b>: 正文 {@code 85.3%} 认源里的 {@code 85.3} 或 {@code 0.853}。</li>
 *   <li><b>万 / 亿 / 千 量级</b>: 正文 {@code 12.35万} 认源里的 {@code 123456} (按 12.35 的精度舍入)
 *       或源里本来就写着的 {@code 12.35}。</li>
 * </ol>
 * 另有一条记号放宽: 正负号允许由文字表达 (源 {@code -12}, 正文「下降 12%」), 按绝对值比较。
 *
 * <h2>源数字池</h2>
 * 工具产出里的<b>所有</b>数: Number 值、字符串里的数 (同一套遮挡规则)、嵌套 Map/List 走到底,
 * 外加<b>每个列表的长度</b> —— 「共 7 个客户」的 7 就是工具产出那个列表的长度, 它在产出里,
 * 只是不以数字字面出现。
 *
 * <h2>⛔ 刻意不做: 算术派生</h2>
 * 不认 {@code a/b}、{@code a-b}、{@code a+b} 这类一层派生。Python 侧同名闸认, 但那边
 * 是只报不拦的仪器; 这里是放行门禁。源池动辄上百个数时, 两两四则再配上舍入容差,
 * 几乎任何数都「找得到出处」—— 那就是恒真式。代价是: 模型自己算出来的占比 / 合计会被判
 * 无出处 —— 那正是要拦的: 它算错了用户也看不出来。要让这类数出现, 让工具把它算出来。
 */
@Slf4j
@Component
public class FactoryNumberProvenance {

    /** 每次判定的结果。 */
    public enum Decision {
        /** 正文里没有需要核对的数。 */
        NO_NUMBERS,
        /** 全部有出处, 原样放行。 */
        PASS,
        /** 有无出处的数, 换成调用方给的确定性模板正文 (只含工具数)。 */
        FALLBACK_TEMPLATE,
        /** 有无出处的数且没有模板, 删掉含这些数的句子并加注说明。 */
        REDACTED
    }

    /** 一个被抽出来的数。{@code mantissa} 是正文写出来的那个数, {@code multiplier} 是 万/亿/千。 */
    public record NumberToken(String raw, BigDecimal mantissa, BigDecimal multiplier,
                              boolean percent, int start, int end) {
        public BigDecimal value() {
            return mantissa.multiply(multiplier);
        }
    }

    /** 一个数的判定: {@code rule == null} 表示无出处。 */
    public record Verdict(NumberToken token, String rule) {
        public boolean sourced() {
            return rule != null;
        }
    }

    /** 一次审计: 逐个数的判定 + 源池大小 (报告时连同分母一起报)。 */
    public record Audit(List<Verdict> verdicts, int sourcePoolSize) {
        public int counted() {
            return verdicts.size();
        }

        public List<Verdict> unsourced() {
            List<Verdict> out = new ArrayList<>();
            for (Verdict v : verdicts) {
                if (!v.sourced()) out.add(v);
            }
            return out;
        }

        /** 有出处的比例; 没有可判定的数时为 {@code null} (不是 0, 也不是 1)。 */
        public Double hitRate() {
            if (verdicts.isEmpty()) return null;
            return (double) (verdicts.size() - unsourced().size()) / verdicts.size();
        }
    }

    /** 一次放行判定的结果: 给用户看的文本 + 判定 + 审计明细。 */
    public record Result(String text, Decision decision, Audit audit) { }

    /** 删句后加在末尾的说明。 */
    static final String REDACTION_NOTE_TEMPLATE =
            "（注：上文有 %d 处数字未能与本次查询返回的数据核对一致，相关语句已省略。）";

    /** 删句后几乎什么都不剩时, 整段换成这句。 */
    static final String NOTHING_LEFT_MESSAGE =
            "本次 AI 生成的说明里有无法与系统数据核对的数字，已不予显示。请以系统查询结果或对应报表为准。";

    private static final int MIN_REMAINING_CHARS = 20;

    /** 11 位及以上的纯数字整数视为标识符 (耳标 15 位、手机号 11 位)。 */
    private static final int ID_DIGITS = 11;

    // ---- 遮挡规则 (顺序有意义: 编码先于日期, 日期先于窗口, 窗口先于序号) ----

    private static final Pattern CODE = Pattern.compile(
            "[A-Za-z][A-Za-z0-9]*(?:[-_/][A-Za-z0-9]+)+"      // SL-CATTLE-005, MT-2026...-03b44cd6
                    + "|[A-Za-z]+\\d[A-Za-z0-9]*");               // F006, Q3, Top5, B2026001

    private static final Pattern DATE_TIME = Pattern.compile(
            "\\d{4}\\s*[-/.]\\s*\\d{1,2}\\s*[-/.]\\s*\\d{1,2}(?:[T\\s]+\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?)?"
                    + "|\\d{4}\\s*年(?:\\s*\\d{1,2}\\s*月(?:\\s*\\d{1,2}\\s*[日号])?)?"
                    + "|\\d{1,2}\\s*月\\s*\\d{1,2}\\s*[日号]"
                    + "|\\d{1,2}\\s*月份?"
                    + "|\\d{4}\\s*[-/]\\s*\\d{1,2}(?![\\d.])"
                    + "|\\d{1,2}:\\d{2}(?::\\d{2})?"
                    + "|第\\s*\\d{1,2}\\s*(?:周|季度)");

    private static final Pattern WINDOW = Pattern.compile(
            "(?:近|最近|过去|未来|前|后)\\s*\\d{1,3}\\s*(?:个)?\\s*(?:自然|工作)?(?:天|日|周|星期|个月|月|年|小时|分钟)"
                    + "|\\d{1,3}\\s*(?:天|日|周|个月|小时|分钟)\\s*(?:内|以内)"
                    + "|(?:前|Top|TOP|top)\\s*\\d{1,3}\\s*(?:名|位|大)?");

    private static final Pattern ORDINAL = Pattern.compile(
            "(?m)^[ \\t]*(?:#{1,6}[ \\t]*)?(?:[-*+>][ \\t]*)?(?:\\*\\*)?[(（]?\\d{1,2}[.、)）](?!\\d)"
                    + "|[(（]\\d{1,2}[)）]"
                    + "|(?<=[：:；;。！!？?])[ \\t]*\\d{1,2}[.、](?!\\d)"
                    + "|第\\s*\\d{1,2}\\s*(?:步|点|条|项|部分|阶段|章|节|轮)");

    /** 数字本体: 可带千分位、小数、负号; 前面不能紧贴 ASCII 字母/数字/点 (那是编码或小数的一部分)。 */
    private static final Pattern NUMBER = Pattern.compile(
            "(?<![A-Za-z0-9_.])(-?)(\\d{1,3}(?:,\\d{3})+|\\d+)(\\.\\d+)?");

    /** 句子边界 (删句用)。 */
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?；;\\n]");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final ObjectMapper POJO_MAPPER = new ObjectMapper().findAndRegisterModules();

    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ==================== 放行判定 ====================

    /**
     * 判定并给出用户能看到的文本。
     *
     * @param site                  调用点名 (进日志与计数器, 用来分辨是哪一处在换文)
     * @param answer                模型写的正文
     * @param deterministicFallback 只由工具产出拼出来的确定性正文; 没有就传 {@code null}
     * @param sources               产生这次回答的工具 / 技能产出 (以及喂给模型的事实文本)
     */
    public Result enforce(String site, String answer, Supplier<String> deterministicFallback,
                          Object... sources) {
        if (answer == null || answer.isBlank()) {
            return record(site, new Result(answer, Decision.NO_NUMBERS, new Audit(List.of(), 0)));
        }
        Audit audit = audit(answer, sources);
        if (audit.counted() == 0) {
            return record(site, new Result(answer, Decision.NO_NUMBERS, audit));
        }
        List<Verdict> unsourced = audit.unsourced();
        if (unsourced.isEmpty()) {
            return record(site, new Result(answer, Decision.PASS, audit));
        }
        String fallback = deterministicFallback != null ? safeGet(deterministicFallback) : null;
        if (fallback != null && !fallback.isBlank()) {
            return record(site, new Result(fallback, Decision.FALLBACK_TEMPLATE, audit));
        }
        return record(site, new Result(redact(answer, unsourced), Decision.REDACTED, audit));
    }

    /** {@link #enforce(String, String, Supplier, Object...)} 的无模板版本。 */
    public String enforceText(String site, String answer, Object... sources) {
        return enforce(site, answer, null, sources).text();
    }

    /** 某个调用点某种判定的累计次数 (进程内)。 */
    public long count(String site, Decision decision) {
        AtomicLong c = counts.get(site + "|" + decision.name());
        return c != null ? c.get() : 0L;
    }

    /** 某个调用点的累计判定次数 —— 每次 {@code enforce} 必加一, 用作「它确实跑了」的对照。 */
    public long total(String site) {
        long sum = 0;
        for (Decision d : Decision.values()) sum += count(site, d);
        return sum;
    }

    // ==================== 审计 (仪器) ====================

    /** 只审计不改文: 逐个数给出判定。 */
    public Audit audit(String answer, Object... sources) {
        Set<BigDecimal> pool = sourceNumbers(sources);
        List<Verdict> verdicts = new ArrayList<>();
        for (NumberToken t : extract(answer)) {
            verdicts.add(new Verdict(t, match(t, pool)));
        }
        return new Audit(Collections.unmodifiableList(verdicts), pool.size());
    }

    /** 正文里要核对的数 (已剔除编码 / 日期 / 窗口 / 序号 / 长标识符)。 */
    public List<NumberToken> extract(String text) {
        List<NumberToken> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;
        String masked = mask(text);
        Matcher m = NUMBER.matcher(masked);
        while (m.find()) {
            String intPart = m.group(2);
            String frac = m.group(3);
            boolean negative = !m.group(1).isEmpty();
            // 「50-60」里的 60: 负号前紧贴数字, 是区间分隔符不是负号
            if (negative && m.start() > 0 && Character.isDigit(masked.charAt(m.start() - 1))) {
                negative = false;
            }
            String digits = intPart.replace(",", "");
            if (frac == null && !intPart.contains(",") && digits.length() >= ID_DIGITS) {
                continue; // 长纯数字 = 标识符
            }
            BigDecimal mantissa;
            try {
                mantissa = new BigDecimal(digits + (frac != null ? frac : ""));
            } catch (NumberFormatException e) {
                continue;
            }
            if (negative) mantissa = mantissa.negate();
            int end = m.end();
            int k = end;
            while (k < masked.length() && (masked.charAt(k) == ' ' || masked.charAt(k) == '\t')) k++;
            boolean percent = false;
            BigDecimal multiplier = BigDecimal.ONE;
            if (k < masked.length()) {
                char c = masked.charAt(k);
                if (c == '%' || c == '％') {
                    percent = true;
                    end = k + 1;
                } else if (c == '万') {
                    multiplier = BigDecimal.valueOf(10_000);
                    end = k + 1;
                } else if (c == '亿') {
                    multiplier = BigDecimal.valueOf(100_000_000);
                    end = k + 1;
                } else if (c == '千' && !followedByMeasureUnit(masked, k + 1)) {
                    multiplier = BigDecimal.valueOf(1_000);
                    end = k + 1;
                }
            }
            out.add(new NumberToken(text.substring(m.start(), end), mantissa, multiplier,
                    percent, m.start(), end));
        }
        return out;
    }

    /** 工具产出里所有的数, 外加每个列表的长度。 */
    public Set<BigDecimal> sourceNumbers(Object... sources) {
        Set<BigDecimal> pool = new HashSet<>();
        if (sources != null) {
            for (Object s : sources) walk(s, pool, 0);
        }
        return pool;
    }

    // ==================== 内部 ====================

    static String mask(String text) {
        StringBuilder sb = new StringBuilder(text);
        for (Pattern p : new Pattern[]{CODE, DATE_TIME, WINDOW, ORDINAL}) {
            Matcher m = p.matcher(sb);
            List<int[]> spans = new ArrayList<>();
            while (m.find()) spans.add(new int[]{m.start(), m.end()});
            for (int[] span : spans) {
                for (int i = span[0]; i < span[1]; i++) {
                    char c = sb.charAt(i);
                    if (c != '\n') sb.setCharAt(i, ' ');
                }
            }
        }
        return sb.toString();
    }

    private static boolean followedByMeasureUnit(String s, int i) {
        return i < s.length() && "克米瓦卡帕伏焦".indexOf(s.charAt(i)) >= 0;
    }

    /** 四条形式改写 + 记号放宽; 返回命中的规则名, 无出处返回 {@code null}。 */
    static String match(NumberToken t, Set<BigDecimal> pool) {
        BigDecimal value = t.value();
        int scale = Math.max(0, t.mantissa().stripTrailingZeros().scale());
        scale = Math.max(scale, t.mantissa().scale());
        boolean scaled = t.multiplier().compareTo(BigDecimal.ONE) != 0;
        for (BigDecimal s : pool) {
            if (s.compareTo(value) == 0) return "same-value";
        }
        for (BigDecimal s : pool) {
            if (roundsTo(s.divide(t.multiplier()), t.mantissa(), scale)) {
                return scaled ? "unit-scaled" : "rounding";
            }
            if (scaled && roundsTo(s, t.mantissa(), scale)) return "unit-scaled";
            if (t.percent() && roundsTo(s.multiply(HUNDRED), t.mantissa(), scale)) return "ratio-percent";
        }
        for (BigDecimal s : pool) {
            BigDecimal abs = s.abs();
            BigDecimal mAbs = t.mantissa().abs();
            if (roundsTo(abs.divide(t.multiplier()), mAbs, scale)
                    || (t.percent() && roundsTo(abs.multiply(HUNDRED), mAbs, scale))
                    || (scaled && roundsTo(abs, mAbs, scale))) {
                return "sign-in-words";
            }
        }
        return null;
    }

    private static boolean roundsTo(BigDecimal source, BigDecimal shown, int scale) {
        return source.setScale(scale, RoundingMode.HALF_UP).compareTo(shown) == 0
                || source.setScale(scale, RoundingMode.DOWN).compareTo(shown) == 0;
    }

    @SuppressWarnings("unchecked")
    private void walk(Object o, Set<BigDecimal> pool, int depth) {
        if (o == null || depth > 40) return;
        if (o instanceof Boolean || o instanceof Temporal || o instanceof Date || o instanceof Enum<?>) return;
        if (o instanceof BigDecimal bd) {
            pool.add(bd);
            return;
        }
        if (o instanceof Number n) {
            try {
                pool.add(new BigDecimal(n.toString()));
            } catch (NumberFormatException ignored) {
                // NaN / Infinity
            }
            return;
        }
        if (o instanceof CharSequence cs) {
            for (NumberToken t : extract(cs.toString())) {
                pool.add(t.value());
                if (t.multiplier().compareTo(BigDecimal.ONE) != 0) pool.add(t.mantissa());
                // 源里写「85%」, 正文写「0.85」: 同一个比率
                if (t.percent()) pool.add(t.value().divide(HUNDRED));
            }
            return;
        }
        if (o instanceof Map<?, ?> map) {
            for (Object v : map.values()) walk(v, pool, depth + 1);
            return;
        }
        if (o instanceof Collection<?> c) {
            pool.add(BigDecimal.valueOf(c.size()));
            for (Object v : c) walk(v, pool, depth + 1);
            return;
        }
        if (o.getClass().isArray()) {
            int len = Array.getLength(o);
            pool.add(BigDecimal.valueOf(len));
            for (int i = 0; i < len; i++) walk(Array.get(o, i), pool, depth + 1);
            return;
        }
        try {
            walk(POJO_MAPPER.convertValue(o, Map.class), pool, depth + 1);
        } catch (IllegalArgumentException e) {
            walk(o.toString(), pool, depth + 1);
        }
    }

    /** 删掉含无出处数字的句子 (以句末标点 / 换行为界), 末尾加说明。 */
    static String redact(String answer, List<Verdict> unsourced) {
        List<int[]> sentences = new ArrayList<>();
        int start = 0;
        Matcher m = SENTENCE_END.matcher(answer);
        while (m.find()) {
            sentences.add(new int[]{start, m.end()});
            start = m.end();
        }
        if (start < answer.length()) sentences.add(new int[]{start, answer.length()});

        StringBuilder kept = new StringBuilder();
        for (int[] s : sentences) {
            boolean dirty = false;
            for (Verdict v : unsourced) {
                if (v.token().start() >= s[0] && v.token().start() < s[1]) {
                    dirty = true;
                    break;
                }
            }
            if (!dirty) {
                kept.append(answer, s[0], s[1]);
            } else if (answer.charAt(s[1] - 1) == '\n') {
                kept.append('\n'); // 保留行结构
            }
        }
        String body = kept.toString().replaceAll("\\n{3,}", "\n\n").strip();
        String meaningful = body.replaceAll("[\\s#*\\-_>|:：。，,、]", "");
        if (meaningful.length() < MIN_REMAINING_CHARS) {
            return NOTHING_LEFT_MESSAGE;
        }
        return body + "\n\n" + String.format(REDACTION_NOTE_TEMPLATE, unsourced.size());
    }

    private Result record(String site, Result r) {
        counts.computeIfAbsent(site + "|" + r.decision().name(), k -> new AtomicLong()).incrementAndGet();
        if (meterRegistry != null) {
            try {
                meterRegistry.counter("cretas.ai.number_provenance",
                        "site", site, "decision", r.decision().name()).increment();
            } catch (RuntimeException ignored) {
                // 计数失败不影响放行判定
            }
        }
        List<String> bad = new ArrayList<>();
        for (Verdict v : r.audit().unsourced()) {
            if (bad.size() >= 8) break;
            bad.add(v.token().raw());
        }
        if (r.decision() == Decision.PASS || r.decision() == Decision.NO_NUMBERS) {
            log.info("[NumberProvenance] site={} decision={} counted={} unsourced=0 pool={}",
                    site, r.decision(), r.audit().counted(), r.audit().sourcePoolSize());
        } else {
            log.warn("[NumberProvenance] site={} decision={} counted={} unsourced={} pool={} values={}",
                    site, r.decision(), r.audit().counted(), r.audit().unsourced().size(),
                    r.audit().sourcePoolSize(), bad);
        }
        return r;
    }

    private static String safeGet(Supplier<String> s) {
        try {
            return s.get();
        } catch (RuntimeException e) {
            log.warn("[NumberProvenance] deterministic fallback failed: {}", e.getMessage());
            return null;
        }
    }
}
