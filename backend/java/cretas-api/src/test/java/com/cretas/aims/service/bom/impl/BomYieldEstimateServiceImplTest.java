package com.cretas.aims.service.bom.impl;

import com.cretas.aims.dto.bom.BomYieldApplyRequest;
import com.cretas.aims.dto.bom.BomYieldApplyResultDTO;
import com.cretas.aims.dto.bom.BomYieldPreviewItemDTO;
import com.cretas.aims.dto.yield.BatchYieldDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomChangeLog;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.exception.BomYieldStaleException;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.bom.BomChangeLogRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomYieldEstimateServiceImplTest {

    private static final String FACTORY = "F006";
    private static final String PRODUCT = "PRODUCT-1";
    private static final String RECIPE_ID = "RECIPE-1";

    @Mock private ProductionBatchRepository productionBatchRepository;
    @Mock private ProductTypeRepository productTypeRepository;
    @Mock private BomRecipeRepository bomRecipeRepository;
    @Mock private BomRecipeItemRepository bomRecipeItemRepository;
    @Mock private BomChangeLogRepository bomChangeLogRepository;
    @Mock private YieldReportService yieldReportService;

    private BomYieldEstimateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new BomYieldEstimateServiceImpl(
                productionBatchRepository,
                productTypeRepository,
                bomRecipeRepository,
                bomRecipeItemRepository,
                bomChangeLogRepository,
                yieldReportService);
    }

    @Test
    void medianConvertsDecimalYieldToPercentage() {
        assertEquals(new BigDecimal("75.00"), service.computeMedian(List.of(
                new BigDecimal("0.70"), new BigDecimal("0.80"), new BigDecimal("0.75"))));
    }

    @Test
    void previewReadsCurrentActiveRecipeAndRecipeItemsOnly() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        BomRecipeItem item = recipeItem();
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrueAndStatus(
                FACTORY, PRODUCT, BomRecipe.Status.ACTIVE)).thenReturn(Optional.of(recipe));
        when(bomRecipeItemRepository.findCurrentByProduct(FACTORY, PRODUCT)).thenReturn(List.of(item));
        stubSamples("0.70", "0.75", "0.80");

        List<BomYieldPreviewItemDTO> preview = service.recalculatePreview(FACTORY, List.of(PRODUCT));

        assertEquals(1, preview.size());
        assertEquals(RECIPE_ID, preview.getFirst().getRecipeId());
        assertEquals(new BigDecimal("75.00"), preview.getFirst().getSuggestedYieldRate());
        assertEquals("UPDATABLE", preview.getFirst().getStatus());
    }

    @Test
    void applyUpdatesRecipeOverallYieldAndWritesRecipeAuditLink() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenAnswer(invocation -> {
            BomChangeLog log = invocation.getArgument(0);
            log.setId("LOG-1");
            return log;
        });

        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(
                BomYieldApplyRequest.builder()
                        .recipeId(RECIPE_ID)
                        .yieldRate(new BigDecimal("82.126"))
                        .expectedCurrentYieldRate(new BigDecimal("80.00"))
                        .build()));

        assertEquals(1, result.getApplied());
        assertEquals(new BigDecimal("82.13"), recipe.getOverallYieldRate());
        ArgumentCaptor<BomChangeLog> captor = ArgumentCaptor.forClass(BomChangeLog.class);
        verify(bomChangeLogRepository).save(captor.capture());
        assertEquals(RECIPE_ID, captor.getValue().getBomRecipeId());
        assertEquals(null, captor.getValue().getBomRecipeItemId());
    }

    @Test
    void staleExpectedYieldAbortsBeforeAnyWrite() {
        BomRecipe recipe = activeRecipe(new BigDecimal("81.00"));
        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));

        assertThrows(BomYieldStaleException.class, () -> service.recalculateApply(FACTORY, List.of(
                BomYieldApplyRequest.builder()
                        .recipeId(RECIPE_ID)
                        .yieldRate(new BigDecimal("82.00"))
                        .expectedCurrentYieldRate(new BigDecimal("80.00"))
                        .build())));

        verify(bomRecipeRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // 🔴 标准出成率的合法区间 (0, 300] —— RECON-LEGAL-VALUE-SENTINEL-20260906
    //
    //  · 100.00 是**合法**的零损耗值。2026-09-06 之前它被 ProductionPlanServiceImpl
    //    当成「未配置」哨兵, 前端还专门拦下来教用户填 99.99 —— 那条路已经拆掉。
    //  · > 100 合法: 保水/腌制工序天然超过 100 (六膳门猪舌保水实测 105–126%)。
    //  · 300 是防手滑上限, 与 DTO @DecimalMax、前端 YIELD_RATE_MAX_PERCENT、
    //    DB chk_br_yield (V20261101_32) 是同一条规则的三层(形态 D)。
    // ────────────────────────────────────────────────────────────────────────────

    @Test
    void applyAccepts100BecauseItIsALegalZeroLossValue() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenAnswer(invocation -> {
            BomChangeLog log = invocation.getArgument(0);
            log.setId("LOG-100");
            return log;
        });

        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(
                BomYieldApplyRequest.builder()
                        .recipeId(RECIPE_ID)
                        .yieldRate(new BigDecimal("100.00"))
                        .expectedCurrentYieldRate(new BigDecimal("80.00"))
                        .build()));

        assertEquals(1, result.getApplied(), "100.00 被拒 ⇒ 那个哨兵又回来了");
        assertEquals(new BigDecimal("100.00"), recipe.getOverallYieldRate());
    }

    @Test
    void applyAccepts126BecauseBrineWorkStepsExceed100() {
        BomRecipe recipe = activeRecipe(new BigDecimal("80.00"));
        when(bomRecipeRepository.findById(RECIPE_ID)).thenReturn(Optional.of(recipe));
        when(bomChangeLogRepository.save(any(BomChangeLog.class))).thenAnswer(invocation -> {
            BomChangeLog log = invocation.getArgument(0);
            log.setId("LOG-126");
            return log;
        });

        BomYieldApplyResultDTO result = service.recalculateApply(FACTORY, List.of(
                BomYieldApplyRequest.builder()
                        .recipeId(RECIPE_ID)
                        .yieldRate(new BigDecimal("126.00"))
                        .expectedCurrentYieldRate(new BigDecimal("80.00"))
                        .build()));

        assertEquals(1, result.getApplied(), "126% 被拒 ⇒ 保水/腌制工序填不进去");
        assertEquals(new BigDecimal("126.00"), recipe.getOverallYieldRate());
    }

    @Test
    void applyRejectsZeroWithAHintedError() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recalculateApply(FACTORY, List.of(
                        BomYieldApplyRequest.builder()
                                .recipeId(RECIPE_ID)
                                .yieldRate(BigDecimal.ZERO)
                                .build())));

        assertEquals(400, error.getCode());
        assertEquals("BOM_YIELD_RATE_OUT_OF_RANGE", error.getErrorCode());
        // 阴性对照: 拒了要说清楚下一步做什么, ⛔ 不是干巴巴一句「不合法」
        assertNotNull(error.getActionHint(), "拒绝没带 actionHint —— 用户不知道该改成什么");
        // ⛔ 拒之前一行都不许写
        verify(bomRecipeRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    @Test
    void applyRejects301AsATypoCeiling() {
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recalculateApply(FACTORY, List.of(
                        BomYieldApplyRequest.builder()
                                .recipeId(RECIPE_ID)
                                .yieldRate(new BigDecimal("301"))
                                .build())));

        assertEquals(400, error.getCode());
        assertEquals("BOM_YIELD_RATE_OUT_OF_RANGE", error.getErrorCode());
        assertTrue(error.getMessage().contains("300"),
                "报错里没说上限是多少: " + error.getMessage());
        verify(bomRecipeRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    @Test
    void applyRejectsAnOutOfRangeRowBeforeWritingTheGoodRowsInTheSameBatch() {
        // 区间校验在整批的最前面跑完 —— 否则第一行写进去了第二行才被拒,
        // 事务虽然会回滚, 但「先写后校验」这个形状迟早会被拆出事务边界。
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.recalculateApply(FACTORY, List.of(
                        BomYieldApplyRequest.builder()
                                .recipeId(RECIPE_ID)
                                .yieldRate(new BigDecimal("93.75"))
                                .build(),
                        BomYieldApplyRequest.builder()
                                .recipeId("RECIPE-2")
                                .yieldRate(new BigDecimal("500"))
                                .build())));

        assertEquals("BOM_YIELD_RATE_OUT_OF_RANGE", error.getErrorCode());
        verify(bomRecipeRepository, never()).save(any());
        verify(bomChangeLogRepository, never()).save(any());
    }

    private BomRecipe activeRecipe(BigDecimal yield) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(RECIPE_ID);
        recipe.setFactoryId(FACTORY);
        recipe.setProductTypeId(PRODUCT);
        recipe.setProductName("Test Product");
        recipe.setStatus(BomRecipe.Status.ACTIVE);
        recipe.setIsCurrent(true);
        recipe.setOverallYieldRate(yield);
        return recipe;
    }

    private BomRecipeItem recipeItem() {
        BomRecipeItem item = new BomRecipeItem();
        item.setRecipeId(RECIPE_ID);
        item.setFactoryId(FACTORY);
        item.setMaterialTypeId("RAW-1");
        item.setMaterialName("Raw Material");
        item.setMaterialCategory("RAW");
        item.setUnit("kg");
        item.setStandardQuantity(BigDecimal.ONE);
        return item;
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
