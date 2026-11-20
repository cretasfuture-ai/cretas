package com.cretas.aims.service.inventory;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/**
 * 改价超过多少需要审批 —— 按工厂配置 (纪律排查 B11, Owner 2026-11-10; V20261110_47)。
 *
 * <p>此前写死在 {@code SalesPriceAdjustmentServiceImpl}: 降价 &gt; 10% / 涨价 &gt; 20% 才审批。
 * 现在读 {@code factory_settings.price_decrease_approval_pct / price_increase_approval_pct},
 * 默认值仍是 10 / 20; 没有 settings 行、列为空、读取异常 —— 一律用默认值 (与改动前完全一致)。
 */
@Slf4j
@Component
public class PriceAdjustmentApprovalThresholds {

    public static final BigDecimal DEFAULT_DECREASE_PCT = new BigDecimal("10");
    public static final BigDecimal DEFAULT_INCREASE_PCT = new BigDecimal("20");
    /** 与列精度 NUMERIC(5,2) 对齐。 */
    public static final BigDecimal MAX_PCT = new BigDecimal("999.99");
    public static final String INVALID_CODE = "PRICE_APPROVAL_THRESHOLD_INVALID";

    /** 超过这两个百分比 (严格大于) 需要审批。 */
    public record Thresholds(BigDecimal decreasePct, BigDecimal increasePct) {
        public static final Thresholds DEFAULT = new Thresholds(DEFAULT_DECREASE_PCT, DEFAULT_INCREASE_PCT);
    }

    @Nullable
    private final FactorySettingsRepository settingsRepository;

    /** 读阈值用独立只读事务 (见 IsolatedSettingsRead); 为空 (手工构造的单测) 时直接读。 */
    @Nullable
    private final org.springframework.transaction.PlatformTransactionManager transactionManager;

    public PriceAdjustmentApprovalThresholds(@Nullable FactorySettingsRepository settingsRepository) {
        this(settingsRepository, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PriceAdjustmentApprovalThresholds(@Nullable FactorySettingsRepository settingsRepository,
                                             @Nullable org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.settingsRepository = settingsRepository;
        this.transactionManager = transactionManager;
    }

    public Thresholds forFactory(String factoryId) {
        if (settingsRepository == null || factoryId == null) {
            return Thresholds.DEFAULT;
        }
        try {
            List<Object[]> rows = com.cretas.aims.service.workflow.IsolatedSettingsRead.read(transactionManager,
                    () -> settingsRepository.findPriceAdjustmentApprovalThresholdsByFactoryId(factoryId));
            if (rows == null || rows.isEmpty()) {
                return Thresholds.DEFAULT;
            }
            Object[] r = rows.get(0);
            BigDecimal decrease = r[0] instanceof BigDecimal d && d.signum() > 0 ? d : DEFAULT_DECREASE_PCT;
            BigDecimal increase = r[1] instanceof BigDecimal i && i.signum() > 0 ? i : DEFAULT_INCREASE_PCT;
            return new Thresholds(decrease, increase);
        } catch (RuntimeException e) {
            log.warn("改价审批阈值读取失败, 用默认 10%/20%: factoryId={}, err={}", factoryId, e.getMessage());
            return Thresholds.DEFAULT;
        }
    }

    /** 配置入口用: 阈值必须 &gt; 0 且 &lt;= 999.99, 否则 400 (不静默落成默认值)。 */
    public static BigDecimal validate(Object raw, String label) {
        BigDecimal value;
        try {
            value = raw instanceof Number n ? new BigDecimal(n.toString()) : new BigDecimal(String.valueOf(raw).trim());
        } catch (RuntimeException e) {
            throw invalid(label);
        }
        if (value.signum() <= 0 || value.compareTo(MAX_PCT) > 0 || value.scale() > 2) {
            throw invalid(label);
        }
        return value;
    }

    private static BusinessException invalid(String label) {
        return new BusinessException(400, label + "必须是大于 0、不超过 999.99 的百分比 (最多两位小数)")
                .withCode(INVALID_CODE)
                .withHintTarget(label);
    }
}
