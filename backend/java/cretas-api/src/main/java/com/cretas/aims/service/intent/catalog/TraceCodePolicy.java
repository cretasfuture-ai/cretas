package com.cretas.aims.service.intent.catalog;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/** Single source of truth for the existing factory trace-code contract. */
public final class TraceCodePolicy {
    private static final DateTimeFormatter DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private TraceCodePolicy() { }

    public static String format(String factoryId, LocalDate date, int sequence) {
        if (factoryId == null || factoryId.isBlank()) throw new IllegalArgumentException("factoryId required");
        if (sequence < 0 || sequence > 999999) throw new IllegalArgumentException("trace sequence out of range");
        return "TRACE-" + factoryId + "-" + DATE.format(date) + "-" + String.format("%06d", sequence);
    }

    public static Map<String, Object> describe(String factoryId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", true);
        result.put("source", "SYSTEM_TRACE_CODE_POLICY");
        result.put("template", "TRACE-<factoryId>-<yyyyMMdd>-<6位>");
        result.put("example", format(factoryId, LocalDate.of(2026, 1, 2), 1));
        result.put("sequenceDigits", 6);
        return result;
    }
}
