package com.cretas.aims.service.indicator.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 指标计算结果 DTO — 携带值 + 计算时刻 + 缓存命中标识 + 来源标签.
 *
 * <p>{@code source} 取值约定:
 * <ul>
 *   <li>{@code "cache"} — Cached strategy, TTL 内命中</li>
 *   <li>{@code "PYTHON_ENDPOINT:*"} — Cached strategy 过期 / Realtime strategy fetch Python</li>
 *   <li>{@code "precomputed"} — Precomputed strategy 直接返 lastValue</li>
 * </ul>
 *
 * <p>{@code status} distinguishes an observed value (including a real zero) from
 * no samples, an indicator that has never been computed, and a failed fetch.
 * Record class (Java 21), 不可变。Phase 2 可扩展 alertLevel / thresholds 字段。
 *
 * @param value       指标取值
 * @param computedAt  物理计算时刻 (cache hit 时为上次 fetch 时刻)
 * @param cacheHit    {@code true} 表示从内存/数据库缓存返；{@code false} 表示新算
 * @param source      来源标签 (cache / precomputed / PYTHON_ENDPOINT:* / REAL_BUSINESS:*)
 * @param status      数据状态 (AVAILABLE / NO_DATA / NOT_COMPUTED / FAILED)
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 */
public record IndicatorValueResult(
        BigDecimal value,
        LocalDateTime computedAt,
        boolean cacheHit,
        String source,
        String status
) {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String NO_DATA = "NO_DATA";
    public static final String NOT_COMPUTED = "NOT_COMPUTED";
    public static final String FAILED = "FAILED";

    /**
     * Source-compatible constructor for existing callers.  A null value from a
     * caller that has no more specific context is treated as no data; the query
     * service uses the five-argument constructor for NOT_COMPUTED and FAILED.
     */
    public IndicatorValueResult(BigDecimal value, LocalDateTime computedAt,
                                boolean cacheHit, String source) {
        this(value, computedAt, cacheHit, source, value == null ? NO_DATA : AVAILABLE);
    }
}
