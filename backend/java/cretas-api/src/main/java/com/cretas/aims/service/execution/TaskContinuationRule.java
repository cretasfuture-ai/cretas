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
 *   <li>{@link #resumeTarget}: 没有正在问的任务时, 这句话是不是要回到某个被挂起的任务:
 *       <ul>
 *         <li>这句话 (或去掉「继续 / 回到 …」之后剩下的部分) 按已审核短语表指向的意图, 正好是栈里某个任务的意图
 *             → 恢复那个任务 (「报工」「继续报工」「回到报工」);</li>
 *         <li>只有恢复说法、没说别的 (「继续」「接着刚才的」) → 恢复栈顶;</li>
 *         <li>恢复说法 + 别的内容但对不上栈里任何任务 (「继续查库存」) → 不恢复, 当新话识别。</li>
 *       </ul></li>
 * </ol>
 * 恢复本身只把任务的已填参数找回来、接着问缺的那一项; <b>绝不执行</b>。补齐之后照常走显式意图 → 写操作出确认卡
 * (预览凭据 + 改前值比对, 见 PR 1632)。
 */
public final class TaskContinuationRule {

    public enum Action { ANSWER, INTERRUPT }

    /** 句首的语气词 / 承接词 (「好了，继续报工」)。 */
    private static final String LEAD = "(?:好了|好的|好|行|那|嗯|ok|OK|Ok)?[\\s，,。.]*";
    /** 恢复说法。「回头」在这里不算: 「回头再报工」是在说以后, 不是现在 (打断那一轮常这么说)。 */
    private static final Pattern RESUME_CUE = Pattern.compile(
            "^" + LEAD + "(?:继续|接着|回到|回去|返回|再回到)");
    /** 恢复说法之后的虚词: 去掉这些还剩东西, 才算「说了别的」。 */
    private static final Pattern RESUME_FILLER = Pattern.compile(
            "刚才|刚刚|之前|前面|上面|上一个|那个|这个|任务|的|做|弄|办|吧|啊|呢|吗|一下|来|去|吧");
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
     * @param phraseIntent 一句话按已审核短语表指向的意图码 (知识库短语表); 没有 = empty
     */
    public static Optional<ConversationSession> resumeTarget(String input, List<ConversationSession> stack,
                                                             Function<String, Optional<String>> phraseIntent) {
        if (input == null || input.isBlank() || stack == null || stack.isEmpty()) {
            return Optional.empty();
        }
        String text = input.strip();
        Matcher cue = RESUME_CUE.matcher(text);
        boolean hasCue = cue.find();
        String rest = hasCue ? text.substring(cue.end()) : text;

        boolean namedSomething = false;
        for (String candidate : hasCue ? List.of(text, rest) : List.of(text)) {
            Optional<String> intent = phrase(phraseIntent, candidate);
            if (intent.isEmpty()) continue;
            namedSomething = true;
            String code = intent.get();
            Optional<ConversationSession> hit =
                    stack.stream().filter(s -> code.equals(s.getKnownIntentCode())).findFirst();
            if (hit.isPresent()) return hit;
        }
        // 说出了一件栈里没有的事 (「查库存」「继续查库存」): 新话, 不恢复
        if (namedSomething || !hasCue) {
            return Optional.empty();
        }
        String leftover = PUNCTUATION.matcher(RESUME_FILLER.matcher(rest).replaceAll("")).replaceAll("");
        return leftover.isEmpty() ? Optional.of(stack.get(0)) : Optional.empty();
    }

    private static Optional<String> phrase(Function<String, Optional<String>> phraseIntent, String text) {
        if (phraseIntent == null || text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<String> hit = phraseIntent.apply(text.strip());
            return hit == null ? Optional.empty() : hit.filter(Objects::nonNull);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
