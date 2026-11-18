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
    /**
     * 工艺保密: 文案 (message) 直接进模型上下文, 当前账号无权查看的保密产品的成本 / 毛利 / 毛利率不写进文案,
     * 负责人 / 有效授权照常写 (Steve 10-29)。结构化字段仍由 ProcessSecretToolOutputAspect 按调用者裁剪。
     */
    @Autowired
    private com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope llmCostScope = com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.standalone();

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
        return ReportToolSchemas.withOptionalExtraction(Map.of(
                "type", "object",
                "properties", Map.of(
                        "productName", Map.of("type", "string", "description", "产品名称(可选，模糊匹配，不填则查全部有销售或有目录价的产品)"),
                        "startDate", Map.of("type", "string", "format", "date", "description", "实际销售毛利的起始日期 yyyy-MM-dd(可选，默认最近30天)"),
                        "endDate", Map.of("type", "string", "format", "date", "description", "实际销售毛利的结束日期 yyyy-MM-dd(可选，默认今天)"),
                        "sortOrder", Map.of("type", "string", "enum", List.of("highest", "lowest"),
                                "description", "排序方向(可选，默认 highest 毛利从高到低)。用户问'毛利最低/最差/末位/倒数/亏钱的产品'时填 lowest(从低到高)")
                ),
                "required", List.of()
        ));
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
        // 产品名本次请求解析一次; 回复开头写明按哪个产品 / 全厂统计 (「卤牛肉片」按卤牛肉回答时也看得出来)
        ReportToolSchemas.ProductResolution product = ReportToolSchemas.resolveProduct(productTypeRepository, factoryId, getString(params, "productName"));
        return ReportToolSchemas.withNote(answer(factoryId, params, context, product), product.leadIn(false));
    }

    private Map<String, Object> answer(String factoryId, Map<String, Object> params, Map<String, Object> context,
                                       ReportToolSchemas.ProductResolution product) throws Exception {
        String raw = getString(params, "productName");
        String keyword = product.label();
        String startText = blankToNull(getString(params, "startDate"));
        String endText = blankToNull(getString(params, "endDate"));

        LocalDate end = endText != null ? parseDate(endText, "endDate") : today();
        LocalDate start = startText != null ? parseDate(startText, "startDate")
                : end.minusDays(DEFAULT_PERIOD_DAYS - 1L);
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        boolean defaultPeriod = startText == null && endText == null;

        // 毛利率与 web SKU 毛利同一判定 (ShipmentSkuMarginDTO.marginRate 的 @PriceSensitive 口径): 看不到就整份不给,
        // 不给「金额已遮、毛利率仍在」的部分答案 (2026-11-16 #1788 独立审查 H1)。不查库。
        if (!replyShowsPriceSpec(context, MARGIN_RATE_SPEC) || !replyShowsAmount(context, "marginRate")) {
            Map<String, Object> hidden = resultData(List.of(), 0, start, end, defaultPeriod);
            hidden.put("marginHidden", true);
            return buildSimpleResult(MARGIN_RATE_HIDDEN_MESSAGE, hidden);
        }

        // ---- 口径 1 的数据: 复用销售毛利报表的查询 (不另写 SQL) ----
        Map<String, Object> payload = realizedQuery.fetch(factoryId, start, end);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> realizedRows = (List<Map<String, Object>>) payload.get("rank");

        List<ProductType> types = product.catalog() != null ? product.catalog() : List.of();   // 本次请求已加载, 不再查第二次
        Map<String, ProductType> typeById = new HashMap<>();
        for (ProductType t : types) {
            if (t.getId() != null) typeById.put(t.getId(), t);
        }

        // ---- 选出要回答的 SKU (口径表 B: 只锁定名称完整出现在原话里的产品, ⛔ 不按「名称含这个词」反向包含挑) ----
        ReportToolSchemas.ProductScope scope = product.scope();
        Set<String> pickedIds = scope == ReportToolSchemas.ProductScope.MATCHED ? product.productIds() : Set.of();
        Set<String> pickedNames = new HashSet<>();
        for (String n : product.productNames()) pickedNames.add(n.trim().toLowerCase(Locale.ROOT));
        String asked = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (ProductType t : types) {
            boolean pick = switch (scope) {
                case ALL -> t.getUnitPrice() != null && t.getUnitPrice().signum() > 0;
                case MATCHED -> pickedIds.contains(t.getId());
                case NAME_ONLY -> containsIgnoreCase(t.getName(), product.keyword());
                case NOT_FOUND -> false;
            };
            if (pick && t.getId() != null) {
                candidates.computeIfAbsent(t.getId(), k -> new Candidate()).type = t;
            }
        }
        for (Map<String, Object> row : realizedRows) {
            String id = (String) row.get("productTypeId");
            String name = (String) row.get("productName");
            String n = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
            boolean pick = switch (scope) {
                case ALL -> true;
                case MATCHED -> (id != null && candidates.containsKey(id)) || (id == null && pickedNames.contains(n));
                case NAME_ONLY -> containsIgnoreCase(name, product.keyword());
                // 目录里没有 (例如已停用) 但期间有销售: 销售记录上的名称完整出现在原话里才算问到了它
                case NOT_FOUND -> n.length() >= 2 && asked.contains(n);
            };
            if (!pick) continue;
            String key = id != null ? id : "name:" + name;
            Candidate c = candidates.computeIfAbsent(key, k -> new Candidate());
            c.row = row;
            if (c.type == null && id != null) c.type = typeById.get(id);
        }

        if (candidates.isEmpty()) {
            String msg;
            if (scope == ReportToolSchemas.ProductScope.NOT_FOUND && !product.suggestions().isEmpty()) {
                msg = product.refusalMessage("计算毛利率", false);
            } else if (keyword != null) {
                msg = "没有找到名称含「" + keyword + "」的在用产品，所选期间(" + start + " 至 " + end + ")也没有该名称的销售记录，毛利率无从计算。";
            } else {
                msg = "所选期间(" + start + " 至 " + end + ")没有销售记录，也没有设置目录价的在用产品，没有可计算毛利率的产品。";
            }
            Map<String, Object> empty = resultData(List.of(), 0, start, end, defaultPeriod);
            if (!product.suggestions().isEmpty()) empty.put("candidates", product.suggestions());
            return buildSimpleResult(msg, empty);
        }

        // ---- 逐 SKU 组装两种口径 ----
        List<Map<String, Object>> items = new ArrayList<>();
        for (Candidate c : candidates.values()) {
            items.add(buildItem(factoryId, c, start, end, defaultPeriod));
        }
        boolean lowestFirst = "lowest".equalsIgnoreCase(getString(params, "sortOrder"));
        // 没有毛利率的 (没数据) 始终排最后, 不因升序跑到「最低」前面
        Comparator<BigDecimal> rateOrder = lowestFirst ? Comparator.<BigDecimal>naturalOrder() : Comparator.<BigDecimal>reverseOrder();
        items.sort(Comparator
                .comparing((Map<String, Object> i) -> rateOf(i, "realized"), Comparator.nullsLast(rateOrder))
                .thenComparing(i -> rateOf(i, "catalog"), Comparator.nullsLast(rateOrder))
                .thenComparing(i -> String.valueOf(i.get("productName"))));
        // 工艺保密: 当前调用者无权看的产品不按毛利排 (排名本身泄露毛利高低) —— 挪到最后, 按名称排
        com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded secret = llmCostScope.forCurrentCaller(factoryId);
        if (!secret.isEmpty()) {
            List<Map<String, Object>> visible = new ArrayList<>();
            List<Map<String, Object>> masked = new ArrayList<>();
            for (Map<String, Object> item : items) {
                Object id = item.get("productTypeId");
                (id == null || secret.coversProduct(id.toString()) ? masked : visible).add(item);
            }
            masked.sort(Comparator.comparing(i -> String.valueOf(i.get("productName"))));
            items = new ArrayList<>(visible);
            items.addAll(masked);
        }
        int total = items.size();
        List<Map<String, Object>> shown = total > MAX_ITEMS ? new ArrayList<>(items.subList(0, MAX_ITEMS)) : items;

        Map<String, Object> data = resultData(shown, total, start, end, defaultPeriod);
        Map<String, Object> chart = marginChart(shown, secret, lowestFirst);
        if (chart != null) {
            data.put("charts", List.of(chart));
        }
        data.put("sortOrder", lowestFirst ? "lowest" : "highest");
        return buildSimpleResult(buildMessage(shown, total, start, end, defaultPeriod, secret,
                        replyShowsAmounts(context, MARGIN_AMOUNT_KEYS), replyShowsAmount(context, "costCoverage"), lowestFirst),
                data);
    }

    /**
     * 实际销售毛利率排行柱图 (六膳门演示: 问答要出图)。只画有毛利率的、当前调用者看得到的 SKU, 前 10;
     * 工艺保密而无权看的产品不进图 (排名和高度本身会泄露毛利)。没有可画的数据就不出图, 不画空图。
     */
    static Map<String, Object> marginChart(List<Map<String, Object>> items,
                                           com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded secret) {
        return marginChart(items, secret, false);
    }

    static Map<String, Object> marginChart(List<Map<String, Object>> items,
                                           com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded secret,
                                           boolean lowestFirst) {
        List<String> names = new ArrayList<>();
        List<BigDecimal> rates = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Object id = item.get("productTypeId");
            if (id == null || secret.coversProduct(id.toString())) continue;
            BigDecimal rate = rateOf(item, "realized");
            if (rate == null) continue;
            names.add(String.valueOf(item.get("productName")));
            rates.add(rate);
            if (names.size() >= 10) break;
        }
        if (names.isEmpty()) return null;
        Map<String, Object> option = new LinkedHashMap<>();
        option.put("tooltip", Map.of("trigger", "axis"));
        option.put("grid", Map.of("left", 50, "right", 20, "bottom", 70, "containLabel", true));
        option.put("xAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("interval", 0, "rotate", 25)));
        option.put("yAxis", Map.of("type", "value", "name", "%"));
        option.put("series", List.of(Map.of("type", "bar", "name", "实际销售毛利率(%)", "data", rates)));
        Map<String, Object> chart = new LinkedHashMap<>();
        chart.put("type", "bar");
        chart.put("title", lowestFirst ? "SKU 实际销售毛利率最低（%，从低到高前 10）" : "SKU 实际销售毛利率排行（%，前 10）");
        chart.put("option", option);
        return chart;
    }

    /**
     * 毛利两处 (本工具 / {@link ReportSalesMarginSummaryTool}) 与 web 同一个可见性判据 (2026-11-16 复跑 Q05/Q06 vs Q11,
     * #1788 独立审查 H1: 与 web 的 shipment-sku-margin / 经营报告毛利段对齐, 只收紧不放开):
     * <ul>
     *   <li>能不能用 = 工具门 {@code finance:read | finance:read_write} (中央 ToolRbacEnforcer, 与 web 财务模块门同源);</li>
     *   <li>毛利率 = {@link #MARGIN_RATE_SPEC}: web {@code ShipmentSkuMarginDTO.marginRate} 上 {@code @PriceSensitive} 的口径,
     *       经同一个判定点 (PriceFieldResponseAdvice#showsSpec → PriceVisibility) 按权限配置判; 看不到 ⇒ 整份不给;</li>
     *   <li>金额 (收入 / 成本 / 毛利 / 目录价 / 单位成本) 是否写进回复 = {@code replyShowsAmounts(这组键)};
     *       成本覆盖率问 {@code costCoverage} 这个键 —— 文字与遮挡后的字段同进同出。</li>
     * </ul>
     * 两个工具都只用这几个常量问, ⛔ 不各写一份、不写死角色。
     */
    static final String[] MARGIN_AMOUNT_KEYS = {"revenue", "cost", "grossProfit", "price", "unitCost"};

    /** web SKU 毛利视图里毛利率字段的口径, 直接读注解 (web 改口径, 这里跟着变)。 */
    static final String MARGIN_RATE_SPEC = webMarginRateSpec();

    static final String MARGIN_RATE_HIDDEN_MESSAGE = "毛利率按当前账号的权限配置不显示（与 web「SKU 毛利」页同一判定，需要价格查看权限），"
            + "销售额、成本、毛利金额也不显示；需要请联系工厂管理员开通。";

    private static String webMarginRateSpec() {
        try {
            com.cretas.aims.security.PriceSensitive annotation = com.cretas.aims.dto.finance.report.ShipmentSkuMarginDTO.class
                    .getDeclaredField("marginRate").getAnnotation(com.cretas.aims.security.PriceSensitive.class);
            if (annotation == null) {
                throw new IllegalStateException("ShipmentSkuMarginDTO.marginRate 没有 @PriceSensitive");
            }
            return annotation.permission();
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("ShipmentSkuMarginDTO.marginRate 不存在", e);
        }
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
        m.put("definition", "实际销售毛利: 销售订单明细 折后未税收入 − 成本单价×数量, 只算有售价且已回填成本的明细");
        Map<String, Object> period = new LinkedHashMap<>();
        period.put("startDate", start.toString());
        period.put("endDate", end.toString());
        period.put("defaultPeriod", defaultPeriod);
        m.put("period", period);

        int lines = row == null ? 0 : intOf(row.get("lineCount"));
        int costedLines = row == null ? 0 : intOf(row.get("costedLineCount"));
        int zeroCostLines = row == null ? 0 : intOf(row.get("zeroCostLineCount"));
        int missingPriceLines = row == null ? 0 : intOf(row.get("missingPriceLineCount"));
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
        } else if (missingPriceLines == lines) {
            // 全部明细都没有售价: 收入不是 0, 是不知道 —— 不给收入/毛利率
            status = "NO_PRICE";
            reason = "所选期间有 " + lines + " 条销售明细，但都没有售价，算不出收入和实际毛利率";
            totalRevenue = null; costedRevenue = null; cost = null; profit = null; marginRate = null; coverage = null;
        } else if (costedLines == 0) {
            status = "NO_COST";
            reason = "所选期间有 " + lines + " 条有售价的销售明细，但都没有回填成本，算不出实际毛利率";
            costedRevenue = null; cost = null; profit = null; marginRate = null;
        } else if (marginRate == null) {
            status = "ZERO_REVENUE";
            reason = "已回填成本的明细收入为 0，算不出实际毛利率";
        } else {
            status = "OK";
            reason = null;
        }
        // 成本=0 仍按「已回填」计 (沿用销售毛利报表语义), 只是告诉用户
        List<String> notes = new ArrayList<>();
        if (lines > 0 && missingPriceLines > 0 && missingPriceLines < lines) {
            notes.add("其中 " + missingPriceLines + " 条明细没有售价，未计入");
        }
        if (lines > 0 && costedLines > 0 && zeroCostLines > 0) {
            notes.add("其中 " + zeroCostLines + " 条明细成本为 0，可能是成本没回填");
        }
        String note = notes.isEmpty() ? null : String.join("；", notes);

        m.put("status", status);
        m.put("reason", reason);
        m.put("lineCount", lines == 0 ? null : lines);
        m.put("costedLineCount", lines == 0 ? null : costedLines);
        m.put("zeroCostLineCount", lines == 0 ? null : zeroCostLines);
        m.put("missingPriceLineCount", lines == 0 ? null : missingPriceLines);
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
        m.put("definition", "目录价理论毛利: 产品目录价 vs 最近 " + ThreePriceComparisonService.CATALOG_COST_BATCHES + " 批已算出单位成本批次的加权单位成本 (按实际产量加权, 不取单批)");
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
        m.put("costBatchCount", cm.costBatchCount());
        m.put("latestBatchUnitCost", money(cm.latestBatchUnitCost()));
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
                                boolean defaultPeriod, com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded secret,
                                boolean showAmounts, boolean showCoverage, boolean lowest) {
        this.lowestFirst.set(lowest);
        this.showAmounts.set(showAmounts);
        this.showCoverage.set(showCoverage);
        try {
            return buildMessageText(shown, total, start, end, defaultPeriod, secret);
        } finally {
            this.lowestFirst.remove();
            this.showAmounts.remove();
            this.showCoverage.remove();
        }
    }

    /** 成本覆盖率进不进文字: 与字段 realized.costCoverage 经遮挡层后同进同出 (#1788 审查 M2)。 */
    private final ThreadLocal<Boolean> showCoverage = new ThreadLocal<>();

    /** 本次回答的排序方向 (文字里写明是从低到高还是从高到低)。 */
    private final ThreadLocal<Boolean> lowestFirst = ThreadLocal.withInitial(() -> false);

    /** 文字里的收入 / 成本 / 目录价 / 单位成本与 revenue / cost / price / unitCost 字段同进同出 (ERP-S6-MSG-TEXT-PRICE-LEAK-001) */
    private final ThreadLocal<Boolean> showAmounts = new ThreadLocal<>();

    private String moneyText(Object amount) {
        return Boolean.TRUE.equals(showAmounts.get()) ? "¥" + amount : "(按权限不显示)";
    }

    private String buildMessageText(List<Map<String, Object>> shown, int total, LocalDate start, LocalDate end,
                                boolean defaultPeriod, com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.Excluded secret) {
        StringBuilder sb = new StringBuilder();
        sb.append("SKU 毛利率共 ").append(total).append(" 个产品（").append(lowestFirst.get() ? "按毛利率从低到高" : "按毛利率从高到低")
          .append("），每个产品给实际销售毛利和目录价理论毛利两个数。")
          .append("「实际销售毛利」统计期间 ").append(start).append(" 至 ").append(end)
          .append(defaultPeriod ? "（默认最近 " + DEFAULT_PERIOD_DAYS + " 天）" : "").append("。\n");
        int n = 0;
        for (Map<String, Object> item : shown) {
            if (n++ >= MESSAGE_SKUS) {
                sb.append("……其余 ").append(total - MESSAGE_SKUS).append(" 个产品见明细数据。\n");
                break;
            }
            sb.append(n).append(". ").append(item.get("productName")).append("\n");
            Object productTypeId = item.get("productTypeId");
            if (!secret.isEmpty() && (productTypeId == null || secret.coversProduct(productTypeId.toString()))) {
                // 保密产品 (或本厂有保密产品而认不出是哪个产品): 成本与毛利一个数都不写
                sb.append("   ").append(com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope.WITHHELD_REASON).append("\n");
                continue;
            }
            sb.append("   实际销售毛利：").append(realizedText(castMap(item.get("realized")))).append("\n");
            sb.append("   目录价理论毛利：").append(catalogText(castMap(item.get("catalog")))).append("\n");
        }
        return sb.toString().trim();
    }

    private String realizedText(Map<String, Object> r) {
        String status = (String) r.get("status");
        if ("OK".equals(status)) {
            StringBuilder sb = new StringBuilder();
            sb.append(r.get("marginRate")).append("%（已回填成本明细的收入 ").append(moneyText(r.get("revenue")))
              .append("，成本 ").append(moneyText(r.get("cost")));
            if (Boolean.TRUE.equals(showCoverage.get())) {
                sb.append("，成本覆盖 ").append(r.get("costCoverage")).append("% 的收入");
            }
            sb.append("）");
            if (r.get("note") != null) sb.append("。").append(r.get("note"));
            return sb.toString();
        }
        String reason = (String) r.get("reason");
        if (r.get("note") != null) reason = reason + "。" + r.get("note");
        return reason;
    }

    private String catalogText(Map<String, Object> c) {
        if ("OK".equals(c.get("status"))) {
            return c.get("marginRate") + "%（目录价 " + moneyText(c.get("price")) + "，近 " + c.get("costBatchCount")
                    + " 批（最近 " + c.get("costBatchNumber") + "）加权单位成本（总成本 ÷ 实际产量）" + moneyText(c.get("unitCost")) + "）";
        }
        StringBuilder sb = new StringBuilder((String) c.get("reason"));
        if (c.get("price") != null) sb.append("（目录价 ").append(moneyText(c.get("price"))).append("）");
        if (c.get("unitCost") != null) sb.append("（近批次加权单位成本 ").append(moneyText(c.get("unitCost"))).append("）");
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

    /**
     * 与 App 里同一份数据的入口同口径 ("AI 能做的 = App 能做的"): web 的 SKU 毛利视图
     * (finance/report/shipment-sku-margin → {@code GET /finance/report/shipment-sku-margin}, FinanceReportController
     * 类门 {@code finance:read | finance:read_write}) 对没有财务模块的角色整页 403。组织者裁定 2026-11-03
     * (acc-s6 s610k): web 拒整份, 对话就拒整个工具 —— 以前按 procurement:price:view 放行, 销售 / 生产 / 采购主管
     * 拿到了金额已遮、但毛利率仍在的部分答案。中央 {@link com.cretas.aims.ai.tool.ToolRbacEnforcer} 判定 (fail-closed)。
     */
    @Override
    public boolean requiresPermission() {
        return true;
    }

    @Override
    public Set<String> getRequiredPermissions() {
        return Set.of("finance:read", "finance:read_write");
    }

    /** spec §8.2 只读查询, 无副作用 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

}
