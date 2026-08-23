package com.cretas.aims.service.bom;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 「一张生产计划要投入哪些物料」的**唯一**解析入口。
 *
 * <h3>为什么要有这个类（形态 D：同一个东西有两份，它一定会漂）</h3>
 *
 * <p>本仓原来有 <b>三处</b>各自写着 {@code findCurrentByProduct(factoryId, plan.getProductTypeId())}：
 * <ul>
 *   <li>{@code FactoryMaterialRequisitionServiceImpl.generateFromPlan} —— 空则 404</li>
 *   <li>{@code ProductionPlanServiceImpl.validateMaterialStockSufficient} —— 空则**静默跳过库存校验**</li>
 *   <li>{@code ProductionPlanServiceImpl.scanMaterialAdvisory} —— 空则返回空，界面印「暂无缺料预警」</li>
 * </ul>
 * 三处是同一个错的三种长相，两处还是无声的。抽成一份，避免下次只改一处。
 *
 * <h3>它修的缺陷（2026-08-18 prod 实测）</h3>
 *
 * <p>多产出工作流（一份原料出多个成品）没有单一成品可以挂归属，于是
 * {@code ProductProcessWorkflowServiceImpl.requireWorkflowOwner} 给**入口原料**懒建一个
 * inactive 锚点 {@code __RAW_WORKFLOW_OWNER__<rawId>}（{@code ProductType.category = RAW_MATERIAL}），
 * 计划的 {@code productTypeId} 就是这个原料 id。而 BOM 是挂在**成品**上的
 * ⇒ {@code findCurrentByProduct(原料 id)} <b>按构造恒空</b>。
 *
 * <p>实测长相（F006，3 个多产出计划，{@code product_type_id = RMT_07a9a522-…}）：
 * <pre>
 * 报工 → 409 PRODUCTION_STOCK_SHORTAGE 「全厂在手 30kg，只是还没领到生产仓 → 去领料」
 * 领料 → 404 「产品 BOM 未配置…productTypeId=RMT_07a9a522-…」
 *         actionHint「请前往 BOM成本管理 配置产品 BOM」   ← 让人给一个【原料】配 BOM
 * </pre>
 * 两条错误各自指向对方，用户在中间来回跑。
 *
 * <h3>修法：改用计划自己钉住的配方版本</h3>
 *
 * <p>计划创建时就把 {@code selected_bom_recipe_ids_by_product}（产品 → 配方 id）钉死了，
 * prod 实测 <b>9/9 计划全都有值</b>，多产出的 3 张也有。它比「产品的 current BOM」更权威：
 * 计划钉的是不可变版本，而 current 会被后续激活/归档改掉。
 *
 * <p>⚠️ 顺序上仍然**先**试 {@code findCurrentByProduct}——今天能开出单的每一条路径行为逐字不变，
 * 本类只在原来「空 ⇒ 404 / 静默跳过」的那一支上多给一次机会。
 *
 * <h3>为什么去重而不是相加</h3>
 *
 * <p>多产出家族里投入只消耗一次。prod 实测（family {@code e8565578-…}）：原料只挂在
 * <b>MAIN</b> 那张配方上，<b>BY_PRODUCT 那张一行明细都没有</b>，
 * 每个 {@code material_type_id} 的 {@code in_n_recipes = 1}。所以今天去重一次都不会触发；
 * 它存在是为了防止将来有人两张都填 —— 那时相加就是**重复领料**。
 * 同一物料出现在多张配方时取 <b>MAIN 侧</b>那一行（投入口径挂在主产出上）。
 */
public final class PlanBomItems {

    private PlanBomItems() {
    }

    /**
     * 解析这张计划的 BOM 明细。
     *
     * @return 永不为 null；解析不出来时返回空 list（调用方自己决定是拦还是跳过）
     */
    public static List<BomRecipeItem> resolve(String factoryId, ProductionPlan plan,
                                              BomRecipeItemRepository itemRepository) {
        if (plan == null || itemRepository == null) {
            return List.of();
        }
        String productTypeId = plan.getProductTypeId();
        if (productTypeId != null && !productTypeId.isBlank()) {
            List<BomRecipeItem> current = itemRepository.findCurrentByProduct(factoryId, productTypeId);
            if (current != null && !current.isEmpty()) {
                return current;
            }
        }
        return fromPinnedRecipes(factoryId, plan, itemRepository);
    }

    /**
     * 这张计划是不是「产出不是单一成品」那一类 —— 决定报错该说什么。
     *
     * <p>判据用**结构**（计划钉了多个产出）而不是名字前缀 {@code __RAW_WORKFLOW_OWNER__}：
     * 名字是展示串，会被改；「钉了几个产出」是这条业务本身。
     */
    public static boolean isMultiOutput(ProductionPlan plan) {
        if (plan == null) {
            return false;
        }
        List<String> targets = plan.getTargetFinishedGoodIds();
        return targets != null && targets.size() > 1;
    }

    /** 这张计划钉住的配方 id（去重、保序）。 */
    public static Set<String> pinnedRecipeIds(ProductionPlan plan) {
        LinkedHashSet<String> recipeIds = new LinkedHashSet<>();
        if (plan == null) {
            return recipeIds;
        }
        Map<String, String> byProduct = plan.getSelectedBomRecipeIdsByProduct();
        if (byProduct != null) {
            for (String recipeId : byProduct.values()) {
                if (recipeId != null && !recipeId.isBlank()) {
                    recipeIds.add(recipeId);
                }
            }
        }
        if (recipeIds.isEmpty()) {
            String single = plan.getSelectedBomRecipeId();
            if (single != null && !single.isBlank()) {
                recipeIds.add(single);
            }
        }
        return recipeIds;
    }

    private static List<BomRecipeItem> fromPinnedRecipes(String factoryId, ProductionPlan plan,
                                                         BomRecipeItemRepository itemRepository) {
        Set<String> recipeIds = pinnedRecipeIds(plan);
        if (recipeIds.isEmpty()) {
            return List.of();
        }
        List<BomRecipeItem> rows = itemRepository.findByRecipeIdsWithRecipe(factoryId, recipeIds);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<BomRecipeItem> ordered = new ArrayList<>(rows);
        // List.sort 是稳定排序 ⇒ 同一 rank 内仍保持仓储层的 sortOrder。
        ordered.sort(Comparator.comparingInt(PlanBomItems::roleRank));

        List<BomRecipeItem> deduped = new ArrayList<>(ordered.size());
        Set<String> seenMaterials = new HashSet<>();
        for (BomRecipeItem item : ordered) {
            String materialTypeId = item.getMaterialTypeId();
            if (materialTypeId != null && !seenMaterials.add(materialTypeId)) {
                continue;
            }
            deduped.add(item);
        }
        return deduped;
    }

    private static int roleRank(BomRecipeItem item) {
        BomRecipe recipe = item.getRecipe();
        BomRecipe.OutputRole role = recipe == null ? null : recipe.getOutputRole();
        if (role == null) {
            return 1;
        }
        return switch (role) {
            case MAIN -> 0;
            case CO_PRODUCT -> 1;
            case BY_PRODUCT -> 2;
        };
    }
}
