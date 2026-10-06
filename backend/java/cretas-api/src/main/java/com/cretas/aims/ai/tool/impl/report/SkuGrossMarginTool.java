package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.service.reports.OperatingReportAssembler;
import com.cretas.aims.service.reports.OperatingReportGrossMarginQuery;
import com.cretas.aims.service.yield.ThreePriceComparisonService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * SKU 毛利率 —— 两种口径<b>分开给, 各自标注定义</b> (owner 2026-10-06 裁定):
 *
 * <ol>
 *   <li><b>实际销售毛利</b>: 直接复用 {@link OperatingReportGrossMarginQuery} (销售毛利报表 / 经营报告同一条 SQL,
 *       Python {@code sales_preset.py /gross-margin} 的 Java 镜像)。折后未税收入 − 成本单价×数量,
 *       <b>只算已回填成本的明细</b>, 并给出成本覆盖率。</li>
 *   <li><b>目录价理论毛利</b>: 复用 {@link ThreePriceComparisonService#catalogMarginFor} (三价对比看板同一口径):
 *       {@code ProductType.unitPrice} vs 最近一条已算出单位成本的批次。</li>
 * </ol>
 *
 * <p>硬规矩: 缺价 / 缺成本 / 没销售一律<b>明说缺哪一边并给 null</b>, 不兜底成 0、不估算、不用另一口径顶替。
 * 机器可读字段里所有「算不出」的数都是 null; 比率一律是百分数 (42.5 = 42.5%)。
 *
 * <p>顶层 {@code marginRate} (给 web-admin finance/sku-margin 页读的那个名字) = 实际销售毛利的百分数,
 * 并用 {@code marginRateBasis=realized} 标明; 目录价口径在 {@code catalog.marginRate}。
 */
@Slf4j
@Component
public class SkuGrossMarginTool extends AbstractBusinessTool {

    static final int DEFAULT_PERIOD_DAYS = 30;
    static final int MAX_ITEMS = 50;
    static final int MESSAGE_SKUS = 5;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired
    private OperatingReportGrossMarginQuery realizedQuery;
    @Autowired
    private ThreePriceComparisonService catalogService;
    @Autowired
    private ProductTypeRepository productTypeRepository;

    @Override
    public String getToolName() {
        return "report_sku_gross_margin";
    }

    @Override
    public String getDescription() {
        return "查询SKU毛利率，两种口径分开给：①实际销售毛利(所选期间销售订单明细的折后未税收入-成本，只算已回填成本的明细，附成本覆盖率)；"
                + "②目录价理论毛利(产品目录价 vs 最近已定价批次的单位成本)。缺价/缺成本/没销售会明说，不估算。"
                + "当用户问'毛利率'、'SKU利润'、'哪个产品赚钱'时调用";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "productName", Map.of("type", "string", "description", "产品名称(可选，模糊匹配，不填则查全部有销售或有目录价的产品)"),
                        "startDate", Map.of("type", "string", "description", "实际销售毛利的起始日期 yyyy-MM-dd(可选，默认最近30天)"),
                        "endDate", Map.of("type", "string", "description", "实际销售毛利的结束日期 yyyy-MM-dd(可选，默认今天)")
                ),
                "required", List.of()
        );
    }

    @Override
    protected List<String> getRequiredParameters() {
        return List.of();
    }

    /** 测试可覆盖; 生产用服务器本地日期 (后端全线 GMT+8)。 */
    protected LocalDate today() {
        return LocalDate.now();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        String keyword = blankToNull(getString(params, "productName"));
        String startText = blankToNull(getString(params, "startDate"));
        String endText = blankToNull(getString(params, "endDate"));

        LocalDate end = endText != null ? parseDate(endText, "endDate") : today();
        LocalDate start = startText != null ? parseDate(startText, "startDate")
                : end.minusDays(DEFAULT_PERIOD_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        boolean defaultPeriod = startText == null && endText == null;

        // ---- 口径 1 的数据: 复用销售毛利报表的查询 (不另写 SQL) ----
        Map<String, Object> payload = realizedQuery.fetch(factoryId, start, end);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> realizedRows = (List<Map<String, Object>>) payload.get("rank");

        List<ProductType> types = productTypeRepository.findByFactoryIdAndIsActiveTrue(factoryId);
        Map<String, ProductType> typeById = new HashMap<>();
        for (ProductType t : types) {
            if (t.getId() != null) typeById.put(t.getId(), t);
        }

        // ---- 选出要回答的 SKU ----
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (ProductType t : types) {
            boolean pick = keyword != null ? containsIgnoreCase(t.getName(), keyword)
                    : t.getUnitPrice() != null && t.getUnitPrice().signum() > 0;
            if (pick && t.getId() != null) {
                candidates.computeIfAbsent(t.getId(), k -> new Candidate()).type = t;
            }
        }
        for (Map<String, Object> row : realizedRows) {
            String id = (String) row.get("productTypeId");
            String name = (String) row.get("productName");
            boolean pick = keyword == null || containsIgnoreCase(name, keyword)
                    || (id != null && candidates.containsKey(id));
            if (!pick) continue;
            String key = id != null ? id : "name:" + name;
            Candidate c = candidates.computeIfAbsent(key, k -> new Candidate());
            c.row = row;
            if (c.type == null && id != null) c.type = typeById.get(id);
        }

        if (candidates.isEmpty()) {
            String msg = keyword != null
                    ? "没有找到名称含「" + keyword + "」的在用产品，所选期间(" + start + " 至 " + end + ")也没有该名称的销售记录，毛利率无从计算。"
                    : "所选期间(" + start + " 至 " + end + ")没有销售记录，也没有设置目录价的在用产品，没有可计算毛利率的产品。";
            return buildSimpleResult(msg, resultData(List.of(), 0, start, end, defaultPeriod));
        }

        // ---- 逐 SKU 组装两种口径 ----
        List<Map<String, Object>> items = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            items.add(buildItem(factoryId, c, start, end, defaultPeriod));
        }
        items.sort(Comparator
                .comparing((Map<String, Object> i) -> rateOf(i, "realized"), Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(i -> rateOf(i, "catalog"), Comparator.nullsLast(Comparator.<BigDecimal>reverseOrder()))
                .thenComparing(i -> String.valueOf(i.get("productName"))));
        int total = items.size();
        List<Map<String, Object>> shown = total > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;

        return buildSimpleResult(buildMessage(shown, total, start, end, defaultPeriod),
                resultData(shown, total, start, end, defaultPeriod));
    }

    // ==================== 组装 ====================

    private Map<String, Object> buildItem(String factoryId, Candidate c, LocalDate start, LocalDate end,
                                          boolean defaultPeriod) {
        String name = c.row != null && c.row.get("productName") != null ? (String) c.row.get("productName")
                : c.type != null ? c.type.getName() : null;
        String id = c.type != null ? c.type.getId() : c.row != null ? (String) c.row.get("productTypeId") : null;

        Map<String, Object> realized = buildRealized(c.row, start, end, defaultPeriod);
        Map<String, Object> catalog = buildCatalog(factoryId, c.type);

        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productTypeId", id);
        item.put("productName", name);
        item.put("quantity", c.row != null ? OperatingReportAssembler.number(c.row.get("quantity")) : null);
        // web-admin finance/sku-margin 读 marginRate: 给实际销售毛利 (有数据支撑的那个), 并标明口径
        item.put("marginRate", realized.get("marginRate"));
        item.put("marginRateBasis", "realized");
        item.put("realized", realized);
        item.put("catalog", catalog);
        return item;
    }

    private Map<String, Object> buildRealized(Map<String, Object> row, LocalDate start, LocalDate end,
                                              boolean defaultPeriod) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", "实际销售毛利: 销售订单明细 折后未税收入 − 成本单价×数量, 只算已回填成本的明细");
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", start.toString());
        period.put("endDate", end.toString());
        period.put("defaultPeriod", defaultPeriod);
        m.put("period", period);

        int lines = row == null ? 0 : intOf(row.get("lineCount"));
        int costedLines = row == null ? 0 : intOf(row.get("costedLineCount"));
        int zeroCostLines = row == null ? 0 : intOf(row.get("zeroCostLineCount"));
        BigDecimal totalRevenue = row == null ? null : money(OperatingReportAssembler.number(row.get("revenue")));
        BigDecimal costedRevenue = row == null ? null : money(OperatingReportAssembler.number(row.get("costedRevenue")));
        BigDecimal cost = row == null ? null : money(OperatingReportAssembler.number(row.get("cost")));
        BigDecimal profit = row == null ? null : money(OperatingReportAssembler.number(row.get("grossProfit")));
        BigDecimal marginRate = row == null ? null : pct(OperatingReportAssembler.number(row.get("marginRate")));
        BigDecimal coverage = row == null ? null : pct(OperatingReportAssembler.number(row.get("costCoverage")));

        String status;
        String reason;
        if (lines == 0) {
            status = "NO_SALES";
            reason = "所选期间没有销售记录";
            costedRevenue = null; cost = null; profit = null; marginRate = null; coverage = null; totalRevenue = null;
        } else if (costedLines == 0) {
            status = "NO_COST";
            reason = "所选期间有 " + lines + " 条销售明细，但都没有回填成本，算不出实际毛利率";
            costedRevenue = null; cost = null; profit = null; marginRate = null;
        } else if (marginRate == null) {
            status = "ZERO_REVENUE";
            reason = "已回填成本的明细收入为 0，算不出实际毛利率";
        } else {
            status = "OK";
            reason = null;
        }
        // 成本=0 仍按「已回填」计 (沿用销售毛利报表语义), 只是告诉用户
        String note = (lines > 0 && costedLines > 0 && zeroCostLines > 0)
                ? "其中 " + zeroCostLines + " 条明细成本为 0，可能是成本没回填" : null;

        m.put("status", status);
        m.put("reason", reason);
        m.put("lineCount", lines == 0 ? null : lines);
        m.put("costedLineCount", lines == 0 ? null : costedLines);
        m.put("zeroCostLineCount", lines == 0 ? null : zeroCostLines);
        m.put("totalRevenue", totalRevenue);
        m.put("revenue", costedRevenue);          // 已回填成本明细的收入 = 毛利率的分母
        m.put("cost", cost);
        m.put("grossProfit", profit);
        m.put("marginRate", marginRate);          // 百分数
        m.put("costCoverage", coverage);          // 百分数: 已回填成本的收入 / 全部收入
        m.put("note", note);
        return m;
    }

    private Map<String, Object> buildCatalog(String factoryId, ProductType type) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("definition", "目录价理论毛利: 产品目录价 vs 最近一条已算出单位成本的批次 (三价对比看板同一口径)");
        if (type == null) {
            m.put("status", "NOT_IN_CATALOG");
            m.put("reason", "该产品不在在用产品目录中，没有目录价");
            m.put("price", null); m.put("unitCost", null); m.put("marginRate", null);
            m.put("costBatchNumber", null); m.put("costAsOf", null);
            return m;
        }
        ThreePriceComparisonService.CatalogMargin cm = catalogService.catalogMarginFor(factoryId, type);
        boolean hasPrice = cm.salesPrice() != null && cm.salesPrice().signum() > 0;
        boolean hasCost = cm.actualCost() != null;
        String status;
        String reason;
        if (hasPrice && hasCost && cm.grossMarginPct() != null) {
            status = "OK";
            reason = null;
        } else if (!hasPrice && !hasCost) {
            status = "NO_PRICE_AND_COST";
            reason = "没有目录价，也没有已算出单位成本的批次";
        } else if (!hasPrice) {
            status = "NO_PRICE";
            reason = "没有目录价";
        } else {
            status = "NO_BATCH_COST";
            reason = "没有已算出单位成本的批次";
        }
        m.put("status", status);
        m.put("reason", reason);
        m.put("price", hasPrice ? money(cm.salesPrice()) : null);
        m.put("unitCost", money(cm.actualCost()));
        m.put("marginRate", status.equals("OK") ? cm.grossMarginPct() : null);   // 已是百分数
        m.put("costBatchNumber", cm.costBatchNumber());
        m.put("costAsOf", cm.costAsOf() != null ? cm.costAsOf().toString() : null);
        return m;
    }

    private Map<String, Object> resultData(List<Map<String, Object>> items, int total, LocalDate start,
                                           LocalDate end, boolean defaultPeriod) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("items", items);
        data.put("totalProducts", total);
        data.put("truncated", total > items.size());
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", start.toString());
        period.put("endDate", end.toString());
        period.put("defaultPeriod", defaultPeriod);
        data.put("realizedPeriod", period);
        data.put("rateUnit", "percent");
        return data;
    }

    // ==================== 文案 ====================

    private String buildMessage(List<Map<String, Object>> shown, int total, LocalDate start, LocalDate end,
                                boolean defaultPeriod) {
        StringBuilder sb = new StringBuilder();
        sb.append("SKU 毛利率共 ").append(total).append(" 个产品，两种口径分开给，不要混着看。")
          .append("「实际销售毛利」统计期间 ").append(start).append(" 至 ").append(end)
          .append(defaultPeriod ? "（默认最近 " + DEFAULT_PERIOD_DAYS + " 天）" : "").append("。\n");
        int n = 0;
        for (Map<String, Object> item : shown) {
            if (n++ >= MESSAGE_SKUS) {
                sb.append("……其余 ").append(total - MESSAGE_SKUS).append(" 个产品见明细数据。\n");
                break;
            }
            sb.append(n).append(". ").append(item.get("productName")).append("\n");
            sb.append("   实际销售毛利：").append(realizedText(castMap(item.get("realized")))).append("\n");
            sb.append("   目录价理论毛利：").append(catalogText(castMap(item.get("catalog")))).append("\n");
        }
        return sb.toString().trim();
    }

    private String realizedText(Map<String, Object> r) {
        String status = (String) r.get("status");
        if ("OK".equals(status)) {
            StringBuilder sb = new StringBuilder();
            sb.append(r.get("marginRate")).append("%（已回填成本明细的收入 ¥").append(r.get("revenue"))
              .append("，成本 ¥").append(r.get("cost")).append("，成本覆盖 ").append(r.get("costCoverage")).append("% 的收入）");
            if (r.get("note") != null) sb.append("。").append(r.get("note"));
            return sb.toString();
        }
        String reason = (String) r.get("reason");
        if (r.get("note") != null) reason = reason + "。" + r.get("note");
        return reason;
    }

    private String catalogText(Map<String, Object> c) {
        if ("OK".equals(c.get("status"))) {
            return c.get("marginRate") + "%（目录价 ¥" + c.get("price") + "，最近批次 " + c.get("costBatchNumber")
                    + " 单位成本 ¥" + c.get("unitCost") + "）";
        }
        StringBuilder sb = new StringBuilder((String) c.get("reason"));
        if (c.get("price") != null) sb.append("（目录价 ¥").append(c.get("price")).append("）");
        if (c.get("unitCost") != null) sb.append("（最近批次单位成本 ¥").append(c.get("unitCost")).append("）");
        return sb.toString();
    }

    // ==================== helpers ====================

    private static final class Candidate {
        ProductType type;
        Map<String, Object> row;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    private static BigDecimal rateOf(Map<String, Object> item, String part) {
        Object rate = castMap(item.get(part)).get("marginRate");
        return rate instanceof BigDecimal ? (BigDecimal) rate : null;
    }

    private static int intOf(Object o) {
        return o instanceof Number ? ((Number) o).intValue() : 0;
    }

    private static BigDecimal money(BigDecimal v) {
        return v == null ? null : v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal pct(BigDecimal ratio) {
        return ratio == null ? null : ratio.multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP);
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static LocalDate parseDate(String text, String field) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(field + " 格式应为 yyyy-MM-dd");
        }
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }
}
