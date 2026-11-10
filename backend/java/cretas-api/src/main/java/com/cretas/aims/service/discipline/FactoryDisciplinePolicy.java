package com.cretas.aims.service.discipline;

import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.ThresholdResolverService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工厂纪律口径的<b>唯一读取处</b> (纪律排查 A3 / A7 / A11 / A13 / A14, Steve 2026-11-10:
 * 是否拦、怎么拦都由工厂自己配置, 不写死)。
 *
 * <p>配置存于阈值中心 {@code factory_thresholds} (工厂行 &gt; 全局 {@code '*'} 行 &gt; 本类代码兜底),
 * 全局默认行见 {@code V20261110_81__factory_discipline_configs.sql}。本类的代码兜底与那份迁移的默认值逐一相同,
 * 只在阈值中心整行停用或读不到时才会用到。
 *
 * <p><b>字符串配置写错 (认不出的值) 一律按最严</b>: 放行规则按 {@link ReleaseMode#QC_ONLY}, 多收按
 * {@link OverReceiveMode#BLOCK}; 原始值保留在 {@code raw*} 里给拦截文案说出来, ⛔ 不静默按宽处理。
 * 数值配置为负数同样按默认值处理。
 *
 * <p>⛔ 不要在业务 service 里再直接读这些 key —— 同一个口径有两份, 它一定会漂。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FactoryDisciplinePolicy {

    // 代码兜底 = 迁移里的全局默认 (Steve 裁定的默认值)
    static final String DEFAULT_RELEASE_MODE = "QC_ONLY";
    static final int DEFAULT_RELEASE_REQUIRE_REASON = 1;
    static final int DEFAULT_REQUIRE_SETTLEMENT = 1;
    static final String DEFAULT_OVER_RECEIVE_MODE = "BLOCK";
    static final BigDecimal DEFAULT_OVER_RECEIVE_RATE = new BigDecimal("0.05");
    static final int DEFAULT_THAW_BACKFILL_MAX_MINUTES = 480;
    /** 单测里策略未注入时, 调用方用的兜底 (= 迁移默认)。 */
    public static final int DEFAULT_THAW_BACKFILL_MAX_MINUTES_PUBLIC = DEFAULT_THAW_BACKFILL_MAX_MINUTES;
    static final int DEFAULT_BACKDATE_MAX_DAYS = 2;

    private final ThresholdResolverService thresholds;

    // ===================== A3 批次放行 =====================

    public enum ReleaseMode {
        /** 只能走质检放行 (默认)。 */
        QC_ONLY,
        /** 质检放行之外, 允许指定角色手动放行。 */
        QC_OR_MANUAL
    }

    /**
     * @param manualRoles 允许手动放行的角色 (小写); 空集 = 不另限角色 (有接口权限即可)
     */
    public record ReleaseRule(ReleaseMode mode, String rawMode, Set<String> manualRoles, boolean requireReason) {
        public boolean roleMayRelease(String role) {
            if (manualRoles.isEmpty()) {
                return true;
            }
            return role != null && manualRoles.contains(role.trim().toLowerCase(Locale.ROOT));
        }
    }

    public ReleaseRule batchRelease(String factoryId) {
        String raw = thresholds.getString(factoryId, ThresholdKeys.QUALITY_BATCH_RELEASE_MODE, DEFAULT_RELEASE_MODE);
        ReleaseMode mode = parseEnum(ReleaseMode.class, raw, ReleaseMode.QC_ONLY, factoryId,
                ThresholdKeys.QUALITY_BATCH_RELEASE_MODE);
        String rolesCsv = thresholds.getString(factoryId, ThresholdKeys.QUALITY_BATCH_RELEASE_MANUAL_ROLES, "");
        Set<String> roles = rolesCsv == null ? Set.of() : Arrays.stream(rolesCsv.split("[,，\\s]+"))
                .map(String::trim)
                .filter(r -> !r.isEmpty())
                .map(r -> r.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        int requireReason = thresholds.getInteger(factoryId, ThresholdKeys.QUALITY_BATCH_RELEASE_REQUIRE_REASON,
                DEFAULT_RELEASE_REQUIRE_REASON);
        // 只有显式 0 才是「可不填」; 其它值 (含写错的 2、-1) 都按必须填
        return new ReleaseRule(mode, raw, roles, requireReason != 0);
    }

    // ===================== A7 完工必须先结单 =====================

    public boolean requireSettlementBeforeComplete(String factoryId) {
        // 只有显式 0 才是「允许直接完工」
        return thresholds.getInteger(factoryId, ThresholdKeys.PRODUCTION_COMPLETION_REQUIRE_SETTLEMENT,
                DEFAULT_REQUIRE_SETTLEMENT) != 0;
    }

    // ===================== A11 仓库多收 =====================

    public enum OverReceiveMode {
        /** 硬拦 (默认, = 现状)。 */
        BLOCK,
        /** 不超过比例时填原因 + 选责任方放行, 超出仍拦。 */
        WITHIN_RATE,
        /** 一律填原因 + 选责任方放行。 */
        ALWAYS_WITH_REASON
    }

    public record OverReceiveRule(OverReceiveMode mode, String rawMode, BigDecimal rate) {
        /** 多收是否在本厂规则允许的范围内 (允许 = 还要填原因 + 选责任方)。 */
        public boolean allows(BigDecimal reported, BigDecimal received) {
            return switch (mode) {
                case BLOCK -> false;
                case ALWAYS_WITH_REASON -> true;
                case WITHIN_RATE -> received.compareTo(maxAllowed(reported)) <= 0;
            };
        }

        public BigDecimal maxAllowed(BigDecimal reported) {
            return reported.multiply(BigDecimal.ONE.add(rate));
        }
    }

    public OverReceiveRule overReceive(String factoryId) {
        String raw = thresholds.getString(factoryId, ThresholdKeys.PRODUCTION_RECEIPT_OVER_RECEIVE_MODE,
                DEFAULT_OVER_RECEIVE_MODE);
        OverReceiveMode mode = parseEnum(OverReceiveMode.class, raw, OverReceiveMode.BLOCK, factoryId,
                ThresholdKeys.PRODUCTION_RECEIPT_OVER_RECEIVE_MODE);
        BigDecimal rate = thresholds.getBigDecimal(factoryId, ThresholdKeys.PRODUCTION_RECEIPT_OVER_RECEIVE_RATE,
                DEFAULT_OVER_RECEIVE_RATE);
        if (rate == null || rate.signum() < 0) {
            rate = DEFAULT_OVER_RECEIVE_RATE;
        }
        return new OverReceiveRule(mode, raw, rate);
    }

    // ===================== A13 解冻开始补登 =====================

    public int thawBackfillMaxMinutes(String factoryId) {
        int v = thresholds.getInteger(factoryId, ThresholdKeys.PRODUCTION_THAW_BACKFILL_MAX_MINUTES,
                DEFAULT_THAW_BACKFILL_MAX_MINUTES);
        return v < 0 ? DEFAULT_THAW_BACKFILL_MAX_MINUTES : v;
    }

    // ===================== A14 补录窗口 =====================

    /**
     * @param fallbackDays 阈值中心读不到时的兜底 (全局属性 {@code cretas.backdate.max-days})
     */
    public int backdateMaxDays(String factoryId, int fallbackDays) {
        int fallback = fallbackDays < 0 ? DEFAULT_BACKDATE_MAX_DAYS : fallbackDays;
        int v = thresholds.getInteger(factoryId, ThresholdKeys.PRODUCTION_BACKDATE_MAX_DAYS, fallback);
        return v < 0 ? fallback : v;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String raw, E strictest,
                                                   String factoryId, String key) {
        if (raw == null || raw.isBlank()) {
            return strictest;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("纪律配置认不出, 按最严处理: factoryId={} key={} value={} -> {}", factoryId, key, raw, strictest);
            return strictest;
        }
    }
}
