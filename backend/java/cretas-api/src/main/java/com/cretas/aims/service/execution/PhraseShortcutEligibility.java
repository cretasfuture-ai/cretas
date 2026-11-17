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
 * 续上一件事的说法 ({@link #CONVERSATION_CONTROL_INTENTS}, 「继续报工」) 不带查询条件, 不判。
 */
public final class PhraseShortcutEligibility {

    /** 允许短语之外剩下的字数 (「先看下库存」里的「先」)。 */
    static final int MAX_RESIDUAL_CHARS = 1;

    /** 对话控制类意图: 说的是「接着上一件事」, 后面跟的是那件事的名字, 不是查询条件。 */
    static final java.util.Set<String> CONVERSATION_CONTROL_INTENTS = java.util.Set.of("CONTEXT_CONTINUE");

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
        return residual(input, phrase).length() <= MAX_RESIDUAL_CHARS;
    }

    /** 整句拿掉短语、标点、语气词后剩下的部分 (日志 / 测试读数用)。短语不在原话里 (剥过数量 / 时间才命中) = 原话全部。 */
    static String residual(String input, String phrase) {
        String normalized = PUNCT_AND_SPACE.matcher(IntentKnowledgeBase.stripLeadingTime(input.toLowerCase())).replaceAll("");
        String p = PUNCT_AND_SPACE.matcher(phrase.toLowerCase()).replaceAll("");
        int at = p.isEmpty() ? -1 : normalized.indexOf(p);
        String rest = at < 0 ? normalized : normalized.substring(0, at) + normalized.substring(at + p.length());
        return FILLER.matcher(rest).replaceAll("");
    }
}
