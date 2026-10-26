package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.service.bom.PlanBomItems;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 跨批汇总 (Steve 第三问 A: 汇总只给负责人和授权人) 里要排除的范围: 调用者无完整查看权的保密产品,
 * 以及牵涉它们的生产计划和这些计划的产品锚点。多产出计划的锚点 id 就是原料 id (独立审查第三轮 A),
 * 半成品行的产品是半成品 SKU (第三轮 B) —— 只按产品排除都会漏, 所以连计划一起给出。
 * 同一原料锚定的其他计划随之被排除 (宁可少汇总, 不漏)。每个被排除的产品记一条 DENY 审计。
 */
@Component
public class ProcessSecretAggregateExclusions {

    /** 排除列表为空时的哨兵 (NOT IN 不能给空列表)。 */
    public static final String NONE = "__none__";

    public record Scope(List<String> productTypeIds, List<String> planIds) {
        public static final Scope EMPTY = new Scope(List.of(), List.of());

        public List<String> productTypeIdsOrSentinel() {
            return productTypeIds.isEmpty() ? List.of(NONE) : productTypeIds;
        }

        public List<String> planIdsOrSentinel() {
            return planIds.isEmpty() ? List.of(NONE) : planIds;
        }
    }

    private final ProcessSecretAccessService accessService;
    private final ProductionPlanRepository planRepository;
    private final BomRecipeRepository recipeRepository;

    public ProcessSecretAggregateExclusions(ProcessSecretAccessService accessService,
                                            ProductionPlanRepository planRepository,
                                            BomRecipeRepository recipeRepository) {
        this.accessService = accessService;
        this.planRepository = planRepository;
        this.recipeRepository = recipeRepository;
    }

    public Scope forCurrentCaller(String factoryId, String resourceRef) {
        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.isEmpty()) return Scope.EMPTY;
        List<String> all = new ArrayList<>(protectedIds);
        Set<String> hidden = new HashSet<>(all);
        hidden.removeAll(accessService.filterPermitted(AuthenticatedActor.fromCurrentExecution().orElse(null),
                factoryId, all, ProcessSecretAction.VIEW_FULL, false, resourceRef));
        if (hidden.isEmpty()) return Scope.EMPTY;
        TreeSet<String> products = new TreeSet<>(hidden);
        TreeSet<String> plans = new TreeSet<>();
        List<ProductionPlan> factoryPlans = planRepository.findByFactoryId(factoryId);
        Map<String, String> productOfRecipe = productsOfPinnedRecipes(factoryId, factoryPlans);
        for (ProductionPlan plan : factoryPlans) {
            // 与裁剪器、配比提示同一口径: 锚点、目标成品、按产品钉的配方键, 以及钉住的配方所属产品
            // (只钉了单个配方的多产出计划, 产品只能从配方查到; 独立审查第四轮 M2)
            if (ProcessSecretPlanProducts.of(plan, recipeId -> Optional.ofNullable(productOfRecipe.get(recipeId)))
                    .stream().noneMatch(hidden::contains)) {
                continue;
            }
            if (plan.getId() != null && !plan.getId().isBlank()) plans.add(plan.getId());
            if (plan.getProductTypeId() != null && !plan.getProductTypeId().isBlank()) {
                products.add(plan.getProductTypeId());
            }
        }
        return new Scope(List.copyOf(products), List.copyOf(plans));
    }

    /** 全部计划钉住的配方 → 所属产品 (一次批量查, 只认本厂配方)。 */
    private Map<String, String> productsOfPinnedRecipes(String factoryId, List<ProductionPlan> plans) {
        Set<String> recipeIds = new HashSet<>();
        plans.forEach(plan -> recipeIds.addAll(PlanBomItems.pinnedRecipeIds(plan)));
        Map<String, String> productOf = new HashMap<>();
        if (recipeIds.isEmpty()) return productOf;
        for (BomRecipe recipe : recipeRepository.findAllById(recipeIds)) {
            if (factoryId.equals(recipe.getFactoryId()) && recipe.getProductTypeId() != null) {
                productOf.put(recipe.getId(), recipe.getProductTypeId());
            }
        }
        return productOf;
    }
}
