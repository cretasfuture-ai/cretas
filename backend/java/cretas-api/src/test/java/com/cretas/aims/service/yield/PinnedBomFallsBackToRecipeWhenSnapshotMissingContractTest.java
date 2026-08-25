package com.cretas.aims.service.yield;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 闸 —— pinned BOM 快照缺失时，从 {@code bom_recipes} 现拼一份；
 * 但<b>版本号对不上就不许拼</b>。
 *
 * <h2>🔴 为什么有这道闸（2026-08-25 生产库实测）</h2>
 *
 * 画布保存/激活 BOM 那条路<b>根本不写</b> {@code bom_versions} ——
 * 只有 ECN 手工流程（{@code BomVersionService}）才写它：
 * <pre>
 * F006 ACTIVE bom_recipes                     13 个
 *   其中在 bom_versions 有对应版本快照的        0 个
 * bom_versions 全库                            9 行, 最近写入停在 2026-08-02
 * </pre>
 * 而成本归集只读 {@code bom_versions} ⇒ 对画布建的 BOM 永远读不到 ⇒
 * <b>7/23 个生产批次</b>卡在 {@code PINNED_BOM_SNAPSHOT_MISSING}，
 * 包材成本被算成 0、整单算不出单位成本。本仓形态 D：读的是不再维护的那一份。
 *
 * <h2>⛔ 这道闸真正守的是那条边界</h2>
 * 回退<b>只在版本号对得上时</b>才用。对不上说明 recipe 已经往前走了、
 * 那个版本的内容确实丢了 —— 那时必须<b>诚实报缺</b>，
 * 而不是拿新版本的料去冒充旧版本（那会让历史成本悄悄变化）。
 */
class PinnedBomFallsBackToRecipeWhenSnapshotMissingContractTest {

    private static final String F = "F006";
    private static final String RECIPE = "BOM-CANVAS-1";

    /** 只测回退这一段, 用反射直接调私有方法 —— 它的输入输出都是纯数据。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> invokeFallback(
            OrderCostBreakdownService service, ProductionPlan plan) {
        return (Map<String, Object>) ReflectionTestUtils.invokeMethod(
                service, "pinnedBomSnapshotFromRecipe", F, plan);
    }

    private static OrderCostBreakdownService serviceWith(BomRecipe recipe, List<BomRecipeItem> items) {
        OrderCostBreakdownService service = Mockito.mock(OrderCostBreakdownService.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        BomRecipeRepository recipeRepo = Mockito.mock(BomRecipeRepository.class);
        BomRecipeItemRepository itemRepo = Mockito.mock(BomRecipeItemRepository.class);
        Mockito.when(recipeRepo.findById(RECIPE)).thenReturn(Optional.ofNullable(recipe));
        Mockito.when(itemRepo.findByRecipeIdOrderBySortOrderAsc(RECIPE)).thenReturn(items);
        ReflectionTestUtils.setField(service, "bomRecipeFallbackRepository", recipeRepo);
        ReflectionTestUtils.setField(service, "bomRecipeItemFallbackRepository", itemRepo);
        return service;
    }

    private static BomRecipe recipe(int version) {
        BomRecipe r = new BomRecipe();
        r.setId(RECIPE);
        r.setFactoryId(F);
        r.setVersion(version);
        r.setOutputQuantityPerUnit(new BigDecimal("800"));
        r.setOutputUnit("g");
        return r;
    }

    private static BomRecipeItem packagingItem() {
        BomRecipeItem it = new BomRecipeItem();
        it.setRecipeId(RECIPE);
        it.setFactoryId(F);
        it.setMaterialTypeId("RMT_BOX");
        it.setMaterialName("成品盒");
        it.setMaterialCategory("PACKAGING");
        it.setStandardQuantity(new BigDecimal("1.0000"));
        it.setUnit("盒");
        it.setItemCost(new BigDecimal("0.0100"));
        return it;
    }

    private static ProductionPlan plan(int pinnedVersion) {
        ProductionPlan p = new ProductionPlan();
        p.setId("PLAN-1");
        p.setFactoryId(F);
        p.setSelectedBomRecipeId(RECIPE);
        p.setSelectedBomVersion(pinnedVersion);
        return p;
    }

    @Test
    @DisplayName("🔴 版本号对得上 → 现拼快照, 键名与 bom_versions 一致")
    void samePinnedVersion_buildsSnapshotFromRecipe() {
        Map<String, Object> snapshot =
                invokeFallback(serviceWith(recipe(1), List.of(packagingItem())), plan(1));

        assertNotNull(snapshot, "版本号对得上却拒绝回退 —— 那 7 个批次会继续算不出成本");
        assertEquals(new BigDecimal("800"), snapshot.get("outputQuantityPerUnit"));
        assertEquals("g", snapshot.get("outputUnit"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) snapshot.get("items");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        // 键名必须与 bom_versions 快照逐字一致, 否则下游 resolvePinnedPackaging 读不到
        assertEquals("PACKAGING", row.get("materialCategory"));
        assertEquals("RMT_BOX", row.get("materialTypeId"));
        assertEquals(new BigDecimal("1.0000"), row.get("standardQuantity"));
        assertEquals(new BigDecimal("0.0100"), row.get("itemCost"));
    }

    @Test
    @DisplayName("🔴 阴性对照: 版本号对不上【绝不】回退 —— 不拿新版本冒充旧版本")
    void differentPinnedVersion_refusesToFallBack() {
        // recipe 已经走到 v2, 而计划锁的是 v1 —— v1 的内容确实丢了
        assertNull(invokeFallback(serviceWith(recipe(2), List.of(packagingItem())), plan(1)),
                "拿 v2 的料冒充 v1 会让历史成本悄悄变化 —— 必须诚实报缺");
    }

    @Test
    @DisplayName("阴性对照: recipe 不存在 / 没有物料行时不回退")
    void missingRecipeOrItems_refusesToFallBack() {
        assertNull(invokeFallback(serviceWith(null, List.of()), plan(1)), "recipe 不存在");
        assertNull(invokeFallback(serviceWith(recipe(1), List.of()), plan(1)), "没有物料行");
    }

    @Test
    @DisplayName("阴性对照: 跨租户的 recipe 不许被拿来回退")
    void otherTenantRecipe_refusesToFallBack() {
        BomRecipe foreign = recipe(1);
        foreign.setFactoryId("LIUSHANMEN");
        assertNull(invokeFallback(serviceWith(foreign, List.of(packagingItem())), plan(1)),
                "跨租户读 BOM —— 这是租户隔离, 不是性能问题");
    }

    @Test
    @DisplayName("阳性对照: 仓储缺失时整体跳过(不炸), 退回原来的报缺行为")
    void missingRepositories_skipQuietly() {
        OrderCostBreakdownService service = Mockito.mock(OrderCostBreakdownService.class,
                Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
        // 两个回退仓储都不注入 —— 纯 Mockito 单测的常态
        assertNull(invokeFallback(service, plan(1)));
        assertTrue(true, "没有抛异常即为通过");
    }
}
