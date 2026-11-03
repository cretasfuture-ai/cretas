package com.cretas.aims.service.yield.balance;

import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.service.yield.balance.MaterialBalanceCalculator.Thresholds;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;

/** 工厂的物料平衡阈值 (全厂一个值)。没配置 / 读不到 → 默认 5% / 15%。 */
@Component
public class MaterialBalanceThresholdProvider {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final FactorySettingsRepository settingsRepository;

    public MaterialBalanceThresholdProvider(FactorySettingsRepository settingsRepository) {
        this.settingsRepository = settingsRepository;
    }

    public Thresholds forFactory(String factoryId) {
        if (factoryId == null) return Thresholds.DEFAULT;
        List<Object[]> rows = settingsRepository.findMaterialBalanceThresholdsByFactoryId(factoryId);
        if (rows == null || rows.isEmpty()) return Thresholds.DEFAULT;
        Object[] r = rows.get(0);
        if (r[0] == null || r[1] == null) return Thresholds.DEFAULT;
        return new Thresholds(((BigDecimal) r[0]).divide(HUNDRED), ((BigDecimal) r[1]).divide(HUNDRED));
    }

    /** 校验并保存。要求 0 < warn < red <= 100。 */
    public Thresholds save(String factoryId, BigDecimal warnPct, BigDecimal redPct) {
        if (warnPct == null || redPct == null || warnPct.signum() <= 0 || warnPct.compareTo(redPct) >= 0
                || redPct.compareTo(HUNDRED) > 0) {
            throw new com.cretas.aims.exception.BusinessException(400, "黄色阈值必须大于 0 且小于红色阈值, 红色阈值不超过 100")
                    .withCode("MATERIAL_BALANCE_THRESHOLD_INVALID");
        }
        if (settingsRepository.updateMaterialBalanceThresholds(factoryId, warnPct, redPct) == 0) {
            throw new com.cretas.aims.exception.BusinessException(404, "本厂还没有工厂配置记录, 请先在工厂配置页保存一次")
                    .withCode("FACTORY_SETTINGS_MISSING");
        }
        return forFactory(factoryId);
    }
}
