package com.cretas.aims.entity.enums;

/**
 * Who must act on an annotation candidate.
 * CUSTOMER candidates are actionable factory QC risks. PLATFORM candidates are
 * model/geometry diagnostics and must never inflate the factory review queue.
 */
public enum LabelQcAnnotationAudience {
    CUSTOMER,
    PLATFORM
}
