package com.cretas.aims.service.consent;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 意图对应 (用户原话 → 意图) 进训练样本表之前的规则 —— Steve 2026-10-24 拍板。
 *
 * <ul>
 *   <li>原话里有配方 / 工艺参数类内容 ({@link #PARAMETER_MARKERS}): 任何工厂都不存, 授权也不存;</li>
 *   <li>其余原话默认存, 用来学「听懂」; 未授权工厂的原话把数字、手机号、身份证号、银行卡号换成占位符;
 *       已授权 (training_consent = private / shared_anon) 原样存。</li>
 * </ul>
 * 工序词 (腌制、滚揉 …) 在原话里允许 —— 意图模型学的是说法, 不生成建议。
 *
 * <p>与 Python {@code backend/python/smartbi/services/training_consent.py} 的
 * {@code PARAMETER_MARKERS} / {@code mask_numbers} 必须一致: 两边都跑
 * {@code training_sample_rule_cases.json}, Python 测试还会比对本文件里的词表。
 */
public final class TrainingSampleRules {

    /** 配方 / 工艺参数类词 (与 Python PARAMETER_MARKERS 逐项相同)。 */
    public static final List<String> PARAMETER_MARKERS = List.of(
            "配方", "配比", "配料比", "投料比", "用量", "添加量", "添加比例",
            "工艺参数", "工艺条件", "加工参数", "杀菌温度", "杀菌时间", "灭菌温度", "灭菌时间",
            "中心温度", "油温", "转速", "糖度", "盐度", "含水量", "浓度", "pH");

    private static final Pattern ID_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{17}[\\dXx](?![0-9A-Za-z])");
    private static final Pattern BANK_CARD = Pattern.compile("(?<![0-9A-Za-z])\\d{16,19}(?![0-9A-Za-z])");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9A-Za-z])1[3-9]\\d{9}(?![0-9A-Za-z])");
    /** 数字; F006 / V2 / B12 这类编号保留。 */
    private static final Pattern NUMBER = Pattern.compile("(?<![A-Za-z0-9_.<])\\d+(?:\\.\\d+)?");

    private TrainingSampleRules() {
    }

    /** 原话含配方 / 工艺参数类内容 → 任何工厂都不进训练样本。 */
    public static boolean utteranceHasBlockedContent(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (String marker : PARAMETER_MARKERS) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    /** 未授权工厂原话的脱敏: 证件号 / 卡号 / 手机号 → 占位符, 其余数字 → {@code <数值>}。 */
    public static String maskNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = ID_CARD.matcher(text).replaceAll("<证件号>");
        out = BANK_CARD.matcher(out).replaceAll("<卡号>");
        out = PHONE.matcher(out).replaceAll("<电话>");
        return NUMBER.matcher(out).replaceAll("<数值>");
    }

    /**
     * 一条原话该怎么存: {@code null} = 不存; 否则返回要存的文本 (已授权原样, 未授权脱敏)。
     */
    public static String textToStore(String text, boolean authorized) {
        if (utteranceHasBlockedContent(text)) {
            return null;
        }
        return authorized ? text : maskNumbers(text);
    }
}
