package com.cretas.aims.service.integration;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 能推送进 47 的外部数据集, 以及每行允许出现的字段 (白名单)。
 * ⛔ 白名单里没有人员 / 工资字段: 推送里出现白名单外的键一律拒收 (不是丢掉), 推送端就得先投影干净。
 * 取值与 external_source_snapshots 的 ck_ess_dataset 一致。
 */
public enum ExternalDataset {
    /** 黑湖报工按「报工日 (北京) × 产品 × 工序 × 单位」汇总后的良品 / 不良数量。 */
    REPORT_OUTPUT("report_output", "报工",
            Set.of("reportDate", "productCode", "productName", "processName", "unit",
                    "goodQty", "defectQty", "reportCount")),
    PROJECT_COST("project_cost", "工单成本", "costDate", "workOrderCode",
            Set.of("productCode"), Set.of("productName", "outputUnit"), Set.of(),
            Set.of("outputQty", "materialCost", "laborCost", "otherCost", "totalCost", "unitCost")),
    PRODUCTION_ISSUE("production_issue", "生产领料", "issueDate", "lineId",
            Set.of("materialCode"), Set.of("workOrderCode", "materialName", "unit"), Set.of("quantity"),
            Set.of("costUnitPrice", "signedCostAmount", "movingAveragePrice"));

    private final String code;
    private final String label;
    private final Set<String> allowedFields;
    private final String dateField;
    private final String keyField;
    private final Set<String> requiredText;
    private final Set<String> nullableText;
    private final Set<String> requiredDecimal;
    private final Set<String> nullableDecimal;

    ExternalDataset(String code, String label, Set<String> allowedFields) {
        this.code = code;
        this.label = label;
        this.allowedFields = allowedFields;
        this.dateField = "reportDate";
        this.keyField = null;
        this.requiredText = this.nullableText = this.requiredDecimal = this.nullableDecimal = Set.of();
    }

    ExternalDataset(String code, String label, String dateField, String keyField,
                    Set<String> requiredText, Set<String> nullableText,
                    Set<String> requiredDecimal, Set<String> nullableDecimal) {
        this.code = code;
        this.label = label;
        this.dateField = dateField;
        this.keyField = keyField;
        var text = new HashSet<>(requiredText);
        text.add(keyField);
        this.requiredText = Set.copyOf(text);
        this.nullableText = nullableText;
        this.requiredDecimal = requiredDecimal;
        this.nullableDecimal = nullableDecimal;
        var fields = new HashSet<>(text);
        fields.add(dateField);
        fields.addAll(nullableText);
        fields.addAll(requiredDecimal);
        fields.addAll(nullableDecimal);
        this.allowedFields = Set.copyOf(fields);
    }

    public String code() { return code; }

    public String label() { return label; }

    public Set<String> allowedFields() { return allowedFields; }

    String dateField() { return dateField; }

    String keyField() { return keyField; }

    boolean validValue(String field, Object value) {
        if (requiredText.contains(field) || nullableText.contains(field)) {
            return value == null ? nullableText.contains(field)
                    : value instanceof String text && !text.isBlank() && text.equals(text.strip());
        }
        if (requiredDecimal.contains(field) || nullableDecimal.contains(field)) {
            if (value == null) return nullableDecimal.contains(field);
            if (!(value instanceof String || value instanceof Number)) return false;
            try {
                var amount = new BigDecimal(value.toString());
                return amount.precision() - (long) amount.scale() <= 101
                        && amount.scale() >= -100 && amount.scale() <= 100;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public static Optional<ExternalDataset> of(String code) {
        return Arrays.stream(values()).filter(d -> d.code.equals(code)).findFirst();
    }
}
