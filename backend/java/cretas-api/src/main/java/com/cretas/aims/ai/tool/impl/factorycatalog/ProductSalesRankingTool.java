package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ranks actual sales-order lines, never dashboard aggregates. */
@Component
public class ProductSalesRankingTool extends AbstractBusinessTool {
    private final ProductSalesRankingService rankingService;

    public ProductSalesRankingTool(ProductSalesRankingService rankingService) {
        this.rankingService = rankingService;
    }

    @Override public String getToolName() { return "product_sales_ranking"; }
    @Override public String getDescription() {
        return "按真实客户销售订单行聚合产品销售数量和金额，支持日期范围、包装换算和数据权限";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "startDate", Map.of("type", "string", "format", "date"),
                "endDate", Map.of("type", "string", "format", "date"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100),
                // 只按销售金额排序。点名按数量 / 销量排时明确说不支持, 不悄悄按金额返回 (INTENT-DISTILL D1)
                "metric", Map.of("type", "string", "enum", List.of("amount"), "default", "amount",
                        "description", "排序口径，目前只支持 amount (销售金额)",
                        com.cretas.aims.ai.tool.UnsupportedOptionGuard.OPTION_WORDS,
                        Map.of("amount", List.of("金额", "销售额")),
                        com.cretas.aims.ai.tool.UnsupportedOptionGuard.UNSUPPORTED,
                        Map.of("quantity", Map.of("label", "销售数量", "words", List.of("数量", "销量", "件数"))))
        ), "required", List.of("startDate", "endDate"));
    }

    @Override protected List<String> getRequiredParameters() {
        return List.of("startDate", "endDate");
    }

    private Map<String, Object> executeRanking(String factoryId, Map<String, Object> params,
                                               Map<String, Object> context, boolean preview) {
        LocalDate start = parseDate(getString(params, "startDate"), "startDate");
        LocalDate end = parseDate(getString(params, "endDate"), "endDate");
        if (start.isAfter(end)) throw new IllegalArgumentException("startDate不能晚于endDate");
        String metric = getString(params, "metric");
        if (metric != null && !metric.isBlank() && !"amount".equalsIgnoreCase(metric.trim())) {
            throw new IllegalArgumentException("产品销售排行只支持按销售金额排序，不支持按" + metric + "排序");
        }
        int limit = getInteger(params, "limit", 20);
        List<SalesOrderItemRepository.SalesRankingRow> rows = rankingService.rank(factoryId, start, end);
        List<Map<String, Object>> ranking = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, rows.size()); i++) {
            SalesOrderItemRepository.SalesRankingRow row = rows.get(i);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("rank", i + 1);
            item.put("productTypeId", row.getProductTypeId());
            item.put("productName", row.getProductName());
            item.put("quantity", row.getQuantity());
            item.put("unit", row.getUnit());
            item.put("quantitiesByUnit", row.getQuantitiesByUnit());
            if (replyShowsAmount(context, "amount")) item.put("amount", row.getAmount());
            ranking.add(item);
        }
        return Map.of("status", preview ? "PREVIEW" : "COMPLETED",
                "startDate", start, "endDate", end, "ranking", ranking,
                "source", "SALES_ORDER_ITEMS", "quantityGrouping", "PRODUCT_AND_NORMALIZED_UNIT",
                "message", "按产品销售总金额排序；无法换算的数量按单位分桶，数量不跨单位相加。", "willWrite", false);
    }

    private LocalDate parseDate(String value, String name) {
        try { return LocalDate.parse(value); }
        catch (RuntimeException e) { throw new IllegalArgumentException(name + "必须是YYYY-MM-DD"); }
    }

    @Override protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                                      Map<String, Object> context) {
        return executeRanking(factoryId, params, context, false);
    }

    @Override public boolean supportsPreview() { return true; }
    @Override protected Map<String, Object> doPreview(String factoryId, Map<String, Object> params,
                                                      Map<String, Object> context) {
        return executeRanking(factoryId, params, context, true);
    }
    @Override public AccessMode getAccessMode() { return AccessMode.READ; }
    @Override public ActionType getActionType() { return ActionType.READ; }
    @Override public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("sales:read", "sales:read_write");
    }
    @Override public boolean requiresPermission() { return true; }
}
