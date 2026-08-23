package com.cretas.aims.service.factory;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryWarehouse;
import com.cretas.aims.entity.factory.FactoryWarehouse.WarehouseType;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionItemRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.production.ProductionMaterialReturnRepository;
import com.cretas.aims.service.factory.impl.FactoryMaterialRequisitionServiceImpl;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 判据五 · 坎 2：**多产出计划领不到料**，两条错误各自指向对方。
 *
 * <p>prod 实测（2026-08-18，F006）：
 * <pre>
 * 报工 → 409 PRODUCTION_STOCK_SHORTAGE 「全厂在手 30kg，只是还没领到生产仓 → 去领料」
 * 领料 → 404 「产品 BOM 未配置…productTypeId=RMT_07a9a522-…」
 *         actionHint「请前往 BOM成本管理 配置产品 BOM」   ← 让人给一个【原料】配 BOM
 * </pre>
 *
 * <p>根因：多产出工作流没有单一成品可挂归属，归属落在**入口原料**上
 * （{@code __RAW_WORKFLOW_OWNER__} 锚点，{@code ProductType.category = RAW_MATERIAL}），
 * 而 BOM 挂在成品上 ⇒ {@code findCurrentByProduct(原料 id)} <b>按构造恒空</b>。
 *
 * <p>DB 取证（{@code cretas_prod_db.public}，只读）：3 张多产出计划的
 * {@code product_type_id} 全是 {@code RMT_07a9a522-…}；{@code target_finished_good_ids}
 * 是两个真成品；{@code selected_bom_recipe_ids_by_product} <b>9/9 计划全都有值</b>；
 * BOM 家族内每个 {@code material_type_id} 的 {@code in_n_recipes = 1}
 * （原料只挂 MAIN，BY_PRODUCT 那张一行明细都没有）。
 *
 * <p>⚠️ 这些断言全部跑在**真实入口** {@code service.generateFromPlan} 上，不是 helper。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("判据五坎2: 多产出计划(归属在入口原料上)必须能开出领料单")
class MultiOutputPlanRequisitionContractTest {

    private static final String FACTORY_ID = "F006";
    private static final String PLAN_ID = "plan-multi-001";
    /** 多产出工作流的归属锚点 —— 是个**原料**，按构造永远没有产品 BOM。 */
    private static final String RAW_ANCHOR_ID = "RMT_07a9a522-43e4-4c0f-8f87-24751f8fd1d3";
    private static final String FG_A = "44f7f9f8-51b1-4d5d-a876-d8723e8f081b";
    private static final String FG_B = "e9fd2932-a5fb-48c0-b645-0c6d63663eed";
    private static final String RECIPE_A = "b837f4cd-ae0c-468f-967b-cb54b14a2efe";
    private static final String RECIPE_B = "a5700402-8cfb-4155-82b3-065e90c4b208";
    private static final String WH_RAW = "wh-raw";
    private static final String WH_WORKSHOP = "wh-workshop";

    @Mock
    private FactoryMaterialRequisitionRepository repository;
    @Mock
    private FactoryMaterialRequisitionItemRepository itemRepository;
    @Mock
    private ProductionPlanRepository productionPlanRepository;
    @Mock
    private BomRecipeItemRepository bomItemRepository;
    @Mock
    private TransferService transferService;
    @Mock
    private FactoryWarehouseRepository warehouseRepository;
    @Mock
    private MaterialBatchRepository materialBatchRepository;
    @Mock
    private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock
    private ProductionMaterialReturnRepository productionMaterialReturnRepository;
    @Mock
    private WarehouseResolver warehouseResolver;

    @InjectMocks
    private FactoryMaterialRequisitionServiceImpl service;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
        lenient().when(warehouseRepository
                        .findByFactoryIdAndTypeAndDeletedAtIsNullOrderByCodeAsc(FACTORY_ID, WarehouseType.WORKSHOP))
                .thenReturn(List.of(warehouse(WH_WORKSHOP)));
        lenient().when(repository.countByFactoryIdAndRequisitionNoPrefix(eq(FACTORY_ID), any())).thenReturn(0L);
        lenient().when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(warehouseResolver.resolvePurchaseInboundWh(FACTORY_ID)).thenReturn(WH_RAW);
        lenient().when(materialBatchRepository.findStockUnitsByMaterialType(eq(FACTORY_ID), anyString()))
                .thenReturn(List.of("kg"));
    }

    @Test
    @DisplayName("归属在入口原料上的多产出计划: 用计划自己钉住的配方开出领料单, 不再 404")
    void multiOutputPlanAnchoredOnRawMaterial_generatesRequisitionFromPinnedRecipes() {
        givenPlan(multiOutputPlan());
        // 锚点是原料 ⇒ 产品 BOM 按构造恒空 (prod 实测长相)
        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, RAW_ANCHOR_ID)).thenReturn(List.of());
        // 钉住的两张配方: 投入挂在 MAIN 上, BY_PRODUCT 那张没有明细 (prod 实测长相)
        when(bomItemRepository.findByRecipeIdsWithRecipe(eq(FACTORY_ID), any()))
                .thenReturn(List.of(item(642L, "RMT-M", "CLAUDEPF-原料M", "20", RECIPE_A, BomRecipe.OutputRole.MAIN)));

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertNotNull(mr, "多产出计划必须能开出领料单 —— 开不出就把用户卡在报工↔领料之间来回跑");
        assertEquals(1, mr.getItems().size(), "钉住的配方里有 1 行投入, 领料单就该有 1 行");
        assertEquals("RMT-M", mr.getItems().get(0).getMaterialTypeId());
        assertEquals(WH_WORKSHOP, mr.getTargetWarehouseId(), "目标仓仍是生产仓");
    }

    @Test
    @DisplayName("同一物料出现在家族里多张配方时, 只领一次 (⛔ 不许相加 —— 那是重复领料)")
    void sharedMaterialAcrossPinnedRecipes_isRequisitionedOnce() {
        givenPlan(multiOutputPlan());
        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, RAW_ANCHOR_ID)).thenReturn(List.of());
        // 假想将来两张配方都填了同一个原料 —— 多产出家族里投入只消耗一次
        when(bomItemRepository.findByRecipeIdsWithRecipe(eq(FACTORY_ID), any()))
                .thenReturn(List.of(
                        item(700L, "RMT-SHARED", "共用原料", "20", RECIPE_B, BomRecipe.OutputRole.BY_PRODUCT),
                        item(701L, "RMT-SHARED", "共用原料", "20", RECIPE_A, BomRecipe.OutputRole.MAIN)));

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertEquals(1, mr.getItems().size(),
                "同一物料在家族两张配方里各出现一次, 领料单只能有一行 —— 两行就是重复领料");
        assertEquals(701L, mr.getItems().get(0).getBomRecipeItemId(),
                "同一物料取 MAIN 侧那一行 (投入口径挂在主产出上), 不是列表里先出现的那一行");
    }

    @Test
    @DisplayName("阳性对照/回归: 单产出计划有 current BOM 时, 一次都不去读钉住的配方")
    void singleOutputPlanWithCurrentBom_neverConsultsPinnedRecipes() {
        ProductionPlan plan = basePlan();
        plan.setProductTypeId("fg-single-001");
        plan.setTargetFinishedGoodIds(new ArrayList<>(List.of("fg-single-001")));
        plan.setSelectedBomRecipeIdsByProduct(new LinkedHashMap<>(Map.of("fg-single-001", "recipe-single")));
        givenPlan(plan);
        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, "fg-single-001"))
                .thenReturn(List.of(item(1L, "MAT-001", "猪蹄", "2", "recipe-single", BomRecipe.OutputRole.MAIN)));

        FactoryMaterialRequisition mr = service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L);

        assertEquals(1, mr.getItems().size());
        assertEquals("MAT-001", mr.getItems().get(0).getMaterialTypeId());
        verify(bomItemRepository, never()).findByRecipeIdsWithRecipe(anyString(), any());
    }

    @Test
    @DisplayName("钉住的配方也取不到时: 中文报错说清是多产出, ⛔ 不许再让人去给原料配 BOM")
    void unresolvablePinnedRecipes_giveActionableChineseErrorWithoutSendingUserToConfigureRawMaterialBom() {
        givenPlan(multiOutputPlan());
        when(bomItemRepository.findCurrentByProduct(FACTORY_ID, RAW_ANCHOR_ID)).thenReturn(List.of());
        when(bomItemRepository.findByRecipeIdsWithRecipe(eq(FACTORY_ID), any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.generateFromPlan(FACTORY_ID, PLAN_ID, 1L));

        String message = String.valueOf(ex.getMessage());
        String hint = String.valueOf(ex.getActionHint());
        assertTrue(message.matches(".*[\\u4e00-\\u9fa5].*"), "面向用户的报错必须是中文: " + message);
        assertNotNull(ex.getActionHint(), "拦住人的地方必须给下一步");
        assertFalse(message.contains(RAW_ANCHOR_ID) || hint.contains(RAW_ANCHOR_ID),
                "⛔ 不许把原料锚点 id 丢给用户 —— 他拿这个 id 什么也做不了: " + message + " / " + hint);
        assertTrue(hint.contains("多个成品") || hint.contains("多产出") || message.contains("多个成品"),
                "必须说清这是多产出计划, 否则用户会照着旧提示去给原料配 BOM: " + message + " / " + hint);
    }

    // ---------- fixtures ----------

    private void givenPlan(ProductionPlan plan) {
        when(productionPlanRepository.findByIdAndFactoryId(PLAN_ID, FACTORY_ID)).thenReturn(Optional.of(plan));
    }

    private ProductionPlan basePlan() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(PLAN_ID);
        plan.setFactoryId(FACTORY_ID);
        plan.setPlanNumber("PLAN-1787037552334-1617FF84");
        plan.setPlannedQuantity(new BigDecimal("1"));
        plan.setExpectedCompletionDate(LocalDate.now().plusDays(3));
        return plan;
    }

    /** prod 上真实存在的形状: 归属锚在原料, 两个 target 成品, 两张钉住的配方。 */
    private ProductionPlan multiOutputPlan() {
        ProductionPlan plan = basePlan();
        plan.setProductTypeId(RAW_ANCHOR_ID);
        plan.setTargetFinishedGoodIds(new ArrayList<>(List.of(FG_A, FG_B)));
        LinkedHashMap<String, String> pinned = new LinkedHashMap<>();
        pinned.put(FG_A, RECIPE_A);
        pinned.put(FG_B, RECIPE_B);
        plan.setSelectedBomRecipeIdsByProduct(pinned);
        return plan;
    }

    private BomRecipeItem item(Long id, String materialTypeId, String name, String stdQty,
                               String recipeId, BomRecipe.OutputRole role) {
        BomRecipe recipe = new BomRecipe();
        recipe.setId(recipeId);
        recipe.setFactoryId(FACTORY_ID);
        recipe.setOutputRole(role);

        BomRecipeItem item = new BomRecipeItem();
        item.setId(id);
        item.setRecipeId(recipeId);
        item.setRecipe(recipe);
        item.setMaterialTypeId(materialTypeId);
        item.setMaterialName(name);
        item.setStandardQuantity(stdQty == null ? null : new BigDecimal(stdQty));
        item.setUnit("kg");
        item.setMaterialCategory("RAW");
        return item;
    }

    private FactoryWarehouse warehouse(String id) {
        FactoryWarehouse wh = new FactoryWarehouse();
        wh.setId(id);
        wh.setFactoryId(FACTORY_ID);
        wh.setType(WarehouseType.WORKSHOP);
        return wh;
    }

    /** 编译期锚点: 提醒改仓储签名时同步这个测试 (Collection 参数)。 */
    @SuppressWarnings("unused")
    private static Collection<String> signatureAnchor() {
        return List.of(RECIPE_A, RECIPE_B);
    }
}
