package com.cretas.aims.service;

import com.cretas.aims.entity.conversation.ConversationSession.CandidateIntent;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 用户对澄清选项的回复对上哪一项: 「1」「选2」「第一个」「完成工序」「我选完成工序」。
 *
 * <p>只认「像在选」的回复: 序号, 或几乎只有选项名 (名字之外最多再带 {@value #MAX_EXTRA_CHARS} 个字的
 * 「我选 / 就是 / 吧」之类)。一句完整的新话 (哪怕里面碰巧含选项名, 如「完成工序任务1830」) 不算选择 ——
 * 否则用户换话题会被旧选项截走。名字同时对上两项也不算 (宁可当新话重新识别)。
 */
public final class ClarificationChoiceMatcher {

    static final int MAX_EXTRA_CHARS = 4;
    /** 选项名 (≤3 项、各自很短) + 修饰字之上的回复必不是在选, 调用方据此免查会话。 */
    public static final int MAX_REPLY_CHARS = 20;
    private static final Pattern PUNCT = Pattern.compile("[\\s。．.,，、!！?？:：;；~～]+");
    private static final Pattern ORDINAL = Pattern.compile(
            "^(?:我)?(?:就)?(?:选择|选|要|是)?第?([0-9一二三四五])(?:个|项|条|号)?(?:吧|啊|呀|的)?$");

    private ClarificationChoiceMatcher() { }

    /** @return 命中候选的下标; 没对上返回 -1 */
    public static int match(String reply, List<CandidateIntent> candidates) {
        if (reply == null || candidates == null || candidates.isEmpty()) {
            return -1;
        }
        String text = PUNCT.matcher(reply).replaceAll("").toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return -1;
        }
        Matcher ordinal = ORDINAL.matcher(text);
        if (ordinal.matches()) {
            int index = ordinalValue(ordinal.group(1)) - 1;
            return index >= 0 && index < candidates.size() ? index : -1;
        }
        int hit = -1;
        for (int i = 0; i < candidates.size(); i++) {
            CandidateIntent c = candidates.get(i);
            if (c == null) {
                continue;
            }
            if (c.getIntentCode() != null && text.equals(c.getIntentCode().toLowerCase(Locale.ROOT))) {
                return i;
            }
            String name = c.getIntentName() == null ? ""
                    : PUNCT.matcher(c.getIntentName()).replaceAll("").toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && text.contains(name) && text.length() <= name.length() + MAX_EXTRA_CHARS) {
                if (hit >= 0) {
                    return -1;
                }
                hit = i;
            }
        }
        return hit;
    }

    /** 关键词在 {@code reasoning} 里用这个符号隔开 (见 {@link #matchParameterChoice})。 */
    public static final String KEYWORD_SEPARATOR = "|";
    /** 关键词之外还剩的字数上限 (去掉数字和标点后): 「那批报工」「我选这个」算, 一整句新话不算。 */
    static final int MAX_LEFTOVER_CHARS = 8;
    /** 剩下的话里出现这些 = 在问别的 / 在否定, 不是在选。 */
    private static final Pattern NOT_A_PICK = Pattern.compile("查|看看|问|多少|库存|怎么|为什么|吗|取消|不要|别|不是|算了");
    private static final Pattern DIGITS = Pattern.compile("[0-9]+(?:\\.[0-9]+)?");

    /**
     * 「选哪一道工序 / 哪一批」这类参数选项的回复匹配: 先按序号 / 选项全名 ({@link #match}),
     * 再按选项的关键词 (产品名、工序名、批次号, 存在 {@code reasoning} 里, 用 {@value #KEYWORD_SEPARATOR} 隔开):
     * 回复里出现的关键词越多越像; 并列第一 (同一产品下好几道工序、只说了产品名) 不算选中, 调用方再问一遍。
     * 关键词之外还带一整句别的话 (「查一下红烧肉库存」) 同样不算, 免得换话题被旧选项截走。
     *
     * @return 命中候选的下标; 没对上返回 -1
     */
    public static int matchParameterChoice(String reply, List<CandidateIntent> candidates) {
        int byOrdinalOrName = match(reply, candidates);
        if (byOrdinalOrName >= 0 || reply == null || candidates == null || candidates.isEmpty()) {
            return byOrdinalOrName;
        }
        String text = PUNCT.matcher(reply).replaceAll("").toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            return -1;
        }
        int best = -1;
        int bestScore = 0;
        boolean tie = false;
        String leftover = text;
        for (int i = 0; i < candidates.size(); i++) {
            CandidateIntent c = candidates.get(i);
            if (c == null || c.getReasoning() == null || c.getReasoning().isBlank()) {
                continue;
            }
            int score = 0;
            String rest = text;
            for (String keyword : c.getReasoning().split("\\" + KEYWORD_SEPARATOR)) {
                String k = PUNCT.matcher(keyword).replaceAll("").toLowerCase(Locale.ROOT);
                if (k.length() >= 2 && rest.contains(k)) {
                    score++;
                    rest = rest.replace(k, "");
                }
            }
            if (score > bestScore) {
                best = i;
                bestScore = score;
                tie = false;
                leftover = rest;
            } else if (score == bestScore && score > 0) {
                tie = true;
            }
        }
        if (best < 0 || tie) {
            return -1;
        }
        // 关键词之外还带了数字 (「卤鸭腿，改成80」) = 同时在说新的数据, 不当成单纯的选择 (否则那个 80 会被悄悄丢掉),
        // 交回识别按新话处理
        if (DIGITS.matcher(leftover).find()) {
            return -1;
        }
        String extra = PUNCT.matcher(leftover).replaceAll("");
        if (extra.length() > MAX_LEFTOVER_CHARS || NOT_A_PICK.matcher(extra).find()) {
            return -1;
        }
        return best;
    }

    private static int ordinalValue(String s) {
        return switch (s) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            default -> Integer.parseInt(s);
        };
    }
}
