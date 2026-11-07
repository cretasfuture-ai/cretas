package com.cretas.aims.service.execution;

import java.time.LocalDate;
import java.util.Map;
import java.util.regex.Pattern;

/** Shared parameter policy for the existing sales ranking Tool; no intent labels or model calls. */
public final class SalesRankingParameters {
    public static final String INTENT = "PRODUCT_SALES_RANKING";
    public static final String TOOL = "product_sales_ranking";
    private static final Pattern LIMIT = Pattern.compile("前([0-9]+|[零一二两三四五六七八九十百千]+)(?:个|名|条)?");
    private SalesRankingParameters() { }

    public static void apply(String input, Map<String, Object> params, LocalDate today) {
        String text = input == null ? "" : input;
        // Only positive, scoped metric phrases win. '不是金额，按销量' is a correction, not cancellation.
        var metrics = Pattern.compile("(?:按|看|查)(?:销售)?(数量|销量|金额)").matcher(text);
        while (metrics.find()) {
            String prefix = text.substring(Math.max(0, metrics.start() - 2), metrics.start());
            if (prefix.matches(".*(?:不|别|不要|不是)$")) continue;
            params.put("metric", "金额".equals(metrics.group(1)) ? "amount" : "quantity");
        }
        if (text.matches("销量|数量") || text.contains("销量排行") || text.contains("销量排名")) params.put("metric", "quantity");
        if (text.equals("金额")) params.put("metric", "amount");
        String month = null;
        var months = Pattern.compile("上个月|上月|本月|这个月").matcher(text);
        while (months.find()) {
            String prefix = text.substring(Math.max(0, months.start() - 2), months.start());
            if (!prefix.matches(".*(?:不是|不要|不看)$")) month = months.group();
        }
        if ("上个月".equals(month) || "上月".equals(month)) {
            LocalDate first = today.withDayOfMonth(1);
            params.put("startDate", first.minusMonths(1).toString());
            params.put("endDate", first.minusDays(1).toString());
        } else if (month != null) {
            params.put("startDate", today.withDayOfMonth(1).toString());
            params.put("endDate", today.toString());
        }
        var matcher = LIMIT.matcher(text);
        if (matcher.find()) {
            String raw = matcher.group(1);
            var n = raw.matches("[0-9]+") ? new java.math.BigDecimal(raw)
                    : FollowUpResolver.parseQuantityAllowingZero(raw);
            // Keep the complete number. Out-of-range values are clarified at dispatch,
            // never silently truncated (e.g. 1000 -> 100) or clamped into a different request.
            if (n != null) params.put("limit", n.compareTo(java.math.BigDecimal.valueOf(100)) > 0
                    ? n : n.intValueExact());
        }
        defaults(params, today);
    }

    public static void defaults(Map<String,Object> params, LocalDate today) {
        // Only absent dates default. Existing explicit dates (including those resolved by the
        // standard preprocessor/LLM) retain their meaning; partial ranges are never guessed.
        if (!params.containsKey("startDate") && !params.containsKey("endDate")) {
            params.put("startDate", today.withDayOfMonth(1).toString());
            params.put("endDate", today.toString());
        }
        params.putIfAbsent("limit", 20);
        params.putIfAbsent("metric", "amount");
    }
}
