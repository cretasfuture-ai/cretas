package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.bom.BomSeasoningItem;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomSeasoningItemRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 缺陷31 (owner 实测 2026-09-02, LIUSHANMEN 熟肉饼) —— 计划列表「原料参考」tooltip 只报
 * 「算得出需求量」的缺口, 全厂库存本身为 0 的 AUXILIARY/PACKAGING 物料<b>完全不出现</b> ——
 * 既没进缺口清单, 也没进「不参与事前核算」的豁免名单(那里只列 RAW)。
 *
 * <p>实测（cretas_prod_db, LIUSHANMEN 熟肉饼配方 {@code f3b2cf8d-…}）：
 * <ul>
 *   <li>{@code bom_recipe_items} 只有 4 行: 3 个 RAW(标准用量 null, 绑了 workflow 投料端口)
 *       + 1 个 PACKAGING(L1纸箱)</li>
 *   <li>7 个辅料(洋葱泥/食用盐/白砂糖/复合磷酸盐/黑胡椒/小苏打/白洋葱粒)<b>完全不在</b>
 *       {@code bom_recipe_items} 里 —— 只存在于 {@code bom_seasoning_items}(用量按
 *       {@code dosagePerKgG} 现场折算, 计划期本来就算不出总量)</li>
 * </ul>
 * 旧代码的缺料扫描只读 {@code bom_recipe_items}，对 {@code bom_seasoning_items} <b>视而不见</b> ——
 * 与 2026-08-18 {@code resolveEligibleBomMaterialTypeIds} 那次踩的坑同一形状(调料在另一张表)。
 */
class MaterialAdvisoryZeroStockNonRawTest {

    private static final String F = "F006";
    private static final String PRODUCT = "PT_TEST_SHUROUBING";
    private static final String RECIPE = "RECIPE_SHUROUBING";
    private static final String RAW_MAT = "RMT_RAW_A";
    private static final String PACKAGING_MAT = "RMT_L1_BOX";
    private static final String AUX_MAT_ONION = "RMT_AUX_ONION";
    private static final String AUX_MAT_SALT = "RMT_AUX_SALT";

    private ProductionPlanServiceImpl service;
    private MaterialBatchRepository batchRepo;
    private BomSeasoningItemRepository seasoningRepo;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        BomRecipeItemRepository bomRepo = mock(BomRecipeItemRepository.class);
        when(bomRepo.findCurrentByProduct(eq(F), eq(PRODUCT))).thenReturn(List.of(rawItem(), packagingItem()));

        batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.findRawStockUnitsByMaterialType(anyString(), anyString())).thenReturn(List.of("个"));
        // 缺口清单沿用 —— PACKAGING 有真实需求量, 全厂 0 ⇒ 已经在 warnings 里 (老行为不变)
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(PACKAGING_MAT)))
                .thenReturn(BigDecimal.ZERO);
        // RAW 库存无所谓 —— 它走豁免名单, 不看库存
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(RAW_MAT)))
                .thenReturn(BigDecimal.ZERO);

        seasoningRepo = mock(BomSeasoningItemRepository.class);
        when(seasoningRepo.findByRecipeIdOrderBySeqAsc(RECIPE)).thenReturn(List.of(
                seasoningItem("洋葱泥", AUX_MAT_ONION),
                seasoningItem("食用盐", AUX_MAT_SALT)));

        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "bomSeasoningItemRepository", seasoningRepo);
    }

    private static BomRecipeItem rawItem() {
        BomRecipeItem it = new BomRecipeItem();
        it.setRecipeId(RECIPE);
        it.setMaterialTypeId(RAW_MAT);
        it.setMaterialName("3251厂牛前");
        it.setMaterialCategory("RAW");
        it.setUnit("kg");
        it.setWorkflowInputPortId("input:9100000001:0"); // 投料量由报工当场决定
        return it; // standardQuantity 保持 null
    }

    private static BomRecipeItem packagingItem() {
        BomRecipeItem it = new BomRecipeItem();
        it.setRecipeId(RECIPE);
        it.setMaterialTypeId(PACKAGING_MAT);
        it.setMaterialName("L1纸箱");
        it.setMaterialCategory("PACKAGING");
        it.setStandardQuantity(new BigDecimal("0.0694"));
        it.setYieldRate(new BigDecimal("100.00"));
        it.setUnit("个");
        return it;
    }

    private static BomSeasoningItem seasoningItem(String name, String materialTypeId) {
        BomSeasoningItem s = new BomSeasoningItem();
        s.setRecipeId(RECIPE);
        s.setFactoryId(F);
        s.setSection(BomSeasoningItem.SECTION_COOKING);
        s.setName(name);
        s.setDosagePerKgG(new BigDecimal("83.8000"));
        s.setMaterialTypeId(materialTypeId);
        return s;
    }

    private static ProductionPlan plan() {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-shuroubing");
        p.setPlanNumber("PLAN-SHUROUBING");
        p.setFactoryId(F);
        p.setProductTypeId(PRODUCT);
        p.setPlannedQuantity(new BigDecimal("100"));
        return p;
    }

    @SuppressWarnings("unchecked")
    private Object scan() {
        return ReflectionTestUtils.invokeMethod(service, "scanMaterialAdvisory", F, plan());
    }

    @SuppressWarnings("unchecked")
    private List<String> zeroStockField(Object scan) {
        return (List<String>) ReflectionTestUtils.invokeMethod(scan, "zeroStock");
    }

    @SuppressWarnings("unchecked")
    private List<String> byReportingField(Object scan) {
        return (List<String>) ReflectionTestUtils.invokeMethod(scan, "byReporting");
    }

    // ================================================================
    // 阳性对照: 仪器必须真的看得到东西, 否则下面全是恒真式
    // ================================================================

    @Test
    @DisplayName("阳性对照: seasoningRepo 真的被调用了 (否则 zeroStock 恒空跟本次改动无关)")
    void positiveControlSeasoningRepoIsConsulted() {
        scan();
        org.mockito.Mockito.verify(seasoningRepo).findByRecipeIdOrderBySeqAsc(RECIPE);
    }

    // ================================================================
    // 主断言
    // ================================================================

    @Test
    @DisplayName("🔴 主断言: bom_seasoning_items 里全厂库存为 0 的辅料必须出现在 zeroStock")
    void seasoningItemsWithZeroStockAppearInZeroStock() {
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_ONION)))
                .thenReturn(BigDecimal.ZERO);
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_SALT)))
                .thenReturn(BigDecimal.ZERO);

        List<String> zeroStock = zeroStockField(scan());
        assertTrue(zeroStock.contains("洋葱泥"), "洋葱泥全厂库存 0, 没有进 zeroStock: " + zeroStock);
        assertTrue(zeroStock.contains("食用盐"), "食用盐全厂库存 0, 没有进 zeroStock: " + zeroStock);
    }

    @Test
    @DisplayName("🔴 阴性对照: 库存 > 0 的辅料不许出现在 zeroStock")
    void seasoningItemWithPositiveStockIsExcluded() {
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_ONION)))
                .thenReturn(new BigDecimal("50"));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_SALT)))
                .thenReturn(BigDecimal.ZERO);

        List<String> zeroStock = zeroStockField(scan());
        assertFalse(zeroStock.contains("洋葱泥"), "库存 50 的洋葱泥不该出现在 zeroStock: " + zeroStock);
        assertTrue(zeroStock.contains("食用盐"), "库存 0 的食用盐仍应出现: " + zeroStock);
    }

    @Test
    @DisplayName("🔴 阴性对照: RAW 物料库存为 0 也不许出现在 zeroStock —— 它属于豁免名单")
    void rawMaterialWithZeroStockStaysInExemptionListNotZeroStock() {
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_ONION)))
                .thenReturn(new BigDecimal("50"));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_SALT)))
                .thenReturn(new BigDecimal("50"));

        Object scan = scan();
        List<String> zeroStock = zeroStockField(scan);
        List<String> byReporting = byReportingField(scan);
        assertFalse(zeroStock.contains("3251厂牛前"),
                "RAW 库存为 0 混进了 zeroStock —— 豁免只对 RAW 成立, 走错了地方: " + zeroStock);
        assertTrue(byReporting.contains("3251厂牛前"), "RAW 应该仍在豁免名单里: " + byReporting);
    }

    @Test
    @DisplayName("已在缺口清单(warnings)里的 PACKAGING 物料不重复出现在 zeroStock")
    void packagingAlreadyInShortageWarningsIsNotDuplicatedIntoZeroStock() {
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_ONION)))
                .thenReturn(new BigDecimal("50"));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_SALT)))
                .thenReturn(new BigDecimal("50"));

        Object scan = scan();
        List<String> zeroStock = zeroStockField(scan);
        assertFalse(zeroStock.contains("L1纸箱"),
                "L1纸箱已经在缺口清单里报过一次, 不该在 zeroStock 里重复出现: " + zeroStock);
    }

    // ================================================================
    // 端到端: getMaterialAdvisory 三组文案顺序 + hasWarning
    // ================================================================

    @Test
    @DisplayName("🔴 端到端: tooltip 三组顺序固定为 缺口 → 库存为0 → 豁免名单, 且 hasWarning=true")
    void endToEndMessageOrderAndHasWarning() {
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_ONION)))
                .thenReturn(BigDecimal.ZERO);
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(AUX_MAT_SALT)))
                .thenReturn(new BigDecimal("50"));

        ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
        ProductionPlan plan = plan();
        when(planRepo.findById(plan.getId())).thenReturn(Optional.of(plan));
        ReflectionTestUtils.setField(service, "productionPlanRepository", planRepo);

        ProductionPlanMaterialAdvisoryDTO advisory = service.getMaterialAdvisory(F, plan.getId());

        assertTrue(advisory.isHasWarning(), "有库存为0的物料, hasWarning 必须是 true");
        String msg = advisory.getMessage();
        int idxShortage = msg.indexOf("L1纸箱");
        int idxZeroStock = msg.indexOf("库存为 0");
        int idxExemption = msg.indexOf("不参与事前核算");
        assertTrue(idxShortage >= 0 && idxZeroStock > idxShortage,
                "缺口应排在库存为0之前: " + msg);
        assertTrue(idxExemption > idxZeroStock, "豁免名单应排在库存为0之后: " + msg);
        assertTrue(msg.contains("洋葱泥"), "洋葱泥没有出现在 tooltip 里: " + msg);
        assertFalse(msg.contains("全厂库存足够"), "有库存为0的物料却还说库存足够, 自相矛盾: " + msg);
    }
}
