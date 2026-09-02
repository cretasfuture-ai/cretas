package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;

/**
 * 2B Task B2: 把生产计划关联的 workflow 批次快照投影为文员过程单可消费的配置。
 */
public interface WorkflowClerkSheetService {

    /**
     * @param factoryId 工厂 ID (租户隔离)
     * @param planId    生产计划 ID
     * @return 该计划的 workflow 过程单配置; 只有计划没有 workflow 批次（明确 legacy）时返回 {@code null}
     * @throws com.cretas.aims.exception.BusinessException 已锁定 WORKFLOW 的批次快照不完整时阻断，禁止回退 legacy
     */
    WorkflowClerkSheetConfigDTO getWorkflowSheetConfig(String factoryId, String planId);

    /**
     * 计划钉住的 Workflow 已转批次且运行时实例已物化时, 返回编译冻结的 INPUT 端口份额
     * ({@code workflow_task_ports.input_share_ratio}, key = workflowPortId, 只收非 null 的)。
     * 没有 Workflow 批次 / 实例还没物化 ⇒ {@link java.util.Optional#empty()} (调用方退回读修订 nodesJson);
     * 多批次歧义 ⇒ 与报工同一个 409 WORKFLOW_RUNTIME_BATCH_AMBIGUOUS。
     */
    java.util.Optional<java.util.Map<String, java.math.BigDecimal>> resolveCompiledInputShares(
            String factoryId, String planId);
}
