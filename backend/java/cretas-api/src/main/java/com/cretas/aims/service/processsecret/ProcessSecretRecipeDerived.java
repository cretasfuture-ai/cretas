package com.cretas.aims.service.processsecret;

import java.util.Locale;
import java.util.Set;

/**
 * 落库后认回「按配方自动算出」的消耗行。报工自动带出的调料 / 包材 (source_type = SEASONING / PACKAGING)
 * 和批次完工的 BOM 自动扣料 (AUTO_BOM) 的数量 = 配比 × 投入量, 读出来时和内存里的 automatic=true 行同一口径
 * (独立审查 V2.1 M4)。人工 / 显式投料一律写 RAW_MATERIAL, 差异调整写 ADJUSTMENT, 都是实际值。
 *
 * <p>出口把这样的行 (或合计里含这样的行的对象) 标上 {@code automatic=true},
 * 由 {@link ProcessSecretPayloadScrubber} 统一按配比类裁剪。</p>
 */
public final class ProcessSecretRecipeDerived {

    public static final Set<String> CONSUMPTION_SOURCE_TYPES = Set.of("SEASONING", "PACKAGING", "AUTO_BOM");

    private ProcessSecretRecipeDerived() {
    }

    public static boolean isAutomaticConsumption(String sourceType) {
        return sourceType != null
                && CONSUMPTION_SOURCE_TYPES.contains(sourceType.trim().toUpperCase(Locale.ROOT));
    }
}
