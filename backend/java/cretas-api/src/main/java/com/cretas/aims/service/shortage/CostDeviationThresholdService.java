package com.cretas.aims.service.shortage;

import com.cretas.aims.repository.FactorySettingsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 「发现的问题」成本 / 损耗告警阈值的唯一解析处 (工厂配的优先, 没配的那一类回退系统默认, 都没有 = null)。
 * 只返回阈值配置数值 (百分数) 与来源, 不读任何批次、计划、金额、偏差等业务数据。
 *
 * <p>{@link CostDeviationScanService#thresholds} / {@link CostDeviationScanService#effectiveThresholds} 委托这里,
 * 扫描与 App 阈值接口口径不变。对外 (AI 工具) 只开放 {@link #balanceAlert}: 物料平衡 (损耗) 一类的阈值与来源,
 * 供对话「损耗异常」与问题页用同一条线 (BP 问题 28)。受控数据闸里本类登记为 INTERNAL (只含配置数值)。</p>
 */
@Service
@RequiredArgsConstructor
public class CostDeviationThresholdService {

    /** source 取值。 */
    public static final String FACTORY = "FACTORY";
    public static final String DEFAULT = "DEFAULT";

    /** 物料平衡 (损耗) 告警阈值: pct 百分数 (如 3); source = FACTORY 本厂配置 / DEFAULT 系统默认; 都没有时两者皆 null。 */
    public record BalanceAlert(BigDecimal pct, String source) {
    }

    record Configured(BigDecimal variancePct, BigDecimal balancePct) {
    }

    record Effective(BigDecimal variancePct, BigDecimal balancePct, String source) {
    }

    private final FactorySettingsRepository settingsRepository;
    private final CostDeviationScanDefaults defaults;

    /** 本厂自己配的阈值 (各自可空 = 未配置)。 */
    Configured configured(String factoryId) {
        List<Object[]> rows = settingsRepository.findCostDeviationThresholdsByFactoryId(factoryId);
        if (rows == null || rows.isEmpty()) return new Configured(null, null);
        return new Configured((BigDecimal) rows.get(0)[0], (BigDecimal) rows.get(0)[1]);
    }

    /** 生效阈值; source: FACTORY / DEFAULT / MIXED / null (都没有)。 */
    Effective effective(String factoryId) {
        Configured t = configured(factoryId);
        BigDecimal v = t.variancePct() != null ? t.variancePct() : defaults.variancePct();
        BigDecimal b = t.balancePct() != null ? t.balancePct() : defaults.balancePct();
        boolean fromFactory = t.variancePct() != null || t.balancePct() != null;
        boolean fromDefault = (t.variancePct() == null && v != null) || (t.balancePct() == null && b != null);
        String source = fromFactory && fromDefault ? "MIXED" : fromFactory ? FACTORY : fromDefault ? DEFAULT : null;
        return new Effective(v, b, source);
    }

    /** 物料平衡 (损耗) 一类的生效阈值与它自己的来源 (不受成本差异一类是否配置影响)。 */
    public BalanceAlert balanceAlert(String factoryId) {
        BigDecimal own = configured(factoryId).balancePct();
        if (own != null) return new BalanceAlert(own, FACTORY);
        BigDecimal def = defaults.balancePct();
        return def != null ? new BalanceAlert(def, DEFAULT) : new BalanceAlert(null, null);
    }
}
