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
 * 全局默认行见 {@code V20261111_81__factory_discipline_configs.sql}。本类的代码兜底与那份迁移的默认值逐一相同,
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
        /** 不超过比例时放行, 超出仍拦。 */
        WITHIN_RATE,
        /**
         * 不设上限一律放行。注意: 与少收同口径, 差异在称重容差内不要原因 (记为称重误差),
         * 超出容差才必须填差异原因 + 选责任方 —— 名字里的 WITH_REASON 指的是超出容差的部分。
         */
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

    // ===================== 缺料实例收尾 (2026-11-11) =====================

    public enum ShortageCloseMode {
        /** 停在「缺口已解决」, 生产主管手工关闭 (默认, = 原行为)。 */
        MANUAL,
        /** 缺口补到 0 时系统直接关闭并留痕。 */
        AUTO
    }

    /** 认不出 / 没配 = MANUAL (保持原行为; 自动关闭是工厂主动选的)。 */
    public ShortageCloseMode shortageCloseMode(String factoryId) {
        String raw = thresholds.getString(factoryId, ThresholdKeys.SHORTAGE_CASE_CLOSE_MODE, "MANUAL");
        return parseEnum(ShortageCloseMode.class, raw, ShortageCloseMode.MANUAL, factoryId,
                ThresholdKeys.SHORTAGE_CASE_CLOSE_MODE);
    }

    // ===================== 包装单位计划换线的产能核对 (2026-11-11) =====================

    public enum PackageUnitCapacityMode {
        /** 只认 件 / kg / 批, 包装单位判未验证 (默认, = 原行为)。 */
        NONE,
        /** 按产品净含量折成 kg 再核对 kg 产能。 */
        NET_CONTENT,
        /** 产线产能可按工厂自己的包装单位配置。 */
        FACTORY_UNIT
    }

    /** 认不出 / 没配 = NONE (保持原行为, 不猜不换算)。 */
    public PackageUnitCapacityMode packageUnitCapacityMode(String factoryId) {
        String raw = thresholds.getString(factoryId, ThresholdKeys.PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE, "NONE");
        return parseEnum(PackageUnitCapacityMode.class, raw, PackageUnitCapacityMode.NONE, factoryId,
                ThresholdKeys.PRODUCTION_CAPACITY_PACKAGE_UNIT_MODE);
    }

    // ===================== 动态质检表整张判 FAIL 的范围 (2026-11-11) =====================

    public enum InspectionFailScope {
        /** 任一项不合格整张 FAIL (原行为; 也是认不出时的最严口径)。 */
        ANY_ITEM,
        /** 关键 / 主要项不合格整张 FAIL; 只有次要项不合格 ⇒ 条件合格, 交质量经理决定 (默认)。 */
        MINOR_FOR_REVIEW,
        /** 只有关键项不合格整张 FAIL; 主要 / 次要项不合格 ⇒ 条件合格, 交质量经理决定。 */
        CRITICAL_ONLY;

        /** 这个严重程度的项不合格, 是否判整张 FAIL。没标严重程度 (null) 按关键项算 —— 不知道多严重就按最严。 */
        public boolean failsWholeSheet(com.cretas.aims.entity.enums.QualitySeverity severity) {
            if (severity == null || severity == com.cretas.aims.entity.enums.QualitySeverity.CRITICAL) {
                return true;
            }
            return switch (this) {
                case ANY_ITEM -> true;
                case MINOR_FOR_REVIEW -> severity != com.cretas.aims.entity.enums.QualitySeverity.MINOR;
                case CRITICAL_ONLY -> false;
            };
        }
    }

    /** 默认 = Steve 裁定的 MINOR_FOR_REVIEW (= 迁移全局默认行); 写错的值按最严 ANY_ITEM。 */
    public static final InspectionFailScope DEFAULT_INSPECTION_FAIL_SCOPE = InspectionFailScope.MINOR_FOR_REVIEW;

    public InspectionFailScope inspectionFailScope(String factoryId) {
        String raw = thresholds.getString(factoryId, ThresholdKeys.QUALITY_INSPECTION_FAIL_SCOPE,
                DEFAULT_INSPECTION_FAIL_SCOPE.name());
        if (raw == null || raw.isBlank()) {
            return DEFAULT_INSPECTION_FAIL_SCOPE;
        }
        return parseEnum(InspectionFailScope.class, raw, InspectionFailScope.ANY_ITEM, factoryId,
                ThresholdKeys.QUALITY_INSPECTION_FAIL_SCOPE);
    }

    // ===================== 插单一律走审批 (1551-11, 2026-11-11) =====================

    /** 只有显式 1 才开 (默认 0 = 原行为: 只有预览判定需要审批的插单走画布); 写错的值按关。 */
    public boolean urgentInsertAlwaysApproval(String factoryId) {
        return thresholds.getInteger(factoryId, ThresholdKeys.PRODUCTION_URGENT_INSERT_ALWAYS_APPROVAL, 0) == 1;
    }

    // ===================== 销售单财务批准后取消 (2026-11-11) =====================

    /** 只有显式 1 才开 (默认 0 = 原行为: 批准后只能驳回 / 退款); 写错的值按关。 */
    public boolean cancelApprovedSalesOrderAllowed(String factoryId) {
        return thresholds.getInteger(factoryId, ThresholdKeys.SALES_ORDER_CANCEL_AFTER_APPROVAL, 0) == 1;
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
