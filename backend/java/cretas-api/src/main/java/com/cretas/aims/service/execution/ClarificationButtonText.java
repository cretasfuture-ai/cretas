package com.cretas.aims.service.execution;

import java.util.regex.Pattern;

/**
 * 澄清按钮 (SELECT_INTENT) 副标题的用户可见文本规则。
 *
 * <p>2026-10-06 prod 实测, 一线在按钮上看到两类不该看的东西:
 * <ul>
 *   <li>「置信度: 39%」 —— 意图没有描述时, 旧代码用「置信度: xx%」兜底当副标题。识别分数是内部读数,
 *       不是给人看的, 而且 39% 这种数字只会让人怀疑「系统自己都没把握」。没有描述就<b>不显示副标题</b>。</li>
 *   <li>「Sprint 10 Loop 1 — confirm 模式, 接 salesOrderId」 —— 意图描述里的开发者备注原样漏给了用户。
 *       真正的修法是改数据 (V20261101_58), 这里只是兜底: 以后再有人写进这种描述, 按钮上宁可不显示副标题。</li>
 * </ul>
 *
 * <p>⚠️ {@link #looksLikeDeveloperText} 是<b>代理判据</b> (形态 E): 它按关键词猜「这是开发者写的」,
 * 看不见所有写法。所以它只能<b>隐藏</b>副标题, 绝不拦截按钮或改动识别; 误判的代价只是少一行副标题。
 * 不要靠不断加关键词去逼近 —— 那条路上的闸只会越来越误报。
 */
public final class ClarificationButtonText {

    private static final Pattern DEVELOPER_TEXT = Pattern.compile(
            "(?i:sprint\\s*\\d|loop\\s*\\d|workdesk|read-only|preview|idempotent|\\btool\\b|\\bskill\\b)"
                    + "|灵魂|confirm 模式"
                    + "|\\bP\\d[a-z]?\\b|\\bR[1-9]\\b|\\bWRITE\\b|\\bBLOCKING\\b"
                    + "|\\b[a-z]+[A-Z][a-z]+[A-Za-z]*\\b"          // camelCase 字段名 (salesOrderId …)
                    + "|\\b[A-Z]{2,}_[A-Z0-9_]+\\b");              // 枚举码 (IN_PROGRESS …)

    private ClarificationButtonText() {
    }

    /**
     * 按钮副标题: 描述为空、或看起来是开发者备注 → {@code null} (按钮不显示副标题);
     * 否则原样返回。⛔ 不再用「置信度」兜底。
     */
    public static String subtitle(String description) {
        if (description == null || description.isBlank() || looksLikeDeveloperText(description)) {
            return null;
        }
        return description;
    }

    /** 代理判据: 含 Sprint / Loop / 灵魂 / P 编号 / R1-R4 / Tool / camelCase 字段名 / 枚举码 等开发者字样。 */
    public static boolean looksLikeDeveloperText(String text) {
        return text != null && DEVELOPER_TEXT.matcher(text).find();
    }
}
