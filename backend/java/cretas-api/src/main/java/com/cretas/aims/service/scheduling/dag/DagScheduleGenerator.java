package com.cretas.aims.service.scheduling.dag;

import com.cretas.aims.dto.scheduling.AutoScheduleItem;
import com.cretas.aims.dto.scheduling.GenerateScheduleRequest;
import com.cretas.aims.dto.scheduling.SchedulingPlanDTO;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionLine;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.ProductionBatchStatus;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionLineRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.scheduling.core.SchedulingPlanCrudService;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.JobIn;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.JobOut;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.Status;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.TaskIn;
import com.cretas.aims.service.scheduling.dag.DagScheduleEngine.TaskOut;
import com.cretas.aims.service.workflow.CompiledProductProcessWorkflow;
import com.cretas.aims.service.workflow.ProductProcessWorkflowRuntimeService;
import com.cretas.aims.service.workprocess.WorkflowStepUnlockGuard;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedSet;
import java.util.stream.Collectors;

/**
 * APS v1: 选几个生产计划 → 按工艺图先后关系 + 每道工序的预计工时排出每道工序的开始 / 结束时间,
 * 落成一份调度计划 (每个完整排出的生产计划一条 {@code LineSchedule})。
 *
 * <p>口径 (Steve 2026-10-28 选定, 不要改):
 * <ul>
 *   <li>任务图: 计划已开工 (批次上有工序任务) 用实际任务; 还没开工用计划钉住的已发布工艺版本编译出的图。
 *       没有工艺图的计划列为「没有工艺图, 无法排」。</li>
 *   <li>时长 = 任务 / 编译任务上的预计分钟, 为空回落工序定义的预计分钟; 都没有 = 缺工时。
 *       ⛔ 缺工时不拿默认值猜: 该计划整体不落、不占工位, 在 warnings 里列出工序名。</li>
 *   <li>资源只看产线; 产线 hourly_capacity 不参与排时间 (单位不明)。⛔ 不自动派工。</li>
 *   <li>已完工 / 跳过 / 取消的工序视为已完成, 不占时间。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DagScheduleGenerator {

    static final LocalTime DEFAULT_START = LocalTime.of(8, 0);
    static final String DEFAULT_START_NOTE = "没有指定开工时刻，按 08:00 开工排";

    private final ProductionPlanRepository productionPlanRepository;
    private final ProductionBatchRepository batchRepository;
    private final ProductionLineRepository lineRepository;
    private final WorkProcessTaskRepository taskRepository;
    private final WorkProcessRepository workProcessRepository;
    private final WorkflowStepUnlockGuard unlockGuard;
    private final ProductProcessWorkflowRuntimeService workflowRuntimeService;
    private final SchedulingPlanCrudService crudService;

    /** 一个生产计划组装出来的作业, 外加落库要用的东西。 */
    private record PlanJob(ProductionPlan plan, JobIn job, Long firstBatchId,
                           Map<Long, WorkProcessTask> realTasks, Map<Long, String> workProcessIdByTask) {
    }

    @Transactional
    public SchedulingPlanDTO generate(String factoryId, GenerateScheduleRequest request, Long userId) {
        if (request == null || request.getPlanDate() == null) {
            throw new BusinessException(400, "计划日期不能为空");
        }
        List<ProductionPlan> plans = resolvePlans(factoryId, request);
        ProductionLine line = resolveLine(factoryId, request.getProductionLineId());

        List<String> warnings = new ArrayList<>();
        LocalDateTime startAt;
        boolean defaultStart = request.getStartTime() == null || request.getStartTime().isBlank();
        if (defaultStart) {
            startAt = request.getPlanDate().atTime(DEFAULT_START);
            warnings.add(DEFAULT_START_NOTE);
        } else {
            try {
                startAt = request.getPlanDate().atTime(LocalTime.parse(request.getStartTime().trim()));
            } catch (DateTimeParseException e) {
                throw new BusinessException(400, "开工时刻格式应为 HH:mm, 例如 08:00");
            }
        }

        long[] syntheticIds = {0L};
        List<PlanJob> jobs = new ArrayList<>();
        for (ProductionPlan plan : plans) {
            PlanJob job = buildJob(factoryId, plan, warnings, syntheticIds);
            if (job != null) {
                jobs.add(job);
            }
        }

        List<JobOut> scheduled = DagScheduleEngine.schedule(jobs.stream().map(PlanJob::job).toList(), startAt);

        List<AutoScheduleItem> items = new ArrayList<>();
        List<WorkProcessTask> taskUpdates = new ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            PlanJob pj = jobs.get(i);
            JobOut out = scheduled.get(i);
            String label = planLabel(pj.plan());
            if (!out.complete()) {
                warnings.add(missingDurationWarning(label, out));
                continue;
            }
            if (out.start() == null || out.end() == null) {
                warnings.add("计划「" + label + "」的工序都已完成，不需要排");
                continue;
            }
            Integer quantity = roundedQuantity(pj.plan().getPlannedQuantity());
            if (quantity == null) {
                warnings.add("计划「" + label + "」没有计划数量，没有排");
                continue;
            }
            List<Map<String, Object>> timeline = new ArrayList<>();
            for (TaskOut t : out.tasks()) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("taskName", t.name());
                entry.put("processOrder", t.processOrder());
                entry.put("workProcessId", pj.workProcessIdByTask().get(t.taskId()));
                entry.put("start", t.start() == null ? null : t.start().toString());
                entry.put("end", t.end() == null ? null : t.end().toString());
                entry.put("status", t.status().name());
                timeline.add(entry);
                WorkProcessTask real = pj.realTasks().get(t.taskId());
                if (real != null && t.status() == Status.SCHEDULED) {
                    real.setPlannedStartAt(t.start());
                    real.setPlannedEndAt(t.end());
                    taskUpdates.add(real);
                }
            }
            items.add(new AutoScheduleItem(line.getId(), pj.plan().getId(), pj.firstBatchId(), items.size(),
                    out.start(), out.end(), quantity, timeline));
        }

        if (items.isEmpty()) {
            throw new BusinessException(422, "没有能排出的计划：" + String.join("；", warnings))
                    .withCode("APS_NOTHING_SCHEDULABLE")
                    .withHint("缺工时的工序请到「工序管理」填预计分钟；没有工艺图的计划需要先按工艺图建计划")
                    .withSeverity("warning");
        }

        String notes = "按工艺图和预计工时自动排程（不自动派工）";
        if (defaultStart) {
            notes = notes + "；" + DEFAULT_START_NOTE;
        }
        SchedulingPlanDTO created = crudService.createAutoSchedulePlan(factoryId, request.getPlanDate(),
                request.getPlanDate() + " 自动排程", notes, items, userId);
        if (!taskUpdates.isEmpty()) {
            taskRepository.saveAll(taskUpdates);
        }
        SchedulingPlanDTO result = crudService.getPlan(factoryId, created.getId());
        result.setWarnings(warnings);
        log.info("APS 自动排程: factoryId={}, schedulingPlanId={}, 排出 {} 个计划, 提示 {} 条",
                factoryId, result.getId(), items.size(), warnings.size());
        return result;
    }

    // ==================== 入参解析 ====================

    private List<ProductionPlan> resolvePlans(String factoryId, GenerateScheduleRequest request) {
        Set<String> ids = new LinkedHashSet<>();
        if (request.getPlanIds() != null) {
            for (String id : request.getPlanIds()) {
                if (id != null && !id.isBlank()) {
                    ids.add(id.trim());
                }
            }
        }
        if (request.getBatchIds() != null) {
            for (Long batchId : request.getBatchIds()) {
                if (batchId == null) {
                    continue;
                }
                ProductionBatch batch = batchRepository.findByIdAndFactoryId(batchId, factoryId)
                        .orElseThrow(() -> new BusinessException(404, "批次不存在或不属于当前工厂: " + batchId));
                if (batch.getProductionPlanId() == null) {
                    throw new BusinessException(400, "批次 " + batch.getBatchNumber() + " 没有关联生产计划，不能自动排程");
                }
                ids.add(batch.getProductionPlanId());
            }
        }
        if (ids.isEmpty()) {
            throw new BusinessException(400, "请至少选择一个生产计划");
        }
        List<ProductionPlan> plans = new ArrayList<>();
        for (String id : ids) {
            plans.add(productionPlanRepository.findByIdAndFactoryId(id, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "生产计划不存在或不属于当前工厂: " + id)));
        }
        return plans;
    }

    private ProductionLine resolveLine(String factoryId, String productionLineId) {
        if (productionLineId != null && !productionLineId.isBlank()) {
            ProductionLine line = lineRepository.findByIdAndFactoryIdAndDeletedAtIsNull(productionLineId, factoryId)
                    .orElseThrow(() -> new BusinessException(404, "产线不存在或不属于当前工厂: " + productionLineId));
            if (line.getStatus() != ProductionLine.LineStatus.active) {
                throw new BusinessException(400, "产线「" + line.getName() + "」当前不可排程，请换一条产线");
            }
            return line;
        }
        List<ProductionLine> active = lineRepository.findByFactoryIdAndStatusAndDeletedAtIsNull(
                factoryId, ProductionLine.LineStatus.active);
        if (active.size() == 1) {
            return active.get(0);
        }
        if (active.isEmpty()) {
            throw new BusinessException(409, "工厂还没有可用的产线，不能自动排程")
                    .withCode("APS_NO_PRODUCTION_LINE")
                    .withHint("先到「产线管理」新建一条产线");
        }
        throw new BusinessException(400, "工厂有 " + active.size() + " 条产线，请选择要排在哪条产线上")
                .withCode("APS_PRODUCTION_LINE_REQUIRED")
                .withSeverity("warning");
    }

    // ==================== 任务图 ====================

    private PlanJob buildJob(String factoryId, ProductionPlan plan, List<String> warnings, long[] syntheticIds) {
        String label = planLabel(plan);
        if (plan.getStatus() == ProductionPlanStatus.COMPLETED || plan.getStatus() == ProductionPlanStatus.CANCELLED) {
            warnings.add("计划「" + label + "」" + plan.getStatus().getDisplayName() + "，不排");
            return null;
        }
        List<ProductionBatch> openBatches = batchRepository
                .findByFactoryIdAndProductionPlanId(factoryId, plan.getId()).stream()
                .filter(b -> b.getStatus() != ProductionBatchStatus.COMPLETED
                        && b.getStatus() != ProductionBatchStatus.CANCELLED)
                .sorted(Comparator.comparing(ProductionBatch::getId))
                .toList();
        Map<Long, List<WorkProcessTask>> tasksByBatch = new LinkedHashMap<>();
        for (ProductionBatch batch : openBatches) {
            List<WorkProcessTask> tasks = taskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, batch.getId());
            if (!tasks.isEmpty()) {
                tasksByBatch.put(batch.getId(), tasks);
            }
        }
        if (!tasksByBatch.isEmpty()) {
            return startedJob(factoryId, plan, tasksByBatch);
        }
        return compiledJob(factoryId, plan, warnings, syntheticIds);
    }

    /** 已开工: 用批次上的实际工序任务。 */
    private PlanJob startedJob(String factoryId, ProductionPlan plan, Map<Long, List<WorkProcessTask>> tasksByBatch) {
        Set<String> processIds = new HashSet<>();
        tasksByBatch.values().forEach(ts -> ts.forEach(t -> processIds.add(t.getWorkProcessId())));
        Map<String, WorkProcess> processes = loadProcesses(factoryId, processIds);

        List<TaskIn> tasks = new ArrayList<>();
        Map<Long, WorkProcessTask> realTasks = new HashMap<>();
        Map<Long, String> wpByTask = new HashMap<>();
        for (List<WorkProcessTask> batchTasks : tasksByBatch.values()) {
            // 同一工艺实例里 processOrder -> 任务 id (上游判定返回的是 processOrder)
            Map<Long, Map<Integer, List<Long>>> idsByInstanceOrder = new HashMap<>();
            for (WorkProcessTask t : batchTasks) {
                if (t.getWorkflowInstanceId() != null && t.getProcessOrder() != null) {
                    idsByInstanceOrder.computeIfAbsent(t.getWorkflowInstanceId(), k -> new HashMap<>())
                            .computeIfAbsent(t.getProcessOrder(), k -> new ArrayList<>()).add(t.getId());
                }
            }
            for (WorkProcessTask t : batchTasks) {
                Set<Long> preds = new HashSet<>();
                if (t.getWorkflowInstanceId() != null) {
                    SortedSet<Integer> orders = unlockGuard.upstreamProcessOrders(factoryId, t);
                    Map<Integer, List<Long>> byOrder = idsByInstanceOrder.getOrDefault(t.getWorkflowInstanceId(), Map.of());
                    orders.forEach(o -> preds.addAll(byOrder.getOrDefault(o, List.of())));
                } else {
                    legacyPrevious(t, batchTasks).ifPresent(preds::add);
                }
                WorkProcess wp = processes.get(t.getWorkProcessId());
                Integer minutes = t.getEstimatedMinutes() != null ? t.getEstimatedMinutes()
                        : wp != null ? wp.getEstimatedMinutes() : null;
                int order = t.getProcessOrder() == null ? 0 : t.getProcessOrder();
                boolean finished = t.getStatus() != null && t.getStatus().isTerminal();
                tasks.add(new TaskIn(t.getId(), t.getWorkProcessId(), processName(wp, order), order, minutes,
                        preds, finished));
                realTasks.put(t.getId(), t);
                wpByTask.put(t.getId(), t.getWorkProcessId());
            }
        }
        Long firstBatchId = tasksByBatch.keySet().iterator().next();
        return new PlanJob(plan, new JobIn(plan.getId(), tasks), firstBatchId, realTasks, wpByTask);
    }

    /** 无工艺实例的旧式任务: 上游 = 同批次 processOrder 紧挨着的前一道 (与解锁判定同一口径)。 */
    private static java.util.Optional<Long> legacyPrevious(WorkProcessTask task, List<WorkProcessTask> siblings) {
        if (task.getProcessOrder() == null) {
            return java.util.Optional.empty();
        }
        WorkProcessTask previous = null;
        for (WorkProcessTask s : siblings) {
            if (Objects.equals(s.getId(), task.getId()) || s.getProcessOrder() == null
                    || s.getProcessOrder() >= task.getProcessOrder()) {
                continue;
            }
            if (previous == null || s.getProcessOrder() > previous.getProcessOrder()) {
                previous = s;
            }
        }
        return previous == null ? java.util.Optional.empty() : java.util.Optional.of(previous.getId());
    }

    /** 还没开工: 用计划钉住的已发布工艺版本编译出的图。 */
    private PlanJob compiledJob(String factoryId, ProductionPlan plan, List<String> warnings, long[] syntheticIds) {
        String label = planLabel(plan);
        if (plan.getWorkflowSelectionMode() != ProductionBatch.WorkflowSelectionMode.WORKFLOW
                || plan.getSelectedWorkflowRevisionId() == null) {
            warnings.add("计划「" + label + "」没有工艺图，无法排（按工艺图建的计划才能自动排程）");
            return null;
        }
        CompiledProductProcessWorkflow compiled;
        try {
            compiled = workflowRuntimeService.compilePublishedRevision(factoryId, plan.getSelectedWorkflowRevisionId());
        } catch (BusinessException e) {
            warnings.add("计划「" + label + "」钉住的工艺版本不可用（已删除、未发布或单位换算已过期），没有排"
                    + (e.getErrorCode() != null ? "（" + e.getErrorCode() + "）" : ""));
            return null;
        }
        List<CompiledProductProcessWorkflow.CompiledTask> compiledTasks = compiled.allProcessTasks();
        if (compiledTasks.isEmpty()) {
            warnings.add("计划「" + label + "」的工艺图里没有工序，无法排");
            return null;
        }
        Set<String> processIds = compiledTasks.stream()
                .map(CompiledProductProcessWorkflow.CompiledTask::workProcessId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, WorkProcess> processes = loadProcesses(factoryId, processIds);

        Map<String, Long> idByNode = new HashMap<>();
        for (CompiledProductProcessWorkflow.CompiledTask ct : compiledTasks) {
            idByNode.put(ct.workflowNodeId(), --syntheticIds[0]); // 负数: 与真实任务 id 不会撞
        }
        // 每道工序的产出物料节点
        Map<String, Set<String>> outputsByNode = new HashMap<>();
        Map<String, Set<String>> inputsByNode = new HashMap<>();
        for (CompiledProductProcessWorkflow.CompiledTask ct : compiledTasks) {
            for (CompiledProductProcessWorkflow.CompiledPort p : compiled.portsFor(ct.workflowNodeId())) {
                if (p.materialNodeId() == null) {
                    continue;
                }
                if ("OUTPUT".equals(p.direction())) {
                    outputsByNode.computeIfAbsent(ct.workflowNodeId(), k -> new HashSet<>()).add(p.materialNodeId());
                } else if ("INPUT".equals(p.direction())) {
                    inputsByNode.computeIfAbsent(ct.workflowNodeId(), k -> new HashSet<>()).add(p.materialNodeId());
                }
            }
        }
        List<TaskIn> tasks = new ArrayList<>();
        Map<Long, String> wpByTask = new HashMap<>();
        for (CompiledProductProcessWorkflow.CompiledTask ct : compiledTasks) {
            Set<String> inputs = inputsByNode.getOrDefault(ct.workflowNodeId(), Set.of());
            Set<Long> preds = new HashSet<>();
            // 上游 = 产出口物料节点 ∈ 本道投入口物料节点, 且 processOrder 更小 (与解锁判定同一口径)
            for (CompiledProductProcessWorkflow.CompiledTask other : compiledTasks) {
                if (other == ct || other.processOrder() >= ct.processOrder()) {
                    continue;
                }
                Set<String> outs = outputsByNode.getOrDefault(other.workflowNodeId(), Set.of());
                if (outs.stream().anyMatch(inputs::contains)) {
                    preds.add(idByNode.get(other.workflowNodeId()));
                }
            }
            WorkProcess wp = processes.get(ct.workProcessId());
            Integer minutes = ct.estimatedMinutes() != null ? ct.estimatedMinutes()
                    : wp != null ? wp.getEstimatedMinutes() : null;
            Long id = idByNode.get(ct.workflowNodeId());
            tasks.add(new TaskIn(id, ct.workProcessId(), processName(wp, ct.processOrder()), ct.processOrder(),
                    minutes, preds, false));
            wpByTask.put(id, ct.workProcessId());
        }
        return new PlanJob(plan, new JobIn(plan.getId(), tasks), null, Map.of(), wpByTask);
    }

    // ==================== 小工具 ====================

    private Map<String, WorkProcess> loadProcesses(String factoryId, Set<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return workProcessRepository.findByFactoryIdAndIdIn(factoryId, new ArrayList<>(ids)).stream()
                .collect(Collectors.toMap(WorkProcess::getId, wp -> wp, (a, b) -> a));
    }

    private static String processName(WorkProcess wp, int order) {
        return wp != null && wp.getProcessName() != null ? wp.getProcessName() : "第" + order + "道";
    }

    private static String planLabel(ProductionPlan plan) {
        return plan.getPlanNumber() != null ? plan.getPlanNumber() : plan.getId();
    }

    private static String missingDurationWarning(String label, JobOut out) {
        List<String> names = out.missingDurationNames();
        return "计划「" + label + "」有工序缺预计工时：「" + String.join("」「", names)
                + "」，这个计划没有排，也不占工位。请到「工序管理」给这些工序填预计分钟后再排";
    }

    /** 计划量取整 (四舍五入), 计划量为正但不足 0.5 时记 1; 没有计划量 → null (不排)。 */
    static Integer roundedQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            return null;
        }
        int rounded = quantity.setScale(0, RoundingMode.HALF_UP).intValue();
        return Math.max(rounded, 1);
    }
}
