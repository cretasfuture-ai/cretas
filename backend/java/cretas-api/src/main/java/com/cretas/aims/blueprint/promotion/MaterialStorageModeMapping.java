package com.cretas.aims.blueprint.promotion;

import java.util.Map;

/**
 * The only translation from blueprint storage modes to the live material/dictionary values.
 * The live dictionary uses {@code fresh}, {@code frozen}, {@code dry}, and {@code normal}; the
 * blueprint deliberately exposes the more precise three-value vocabulary below.
 */
final class MaterialStorageModeMapping {
    record LiveValue(String code, String label) {}

    private static final Map<String, LiveValue> VALUES = Map.of(
            "FROZEN", new LiveValue("frozen", "冻货"),
            "REFRIGERATED", new LiveValue("fresh", "新鲜"),
            "AMBIENT", new LiveValue("normal", "常温"));

    private MaterialStorageModeMapping() {}

    static LiveValue require(String draftValue) {
        LiveValue value = VALUES.get(draftValue);
        if (value == null) throw new IllegalArgumentException("未映射的物料储存方式: " + draftValue);
        return value;
    }

    static Map<String, LiveValue> values() {
        return VALUES;
    }
}
