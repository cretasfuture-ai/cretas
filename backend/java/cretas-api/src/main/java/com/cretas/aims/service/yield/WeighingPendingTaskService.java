package com.cretas.aims.service.yield;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 待称重任务总览: 执行中且还没有任何称重的工序任务, 今日计划的排前面。只读。
 */
@Service
@RequiredArgsConstructor
public class WeighingPendingTaskService {

    private final WorkProcessTaskRepository taskRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionPlanRepository planRepository;
    private final WorkProcessRepository processRepository;

    public record PendingTask(Long taskId, Long batchId, String batchNumber, String planId, String planNumber,
                              LocalDate plannedDate, boolean plannedToday, Integer processOrder,
                              String workProcessId, String processName, String productTypeId,
                              BigDecimal plannedQuantity, String plannedUnit) {
    }

    @Transactional(readOnly = true)
    public List<PendingTask> list(String factoryId, LocalDate today) {
        List<WorkProcessTask> tasks = taskRepository.findInProgressWithoutWeighing(factoryId);
        Map<Long, ProductionBatch> batches = new HashMap<>();
        Map<String, ProductionPlan> plans = new HashMap<>();
        Map<String, WorkProcess> processes = new HashMap<>();
        for (WorkProcessTask t : tasks) {
            if (t.getProductionBatchId() != null && !batches.containsKey(t.getProductionBatchId())) {
                ProductionBatch b = batchRepository.findByIdAndFactoryId(t.getProductionBatchId(), factoryId).orElse(null);
                batches.put(t.getProductionBatchId(), b);
                if (b != null && b.getProductionPlanId() != null && !plans.containsKey(b.getProductionPlanId())) {
                    plans.put(b.getProductionPlanId(),
                            planRepository.findByIdAndFactoryId(b.getProductionPlanId(), factoryId).orElse(null));
                }
            }
            if (t.getWorkProcessId() != null && !processes.containsKey(t.getWorkProcessId())) {
                processes.put(t.getWorkProcessId(), processRepository.findById(t.getWorkProcessId()).orElse(null));
            }
        }
        return tasks.stream().map(t -> {
            ProductionBatch b = batches.get(t.getProductionBatchId());
            ProductionPlan p = b == null || b.getProductionPlanId() == null ? null : plans.get(b.getProductionPlanId());
            WorkProcess wp = processes.get(t.getWorkProcessId());
            LocalDate planned = p == null ? null : p.getPlannedDate();
            return new PendingTask(t.getId(), t.getProductionBatchId(), b == null ? null : b.getBatchNumber(),
                    p == null ? null : p.getId(), p == null ? null : p.getPlanNumber(),
                    planned, planned != null && planned.equals(today), t.getProcessOrder(),
                    t.getWorkProcessId(), wp == null ? null : wp.getProcessName(), t.getProductTypeId(),
                    t.getPlannedQuantity(), t.getPlannedUnit());
        }).sorted(Comparator
                .comparing((PendingTask x) -> !x.plannedToday())
                .thenComparing(PendingTask::plannedDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PendingTask::batchId, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PendingTask::processOrder, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();
    }
}
