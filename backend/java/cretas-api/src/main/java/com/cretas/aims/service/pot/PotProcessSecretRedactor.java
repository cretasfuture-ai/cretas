package com.cretas.aims.service.pot;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretPlanProducts;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * S3-16 锅次的工艺保密: 锅次上的工序名 / 工序代码 / 工艺参数 (温度、时长…) / 备注都是该产品的工艺,
 * 按 {@link ProcessSecretAction#VIEW_FULL} 判定 (与 {@code @ProcessSecretGuard(VIEW_FULL, BATCH_PATH)} 同口径:
 * 批次产品 + 计划牵涉的全部产品)。
 *
 * <p>锅次接口一次返回多个批次 (按批次列表 / 按锅追溯跨批次), 路径注解解析不了, 所以在服务层裁剪
 * (ProcessSecretRouteLedgerContractTest 登记为 CHECKED_IN_SERVICE)。无权时不拒绝整个请求 ——
 * 锅号、时间、用料、去向照常给 (召回要用), 只去掉工艺字段。批次查不到、调用者不明 ⇒ 按受限 (fail-closed)。
 * 本厂没有任何保密产品时零额外查询。
 */
@Component
@RequiredArgsConstructor
public class PotProcessSecretRedactor {

    private final ProcessSecretAccessService accessService;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository planRepository;
    private final BomRecipeRepository recipeRepository;

    /** 当前调用者<b>不能</b>看完整工艺的生产批次。 */
    public Set<Long> restrictedBatches(String factoryId, Collection<Long> productionBatchIds) {
        if (productionBatchIds == null || productionBatchIds.isEmpty()) {
            return Set.of();
        }
        Set<String> protectedProducts = accessService.protectedProductTypeIds(factoryId);
        if (protectedProducts.isEmpty()) {
            return Set.of();
        }
        AuthenticatedActor actor = AuthenticatedActor.fromCurrentExecution().orElse(null);
        Set<Long> restricted = new HashSet<>(productionBatchIds);
        Map<String, Boolean> permittedByProduct = new HashMap<>();
        for (ProductionBatch batch : batchRepository.findAllById(new LinkedHashSet<>(productionBatchIds))) {
            if (!factoryId.equals(batch.getFactoryId())) {
                continue;   // 别厂批次: 保持受限
            }
            Set<String> products = new LinkedHashSet<>();
            if (batch.getProductTypeId() != null) {
                products.add(batch.getProductTypeId());
            }
            if (batch.getProductionPlanId() != null && !batch.getProductionPlanId().isBlank()) {
                planRepository.findByIdAndFactoryId(batch.getProductionPlanId(), factoryId)
                        .ifPresent(plan -> products.addAll(ProcessSecretPlanProducts.of(plan,
                                recipeId -> recipeProduct(factoryId, recipeId))));
            }
            boolean allowed = products.stream()
                    .filter(protectedProducts::contains)
                    .allMatch(p -> permittedByProduct.computeIfAbsent(p, k ->
                            accessService.decide(actor, factoryId, k, ProcessSecretAction.VIEW_FULL).permitted()));
            if (allowed) {
                restricted.remove(batch.getId());
            }
        }
        return restricted;
    }

    private Optional<String> recipeProduct(String factoryId, String recipeId) {
        if (recipeId == null) {
            return Optional.empty();
        }
        return recipeRepository.findById(recipeId)
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .map(BomRecipe::getProductTypeId);
    }
}
