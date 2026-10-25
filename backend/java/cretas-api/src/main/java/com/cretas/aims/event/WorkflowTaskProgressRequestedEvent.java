package com.cretas.aims.event;

import java.math.BigDecimal;

/**
 * Exact Workflow task progress projection requested by a committed process-sheet change.
 *
 * <p>G08 (2026-10-24): 这条事件<b>只是「请重算这道工序的进度」的信号</b>。任务实际产量由
 * {@code WorkflowTaskProgressWriter} 在提交后从该工序节点<b>全部有效</b> process_sheet_rows 聚合得出 ——
 * ⛔ 不再拿 {@link #actualQuantity} 覆盖 (同一工序第二行会把第一行的 48 覆盖成 32)。
 *
 * @param actualQuantity       触发这次重算的那一行的产出量, 只用于日志/排查; ⛔ 不是任务产量
 * @param primaryOutputPortId  该工序的主产出端口 (ProcessDescriptor.output, 第一个非副产产出端口);
 *                             多产出行只把这个端口的产出计进任务产量。{@code null} = 未知,
 *                             此时只计单产出行, 多产出成员行一律不计 (不混单位)
 * @param markCompleted        {@code true} = 有产出的保存/提交 (沿用旧语义, 置 COMPLETED);
 *                             {@code false} = 删除/撤回成草稿后的重算, 只改产量不动状态
 */
public record WorkflowTaskProgressRequestedEvent(
        String factoryId,
        String planId,
        Long workflowInstanceId,
        String workflowNodeId,
        Integer processOrder,
        BigDecimal actualQuantity,
        String primaryOutputPortId,
        boolean markCompleted) {

    /** 旧签名 (保存/提交触发): 主产出端口未知, 置 COMPLETED。 */
    public WorkflowTaskProgressRequestedEvent(
            String factoryId,
            String planId,
            Long workflowInstanceId,
            String workflowNodeId,
            Integer processOrder,
            BigDecimal actualQuantity) {
        this(factoryId, planId, workflowInstanceId, workflowNodeId, processOrder, actualQuantity, null, true);
    }
}
