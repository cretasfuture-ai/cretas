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
            if (json.startsWith("```json") && json.endsWith("```")) json = json.substring(7, json.length() - 3).trim();
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
        for (OperatingReportSnapshot.Section section : snapshot.sections()) {
            if (section.priceSensitive() && !canViewPrices) continue;
            text.append("## ").append(label(section.title())).append("\n")
                    .append(label(section.basis())).append("\n\n");
            for (OperatingReportSnapshot.Metric metric : section.metrics()) {
                text.append("- ").append(label(metric.label())).append("：");
                if (metric.value() == null && PROCESS_SECRET_MASK.equals(metric.missingReason())) text.append(PROCESS_SECRET_MASK);
                else if (metric.value() == null && CO_PRODUCT_MASK.equals(metric.missingReason())) text.append(CO_PRODUCT_MASK);
                else if (metric.value() == null) text.append("数据缺失（").append(label(metric.missingReason())).append("）");
                else text.append(metric.value().stripTrailingZeros().toPlainString()).append(label(metric.unit()));
                if (metric.coverage() != null) text.append("；覆盖率 ")
                        .append(metric.coverage().multiply(java.math.BigDecimal.valueOf(100)).stripTrailingZeros().toPlainString()).append('%');
                else if (metric.missingReason() != null && metric.value() != null)
                    text.append("；").append(label(metric.missingReason()));
                text.append('\n');
            }
            if (section.metrics().isEmpty()) text.append("- 无查询记录；覆盖率不可计算\n");
            text.append('\n');
        }
        text.append("## 取数来源与时间基准\n");
        for (OperatingReportSnapshot.SourceReceipt source : snapshot.sources()) {
            text.append("- ").append(label(source.endpoint())).append("；")
                    .append(label(source.basis())).append("；").append(label(source.fetchedAt())).append('\n');
        }
        text.append('\n');
        if (canViewPrices) {
            text.append("## AI 经营解读与建议\n");
            Map<String, OperatingReportSnapshot.Metric> metrics = snapshot.sections().stream().flatMap(s -> s.metrics().stream())
                    .collect(Collectors.toMap(OperatingReportSnapshot.Metric::id, Function.identity(), (a,b) -> a));
            if (snapshot.insights() == null || snapshot.insights().isEmpty()) text.append("模型未选择重点事项；数字见上述查询快照。\n");
            else for (var insight : snapshot.insights()) {
                var metric = metrics.get(insight.metricId());
                if (metric == null) continue;
                text.append("- ").append(label(metric.label())).append("：");
                text.append(switch (insight.kind()) { case MISSING_VALUE -> "数据缺失，不能据此作经营结论。"; case INCOMPLETE_COVERAGE -> "数据覆盖不完整，需补齐后复核。"; case REVIEW_RECORDED_VALUE -> "请结合以上查询值复核。"; });
                text.append(switch (insight.action()) { case VERIFY_COST -> "核查并补齐成本记录。"; case VERIFY_ADDRESS -> "核查订单与客户地址。"; case VERIFY_SALESPERSON -> "补齐订单业务员归属。"; case FOLLOW_UP_COLLECTION -> "跟进客户回款与逾期应收。"; case REVIEW_PROFIT -> "复核已过账凭证与盈利情况。"; case REVIEW_INVENTORY -> "核查库存余量与预留安排。"; case REVIEW_SALES -> "复核销售结构与比较期间。"; }).append('\n');
            }
        } else text.append("财务段落与 AI 财务解读已按价格权限裁剪。\n");
        return text.toString();
    }

    public OperatingReportSnapshot.Failure failure(OperatingReportSnapshot snapshot) {
        return snapshot.failure() == null ? OperatingReportSnapshot.Failure.GENERATION_FAILED : snapshot.failure();
    }

    private String label(String text) {
        return text == null ? "覆盖率不可计算" : text.replaceAll("[\\r\\n\\t]", " ")
                .replace("<", "&lt;").replace(">", "&gt;").replace("#", "\\#");
    }
}
