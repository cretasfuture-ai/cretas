package com.cretas.aims.service;

import com.cretas.aims.entity.config.ApprovalChainConfig.DecisionType;
import com.cretas.aims.entity.config.ApprovalWorkflow;
import com.cretas.aims.entity.config.ApprovalWorkflowEdge;
import com.cretas.aims.entity.config.ApprovalWorkflowNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Graph-native 审批工作流服务.
 *
 * <p>Sprint 3 Track-I (C-APPROVAL-EDITOR-1) 引入。Canvas graph 是新审批请求
 * 的唯一运行时配置来源:
 * <ul>
 *   <li>ApprovalWorkflowService 管理可执行的 graph 与不可变发布版本.</li>
 *   <li>ApprovalChainService 仅保留旧 flat-list 的只读迁移证据.</li>
 * </ul>
 *
 * @since 2026-05-16
 */
public interface ApprovalWorkflowService {

    // ==================== CRUD ====================

    ApprovalWorkflow create(String factoryId, ApprovalWorkflow workflow);

    /**
     * @param force M-6 (2026-08-20 手动测试台账): published/archived 版本默认按在途实例数决定
     *              能不能原地改 —— 0 条在途放行，&gt;0 条要么拒绝要么显式 force=true 放行。
     *              draft 版本不受这个参数影响，仍然一律不能有在途实例（理论上不会有）。
     */
    ApprovalWorkflow update(String factoryId, String id, ApprovalWorkflow partial, boolean force);

    /** 该 workflow 当前处于 RUNNING 状态的审批实例数 —— 前端据此决定是否放行原地编辑。 */
    int countRunningInstances(String factoryId, String id);

    void delete(String factoryId, String id);

    Optional<ApprovalWorkflow> getById(String factoryId, String id);

    /** Exact pessimistic lock for validated-definition instance creation. */
    Optional<ApprovalWorkflow> getByIdForUpdate(String factoryId, String id);

    List<ApprovalWorkflow> getAllByFactory(String factoryId);

    List<ApprovalWorkflow> getByDecisionType(String factoryId, DecisionType decisionType);

    // ==================== Lookup (executor 用) ====================

    /**
     * 找当前 active workflow.
     *
     * <p>Filter: {@code publishStatus='published'} + {@code enabled=true}.
     * <p>必须恰好 0 或 1 个 active；历史异常出现多个时 fail-closed，不按优先级猜选.
     *
     * @return Optional.empty() 表示没有 active workflow，新请求按“无需审批”直通；
     *         如仍存在启用的旧配置则由 cutover gate 阻止并要求迁移
     */
    Optional<ApprovalWorkflow> getActiveByDecisionType(String factoryId, DecisionType decisionType);

    // ==================== Lifecycle ====================

    /** Clone an immutable published/history definition into a separate editable draft. */
    ApprovalWorkflow cloneAsDraft(String factoryId, String id);

    /** Draft → Published (transactional). */
    ApprovalWorkflow publishDraft(String factoryId, String id);

    /** Published → Archived (transactional). */
    ApprovalWorkflow archive(String factoryId, String id);

    /** Toggle enabled flag (PATCH semantics). */
    ApprovalWorkflow toggleEnabled(String factoryId, String id, boolean enabled);

    // ==================== JSONB 序列化 helpers ====================

    /** Deserialize {@code nodesJson} String → List. */
    List<ApprovalWorkflowNode> deserializeNodes(String nodesJson);

    /** Deserialize {@code edgesJson} String → List. */
    List<ApprovalWorkflowEdge> deserializeEdges(String edgesJson);

    /** Serialize List → JSON String (for setNodesJson). */
    String serializeNodes(List<ApprovalWorkflowNode> nodes);

    /** Serialize List → JSON String (for setEdgesJson). */
    String serializeEdges(List<ApprovalWorkflowEdge> edges);

    // ==================== Validation ====================

    /**
     * 校验 graph 结构 + SpEL syntax.
     *
     * <p>Checks:
     * <ul>
     *   <li>≥ 1 节点, ≥ 1 type=start 节点 且 startNodeId 匹配</li>
     *   <li>≥ 1 type=end 节点</li>
     *   <li>所有 edge source/target 引用合法 nodeId</li>
     *   <li>无孤立节点 (除 start)</li>
     *   <li>无环 (DAG topology)</li>
     *   <li>join 节点 mode 字段合法 (ALL/N_OF_M/ANY)</li>
     *   <li>condition SpEL 语法基础合法 (轻量 parse)</li>
     * </ul>
     *
     * @return {@code {valid: boolean, errors: List<String>, warnings: List<String>}}
     */
    Map<String, Object> validateGraph(ApprovalWorkflow workflow);

    // ==================== 统计 ====================

    Map<DecisionType, Long> getConfigStatistics(String factoryId);
}
