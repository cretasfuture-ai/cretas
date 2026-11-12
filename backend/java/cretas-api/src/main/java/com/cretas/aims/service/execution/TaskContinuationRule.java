package com.cretas.aims.service.execution;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 续轮规则 (INTENT-DISTILL-20261111 第二批「任务状态」)。<b>普通入口与 SSE 入口共用这一份</b>,
 * 不各写一份 (审阅 5.2 缺口 5: 两个入口对「追问参数时换了话题」处理不同)。纯函数, 无副作用。
 *
 * <ol>
 *   <li>{@link #forActive}: 有一个正在问参数的任务时, 这句话是在回答它 ({@link Action#ANSWER}),
 *       还是打断它去做别的 ({@link Action#INTERRUPT})。「像不像回答」沿用 {@link SlotAnswerRule}。</li>
 *   <li>{@link #resumeTarget}: 没有正在问的任务时, 这句话是不是要回到某个被挂起的任务。宁可不恢复也不吞话
 *       (终审 S1 / N1):
 *       <ul>
 *         <li><b>必须以恢复说法开头</b> (继续 / 接着 / 回到 / 回去 / 返回)。只说一个意图短语 (「报工」) 不恢复 ——
 *             那是一次新的请求, 照常识别。</li>
 *         <li>带否定 (别 / 不要 / 不用 / 算了 / 取消 …) 不恢复。</li>
 *         <li>恢复说法之后只剩虚词 (「继续」「接着刚才的吧」) → 恢复栈顶。</li>
 *         <li>恢复说法之后剩下的部分含数字 / 字母 (像是在顺带给值或编号: 「继续完成工序任务1830」) 或超过
 *             {@link #MAX_NAMED_CHARS} 字 → 不恢复, 当新话识别, 免得值被吞掉。</li>
 *         <li>剩下的部分 = 栈里某个任务的意图名称或它配置的关键词之一 (规范化后整句相等;
 *             「报工」是 WORK_PROCESS_TASK_COMPLETE 配置的关键词, 见 V20261101_48), 或按已审核短语表指向那个任务的意图
 *             → 恢复那个任务; 对不上栈里任何任务 → 不恢复。</li>
 *       </ul>
 *       「回头」不算恢复说法: 「回头再报工」是在说以后。<b>调用方另须保证: 本轮刚打断过一个任务时不恢复</b>
 *       (同一句话既打断又恢复 = 原地吞话)。</li>
 * </ol>
 * 恢复本身只把任务的已填参数找回来、接着问缺的那一项; <b>绝不执行</b>。补齐之后照常走显式意图 → 写操作出确认卡
 * (预览凭据 + 改前值比对, 见 PR 1632)。
 */
public final class TaskContinuationRule {

    /**
     * ANSWER = 在回答; INTERRUPT = 换了话题, 挂起它; REPEAT = 说的是「继续 + 这个任务本身」
     * (「继续完成工序」时它正问着) —— 不挂起、不另起一份收集, 只把当前这一问再问一遍。
     */
    public enum Action { ANSWER, INTERRUPT, REPEAT }

    /** 恢复说法之后点名一个任务最多几个字 (「完成工序」「原料入库」); 再长多半是在说一件新事。 */
    static final int MAX_NAMED_CHARS = 8;

    /** 句首的语气词 / 承接词 (「好了，继续报工」)。 */
    private static final String LEAD = "(?:好了|好的|好|行|那|嗯|ok|OK|Ok)?[\\s，,。.]*";
    private static final Pattern RESUME_CUE = Pattern.compile(
            "^" + LEAD + "(?:继续|接着|回到|回去|返回|再回到)");
    /** 恢复说法之后的虚词: 去掉这些还剩东西, 才算「点名了一件事」。 */
    private static final Pattern RESUME_FILLER = Pattern.compile(
            "刚才|刚刚|之前|前面|上面|上一个|那个|这个|任务|的|做|弄|办|吧|啊|呢|吗|一下|来|去");
    private static final Pattern NEGATION = Pattern.compile("别|不要|不用|不想|不做|不报|先不|算了|取消|停止|作废");
    private static final Pattern VALUE_LIKE = Pattern.compile("[0-9A-Za-z０-９]");
    private static final Pattern PUNCTUATION = Pattern.compile("[\\s\\u3000，,。.！!？?、~～:：]+");

    private TaskContinuationRule() {
    }

    /** 正在问参数时: 回答它, 还是打断它。 */
    public static Action forActive(String input, List<RequiredParameter> pending,
                                   Predicate<String> isKnownCommand) {
        return SlotAnswerRule.looksLikeAnswer(input, pending, isKnownCommand) ? Action.ANSWER : Action.INTERRUPT;
    }

    /**
     * @param stack        被挂起的任务, 栈顶在前 (最近挂起的在前)
     * @param phraseIntent 一句话按已审核短语表指向的意图码; 没有 = empty
     * @param intentNames  意图码 → 名称与配置的关键词 (名称在前, 例如 [完成工序, 报工, 做完了, …]); 拿不到 = 空
     */
    public static Optional<ConversationSession> resumeTarget(String input, List<ConversationSession> stack,
                                                             Function<String, Optional<String>> phraseIntent,
                                                             Function<String, List<String>> intentNames) {
        if (input == null || input.isBlank() || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        String text = input.strip();
        Matcher cue = RESUME_CUE.matcher(text);
        if (!cue.find() || NEGATION.matcher(text).find()) {
            return Optional.empty();
        }
        String rest = normalize(RESUME_FILLER.matcher(text.substring(cue.end())).replaceAll(""));
        if (rest.isEmpty()) {
            return Optional.of(stack.get(0));
        }
        if (VALUE_LIKE.matcher(rest).find() || rest.length() > MAX_NAMED_CHARS) {
            return Optional.empty();
        }
        for (ConversationSession task : stack) {
            for (String name : names(intentNames, task.getKnownIntentCode())) {
                if (name != null && rest.equals(normalize(RESUME_FILLER.matcher(name).replaceAll("")))) {
                    return Optional.of(task);
                }
            }
        }
        Optional<String> code = phrase(phraseIntent, rest);
        if (code.isEmpty()) {
            return Optional.empty();
        }
        return stack.stream().filter(s -> code.get().equals(s.getKnownIntentCode())).findFirst();
    }

    private static String normalize(String text) {
        return text == null ? "" : PUNCTUATION.matcher(text).replaceAll("");
    }

    private static List<String> names(Function<String, List<String>> intentNames, String code) {
        if (intentNames == null || code == null) return List.of();
        try {
            List<String> names = intentNames.apply(code);
            return names == null ? List.of() : names;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Optional<String> phrase(Function<String, Optional<String>> phraseIntent, String text) {
        if (phraseIntent == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<String> hit = phraseIntent.apply(text);
            return hit == null ? Optional.empty() : hit.filter(Objects::nonNull);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
