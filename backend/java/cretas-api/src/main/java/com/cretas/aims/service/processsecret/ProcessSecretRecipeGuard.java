package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.processsecret.ProcessSecretAction;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.security.AuthenticatedActor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 服务层的工艺保密判定 (按配方定位产品)。用在 HTTP 拦截器够不到的地方:
 * 版本审批 (ECN 审批也走这里)、BOM 批量改、AI 工具。调用者取当前请求 / 受信异步作用域里的身份,
 * 取不到按无权处理 (受保护产品 fail-closed, 未受保护产品不受影响)。
 */
@Component
public class ProcessSecretRecipeGuard {

    private final BomRecipeRepository recipeRepository;
    private final ProcessSecretAccessService accessService;

    public ProcessSecretRecipeGuard(BomRecipeRepository recipeRepository, ProcessSecretAccessService accessService) {
        this.recipeRepository = recipeRepository;
        this.accessService = accessService;
    }

    public Optional<String> productOf(String factoryId, String recipeId) {
        if (recipeId == null) return Optional.empty();
        return recipeRepository.findById(recipeId)
                .filter(r -> Objects.equals(factoryId, r.getFactoryId()))
                .map(BomRecipe::getProductTypeId);
    }

    /** 配方属于受保护产品且当前调用者无权 → 403 + 审计; 配方不存在时不判 (业务自己报 404)。 */
    public void requireRecipe(String factoryId, String recipeId, ProcessSecretAction action, String resourceRef) {
        productOf(factoryId, recipeId).ifPresent(product ->
                accessService.require(currentActor(), factoryId, product, action, resourceRef));
    }

    /** 批量操作: 范围内任一受保护配方无权即整体 403 —— 批量写不做「静默跳过一部分」。 */
    public void requireRecipes(String factoryId, Collection<BomRecipe> recipes, ProcessSecretAction action,
                               String resourceRef) {
        if (recipes == null || recipes.isEmpty()) return;
        List<String> products = recipes.stream()
                .filter(r -> Objects.equals(factoryId, r.getFactoryId()))
                .map(BomRecipe::getProductTypeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        accessService.filterPermitted(currentActor(), factoryId, products, action, true, resourceRef);
    }

    /** 批量改动的范围拆分结果: {@code skipped} 是 recipeId → 原因。 */
    public record Partition(List<BomRecipe> allowed, java.util.Map<String, String> skipped) { }

    /**
     * 批量编辑: 点名的配方 ({@code explicit}) 任一无权即整体 403;
     * 「全厂批量」则跳过无权的受保护配方并写明原因 (复审 M-2: 不能让一个保密产品锁死全厂批量)。
     */
    public Partition partitionForEdit(String factoryId, List<BomRecipe> recipes, boolean explicit,
                                      String resourceRef) {
        if (recipes == null || recipes.isEmpty()) return new Partition(List.of(), java.util.Map.of());
        if (explicit) {
            requireRecipes(factoryId, recipes, ProcessSecretAction.EDIT, resourceRef);
            return new Partition(recipes, java.util.Map.of());
        }
        AuthenticatedActor actor = currentActor();
        java.util.List<BomRecipe> allowed = new java.util.ArrayList<>();
        java.util.Map<String, String> skipped = new java.util.LinkedHashMap<>();
        // 先逐产品判定并写审计; 放行但审计写不进去的, 与 require() 一致按拒绝处理
        java.util.Map<String, Boolean> editable = new java.util.HashMap<>();
        for (BomRecipe recipe : recipes) {
            String product = recipe.getProductTypeId();
            if (product == null || editable.containsKey(product)) continue;
            ProcessSecretAccessService.Decision decision = accessService.decide(actor, factoryId, product,
                    ProcessSecretAction.EDIT);
            if (!decision.isProtected()) {
                editable.put(product, true);
            } else if (decision.permitted()) {
                editable.put(product, accessService.audit(factoryId, product, actor, ProcessSecretAction.EDIT,
                        "ALLOW", decision.reason(), resourceRef));
            } else {
                accessService.audit(factoryId, product, actor, ProcessSecretAction.EDIT, "DENY",
                        decision.reason() + "（全厂批量已跳过）", resourceRef);
                editable.put(product, false);
            }
        }
        for (BomRecipe recipe : recipes) {
            String product = recipe.getProductTypeId();
            if (product != null && !editable.getOrDefault(product, true)) {
                skipped.put(recipe.getId(), "工艺保密：无编辑授权，已跳过");
                continue;
            }
            allowed.add(recipe);
        }
        return new Partition(allowed, skipped);
    }

    /** AI 工具用: 不抛异常, 返回能否给出完整内容 (受保护产品的判定照常审计)。 */
    public boolean permitsFullContent(String factoryId, String recipeId, String resourceRef) {
        Optional<String> product = productOf(factoryId, recipeId);
        if (product.isEmpty()) return true;
        AuthenticatedActor actor = currentActor();
        ProcessSecretAccessService.Decision decision = accessService.decide(actor, factoryId, product.get(),
                ProcessSecretAction.VIEW_FULL);
        if (!decision.isProtected()) return true;
        boolean recorded = accessService.audit(factoryId, product.get(), actor, ProcessSecretAction.VIEW_FULL,
                decision.permitted() ? "ALLOW" : "DENY", decision.reason(), resourceRef);
        return decision.permitted() && recorded;
    }

    /** 按产品判定 (不抛异常), 受保护产品的判定照常审计; 审计写不进去按无权处理。 */
    public boolean permitsProduct(String factoryId, String productTypeId, ProcessSecretAction action,
                                  String resourceRef) {
        if (productTypeId == null) return true;
        AuthenticatedActor actor = currentActor();
        ProcessSecretAccessService.Decision decision = accessService.decide(actor, factoryId, productTypeId, action);
        if (!decision.isProtected()) return true;
        boolean recorded = accessService.audit(factoryId, productTypeId, actor, action,
                decision.permitted() ? "ALLOW" : "DENY", decision.reason(), resourceRef);
        return decision.permitted() && recorded;
    }

    /** 按产品判定, 受保护且无权即 403 + 审计 (调用者取当前请求 / 受信异步作用域)。 */
    public void requireProduct(String factoryId, String productTypeId, ProcessSecretAction action,
                               String resourceRef) {
        if (productTypeId == null) return;
        accessService.require(currentActor(), factoryId, productTypeId, action, resourceRef);
    }

    /**
     * 复制类操作 (组装 SKU、复制工序链、按已有产品建品): 源产品受保护时, 必须同时有完整查看和导出授权
     * —— 复制出来的新产品没有保密策略, 等于把整套工艺搬到不设防的地方 (与 copyToDraft 同一口径)。
     */
    public void requireCopySource(String factoryId, String sourceProductTypeId, String resourceRef) {
        if (sourceProductTypeId == null) return;
        AuthenticatedActor actor = currentActor();
        accessService.require(actor, factoryId, sourceProductTypeId, ProcessSecretAction.VIEW_FULL, resourceRef);
        accessService.require(actor, factoryId, sourceProductTypeId, ProcessSecretAction.EXPORT, resourceRef);
    }

    private static AuthenticatedActor currentActor() {
        return AuthenticatedActor.fromCurrentExecution().orElse(null);
    }
}
