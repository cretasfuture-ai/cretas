package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.service.bom.BomYieldSuggestionService;
import com.cretas.aims.service.yield.YieldReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class BomYieldSuggestionServiceImpl implements BomYieldSuggestionService {

    static final int MIN_SAMPLES = 3;
    static final BigDecimal MAX_OFFSET_PERCENTAGE_POINTS = new BigDecimal("30.00");
    static final String SYSTEM_GENERATOR = "SYSTEM_AUTO_YIELD_SELF_LEARN";

    private final ProductionBatchRepository productionBatchRepository;
    private final BomRecipeRepository bomRecipeRepository;
    private final BomYieldSuggestionRepository bomYieldSuggestionRepository;
    private final YieldReportService yieldReportService;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<BomYieldSuggestion> generateForProduct(
            String factoryId, String productTypeId, String sourceEventType, String sourceEventId) {
        if (isBlank(factoryId) || isBlank(productTypeId) || isBlank(sourceEventType) || isBlank(sourceEventId)) {
            return Optional.empty();
        }
        if (bomYieldSuggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                factoryId, productTypeId, sourceEventType, sourceEventId)) {
            return Optional.empty();
        }

        Optional<BomRecipe> currentRecipe = bomRecipeRepository
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                        factoryId, productTypeId, BomRecipe.Status.ACTIVE);
        if (currentRecipe.isEmpty()) {
            log.warn("[BomYieldSuggestion] no current ACTIVE recipe: factoryId={}, productTypeId={}",
                    factoryId, productTypeId);
            return Optional.empty();
        }

        List<BigDecimal> samples = collectYieldPercentSamples(factoryId, productTypeId);
        if (samples.size() < MIN_SAMPLES) {
            return Optional.empty();
        }
        BigDecimal initialMedian = computeMedian(samples);
        List<BigDecimal> filtered = samples.stream()
                .filter(sample -> sample.subtract(initialMedian).abs()
                        .compareTo(MAX_OFFSET_PERCENTAGE_POINTS) <= 0)
                .toList();
        if (filtered.size() < MIN_SAMPLES) {
            return Optional.empty();
        }

        BomRecipe recipe = currentRecipe.get();
        BomYieldSuggestion suggestion = new BomYieldSuggestion();
        suggestion.setFactoryId(factoryId);
        suggestion.setProductTypeId(productTypeId);
        suggestion.setProductName(recipe.getProductName());
        suggestion.setBomRecipeId(recipe.getId());
        suggestion.setPreviousYieldRate(recipe.getOverallYieldRate());
        suggestion.setSuggestedYieldRate(computeMedian(filtered));
        suggestion.setSampleCount(filtered.size());
        suggestion.setExcludedSampleCount(samples.size() - filtered.size());
        suggestion.setGuardMaxOffsetPercent(MAX_OFFSET_PERCENTAGE_POINTS);
        suggestion.setStatus(BomYieldSuggestion.Status.PENDING);
        suggestion.setSourceEventType(sourceEventType);
        suggestion.setSourceEventId(sourceEventId);
        suggestion.setGeneratedBy(SYSTEM_GENERATOR);
        suggestion.setGeneratedAt(LocalDateTime.now());

        // ⛔ 生成到 PENDING 就结束 —— 建议**不再**自动写回 bom_recipes.overall_yield_rate。
        //
        // 2026-09-05 owner 裁定「标准只能人改」。删掉的那段 autoApplyIfGuarded 在生产上
        // 已经改过 12 次 (F006 黄油鸡, appliedBy=SYSTEM), 轨迹:
        //     (空) → 93.75 → 93.75 → 93.75 → 96.88 → 100.00 → 之后 6 次全停在 100.00
        //
        // 🔴 它不是「漂了两个点」, 是漂到了一个**让机制静默失效**的值:
        //    100.00 同时是 ProductionPlanServiceImpl#resolveEffectiveProductYieldRate 与
        //    #isMainMaterialCandidate 里「视为未配置」的哨兵 ⇒ 自学习一旦把值推到 100,
        //    缺料预警就再也不按出成率折算主料了。自学习把自己学没了。
        //    ⚠️ 教训: 一个**合法取值**同时被当哨兵用, 任何能到达它的过程都会静默关掉依赖它的机制。
        //
        // 人工写入那条路仍在, 且本来就带留痕: BomYieldEstimateServiceImpl#recalculateApply
        // (记 BomChangeLog + 操作人 + 过期校验), 权限见
        // PermissionServiceImpl#YIELD_STANDARD_WRITE_PERMISSION。
        return Optional.of(bomYieldSuggestionRepository.save(suggestion));
    }

    private List<BigDecimal> collectYieldPercentSamples(String factoryId, String productTypeId) {
        List<ProductionBatch> batches = productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                factoryId, productTypeId, Pageable.unpaged());
        List<BigDecimal> samples = new ArrayList<>();
        for (ProductionBatch batch : batches) {
            try {
                BatchYieldDTO yield = yieldReportService.getYield(factoryId, batch.getId());
                if (yield != null && yield.getCumulativeYieldRate() != null) {
                    samples.add(yield.getCumulativeYieldRate()
                            .multiply(BigDecimal.valueOf(100))
                            .setScale(2, RoundingMode.HALF_UP));
                }
            } catch (Exception exception) {
                log.warn("[BomYieldSuggestion] batch yield unavailable: factoryId={}, batchId={}, error={}",
                        factoryId, batch.getId(), exception.getMessage());
            }
        }
        return samples;
    }

    BigDecimal computeMedian(List<BigDecimal> samples) {
        List<BigDecimal> sorted = new ArrayList<>(samples);
        Collections.sort(sorted);
        int size = sorted.size();
        BigDecimal median = size % 2 == 1
                ? sorted.get(size / 2)
                : sorted.get(size / 2 - 1).add(sorted.get(size / 2))
                        .divide(BigDecimal.valueOf(2), 10, RoundingMode.HALF_UP);
        return median.setScale(2, RoundingMode.HALF_UP);
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
