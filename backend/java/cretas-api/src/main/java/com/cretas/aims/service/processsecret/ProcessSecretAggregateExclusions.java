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
        return expand(factoryId, hidden);
    }

    /**
     * 给「发给大模型的数据」用: 已经判定好的隐藏产品 (对当前调用者无完整查看权的保密产品), 连同牵涉它们的计划和锚点,
     * 以及每个被排除计划背后是哪几个隐藏产品 (给「不含 N 个保密产品」计数, 锚点 / 同计划普通产品不算)。
     * 判定由调用方做 (ProcessSecretLlmCostScope), 这里不判权限、不记审计。
     */
    public record ProtectedScope(Scope scope, Set<String> protectedProductTypeIds,
                                 Map<String, Set<String>> protectedProductsByPlan, Set<String> planProductTypeIds) {
    }

    public ProtectedScope forHidden(String factoryId, Set<String> hidden) {
        Map<String, Set<String>> byPlan = new HashMap<>();
        Set<String> planProducts = new TreeSet<>();
        Scope scope = expand(factoryId, new HashSet<>(hidden), byPlan, planProducts);
        return new ProtectedScope(scope, Set.copyOf(hidden), Map.copyOf(byPlan), Set.copyOf(planProducts));
    }

    private Scope expand(String factoryId, Set<String> hidden) {
        return expand(factoryId, hidden, null, null);
    }

    /** {@code planProducts}: 牵涉隐藏产品的计划上的全部产品 (锚点、目标成品、联产的普通产品)。 */
    private Scope expand(String factoryId, Set<String> hidden, Map<String, Set<String>> hiddenByPlan,
                         Set<String> planProducts) {
        // 判定范围要读全厂计划和它们钉住的配方: 整段不打标 (否则出口会以为这次输出牵涉了所有计划 / 配方产品,
        // 把已经按调用者裁剪的合计一起藏掉); 段内新载入的实体在段结束时移出持久化上下文
        return ProcessSecretReadTracker.untracked(() -> expandTracked(factoryId, hidden, hiddenByPlan, planProducts));
    }

    private Scope expandTracked(String factoryId, Set<String> hidden, Map<String, Set<String>> hiddenByPlan,
                                Set<String> planProducts) {
        TreeSet<String> products = new TreeSet<>(hidden);
        TreeSet<String> plans = new TreeSet<>();
        List<ProductionPlan> factoryPlans = planRepository.findByFactoryId(factoryId);
        Map<String, String> productOfRecipe = productsOfPinnedRecipes(factoryId, factoryPlans);
        for (ProductionPlan plan : factoryPlans) {
            // 与裁剪器、配比提示同一口径: 锚点、目标成品、按产品钉的配方键, 以及钉住的配方所属产品
            // (只钉了单个配方的多产出计划, 产品只能从配方查到; 独立审查第四轮 M2)
            Set<String> allOfPlan = ProcessSecretPlanProducts.of(plan, recipeId -> Optional.ofNullable(productOfRecipe.get(recipeId)));
            Set<String> involved = new TreeSet<>(allOfPlan);
            involved.retainAll(hidden);
            if (involved.isEmpty()) {
                continue;
            }
            if (planProducts != null) planProducts.addAll(allOfPlan);
            if (plan.getId() != null && !plan.getId().isBlank()) {
                plans.add(plan.getId());
                if (hiddenByPlan != null) hiddenByPlan.put(plan.getId(), Set.copyOf(involved));
            }
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
