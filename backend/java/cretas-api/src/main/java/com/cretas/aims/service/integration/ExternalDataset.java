package com.cretas.aims.service.integration;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * 能推送进 47 的外部数据集, 以及每行允许出现的字段 (白名单)。
 * ⛔ 白名单里没有人员 / 工资字段: 推送里出现白名单外的键一律拒收 (不是丢掉), 推送端就得先投影干净。
 * 取值与迁移 V20261115_51 的 CHECK 一致。
 */
public enum ExternalDataset {
    /** 黑湖报工按「报工日 (北京) × 产品 × 工序 × 单位」汇总后的良品 / 不良数量。 */
    REPORT_OUTPUT("report_output", "报工",
            Set.of("reportDate", "productCode", "productName", "processName", "unit",
                    "goodQty", "defectQty", "reportCount"));

    private final String code;
    private final String label;
    private final Set<String> allowedFields;

    ExternalDataset(String code, String label, Set<String> allowedFields) {
        this.code = code;
        this.label = label;
        this.allowedFields = allowedFields;
    }

    public String code() { return code; }

    public String label() { return label; }

    public Set<String> allowedFields() { return allowedFields; }

    public static Optional<ExternalDataset> of(String code) {
        return Arrays.stream(values()).filter(d -> d.code.equals(code)).findFirst();
    }
}
