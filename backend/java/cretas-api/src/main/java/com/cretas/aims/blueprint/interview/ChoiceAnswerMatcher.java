package com.cretas.aims.blueprint.interview;

import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;

import java.util.List;
import java.util.regex.Matcher;

/**
 * 枚举题的答案匹配 —— <b>全仓唯一一处</b>「客户这句话是不是逐字答出了某个选项」。
 *
 * <h2>被治的那个缺陷（2026-08-17 本地端到端实测，两次都复现）</h2>
 *
 * <pre>
 * choiceParam("createAccounts", "登录账号", …, List.of("建", "先不建, 我们自己加"))
 *
 * 客户答「建」 -> 锚点 createAccounts(正确) -> 模型返回 createAccounts: null
 * 模型 reasoning:「用户只输入了一个字『建』，这显然不足以提取任何参数。
 *                 根据提取判据，我需要确保用户确实在回答某个参数对应的问题时才填。」
 * -> 问 2 次都 null -> 跳过 -> provisionDefaultUsers=False
 * </pre>
 *
 * ⚠️ <b>模型没做错。</b>那三条抽取判据（「只有确实在回答时才填」「宁可留 null
 * 也不要猜」）是 2026-08-10 为治「一句话被劈成两半填进多个槽」加的，它们是对的。
 * 错在<b>枚举题根本不该走那条路</b> —— {@code FactoryAssemblyConversationSession} 里那段
 * v2 扩展字段的注释早就写着：
 *
 * <blockquote>而 LLM 抽取对枚举项是<b>负作用</b>：它会把「差不多」的说法归一到
 * 某个选项，而那正是我们不想要的猜测。</blockquote>
 *
 * <b>注释说了，抽取那条路从来不读 {@code inputType}。</b>（形态 B：机制在，没接上。）
 *
 * <p>后果是 2026-08-10 审计里最重的那一条：<b>配完的工厂没有任何人能登录</b>。
 *
 * <h2>⛔ 只做逐字匹配</h2>
 *
 * 匹配不上就返回 {@code null}，调用方<b>原样</b>走 LLM —— 行为与改动前逐字一致。
 *
 * <ul>
 *   <li>「建」→ 命中 ✅</li>
 *   <li>「差不多要建吧」→ 不命中，走 LLM（⛔ 不猜）</li>
 *   <li>「建三个仓库」→ 不命中（含「建」字，但显然不是在答这道题）</li>
 * </ul>
 *
 * ⛔ <b>不许加同义词表 / 包含匹配 / 编辑距离</b>。那就是上面那句注释反对的那件事，
 * 等于把同一个错换个地方再犯一遍。
 *
 * <p>⛔ <b>多选题不走这条</b>：答一个选项 ≠ 答完了，替它做主是另一类错。
 *
 * @since 2026-08-17
 */
public final class ChoiceAnswerMatcher {

    private ChoiceAnswerMatcher() {
    }

    /**
     * 这一轮问的那道题如果是<b>单选</b>枚举题，且 {@code userReply} 去掉首尾空白后
     * <b>逐字等于</b>某个选项，就返回那个选项的<b>原文</b>；否则返回 {@code null}。
     *
     * <p>⚠️ 返回选项<b>原文</b>而不是客户打的字：下游（{@code _wants_default_users}
     * 之类）按选项文本判定，返回客户的写法会让「建 」和「建」变成两个值。
     *
     * @param askedThisRound 界面这一轮问的那一题（⛔ 来自
     *                       {@link FactoryAssemblyConversationSession#getNextPendingParameter()}，
     *                       不要在调用方自己再算一份 —— 那正是本仓刚修过的形态 D）
     */
    public static String exactOption(FactoryAssemblyConversationSession session,
                                     String askedThisRound,
                                     String userReply) {
        if (session == null || askedThisRound == null || userReply == null) {
            return null;
        }
        String answer = normalize(userReply);
        if (answer.isEmpty()) {
            return null;
        }
        List<FactoryAssemblyConversationSession.RequiredParameter> params = session.getRequiredParameters();
        if (params == null) {
            return null;
        }
        for (FactoryAssemblyConversationSession.RequiredParameter p : params) {
            if (!askedThisRound.equals(p.getName())) {
                continue;
            }
            // ⛔ 自由文本题一律不参与 —— 行为与改动前逐字一致。
            if (!"CHOICE".equals(p.getInputType()) || p.isMultiSelect()) {
                return null;
            }
            List<String> options = p.getOptions();
            if (options == null) {
                return null;
            }
            for (String opt : options) {
                if (opt != null && normalize(opt).equals(answer)) {
                    return opt;           // ⚠️ 原文, 不是客户打的字
                }
            }
            return null;
        }
        return null;
    }

    /**
     * 计数题：客户答「2 个」而选项是「2」时，也按<b>确定性</b>算命中。
     *
     * <h2>被治的那个缺陷（2026-08-18 实测，同一套答案连跑 8 遍）</h2>
     *
     * 「成品个数」的选项是<b>裸数字</b> {@code ["1","2","3","4","5","6","更多(说明)"]}，
     * 而客户答的是「2 个」——{@link #exactOption} 要求逐字相等，「2 个」≠「2」，
     * 于是这一题**落到模型**去抽。实测结果：
     *
     * <pre>
     * 客户答「2 个」  →  collected[productCount] = 4      8 遍里 6 遍
     *                                            ↑ 恰好等于同一场访谈里的「工序道数」
     * </pre>
     *
     * 那个 4 会**多展开出 product3 / product4 两个成品槽**，
     * 落库 {@code product_types} 因此变成 1/2/1/2/4/4/1/2，而八遍全部报 PASS。
     *
     * <h2>⚠️ 为什么只认「答案里只有一个数字」</h2>
     *
     * 客户可能一句话说两件事（「4 道工序，2 个成品」）——那时哪个数属于这一题
     * <b>不确定</b>，⛔ 猜错比不猜更糟（本仓判据：宁可漏报也不误报），
     * 交回给模型按上下文判。⇒ 只有<b>整句里恰好一个数字</b>时才确定性命中。
     *
     * <p>⚠️ 还要求那个数字**是这一题的选项之一**：答「12 个」而选项只到 6，
     * 说明他要的是「更多(说明)」那一支，不是这里能定的。
     */
    public static String numericOption(FactoryAssemblyConversationSession session,
                                       String askedThisRound,
                                       String userReply) {
        if (session == null || askedThisRound == null || userReply == null) {
            return null;
        }
        List<FactoryAssemblyConversationSession.RequiredParameter> params = session.getRequiredParameters();
        if (params == null) {
            return null;
        }
        for (FactoryAssemblyConversationSession.RequiredParameter p : params) {
            if (!askedThisRound.equals(p.getName())) {
                continue;
            }
            // ⛔ 只管计数题(type=number 的枚举题) —— 别的枚举题不许按数字匹配。
            if (!"CHOICE".equals(p.getInputType()) || p.isMultiSelect()
                    || !"number".equals(p.getType())) {
                return null;
            }
            List<String> options = p.getOptions();
            if (options == null) {
                return null;
            }
            Matcher m = DIGITS.matcher(userReply);
            String only = null;
            while (m.find()) {
                if (only != null) {
                    return null;      // 一句话里不止一个数字 —— 不确定, 交给模型
                }
                only = m.group();
            }
            if (only == null) {
                return null;
            }
            for (String opt : options) {
                if (opt != null && normalize(opt).equals(only)) {
                    return opt;       // ⚠️ 原文, 与 exactOption 同一条纪律
                }
            }
            return null;
        }
        return null;
    }

    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("\\d+");

    /**
     * 只归一首尾空白和中文/英文括号。括号是视觉输入法差异，不是业务含义；中间空白、
     * 同义词和包含关系仍不归一，避免把近似回答猜成枚举值。
     */
    private static String normalize(String s) {
        return s.replaceAll("^[\\s　]+|[\\s　]+$", "")
                .replace('（', '(')
                .replace('）', ')');
    }
}
