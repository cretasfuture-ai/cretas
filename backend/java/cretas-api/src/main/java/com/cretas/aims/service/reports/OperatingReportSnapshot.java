package com.cretas.aims.service.reports;

import java.math.BigDecimal;
import java.util.List;

/** Versioned, query-derived payload stored in the existing analysis_text column. */
public record OperatingReportSnapshot(
        String schema, Status status, String reportType, String periodStart, String periodEnd,
        String fetchedAt, List<SourceReceipt> sources, List<Section> sections,
        List<Insight> insights, Failure failure, AuditReceipt audit) {
    public OperatingReportSnapshot(String schema, Status status, String reportType, String periodStart, String periodEnd,
                                   String fetchedAt, List<SourceReceipt> sources, List<Section> sections,
                                   List<Insight> insights, Failure failure) {
        this(schema,status,reportType,periodStart,periodEnd,fetchedAt,sources,sections,insights,failure,null);
    }
    public OperatingReportSnapshot withAudit(AuditReceipt receipt) {
        return new OperatingReportSnapshot(schema,status,reportType,periodStart,periodEnd,fetchedAt,sources,sections,insights,failure,receipt);
    }
    public OperatingReportSnapshot withInsights(List<Insight> selection) {
        return new OperatingReportSnapshot(schema,status,reportType,periodStart,periodEnd,fetchedAt,sources,sections,selection,failure,audit);
    }
    public record AuditReceipt(String principalType,String principalId,String factoryId,String runId,
                               String correlationId,String purpose,String scopedCapability,String outcome) { }
    public static final String SCHEMA = "CRETAS_OPERATING_REPORT_V1";
    public enum Status { SUCCESS, FAILED }
    public enum Failure {
        SOURCE_UNAVAILABLE("经营数据查询失败，请稍后重试或联系管理员检查数据服务。"),
        MODEL_UNAVAILABLE("AI 解读生成失败，请稍后重试。"),
        MODEL_OUTPUT_INVALID("AI 解读未通过结构和指标引用校验，请重新生成。"),
        INVALID_SNAPSHOT("报告内容无法解析，请重新生成。"),
        GENERATION_FAILED("经营报告生成失败，请稍后重试。");
        private final String reason;
        Failure(String reason) { this.reason = reason; }
        public String reason() { return reason; }
    }
    public enum InsightKind { MISSING_VALUE, INCOMPLETE_COVERAGE, REVIEW_RECORDED_VALUE }
    public enum Action { VERIFY_COST, VERIFY_ADDRESS, VERIFY_SALESPERSON, FOLLOW_UP_COLLECTION, REVIEW_PROFIT, REVIEW_INVENTORY, REVIEW_SALES }
    public record Insight(InsightKind kind, String metricId, Action action) { }
    public record SourceReceipt(String endpoint, String fetchedAt, String basis) { }
    public record Section(String id, String title, boolean priceSensitive, String basis, List<Metric> metrics) { }
    public record Metric(String id, String label, BigDecimal value, String unit,
                         String missingReason, BigDecimal coverage, String sourcePath) { }
}
