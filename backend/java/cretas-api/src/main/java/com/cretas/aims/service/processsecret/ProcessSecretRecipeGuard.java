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

    private static AuthenticatedActor currentActor() {
        return AuthenticatedActor.fromCurrentExecution().orElse(null);
    }
}
