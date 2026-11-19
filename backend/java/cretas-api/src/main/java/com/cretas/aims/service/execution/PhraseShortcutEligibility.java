package com.cretas.aims.service.execution;

import com.cretas.aims.config.IntentKnowledgeBase;

import java.util.regex.Pattern;

/**
 * 短语快捷通道的放行条件: 只有「整句基本就是这个短语」的无条件简单说法才跳过决策层直接定意图。
 *
 * <p>2026-11-17 F006 数字班组实测, 短语表的「≥4 字短语句中包含即命中」把带条件 / 多个子问的句子截走了:
 * <ul>
 *   <li>「周五要交 2 吨酱牛肉急单，原料够不够，产线排不排得开？」→ 句中「料够不够」→ 307 条原料批次清单;</li>
 *   <li>「现在牛前腱还有多少库存？」→「还有多少库存」→ 不按物料过滤的全部库存;</li>
 *   <li>「这个月销售额多少，哪个客户买得最多？」→「销售额多少」→ 无关的 KPI 报告;</li>
 *   <li>「上个月毛利率多少？」→ 剥掉句首时间词后「毛利率多少」→ 时间条件没人管。</li>
 * </ul>
 * 规则: 命中的短语从整句里拿掉, 再去掉标点和语气 / 礼貌用词 ({@link #FILLER}), 剩下的字 (物料名、时间、客户、
 * 数量、第二个问题) 超过 {@value #MAX_RESIDUAL_CHARS} 个就是带条件, 交给决策层。
 * 报工说法有自己的整句约束 ({@link IntentKnowledgeBase#isReportWorkSentence}), 符合就放行 (产量是它的一部分)。
 *
 * <p>句首时间词不算条件 (P1-A 既有设计): 短语表剥掉句首时间词再匹配, 工具从原话里解析时间
 * (「这个月卖得怎么样」「上个月毛利率多少」照走短语, 工具查的是那个月; 「上个月」写成「本月」的是标题, 已在格式化修)。
 * 续上一件事的说法 (「继续报工」) 和不看条件的能力说明 (供应商比价) 不判, 见 {@link #CONVERSATION_CONTROL_INTENTS}。
 */
public final class PhraseShortcutEligibility {

    /** 允许短语之外剩下的字数 (「先看下库存」里的「先」)。 */
    static final int MAX_RESIDUAL_CHARS = 1;

    /**
     * 不看条件的意图, 不判:
     * <ul>
     *   <li>CONTEXT_CONTINUE: 说的是「接着上一件事」, 后面跟的是那件事的名字, 不是查询条件;</li>
     *   <li>SUPPLIER_PRICE_COMPARISON: 代码自有的只读能力说明 ({@link SupplierComparisonReadIntent}, 不在库里的决策目录),
     *       工具不论物料 / 供应商都只答「未接入真实报价、不能比价」, 句中条件丢不了; 交给决策层反而认不到它,
     *       权限闸也就轮不到它把关 (SupplierComparisonDispatchContractTest 守)。</li>
     * </ul>
     */
    static final java.util.Set<String> CONVERSATION_CONTROL_INTENTS = java.util.Set.of(
            "CONTEXT_CONTINUE", SupplierComparisonReadIntent.CODE);

    private static final Pattern PUNCT_AND_SPACE = Pattern.compile("[\\s\\p{P}\\p{S}　]+");

    /** 不改变查询范围的语气 / 礼貌 / 指代当下的词。⛔ 不含时间段 (本月 / 上个月)、不含任何名词。 */
    private static final Pattern FILLER = Pattern.compile(
            "帮我|帮忙|麻烦|请你|请|给我|我想|我要|想要|一下|看看|看下|查查|现在|目前|当前|一共|总共"
                    + "|吧|吗|呢|啊|呀|嘛|哦|了");

    private PhraseShortcutEligibility() {
    }

    /**
     * @param input  用户原话
     * @param phrase 短语表里命中的那条短语 (null = 拿不到命中的短语, 不判覆盖率, 照原行为放行)
     * @return true = 可以走短语快捷
     */
    public static boolean isUnconditionedSimple(String input, String phrase) {
        return isUnconditionedSimple(input, phrase, null);
    }

    /** @param intentCode 短语指向的意图 (对话控制类不判, 见 {@link #CONVERSATION_CONTROL_INTENTS}) */
    public static boolean isUnconditionedSimple(String input, String phrase, String intentCode) {
        if (input == null || input.isBlank()) {
            return false;
        }
        if (intentCode != null && CONVERSATION_CONTROL_INTENTS.contains(intentCode)) {
            return true;
        }
        if (IntentKnowledgeBase.isReportWorkSentence(input)) {
            return true;
        }
        if (phrase == null || phrase.isBlank()) {
            return true;
        }
        if (intentCode != null && SELF_PARSING_CONDITION_INTENTS.contains(intentCode)) {
            // 急单分析工具自己从原话里解析交期 / 数量 / 品名 (「周五要交2吨酱牛肉急单, 原料够不够, 产线排不排得开」,
            // BP 原话): 句中条件正是它的输入, 不是第二个子问 (2026-11-18 prod [SSE-EarlyPhrase] residual 被拦落到澄清)
            return true;
        }
        if ("PRODUCT_PRODUCT_SALES_RANKING".equals(intentCode) && IntentKnowledgeBase.GROWTH_BREAKDOWN_PHRASES.contains(phrase)) {
            // 「10月比9月涨了8.2%, 主要是哪个客户、哪个产品带来的」: 两个月和百分数是增量拆分工具自己从原话解析的输入, 不是第二个子问
            return true;
        }
        String rest = residual(input, phrase);
        if (intentCode != null && DATE_RANGE_OK_INTENTS.contains(intentCode)) {
            // 「11月1日到18日销售额是多少」: 区间本身是工具的 startDate/endDate, 不是第二个子问 (2026-11-18 LSMDEMO:
            // 这句被当成带条件交给决策层, 落到要 indicator_code 的指标工具)
            rest = DATE_RANGE.matcher(rest).replaceAll("");
        }
        return rest.length() <= MAX_RESIDUAL_CHARS;
    }

    /** 条件由工具自己从原话解析的意图, 命中即放行。只放这一个, 其它意图仍按残留字数判。 */
    static final java.util.Set<String> SELF_PARSING_CONDITION_INTENTS = java.util.Set.of("SCHEDULING_RUSH_ORDER_ANALYSIS");

    /** 工具自己接受 startDate / endDate 的只读汇总意图: 句中的日期区间不算「带条件」。 */
    static final java.util.Set<String> DATE_RANGE_OK_INTENTS = java.util.Set.of("REPORT_SALES_MARGIN_SUMMARY");

    private static final Pattern DATE_RANGE = Pattern.compile(
            "(\\d{4}年)?\\d{1,2}月(\\d{1,2}[日号]?)?|\\d{1,2}[日号]|[到至~—-]|从|自|截止|截至|今天|昨天|前天");

    /**
     * 整句拿掉短语、标点、语气词后剩下的部分 (日志 / 测试读数用)。短语不在原话里 (剥过数量 / 时间才命中) = 原话全部。
     *
     * <p>句首时间词两边一起剥 (#1813 终审 M1): 短语表里有约 93 条以时间词开头的短语 (本月营收 / 上月净利率 / 本周发货),
     * 只剥原话不剥短语时, 一字不差说出「本月营收」反而剩下「营收」被拒。短语与原话一样先剥句首时间词再比
     * (句首时间词不算条件, 工具按原话里的时间查, 同 P1-A)。
     */
    static String residual(String input, String phrase) {
        return withoutPhrase(normalize(input), normalize(phrase));
    }

    private static String normalize(String text) {
        return PUNCT_AND_SPACE.matcher(IntentKnowledgeBase.stripLeadingTime(text.toLowerCase())).replaceAll("");
    }

    private static String withoutPhrase(String normalized, String p) {
        int at = p.isEmpty() ? -1 : normalized.indexOf(p);
        String rest = at < 0 ? normalized : normalized.substring(0, at) + normalized.substring(at + p.length());
        return FILLER.matcher(rest).replaceAll("");
    }
}
