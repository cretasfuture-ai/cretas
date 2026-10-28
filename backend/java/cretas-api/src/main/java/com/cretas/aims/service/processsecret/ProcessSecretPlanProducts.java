package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.service.bom.PlanBomItems;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 一张生产计划牵涉的全部产品。多产出计划的 {@code productTypeId} 是原料锚点 —— 它的 id 就是原料 id,
 * 只有名字带 {@code __RAW_WORKFLOW_OWNER__} 前缀, 所以不能按 id 前缀认锚点 (独立审查第三轮 A);
 * 真正被打印 / 领料的是各成品钉住的配方 —— 只判锚点会放过受保护成品 (复审 H-A)。所以把锚点、目标成品、按产品钉住的配方及其产品全部算上, 任一受保护且无权即拦。
 */
public final class ProcessSecretPlanProducts {

    private ProcessSecretPlanProducts() {
    }

    public static Set<String> of(ProductionPlan plan, Function<String, Optional<String>> productOfRecipe) {
        Set<String> products = new LinkedHashSet<>();
        if (plan == null) return products;
        addIfPresent(products, plan.getProductTypeId());
        if (plan.getTargetFinishedGoodIds() != null) {
            plan.getTargetFinishedGoodIds().forEach(id -> addIfPresent(products, id));
        }
        Map<String, String> byProduct = plan.getSelectedBomRecipeIdsByProduct();
        if (byProduct != null) {
            byProduct.keySet().forEach(id -> addIfPresent(products, id));
        }
        for (String recipeId : PlanBomItems.pinnedRecipeIds(plan)) {
            productOfRecipe.apply(recipeId).ifPresent(id -> addIfPresent(products, id));
        }
        return products;
    }

    private static void addIfPresent(Set<String> products, String id) {
        if (id != null && !id.isBlank()) products.add(id);
    }
}
