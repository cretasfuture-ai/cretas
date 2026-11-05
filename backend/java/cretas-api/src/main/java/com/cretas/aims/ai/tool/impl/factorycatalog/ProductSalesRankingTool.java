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
        return "按真实销售订单金额排序（不是实收收入）；支持日期范围和前N名，数量仅按单位分桶展示，不支持按销量排序";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "startDate", Map.of("type", "string", "format", "date"),
                "endDate", Map.of("type", "string", "format", "date"),
                "metric", Map.of("type", "string", "enum", List.of("amount"), "description", "仅支持销售订单金额排序；quantity不可执行"),
                "limit", Map.of("type", "integer", "minimum", 1, "maximum", 100)
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
        String metric = getString(params, "metric", "amount");
        if (!"amount".equals(metric)) throw new IllegalArgumentException("当前产品排行仅支持销售订单金额排序，不支持销量排名；数量按单位分桶，不能跨单位相加。请确认是否改看金额排行。");
        int limit = getInteger(params, "limit", 20);
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("排行条数必须在1到100之间");
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
                "metric", "amount", "limit", limit,
                "message", "口径：" + start + "至" + end + "，按销售订单金额降序，前" + limit
                        + "名（非实收收入）；数量按单位分桶，不跨单位相加。", "willWrite", false);
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
