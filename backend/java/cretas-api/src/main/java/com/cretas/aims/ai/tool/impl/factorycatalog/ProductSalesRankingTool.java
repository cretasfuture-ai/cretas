package com.cretas.aims.ai.tool.impl.factorycatalog;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.service.intent.catalog.ProductSalesRankingService;
import com.cretas.aims.domain.OrderUsageWhitelists;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.service.inventory.SalesService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ranks actual sales-order lines, never dashboard aggregates. */
@Component
public class ProductSalesRankingTool extends AbstractBusinessTool {
    private final ProductSalesRankingService rankingService;
    /** 只在「X月比Y月涨了 / 主要是哪个客户带来的」拆分里用 (按客户汇总订单金额); 没有它就只能按产品拆。 */
    @Autowired(required = false)
    private SalesService salesService;

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
        Map<String, Object> growth = tryGrowthBreakdown(factoryId, params, context, preview, LocalDate.now());
        if (growth != null) return growth;
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

    // ------------------------------------------------------------------ 「X月比Y月涨了, 主要是哪个客户/产品带来的」

    private static final Pattern GROWTH = Pattern.compile(
            "(\\d{1,2}|[一二三四五六七八九十]{1,2})\\s*月(?:份)?\\s*(?:相)?比(?:起)?\\s*(\\d{1,2}|[一二三四五六七八九十]{1,2})\\s*月");
    private static final Pattern GROWTH_WORDS = Pattern.compile("涨|增|长|升|降|跌|下滑|减少|变化|差");
    private static final Pattern STATED_PCT = Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");
    private static final int TOP_N = 5;
    private static final int MAX_ORDER_PAGES = 50;

    static int monthNumber(String text) {
        if (text.chars().allMatch(Character::isDigit)) return Integer.parseInt(text);
        String digits = "一二三四五六七八九";
        if (text.equals("十")) return 10;
        if (text.length() == 2 && text.charAt(0) == '十') return 10 + digits.indexOf(text.charAt(1)) + 1;
        return text.length() == 1 ? digits.indexOf(text.charAt(0)) + 1 : -1;
    }

    /** 原话里的「X月比Y月 + 涨跌词」: 返回 {当期月初, 当期月末, 基期月初, 基期月末}; 不是这种问法返回 null。 */
    static LocalDate[] growthPeriods(String said, LocalDate today) {
        if (said == null) return null;
        Matcher m = GROWTH.matcher(said);
        if (!m.find() || !GROWTH_WORDS.matcher(said).find()) return null;
        int cur = monthNumber(m.group(1));
        int base = monthNumber(m.group(2));
        if (cur < 1 || cur > 12 || base < 1 || base > 12 || cur == base) return null;
        int curYear = cur <= today.getMonthValue() ? today.getYear() : today.getYear() - 1;
        int baseYear = base <= cur ? curYear : curYear - 1;
        LocalDate cs = LocalDate.of(curYear, cur, 1);
        LocalDate bs = LocalDate.of(baseYear, base, 1);
        return new LocalDate[]{cs, cs.withDayOfMonth(cs.lengthOfMonth()), bs, bs.withDayOfMonth(bs.lengthOfMonth())};
    }

    private Map<String, BigDecimal> productTotals(String factoryId, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (SalesOrderItemRepository.SalesRankingRow row : rankingService.rank(factoryId, from, to)) {
            String name = row.getProductName() == null || row.getProductName().isBlank() ? "未命名产品" : row.getProductName();
            totals.merge(name, row.getAmount() == null ? BigDecimal.ZERO : row.getAmount(), BigDecimal::add);
        }
        return totals;
    }

    private Map<String, BigDecimal> customerTotals(String factoryId, LocalDate from, LocalDate to) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (int page = 1; page <= MAX_ORDER_PAGES; page++) {
            PageResponse<SalesOrder> orders = salesService.getSalesOrdersFiltered(factoryId, null, from, to, page, 100);
            if (orders == null || orders.getContent() == null) break;
            for (SalesOrder order : orders.getContent()) {
                if (order.getStatus() == null || !OrderUsageWhitelists.SO_ALL.contains(order.getStatus())) continue;
                String name = order.getCustomerName() == null || order.getCustomerName().isBlank()
                        ? "未填写客户" : order.getCustomerName();
                totals.merge(name, order.getTotalAmount() == null ? BigDecimal.ZERO : order.getTotalAmount(),
                        BigDecimal::add);
            }
            if (page >= orders.getTotalPages()) break;
        }
        return totals;
    }

    private static Map<String, Object> contributions(Map<String, BigDecimal> cur, Map<String, BigDecimal> base,
                                                     String nameKey, boolean showAmounts) {
        Set<String> names = new LinkedHashSet<>(cur.keySet());
        names.addAll(base.keySet());
        BigDecimal curTotal = cur.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal baseTotal = base.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalDelta = curTotal.subtract(baseTotal);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String n : names) {
            BigDecimal c = cur.getOrDefault(n, BigDecimal.ZERO);
            BigDecimal b = base.getOrDefault(n, BigDecimal.ZERO);
            BigDecimal d = c.subtract(b);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(nameKey, n);
            if (showAmounts) {
                row.put("baseAmount", b);
                row.put("currentAmount", c);
                row.put("delta", d);
            }
            row.put("sharePct", totalDelta.signum() == 0 ? null
                    : d.multiply(BigDecimal.valueOf(100)).divide(totalDelta, 1, RoundingMode.HALF_UP));
            row.put("_d", d);
            rows.add(row);
        }
        // 按增量贡献降序 (增得最多的在前)
        rows.sort(Comparator.comparing((Map<String, Object> r) -> (BigDecimal) r.get("_d")).reversed());
        rows.forEach(r -> r.remove("_d"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("top", new ArrayList<>(rows.subList(0, Math.min(TOP_N, rows.size()))));
        out.put("totalGrowthPct", baseTotal.signum() == 0 ? null
                : totalDelta.multiply(BigDecimal.valueOf(100)).divide(baseTotal, 1, RoundingMode.HALF_UP));
        if (showAmounts) {
            out.put("baseTotal", baseTotal);
            out.put("currentTotal", curTotal);
            out.put("deltaTotal", totalDelta);
        }
        return out;
    }

    Map<String, Object> tryGrowthBreakdown(String factoryId, Map<String, Object> params, Map<String, Object> context,
                                           boolean preview, LocalDate today) {
        String said = getString(params, "originalUserInput") != null
                ? getString(params, "originalUserInput") : getString(params, "userInput");
        LocalDate[] p = growthPeriods(said, today);
        if (p == null) return null;
        boolean showAmounts = replyShowsAmount(context, "amount");
        Map<String, Object> product = contributions(productTotals(factoryId, p[0], p[1]),
                productTotals(factoryId, p[2], p[3]), "productName", showAmounts);
        Map<String, Object> customer = salesService == null ? null
                : contributions(customerTotals(factoryId, p[0], p[1]), customerTotals(factoryId, p[2], p[3]),
                "customerName", showAmounts);
        StringBuilder msg = new StringBuilder();
        Object sysPct = (customer != null ? customer : product).get("totalGrowthPct");
        msg.append(p[0].getMonthValue()).append("月比").append(p[2].getMonthValue()).append("月，系统算出的销售额变化为")
                .append(sysPct == null ? "无法计算（基期没有销售）" : (sysPct + "%"));
        Matcher stated = STATED_PCT.matcher(said);
        if (stated.find()) msg.append("（您提到的 ").append(stated.group(1)).append("% 仅作参照，以系统数据为准）");
        msg.append("。\n");
        appendTop(msg, "客户", customer, "customerName");
        appendTop(msg, "产品", product, "productName");
        msg.append("口径：客户按销售订单金额（已确认及之后状态，与「各客户销售额排行」一致），产品按销售订单行金额（与产品销售排行一致），"
                + "两者口径不同、总额可能略有差异；按增量从大到小列前 ").append(TOP_N).append(" 名。");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", preview ? "PREVIEW" : "COMPLETED");
        result.put("mode", "GROWTH_BREAKDOWN");
        result.put("currentPeriod", Map.of("startDate", p[0], "endDate", p[1]));
        result.put("basePeriod", Map.of("startDate", p[2], "endDate", p[3]));
        if (customer != null) result.put("byCustomer", customer);
        result.put("byProduct", product);
        result.put("source", "SALES_ORDERS_AND_ITEMS");
        result.put("message", msg.toString());
        result.put("willWrite", false);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static void appendTop(StringBuilder msg, String label, Map<String, Object> dim, String nameKey) {
        if (dim == null) return;
        List<Map<String, Object>> top = (List<Map<String, Object>>) dim.get("top");
        msg.append("按").append(label).append("拆分，增量贡献前几名：");
        int i = 1;
        for (Map<String, Object> r : top) {
            msg.append(i++).append(". ").append(r.get(nameKey));
            if (r.containsKey("delta")) {
                msg.append("（").append(r.get("baseAmount")).append(" → ").append(r.get("currentAmount"))
                        .append("，增量 ").append(r.get("delta")).append("）");
            }
            if (r.get("sharePct") != null) msg.append(" 占总增量 ").append(r.get("sharePct")).append("%");
            msg.append("；");
        }
        msg.append("\n");
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
