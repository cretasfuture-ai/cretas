package com.cretas.aims.dto.workflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * "我待审" widget 单行 DTO — issue #20.
 *
 * <p>由 {@code WorkflowInstanceController.getPendingForUser} 返回. 前端用来在
 * Dashboard 顶部显示 finance_manager / quality_manager / 等审批角色待审的
 * RUNNING workflow 实例.
 *
 * <p>businessSummary 由 controller 根据 moduleCode 现 hydrate:
 * <ul>
 *   <li>PURCHASE_ORDER → "PO-20260518-0123 ¥40000 (供应商甲)"</li>
 *   <li>SALES_ORDER → 类似 pattern (Phase 2 加)</li>
 *   <li>其他 → instanceId fallback</li>
 * </ul>
 *
 * @since 2026-05-18 (issue #20 / Phase 1 closure for ADR-001 AC-3)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "我待审 widget 单行 DTO")
public class WorkflowInstancePendingDTO {

    @Schema(description = "工作流实例 ID (UUID)")
    private String instanceId;

    @Schema(description = "业务模块 code", example = "PURCHASE_ORDER")
    private String moduleCode;

    @Schema(description = "业务实体 ID (PO id / SO id)")
    private String businessEntityId;

    @Schema(description = "前端显示用业务摘要", example = "PO-20260518-0123 ¥40000 (供应商甲)")
    private String businessSummary;

    // ─────────────────────────────────────────────────────────────────────
    // 🔴 M-12 (2026-08-20, 财务主管实测): 上面那个 businessSummary 是把
    //    单号 + 金额 + 往来单位**拼成一句话**。审批列表拿到的就只有这一句,
    //    于是财务只能看着 "PO-20260820-0002 ¥35000.00 (北京飞熊)" 点通过/驳回。
    //
    //    拼成一句话还带来两个具体损失:
    //      · 金额**不能右对齐、不能排序** —— 而审批的人最想做的就是按金额排, 先看大额的;
    //      · 往来单位不能筛。
    //
    //    ⛔ 前端**不许**从 businessSummary 正则反解这三个值 ——
    //       格式一改就崩, 本仓在「拿正则切拼接文本」上栽过(见 measurement-and-wiring 形态 A‴)。
    //    ⇒ 在这里下发结构化字段, 前端直接用; businessSummary 保留作兜底,
    //       未接入的模块仍显示原来那句话, ⛔ 不要因为加了新字段就把它删掉。
    // ─────────────────────────────────────────────────────────────────────

    @Schema(description = "业务单据号 (结构化, 供前端单独成列并做跳转)", example = "PO-20260820-0002")
    private String businessCode;

    @Schema(description = "单据金额 (结构化, 供前端右对齐 + 排序); 该业务无金额概念时为 null",
            example = "35000.00")
    private java.math.BigDecimal businessAmount;

    @Schema(description = "往来单位 —— 采购看供应商, 销售看客户; 无则为 null", example = "北京飞熊")
    private String counterpartyName;

    /** 仅报工修正审批下发：含 before/after、原因、工序与关联计划/批次。 */
    @Schema(description = "报工修正审批差异；非该业务类型时为 null")
    private Map<String, Object> reportCorrectionDetails;

    @Schema(description = "当前 active 审批节点 ID")
    private String currentNodeId;

    @Schema(description = "当前节点显示名", example = "财务审批")
    private String currentNodeLabel;

    @Schema(description = "工作流启动时间 (RUNNING 实例的发起时间)")
    private LocalDateTime initiatedAt;

    @Schema(description = "发起人 username (PO 创建者). 系统触发时为 null.")
    private String initiatedByUsername;

    @Schema(description = "实例状态", example = "RUNNING")
    private String status;

    @Schema(description = "终态时间")
    private LocalDateTime completedAt;

    @Schema(description = "当前节点授权角色")
    private List<String> approverRoles;

    /**
     * 业务类型中文名, 取自权威表 {@code DecisionTypeMetadataRegistry}。
     *
     * <p>前端曾自行维护一份 4 个码的 MODULE_LABELS, 其余 20 多个码全落「未知状态（X）」兜底
     * (客户截图里的「未知状态（BUDGET）」即此)。改由后端按权威表下发, 前端不再维护第二份。
     * 解析不出来时为 null, 由前端兜底 —— 后端不编造。
     */
    @Schema(description = "业务类型中文名 (取自权威表; 解析不出时为 null, 前端兜底)",
            example = "会计期间结账")
    private String moduleLabel;

    /**
     * 该实例是否由系统流程发起(无人类申请人)。
     *
     * <p>判据是 {@code initiatedBy == null}, <b>不是</b> username 为空 —— 用户被删同样会让
     * username 为空, 那种情况该显示「—」而非「系统自动发起」。
     * 客户截图里申请人空白的那条是凌晨 2 点定时任务发起的月度会计期间结账审批。
     */
    @Schema(description = "是否系统自动发起 (无人类申请人)", example = "true")
    private boolean systemInitiated;
}
