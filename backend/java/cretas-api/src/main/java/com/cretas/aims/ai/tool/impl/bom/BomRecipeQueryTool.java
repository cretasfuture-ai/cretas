package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomRecipeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-1 BOM 配方查询 (Track D1).
 *
 * 适用场景: "红烧肉的 BOM 是什么?" / "猪蹄 SKU-201 当前配方"
 */
@Slf4j
@Component
public class BomRecipeQueryTool extends AbstractBusinessTool {

    @Autowired
    private BomRecipeService recipeService;

    @Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretAccessService processSecretAccessService;

    /** 按产品名找产品: 与成本 / 毛利类报表工具同一份解析 (去「演示·」前缀与规格, 多命中列候选, 查不到如实说)。 */
    @Autowired
    private com.cretas.aims.repository.ProductTypeRepository productTypeRepository;

    @Override
    public String getToolName() {
        return "bom_recipe_query";
    }

    @Override
    public String getDescription() {
        return "查询 BOM 配方. 支持按产品名称取当前生效版本 (模糊匹配, 可带规格), 或按配方编号取详情. "
             + "适用场景: 查看某产品当前 BOM / 查询配方明细 / 看出成率 / 看物料构成. "
             + "不用于问成本构成、成本占比、成本怎么算 (那是成本查询).";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> productTypeId = new HashMap<>();
        productTypeId.put("type", "string");
        productTypeId.put("description", "产品类型 — 取当前生效的 BOM 配方. 与配方编号二选一.");

        Map<String, Object> productName = new HashMap<>();
        productName.put("type", "string");
        productName.put("description", "产品名称 (如「酱卤牛肉」, 可带「演示·」前缀或规格, 模糊匹配) — 取当前生效的 BOM 配方.");

        Map<String, Object> recipeId = new HashMap<>();
        recipeId.put("type", "string");
        recipeId.put("description", "BOM 配方编号 — 取配方详情. 与产品类型二选一.");

        Map<String, Object> properties = new HashMap<>();
        properties.put("productName", productName);
        properties.put("productTypeId", productTypeId);
        properties.put("recipeId", recipeId);

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Collections.emptyList());  // 二选一, 走自定义校验
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String productTypeId = getString(params, "productTypeId");
        String recipeId = getString(params, "recipeId");

        if ((productTypeId == null || productTypeId.isBlank())
                && (recipeId == null || recipeId.isBlank())) {
            // 没给编号: 按产品名 (抽参给的 productName, 没有就用原话) 在本厂在用产品里找, 不把字段名抛给用户
            String said = getString(params, "productName");
            if (said == null || said.isBlank()) {
                said = getString(params, "originalUserInput") != null
                        ? getString(params, "originalUserInput") : getString(params, "userInput");
            }
            com.cretas.aims.ai.tool.impl.report.ProductNameLookup.Result found =
                    com.cretas.aims.ai.tool.impl.report.ProductNameLookup.resolve(
                            productTypeRepository, factoryId, said, "查看配方");
            switch (found.outcome()) {
                case ONE -> productTypeId = found.only().getId();
                case MANY -> {
                    return buildSimpleResult(found.message(), Map.of("status", "NEED_MORE_INFO",
                            "candidates", found.products().stream().map(p -> p.getName()).toList()));
                }
                case NOT_FOUND -> {
                    return buildSimpleResult(found.message(), Map.of("found", false));
                }
                default -> {
                    return buildSimpleResult("请告诉我是哪个产品或配方",
                            Map.of("status", "NEED_MORE_INFO"));
                }
            }
        }

        BomRecipe recipe;
        if (recipeId != null && !recipeId.isBlank()) {
            recipe = recipeService.getRecipe(factoryId, recipeId);
        } else {
            Optional<BomRecipe> opt = recipeService.getCurrentRecipe(factoryId, productTypeId);
            if (opt.isEmpty()) {
                return buildSimpleResult("这个产品还没有当前生效的配方",
                        Map.of("found", false, "productTypeId", productTypeId));
            }
            recipe = opt.get();
        }

        // 工艺保密: AI 工具结果不能比人直接调接口更宽; 识别不出调用者也按无权处理 (fail-closed)
        com.cretas.aims.security.AuthenticatedActor actor =
                com.cretas.aims.security.AuthenticatedActor.fromCurrentExecution().orElse(null);
        com.cretas.aims.service.processsecret.ProcessSecretAccessService.Decision decision =
                processSecretAccessService.decide(actor, factoryId, recipe.getProductTypeId(),
                        com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL);
        if (decision.isProtected()) {
            boolean recorded = processSecretAccessService.audit(factoryId, recipe.getProductTypeId(), actor,
                    com.cretas.aims.entity.processsecret.ProcessSecretAction.VIEW_FULL,
                    decision.permitted() ? "ALLOW" : "DENY", decision.reason(), "AI tool bom_recipe_query");
            if (!decision.permitted() || !recorded) {
                Map<String, Object> summary = new java.util.LinkedHashMap<>();
                summary.put("found", true);
                summary.put("recipeId", recipe.getId());
                summary.put("productTypeId", recipe.getProductTypeId());
                summary.put("productName", recipe.getProductName());
                summary.put("processSecret", Map.of("visibility", "RESTRICTED"));
                return buildSimpleResult(
                        com.cretas.aims.security.ProcessSecretResponseAdvice.RESTRICTED_MESSAGE, summary);
            }
        }

        log.info("BOM 查询: factoryId={}, recipeId={}, productTypeId={}",
                factoryId, recipe.getId(), recipe.getProductTypeId());
        return buildSimpleResult("查询成功", recipe);
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
