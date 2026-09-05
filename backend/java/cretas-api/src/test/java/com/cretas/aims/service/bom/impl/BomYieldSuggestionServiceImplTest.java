package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomYieldSuggestion;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomYieldSuggestionRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomYieldSuggestionServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PRODUCT-1";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomYieldSuggestionRepository suggestionRepository;
    @Mock private YieldReportService yieldReportService;

    private BomYieldSuggestionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomYieldSuggestionServiceImpl(
                productionBatchRepository, bomRecipeRepository, suggestionRepository, yieldReportService);
    }

    @Test
    void noCurrentActiveRecipeProducesNoSuggestion() {
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-1")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.empty());

        assertTrue(service.generateForProduct(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-1").isEmpty());
        verify(suggestionRepository, never()).save(any());
    }

    /**
     * 被守的性质 (2026-09-05 owner 裁定「标准只能人改」): <b>生成建议，但一个字节都不写标准</b>。
     *
     * <p>⚠️ 这个用例的前身叫 {@code guardedSuggestionLinksRecipeAndAutoAppliesProductYield},
     * 断言的是 {@code status == APPLIED} 且 {@code recipe.overallYieldRate} 被改成 81.00 ——
     * 它没坏, 也不是恒真式, 它好好地守着一个**现在被判定为缺陷**的行为 (形态 C‴)。
     * 所以改的是断言守的东西: 从「字面上等于 81.00」抬到「标准没有被这条路径动过」。
     */
    @Test
    void suggestionIsGeneratedButNeverWritesTheStandard() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-3")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        stubSamples("0.80", "0.81", "0.82");
        when(suggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Optional<BomYieldSuggestion> result = service.generateForProduct(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-3");

        // 建议本身照常产出 —— 阳性对照: 这条路径确实跑到了底, 不是提前 return 了
        assertTrue(result.isPresent(), "建议没生成 —— 后面那些「没写标准」的断言就没有意义");
        assertEquals("RECIPE-1", result.get().getBomRecipeId());
        assertEquals(new BigDecimal("81.00"), result.get().getSuggestedYieldRate());

        // 被守的性质: 标准没被动过
        assertEquals(BomYieldSuggestion.Status.PENDING, result.get().getStatus());
        assertEquals(new BigDecimal("80.00"), recipe.getOverallYieldRate(), "标准被这条路径改写了");
        verify(bomRecipeRepository, never()).save(any());

        // 阴性对照: 「已应用」那一套痕迹一个都不许出现
        assertNotEquals(BomYieldSuggestion.Status.APPLIED, result.get().getStatus());
        assertNull(result.get().getAppliedAt(), "没有人应用过, appliedAt 不该有值");
        assertNull(result.get().getAppliedBy(), "没有人应用过, appliedBy 不该有值");
    }

    /**
     * 同一条性质，换一个幅度的样本 —— 前身是 {@code relativeChangeOverGuardRemainsPending},
     * 当时靠「相对变化 > 30% 的闸」才保持 PENDING。那道闸已随自动回写一起删除,
     * 所以这里要证明的是: <b>不论幅度大小, 标准都不被写</b> (幅度不再是判据的一部分)。
     */
    @Test
    void standardIsUntouchedRegardlessOfHowFarTheSamplesDrift() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-4")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        stubSamples("0.40", "0.41", "0.42");
        when(suggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BomYieldSuggestion result = service.generateForProduct(
                FACTORY, PRODUCT, "BATCH_COMPLETED", "B-4").orElseThrow();

        assertEquals(BomYieldSuggestion.Status.PENDING, result.getStatus());
        assertEquals(new BigDecimal("80.00"), recipe.getOverallYieldRate());
        verify(bomRecipeRepository, never()).save(any());
    }

    /**
     * 生产事故的回归用例 (F006 黄油鸡, 2026-08-19 ~ 09-01)。
     *
     * <p>自学习把标准一路推到 <b>100.00</b>, 而 100.00 正是
     * {@code ProductionPlanServiceImpl#resolveEffectiveProductYieldRate} 与
     * {@code #isMainMaterialCandidate} 里「视为未配置」的<b>哨兵值</b> ⇒ 一到 100,
     * 缺料预警就不再按出成率折算主料, 自学习把自己学没了。
     *
     * <p>⚠️ 这一条比上面两条更该长在: 上面守的是「不写」, 这一条守的是
     * 「<b>就算样本齐刷刷指向那个哨兵值, 也不写</b>」—— 它是当初真实发生的那个输入。
     */
    @Test
    void samplesPointingAtTheSentinelValueStillDoNotOverwriteTheStandard() {
        BomRecipe recipe = activeRecipe(new BigDecimal("93.75"));
        when(suggestionRepository.existsByFactoryIdAndProductTypeIdAndSourceEventTypeAndSourceEventIdAndDeletedAtIsNull(
                FACTORY, PRODUCT, "PRODUCTION_SETTLED", "S-1")).thenReturn(false);
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        stubSamples("1.00", "1.00", "1.00");
        when(suggestionRepository.save(any(BomYieldSuggestion.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        BomYieldSuggestion result = service.generateForProduct(
                FACTORY, PRODUCT, "PRODUCTION_SETTLED", "S-1").orElseThrow();

        // 建议里可以出现 100.00 (那是实测中位数, 如实记录) ——
        assertEquals(new BigDecimal("100.00"), result.getSuggestedYieldRate());
        // —— 但它绝不许落到标准上, 否则缺料预警会被这个哨兵值静默关掉
        assertEquals(new BigDecimal("93.75"), recipe.getOverallYieldRate(),
                "标准被推到了哨兵值 100.00 —— 这正是 F006 黄油鸡那次事故");
        assertEquals(BomYieldSuggestion.Status.PENDING, result.getStatus());
        verify(bomRecipeRepository, never()).save(any());
    }

    private BomRecipe activeRecipe(BigDecimal yield) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("RECIPE-1");
        recipe.setFactoryId(FACTORY);
        recipe.setProductTypeId(PRODUCT);
        recipe.setProductName("Test Product");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(yield);
        return recipe;
    }

    private void stubSamples(String... rates) {
        List<ProductionBatch> batches = java.util.stream.IntStream.range(0, rates.length)
                .mapToObj(index -> {
                    ProductionBatch batch = new ProductionBatch();
                    batch.setId((long) index + 1);
                    return batch;
                }).toList();
        when(productionBatchRepository.findRecentCompletedByFactoryAndProductType(
                eq(FACTORY), eq(PRODUCT), any(Pageable.class))).thenReturn(batches);
        for (int index = 0; index < rates.length; index++) {
            when(yieldReportService.getYield(FACTORY, (long) index + 1)).thenReturn(
                    BatchYieldDTO.builder().cumulativeYieldRate(new BigDecimal(rates[index])).build());
        }
    }
}
