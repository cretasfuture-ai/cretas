package com.cretas.aims.ai.tool;

import java.time.LocalDate;
import java.util.Map;

/** Inclusive dates for tools that query a material batch's expiry date. */
public record ExpiryDateWindow(LocalDate from, LocalDate to) {

    public static ExpiryDateWindow resolve(Map<String, Object> params, LocalDate defaultFrom, LocalDate defaultTo) {
        LocalDate from = TimeRangeFilter.parseDate(params.get("startDate"), "startDate");
        LocalDate to = TimeRangeFilter.parseDate(params.get("endDate"), "endDate");
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        return new ExpiryDateWindow(from == null ? defaultFrom : from, to == null ? defaultTo : to);
    }

    public boolean includes(LocalDate date) {
        return date != null && (from == null || !date.isBefore(from)) && !date.isAfter(to);
    }

    public static void declareParameters(Map<String, Object> properties) {
        properties.put("startDate", TimeRangeFilter.dateProperty("到期日筛选起点（含当天）"));
        properties.put("endDate", TimeRangeFilter.dateProperty("到期日筛选终点（含当天）"));
    }
}
