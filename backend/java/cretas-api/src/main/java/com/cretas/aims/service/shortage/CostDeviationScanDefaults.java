package com.cretas.aims.service.shortage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 工厂没配阈值时的系统默认阈值 (配置项, 默认不设):
 * {@code cretas.cost-deviation-scan.default-variance-pct} / {@code default-balance-pct}。
 * 不设 = 没有默认值, 工厂没配的那一类记「没量到」, ⛔ 不静默跳过也不当正常。
 * 设了的值必须 0 < v <= 100, 否则视为没有默认值 (并在原因里说明)。
 */
@Component
public class CostDeviationScanDefaults {

    private final BigDecimal variancePct;
    private final BigDecimal balancePct;

    public CostDeviationScanDefaults(
            @Value("${cretas.cost-deviation-scan.default-variance-pct:}") String variance,
            @Value("${cretas.cost-deviation-scan.default-balance-pct:}") String balance) {
        this.variancePct = parse(variance);
        this.balancePct = parse(balance);
    }

    public static CostDeviationScanDefaults none() {
        return new CostDeviationScanDefaults("", "");
    }

    public BigDecimal variancePct() {
        return variancePct;
    }

    public BigDecimal balancePct() {
        return balancePct;
    }

    private static BigDecimal parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            BigDecimal v = new BigDecimal(s.trim());
            return v.signum() > 0 && v.compareTo(new BigDecimal("100")) <= 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
