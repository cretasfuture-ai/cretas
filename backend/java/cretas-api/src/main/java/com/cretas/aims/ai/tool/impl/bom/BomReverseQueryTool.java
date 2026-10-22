package com.cretas.aims.ai.tool.impl.bom;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.bom.BomItemUsage;
import com.cretas.aims.dto.bom.MaterialReplacementImpact;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.service.bom.BomReverseQueryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * M-BOM-VER-1 — 物料 → BOM 反查 (Sprint 3 Track-H).
 *
 * <p>mode=RECIPES: 哪些 BOM 用了某物料 (recipe list).
 * mode=USAGE: 含 BomRecipeItem 用量/成本明细 (per-item rows).
 * mode=REPLACE_ANALYSIS: 替换影响分析 (oldMat → newMat 总 cost delta + per-recipe breakdown).
 *
 * 适用场景: "哪些产品用了昌弘鸡精?" / "把鸡精换成味精, 成本影响?" / 物料停产影响评估.
 */
@Slf4j
@Component
public class BomReverseQueryTool extends AbstractBusinessTool {

    @Autowired
    private BomReverseQueryService reverseQueryService;

    @Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretRecipeGuard processSecretGuard;

    private static Map<String, Object> restrictedSummary(String recipeId, String productTypeId) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("recipeId", recipeId);
        m.put("productTypeId", productTypeId);
        m.put("processSecret", Map.of("visibility", "RESTRICTED"));
        return m;
    }

    @Override
    public String getToolName() {
        return "bom_reverse_query";
    }

    @Override
    public String getDescription() {
        return "物料 → BOM 反查. mode=RECIPES 查哪些 BOM 用了此物料, "
             + "mode=USAGE 含 item 用量明细, mode=REPLACE_ANALYSIS 替换影响 cost delta. "
             + "适用场景: 物料停产影响评估 / 替换为低价等效物料的成本测算 / "
             + "客户问 '哪些产品用了昌弘鸡精' 类型查询.";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> properties = new HashMap<>();
        properties.put("mode", Map.of("type", "string",
                "enum", Arrays.asList("RECIPES", "USAGE", "REPLACE_ANALYSIS"),
                "description", "查询模式"));
        properties.put("materialId", Map.of("type", "string",
                "description", "物料编号 (反查配方、用量时是目标物料; 做替换分析时是被替换的物料)"));
        properties.put("newMaterialId", Map.of("type", "string",
                "description", "新物料 ID (REPLACE_ANALYSIS 必填)"));

        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", Arrays.asList("mode", "materialId"));
        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Arrays.asList("mode", "materialId");
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String mode = getString(params, "mode");
        String materialId = getString(params, "materialId");

        switch (mode.toUpperCase()) {
            case "RECIPES": {
                List<BomRecipe> recipes = reverseQueryService.findRecipesByMaterial(factoryId, materialId);
                // 工艺保密: 受保护且无权的配方只给摘要 (AI 工具结果不能比人直接调接口更宽)
                List<Object> visible = new java.util.ArrayList<>();
                for (BomRecipe r : recipes) {
                    visible.add(processSecretGuard.permitsFullContent(factoryId, r.getId(), "AI tool bom_reverse_query")
                            ? r : restrictedSummary(r.getId(), r.getProductTypeId()));
                }
                return buildSimpleResult("反查 " + recipes.size() + " 个 BOM 配方",
                        Map.of("count", recipes.size(), "recipes", visible));
            }
            case "USAGE": {
                List<BomItemUsage> usages = reverseQueryService.findUsageByMaterial(factoryId, materialId);
                List<Object> visible = new java.util.ArrayList<>();
                for (BomItemUsage u : usages) {
                    visible.add(processSecretGuard.permitsFullContent(factoryId, u.getRecipeId(), "AI tool bom_reverse_query")
                            ? u : restrictedSummary(u.getRecipeId(), u.getProductTypeId()));
                }
                return buildSimpleResult("反查 " + usages.size() + " 条用量明细",
                        Map.of("count", usages.size(), "usages", visible));
            }
            case "REPLACE_ANALYSIS": {
                String newMaterialId = getString(params, "newMaterialId");
                if (newMaterialId == null || newMaterialId.isBlank()) {
                    throw new IllegalArgumentException("mode=REPLACE_ANALYSIS 必须提供 newMaterialId");
                }
                MaterialReplacementImpact impact = reverseQueryService.analyzeReplacement(
                        factoryId, materialId, newMaterialId);
                // 工艺保密: 成本差 ÷ 价差 = 用量, 受保护且无权的配方不出现在明细里
                if (impact.getRecipeBreakdown() != null) {
                    int before = impact.getRecipeBreakdown().size();
                    impact.getRecipeBreakdown().removeIf(ri -> !processSecretGuard.permitsFullContent(
                            factoryId, ri.getRecipeId(), "AI tool bom_reverse_query"));
                    int hidden = before - impact.getRecipeBreakdown().size();
                    if (hidden > 0 && impact.getWarnings() != null) {
                        impact.getWarnings().add(hidden + " 个保密配方的明细未展示");
                    }
                }
                return buildSimpleResult("替换影响: " + impact.getAffectedRecipeCount() + " BOM 受影响, "
                        + "totalCostDelta=" + impact.getTotalCostDelta(), impact);
            }
            default:
                throw new IllegalArgumentException("无效 mode: " + mode
                        + " (限 RECIPES / USAGE / REPLACE_ANALYSIS)");
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
