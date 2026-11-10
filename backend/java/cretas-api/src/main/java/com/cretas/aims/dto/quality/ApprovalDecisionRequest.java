package com.cretas.aims.dto.quality;

import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * 审批决策请求 DTO
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
@Data
public class ApprovalDecisionRequest {

    /**
     * 审批决定 (APPROVE/REJECT)
     */
    @NotNull(message = "审批决定不能为空")
    private ApprovalDecision decision;

    /**
     * 审批意见
     */
    @NotBlank(message = "审批意见不能为空")
    private String comment;

    /**
     * 审批人ID (从Token中获取，可选传入)
     */
    private Long approverId;

    /**
     * 附加条件 (如果是条件批准)
     */
    private String conditions;

    /**
     * 后续跟踪要求
     */
    private String followUpRequirements;

    /**
     * 本人审批原因 (纪律排查 B3): 审批人就是申请人、且工厂开了「允许本人审批」时必填;
     * 不填时取审批意见 (comment)。
     */
    private String selfApprovalReason;

    /**
     * 审批意见是调用方代填的占位词 (例如 AI 工具在模型没给原因时写「通过」/「拒绝」), 不是人写的。
     * 为 true 时意见<b>不能</b>顶替本人审批原因 —— 否则「本人审批必须写原因」被一个占位词绕过
     * (#1580 独立审查必修 2)。只由服务端代码设置, 不从请求 JSON 读。
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    private boolean commentIsPlaceholder;

    /**
     * 审批决定枚举
     */
    public enum ApprovalDecision {
        APPROVE("批准"),
        REJECT("拒绝");

        private final String description;

        ApprovalDecision(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }
}
