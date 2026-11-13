package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;
import com.cretas.aims.entity.workflow.ApprovalWorkflowInstance;
import com.cretas.aims.service.ApprovalWorkflowService;
import com.cretas.aims.service.workflow.WorkflowEngineService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * 1551-11 开工被拦时说清「这张强制插单在等谁批」—— 只读, 只给 {@link PendingApprovalStartGuard} 拼提示用。
 *
 * <ul>
 *   <li>本厂启用了强制插单审批画布: 有进行中的画布实例 ⇒ 「审批中心里「节点名」这一步」;</li>
 *   <li>没有画布 (高 / 严重影响走直接审批): 有排产或生产管理权限的人在「强制插单审批」里批。</li>
 * </ul>
 * 查不到 / 查询出错只影响措辞, 不影响拦截 (拦不拦只由 {@link PendingApprovalStartGuard} 决定)。
 */
@Slf4j
@Component
public class ForceInsertApproverDescriber {

    /** 强制插单的审批画布模块码; UrgentInsertServiceImpl 建实例用的就是这一个。 */
    public static final String MODULE = "FORCE_INSERT";

    static final String DIRECT_APPROVAL = "有排产或生产管理权限的人在「强制插单审批」里批准";

    @Autowired(required = false)
    private WorkflowEngineService workflowEngine;
    @Autowired(required = false)
    private ApprovalWorkflowService approvalWorkflowService;

    public ForceInsertApproverDescriber() {}

    /** 测试用; Spring 走无参构造 + 字段注入 (两个依赖都可缺)。 */
    public ForceInsertApproverDescriber(WorkflowEngineService workflowEngine, ApprovalWorkflowService approvalWorkflowService) {
        this.workflowEngine = workflowEngine;
        this.approvalWorkflowService = approvalWorkflowService;
    }

    /** 返回「等 X」里的 X, 例如「审批中心里「厂长审批」这一步批准」。 */
    public String describe(ProductionPlan plan) {
        if (workflowEngine == null || plan == null || plan.getId() == null) {
            return DIRECT_APPROVAL;
        }
        try {
            Optional<ApprovalWorkflowInstance> found =
                    workflowEngine.getCurrentInstance(plan.getFactoryId(), MODULE, plan.getId());
            if (found.isEmpty() || found.get().getStatus() != ApprovalWorkflowInstance.InstanceStatus.RUNNING) {
                return DIRECT_APPROVAL;
            }
            Set<String> steps = currentStepLabels(plan.getFactoryId(), found.get());
            return steps.isEmpty()
                    ? "审批中心里当前审批人批准"
                    : "审批中心里「" + String.join("」「", steps) + "」这一步批准";
        } catch (RuntimeException e) {
            log.warn("开工拦截: 查强制插单审批进度失败, 提示用通用说法 planId={}: {}", plan.getId(), e.toString());
            return "审批人批准";
        }
    }

    private Set<String> currentStepLabels(String factoryId, ApprovalWorkflowInstance instance) {
        Set<String> labels = new LinkedHashSet<>();
        if (approvalWorkflowService == null || instance.getCurrentNodeIds() == null) {
            return labels;
        }
        Optional<ApprovalWorkflow> workflow = approvalWorkflowService.getById(factoryId, instance.getWorkflowId());
        if (workflow.isEmpty()) {
            return labels;
        }
        for (ApprovalWorkflowNode node : approvalWorkflowService.deserializeNodes(workflow.get().getNodesJson())) {
            if (instance.getCurrentNodeIds().contains(node.getId()) && node.getLabel() != null
                    && !node.getLabel().isBlank()) {
                labels.add(node.getLabel());
            }
        }
        return labels;
    }
}
