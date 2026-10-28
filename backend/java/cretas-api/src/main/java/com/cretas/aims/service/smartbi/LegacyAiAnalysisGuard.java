package com.cretas.aims.service.smartbi;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.text.Normalizer;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Boundary checks for the legacy Excel-upload chart analysis.
 *
 * The chart prompt receives aggregated series, so it cannot be trusted to
 * recover units, loss prices, or business thresholds from prose. This guard
 * rejects only claims that contradict the explicit analysisFacts contract and
 * returns a useful, honest fallback for the UI.
 */
public final class LegacyAiAnalysisGuard {
    private static final String NUMBER = "[+-]?\\d[\\d,]*(?:\\.\\d+)?";
    private static final String UNITS = "(?:件|盒|箱|袋|个|份|样本|批|次|公斤|千克|吨|斤|pcs?|pieces?|units?|items?|box(?:es)?|bags?|cases?|kg|ml|g|t|L)";
    private static final String QUANTITY_LABEL = "(?:总产出|总产量|产出|产量|总数量|不良数量|良品数量|不良品|良品|不良数|不良|合格数|检验数|total[ \\t]+output|output|quantity)(?!率)";
    private static final Pattern QUANTITY_WITH_UNIT = Pattern.compile(
            "(?i)" + NUMBER + "[ \\t]*(" + UNITS + ")(?![A-Za-z])");
    private static final Pattern QUANTITY_CLAIM = Pattern.compile(
            "(?i)" + QUANTITY_LABEL + "[ \\t]*(?:(?:数量|合计|总计|为|是|达到|:|=)[ \\t]*){0,4}"
                    + "(" + NUMBER + ")[ \\t]*(?:(" + UNITS + ")(?![A-Za-z]))?");
    private static final String CURRENCY = "(?:人民币|美元|元|块|¥|\\$|CNY|RMB|USD|yuan|dollars?)";
    // Groups: whole monetary span, prefix currency, prefix amount, suffix
    // currency after a prefixed amount, suffix-only amount, suffix currency.
    private static final String MONEY = "((?:((?:" + CURRENCY + "))[ \\t]*(" + NUMBER + ")"
            + "(?:[ \\t]*((?:" + CURRENCY + ")))?|(" + NUMBER + ")[ \\t]*((?:" + CURRENCY + "))))";
    private static final String LOSS = "(?:隐性损耗|损耗|报损|waste(?:[ \\t]+(?:cost|amount))?|loss(?:[ \\t]+(?:cost|amount))?)";
    private static final Pattern LOSS_TOKEN = Pattern.compile("(?i)" + LOSS);
    private static final Pattern MONEY_TOKEN = Pattern.compile("(?i)" + MONEY);
    private static final String LOSS_CONNECTOR = "[ \\t]*(?:(?:金额|成本|费用|数额|总额|约为|大约为|估计为|约|大约|估计|预计|为|是|合计|总计|共计|计为|计|amount|cost|equals|is|was|of|totaling|:|=|\\(|\\))[ \\t]*){0,5}";
    private static final Pattern LOSS_NUMBER = Pattern.compile("(?i)" + LOSS + LOSS_CONNECTOR + NUMBER);
    private static final Pattern CLAIM_SUFFIX = Pattern.compile("(?i)^[ \\t]*(?:" + UNITS + "|" + CURRENCY + ")(?![A-Za-z])");
    private static final Pattern LOSS_BEFORE_MONEY = Pattern.compile("(?i)" + LOSS + LOSS_CONNECTOR
            + MONEY);
    private static final Pattern MONEY_BEFORE_LOSS = Pattern.compile("(?i)" + MONEY
            + "[ \\t]*(?:(?:是|为|属于|用于|计作|作为|的|in|of|for|as)[ \\t]*){0,3}" + LOSS);
    private static final Pattern UNSOURCED_TARGET = Pattern.compile(
            "(?is)(结构失衡|成本结构失衡|cost\\s+mix.{0,20}(imbalanced|unbalanced)|"
                    + "(?:目标|target|建议|优化|优化到|低于|不超过|控制在|降到|降至|调整到|调整至)"
                    + ".{0,20}(?:\\d+(?:\\.\\d+)?\\s*%|[一二三四五六七八九十]+成)|"
                    + "(?:偏高|过高|too\\s+high|high).{0,35}"
                    + "(?:调整到|调整至|降到|降至|bring\\s+it\\s+down\\s+to|reduce.{0,8}to)"
                    + "\\s*\\d+(?:\\.\\d+)?\\s*%)");

    private LegacyAiAnalysisGuard() {}

    public static GuardResult validate(Map<String, Object> chartData, String analysis) {
        if (analysis == null || analysis.isBlank()) {
            return invalid("LLM 未返回可用分析文本", fallback(chartData, "未返回可用分析"));
        }

        Map<String, Object> facts = facts(chartData);
        Collection<?> unitValues = collection(facts.get("unitValues"));
        boolean unitComplete = Boolean.TRUE.equals(facts.get("unitComplete"));
        String text = Normalizer.normalize(analysis.trim(), Normalizer.Form.NFKC);

        // A complete unit contract makes a numeric unit contradiction a hard
        // failure. Incomplete coverage is also not permission to guess.
        var quantityMatches = QUANTITY_WITH_UNIT.matcher(text);
        while (quantityMatches.find()) {
            if (!unitComplete || unitValues.isEmpty() || !containsUnit(unitValues, quantityMatches.group(1))) {
                return invalid("分析使用了未被源数据证明的计数单位", fallback(chartData,
                        "单位事实不足：源数据单位为 " + unitValues + "，请按原始单位核对产量。"));
            }
        }
        var quantityClaims = QUANTITY_CLAIM.matcher(text);
        while (quantityClaims.find()) {
            if (quantityClaims.group(2) == null) {
                return invalid("数量结论未注明对应计量单位", fallback(chartData,
                        "产量和数量结论必须逐项带原始单位，不能继承前文单位。"));
            }
        }
        if ((!unitComplete || unitValues.isEmpty() || unitValues.size() > 1)
                && containsAggregateQuantity(text)) {
            return invalid("分析把不同计量单位合并汇总", fallback(chartData,
                    "检测到多种计量单位 " + unitValues + "，不能直接合计；请按单位分别查看或补充换算规则。"));
        }

        // Bind amounts inside one clause. A price/revenue in a different
        // clause must neither authorize nor invalidate a loss statement.
        for (String clause : text.split("[。！？!?；;\\r\\n]|(?<!\\d)[,，]|[,，](?!\\d)")) {
            if (!LOSS_TOKEN.matcher(clause).find()) continue;
            var numericLoss = LOSS_NUMBER.matcher(clause);
            while (numericLoss.find()) {
                if (!CLAIM_SUFFIX.matcher(clause.substring(numericLoss.end())).find()) {
                    return invalid("损耗数字缺少计量单位或币种", fallback(chartData,
                            "损耗数量必须注明原单位，金额必须注明与来源一致的币种。"));
                }
            }
            var amounts = MONEY_TOKEN.matcher(clause);
            var monetarySpans = new HashSet<String>();
            while (amounts.find()) monetarySpans.add(amounts.start(1) + ":" + amounts.end(1));
            if (monetarySpans.isEmpty()) continue;
            var checkedSpans = new HashSet<String>();
            for (Pattern pattern : new Pattern[]{LOSS_BEFORE_MONEY, MONEY_BEFORE_LOSS}) {
                var claims = pattern.matcher(clause);
                while (claims.find()) {
                    if (!authorizedAmount(claims, facts)) {
                        return invalid("损耗金额或币种无法与来源逐项核对", fallback(chartData,
                                "损耗金额需要完整、明确的来源金额及币种；不能按不良数量自行估价。"));
                    }
                    checkedSpans.add(claims.start(1) + ":" + claims.end(1));
                }
            }
            if (!checkedSpans.containsAll(monetarySpans)) {
                return invalid("分析推导了缺少单价或损耗金额依据的金额", fallback(chartData,
                        "同一表述内的损耗与其他金额无法明确区分，请分别列出依据。"));
            }
        }

        if (UNSOURCED_TARGET.matcher(text).find()
                && collection(facts.get("benchmarkValues")).isEmpty()) {
            return invalid("分析使用了未提供来源的结构判断或目标阈值", fallback(chartData,
                    "可报告已记录的成本占比；是否合理及目标值需补充历史、预算或明确行业基准。"));
        }

        return GuardResult.accepted(text);
    }

    private static Map<String, Object> facts(Map<String, Object> chartData) {
        if (chartData == null) return Collections.emptyMap();
        Object raw = chartData.get("analysisFacts");
        return raw instanceof Map<?, ?> ? (Map<String, Object>) raw : Collections.emptyMap();
    }

    private static Collection<?> collection(Object value) {
        return value instanceof Collection<?> ? (Collection<?>) value : Collections.emptyList();
    }

    private static String fallback(Map<String, Object> chartData, String reason) {
        Map<String, Object> facts = facts(chartData);
        Collection<?> units = collection(facts.get("unitValues"));
        if (!units.isEmpty()) return reason + " 已识别单位：" + units + "。";
        return reason + "。";
    }

    private static boolean containsAggregateQuantity(String text) {
        return Pattern.compile("(?is)(总计|合计|总产出|总产量|共计|总量).{0,20}\\d+(?:\\.\\d+)?")
                .matcher(text).find();
    }

    private static boolean containsUnit(Collection<?> values, String candidate) {
        return values.stream().anyMatch(value -> value != null
                && value.toString().equalsIgnoreCase(candidate));
    }

    private static boolean authorizedAmount(java.util.regex.Matcher claim, Map<String, Object> facts) {
        String currency = normalizedCurrency(claim.group(2) != null ? claim.group(2) : claim.group(6));
        String expected = normalizedCurrency(facts.get("explicitWasteCurrency"));
        if (currency == null || !currency.equals(expected)) return false;
        if (claim.group(4) != null && !currency.equals(normalizedCurrency(claim.group(4)))) return false;
        try {
            BigDecimal value = new BigDecimal((claim.group(3) != null ? claim.group(3) : claim.group(5)).replace(",", ""));
            if (value.signum() < 0) return false;
            for (Object raw : collection(facts.get("explicitWasteAmounts"))) {
                try {
                    if (new BigDecimal(String.valueOf(raw)).compareTo(value) == 0) return true;
                } catch (NumberFormatException ignored) { /* not a source amount */ }
            }
        } catch (NumberFormatException ignored) {
            // An unparsable model claim is never evidence.
        }
        return false;
    }

    private static String normalizedCurrency(Object raw) {
        if (raw == null) return null;
        return switch (raw.toString().trim().toUpperCase(Locale.ROOT)) {
            case "CNY", "RMB", "元", "块", "人民币", "¥", "￥", "YUAN" -> "CNY";
            case "USD", "$", "美元", "DOLLAR", "DOLLARS" -> "USD";
            default -> null;
        };
    }

    private static GuardResult invalid(String reason, String fallback) {
        return new GuardResult(false, reason, "分析不可用：" + fallback);
    }

    public record GuardResult(boolean usable, String reason, String text) {
        public static GuardResult accepted(String text) {
            return new GuardResult(true, null, text);
        }
    }
}
