package com.cretas.aims.service.bom;

import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 回料(半成品)行的成本路径 —— 「先做后用」<b>够得到</b>了。
 *
 * <h2>这条测试守的是一个「机制在、没接上」</h2>
 * {@code semiFinishedRefCode} 与「先做后用」的 WIP 移动均价查询 2026-10 就写好了,
 * 但 {@code resolveItemCost} 开头是:
 * <pre>if (subProductTypeId == null) return item.computeItemCost();</pre>
 * 而 {@code isNestedComponent} 也只认 {@code subProductTypeId}。于是<b>回料行的正常长相</b>
 * (只有 semiFinishedRefCode, 没有子产品) 一进来就被弹回 {@code actualQuantity × unitPrice} ——
 * 回料没有原料档案, 也就没有 unitPrice, 结果恒 null。
 * 全库 {@code semi_finished_ref_code} 非空行数 = 0, 所以这条路一次都没被生产走过。
 *
 * <p>下面每一条都<b>不带 {@code subProductTypeId}</b> —— 那正是以前够不到的形状。
 */
@DisplayName("回料(半成品)BOM 行 — 成本走「先做后用」")
@ExtendWith(MockitoExtension.class)
class SemiFinishedBomRowCostTest {

    private static final String FACTORY = "F006";
    private static final String WIP_CODE = "WIP-F006-REWORK-20260829";
    private static final String SUB_PRODUCT_TYPE_ID = "PTSEMI-REWORK-001";

    @Mock
    BomRecipeRepository bomRecipeRepository;

    @Mock
    SemiFinishedInventoryRepository sfiRepository;

    @InjectMocks
    NestedBomCostService service;

    /**
     * 一条回料行的真实长相: material_type_id 为 null (DB 侧 chk_bri_material_ref_xor 要求),
     * 身份在 semi_finished_ref_code 上, 没有 unitPrice。
     */
    private BomRecipeItem reworkRow(BigDecimal standardQuantity) {
        BomRecipeItem item = new BomRecipeItem();
        item.setId(77L);
        item.setFactoryId(FACTORY);
        item.setMaterialTypeId(null);
        item.setSemiFinishedRefCode(WIP_CODE);
        item.setMaterialCategory(BomRecipeItem.CATEGORY_SEMI_FINISHED);
        item.setStandardQuantity(standardQuantity);
        item.setYieldRate(new BigDecimal("100.00"));
        item.setUnit("kg");
        item.setUnitPrice(null);
        return item;
    }

    private SemiFinishedInventory wipWithUnitCost(BigDecimal unitCost) {
        SemiFinishedInventory wip = new SemiFinishedInventory();
        wip.setUnitCost(unitCost);
        wip.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        return wip;
    }

    // ─────────────── 接线本身 ───────────────

    @Test
    @DisplayName("接线: 只有 semiFinishedRefCode 的行也被判为「要走 resolveItemCost」")
    void semiOnlyRowIsRoutedToResolveItemCost() {
        assertThat(service.isNestedComponent(reworkRow(BigDecimal.ONE)))
                .as("isNestedComponent 只认 subProductTypeId 的话, 回料行会被当成普通原料行, "
                        + "整条「先做后用」在生产上就是死代码")
                .isTrue();
    }

    @Test
    @DisplayName("阴性对照: 两个引用都没有的普通原料行仍然判 false —— 接线没有放宽到「什么都算」")
    void plainMaterialRowIsStillNotNested() {
        BomRecipeItem plain = new BomRecipeItem();
        plain.setMaterialTypeId("MT-001");
        plain.setStandardQuantity(BigDecimal.ONE);
        plain.setUnit("kg");
        assertThat(service.isNestedComponent(plain)).isFalse();
    }

    @Test
    @DisplayName("阴性对照: semiFinishedRefCode 是空白串不算身份")
    void blankSemiRefIsNotAnIdentity() {
        BomRecipeItem blank = reworkRow(BigDecimal.ONE);
        blank.setSemiFinishedRefCode("   ");
        assertThat(service.isNestedComponent(blank)).isFalse();
        assertThat(BomRecipeItem.isSemiFinishedRow(blank)).isFalse();
    }

    // ─────────────── 分支一: WIP 移动均价 ───────────────

    @Test
    @DisplayName("WIP 有移动均价 → 用它 (2.5kg × ¥18.4000 = ¥46.0000), 且不去查子 BOM")
    void wipMovingAveragePriceIsUsed() {
        BomRecipeItem item = reworkRow(new BigDecimal("2.5"));
        when(sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, WIP_CODE))
                .thenReturn(Optional.of(wipWithUnitCost(new BigDecimal("18.4000"))));

        BigDecimal cost = service.resolveItemCost(FACTORY, item);

        assertThat(cost).isEqualByComparingTo("46.0000");
        verifyNoInteractions(bomRecipeRepository);
    }

    // ─────────────── 分支二: 降级到子 BOM 设计成本 ───────────────

    @Test
    @DisplayName("WIP 不存在 → 降级到子 BOM 设计成本 (totalCost 150 ÷ 每单位 500 × 1000 = ¥300.0000)")
    void fallsBackToSubBomDesignCostWhenWipMissing() {
        BomRecipeItem item = reworkRow(new BigDecimal("1000"));
        item.setSubProductTypeId(SUB_PRODUCT_TYPE_ID);
        when(sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, WIP_CODE))
                .thenReturn(Optional.empty());

        BomRecipe sub = new BomRecipe();
        sub.setId("SUB-BOM-REWORK");
        sub.setFactoryId(FACTORY);
        sub.setProductTypeId(SUB_PRODUCT_TYPE_ID);
        sub.setStatus(BomRecipe.Status.ACTIVE);
        sub.setIsCurrent(true);
        sub.setTotalCost(new BigDecimal("150.0000"));
        sub.setOutputQuantityPerUnit(new BigDecimal("500"));
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(
                FACTORY, SUB_PRODUCT_TYPE_ID)).thenReturn(Optional.of(sub));

        BigDecimal cost = service.resolveItemCost(FACTORY, item);

        assertThat(cost).isEqualByComparingTo("300.0000");
        verify(bomRecipeRepository)
                .findByFactoryIdAndProductTypeIdAndIsCurrentTrue(FACTORY, SUB_PRODUCT_TYPE_ID);
    }

    @Test
    @DisplayName("WIP 记着但没记单价 → 同样降级到子 BOM 设计成本")
    void fallsBackToSubBomDesignCostWhenWipHasNoUnitCost() {
        BomRecipeItem item = reworkRow(new BigDecimal("1000"));
        item.setSubProductTypeId(SUB_PRODUCT_TYPE_ID);
        when(sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, WIP_CODE))
                .thenReturn(Optional.of(wipWithUnitCost(null)));

        BomRecipe sub = new BomRecipe();
        sub.setId("SUB-BOM-REWORK");
        sub.setFactoryId(FACTORY);
        sub.setProductTypeId(SUB_PRODUCT_TYPE_ID);
        sub.setStatus(BomRecipe.Status.ACTIVE);
        sub.setIsCurrent(true);
        sub.setTotalCost(new BigDecimal("150.0000"));
        sub.setOutputQuantityPerUnit(new BigDecimal("500"));
        when(bomRecipeRepository.findByFactoryIdAndProductTypeIdAndIsCurrentTrue(
                FACTORY, SUB_PRODUCT_TYPE_ID)).thenReturn(Optional.of(sub));

        assertThat(service.resolveItemCost(FACTORY, item)).isEqualByComparingTo("300.0000");
    }

    // ─────────────── 两条都走不通时: 诚实返 null ───────────────

    @Test
    @DisplayName("WIP 没有、也没绑子产品 → null (⛔ 不伪造 0: 「不知道多少钱」不是「不要钱」)")
    void unknownCostStaysNullInsteadOfZero() {
        BomRecipeItem item = reworkRow(new BigDecimal("2.5"));
        when(sfiRepository.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(FACTORY, WIP_CODE))
                .thenReturn(Optional.empty());

        assertThat(service.resolveItemCost(FACTORY, item)).isNull();
        verifyNoInteractions(bomRecipeRepository);
    }

    @Test
    @DisplayName("没填参考用量 → null (与普通行同口径)")
    void missingQuantityStaysNull() {
        BomRecipeItem item = reworkRow(null);
        assertThat(service.resolveItemCost(FACTORY, item)).isNull();
        verifyNoInteractions(bomRecipeRepository, sfiRepository);
    }
}
