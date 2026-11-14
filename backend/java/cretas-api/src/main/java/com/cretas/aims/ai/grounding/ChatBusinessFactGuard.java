package com.cretas.aims.ai.grounding;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 闲聊 / 兜底回复的业务事实护栏 —— 两个对话入口 (普通 {@code IntentExecutionOrchestrator} 与流式
 * {@code SseStreamingService}) 共用这一份, 不各写一套。
 *
 * <p>触发事故 (2026-11-14 F006 viewer, 普通入口): 「这个月卖得怎么样」没命中任何意图, 早期问题类型判成
 * GENERAL_QUESTION, 掉进闲聊; 闲聊提示词里没有任何数据, 本地 qwen3:8b (Python llm_router chat 槽)
 * 编了「同比增长 8%」。同一天另一句识别不出时, 同一个提示词「你是白垩纪AI Agent的智能助手」让模型
 * 自称「专注古生物学与地球历史」。
 *
 * <p>三件事, 都在这里定义:
 * <ol>
 *   <li><b>识别层</b> {@link #isBusinessQuestion}: 业务问法 (销售 / 产量 / 库存 / 成本 / 质检 ... + 问情况 / 多少)
 *       不许进纯闲聊, 调用方改走意图识别 (识别不出就是澄清候选)。</li>
 *   <li><b>提示词</b> {@link #PERSONA} / {@link #NO_DATA_RULES}: 系统身份是食品工厂助手; 没有数据的闲聊
 *       明确禁止业务数字与业务事实。</li>
 *   <li><b>输出闸</b> {@link #check}: 本轮没有工具查询结果支撑的回复里出现业务数字 / 金额 / 百分比 /
 *       业务趋势断言 ⇒ 不下发, 换成 {@link #NEED_QUERY_REPLY}, 并计数 (进程内 + Micrometer
 *       {@code cretas.ai.chat_business_fact_guard{site,decision}}) 与告警日志, 不静默。</li>
 * </ol>
 *
 * <p>数字的定义沿用 {@link FactoryNumberProvenance#extract}: 日期、编码、手机号 / 单号 (11 位及以上)、
 * 「近7天」这类口径、列表序号都不算数。闲聊里的常识数字 (「冷藏 0-4℃」) 不是业务数字, 照常放行 ——
 * 只有挨着金额单位、业务数量单位 + 业务主语, 或在业务指标句里的百分比才算。
 */
@Slf4j
@Component
public class ChatBusinessFactGuard {

    /** 系统身份 (所有闲聊 / 咨询提示词的第一句, 两个入口同一份)。 */
    public static final String PERSONA =
            "你是「白垩纪」食品工厂管理系统里的工厂助手，服务食品加工厂的老板、管理人员和一线员工。"
                    + "「白垩纪」只是本系统的品牌名，与古生物学、恐龙、地质年代没有任何关系，不要介绍或谈论这些。";

    /** 没有任何业务数据进模型时追加的硬规则。 */
    public static final String NO_DATA_RULES =
            "硬性规则：你看不到本工厂的任何实时业务数据。"
                    + "不得给出任何业务数字或业务事实（销售额、销量、营收、利润、成本、产量、出货量、库存、订单数、"
                    + "合格率、同比、环比、排名等），也不得说某项业务在增长、下降、良好或不佳。"
                    + "用户问到这类情况时，只回答需要先查一下真实数据，并请用户说明想查哪一项"
                    + "（例如本月销售概况、出货记录、库存、生产报表）。";

    /** 护栏拦下回复后用户看到的固定文字 (列出可选的查询, 不编任何数)。 */
    public static final String NEED_QUERY_REPLY =
            "这个我需要先查一下本厂的真实数据，不能凭空给出数字。您是想看哪一项？\n"
                    + "1. 本月销售 / 经营概况\n"
                    + "2. 出货记录\n"
                    + "3. 库存情况\n"
                    + "4. 生产报表\n"
                    + "直接说「查本月销售」「查库存」这样就可以。";

    public enum Decision { PASS, BLOCKED }

    /** @param text 用户能看到的文本; @param rule 拦下时命中的规则 (放行时为 null) */
    public record Result(String text, Decision decision, String rule) { }

    // ==================== 识别层 ====================

    /** 业务主语: 这些东西的「情况 / 多少」只能查, 不能聊。「卖」只认业务用法 (卖萌 / 卖关子不算)。 */
    private static final Pattern QUESTION_SUBJECT = Pattern.compile(
            "销售|销量|卖得|卖了|卖出|卖掉|好卖|畅销|营收|营业额|收入|生意|业绩|利润|毛利|成本|产量|产值|生产"
                    + "|出.{0,3}货|发.{0,3}货|库存|存货|质检|合格率|良品率|不良|订单|采购|回款|应收|应付|损耗|报废"
                    + "|入库|出库|产能|开工");

    /** 问情况 / 问数量 / 问变化 (「怎么提高」「如何做」这类咨询不在这里, 也不靠句末问号 / 吗 / 呢判业务)。 */
    private static final Pattern QUESTION_ASK = Pattern.compile(
            "怎么样|咋样|怎样|如何|情况|多少|几[个件吨单批箱台家笔斤]|好不好|行不行|好吗|多吗|够吗|够不够|多不多|高不高"
                    + "|了吗|了没|有没有|有吗|还有|排名|排行|同比|环比|涨|跌|增长|下降|变化|趋势|表现"
                    + "|怎么(?:这么|那么|又|会|还|突然|回事|下降|少|多|了)|[?？]$|吗$|呢$");

    /**
     * 咨询 / 怎么做类问法: 先于业务问法判定。「怎么 / 怎样 / 如何」后面还跟着动作才是咨询
     * (「怎么样」「销量如何」「如何？」是在问情况, 不算); 「注意什么 / 建议 / 合适」同理。
     */
    private static final Pattern CONSULT = Pattern.compile(
            "(?:怎么(?!样|这么|那么|又|会|还|突然|回事|下降|少|多|了)|怎样|如何)(?![?？。!！吗呢啊呀了，,]|$)"
                    + "|怎么办|注意|建议|办法|合适|适合|温度");

    /**
     * 这句话是不是在问业务情况 / 业务数字。是 ⇒ 调用方不许把它交给纯闲聊模型。
     * 「外卖」这类含「卖」的非业务词先剥掉; 咨询句 (怎么降低成本 / 如何提高合格率 / 怎么创建订单) 先判非业务。
     */
    public static boolean isBusinessQuestion(String input) {
        if (input == null || input.isBlank()) return false;
        String s = input.toLowerCase().replaceAll("\\s+", "").replace("外卖", "").replace("买卖", "");
        if (CONSULT.matcher(s).find()) return false;
        return QUESTION_SUBJECT.matcher(s).find() && QUESTION_ASK.matcher(s).find();
    }

    // ==================== 输出闸 ====================

    private static final Pattern SENTENCE = Pattern.compile("[^。！？!?；;\\n]+");
    /** 句子 + 它后面的一个分隔符 (拦掉句子时连标点一起删, 剩下的原样拼回)。 */
    private static final Pattern SENTENCE_WITH_DELIMITER = Pattern.compile("[^。！？!?；;\\n]+[。！？!?；;\\n]?|[。！？!?；;\\n]");

    /** 百分比只有出现在业务指标句里才算业务数字 (「湿度 60% 以下」不算)。 */
    private static final Pattern METRIC_WORD = Pattern.compile(
            "同比|环比|增长|增加|下降|减少|上涨|下滑|提升|上升|销售|销量|营收|营业额|收入|利润|毛利|成本|产量|产值"
                    + "|出货|发货|库存|订单|合格率|良品率|不良率|完成率|达成率|占比|份额|业绩|生意|回款|损耗率|报废率"
                    + "|开工率|利用率|出勤率");

    /** 业务数量单位 (紧跟在数后面)。「个」只在不是「个月 / 个原则」这类量词时算。 */
    private static final Pattern QUANTITY_UNIT = Pattern.compile(
            "^\\s*(件|箱|吨|公斤|千克|kg|斤|单|笔|批|袋|包|瓶|盒|头|只|台|个订单|个客户|家客户|位客户|万件|万箱)");

    /** 「个」是泛量词: 只在和指标主语 (不含时间词 / 客户) 同句、且不是「3 个关键点」这类建议量词时才算业务数字。 */
    private static final Pattern GE_UNIT = Pattern.compile(
            "^\\s*个(?!月|小时|原则|步|方面|要点|关键|维度|贴士|技巧|建议|措施|环节|方法|办法|思路|角度|阶段|条|点|人|种|类|项)");

    /** 金额单位 (紧跟在数后面)。 */
    private static final Pattern MONEY_UNIT = Pattern.compile("^\\s*(元|块|美元|万元|亿元|万块)");

    /** 业务主语 (数量单位要和它同句才算业务数字)。 */
    private static final Pattern BUSINESS_SUBJECT = Pattern.compile(
            "销售|销量|卖得|卖了|卖出|卖掉|营收|营业额|收入|生意|产量|生产了|产出|出货|发货|库存|订单|入库|出库|采购|客户|回款"
                    + "|本月|这个月|这月|今天|今日|本周|这周|上月|上个月|昨天|今年|本季度");

    /** 业务指标主语 (不含时间词): 不带单位的裸数字 / 万亿量级要和它同句才算业务数字。 */
    private static final Pattern METRIC_SUBJECT = Pattern.compile(
            "销售|销量|卖得|卖了|卖出|卖掉|营收|营业额|收入|利润|毛利|成本|产量|产值|出货|发货|库存|订单|入库|出库"
                    + "|采购|回款|业绩|生意|合格率|良品率|不良率|完成率|达成率|损耗|报废|产能");

    /** 数字后面没有任何单位 (句末 / 标点 / 「了」「的」/ 多 / 余 / 左右): 「订单 35」「库存还有 200 多」。 */
    private static final Pattern BARE_AFTER = Pattern.compile(
            "^\\s*(?:多|余|左右|上下|出头)?\\s*(?:$|[，,。！!？?；;、：:）)]|了|的|吧|呢)");

    /** 业务趋势 / 评价断言 (不带数字的业务事实): 时间锚 + 业务主语 + 趋势评价。同比 / 环比 / 比去年本身就是时间锚。 */
    private static final Pattern TIME_ANCHOR = Pattern.compile(
            "本月|这个月|这月|今天|今日|本周|这周|上周|上月|上个月|昨天|今年|本季度|最近|近期"
                    + "|同比|环比|比去年|比上年|比上月|比上个月|比上周|比昨天|比上季度|较去年|较上年|较上月|较上个月|较上周|较昨天");
    private static final Pattern FACT_SUBJECT = Pattern.compile(
            "销售|销量|卖得|营收|营业额|收入|利润|毛利|成本|合格率|产量|出货|发货|库存|订单|生意|业绩|回款");
    private static final Pattern TREND_WORD = Pattern.compile(
            "增长|下降|上涨|下滑|上升|提升|持平|良好|不错|稳定|向好|偏低|偏高|较好|较差|增加|减少|火爆|低迷");
    /** 条件句 / 假设句 / 愿望句里的趋势词不是在陈述事实 (「如果本月销量下滑, 建议…」「想让销量同比增长, 可以…」)。 */
    private static final Pattern CONDITIONAL = Pattern.compile(
            "如果|若|假如|一旦|假设|万一|是否|有没有|希望|想要|想让|目标|争取|如何|怎么|怎样|有助于|旨在");

    /** 拦下 GENERAL_QUESTION (咨询) 回复时的提示: 不给销售菜单, 只说明涉及本厂数据要先查。 */
    public static final String ADVICE_DATA_NOTICE =
            "提示：这个问题涉及本厂的实际数据，需要先查询真实数据，我不能凭空给出数字或判断。"
                    + "想看实际情况可以直接说具体的查询，比如「查本月销售」「查库存」。";
    /** 咨询回复里去掉涉及本厂数据的句子后, 剩下的通用建议前面的说明。 */
    private static final String ADVICE_KEPT_PREFIX = "（涉及本厂数据的内容需要先查询真实数据，已去掉；以下是通用建议）\n";

    /** 只用它的数字抽取 (遮掉日期 / 编码 / 序号的那一套), 不经它的放行与计数。 */
    private final FactoryNumberProvenance numbers = new FactoryNumberProvenance();
    private final Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
    private MeterRegistry meterRegistry;

    @Autowired(required = false)
    public void setMeterRegistry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /** 一处违规: 命中的规则 + 它所在句子在 reply 里的起点 (拦句用)。 */
    private record Hit(String rule, int sentenceStart) { }

    /**
     * 判定一段<b>没有工具查询结果支撑</b>的闲聊 / 兜底回复能不能给用户看 (闲聊口径: 被拦换固定的「需要先查」菜单)。
     *
     * @param site  调用点名 (进日志与计数器)
     * @param reply 模型写的回复
     */
    public Result check(String site, String reply) {
        return check(site, null, reply, false);
    }

    /**
     * @param userInput 用户原话: 原话里出现过的数字是用户自己给的, 回复里复述不算编造
     * @param advice    {@code true} = 这是 GENERAL_QUESTION 咨询回复: 被拦时不回销售菜单, 去掉涉及本厂数据的句子、
     *                  尽量保留剩下的通用建议, 并说明「涉及本厂数据需先查」
     */
    public Result check(String site, String userInput, String reply, boolean advice) {
        List<Hit> hits = hits(reply, userInput);
        Result result;
        if (hits.isEmpty()) {
            result = new Result(reply, Decision.PASS, null);
        } else {
            result = new Result(advice ? adviceWithoutFacts(reply, userInput, hits) : NEED_QUERY_REPLY,
                    Decision.BLOCKED, hits.get(0).rule());
        }
        return record(site, result, reply);
    }

    /** {@link #check} 的文本版本。 */
    public String enforce(String site, String reply) {
        return check(site, reply).text();
    }

    public String enforce(String site, String userInput, String reply, boolean advice) {
        return check(site, userInput, reply, advice).text();
    }

    /** 某个调用点某种判定的累计次数 (进程内)。 */
    public long count(String site, Decision decision) {
        AtomicLong c = counts.get(site + "|" + decision.name());
        return c != null ? c.get() : 0L;
    }

    /** 某个调用点的累计判定次数 —— 每次 {@link #check} 必加一, 用作「它确实跑了」的对照。 */
    public long total(String site) {
        return count(site, Decision.PASS) + count(site, Decision.BLOCKED);
    }

    /** 命中的第一条规则; 没有违规返回 {@code null}。 */
    String violation(String reply) {
        List<Hit> hits = hits(reply, null);
        return hits.isEmpty() ? null : hits.get(0).rule();
    }

    private List<Hit> hits(String reply, String userInput) {
        List<Hit> hits = new ArrayList<>();
        if (reply == null || reply.isBlank()) return hits;
        List<FactoryNumberProvenance.NumberToken> userNumbers =
                userInput == null ? List.of() : numbers.extract(userInput);
        for (FactoryNumberProvenance.NumberToken t : numbers.extract(reply)) {
            if (echoedByUser(t, userNumbers)) continue;
            int sentenceStart = sentenceStartAt(reply, t.start());
            String sentence = sentenceAt(reply, t.start());
            String before = reply.substring(Math.max(0, t.start() - 2), t.start());
            String after = reply.substring(Math.min(reply.length(), t.end()));
            String rule = null;
            if (before.contains("¥") || before.contains("￥") || MONEY_UNIT.matcher(after).find()) {
                rule = "money:" + t.raw();
            } else if (t.percent() && METRIC_WORD.matcher(sentence).find()) {
                rule = "metric-percent:" + t.raw();
            } else if (sentence.contains("同比") || sentence.contains("环比")) {
                rule = "yoy-mom:" + t.raw();
            } else if (t.multiplier().compareTo(BigDecimal.ONE) > 0 && !t.percent()
                    && (METRIC_WORD.matcher(sentence).find() || METRIC_SUBJECT.matcher(sentence).find())) {
                rule = "big-number:" + t.raw(); // 「营收约 35 万」「销售额 120 万左右」: 不带「元」也是金额 / 量级
            } else if ((QUANTITY_UNIT.matcher(after).find() && BUSINESS_SUBJECT.matcher(sentence).find())
                    || (GE_UNIT.matcher(after).find() && METRIC_SUBJECT.matcher(sentence).find())) {
                rule = "business-quantity:" + t.raw();
            } else if (!t.percent() && BARE_AFTER.matcher(after).find() && METRIC_SUBJECT.matcher(sentence).find()) {
                rule = "business-bare-number:" + t.raw(); // 「产量达到 1200」「库存还有 200 多」
            }
            if (rule != null) hits.add(new Hit(rule, sentenceStart));
        }
        Matcher m = SENTENCE.matcher(reply);
        while (m.find()) {
            String s = m.group();
            if (TIME_ANCHOR.matcher(s).find() && FACT_SUBJECT.matcher(s).find() && assertsTrend(s)) {
                hits.add(new Hit("business-claim:" + s.strip(), m.start()));
            }
        }
        return hits;
    }

    /**
     * 句子里有没有「在陈述事实」的趋势分句: 按逗号切分, 只在含趋势词的分句里找条件词;
     * 条件词所在分句及其后的分句都算假设 (「如果销量同比增长, 那么库存会增加」不拦),
     * 条件词在后面的分句不影响前面的断言 (「本月销量同比增长明显, 目标是继续保持」要拦)。
     */
    private static boolean assertsTrend(String sentence) {
        boolean conditional = false;
        for (String clause : sentence.split("[，,]")) {
            if (CONDITIONAL.matcher(clause).find()) conditional = true;
            if (!conditional && TREND_WORD.matcher(clause).find()) return true;
        }
        return false;
    }

    /** 用户原话里写过同一个数 (含同样的百分号 / 万亿量级) ⇒ 是复述, 不是编造。 */
    private static boolean echoedByUser(FactoryNumberProvenance.NumberToken t,
                                        List<FactoryNumberProvenance.NumberToken> userNumbers) {
        for (FactoryNumberProvenance.NumberToken u : userNumbers) {
            if (u.percent() == t.percent() && u.value().compareTo(t.value()) == 0) return true;
        }
        return false;
    }

    /** 去掉命中句子, 剩下的通用建议前面加说明; 一句都不剩时只给提示 (不带销售菜单)。 */
    private String adviceWithoutFacts(String reply, String userInput, List<Hit> hits) {
        java.util.Set<Integer> dropped = new java.util.HashSet<>();
        for (Hit h : hits) dropped.add(h.sentenceStart());
        StringBuilder kept = new StringBuilder();
        Matcher m = SENTENCE_WITH_DELIMITER.matcher(reply);
        while (m.find()) {
            if (!dropped.contains(m.start())) kept.append(m.group());
        }
        String remaining = kept.toString().strip();
        boolean usable = remaining.codePoints().filter(Character::isLetterOrDigit).count() >= 8
                && hits(remaining, userInput).isEmpty();
        return usable ? ADVICE_KEPT_PREFIX + remaining + "\n\n" + ADVICE_DATA_NOTICE : ADVICE_DATA_NOTICE;
    }

    private static int sentenceStartAt(String text, int index) {
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            if (index >= m.start() && index < m.end()) return m.start();
        }
        return -1;
    }

    private static String sentenceAt(String text, int index) {
        Matcher m = SENTENCE.matcher(text);
        while (m.find()) {
            if (index >= m.start() && index < m.end()) return m.group();
        }
        return "";
    }

    private Result record(String site, Result r, String original) {
        counts.computeIfAbsent(site + "|" + r.decision().name(), k -> new AtomicLong()).incrementAndGet();
        if (meterRegistry != null) {
            try {
                meterRegistry.counter("cretas.ai.chat_business_fact_guard",
                        "site", site, "decision", r.decision().name()).increment();
            } catch (RuntimeException ignored) {
                // 计数失败不影响放行判定
            }
        }
        if (r.decision() == Decision.BLOCKED) {
            // 不写回复原文 (里面可能有用户或客户信息): 只记命中的规则类别和长度
            String rule = r.rule() == null ? "?" : r.rule().split(":", 2)[0];
            log.warn("[ChatBusinessFactGuard] site={} decision=BLOCKED rule={} replyLength={}",
                    site, rule, original == null ? 0 : original.length());
        }
        return r;
    }
}
