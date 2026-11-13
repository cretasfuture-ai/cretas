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
