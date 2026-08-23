package com.cretas.aims.dto.approval;

import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 更新审批工作流请求 DTO.
 *
 * <p>所有字段可选 (PATCH 语义): service 层用 {@code if (... != null)} guard 决定
 * 是否更新该字段, null 字段保留 DB 原值. {@code decisionType} 不允许改 (只能新建).
 *
 * @see com.cretas.aims.entity.config.ApprovalWorkflow
 */
@Data
@Schema(description = "更新审批工作流请求")
public class UpdateApprovalWorkflowRequest {

    @Schema(description = "工作流名称")
    @Size(max = 100, message = "工作流名称长度不能超过100个字符")
    private String name;

    @Schema(description = "工作流描述")
    @Size(max = 500, message = "工作流描述长度不能超过500个字符")
    private String description;

    @Schema(description = "节点列表 — 全量替换语义 (传 null = 不动)")
    private List<ApprovalWorkflowNode> nodes;

    @Schema(description = "边列表 — 全量替换语义 (传 null = 不动)")
    private List<ApprovalWorkflowEdge> edges;

    @Schema(description = "入口节点ID")
    @Size(max = 50, message = "入口节点ID长度不能超过50个字符")
    private String startNodeId;

    @Schema(description = "优先级")
    private Integer priority;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "M-6: 已发布/历史版本在有在途审批实例时，仍要求原地修改 —— " +
            "默认 false (0 条在途放行，>0 条拒绝)；用户在前端明确看到在途条数后选择「仍然直接改」才传 true")
    private Boolean force;
}
