package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * 计划作废时级联作废它的批次与工序任务 —— 唯一一份 (⛔ 不要在调用方另写)。
 *
 * <p>调用方: {@code ProductionPlanServiceImpl} 取消计划 / 撤回审批通过;
 * {@code UrgentInsertServiceImpl} 驳回强制插单 (1551-11 红线 1 独立审查 B1: 驳回原先只把计划置 CANCELLED,
 * 挂在上面的手工批次还留在 PLANNED, 还能开工)。
 */
@Slf4j
public final class PlanBatchCascade {

    private PlanBatchCascade() {}

    /**
     * R1 (2026-06-14): 按批次定向级联取消 — 把传入的每个未取消批次置 CANCELLED, 并 CANCELLED 其活跃 WorkProcessTask。
     *
     * <p>严格按 batchId 查任务 ({@code findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc}),
     * 不碰其它批次的任务 (即便同一 productTypeId)。终态任务 (COMPLETED/SKIPPED/CANCELLED) 不变。
     *
     * @param taskRepository null = 单测无注入, 只取消批次
     */
    public static void cancelBatchesAndTasks(String factoryId, String planId, List<ProductionBatch> planBatches,
                                             ProductionBatchRepository batchRepository,
                                             WorkProcessTaskRepository taskRepository) {
        if (planBatches == null || planBatches.isEmpty()) {
            return;
        }
        for (ProductionBatch batch : planBatches) {
            if (batch.getId() == null) {
                continue;
            }
            // 空批次 / 活跃批次 → CANCELLED (已 CANCELLED/COMPLETED 不动)
            if (batch.getStatus() != ProductionBatchStatus.CANCELLED
                    && batch.getStatus() != ProductionBatchStatus.COMPLETED) {
                batch.setStatus(ProductionBatchStatus.CANCELLED);
                batchRepository.save(batch);
            }
            if (taskRepository == null) {
                continue; // 单测无注入 → 跳过 (有注入的测试覆盖级联)
            }
            List<WorkProcessTask> tasks = taskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batch.getId());
            int closedCount = 0;
            for (WorkProcessTask task : tasks) {
                if (!task.getStatus().isTerminal()) {
                    task.setStatus(WorkProcessTask.Status.CANCELLED);
                    closedCount++;
                }
            }
            if (closedCount > 0) {
                taskRepository.saveAll(tasks);
                log.info("R1 级联取消 {} 个工序任务: planId={}, batchId={}", closedCount, planId, batch.getId());
            }
        }
    }
}
