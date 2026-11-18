package com.cretas.aims.service.reports;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/** All report surfaces use this projection; stored JSON is never an API report body. */
@Component
public class OperatingReportCodec {
    private final ObjectMapper mapper;
    public OperatingReportCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public String encode(OperatingReportSnapshot snapshot) {
        try { return mapper.writeValueAsString(snapshot); }
        catch (Exception exception) { throw new IllegalStateException("Operating report encoding failed"); }
    }

    public Optional<OperatingReportSnapshot> decode(String stored) {
        if (stored == null || !stored.stripLeading().startsWith("{")) return Optional.empty();
        try {
            OperatingReportSnapshot snapshot = mapper.readValue(stored, OperatingReportSnapshot.class);
            if (!OperatingReportSnapshot.SCHEMA.equals(snapshot.schema()) || snapshot.status() == null
                    || snapshot.sections() == null || snapshot.sources() == null) throw new IllegalArgumentException();
            return Optional.of(snapshot);
        } catch (Exception exception) {
            return Optional.of(new OperatingReportSnapshot(OperatingReportSnapshot.SCHEMA,
                    OperatingReportSnapshot.Status.FAILED, null, null, null, null, List.of(), List.of(),
                    null, OperatingReportSnapshot.Failure.INVALID_SNAPSHOT));
        }
    }

    /** The model selects only validated enums and metric references; no free text or model numbers are rendered. */
    public List<OperatingReportSnapshot.Insight> parseInsights(String raw, OperatingReportSnapshot snapshot) {
        try {
            if (raw == null || raw.length() > 65536) throw new IllegalArgumentException();
            String json = raw.trim();
            json = extractJson(json);
            var root = mapper.readTree(json);
            if (!root.isObject() || root.size() != 1 || !root.path("insights").isArray() || root.path("insights").size() > 20) throw new IllegalArgumentException();
            Map<String, OperatingReportSnapshot.Metric> metrics = snapshot.sections().stream().flatMap(s -> s.metrics().stream())
                    .collect(Collectors.toMap(OperatingReportSnapshot.Metric::id, Function.identity(), (a,b) -> a));
            List<OperatingReportSnapshot.Insight> insights = new ArrayList<>();
            for (var item : root.path("insights")) {
                if (!item.isObject() || item.size() != 3 || !item.path("kind").isTextual() || !item.path("metricId").isTextual() || !item.path("action").isTextual()) throw new IllegalArgumentException();
                var kind = OperatingReportSnapshot.InsightKind.valueOf(item.path("kind").asText());
                var action = OperatingReportSnapshot.Action.valueOf(item.path("action").asText());
                String metricId = item.path("metricId").asText();
                var metric = metrics.get(metricId);
                if (metric == null) throw new IllegalArgumentException();
                if (kind == OperatingReportSnapshot.InsightKind.MISSING_VALUE && metric.value() != null) throw new IllegalArgumentException();
                if (kind == OperatingReportSnapshot.InsightKind.INCOMPLETE_COVERAGE && (metric.coverage() == null || metric.coverage().compareTo(java.math.BigDecimal.ONE) >= 0)) throw new IllegalArgumentException();
                if (kind == OperatingReportSnapshot.InsightKind.REVIEW_RECORDED_VALUE && metric.value() == null) throw new IllegalArgumentException();
                insights.add(new OperatingReportSnapshot.Insight(kind, metricId, action));
            }
            return List.copyOf(insights);
        } catch (Exception exception) { throw new IllegalArgumentException("Invalid operating report insight selection"); }
    }


    private static final java.util.regex.Pattern PLACEHOLDER = java.util.regex.Pattern.compile("\\{\\{([^{}]+)\\}\\}");
    /** 开发口径: 指标 id / 字段名 / 接口名 (形如 sales.mom、indicator_code、gross-margin) 不许出现在给老板看的正文里。 */
    private static final java.util.regex.Pattern DEV_TOKEN = java.util.regex.Pattern.compile("[A-Za-z]+(?:[._-][A-Za-z0-9]+)+");
    private static final java.util.regex.Pattern DIGITS = java.util.regex.Pattern.compile("[0-9０-９]+");
    private static final java.util.regex.Pattern CN_QUANTITY = java.util.regex.Pattern.compile("[一二两三四五六七八九十百千万亿]+(?:[成倍元万%％]|个点)|百分之");

    /**
     * 自由业务结论: 模型输出 {"conclusions":[{"text":"...","refs":["指标id"]}]}。
     * 数字只能写成 {{指标id}} 占位 (读取时按真实指标值替换); 正文里任何裸数字 / 中文数量词一律拒收 (整份输出作废, 不静默删句)。
     * 产品名等词语来自指标标签, 里面带的数字 (如「3号」) 允许原样出现。
     */
    public List<OperatingReportSnapshot.Insight> parseConclusions(String raw, OperatingReportSnapshot snapshot) {
        try {
            if (raw == null || raw.length() > 65536) throw new IllegalArgumentException();
            String json = raw.trim();
            json = extractJson(json);
            var root = mapper.readTree(json);
            if (!root.isObject() || root.size() != 1 || !root.path("conclusions").isArray() || root.path("conclusions").size() > 8) throw new IllegalArgumentException();
            Map<String, OperatingReportSnapshot.Metric> metrics = metricsById(snapshot);
            Set<String> labelDigits = new java.util.HashSet<>();
            for (var metric : metrics.values()) { var m = DIGITS.matcher(metric.label() == null ? "" : metric.label()); while (m.find()) labelDigits.add(m.group()); }
            List<OperatingReportSnapshot.Insight> result = new ArrayList<>();
            for (var item : root.path("conclusions")) {
                if (!item.isObject() || item.size() != 2 || !item.path("text").isTextual() || !item.path("refs").isArray()) throw new IllegalArgumentException();
                String text = item.path("text").asText().strip();
                if (text.isEmpty() || text.length() > 400) throw new IllegalArgumentException();
                List<String> refs = new ArrayList<>();
                for (var ref : item.path("refs")) { if (!ref.isTextual() || !metrics.containsKey(ref.asText())) throw new IllegalArgumentException(); refs.add(ref.asText()); }
                var placeholders = PLACEHOLDER.matcher(text);
                while (placeholders.find()) {
                    String id = placeholders.group(1).trim();
                    var metric = metrics.get(id);
                    if (metric == null || metric.value() == null || !refs.contains(id)) throw new IllegalArgumentException();
                }
                String bare = PLACEHOLDER.matcher(text).replaceAll(" ");
                var digits = DIGITS.matcher(bare);
                while (digits.find()) if (!labelDigits.contains(digits.group())) throw new IllegalArgumentException();
                if (CN_QUANTITY.matcher(bare).find()) throw new IllegalArgumentException();
                var dev = DEV_TOKEN.matcher(bare);
                while (dev.find()) if (metrics.values().stream().noneMatch(m -> m.label() != null && m.label().contains(dev.group()))) throw new IllegalArgumentException();
                result.add(new OperatingReportSnapshot.Insight(OperatingReportSnapshot.InsightKind.NARRATIVE, null, null, text, List.copyOf(refs)));
            }
            return List.copyOf(result);
        } catch (Exception exception) { throw new IllegalArgumentException("Invalid operating report conclusions"); }
    }

    /** 新合同 conclusions 与旧合同 insights 都认 (旧合同仅为兼容既有调用与测试)。 */
    public List<OperatingReportSnapshot.Insight> parseModelOutput(String raw, OperatingReportSnapshot snapshot) {
        try {
            String json = raw == null ? "" : raw.trim();
            json = extractJson(json);
            if (mapper.readTree(json).has("conclusions")) return parseConclusions(raw, snapshot);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) { }
        return parseInsights(raw, snapshot);
    }

    private static Map<String, OperatingReportSnapshot.Metric> metricsById(OperatingReportSnapshot snapshot) {
        return snapshot.sections().stream().flatMap(s -> s.metrics().stream())
                .collect(Collectors.toMap(OperatingReportSnapshot.Metric::id, Function.identity(), (a,b) -> a));
    }

    static String formatValue(OperatingReportSnapshot.Metric metric) {
        java.math.BigDecimal v = metric.value().setScale(2, java.math.RoundingMode.HALF_UP).stripTrailingZeros();
        String unit = metric.unit() == null ? "" : metric.unit();
        return v.toPlainString() + unit;
    }

    /** 占位符换成真实指标值; 引用的指标已被遮 / 无值 → 返回 empty (整条不显示)。 */
    static Optional<String> fillNarrative(String text, Map<String, OperatingReportSnapshot.Metric> metrics) {
        var m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            var metric = metrics.get(m.group(1).trim());
            if (metric == null || metric.value() == null) return Optional.empty();
            m.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(formatValue(metric)));
        }
        m.appendTail(out);
        return Optional.of(out.toString());
    }

    /** 工艺保密遮挡标记 (与其他页面「已保密」一致): 值为 null 且缺失原因是它, 正文显示「已保密」而不是「数据缺失」。 */
    public static final String PROCESS_SECRET_MASK = "已保密";
    /** 与保密产品同计划联产的普通产品: 成本与保密产品分摊, 单独给会泄露 —— 中性措辞, 不打「保密」标签。 */
    public static final String CO_PRODUCT_MASK = "不可单独计算";

    public String render(OperatingReportSnapshot snapshot, boolean canViewPrices) {
        if (snapshot.status() == OperatingReportSnapshot.Status.FAILED) {
            return "生成失败：" + failure(snapshot).reason();
        }
        StringBuilder text = new StringBuilder("# 经营分析报告\n\n报告期间：")
                .append(snapshot.periodStart()).append(" 至 ").append(snapshot.periodEnd())
                .append("\n取数时间：").append(snapshot.fetchedAt()).append("\n\n");
        if (noOrders(snapshot)) text.append("> ").append(NO_DATA_NOTICE).append("\n\n");
        for (OperatingReportSnapshot.Section section : snapshot.sections()) {
            if (section.priceSensitive() && !canViewPrices) continue;
            text.append("## ").append(label(section.title())).append("\n")
                    .append(label(section.basis())).append("\n\n");
            for (OperatingReportSnapshot.Metric metric : section.metrics()) {
                text.append("- ").append(label(metric.label())).append("：");
                if (metric.value() == null && PROCESS_SECRET_MASK.equals(metric.missingReason())) text.append(PROCESS_SECRET_MASK);
                else if (metric.value() == null && CO_PRODUCT_MASK.equals(metric.missingReason())) text.append(CO_PRODUCT_MASK);
                else if (metric.value() == null) text.append("数据缺失（").append(label(metric.missingReason())).append("）");
                else text.append(plain(metric)).append(label(metric.unit()));
                if (metric.coverage() != null) text.append("；覆盖率 ")
                        .append(metric.coverage().multiply(java.math.BigDecimal.valueOf(100)).setScale(1, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()).append('%');
                else if (metric.missingReason() != null && metric.value() != null)
                    text.append("；").append(label(metric.missingReason()));
                text.append('\n');
            }
            if (section.metrics().isEmpty()) text.append("- 无查询记录；覆盖率不可计算\n");
            text.append('\n');
        }
        text.append("## 数据来源与统计口径\n");
        for (OperatingReportSnapshot.SourceReceipt source : snapshot.sources()) {
            text.append("- ").append(label(sourceName(source.endpoint()))).append("；")
                    .append(label(sourceBasis(source.basis()))).append("；取数时间 ").append(label(source.fetchedAt())).append('\n');
        }
        text.append('\n');
        if (canViewPrices) {
            text.append("## 经营解读与建议\n");
            Map<String, OperatingReportSnapshot.Metric> metrics = metricsById(snapshot);
            boolean any = false;
            if (snapshot.insights() != null) for (var insight : snapshot.insights()) {
                if (insight.kind() == OperatingReportSnapshot.InsightKind.NARRATIVE) {
                    var filled = fillNarrative(insight.text() == null ? "" : insight.text(), metrics);
                    if (filled.isPresent()) { text.append("- ").append(label(filled.get())).append('\n'); any = true; }
                    continue;
                }
                var metric = metrics.get(insight.metricId());
                if (metric == null) continue;
                any = true;
                text.append("- ").append(label(metric.label())).append("：");
                text.append(switch (insight.kind()) { case MISSING_VALUE -> "数据缺失，不能据此作经营结论。"; case INCOMPLETE_COVERAGE -> "数据覆盖不完整，需补齐后复核。"; case REVIEW_RECORDED_VALUE -> "请结合以上查询值复核。"; case NARRATIVE -> ""; });
                text.append(switch (insight.action()) { case VERIFY_COST -> "核查并补齐成本记录。"; case VERIFY_ADDRESS -> "核查订单与客户地址。"; case VERIFY_SALESPERSON -> "补齐订单业务员归属。"; case FOLLOW_UP_COLLECTION -> "跟进客户回款与逾期应收。"; case REVIEW_PROFIT -> "复核已过账凭证与盈利情况。"; case REVIEW_INVENTORY -> "核查库存余量与预留安排。"; case REVIEW_SALES -> "复核销售结构与比较期间。"; }).append('\n');
            }
            var orders = metrics.get("sales.orders");
            if (orders != null && orders.value() != null && orders.value().signum() == 0) {
                text.append("- 本期（").append(snapshot.periodStart()).append(" 至 ").append(snapshot.periodEnd())
                        .append("）没有销售订单：销售额、环比同比、毛利与业务员、地区排行均无可分析的发生额；这是业务本身为零，不是数据丢失。")
                        .append("财务段为所在自然月口径，库存与应收为取数时点余额，可单独参考。\n");
                any = true;
            }
            if (!any) text.append("本期未生成可展示的业务结论；数字见上方各项数据。\n");
            appendFollowUp(text, snapshot, metrics);
        } else text.append("财务段落与 AI 财务解读已按价格权限裁剪。\n");
        return text.toString();
    }

    /** 模型常把 JSON 包在 ``` 围栏 (带不带 json 标记都有) 或前后加一句说明: 取第一个 { 到最后一个 } 之间; 内容仍须通过全部结构与指标校验。 */
    static String extractJson(String raw) {
        String s = raw == null ? "" : raw.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        return a >= 0 && b > a ? s.substring(a, b + 1) : s;
    }

    static final String NO_DATA_NOTICE = "所选期间内没有销售订单，下面各项销售数字为 0 属于业务本身为零，并非数据丢失。建议换一个有订单的日期或月份重新生成。";

    static boolean noOrders(OperatingReportSnapshot snapshot) {
        var orders = metricsById(snapshot).get("sales.orders");
        return orders != null && orders.value() != null && orders.value().signum() == 0;
    }

    /** 列表预览用的一句话摘要: 全 0 期间给明确提示, 否则取首条可显示的业务结论; 没有返回 null 由调用方回退。 */
    public String previewLine(OperatingReportSnapshot snapshot) {
        if (snapshot.status() != OperatingReportSnapshot.Status.SUCCESS || snapshot.sections() == null) return null;
        if (noOrders(snapshot)) return "该期间没有销售订单，各项销售数据为 0，建议换一个有订单的期间。";
        var metrics = metricsById(snapshot);
        if (snapshot.insights() != null) for (var insight : snapshot.insights())
            if (insight.kind() == OperatingReportSnapshot.InsightKind.NARRATIVE) {
                var filled = fillNarrative(insight.text() == null ? "" : insight.text(), metrics);
                if (filled.isPresent()) return filled.get();
            }
        return null;
    }

    /** 跟进分析: 由已校验的解读动作汇总, 每条说清跟进什么; 没有可跟进项就如实写明。 */
    private void appendFollowUp(StringBuilder text, OperatingReportSnapshot snapshot, Map<String, OperatingReportSnapshot.Metric> metrics) {
        java.util.LinkedHashSet<String> items = new java.util.LinkedHashSet<>();
        if (snapshot.insights() != null) for (var insight : snapshot.insights()) {
            if (insight.kind() == OperatingReportSnapshot.InsightKind.NARRATIVE || insight.action() == null) continue;
            var metric = metrics.get(insight.metricId());
            items.add((metric == null ? "" : label(metric.label()) + "：") + actionText(insight.action()));
        }
        text.append("\n## 跟进分析\n");
        if (items.isEmpty()) text.append("- 本期数据没有需要专门跟进的缺口，可按日常节奏复核销售、回款与库存。\n");
        else for (String item : items) text.append("- ").append(item).append('\n');
    }

    private static String actionText(OperatingReportSnapshot.Action action) {
        return switch (action) { case VERIFY_COST -> "核查并补齐成本记录。"; case VERIFY_ADDRESS -> "核查订单与客户地址。"; case VERIFY_SALESPERSON -> "补齐订单业务员归属。"; case FOLLOW_UP_COLLECTION -> "跟进客户回款与逾期应收。"; case REVIEW_PROFIT -> "复核已过账凭证与盈利情况。"; case REVIEW_INVENTORY -> "核查库存余量与预留安排。"; case REVIEW_SALES -> "复核销售结构与比较期间。"; };
    }

    /** 数字展示: 百分比保留至多三位小数 (不取整), 其余保持原精度去尾零。 */
    private static String plain(OperatingReportSnapshot.Metric metric) {
        var v = "%".equals(metric.unit()) ? metric.value().setScale(3, java.math.RoundingMode.HALF_UP) : metric.value();
        return v.stripTrailingZeros().toPlainString();
    }

    /** 取数来源的业务名称 (原为接口路径等开发口径)。 */
    static String sourceName(String endpoint) {
        String e = endpoint == null ? "" : endpoint;
        if (e.contains("regional-comparison")) return "地区销售对比";
        if (e.contains("salesperson-performance")) return "业务员销售业绩";
        if (e.contains("gross-margin")) return "产品毛利分析";
        if (e.contains("ar-ap/aging")) return "应收账龄";
        if (e.contains("income-statement")) return "利润表";
        if (e.contains("stock-summary")) return "库存汇总";
        return "经营数据";
    }

    static String sourceBasis(String basis) {
        if (basis == null) return "";
        return basis.replace("as of ", "截至 ").replace("; outstanding balance by due date", "，按到期日统计未收余额")
                .replace(" POSTED-only", "（仅含已过账凭证）").replace(" province", "（按省份）")
                .replace("current company-owned available remainder after reservations; Java repeatable-read", "取数时点公司自有批次可用余量（已扣除预留）")
                .replace("..", " 至 ");
    }

    public OperatingReportSnapshot.Failure failure(OperatingReportSnapshot snapshot) {
        return snapshot.failure() == null ? OperatingReportSnapshot.Failure.GENERATION_FAILED : snapshot.failure();
    }

    private String label(String text) {
        return text == null ? "覆盖率不可计算" : text.replaceAll("[\\r\\n\\t]", " ")
                .replace("<", "&lt;").replace(">", "&gt;").replace("#", "\\#");
    }
}
