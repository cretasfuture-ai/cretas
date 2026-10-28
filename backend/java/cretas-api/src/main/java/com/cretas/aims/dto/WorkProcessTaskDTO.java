package com.cretas.aims.dto;

import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 工序任务 DTO (Track D2 — M-WP-1/2).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkProcessTaskDTO {

    private Long id;
    private String factoryId;
    private Long productionBatchId;
    private Long productWorkProcessId;
    private Long workflowInstanceId;
    private String workflowNodeId;
    private String workProcessId;
    private String productTypeId;
    private Integer processOrder;
    private WorkProcessTask.Status status;

    private BigDecimal plannedQuantity;
    private String plannedUnit;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime plannedStartAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime plannedEndAt;

    /**
     * 这道工序超期没有 —— <b>三态</b>，不是布尔。
     *
     * <table border="1">
     *   <caption>三个值各自的意思</caption>
     *   <tr><th>值</th><th>含义</th></tr>
     *   <tr><td>{@code true}</td><td>还没干完，而计划完成日已经过去了</td></tr>
     *   <tr><td>{@code false}</td><td>判过了，没超期（含已完成/已跳过/已取消 —— 活结束了）</td></tr>
     *   <tr><td>{@code null}</td><td><b>判不了</b>：这道工序没设 {@code plannedEndAt}</td></tr>
     * </table>
     *
     * <p>⛔ {@code null} 不许在任何一层兜底成 {@code false}。「没设计划完成日」
     * 和「按期」是两件完全不同的事：前者是主管<b>还没排产</b>，后者是<b>排了且没拖</b>。
     * 兜底会把一屏「没排期」的工序画成一屏「一切正常」，那不是显示错误，是编了一个结论。
     *
     * <p>判据来自 {@link com.cretas.aims.enums.DeliveryWarnLevel}，与 AI 的交货预警
     * （「哪些订单要超期了」）是<b>同一份</b>，⛔ 不是这里重新判的。
     */
    private Boolean overdue;

    /**
     * 按工艺图能不能报这一道 (缺口 04): 上游产出的料到了就是 true。由后端
     * {@link com.cretas.aims.service.workprocess.WorkflowStepUnlockGuard} 算, 与开工 / 报工写入口拦截用的是同一份判定。
     * {@code null} = 没算 (终态任务) —— 前端不要把 null 当成 false。
     */
    private Boolean unlocked;

    /** {@code unlocked=false} 时给人看的原因, 写明在等哪一道上游。 */
    private String blockedReason;

    private Integer estimatedMinutes;

    private BigDecimal actualQuantity;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualStartAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime actualEndAt;

    private Integer actualMinutes;

    private Long assignedTo;
    /** T142: 责任人姓名 (后端 batch join, 不查 DB 逐条, 参照 T135 responsibleWorkerName 模式). null = 未分配或用户已删除. */
    private String assignedToName;
    private Long completedBy;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime completedAt;

    private String notes;

    /** 关联展示字段 (后端 join 提供, 选填). */
    private String processName;
    private String processCategory;

    /** T157: 产品名称 (list 路径 batch join 透出, 无 N+1; 给 RN 小组长跨批次选择屏分组用). null = 批次/产品已删除. */
    private String productTypeName;

    /** T157: 批次号 (list 路径 batch join 透出, 无 N+1). null = 批次已删除. */
    private String batchNumber;

    /** 标准出成率下限 (张权 A7; 来自该工序 WorkProcess, join 透出; null=不校验). */
    private BigDecimal standardYieldMin;

    /** 标准出成率上限 (张权 A7; 支持 >1 如滚揉保水 1.35; null=不校验). */
    private BigDecimal standardYieldMax;

    /** P0-2 产出单位 (来自该工序 WorkProcess.outputUnit, join 透出; null=沿用投入单位). */
    private String outputUnit;

    /**
     * 预期副产物列表 (防呆 Rule 3: 报工 OUTPUT 阶段预填提示; 来自 WorkProcess.expectedByproducts).
     * 格式: [{"name":"肥油","unit":"kg","defaultEnabled":true}, ...]
     * null = 本道无预期副产物, RN 不预填。
     */
    private List<Map<String, Object>> expectedByproducts;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    // ==================== sub-request DTOs ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CompleteRequest {
        @NotNull(message = "actualQuantity 不能为空")
        @PositiveOrZero(message = "actualQuantity 不能是负数（没做出来就填 0）")
        private BigDecimal actualQuantity;

        @Size(max = 500, message = "notes 不能超过 500 字符")
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SkipRequest {
        @NotNull(message = "原因不能为空")
        @Size(min = 1, max = 500, message = "原因 1-500 字符")
        private String notes;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AssignRequest {
        @NotNull(message = "assignedTo 不能为空")
        private Long assignedTo;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdatePlanRequest {
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime plannedStartAt;

        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        private LocalDateTime plannedEndAt;

        private BigDecimal plannedQuantity;
        private String plannedUnit;
        private Integer estimatedMinutes;

        /**
         * 指派给谁。{@code null} = <b>本次不改</b>（与其余字段同语义）。
         *
         * <p>⚠️ 所以它<b>清不掉</b>已有的指派 —— 要退回「未指派」必须用
         * {@link #clearAssignee}。
         */
        private Long assignedTo;

        /**
         * 🔴 把这道工序退回「未指派」。
         *
         * <h3>为什么需要它</h3>
         *
         * <p>指派是<b>排他</b>的，而且是两处判据一起排他：
         * 后端 {@code ReportAuthGuard#assertCanReport}（不是本人且非主管 → 403），
         * 以及 App 的 {@code isTaskClaimableBy}（指派给他人的在「我的任务」里<b>看不到</b>）。
         *
         * <p>于是「徐师傅回家了，还不知道谁接手」这种情况没有出路：
         * {@code assignedTo} 传 {@code null} 只是「不改」，改派又必须先知道改给谁。
         * ⇒ 排他必须配一个逃生门，否则一次派错就把那道工序焊死到主管手动介入为止。
         *
         * <p>置 {@code true} 时忽略 {@code assignedTo}，直接清空。
         */
        private Boolean clearAssignee;

        @Size(max = 500, message = "notes 不能超过 500 字符")
        private String notes;
    }

    /** Canonical per-task production report aggregation. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WorkerSummary {
        private Long workerId;
        private String workerName;
        private BigDecimal totalQuantity;
        private BigDecimal approvedQuantity;
        private BigDecimal pendingQuantity;
        private Integer reportCount;
    }
}
