package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 缺陷49「tooltip 半」(owner 05:05 裁定, 派单 aa3b35b873bab5177) —— 同一需求量本轮 owner
 * 现场核出<b>三处三个数</b>: tooltip / 结单(✅正确) / 配料单(=2×, 派给另一执行者 a3a7a38eaf271a120)。
 *
 * <p>本类只钉「tooltip 半」: {@code scanMaterialAdvisory} 里「需要」这个数必须是
 * {@code perUnitRequired × plan.plannedQuantity}, 与结单
 * ({@code ProcessSheetServiceImpl.buildAutomaticBomRequirements} 的
 * {@code perBasis × scale}, scale = 实际报工量 / 配方基准量) 同一套「单位用量 × 数量」乘法,
 * ⛔ 不写第三个公式。
 *
 * <p>实测取值全部来自 cretas_prod_db（LIUSHANMEN, factory_id=LIUSHANMEN, 2026-09-02 直查）:
 * {@code bom_recipe_items} 里 L1纸箱(PACKAGING) 行 {@code standard_quantity=0.0694}
 * {@code yield_rate=100.00}, 与美式汉堡饼计划 {@code planned_quantity=100.00 kg} 同一条 BOM。
 *
 * <p>🔴 复查结论: 用真实数值跑通当前源码 {@code scanMaterialAdvisory}, 得到 required=6.94 ——
 * 与 owner 认定「结单 6.94 ✅」<b>已经一致</b>。「tooltip 0.0694 漏乘计划量」这条诊断,
 * 对应的是<b>另一张计划</b>（该产品当晚测试用的 IN_PROGRESS 计划 plannedQuantity=1.00,
 * 0.0694×1=0.0694 对那张计划本身是算对的，不是公式漏乘）。⇒ premise 不成立 —— tooltip 的乘法
 * 本身没有缺陷，这份测试把它<b>钉死</b>，防止将来被误改回「不乘计划量」。
 */
class MaterialAdvisoryRequiredQuantityScalesWithPlanQuantityTest {

    private static final String F = "LIUSHANMEN";
    private static final String PRODUCT = "PT_TEST_HANBAOBING";
    // 真实 cretas_prod_db 值 (bom_recipe_items.id=672, recipe e085deab-…, 2026-09-02 直查)。
    private static final String L1_BOX = "RMT_1782891392801";
    private static final BigDecimal DOSAGE_PER_KG = new BigDecimal("0.0694");

    private ProductionPlanServiceImpl service;
    private MaterialBatchRepository batchRepo;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        BomRecipeItem l1Box = new BomRecipeItem();
        l1Box.setMaterialTypeId(L1_BOX);
        l1Box.setMaterialName("L1纸箱");
        l1Box.setMaterialCategory("PACKAGING");
        l1Box.setStandardQuantity(DOSAGE_PER_KG);
        l1Box.setYieldRate(new BigDecimal("100.00"));
        l1Box.setUnit("个");

        BomRecipeItemRepository bomRepo = mock(BomRecipeItemRepository.class);
        when(bomRepo.findCurrentByProduct(eq(F), eq(PRODUCT))).thenReturn(List.of(l1Box));

        batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.findRawStockUnitsByMaterialType(anyString(), anyString())).thenReturn(List.of("个"));
        // 全厂库存 0 (与 owner 实测现场一致: L1纸箱可用 0个) —— 让它必然落进缺口清单,
        // 这样 requiredQuantity 能直接从 warnings 读出来。
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(BigDecimal.ZERO);

        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
    }

    private static ProductionPlan planWithQuantity(String qty) {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-real-" + qty);
        p.setPlanNumber("PLAN-REAL-" + qty);
        p.setFactoryId(F);
        p.setProductTypeId(PRODUCT);
        p.setPlannedQuantity(new BigDecimal(qty));
        return p;
    }

    @SuppressWarnings("unchecked")
    private List<ProductionPlanMaterialAdvisoryDTO.Item> advise(String plannedQty) {
        return (List<ProductionPlanMaterialAdvisoryDTO.Item>) ReflectionTestUtils.invokeMethod(
                service, "buildMaterialAdvisoryItems", F, planWithQuantity(plannedQty));
    }

    @Test
    @DisplayName("阳性对照: 有条目返回 (否则下面的数值断言在守空气)")
    void positiveControlItemIsReturned() {
        List<ProductionPlanMaterialAdvisoryDTO.Item> items = advise("100");
        assertEquals(1, items.size(), "实际 " + items);
    }

    @Test
    @DisplayName("🔴 主断言: 计划量 100kg × 0.0694/kg = 6.94, 与结单(✅正确)口径一致")
    void requiredQuantityScalesByPlannedQuantity100() {
        ProductionPlanMaterialAdvisoryDTO.Item item = advise("100").get(0);
        assertEquals(0, item.getRequiredQuantity().compareTo(new BigDecimal("6.94")),
                "需要 " + item.getRequiredQuantity() + " (期望 6.94, 与结单 6.94✅ 同口径)");
        assertEquals(0, item.getShortageQuantity().compareTo(new BigDecimal("6.94")));
    }

    @Test
    @DisplayName("🔴 阴性对照(证明它在缩放, 不是写死): 计划量 50kg × 0.0694/kg = 3.47")
    void requiredQuantityScalesByPlannedQuantity50() {
        ProductionPlanMaterialAdvisoryDTO.Item item = advise("50").get(0);
        assertEquals(0, item.getRequiredQuantity().compareTo(new BigDecimal("3.47")),
                "需要 " + item.getRequiredQuantity() + " (期望 3.47 —— 与 100kg 的用例得到相同的数就说明没有真的按计划量缩放)");
    }
}
