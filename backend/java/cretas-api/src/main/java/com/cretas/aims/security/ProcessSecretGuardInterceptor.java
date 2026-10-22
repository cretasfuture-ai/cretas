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
        // 本厂没有任何保密策略: 不做额外查询 (无策略工厂零开销)
        if (accessService.protectedProductTypeIds(factoryId).isEmpty()) return true;
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
        Set<String> products = new LinkedHashSet<>();
        switch (guard.from()) {
            case PLAN_PARAM_LIST -> {
                String[] raw = request.getParameterValues(guard.name());
                if (raw == null) return List.of();
                List<String> planIds = Arrays.stream(raw).flatMap(v -> Arrays.stream(v.split(",")))
                        .map(String::trim).filter(v -> !v.isEmpty()).distinct().toList();
                if (planIds.size() > MAX_PLANS_PER_REQUEST) {
                    throw new com.cretas.aims.exception.BusinessException(400,
                            "一次最多处理 " + MAX_PLANS_PER_REQUEST + " 个生产计划");
                }
                planIds.forEach(planId -> products.addAll(planProducts(factoryId, planId)));
            }
            case PLAN_PATH -> products.addAll(planProducts(factoryId, vars.get(guard.name())));
            case MATERIAL_REQUISITION_PATH -> {
                String raw = vars.get(guard.name());
                if (raw != null) {
                    requisitionRepository.findById(raw)
                            .filter(r -> factoryId.equals(r.getFactoryId()))
                            .ifPresent(r -> products.addAll(planProducts(factoryId, r.getProductionPlanId())));
                }
            }
            case BATCH_PATH -> parseLong(vars.get(guard.name()))
                    .flatMap(id -> batchRepository.findByIdAndFactoryId(id, factoryId))
                    .ifPresent(batch -> {
                        if (batch.getProductTypeId() != null) products.add(batch.getProductTypeId());
                        products.addAll(planProducts(factoryId, batch.getProductionPlanId()));
                    });
            default -> resolveProduct(guard, factoryId, vars, request).ifPresent(products::add);
        }
        return new ArrayList<>(products);
    }

    static final int MAX_PLANS_PER_REQUEST = 20;

    /** 计划牵涉的全部产品 (多产出计划: 锚点 + 各成品 + 钉住配方的产品, 复审 H-A)。 */
    private Set<String> planProducts(String factoryId, String planId) {
        if (planId == null || planId.isBlank()) return Set.of();
        return planRepository.findByIdAndFactoryId(planId, factoryId)
                .map(plan -> com.cretas.aims.service.processsecret.ProcessSecretPlanProducts.of(plan,
                        recipeId -> recipeProduct(factoryId, recipeId)))
                .orElse(Set.of());
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
            // 以下几种在 resolveProducts 里按「多产品」处理
            case BATCH_PATH, PLAN_PATH, MATERIAL_REQUISITION_PATH, PLAN_PARAM_LIST -> Optional.empty();
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
