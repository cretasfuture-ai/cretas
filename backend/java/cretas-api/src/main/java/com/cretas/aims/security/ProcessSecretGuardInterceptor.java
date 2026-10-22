package com.cretas.aims.security;

import com.cretas.aims.annotation.ProcessSecretGuard;
import com.cretas.aims.entity.ProductProcessWorkflow;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.repository.ProductProcessWorkflowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bom.BomVersionRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 执行 {@link ProcessSecretGuard}: 在 controller 之前确定产品, 交给
 * {@link ProcessSecretAccessService#require} 判定 (拒绝 = 403 + 审计)。
 *
 * <p>产品一律从<b>服务端数据</b>解析 (配方 / 明细 / Workflow 的 factoryId 必须与路径一致),
 * 不信任请求体里自称的产品。</p>
 */
@Component
public class ProcessSecretGuardInterceptor implements HandlerInterceptor {

    private final ProcessSecretAccessService accessService;
    private final BomRecipeRepository recipeRepository;
    private final BomRecipeItemRepository recipeItemRepository;
    private final ProductProcessWorkflowRepository workflowRepository;
    private final BomVersionRepository versionRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository planRepository;
    private final FactoryMaterialRequisitionRepository requisitionRepository;

    public ProcessSecretGuardInterceptor(ProcessSecretAccessService accessService,
                                         BomRecipeRepository recipeRepository,
                                         BomRecipeItemRepository recipeItemRepository,
                                         ProductProcessWorkflowRepository workflowRepository,
                                         BomVersionRepository versionRepository,
                                         ProductionBatchRepository batchRepository,
                                         ProductionPlanRepository planRepository,
                                         FactoryMaterialRequisitionRepository requisitionRepository) {
        this.accessService = accessService;
        this.recipeRepository = recipeRepository;
        this.recipeItemRepository = recipeItemRepository;
        this.workflowRepository = workflowRepository;
        this.versionRepository = versionRepository;
        this.batchRepository = batchRepository;
        this.planRepository = planRepository;
        this.requisitionRepository = requisitionRepository;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) return true;
        ProcessSecretGuard guard = method.getMethodAnnotation(ProcessSecretGuard.class);
        if (guard == null) return true;

        Map<String, String> vars = pathVariables(request);
        String factoryId = vars.get("factoryId");
        if (factoryId == null) return true;
        AuthenticatedActor actor = AuthenticatedActor.current(request).orElse(null);
        for (String productTypeId : resolveProducts(guard, factoryId, vars, request)) {
            accessService.require(actor, factoryId, productTypeId, guard.action(),
                    request.getMethod() + " " + request.getRequestURI());
        }
        return true;
    }

    /** 一个接口可能涉及多个产品 (多计划打印); 任一受保护产品无权即 403。 */
    List<String> resolveProducts(ProcessSecretGuard guard, String factoryId, Map<String, String> vars,
                                 HttpServletRequest request) {
        if (guard.from() == ProcessSecretGuard.Source.PLAN_PARAM_LIST) {
            String[] raw = request.getParameterValues(guard.name());
            if (raw == null) return List.of();
            Set<String> products = new LinkedHashSet<>();
            Arrays.stream(raw).flatMap(v -> Arrays.stream(v.split(",")))
                    .map(String::trim).filter(v -> !v.isEmpty())
                    .forEach(planId -> planProduct(factoryId, planId).ifPresent(products::add));
            return new ArrayList<>(products);
        }
        return resolveProduct(guard, factoryId, vars, request).map(List::of).orElse(List.of());
    }

    private Optional<String> planProduct(String factoryId, String planId) {
        return planRepository.findByIdAndFactoryId(planId, factoryId)
                .map(com.cretas.aims.entity.ProductionPlan::getProductTypeId);
    }

    private Optional<String> recipeProduct(String factoryId, String recipeId) {
        if (recipeId == null) return Optional.empty();
        return recipeRepository.findById(recipeId)
                .filter(r -> factoryId.equals(r.getFactoryId()))
                .map(BomRecipe::getProductTypeId);
    }

    Optional<String> resolveProduct(ProcessSecretGuard guard, String factoryId, Map<String, String> vars,
                                    HttpServletRequest request) {
        String raw = guard.from() == ProcessSecretGuard.Source.PRODUCT_PARAM
                ? request.getParameter(guard.name())
                : vars.get(guard.name());
        if (raw == null || raw.isBlank()) return Optional.empty();
        return switch (guard.from()) {
            case PRODUCT_PATH, PRODUCT_PARAM -> Optional.of(raw);
            case RECIPE_PATH -> recipeRepository.findById(raw)
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .map(BomRecipe::getProductTypeId);
            case RECIPE_ITEM_PATH -> parseLong(raw)
                    .flatMap(recipeItemRepository::findById)
                    .filter(i -> factoryId.equals(i.getFactoryId()))
                    .map(BomRecipeItem::getRecipeId)
                    .flatMap(recipeRepository::findById)
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .map(BomRecipe::getProductTypeId);
            case WORKFLOW_PATH -> parseLong(raw)
                    .flatMap(workflowRepository::findById)
                    .filter(w -> factoryId.equals(w.getFactoryId()))
                    .map(ProductProcessWorkflow::getProductTypeId);
            case BOM_VERSION_PATH -> versionRepository.findById(raw)
                    .filter(v -> factoryId.equals(v.getFactoryId()))
                    .flatMap(v -> recipeProduct(factoryId, v.getBomRecipeId()));
            case BATCH_PATH -> parseLong(raw)
                    .flatMap(id -> batchRepository.findByIdAndFactoryId(id, factoryId))
                    .map(com.cretas.aims.entity.ProductionBatch::getProductTypeId);
            case PLAN_PATH -> planProduct(factoryId, raw);
            case MATERIAL_REQUISITION_PATH -> requisitionRepository.findById(raw)
                    .filter(r -> factoryId.equals(r.getFactoryId()))
                    .flatMap(r -> planProduct(factoryId, r.getProductionPlanId()));
            case PLAN_PARAM_LIST -> Optional.empty();
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object raw = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return raw instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static Optional<Long> parseLong(String raw) {
        try {
            return Optional.of(Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
