package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.cretas.aims.service.production.PlanCostVarianceService;
import com.cretas.aims.service.shortage.CostDeviationCaseWriter.Applied;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Outcome;
import com.cretas.aims.service.shortage.CostDeviationEvaluator.Reading;
import com.cretas.aims.service.yield.balance.MaterialBalanceService;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitConversionContext;
import com.cretas.aims.service.unit.UnitConversionResult;
import com.cretas.aims.service.unit.UnitUsageScene;
import org.springframework.beans.factory.annotation.Autowired;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 扫描近期计划的成本差异与物料平衡, 超阈值生成异常。某一类阈值没配就不扫这一类;
 * 算不出的计划单独计数并列出, 单张计划算失败也计数, ⛔ 都不算「没问题」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CostDeviationScanService {

    static final int LOOKBACK_DAYS = 30;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record Thresholds(BigDecimal variancePct, BigDecimal balancePct) {
    }

    public record UnknownPlan(String sourceType, String planNumber, String reason) {
    }

    public record ScanResult(boolean varianceConfigured, boolean balanceConfigured, int evaluated, int created,
                             int refreshed, int unchanged, int alreadyResolved, int within, int failed,
                             List<UnknownPlan> unknown, int notified, int notifyFailed, String thresholdSource) {
    }

    /** source: FACTORY (工厂自己配的) / DEFAULT (系统默认) / MIXED / null (都没有)。 */
    public record EffectiveThresholds(Thresholds thresholds, String source) {
    }

    private final FactorySettingsRepository settingsRepository;
    private final ProductionBatchRepository batchRepository;
    private final PlanCostVarianceService varianceService;
    private final MaterialBalanceService balanceService;
    private final ProcessSecretLlmCostScope costScope;
    private final CostDeviationCaseWriter writer;
    /** 物料平衡差异金额估价用; required=false: 纯单测可不给, 不给就维持金额未知。 */
    @Autowired(required = false)
    private PlanStandardCostLineRepository standardCostLineRepository;
    @Autowired(required = false)
    private UnitContractService unitContractService;
    private final CostDeviationNotifier notifier;
    private final CostDeviationScanDefaults defaults;

    public Thresholds thresholds(String factoryId) {
        List<Object[]> rows = settingsRepository.findCostDeviationThresholdsByFactoryId(factoryId);
        if (rows == null || rows.isEmpty()) return new Thresholds(null, null);
        return new Thresholds((BigDecimal) rows.get(0)[0], (BigDecimal) rows.get(0)[1]);
    }

    /** 工厂配的优先; 没配的那一类回退系统默认; 都没有 = null (没量到)。 */
    public EffectiveThresholds effectiveThresholds(String factoryId) {
        Thresholds t = thresholds(factoryId);
        BigDecimal v = t.variancePct() != null ? t.variancePct() : defaults.variancePct();
        BigDecimal b = t.balancePct() != null ? t.balancePct() : defaults.balancePct();
        boolean fromFactory = t.variancePct() != null || t.balancePct() != null;
        boolean fromDefault = (t.variancePct() == null && v != null) || (t.balancePct() == null && b != null);
        String source = fromFactory && fromDefault ? "MIXED" : fromFactory ? "FACTORY" : fromDefault ? "DEFAULT" : null;
        return new EffectiveThresholds(new Thresholds(v, b), source);
    }

    /** 传 null = 清除该项 (回到未配置)。 */
    public Thresholds saveThresholds(String factoryId, BigDecimal variancePct, BigDecimal balancePct) {
        for (BigDecimal v : new BigDecimal[]{variancePct, balancePct}) {
            if (v != null && (v.signum() <= 0 || v.compareTo(HUNDRED) > 0)) {
                throw new BusinessException(400, "阈值要大于 0 且不超过 100").withCode("COST_DEVIATION_THRESHOLD_INVALID");
            }
        }
        if (settingsRepository.updateCostDeviationThresholds(factoryId, variancePct, balancePct) == 0) {
            throw new BusinessException(404, "本厂还没有工厂配置记录, 请先在工厂配置页保存一次")
                    .withCode("FACTORY_SETTINGS_MISSING");
        }
        return thresholds(factoryId);
    }

    public ScanResult scan(String factoryId, String trigger) {
        EffectiveThresholds eff = effectiveThresholds(factoryId);
        Thresholds t = eff.thresholds();
        LocalDate end = LocalDate.now();
        LocalDate start = end.minusDays(LOOKBACK_DAYS);
        Tally tally = new Tally();
        if (t.variancePct() != null) scanVariance(factoryId, start, end, t.variancePct(), trigger, tally);
        if (t.balancePct() != null) scanBalance(factoryId, start, end, t.balancePct(), trigger, tally);
        return new ScanResult(t.variancePct() != null, t.balancePct() != null, tally.evaluated, tally.created,
                tally.refreshed, tally.unchanged, tally.alreadyResolved, tally.within, tally.failed, tally.unknown,
                tally.notified, tally.notifyFailed, eff.source());
    }

    private void scanVariance(String factoryId, LocalDate start, LocalDate end, BigDecimal threshold, String trigger,
                              Tally tally) {
        Map<String, ProductionBatch> plans = new LinkedHashMap<>();
        for (ProductionBatch b : batchRepository.findByFactoryIdAndCreatedAtBetween(factoryId, start.atStartOfDay(),
                end.plusDays(1).atStartOfDay())) {
            if (b.getProductionPlanId() != null) plans.putIfAbsent(b.getProductionPlanId(), b);
        }
        for (Map.Entry<String, ProductionBatch> e : plans.entrySet()) {
            if (costScope.isProtectedBatch(factoryId, e.getValue().getProductTypeId(), e.getKey())) continue;
            try {
                PlanCostVarianceDTO v = varianceService.computeForPlan(factoryId, e.getKey(), false);
                record(factoryId, CostDeviationEvaluator.evaluateCost(v, threshold), trigger, tally);
            } catch (RuntimeException ex) {
                log.warn("成本异常扫描: 计划 {} 算失败: {}", e.getKey(), ex.getMessage());
                tally.failed++;
            }
        }
    }

    private void scanBalance(String factoryId, LocalDate start, LocalDate end, BigDecimal threshold, String trigger,
                             Tally tally) {
        MaterialBalanceTableDTO table = balanceService.table(factoryId, start, end, null);
        for (MaterialBalanceTableDTO.PlanBlock block : table.getPlans()) {
            if (block.getPlanId() == null) continue; // 没挂计划的报工没有处理实例的归属
            try {
                record(factoryId, CostDeviationEvaluator.evaluateBalance(block, threshold,
                        balancePricer(factoryId, block.getPlanId())), trigger, tally);
            } catch (RuntimeException ex) {
                log.warn("损耗异常扫描: 计划 {} 写入失败: {}", block.getPlanId(), ex.getMessage());
                tally.failed++;
            }
        }
    }

    /**
     * 物料平衡差异金额的单价 (LSM-COSTBI 第 11 项): 以前物料平衡只有数量, 金额一律「未知」。
     * 用开计划时的主料单价快照, 单位换算走单位契约 (⛔ 不比字符串、不猜换算率)。
     * 多个主料 / 没有快照 / 单位换不过去 ⇒ 定不了价, 原因写进摘要, 金额仍未知。
     * 依赖缺席 (纯单测) ⇒ null, 维持旧行为。
     */
    Function<MaterialBalanceTableDTO.Row, CostDeviationEvaluator.RowPrice> balancePricer(String factoryId, String planId) {
        if (standardCostLineRepository == null || unitContractService == null) return null;
        List<PlanStandardCostLine> mains = standardCostLineRepository
                .findByFactoryIdAndPlanIdOrderByIdAsc(factoryId, planId).stream()
                .filter(l -> PlanStandardCostLine.TYPE_MATERIAL.equals(l.getLineType()))
                .filter(l -> PlanStandardCostLine.BASIS_RAW_INPUT_SHARE.equals(l.getStandardBasis()))
                .toList();
        if (mains.isEmpty()) {
            return r -> CostDeviationEvaluator.RowPrice.unknown("这张计划没有主料单价快照");
        }
        if (mains.size() > 1) {
            return r -> CostDeviationEvaluator.RowPrice.unknown("有多个主料, 不能按单一单价折算");
        }
        PlanStandardCostLine main = mains.get(0);
        if (main.getUnitPrice() == null || main.getPriceUnit() == null) {
            return r -> CostDeviationEvaluator.RowPrice.unknown("开计划时主料没有单价");
        }
        String name = main.getMaterialName() == null ? "主料" : "主料「" + main.getMaterialName() + "」";
        String basis = "按" + name + "开计划时单价估算";
        Map<String, CostDeviationEvaluator.RowPrice> byUnit = new HashMap<>();
        return r -> byUnit.computeIfAbsent(String.valueOf(r.getUnit()), u -> {
            if (r.getUnit() == null) return CostDeviationEvaluator.RowPrice.unknown("工序报工没有单位");
            UnitConversionResult one = unitContractService.convert(BigDecimal.ONE, new UnitConversionContext(
                    factoryId, null, r.getUnit(), main.getPriceUnit(), LocalDateTime.now(), UnitUsageScene.PRODUCTION,
                    12, RoundingMode.HALF_UP));
            if (one == null || !one.succeeded() || one.quantity() == null) {
                return CostDeviationEvaluator.RowPrice.unknown("报工单位 " + r.getUnit() + " 换算不到单价单位 "
                        + main.getPriceUnit());
            }
            return new CostDeviationEvaluator.RowPrice(main.getUnitPrice().multiply(one.quantity()), basis, null);
        });
    }

    private void record(String factoryId, Reading r, String trigger, Tally tally) {
        tally.evaluated++;
        if (r.outcome() == Outcome.UNKNOWN) {
            tally.unknown.add(new UnknownPlan(r.sourceType().name(), r.planNumber(), r.summary()));
            return;
        }
        Applied applied = writer.apply(factoryId, r, trigger);
        switch (applied) {
            case CREATED -> {
                tally.created++;
                notifyOwner(factoryId, r, tally);
            }
            case REFRESHED, BELOW_THRESHOLD -> tally.refreshed++;
            case UNCHANGED -> tally.unchanged++;
            case ALREADY_RESOLVED -> tally.alreadyResolved++;
            case NONE -> tally.within++;
        }
    }

    /** 派人已在创建时写入; 这里把新异常推到负责人的通知。失败计数, 不回滚、不当已送达。 */
    private void notifyOwner(String factoryId, Reading r, Tally tally) {
        try {
            if (notifier.notifyCreated(factoryId, r.sourceType(), r.planId())) tally.notified++;
            else tally.notifyFailed++;
        } catch (RuntimeException ex) {
            log.error("成本损耗异常推送失败 factory={} plan={}: {}", factoryId, r.planId(), ex.getMessage());
            tally.notifyFailed++;
        }
    }

    private static final class Tally {
        int notified;
        int notifyFailed;
        int evaluated;
        int created;
        int refreshed;
        int unchanged;
        int alreadyResolved;
        int within;
        int failed;
        final List<UnknownPlan> unknown = new ArrayList<>();
    }
}
