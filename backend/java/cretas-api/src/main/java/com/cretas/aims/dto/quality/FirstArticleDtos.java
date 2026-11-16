package com.cretas.aims.dto.quality;

import com.cretas.aims.entity.quality.firstarticle.FirstArticleInspection;
import com.cretas.aims.entity.quality.firstarticle.FirstArticlePolicy;
import com.cretas.aims.service.quality.firstarticle.FirstArticleItemRules;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 工序首检接口的请求 / 返回形状 (设计 docs/specs/process-first-article-inspection-design.md)。 */
public final class FirstArticleDtos {

    private FirstArticleDtos() {
    }

    // ==================== 请求 ====================

    public record PolicyRequest(FirstArticlePolicy.Mode mode) {
    }

    /** {@code id == null} 新建; 否则改这条标准 (revision + 1, 工序不能改)。 */
    public record StandardRequest(Long id, String workProcessId, String name,
                                  List<FirstArticleItemRules.Item> items) {
    }

    public record ReasonRequest(String reason) {
    }

    public record InspectionRequest(Long taskId, BigDecimal sampleQuantity, String sampleUnit, String notes) {
    }

    /** {@code selfJudgeReason}: 判定人就是送检人、且本厂开了「允许本人审批」时必填 (开关关时本人一律不能判)。 */
    public record JudgeRequest(List<FirstArticleItemRules.ResultInput> results,
                               FirstArticleInspection.Verdict verdict, String notes, String selfJudgeReason) {
    }

    // ==================== 返回 ====================

    public record PolicyView(FirstArticlePolicy.Mode mode, String modeLabel, boolean configured) {
    }

    public record StandardView(Long id, String workProcessId, String processName, String name,
                               List<FirstArticleItemRules.Item> items, boolean active, int revision,
                               String deactivatedReason,
                               @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt) {
    }

    /** 保存标准前的预览: 规整后的检验项 + 每项判定口径, 不写库。 */
    public record StandardPreview(String action, String workProcessId, String processName, String name,
                                  List<FirstArticleItemRules.Item> items, List<String> ruleTexts,
                                  int nextRevision) {
    }

    public record InspectionView(Long id, Long taskId, Long productionBatchId, String workProcessId,
                                 String processName, Long standardId, int standardRevision, String standardName,
                                 List<FirstArticleItemRules.Item> standardItems,
                                 FirstArticleInspection.Status status, String statusLabel,
                                 BigDecimal sampleQuantity, String sampleUnit, String requestNotes,
                                 Long requestedBy,
                                 @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime requestedAt,
                                 List<FirstArticleItemRules.ItemResult> results,
                                 FirstArticleInspection.Verdict autoVerdict,
                                 Long judgedBy,
                                 @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime judgedAt,
                                 String judgeNotes, boolean selfJudged, String selfJudgeReason, String voidReason,
                                 @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime voidedAt) {
    }

    /** 判定预览: 服务端逐项算出的结论, 不写库。 */
    public record JudgePreview(Long inspectionId, List<FirstArticleItemRules.ItemResult> results,
                               FirstArticleInspection.Verdict autoVerdict, List<String> failedItems,
                               FirstArticleInspection.Verdict verdict, boolean canSubmit, String message,
                               boolean selfJudge) {
    }

    /**
     * 「工序首检」表的一行: 一个要首检的工序任务 + 它当前的首检状态。
     * {@code firstArticleStatus}: NOT_REQUESTED (还没送检) / PENDING / PASSED / FAILED。
     */
    public record TaskRow(Long taskId, Long productionBatchId, String batchNumber, String workProcessId, String processName,
                          String productTypeId, String taskStatus, String taskStatusLabel,
                          @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime actualStartAt,
                          Long standardId, String standardName,
                          String firstArticleStatus, String firstArticleStatusLabel,
                          Long latestInspectionId) {
    }
}
