package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.dto.bom.BomCostSummaryDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.BomService;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * BOM 成本排行 (只读, 对应意图 REPORT_BOM_COST_RANK) —— 「BOM 成本最高的产品」。
 *
 * <p>成本 = {@link BomService#calculateProductCost} 的现行 BOM 估算 (每 1 个成品; 该口径当前只归集包材行,
 * 原辅料 / 人工 / 均摊不在 BOM 归集, 文案如实说明, 不冒充完整成本)。产品名 → productTypeId 由在用产品目录解析;
 * 给了产品名就只排名称匹配的产品 (没有匹配如实说), 不给则排所有有 BOM 的在用产品。
 * 缺单价行使成本被低估的产品照排并标注; 工艺保密而无权查看的产品不进排行 (排名本身泄露成本高低) 并说明个数。
 * 无成本查看权限的调用者: 金额与图一律不出。
 */
@Component
public class ReportBomCostRankTool extends AbstractBusinessTool {

    static final int DEFAULT_LIMIT = 10;
    static final int MAX_LIMIT = 30;

    @Autowired
    private BomService bomService;

    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Autowired(required = false)
    private ProcessSecretLlmCostScope llmCostScope = ProcessSecretLlmCostScope.standalone();

    @Override
    public String getToolName() {
        return "report_bom_cost_rank";
    }

    @Override
    public String getDescription() {
        return "查询BOM成本排行（只读）：按产品现行BOM成本（每1个成品）从高到低排序并附柱图，可按产品名筛选（自动解析产品ID）。"
                + "BOM成本口径当前仅归集包材，缺单价的会标注，不估算；保密产品不进排行。"
                + "适用：BOM成本最高的产品、哪个产品BOM成本高、某产品的BOM成本。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return ReportToolSchemas.withOptionalExtraction(Map.of(
                "type", "object",
                "properties", Map.of(
                        "productName", Map.of("type", "string", "description", "产品名（可选）；有完全同名只看它，否则列出所有包含该名的产品；不填则排所有有 BOM 的产品"),
                        "order", Map.of("type", "string", "description", "highest=成本最高在前(默认) / lowest=最低在前"),
                        "limit", Map.of("type", "integer", "description", "返回前几名，默认 10，最多 30")
                ),
                "required", List.of()));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        // 产品名本次请求解析一次; 回复开头写明按哪个产品 / 全厂统计 (「卤牛肉片」按卤牛肉回答时也看得出来)
        ReportToolSchemas.ProductResolution product = ReportToolSchemas.resolveProduct(productTypeRepository, factoryId, getString(params, "productName"));
        return ReportToolSchemas.withNote(answer(factoryId, params, context, product), product.leadIn(false));
    }

    private Map<String, Object> answer(String factoryId, Map<String, Object> params, Map<String, Object> context,
                                       ReportToolSchemas.ProductResolution product) throws Exception {
        String keyword = product.label();
        boolean highestFirst = !"lowest".equalsIgnoreCase(getString(params, "order", "highest"));
        int limit = DEFAULT_LIMIT;
        Object limitRaw = params.get("limit");
        if (limitRaw instanceof Number n && n.intValue() > 0) {
            limit = Math.min(n.intValue(), MAX_LIMIT);
        }
        boolean showAmounts = replyShowsAmounts(context, "totalCost", "unitCost");

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("productFilter", keyword);
        data.put("basis", "现行 BOM 估算，每 1 个成品；该口径当前只归集包材行，原辅料/人工/均摊不在 BOM 归集");
        if (!showAmounts) {
            return buildSimpleResult("BOM 成本金额按当前账号权限不显示；需要请用有成本查看权限的账号。", data);
        }

        // 产品名按口径表 B 解析 (只锁定名称完整出现在原话里的产品, 可多个; 没找到如实说, 名称含该词的只列名不排行)
        String resolution = product.refusalMessage("给出 BOM 成本", false);
        if (resolution != null) {
            data.put("candidates", product.candidateNames());
            return buildSimpleResult(resolution, data);
        }
        Set<String> withBom = new LinkedHashSet<>(orEmpty(bomService.getProductTypesWithBom(factoryId)));
        List<ProductType> types = orEmpty(product.catalog());
        Set<String> pickedIds = product.scope() == ReportToolSchemas.ProductScope.MATCHED ? product.productIds() : null;
        List<ProductType> matched = new ArrayList<>();
        for (ProductType t : types) {
            if (t.getId() == null || !withBom.contains(t.getId())) continue;
            if (pickedIds != null && !pickedIds.contains(t.getId())) continue;
            matched.add(t);
        }
        if (matched.isEmpty()) {
            return buildSimpleResult(keyword != null
                    ? "没有找到名称含「" + keyword + "」且配置了 BOM 的在用产品，无法给出 BOM 成本。"
                    : "没有配置了 BOM 的在用产品，无法给出 BOM 成本排行。", data);
        }

        ProcessSecretLlmCostScope.Excluded secret = llmCostScope == null
                ? ProcessSecretLlmCostScope.Excluded.NONE : llmCostScope.forCurrentCaller(factoryId);
        Set<String> withheldIds = secret.withheldProducts();
        Set<String> withheldProtected = new LinkedHashSet<>();
        int noPackagingRows = 0;
        List<Map<String, Object>> all = new ArrayList<>();
        for (ProductType t : matched) {
            if (withheldIds.contains(t.getId())) {
                // 计数只数真正受保护的产品 (计划锚点 / 同计划联产的普通产品不算, 不暴露联产结构)
                if (secret.protectedProductTypeIds().contains(t.getId())) withheldProtected.add(t.getId());
                continue;
            }
            BomCostSummaryDTO s = bomService.calculateProductCost(factoryId, t.getId());
            if (s == null || s.getTotalCost() == null) continue;
            // calculateProductCost 无可归集 (包材) 行时给 0 不是 null: 0 不是「不要钱」是「此处没有可归集的行」, 不进榜
            boolean hasAggregatableRow = s.getMaterialCosts() != null
                    && s.getMaterialCosts().stream().anyMatch(i -> i.getSubtotal() != null);
            if (!hasAggregatableRow) {
                noPackagingRows++;
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productTypeId", t.getId());
            row.put("productName", t.getName());
            row.put("totalCost", s.getTotalCost().setScale(2, RoundingMode.HALF_UP));
            row.put("costUnit", s.getCostUnit());
            row.put("hasMissingPrice", s.isHasMissingPrice());
            row.put("missingPriceCount", s.getMissingPriceCount());
            all.add(row);
        }
        // 不同单位 (元/kg、元/盒) 不混排: 只排人数最多的主单位组, 其余单位的产品个数单独说明 (同 #1732 单位不相比)
        Map<String, List<Map<String, Object>>> byUnit = new LinkedHashMap<>();
        for (Map<String, Object> r : all) {
            byUnit.computeIfAbsent(String.valueOf(r.get("costUnit")), k -> new ArrayList<>()).add(r);
        }
        String mainUnit = null;
        for (Map.Entry<String, List<Map<String, Object>>> e : byUnit.entrySet()) {
            if (mainUnit == null || e.getValue().size() > byUnit.get(mainUnit).size()) mainUnit = e.getKey();
        }
        List<Map<String, Object>> ranked = mainUnit == null ? new ArrayList<>() : new ArrayList<>(byUnit.get(mainUnit));
        Map<String, Integer> otherUnits = new LinkedHashMap<>();
        final String main = mainUnit;
        byUnit.forEach((u, rows) -> { if (!u.equals(main)) otherUnits.put(u, rows.size()); });
        Comparator<Map<String, Object>> byCost = Comparator.comparing(r -> (BigDecimal) r.get("totalCost"));
        ranked.sort(highestFirst ? byCost.reversed() : byCost);
        List<Map<String, Object>> shown = ranked.size() > limit ? new ArrayList<>(ranked.subList(0, limit)) : ranked;

        String unitLabel = mainUnit == null || "null".equals(mainUnit) ? "元/成品" : mainUnit;
        data.put("order", highestFirst ? "highest" : "lowest");
        data.put("productCount", ranked.size());
        data.put("withheldProductCount", withheldProtected.size());
        data.put("noPackagingRowProductCount", noPackagingRows);
        data.put("costUnit", unitLabel);
        data.put("otherUnitProductCounts", otherUnits);
        data.put("rows", shown);
        if (!shown.isEmpty()) {
            List<String> names = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (Map<String, Object> r : shown) {
                names.add(String.valueOf(r.get("productName")));
                values.add((BigDecimal) r.get("totalCost"));
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("tooltip", Map.of("trigger", "axis"));
            option.put("grid", Map.of("left", 60, "right", 20, "bottom", 70, "containLabel", true));
            option.put("xAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("interval", 0, "rotate", 25)));
            option.put("yAxis", Map.of("type", "value", "name", unitLabel));
            option.put("series", List.of(Map.of("type", "bar", "name", "BOM成本", "data", values)));
            data.put("charts", List.of(Map.of("type", "bar",
                    "title", "BOM 成本（" + unitLabel + "，" + (highestFirst ? "最高" : "最低") + "前 " + shown.size() + "）", "option", option)));
        }

        StringBuilder sb = new StringBuilder();
        if (shown.isEmpty()) {
            sb.append("没有能算出 BOM 成本的产品（没有可归集成本行的产品不进排行）。");
        } else {
            sb.append("BOM 成本").append(highestFirst ? "最高" : "最低").append("的 ").append(shown.size()).append(" 个产品（").append(unitLabel)
                    .append("，共 ").append(ranked.size()).append(" 个有 BOM 成本）：");
            int i = 0;
            for (Map<String, Object> r : shown) {
                sb.append("\n").append(++i).append(". ").append(r.get("productName")).append("：¥").append(r.get("totalCost"));
                if (Boolean.TRUE.equals(r.get("hasMissingPrice"))) {
                    sb.append("，有 ").append(r.get("missingPriceCount")).append(" 行缺单价，成本被低估");
                }
            }
        }
        if (!otherUnits.isEmpty()) {
            sb.append("\n另有产品按其他单位计成本（").append(String.join("、", otherUnits.keySet()))
                    .append("），单位不同不能一起排，未计入排行，需要请指定产品名。");
        }
        if (noPackagingRows > 0) {
            sb.append("\n另有 ").append(noPackagingRows).append(" 个产品的 BOM 里没有可归集成本的行（当前口径只归集包材），未进排行。");
        }
        if (!withheldIds.isEmpty() && matched.stream().anyMatch(t -> withheldIds.contains(t.getId()))) {
            sb.append("\n").append(ProcessSecretLlmCostScope.note(withheldProtected.size()));
        }
        sb.append("\n口径：现行 BOM 估算，每 1 个成品；该口径当前只归集包材成本，原辅料、人工、均摊不在 BOM 归集，不是完整生产成本。");
        return buildSimpleResult(sb.toString(), data);
    }

    private static <T> List<T> orEmpty(List<T> l) {
        return l == null ? List.of() : l;
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** 与 report_bom_cost / 成本分析同口径: analytics:read_write。 */
    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("analytics:read_write");
    }

    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    @Override
    public ActionType getActionType() {
        return ActionType.READ;
    }

    @Override
    public RiskLevel getRiskLevel() {
        return RiskLevel.LOW;
    }

    @Override
    public boolean supportsPreview() {
        return false;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public Set<String> getDomainTags() {
        return Set.of("report", "cost");
    }
}
