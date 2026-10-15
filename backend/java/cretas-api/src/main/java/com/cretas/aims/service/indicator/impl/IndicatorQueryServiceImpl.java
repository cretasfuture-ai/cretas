package com.cretas.aims.service.indicator.impl;

import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.indicator.Indicator;
import com.cretas.aims.entity.indicator.IndicatorComputation;
import com.cretas.aims.entity.indicator.IndicatorVersion;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.indicator.IndicatorComputationRepository;
import com.cretas.aims.repository.indicator.IndicatorRepository;
import com.cretas.aims.repository.indicator.IndicatorVersionRepository;
import com.cretas.aims.service.indicator.IndicatorQueryService;
import com.cretas.aims.service.indicator.dto.IndicatorValueResult;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategy;
import com.cretas.aims.service.indicator.strategy.IndicatorComputationStrategyRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 指标查询服务实现 — Phase 1 Sprint 1 Day 4 (2026-05-22).
 *
 * <p>核心职责: 按 {@link Indicator#getComputeStrategy()} 字段分支选择计算路径:
 * REALTIME / PRECOMPUTED / CACHED. 见 {@link #computeForCode}.
 *
 * <p>Phase 2B Python 端点真实接入前，{@link PythonSmartBIClient#fetchIndicatorValue} 为 stub
 * 返 {@link BigDecimal#ZERO}; 本 Service 不感知 stub vs 真实 — 调用方约定后续 Phase 2B 替换 stub
 * 时本 Service 不需改动。
 *
 * <p>每次 fetch Python (cached 过期 / realtime) 会落 {@link IndicatorVersion} 快照，
 * 用于历史趋势分析。precomputed 不落 version (由 scheduler 维护已经写过).
 *
 * @author Cretas Team
 * @since 2026-05-22 (Phase 1 Sprint 1 Day 4)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IndicatorQueryServiceImpl implements IndicatorQueryService {

    private final IndicatorRepository indicatorRepository;
    private final IndicatorVersionRepository indicatorVersionRepository;
    private final IndicatorComputationRepository indicatorComputationRepository;
    private final PythonSmartBIClient pythonSmartBIClient;
    private final IndicatorComputationStrategyRegistry strategyRegistry;

    /** {@inheritDoc} */
    @Override
    @Transactional
    public IndicatorValueResult computeForCode(String code, String factoryId,
                                               LocalDate periodStart, LocalDate periodEnd) {
        if (code == null || factoryId == null) {
            throw new BusinessException("code 和 factoryId 不能为空");
        }
        Indicator ind = indicatorRepository
                .findByCodeAndFactoryIdAndDeletedAtIsNull(code, factoryId)
                .orElseThrow(() -> new BusinessException(
                        "Indicator not found: code=" + code + ", factoryId=" + factoryId));

        String strategy = ind.getComputeStrategy();
        log.debug("computeForCode: code={}, factoryId={}, strategy={}", code, factoryId, strategy);

        return switch (strategy == null ? "" : strategy) {
            case "REALTIME"    -> computeRealtime(ind, factoryId, periodStart, periodEnd);
            case "PRECOMPUTED" -> computePrecomputed(ind);
            case "CACHED"      -> computeCached(ind, factoryId, periodStart, periodEnd);
            default            -> throw new BusinessException(
                    "Unknown computeStrategy: " + strategy + " (indicator code=" + code + ")");
        };
    }

    /** {@inheritDoc} */
    @Override
    public List<Indicator> listByCategory(String factoryId, String category) {
        if (factoryId == null || category == null) {
            throw new BusinessException("factoryId 和 category 不能为空");
        }
        return indicatorRepository.findByFactoryIdAndCategoryAndIsActiveTrueAndDeletedAtIsNull(
                factoryId, category);
    }

    /** {@inheritDoc} */
    @Override
    public Indicator getById(String id, String factoryId) {
        if (id == null || factoryId == null) {
            throw new BusinessException("id 和 factoryId 不能为空");
        }
        return indicatorRepository.findByIdAndFactoryIdAndDeletedAtIsNull(id, factoryId)
                .orElseThrow(() -> new BusinessException(
                        "Indicator not found: id=" + id + ", factoryId=" + factoryId));
    }

    // ====================================================================
    // 策略分支实现
    // ====================================================================

    /**
     * REALTIME — 每次都调 Python, 不读 cache 也不写 lastValue (保留 lastValue 给 scheduler).
     * 仍落 version 快照, 用于审计 / 趋势.
     *
     * <p>Phase B step 5: ratio strategies 无样本返 null → skip saveVersion (column NOT NULL).
     */
    IndicatorValueResult computeRealtime(Indicator ind, String factoryId,
                                          LocalDate start, LocalDate end) {
        FetchOutcome outcome = fetchFromPython(ind, factoryId, start, end);
        LocalDateTime computedAt = computedAtFor(outcome);
        if (IndicatorValueResult.AVAILABLE.equals(outcome.status())) {
            saveVersion(ind, factoryId, outcome.value(), start, end, computedAt, outcome.sourceLabel());
        }
        return toResult(outcome, computedAt, false);
    }

    /**
     * PRECOMPUTED — 直接返 lastValue (Phase 2C scheduler 维护). 不调 Python 不落 version.
     * 如果 lastValue 为 null (scheduler 还没跑过), 保持 null 和真实的计算时间，
     * 让前端展示 "—" 而不是伪造 0 或当前时间.
     */
    IndicatorValueResult computePrecomputed(Indicator ind) {
        BigDecimal value = ind.getLastValue();
        LocalDateTime computedAt = ind.getLastComputedAt();
        String status = value != null
                ? IndicatorValueResult.AVAILABLE
                : (computedAt == null ? IndicatorValueResult.NOT_COMPUTED : IndicatorValueResult.NO_DATA);
        return new IndicatorValueResult(value, computedAt, true, "precomputed", status);
    }

    /**
     * CACHED — TTL 内复用 lastValue, 过期 fetch Python + 落 version + 更新 lastValue.
     * 默认 TTL = ind.cacheTtlSeconds (Indicator entity field, default 3600s).
     * 若该字段为 null, 视为永久缓存 (不重算).
     */
    IndicatorValueResult computeCached(Indicator ind, String factoryId,
                                        LocalDate start, LocalDate end) {
        Integer ttl = ind.getCacheTtlSeconds();
        LocalDateTime lastComputed = ind.getLastComputedAt();
        BigDecimal lastValue = ind.getLastValue();

        // Cache hit: lastValue 存在 + 在 TTL 内 (lastComputed null 时 ttl 视为永久缓存 if value 存在)
        boolean withinTtl = lastComputed != null && ttl != null &&
                lastComputed.isAfter(LocalDateTime.now().minusSeconds(ttl));
        if (lastValue != null && withinTtl) {
            return new IndicatorValueResult(lastValue, lastComputed, true, "cache",
                    IndicatorValueResult.AVAILABLE);
        }

        // Cache miss / 过期: fetch + 更新 lastValue + 落 version
        // Phase B step 5: ratio strategies 无样本返 null → skip persistence
        // (column NOT NULL + Rule 21 honest data 让 UI 渲 "—" 而非 "0%" 误导)
        FetchOutcome outcome = fetchFromPython(ind, factoryId, start, end);
        LocalDateTime computedAt = computedAtFor(outcome);
        if (IndicatorValueResult.AVAILABLE.equals(outcome.status())) {
            ind.setLastValue(outcome.value());
            ind.setLastComputedAt(computedAt);
            indicatorRepository.save(ind);
            saveVersion(ind, factoryId, outcome.value(), start, end, computedAt, outcome.sourceLabel());
        }
        return toResult(outcome, computedAt, false);
    }

    /**
     * 单次 fetch 返回结果 — value + compute_source label + status (避免 saveVersion
     * 重复查 repo，也不把配置错误或无样本伪装成零).
     */
    record FetchOutcome(BigDecimal value, String sourceLabel, String status) {}

    private static LocalDateTime computedAtFor(FetchOutcome outcome) {
        return IndicatorValueResult.AVAILABLE.equals(outcome.status())
                ? LocalDateTime.now() : null;
    }

    private static IndicatorValueResult toResult(FetchOutcome outcome,
                                                  LocalDateTime computedAt,
                                                  boolean cacheHit) {
        return new IndicatorValueResult(outcome.value(), computedAt, cacheHit,
                outcome.sourceLabel(), outcome.status());
    }

    /**
     * 调 Python 端点取值 — 走 IndicatorComputation 主策略 (priority 最低的启用条目).
     *
     * <p>Sprint 12 Phase B: 新增 {@code JPA_AGGREGATE} 分支, 走
     * {@link IndicatorComputationStrategyRegistry} 找 Java strategy 真接业务表 SQL aggregate.
     * 跟 PYTHON_ENDPOINT 平级 — Registry 找不到 strategy 标记 FAILED 不抛错.
     *
     * <p>若没有启用策略 / 主策略类型未识别, 返回 FAILED 并 log warn — 不抛错，
     * 但不把配置错误伪装成业务零.
     */
    FetchOutcome fetchFromPython(Indicator ind, String factoryId,
                                  LocalDate start, LocalDate end) {
        List<IndicatorComputation> strategies = indicatorComputationRepository
                .findByIndicatorIdAndIsActiveTrueAndDeletedAtIsNull(ind.getId());
        if (strategies.isEmpty()) {
            log.warn("fetchFromPython: no active computation for indicator id={}, code={}",
                    ind.getId(), ind.getCode());
            return new FetchOutcome(null, "UNKNOWN:" + ind.getCode(), IndicatorValueResult.FAILED);
        }
        IndicatorComputation primary = strategies.get(0);
        String type = primary.getComputeType();

        // Sprint 12 Phase B: JPA_AGGREGATE 走 Java strategy 真接业务表
        if ("JPA_AGGREGATE".equals(type)) {
            Optional<IndicatorComputationStrategy> strategyOpt = strategyRegistry.find(ind.getCode());
            if (strategyOpt.isEmpty()) {
                log.warn("fetchFromPython: JPA_AGGREGATE configured but no strategy registered for code={} — returning FAILED",
                        ind.getCode());
                return new FetchOutcome(null, "REAL_BUSINESS_MISSING:" + ind.getCode(),
                        IndicatorValueResult.FAILED);
            }
            try {
                BigDecimal value = strategyOpt.get().compute(factoryId, start, end);
                return outcomeFor(value, "REAL_BUSINESS:" + ind.getCode());
            } catch (RuntimeException ex) {
                log.warn("fetchFromPython: real business strategy failed for code={}: {}",
                        ind.getCode(), ex.getMessage());
                return new FetchOutcome(null, "REAL_BUSINESS:" + ind.getCode(),
                        IndicatorValueResult.FAILED);
            }
        }

        if ("PYTHON_ENDPOINT".equals(type)) {
            try {
                BigDecimal value = pythonSmartBIClient.fetchIndicatorValue(
                        factoryId, primary.getComputeSource(), start, end);
                return outcomeFor(value, "PYTHON_ENDPOINT:" + ind.getCode());
            } catch (RuntimeException ex) {
                log.warn("fetchFromPython: Python endpoint failed for code={}: {}",
                        ind.getCode(), ex.getMessage());
                return new FetchOutcome(null, "PYTHON_ENDPOINT:" + ind.getCode(),
                        IndicatorValueResult.FAILED);
            }
        }

        log.warn("fetchFromPython: unrecognized computeType={} for code={} — returning FAILED",
                type, ind.getCode());
        return new FetchOutcome(null, "UNKNOWN:" + ind.getCode(), IndicatorValueResult.FAILED);
    }

    private static FetchOutcome outcomeFor(BigDecimal value, String sourceLabel) {
        return new FetchOutcome(value, sourceLabel,
                value == null ? IndicatorValueResult.NO_DATA : IndicatorValueResult.AVAILABLE);
    }

    /**
     * 持久化历史快照 — append-only.
     *
     * <p>Sprint 12 Phase B: 调用方从 {@link FetchOutcome#sourceLabel()} 传 compute_source,
     * 按主策略类型 (JPA_AGGREGATE / PYTHON_ENDPOINT / UNKNOWN) 区分, 满足 Rule 21 mock-vs-real 隔离.
     *
     * <p>Sprint 12 Phase B step 3 hotfix: {@code period_start}/{@code period_end} DB schema 是
     * NOT NULL, scheduler 跟 HTTP caller 都可能传 null (REAL_BUSINESS strategies 用自己 default
     * MTD / instant snapshot, 不依赖 caller period). 默认 null → 今日, 让 version 行能落库,
     * "today/today" 单日窗口反映 "this is the point-in-time captured value", 跟 strategy
     * 的内部 MTD/instant 语义 reconcile 的责任留给 downstream 趋势分析.
     */
    void saveVersion(Indicator ind, String factoryId, BigDecimal value,
                     LocalDate start, LocalDate end, LocalDateTime computedAt,
                     String computeSourceLabel) {
        LocalDate effectiveStart = start != null ? start : LocalDate.now();
        LocalDate effectiveEnd = end != null ? end : LocalDate.now();
        IndicatorVersion version = new IndicatorVersion();
        version.setFactoryId(factoryId);
        version.setIndicatorId(ind.getId());
        version.setPeriodStart(effectiveStart);
        version.setPeriodEnd(effectiveEnd);
        version.setValue(value);
        version.setComputedAt(computedAt);
        version.setComputeSource(computeSourceLabel);
        indicatorVersionRepository.save(version);
    }
}
