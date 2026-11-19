package com.cretas.aims.ai.tool.impl.production;

import java.util.Map;
import java.util.regex.Pattern;

/** 产线内部码 -> 面向用户的显示名；内部码不得出现在回复文本里。 */
final class ProductionLineLabels {

    private static final Map<String, String> LABELS = Map.of(
            "DEDICATED_LINE_A", "A 专线",
            "DEDICATED_LINE_B", "B 专线",
            "SHARED_LINE", "共用产线",
            "OTHER", "其他产线");

    private static final Pattern INTERNAL_CODE = Pattern.compile("\\b[A-Z]+(_[A-Z]+)+\\b");

    private ProductionLineLabels() {
    }

    static String display(String code) {
        if (code == null || code.isBlank()) {
            return "未指定产线";
        }
        String label = LABELS.get(code.trim().toUpperCase());
        if (label != null) {
            return label;
        }
        return INTERNAL_CODE.matcher(code).find() ? "未命名产线" : code;
    }
}
