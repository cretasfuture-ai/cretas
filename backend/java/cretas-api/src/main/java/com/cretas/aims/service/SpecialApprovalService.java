package com.cretas.aims.service;

import com.cretas.aims.dto.quality.ApprovalDecisionRequest;
import com.cretas.aims.dto.quality.SpecialApprovalDTO;
import com.cretas.aims.dto.quality.SpecialApprovalRequest;

import java.util.List;

/**
 * 特批放行审批服务接口
 *
 * 管理质检特批放行的申请、审批流程
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2025-12-30
 */
public interface SpecialApprovalService {

    /**
     * 提交特批放行申请
     *
     * @param factoryId 工厂ID
     * @param inspectionId 质检记录ID
     * @param request 特批申请请求
     * @param requesterId 申请人ID
     * @return 特批申请信息
     */
    SpecialApprovalDTO submitSpecialApproval(
            String factoryId,
            String inspectionId,
            SpecialApprovalRequest request,
            Long requesterId
    );

    /**
     * 获取待审批列表
     *
     * @param factoryId 工厂ID
     * @return 待审批列表
     */
    List<SpecialApprovalDTO> getPendingApprovals(String factoryId);

    /**
     * 获取特批申请详情
     *
     * @param factoryId 工厂ID
     * @param approvalId 审批ID
     * @return 特批申请详情
     */
    SpecialApprovalDTO getApprovalById(String factoryId, String approvalId);

    /**
     * 能审批质检处置的角色 —— 与 {@code QualityDispositionController} 的 {@code /approve} 上的 {@code @RequireRole}
     * 同一份名单, 也是 {@code ProcessingController} 的 {@code /quality/approvals/{id}/decision} 上的 {@code @RequireRole}
     * 与 AI 工具 {@code order_approval} 的 {@code getRequiredRoles()}。注解只能写字面量, 所以几处一致由
     * {@code QualityBatchDispositionContractTest#approverRoleListsAreTheSameEverywhere} 钉住。
     */
    java.util.Set<String> APPROVER_ROLES =
            java.util.Set.of("quality_manager", "factory_super_admin", "platform_admin", "permission_admin");

    /**
     * 处理审批决策
     *
     * <p>谁在审批以 {@code actingUserId} / {@code actingRole} 为准 —— 调用方从<b>已认证的身份</b>取
     * (HTTP: JWT 拦截器写入的 userId / role; AI 工具: 执行上下文里的 userId 及其账号角色), 不从请求体取。
     * 与 {@code /quality-disposition/{id}/approve} 同口径: 只有审批角色能审批, 申请人不能批自己的申请。
     *
     * @param factoryId 工厂ID
     * @param approvalId 审批ID
     * @param decision 审批决策
     * @param approverId 记录在审批行上的审批人ID (沿用原来的取值方式)
     * @param actingUserId 当前已认证的操作人 (审批身份校验用)
     * @param actingRole 当前已认证操作人的角色代码 (审批身份校验用)
     * @return 更新后的审批信息
     */
    SpecialApprovalDTO processDecision(
            String factoryId,
            String approvalId,
            ApprovalDecisionRequest decision,
            Long approverId,
            Long actingUserId,
            String actingRole
    );

    /**
     * 获取我的申请记录
     *
     * @param factoryId 工厂ID
     * @param requesterId 申请人ID
     * @return 申请记录列表
     */
    List<SpecialApprovalDTO> getMyRequests(String factoryId, Long requesterId);

    /**
     * 获取我的审批记录
     *
     * @param factoryId 工厂ID
     * @param approverId 审批人ID
     * @return 审批记录列表
     */
    List<SpecialApprovalDTO> getMyApprovals(String factoryId, Long approverId);
}
