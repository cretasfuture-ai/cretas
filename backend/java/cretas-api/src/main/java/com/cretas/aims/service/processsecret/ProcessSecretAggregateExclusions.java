package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    public ProcessSecretAggregateExclusions(ProcessSecretAccessService accessService,
                                            ProductionPlanRepository planRepository) {
        this.accessService = accessService;
        this.planRepository = planRepository;
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
        for (ProductionPlan plan : planRepository.findByFactoryId(factoryId)) {
            // 目标成品与按产品钉的配方键已覆盖多产出计划的产出 (钉住的配方不再逐个查库)
            if (ProcessSecretPlanProducts.of(plan, recipeId -> Optional.empty()).stream()
                    .noneMatch(hidden::contains)) {
                continue;
            }
            if (plan.getId() != null && !plan.getId().isBlank()) plans.add(plan.getId());
            if (plan.getProductTypeId() != null && !plan.getProductTypeId().isBlank()) {
                products.add(plan.getProductTypeId());
            }
        }
        return new Scope(List.copyOf(products), List.copyOf(plans));
    }
}
