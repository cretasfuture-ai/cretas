package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowOutputResolutionDTO;

import java.util.List;
import java.util.Optional;

/**
 * 生产计划「选择成品 → 解析并固定 Workflow 路线」。
 *
 * <p>反向索引 = 运行时扫描已启用 activation 的原料图 nodesJson 终端成品 (不建映射表, 天然一致)。
 */
public interface ProductWorkflowResolutionService {

    /**
     * 给定一组成品 productTypeId, 解析最高优先层的已启用 workflow 候选 (只读, 空候选不报错)。
     * <ul>
     *   <li>单选和多选都优先终端集合精确图。</li>
     *   <li>无精确图时，只返回能够覆盖所选成品且额外产出最少的同层超集。</li>
     *   <li>同层可返回 1/N 条，N 条必须由调用方显式选择。</li>
     *   <li>0 候选 → NONE (空列表)。</li>
     * </ul>
     */
    WorkflowOutputResolutionDTO resolveForOutputs(String factoryId, List<String> finishedGoodProductTypeIds);

    /**
     * Resolve the unique active workflow and return only the ancestor process path for one finished SKU.
     * An empty result means that no active workflow exists and callers may use the legacy linear chain.
     * Ambiguous or invalid active workflows fail closed and must never fall back silently.
     */
    Optional<WorkflowProcessPath> resolveProcessPath(String factoryId, String finishedGoodProductTypeId);

    /**
     * 二次加工建计划前的预检: 目标成品当前已启用的 Workflow 里, 有没有工序的投入端口接这种半成品 (按 SKU 对)。
     *
     * <p>口径与开工时 {@code SecondaryPlanFeedService.assertAcceptedBy} 一致 (都按 INPUT 端口的 skuId 比),
     * 只是提前到建计划这一步。三态, 不退化成 false:
     * <ul>
     *   <li>{@code Optional.empty()} — 目标成品没有可解析的已启用 Workflow (旧式线性链没有端口可比), 不能判断, 调用方放行,
     *       开工时的闸仍然生效;</li>
     *   <li>{@code Optional.of(true)} — 有工序接这份半成品;</li>
     *   <li>{@code Optional.of(false)} — 有已启用 Workflow 但没有工序接这份半成品。</li>
     * </ul>
     * 已启用 Workflow 的节点 JSON 解析失败 ⇒ 409 WORKFLOW_PROCESS_PATH_INVALID, 不当作「接不住」也不放行。
     */
    Optional<Boolean> activeWorkflowAcceptsInput(String factoryId, String targetProductTypeId, String inputSkuId);

    /**
     * 写路径守卫: 断言 ownerProductTypeId 当前 enabled activation 指向的 workflow 终端覆盖 targets,
     * 否则抛 409 WORKFLOW_RESOLUTION_NOT_COVERED。用于建计划/转批次防绕过 + 防 activation 窗口期漂移。
     */
    void assertActiveWorkflowCoversOutputs(String factoryId, String ownerProductTypeId,
                                           List<String> targetFinishedGoodIds);

    /** Validate the exact workflow/version pinned by an existing production plan. */
    void assertPinnedWorkflowCoversOutputs(String factoryId, Long workflowId, Integer definitionVersion,
                                           List<String> targetFinishedGoodIds);

    /** Resolve and pin the active workflow's terminal reporting-unit contract for a plan. */
    Optional<WorkflowPlanOutputContract> resolveActivePlanOutputContract(
            String factoryId, String ownerProductTypeId, List<String> targetFinishedGoodIds);

    /**
     * Revalidate the exact workflow/version explicitly selected in the plan UI and return its
     * reporting-unit contract. The selection must still be the enabled activation for the owner.
     */
    WorkflowPlanOutputContract resolvePinnedPlanOutputContract(
            String factoryId, String ownerProductTypeId, Long workflowId, Integer definitionVersion,
            List<String> targetFinishedGoodIds);

    /**
     * Read-only route preview of the exact workflow row/version a production plan pinned, whether or not
     * it is still the enabled activation. Replaced versions stay readable (SNAPSHOT or PUBLISHED); a DRAFT,
     * a different factory, a version mismatch or a broken graph yields an empty result, never another version.
     */
    Optional<WorkflowOutputResolutionDTO.Candidate> findPinnedCandidate(
            String factoryId, Long workflowId, Integer definitionVersion);
}
