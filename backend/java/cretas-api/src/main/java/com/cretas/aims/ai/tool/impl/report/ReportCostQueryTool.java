package com.cretas.aims.ai.tool.impl.report;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;

/**
 * 成本查询 Tool (对应意图 COST_QUERY) —— 只读、确定性、直接读已完成批次的成本分项。
 *
 * <p>2026-11-15 重写: 旧实现把问题交给 LLM 写一份「成本分析报告」并落库 (有写副作用, 所以被登记成 WRITE,
 * 咨询页里被写闸拦成「请切换到操作页」, 六膳门演示的成本类问题全部答不出)。现在:
 * <ul>
 *   <li>只读: 不调模型、不存报告, {@link AccessMode#READ}。</li>
 *   <li>数字全部来自批次成本分项 (原料 / 人工 / 设备 / 其他), 与批次表读数一致; 不估算、不补 0。</li>
 *   <li>成本未算全的批次 ({@code costIncomplete}) 不进合计, 单独说明有几个; 工艺保密产品对无权调用者整批剔除并说明。</li>
 *   <li>单位成本 = 总成本 ÷ 实际产量 (与成本分析页同口径), 且只在同一单位内求, 不把 盒/袋/kg 加在一起。</li>
 *   <li>和「上一个等长周期」对比, 给出各分项涨跌; 上期没数据就明说, 不给环比。</li>
 *   <li>结构化结果带 {@code charts} (成本构成饼图 + 产品成本柱图), 前端对话直接渲染。</li>
 * </ul>
 */
@Slf4j
@Component
public class ReportCostQueryTool extends AbstractBusinessTool {

    static final int MAX_PRODUCTS = 15;
    static final java.util.regex.Pattern MONTH_OVER_MONTH =
            java.util.regex.Pattern.compile("(和|与|跟|同|比)(上个月|上月)(比|相比|对比)?|比(上个月|上月)|环比(上个月|上月)");
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    @Autowired
    private ProductionBatchRepository batchRepository;

    /** 解析产品名用 (本厂在用产品目录); 取不到时退回按批次名子串筛 (旧行为)。 */
    @Autowired(required = false)
    private com.cretas.aims.repository.ProductTypeRepository productTypeRepository;

    @Autowired(required = false)
    private ProcessSecretLlmCostScope llmCostScope = ProcessSecretLlmCostScope.standalone();

    @Override
    public String getToolName() {
        return "report_cost_query";
    }

    @Override
    public String getDescription() {
        return "查询生产成本（只读）：期间内已完成批次的成本构成（原料/人工/设备/其他占比）、各产品总成本与单位成本、"
                + "与上一个等长周期的涨跌对比，并附成本构成饼图和产品成本柱图。可按产品名筛选。"
                + "成本未算全的批次、保密产品会明说，不估算。"
                + "适用：单位成本多少、总成本多少、成本构成/占比、成本涨跌、哪个产品成本高。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        return ReportToolSchemas.withOptionalExtraction(Map.of(
                "type", "object",
                "properties", Map.of(
                        "startDate", Map.of("type", "string", "format", "date", "description", "开始日期 yyyy-MM-dd，默认本月 1 号；「上周」填上周一，「本月」填 1 号"),
                        "endDate", Map.of("type", "string", "format", "date", "description", "结束日期 yyyy-MM-dd，默认今天；「上周」填上周日"),
                        "productName", Map.of("type", "string", "description", "产品名（可选，模糊匹配），不填则全部产品"),
                        "period", Map.of("type", "string", "description", "快捷周期: last_week(上周) / this_week(本周) / this_month(本月) / last_month(上月)；与 startDate/endDate 同时给时以日期为准"),
                        "compareTo", Map.of("type", "string", "description", "对比基准: last_month=环比上月（用户说「和上月比」「比上个月」时填，上一期整体往前推一个月）；不填=上一个等长周期"),
                        "question", Map.of("type", "string", "description", "用户的原问题（仅记录）")
                ),
                "required", List.of()));
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.emptyList();
    }

    /** 测试可覆盖; 生产用服务器本地日期。 */
    protected LocalDate today() {
        return LocalDate.now();
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                            Map<String, Object> context) throws Exception {
        LocalDate today = today();
        LocalDate start;
        LocalDate end;
        String startText = blankToNull(getString(params, "startDate"));
        String endText = blankToNull(getString(params, "endDate"));
        String period = blankToNull(getString(params, "period"));
        if (startText != null || endText != null) {
            end = endText != null ? parseDate(endText, "endDate") : today;
            start = startText != null ? parseDate(startText, "startDate") : end.withDayOfMonth(1);
        } else if (period != null) {
            LocalDate[] range = periodRange(period, today);
            start = range[0];
            end = range[1];
        } else {
            start = today.withDayOfMonth(1);
            end = today;
        }
        if (start.isAfter(end)) {
            throw new IllegalArgumentException("开始日期不能晚于结束日期");
        }
        String keyword = blankToNull(getString(params, "productName"));
        boolean showAmounts = replyShowsAmounts(context, "cost", "unitCost", "totalCost");
        // 产品名先在本厂在用产品里解析: 完全同名优先; 包含匹配多个时给候选, 不把「卤牛肉/卤牛肉片/五香卤牛肉」合计成一个数
        Set<String> productIds = null;
        if (keyword != null && productTypeRepository != null) {
            List<ProductType> candidates = matchProducts(factoryId, keyword);
            if (candidates.isEmpty()) {
                Map<String, Object> none = new LinkedHashMap<>();
                none.put("productFilter", keyword);
                none.put("candidates", List.of());
                return buildSimpleResult("没有找到名称含「" + keyword + "」的在用产品，无法统计成本。请确认产品名。", none);
            }
            if (candidates.size() > 1) {
                List<String> names = new ArrayList<>();
                for (ProductType t : candidates) names.add(t.getName());
                Map<String, Object> amb = new LinkedHashMap<>();
                amb.put("productFilter", keyword);
                amb.put("candidates", names);
                return buildSimpleResult("「" + keyword + "」匹配到 " + names.size() + " 个产品：" + String.join("、", names)
                        + "。请说清楚要看哪一个（说完整产品名），我不把它们合计成一个数。", amb);
            }
            productIds = new HashSet<>();
            productIds.add(candidates.get(0).getId());
            keyword = null;
        }

        ProcessSecretLlmCostScope.Excluded secret = llmCostScope == null
                ? ProcessSecretLlmCostScope.Excluded.NONE : llmCostScope.forCurrentCaller(factoryId);

        Aggregate current = aggregate(factoryId, start, end, keyword, productIds, secret);
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        // 「和上月比」= 环比上月: 上一期是整体往前推一个月 (本月 1~今天 对上月 1~同一天), 不是上一个等长周期
        String compareTo = blankToNull(getString(params, "compareTo"));
        if (compareTo == null) {
            // 抽参没填时的兜底: 原话里明说了「和上月比 / 比上月 / 环比上月」(「环比」单独出现不当成上月: 「本周环比」是和上一周比)
            String q = blankToNull(getString(params, "userInput"));
            if (q == null) q = blankToNull(getString(params, "question"));
            if (q != null && MONTH_OVER_MONTH.matcher(q).find()) compareTo = "last_month";
        }
        boolean monthOverMonth = "last_month".equalsIgnoreCase(compareTo);
        LocalDate prevStart;
        LocalDate prevEnd;
        if (monthOverMonth) {
            prevStart = start.minusMonths(1);
            prevEnd = end.getDayOfMonth() == end.lengthOfMonth()
                    ? end.minusMonths(1).withDayOfMonth(end.minusMonths(1).lengthOfMonth())
                    : end.minusMonths(1);
        } else {
            prevEnd = start.minusDays(1);
            prevStart = prevEnd.minusDays(days - 1);
        }
        Aggregate previous = aggregate(factoryId, prevStart, prevEnd, keyword, productIds, secret);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("period", Map.of("startDate", start.toString(), "endDate", end.toString()));
        data.put("previousPeriod", Map.of("startDate", prevStart.toString(), "endDate", prevEnd.toString()));
        data.put("productFilter", keyword);
        data.put("batchCount", current.batchCount);
        data.put("costIncompleteBatchCount", current.incompleteBatches);
        data.put("withheldBatchCount", current.withheldBatches);
        data.put("previousCostedBatchCount", previous.costedBatches);
        data.put("previousCostIncompleteBatchCount", previous.incompleteBatches);
        data.put("basis", "已完成批次的成本分项 (原料/人工/设备/其他); 成本未算全的批次不进合计; 单位成本 = 成本已算全批次的总成本 ÷ 这些批次的实际产量, 按单位分开算 (与成本分析页同口径); 批次按结束日落期");

        if (current.batchCount == 0) {
            String msg = "「" + start + " 至 " + end + "」" + (keyword != null ? "没有「" + keyword + "」" : "没有")
                    + "已完成的生产批次，无从统计成本。"
                    + (current.withheldBatches > 0 ? current.withheldNote() : "")
                    + "（口径：只统计已完成批次；进行中的批次成本未结算，不计入。）";
            data.put("rows", List.of());
            return buildSimpleResult(msg, data);
        }

        if (showAmounts) {
            data.put("totals", current.totalsMap());
            data.put("composition", current.compositionList());
            data.put("products", current.productRows(MAX_PRODUCTS));
            data.put("comparison", comparison(current, previous));
            data.put("charts", charts(current));
        }
        return buildSimpleResult(buildMessage(current, previous, start, end, prevStart, prevEnd, keyword, showAmounts, monthOverMonth), data);
    }

    // ==================== 聚合 ====================

    /** 本厂在用产品里名称匹配 keyword 的: 有完全同名 (忽略大小写) 只返回它, 否则返回所有包含匹配。 */
    List<ProductType> matchProducts(String factoryId, String keyword) {
        return ReportToolSchemas.matchProducts(productTypeRepository, factoryId, keyword);
    }

    private Aggregate aggregate(String factoryId, LocalDate start, LocalDate end, String keyword, Set<String> productIds,
                                ProcessSecretLlmCostScope.Excluded secret) {
        return aggregate(batchRepository, factoryId, start, end, keyword, productIds, secret);
    }

    /** 同口径聚合入口 (人工成本占比等同包工具复用, 不另写一份口径)。 */
    static Aggregate aggregate(ProductionBatchRepository batchRepository, String factoryId, LocalDate start,
                               LocalDate end, String keyword, ProcessSecretLlmCostScope.Excluded secret) {
        return aggregate(batchRepository, factoryId, start, end, keyword, null, secret);
    }

    static Aggregate aggregate(ProductionBatchRepository batchRepository, String factoryId, LocalDate start,
                               LocalDate end, String keyword, Set<String> productIds,
                               ProcessSecretLlmCostScope.Excluded secret) {
        // 与成本分析页同口径: 按批次结束日 (DATE(end_time)) 落期, 半开区间 [start 00:00, end+1 00:00)
        List<ProductionBatch> batches = batchRepository.findByFactoryIdAndEndTimeGreaterThanEqualAndEndTimeLessThan(
                factoryId, start.atStartOfDay(), end.plusDays(1).atStartOfDay());
        Aggregate agg = new Aggregate();
        if (batches == null) {
            return agg;
        }
        for (ProductionBatch b : batches) {
            if (b.getStatus() != ProductionBatchStatus.COMPLETED) continue;
            if ("CLERK_WIP".equals(b.getBatchType())) continue;
            if (keyword != null && !containsIgnoreCase(b.getProductName(), keyword)) continue;
            if (productIds != null && (b.getProductTypeId() == null || !productIds.contains(b.getProductTypeId()))) continue;
            if (secret.coversBatch(b.getProductTypeId(), b.getProductionPlanId())) {
                agg.withheldBatches++;
                continue;
            }
            agg.batchCount++;
            if (Boolean.TRUE.equals(b.getCostIncomplete()) || b.getTotalCost() == null) {
                agg.incompleteBatches++;
                continue;
            }
            agg.add(b);
        }
        return agg;
    }

    static final class Aggregate {
        int batchCount;
        int incompleteBatches;
        int withheldBatches;
        int costedBatches;
        BigDecimal material = BigDecimal.ZERO, labor = BigDecimal.ZERO, equipment = BigDecimal.ZERO, other = BigDecimal.ZERO, total = BigDecimal.ZERO;
        final Map<String, ProductAgg> byProduct = new LinkedHashMap<>();

        void add(ProductionBatch b) {
            costedBatches++;
            material = material.add(nz(b.getMaterialCost()));
            labor = labor.add(nz(b.getLaborCost()));
            equipment = equipment.add(nz(b.getEquipmentCost()));
            other = other.add(nz(b.getOtherCost()));
            total = total.add(b.getTotalCost());
            String name = b.getProductName() != null ? b.getProductName() : "(未命名产品)";
            String key = b.getProductTypeId() != null && !b.getProductTypeId().isBlank() ? b.getProductTypeId() : "name:" + name;
            ProductAgg p = byProduct.computeIfAbsent(key, k -> new ProductAgg());
            p.productTypeId = b.getProductTypeId();
            if (p.name == null) p.name = name;
            p.batches++;
            p.total = p.total.add(b.getTotalCost());
            p.labor = p.labor.add(nz(b.getLaborCost()));
            // 单位成本口径 = 成本分析页 (factory_production_gold.py product-cost-compare):
            // 有成本批次的总成本 ÷ 这些批次的「实际产量」, 按 (产品, 单位) 分开。不用良品数 (页面不用)。
            String unit = b.getUnit() != null ? b.getUnit() : "";
            p.costByUnit.merge(unit, b.getTotalCost(), BigDecimal::add);
            if (b.getActualQuantity() != null) {
                p.qtyByUnit.merge(unit, b.getActualQuantity(), BigDecimal::add);
            }
        }

        /** 所有成本已算全批次都只有一个产出单位时返回该单位 (没有批次或多个单位返回 null)。 */
        String singleUnit() {
            java.util.Set<String> units = new java.util.LinkedHashSet<>();
            for (ProductAgg p : byProduct.values()) units.addAll(p.qtyByUnit.keySet());
            return units.size() == 1 ? units.iterator().next() : null;
        }

        /** 单一单位时的总产量, 否则 null。 */
        BigDecimal singleUnitQuantity() {
            String unit = singleUnit();
            if (unit == null) return null;
            BigDecimal sum = BigDecimal.ZERO;
            for (ProductAgg p : byProduct.values()) sum = sum.add(p.qtyByUnit.getOrDefault(unit, BigDecimal.ZERO));
            return sum;
        }

        String withheldNote() {
            return "另有 " + withheldBatches + " 个批次涉及工艺保密产品，当前账号无权查看其成本，未计入。";
        }

        Map<String, Object> totalsMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("materialCost", money(material));
            m.put("laborCost", money(labor));
            m.put("equipmentCost", money(equipment));
            m.put("otherCost", money(other));
            m.put("totalCost", money(total));
            m.put("costedBatchCount", costedBatches);
            return m;
        }

        List<Map<String, Object>> compositionList() {
            List<Map<String, Object>> list = new ArrayList<>();
            addShare(list, "原料", material);
            addShare(list, "人工", labor);
            addShare(list, "设备", equipment);
            addShare(list, "其他", other);
            return list;
        }

        private void addShare(List<Map<String, Object>> list, String name, BigDecimal amount) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("amount", money(amount));
            m.put("sharePct", total.signum() > 0 ? amount.multiply(HUNDRED).divide(total, 1, RoundingMode.HALF_UP) : null);
            list.add(m);
        }

        List<Map<String, Object>> productRows(int limit) {
            List<ProductAgg> entries = new ArrayList<>(byProduct.values());
            entries.sort((a, c) -> c.total.compareTo(a.total));
            List<Map<String, Object>> rows = new ArrayList<>();
            for (ProductAgg p : entries) {
                if (rows.size() >= limit) break;
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("productTypeId", p.productTypeId);
                r.put("productName", p.name);
                r.put("batchCount", p.batches);
                r.put("totalCost", money(p.total));
                r.put("unitCosts", p.unitCosts());
                rows.add(r);
            }
            return rows;
        }
    }

    static final class ProductAgg {
        String productTypeId;
        String name;
        int batches;
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        final Map<String, BigDecimal> qtyByUnit = new LinkedHashMap<>();
        final Map<String, BigDecimal> costByUnit = new LinkedHashMap<>();

        /** 每个产出单位各一条: 元/单位。没有产出数量就是空列表 (不编单位成本)。 */
        List<Map<String, Object>> unitCosts() {
            List<Map<String, Object>> list = new ArrayList<>();
            for (Map.Entry<String, BigDecimal> e : qtyByUnit.entrySet()) {
                if (e.getValue().signum() <= 0) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("unit", e.getKey().isEmpty() ? "未标单位" : e.getKey());
                m.put("quantity", e.getValue().setScale(2, RoundingMode.HALF_UP));
                m.put("unitCost", costByUnit.get(e.getKey()).divide(e.getValue(), 4, RoundingMode.HALF_UP));
                list.add(m);
            }
            return list;
        }

        String unitCostText() {
            List<Map<String, Object>> list = unitCosts();
            if (list.isEmpty()) return "无产出数量，单位成本算不出";
            StringBuilder sb = new StringBuilder();
            for (Map<String, Object> m : list) {
                if (sb.length() > 0) sb.append("；");
                sb.append("¥").append(((BigDecimal) m.get("unitCost")).setScale(2, RoundingMode.HALF_UP)).append("/").append(m.get("unit"));
            }
            return sb.toString();
        }
    }

    // ==================== 对比 / 图表 / 文案 ====================

    private List<Map<String, Object>> comparison(Aggregate cur, Aggregate prev) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (prev.costedBatches == 0) {
            return list;
        }
        addDelta(list, "原料", cur.material, prev.material);
        addDelta(list, "人工", cur.labor, prev.labor);
        addDelta(list, "设备", cur.equipment, prev.equipment);
        addDelta(list, "其他", cur.other, prev.other);
        addDelta(list, "总成本", cur.total, prev.total);
        return list;
    }

    private void addDelta(List<Map<String, Object>> list, String name, BigDecimal now, BigDecimal before) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("current", money(now));
        m.put("previous", money(before));
        m.put("change", money(now.subtract(before)));
        m.put("changePct", before.signum() > 0
                ? now.subtract(before).multiply(HUNDRED).divide(before, 1, RoundingMode.HALF_UP) : null);
        list.add(m);
    }

    private List<Map<String, Object>> charts(Aggregate cur) {
        List<Map<String, Object>> charts = new ArrayList<>();
        // 成本构成饼图: 只画金额 > 0 的分项
        List<Map<String, Object>> pie = new ArrayList<>();
        for (Map<String, Object> c : cur.compositionList()) {
            BigDecimal amount = (BigDecimal) c.get("amount");
            if (amount.signum() > 0) {
                pie.add(Map.of("name", c.get("name"), "value", amount));
            }
        }
        if (!pie.isEmpty()) {
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("tooltip", Map.of("trigger", "item"));
            option.put("legend", Map.of("bottom", 0));
            option.put("series", List.of(Map.of("type", "pie", "radius", List.of("40%", "68%"), "data", pie)));
            charts.add(Map.of("type", "pie", "title", "成本构成（元）", "option", option));
        }
        // 产品总成本柱图
        List<Map<String, Object>> rows = cur.productRows(8);
        if (!rows.isEmpty()) {
            List<String> names = new ArrayList<>();
            List<BigDecimal> values = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                names.add(String.valueOf(r.get("productName")));
                values.add((BigDecimal) r.get("totalCost"));
            }
            Map<String, Object> option = new LinkedHashMap<>();
            option.put("tooltip", Map.of("trigger", "axis"));
            option.put("grid", Map.of("left", 60, "right", 20, "bottom", 70, "containLabel", true));
            option.put("xAxis", Map.of("type", "category", "data", names, "axisLabel", Map.of("interval", 0, "rotate", 25)));
            option.put("yAxis", Map.of("type", "value", "name", "元"));
            option.put("series", List.of(Map.of("type", "bar", "name", "总成本", "data", values)));
            charts.add(Map.of("type", "bar", "title", "产品总成本（元，前 8）", "option", option));
        }
        return charts;
    }

    private String buildMessage(Aggregate cur, Aggregate prev, LocalDate start, LocalDate end, LocalDate prevStart,
                                LocalDate prevEnd, String keyword, boolean showAmounts, boolean monthOverMonth) {
        String prevLabel = monthOverMonth ? "上月同期" : "上一个等长周期";
        StringBuilder sb = new StringBuilder();
        sb.append(start).append(" 至 ").append(end).append(keyword != null ? "「" + keyword + "」" : "")
                .append(" 已完成批次 ").append(cur.batchCount).append(" 个");
        if (!showAmounts) {
            sb.append("。成本金额按当前账号权限不显示；需要金额请用有成本查看权限的账号。");
            return sb.toString();
        }
        sb.append("，其中成本已算全 ").append(cur.costedBatches).append(" 个。");
        if (cur.incompleteBatches > 0) {
            sb.append("另有 ").append(cur.incompleteBatches).append(" 个批次成本未算全（如原料缺单价），不计入下面的合计。");
        }
        if (cur.withheldBatches > 0) {
            sb.append(cur.withheldNote());
        }
        if (cur.costedBatches == 0) {
            sb.append("没有成本已算全的批次，总成本和单位成本算不出。");
            return sb.toString();
        }
        sb.append("\n总成本 ¥").append(money(cur.total)).append("：");
        List<Map<String, Object>> comp = cur.compositionList();
        for (int i = 0; i < comp.size(); i++) {
            Map<String, Object> c = comp.get(i);
            if (i > 0) sb.append("，");
            sb.append(c.get("name")).append(" ¥").append(c.get("amount"));
            if (c.get("sharePct") != null) sb.append("（").append(c.get("sharePct")).append("%）");
        }
        sb.append("。");
        List<Map<String, Object>> rows = cur.productRows(5);
        sb.append("\n各产品（总成本从高到低，前 ").append(rows.size()).append("）：");
        List<ProductAgg> entries = new ArrayList<>(cur.byProduct.values());
        entries.sort((a, c) -> c.total.compareTo(a.total));
        int n = 0;
        for (ProductAgg p : entries) {
            if (n++ >= 5) break;
            sb.append("\n").append(n).append(". ").append(p.name).append("：总成本 ¥").append(money(p.total))
                    .append("，").append(p.batches).append(" 批，单位成本 ").append(p.unitCostText());
        }
        if (prev.costedBatches == 0) {
            sb.append("\n对比：" + prevLabel + "（").append(prevStart).append(" 至 ").append(prevEnd).append("）没有成本已算全的批次，无法给涨跌。");
        } else {
            sb.append("\n对比" + prevLabel + "（").append(prevStart).append(" 至 ").append(prevEnd).append("，成本已算全 ")
                    .append(prev.costedBatches).append(" 个批次").append(prev.incompleteBatches > 0
                            ? "，另有 " + prev.incompleteBatches + " 个成本未算全未计入" : "").append("）：");
            List<Map<String, Object>> cmp = comparison(cur, prev);
            for (int i = 0; i < cmp.size(); i++) {
                Map<String, Object> c = cmp.get(i);
                if (i > 0) sb.append("；");
                sb.append(c.get("name")).append(" ").append(signed((BigDecimal) c.get("change")));
                if (c.get("changePct") != null) sb.append("（").append(signed((BigDecimal) c.get("changePct"))).append("%）");
            }
            sb.append("。");
            sb.append(riserText(cur, prev));
        }
        sb.append("\n口径：只统计已完成批次（按结束日落期）；单位成本 = 成本已算全批次的总成本 ÷ 这些批次的实际产量，按单位分开算，与成本分析页一致。");
        return sb.toString();
    }

    // ==================== 小工具 ====================

    /**
     * 涨得最多的分项。两期都只有同一个产出单位且有产量时, 按「每单位产量的分项成本」比 (产量翻倍、单位成本不变不算涨);
     * 否则按总额比并明说是总额、没有按产量折算。只在 原料/人工/设备/其他 里比, 不含总成本。
     */
    static String riserText(Aggregate cur, Aggregate prev) {
        BigDecimal curQty = cur.singleUnitQuantity();
        BigDecimal prevQty = prev.singleUnitQuantity();
        boolean perUnit = curQty != null && prevQty != null && curQty.signum() > 0 && prevQty.signum() > 0
                && java.util.Objects.equals(cur.singleUnit(), prev.singleUnit());
        String[] names = {"原料", "人工", "设备", "其他"};
        BigDecimal[] now = {cur.material, cur.labor, cur.equipment, cur.other};
        BigDecimal[] before = {prev.material, prev.labor, prev.equipment, prev.other};
        int top = -1;
        BigDecimal topChange = BigDecimal.ZERO;
        for (int i = 0; i < names.length; i++) {
            BigDecimal a = perUnit ? now[i].divide(curQty, 4, RoundingMode.HALF_UP) : now[i];
            BigDecimal b = perUnit ? before[i].divide(prevQty, 4, RoundingMode.HALF_UP) : before[i];
            BigDecimal change = a.subtract(b);
            if (change.signum() > 0 && change.compareTo(topChange) > 0) {
                top = i;
                topChange = change;
            }
        }
        String basis = perUnit ? "按每" + (cur.singleUnit().isEmpty() ? "单位" : cur.singleUnit()) + "产量的单位成本比"
                : "按总额比，没有按产量折算（产量或单位不同，产量变化会影响总额）";
        if (top < 0) return "\n各分项都没有上涨（" + basis + "）。";
        String amount = topChange.setScale(perUnit ? 2 : 2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        return "\n涨得最多的是" + names[top] + "：+" + amount + (perUnit ? " 元/" + (cur.singleUnit().isEmpty() ? "单位" : cur.singleUnit()) : " 元")
                + "（" + basis + "）。";
    }

    private static String signed(BigDecimal v) {
        return (v.signum() > 0 ? "+" : "") + v.stripTrailingZeros().toPlainString();
    }

    static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static boolean containsIgnoreCase(String hay, String needle) {
        return hay != null && hay.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static LocalDate parseDate(String text, String name) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(name + " 格式应为 yyyy-MM-dd: " + text);
        }
    }

    static LocalDate[] periodRange(String period, LocalDate today) {
        switch (period) {
            case "last_week": {
                LocalDate monday = today.with(DayOfWeek.MONDAY).minusWeeks(1);
                return new LocalDate[]{monday, monday.plusDays(6)};
            }
            case "this_week":
                return new LocalDate[]{today.with(DayOfWeek.MONDAY), today};
            case "last_month": {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                return new LocalDate[]{first, first.withDayOfMonth(first.lengthOfMonth())};
            }
            case "this_month":
                return new LocalDate[]{today.withDayOfMonth(1), today};
            default:
                throw new IllegalArgumentException("不支持的 period: " + period);
        }
    }

    /** 只读: 不调模型、不落库。 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.READ;
    }

    /**
     * S6 gated-data rule (2026-10-29): the AI / Skill path admits exactly who the direct API admits
     * for the same data; pinned by GatedDataToolAiPathPermissionTest.
     */
    @Override
    public java.util.Set<String> getRequiredPermissions() {
        return java.util.Set.of("analytics:read_write");
    }
}
