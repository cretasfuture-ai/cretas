package com.cretas.aims.ai.tool.impl.dataop;

import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolRbacGuard;
import com.cretas.aims.dto.quality.ApprovalDecisionRequest;
import com.cretas.aims.dto.quality.SpecialApprovalDTO;
import com.cretas.aims.service.SpecialApprovalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 订单/调拨审批工具
 *
 * 审批订单或内部调拨单，支持通过和拒绝操作。
 * Intent Code: ORDER_APPROVAL
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-03-07
 */
@Slf4j
@Component
public class OrderApprovalTool extends AbstractBusinessTool {

    @Autowired
    private SpecialApprovalService specialApprovalService;

    /** 操作人角色: 按执行上下文里的 userId 读账号角色 (与 ToolRbacEnforcer 的角色判定同一来源)。 */
    @Autowired
    private ToolRbacGuard rbacGuard;

    /**
     * 与 App 的审批入口同一份审批角色 (SpecialApprovalService.APPROVER_ROLES): ToolRbacEnforcer 先挡一道;
     * 真正的权威校验 (角色 + 申请人不能自批) 在 SpecialApprovalService.processDecision 里, 所有入口都过。
     */
    @Override
    public Set<String> getRequiredRoles() {
        return SpecialApprovalService.APPROVER_ROLES;
    }

    @Override
    public String getToolName() {
        return "order_approval";
    }

    @Override
    public String getDescription() {
        return "审批订单或内部调拨单。支持通过(approve)和拒绝(reject)操作。" +
                "适用场景：审批采购单、审批调拨单、通过/拒绝订单。";
    }

    @Override
    public Map<String, Object> getParametersSchema() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new HashMap<>();

        Map<String, Object> transferId = new HashMap<>();
        transferId.put("type", "string");
        transferId.put("description", "单据ID（订单ID或调拨单ID）");
        properties.put("transferId", transferId);

        Map<String, Object> reject = new HashMap<>();
        reject.put("type", "boolean");
        reject.put("description", "是否拒绝，默认false(通过)");
        properties.put("reject", reject);

        Map<String, Object> reason = new HashMap<>();
        reason.put("type", "string");
        reason.put("description", "拒绝原因（拒绝时需要）；审批的是用户自己提交的申请时，这里填用户说的本人审批原因，不要自己编");
        properties.put("reason", reason);

        schema.put("properties", properties);
        schema.put("required", Collections.singletonList("transferId"));

        return schema;
    }

    @Override
    protected List<String> getRequiredParameters() {
        return Collections.singletonList("transferId");
    }

    @Override
    protected String getParameterQuestion(String paramName) {
        if ("transferId".equals(paramName)) {
            return "请提供要审批的单据ID (transferId)。";
        }
        return super.getParameterQuestion(paramName);
    }

    @Override
    protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params, Map<String, Object> context) throws Exception {
        String approvalId = getString(params, "transferId");
        boolean isReject = Boolean.TRUE.equals(params.get("reject"));
        String rawReason = getString(params, "reason");
        // 只认模型从用户那里拿到的原因; 空白当没给
        String reason = rawReason == null || rawReason.isBlank() ? null : rawReason.trim();

        // Extract approverId from context if available
        Long approverId = null;
        if (context != null && context.get("userId") instanceof Number) {
            approverId = ((Number) context.get("userId")).longValue();
        }

        try {
            ApprovalDecisionRequest decision = new ApprovalDecisionRequest();
            decision.setDecision(isReject
                    ? ApprovalDecisionRequest.ApprovalDecision.REJECT
                    : ApprovalDecisionRequest.ApprovalDecision.APPROVE);
            decision.setComment(reason != null ? reason : (isReject ? "拒绝" : "通过"));
            // 纪律排查 B3 / #1580 审查必修 2: 「通过」「拒绝」是代填的占位词, 不能当本人审批原因
            decision.setCommentIsPlaceholder(reason == null);
            decision.setSelfApprovalReason(reason);

            // 审批身份 = 执行上下文里的已认证用户 (不是模型给的参数)
            SpecialApprovalDTO result = specialApprovalService.processDecision(factoryId, approvalId, decision,
                    approverId, approverId, rbacGuard.roleOf(context));
            return buildSimpleResult(isReject ? "审批拒绝成功" : "审批通过成功", result);
        } catch (com.cretas.aims.exception.BusinessException e) {
            if (com.cretas.aims.service.workflow.FactorySelfApprovalGate.REASON_REQUIRED.equals(e.getErrorCode())) {
                // 不审批, 回去追问用户本人审批的原因 (不替用户编)
                throw new com.cretas.aims.exception.BusinessException(422,
                        "这是你自己提交的申请。本厂允许本人审批，但需要你说明原因：请告诉我为什么由你本人审批，我再提交。")
                        .withCode(com.cretas.aims.service.workflow.FactorySelfApprovalGate.REASON_REQUIRED)
                        .withHintTarget("reason");
            }
            log.warn("订单审批被拒: factoryId={}, approvalId={}, isReject={}, code={}",
                    factoryId, approvalId, isReject, e.getErrorCode());
            throw e;
        } catch (Exception e) {
            log.error("订单审批失败: factoryId={}, approvalId={}, isReject={}", factoryId, approvalId, isReject, e);
            throw e;
        }
    }

    /** spec §8.2 有副作用, 须走 W0 写确认闸 */
    @Override
    public AccessMode getAccessMode() {
        return AccessMode.WRITE;
    }
}
