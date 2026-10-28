package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 不走 JSON 裁剪器的出口 (Excel / CSV 导出、拼进文字的 AI 提示词) 用的成本遮蔽判定:
 * 给一组产品, 返回当前调用者看不到成本的那几个 (受保护且没有完整查看权)。
 *
 * <p>保密产品的成本合计 (原料 / 调料 / 包材小计、总成本、单位成本、毛利) 对无授权账号一律「已保密」
 * (Steve 2026-10-28 选 A), 与 {@link ProcessSecretPayloadScrubber} 对 JSON 的口径一致。
 * 判定与审计都交给 {@link ProcessSecretAccessService#filterPermitted}: 逐个记 ALLOW / DENY,
 * ALLOW 写不进去按无权处理。识别不出调用者 (定时任务等) 时按无权处理。</p>
 */
@Component
public class ProcessSecretCostMask {

    public static final String LABEL = "已保密";

    private final ProcessSecretAccessService accessService;
    private final ProductionPlanRepository planRepository;
    private final ProductionBatchRepository batchRepository;
    private final BomRecipeRepository recipeRepository;

    public ProcessSecretCostMask(ProcessSecretAccessService accessService, ProductionPlanRepository planRepository,
                                 ProductionBatchRepository batchRepository, BomRecipeRepository recipeRepository) {
        this.accessService = accessService;
        this.planRepository = planRepository;
        this.batchRepository = batchRepository;
        this.recipeRepository = recipeRepository;
    }

    /** 当前调用者看不到成本的产品; 没有受保护产品时为空集合 (不写审计)。 */
    public Set<String> hiddenFor(String factoryId, Collection<String> productTypeIds, String resourceRef) {
        return hiddenFor(AuthenticatedActor.fromCurrentExecution().orElse(null), factoryId, productTypeIds,
                resourceRef);
    }

    public Set<String> hiddenFor(AuthenticatedActor actor, String factoryId, Collection<String> productTypeIds,
                                 String resourceRef) {
        if (factoryId == null || productTypeIds == null || productTypeIds.isEmpty()) return Set.of();
        Set<String> protectedIds = accessService.protectedProductTypeIds(factoryId);
        if (protectedIds.isEmpty()) return Set.of();
        List<String> candidates = productTypeIds.stream()
                .filter(Objects::nonNull)
                .filter(protectedIds::contains)
                .distinct()
                .toList();
        if (candidates.isEmpty()) return Set.of();
        List<String> permitted = accessService.filterPermitted(actor, factoryId, candidates,
                ProcessSecretAction.VIEW_FULL, false, resourceRef);
        Set<String> hidden = new LinkedHashSet<>(candidates);
        permitted.forEach(hidden::remove);
        return hidden;
    }

    /** 本厂是否有当前调用者看不到成本的保密产品 (没有归属的成本行据此决定遮不遮)。 */
    public boolean anyHidden(String factoryId, String resourceRef) {
        if (factoryId == null) return false;
        return !hiddenFor(factoryId, accessService.protectedProductTypeIds(factoryId), resourceRef).isEmpty();
    }

    /**
     * 受保护产品连数量也不给的调用者: 平台人员、他厂账号、识别不出的调用者
     * (与 {@link ProcessSecretPayloadScrubber} 的「无权」档一致)。本厂账号只遮金额。
     */
    public boolean quantitiesHidden(String factoryId) {
        return ProcessSecretPayloadScrubber.degradedLevel(AuthenticatedActor.fromCurrentExecution().orElse(null),
                factoryId) == ProcessSecretPayloadScrubber.Level.NONE;
    }

    /**
     * 一条消耗 / 半成品流水牵涉的产品: 所属计划的全部产品 (主产品、目标成品、钉住配方的产品),
     * 计划查不到时用所属生产批次的产品及其计划。{@code cache} 由调用方在一次导出里复用。
     */
    public Set<String> productsOf(String factoryId, String planId, Long productionBatchId,
                                  Map<String, Set<String>> cache) {
        Set<String> products = new LinkedHashSet<>();
        if (planId != null && !planId.isBlank()) products.addAll(planProducts(factoryId, planId, cache));
        if (productionBatchId != null) {
            products.addAll(cache.computeIfAbsent("batch|" + productionBatchId, k -> {
                Set<String> found = new LinkedHashSet<>();
                batchRepository.findByIdAndFactoryId(productionBatchId, factoryId).ifPresent(batch -> {
                    if (batch.getProductTypeId() != null) found.add(batch.getProductTypeId());
                    if (batch.getProductionPlanId() != null && !batch.getProductionPlanId().isBlank()) {
                        found.addAll(planProducts(factoryId, batch.getProductionPlanId(), cache));
                    }
                });
                return found;
            }));
        }
        return products;
    }

    private Set<String> planProducts(String factoryId, String planId, Map<String, Set<String>> cache) {
        String key = "plan|" + planId;
        Set<String> cached = cache.get(key);
        if (cached != null) return cached;
        Set<String> products = planRepository.findByIdAndFactoryId(planId, factoryId)
                .map(plan -> ProcessSecretPlanProducts.of(plan, recipeId -> recipeRepository.findById(recipeId)
                        .filter(r -> factoryId.equals(r.getFactoryId()))
                        .map(BomRecipe::getProductTypeId)))
                .orElse(Set.of());
        cache.put(key, products);
        return products;
    }
}
