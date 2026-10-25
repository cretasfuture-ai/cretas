package com.cretas.aims.service.workprocess;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 按工艺图解锁工序 (缺口 04, Steve 2026-10-25 裁定「上游料够了就解锁」)。
 *
 * <h3>规则</h3>
 * 一道工序的每个必需投入, 如果它是图里<b>另一道更早的工序产出的</b> (该道 OUTPUT 端口与本道 INPUT 端口
 * 指向同一个 {@code materialNodeId}), 那么至少要有一个这样的上游已经<b>产出了这种料</b>, 本道才解锁
 * (Steve 2026-10-25 追加: 「上游有没有出本道要的那种料, 没有就锁住」):
 * <ul>
 *   <li>上游只有一个产出口: {@code actualQuantity > 0} 就算出了 (web 逐道录入与 App 报工都会回写它)。</li>
 *   <li>上游一出多 (如分割出鸡胸 / 鸡腿 / 鸡翅): 按<b>这一种料</b>找证据 —— web 正式录入的行
 *       (产出端口 / 物料节点 / SKU 对得上且数量 &gt; 0), 或 App 报工 (SKU 对得上且产出 &gt; 0)。
 *       分割只出了鸡胸鸡翅时, 吃鸡腿的滚揉照样锁住。</li>
 *   <li>上游跳过 / 取消: 不会再产出, 料只能从别处来 ⇒ 不锁 (否则下游永远锁死)。
 *       上游<b>完工了却没出这种料</b>: 锁住 (没料就是没料)。</li>
 *   <li>⛔ 不要求上游「全部完工」: 分批产出时, 上游先出的那部分就能让下游开工。
 *       够不够这一次投的量, 由各条路径已有的按来源余额校验负责 (WIP_INSUFFICIENT / SFI_INSUFFICIENT)。</li>
 *   <li>只吃原料、没有上游工序产出的投入不设闸 ⇒ 并行分支各报各的。</li>
 *   <li>必需与否看<b>选择组模式</b>: AT_LEAST_ONE / EXACTLY_ONE 有一个成员可用就满足 (编译器给没声明
 *       分组的工序自动套的「实际投入」组就是 AT_LEAST_ONE); ALL_REQUIRED 每个成员都要可用;
 *       OPTIONAL 或最少选 0 个不设闸。端口自己的 required 只对无组端口 (旧快照) 有意义。</li>
 *   <li>只认 {@code processOrder} 比本道小的生产者: 回投 / 回料边不能让上下游互相等死。</li>
 * </ul>
 * 没有工艺图快照的旧式任务 (无 workflowInstanceId): 上游 = 同批次 processOrder 紧挨着的前一道。
 *
 * <p>同一份判定既给列表页算「能不能报」, 也在写入口 (开工 / App 报工 / web 正式录入) 拒绝 —— 两处不会漂。
 */
@Component
@RequiredArgsConstructor
public class WorkflowStepUnlockGuard {

    public static final String ERROR_CODE = "WORKFLOW_UPSTREAM_NOT_READY";

    private final WorkProcessTaskRepository taskRepository;
    private final WorkflowTaskPortRepository portRepository;
    private final WorkProcessRepository workProcessRepository;
    private final ProductionBatchRepository productionBatchRepository;
    private final ProcessSheetRowRepository processSheetRowRepository;
    private final ProductionReportRepository productionReportRepository;
    private final ObjectMapper objectMapper;

    /** 判定结果。{@code waitingFor} 是还没有产出的上游工序名 (去重、按工序顺序)。 */
    public record Readiness(boolean unlocked, List<String> waitingFor) {
        static final Readiness OPEN = new Readiness(true, List.of());

        public String blockedReason() {
            if (unlocked) {
                return null;
            }
            return "上游工序「" + String.join("」「", waitingFor) + "」还没有产出本道要用的料，暂时不能报工";
        }
    }

    /** 写入口用: 未解锁直接 409。 */
    public void assertUnlocked(String factoryId, WorkProcessTask task) {
        Readiness r = evaluate(factoryId, task);
        if (!r.unlocked()) {
            throw new BusinessException(409, r.blockedReason())
                    .withCode(ERROR_CODE)
                    .withHint("先让「" + String.join("」「", r.waitingFor())
                            + "」报一次产出（分批产出时报出第一批即可），再来报这一道；"
                            + "如果这次不做那一道（例如半成品直接从库里领），先把它标记为跳过");
        }
    }

    public Readiness evaluate(String factoryId, WorkProcessTask task) {
        return evaluateAll(factoryId, List.of(task)).getOrDefault(task.getId(), Readiness.OPEN);
    }

    /** 列表用: 一次算完一批任务, 按工艺实例 / 批次分组查库, 不逐条查。 */
    public Map<Long, Readiness> evaluateAll(String factoryId, Collection<WorkProcessTask> tasks) {
        Map<Long, Readiness> out = new HashMap<>();
        if (tasks == null || tasks.isEmpty()) {
            return out;
        }
        Map<Long, List<WorkProcessTask>> byInstance = new LinkedHashMap<>();
        Map<Long, List<WorkProcessTask>> legacyByBatch = new LinkedHashMap<>();
        for (WorkProcessTask t : tasks) {
            if (t == null || t.getId() == null) {
                continue;
            }
            if (t.getWorkflowInstanceId() != null) {
                byInstance.computeIfAbsent(t.getWorkflowInstanceId(), k -> new ArrayList<>()).add(t);
            } else if (t.getProductionBatchId() != null) {
                legacyByBatch.computeIfAbsent(t.getProductionBatchId(), k -> new ArrayList<>()).add(t);
            }
        }

        Map<Long, List<WorkProcessTask>> waiting = new HashMap<>();
        Set<String> processIds = new LinkedHashSet<>();
        Evidence evidence = new Evidence(factoryId);

        for (Map.Entry<Long, List<WorkProcessTask>> e : byInstance.entrySet()) {
            List<WorkProcessTask> siblings = taskRepository
                    .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(factoryId, e.getKey());
            List<WorkflowTaskPort> ports = portRepository
                    .findByFactoryIdAndWorkflowInstanceId(factoryId, e.getKey());
            for (WorkProcessTask t : e.getValue()) {
                List<WorkProcessTask> blockers = workflowBlockers(t, siblings, ports, evidence);
                waiting.put(t.getId(), blockers);
                blockers.forEach(b -> processIds.add(b.getWorkProcessId()));
            }
        }
        for (Map.Entry<Long, List<WorkProcessTask>> e : legacyByBatch.entrySet()) {
            List<WorkProcessTask> siblings = taskRepository
                    .findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(factoryId, e.getKey());
            for (WorkProcessTask t : e.getValue()) {
                List<WorkProcessTask> blockers = legacyBlockers(t, siblings);
                waiting.put(t.getId(), blockers);
                blockers.forEach(b -> processIds.add(b.getWorkProcessId()));
            }
        }

        Map<String, String> names = processIds.isEmpty() ? Map.of()
                : workProcessRepository.findByFactoryIdAndIdIn(factoryId, new ArrayList<>(processIds)).stream()
                        .collect(Collectors.toMap(WorkProcess::getId,
                                wp -> wp.getProcessName() == null ? wp.getId() : wp.getProcessName(),
                                (a, b) -> a));

        for (Map.Entry<Long, List<WorkProcessTask>> e : waiting.entrySet()) {
            if (e.getValue().isEmpty()) {
                out.put(e.getKey(), Readiness.OPEN);
            } else {
                List<String> waitingFor = e.getValue().stream()
                        .map(b -> names.getOrDefault(b.getWorkProcessId(), "第" + b.getProcessOrder() + "道"))
                        .distinct()
                        .collect(Collectors.toList());
                out.put(e.getKey(), new Readiness(false, waitingFor));
            }
        }
        return out;
    }

    /**
     * 按工艺图, 本道半成品投入是哪几道更早工序产出的 (它们的 processOrder)。
     * App 报工算「可领源 WIP」时用它代替「前一道」—— 网状工艺里并行分支的上游不是前一道。
     * 无工艺图 (旧式任务) 或本道只吃原料 → 空集, 调用方回落「前一道」。
     */
    public java.util.SortedSet<Integer> upstreamProcessOrders(String factoryId, WorkProcessTask task) {
        java.util.SortedSet<Integer> orders = new java.util.TreeSet<>();
        if (task == null || task.getWorkflowInstanceId() == null || task.getProcessOrder() == null) {
            return orders;
        }
        List<WorkProcessTask> siblings = taskRepository
                .findByFactoryIdAndWorkflowInstanceIdOrderByProcessOrderAsc(factoryId, task.getWorkflowInstanceId());
        Map<Long, WorkProcessTask> byId = siblings.stream()
                .collect(Collectors.toMap(WorkProcessTask::getId, t -> t, (a, b) -> a));
        List<WorkflowTaskPort> ports = portRepository
                .findByFactoryIdAndWorkflowInstanceId(factoryId, task.getWorkflowInstanceId());
        Set<String> inputs = ports.stream()
                .filter(p -> Objects.equals(p.getTaskId(), task.getId())
                        && p.getDirection() == WorkflowTaskPort.Direction.INPUT && p.getMaterialNodeId() != null)
                .map(WorkflowTaskPort::getMaterialNodeId)
                .collect(Collectors.toSet());
        for (WorkflowTaskPort p : ports) {
            if (p.getDirection() != WorkflowTaskPort.Direction.OUTPUT || !inputs.contains(p.getMaterialNodeId())) {
                continue;
            }
            WorkProcessTask producer = byId.get(p.getTaskId());
            if (producer != null && producer.getProcessOrder() != null
                    && producer.getProcessOrder() < task.getProcessOrder()) {
                orders.add(producer.getProcessOrder());
            }
        }
        return orders;
    }

    /** 上游「有产出」: 报过正数产出, 或已到终态。 */
    static boolean hasProduced(WorkProcessTask t) {
        if (t.getStatus() != null && t.getStatus().isTerminal()) {
            return true;
        }
        BigDecimal q = t.getActualQuantity();
        return q != null && q.signum() > 0;
    }

    private List<WorkProcessTask> workflowBlockers(
            WorkProcessTask task, List<WorkProcessTask> siblings, List<WorkflowTaskPort> ports, Evidence evidence) {
        Map<Long, WorkProcessTask> taskById = siblings.stream()
                .collect(Collectors.toMap(WorkProcessTask::getId, t -> t, (a, b) -> a));
        int myOrder = task.getProcessOrder() == null ? Integer.MAX_VALUE : task.getProcessOrder();

        // materialNodeId -> 产出它的更早工序的那个产出口; 以及每道工序有几个产出口
        Map<String, List<WorkflowTaskPort>> producers = new HashMap<>();
        Map<Long, Long> outputCount = ports.stream()
                .filter(p -> p.getDirection() == WorkflowTaskPort.Direction.OUTPUT && p.getTaskId() != null)
                .collect(Collectors.groupingBy(WorkflowTaskPort::getTaskId, Collectors.counting()));
        for (WorkflowTaskPort p : ports) {
            if (p.getDirection() != WorkflowTaskPort.Direction.OUTPUT || p.getMaterialNodeId() == null) {
                continue;
            }
            WorkProcessTask producer = taskById.get(p.getTaskId());
            if (producer == null || Objects.equals(producer.getId(), task.getId())
                    || producer.getProcessOrder() == null || producer.getProcessOrder() >= myOrder) {
                continue;
            }
            producers.computeIfAbsent(p.getMaterialNodeId(), k -> new ArrayList<>()).add(p);
        }

        // 本道投入, 按选择组归并。
        // ⚠️ 必需与否看的是<b>选择组的模式</b>, 不是端口上的 required: 编译器给没声明分组的工序
        //    自动套一个「实际投入」AT_LEAST_ONE 组 (actual-io:*), 端口 required 一律 false ——
        //    prod 2026-10-25 实读 306 个端口全是 required=false、全部有组。按 required 过滤 = 闸永远不响。
        //    无组的端口 (旧快照) 才看 required。
        Map<String, List<WorkflowTaskPort>> groups = new LinkedHashMap<>();
        for (WorkflowTaskPort p : ports) {
            if (!Objects.equals(p.getTaskId(), task.getId())
                    || p.getDirection() != WorkflowTaskPort.Direction.INPUT) {
                continue;
            }
            if (p.getSelectionGroupId() == null) {
                if (!Boolean.FALSE.equals(p.getRequired())) {
                    groups.computeIfAbsent("p:" + p.getId(), k -> new ArrayList<>()).add(p);
                }
                continue;
            }
            groups.computeIfAbsent("g:" + p.getSelectionGroupId(), k -> new ArrayList<>()).add(p);
        }

        Set<WorkProcessTask> blockers = new LinkedHashSet<>();
        for (List<WorkflowTaskPort> group : groups.values()) {
            WorkflowTaskPort first = group.get(0);
            String mode = first.getSelectionGroupMode();
            Integer min = first.getSelectionGroupMinSelections();
            if (first.getSelectionGroupId() != null
                    && ("OPTIONAL".equals(mode) || (min != null && min <= 0))) {
                continue;   // 可以一样都不投, 不设闸
            }
            // ALL_REQUIRED (以及无组的必需端口): 每个成员都要有料; 其它 (AT_LEAST_ONE / EXACTLY_ONE): 有一个就够
            boolean needsEvery = first.getSelectionGroupId() == null || "ALL_REQUIRED".equals(mode);
            List<WorkProcessTask> groupWaiting = new ArrayList<>();
            int satisfiedMembers = 0;
            for (WorkflowTaskPort in : group) {
                List<WorkflowTaskPort> from = producers.getOrDefault(in.getMaterialNodeId(), List.of());
                if (from.isEmpty() || from.stream().anyMatch(out -> producedMaterial(
                        taskById.get(out.getTaskId()), out,
                        outputCount.getOrDefault(out.getTaskId(), 1L), evidence))) {
                    // 没有上游工序产出它 (原料 / 外购) 或者上游已经出了这种料
                    satisfiedMembers++;
                } else {
                    from.forEach(out -> groupWaiting.add(taskById.get(out.getTaskId())));
                }
            }
            boolean groupSatisfied = needsEvery ? groupWaiting.isEmpty() : satisfiedMembers > 0;
            if (!groupSatisfied) {
                blockers.addAll(groupWaiting);
            }
        }
        List<WorkProcessTask> sorted = new ArrayList<>(blockers);
        sorted.sort((a, b) -> Integer.compare(a.getProcessOrder(), b.getProcessOrder()));
        return sorted;
    }

    /**
     * 上游 producer 有没有出 {@code out} 这一种料。
     * 跳过 / 取消 → 算 (不会再产出, 料从别处来); 只有一个产出口 → actualQuantity &gt; 0;
     * 一出多 → 按这一种料找 web 行 / App 报工证据。
     */
    private boolean producedMaterial(WorkProcessTask producer, WorkflowTaskPort out, long producerOutputs,
                                     Evidence evidence) {
        if (producer == null) {
            return false;
        }
        if (producer.getStatus() == WorkProcessTask.Status.SKIPPED
                || producer.getStatus() == WorkProcessTask.Status.CANCELLED) {
            return true;
        }
        if (producerOutputs <= 1) {
            return positive(producer.getActualQuantity());
        }
        return evidence.webRows(producer).stream().anyMatch(r -> rowProduces(r, out))
                || evidence.appReports(producer).stream().anyMatch(r -> positive(r.getOutputQuantity())
                        && out.getSkuId() != null && out.getSkuId().equals(r.getProductTypeId()));
    }

    /** web 正式录入行是不是出了这一种料 (多产出组拆成的成员行各自带端口)。 */
    private boolean rowProduces(ProcessSheetRow row, WorkflowTaskPort out) {
        if (ProcessSheetRow.SUBMISSION_DRAFT.equals(row.getSubmissionStatus())) {
            return false;
        }
        ProcessSheetRowRequest req = parse(row.getRowPayload());
        if (req == null) {
            return false;
        }
        if (positive(req.getOutputQuantity()) && matches(out, req.getWorkflowPortId(),
                req.getMaterialNodeId(), req.getProductTypeId())) {
            return true;
        }
        return req.getOutputs() != null && req.getOutputs().stream().anyMatch(o -> positive(o.getQuantity())
                && matches(out, o.getWorkflowPortId(), null, o.getProductTypeId()));
    }

    /** 端口身份优先 (端口 id / 物料节点), 都没有时才比 SKU。 */
    private static boolean matches(WorkflowTaskPort out, String portId, String materialNodeId, String skuId) {
        if (portId != null) {
            return portId.equals(out.getWorkflowPortId());
        }
        if (materialNodeId != null) {
            return materialNodeId.equals(out.getMaterialNodeId());
        }
        return skuId != null && skuId.equals(out.getSkuId());
    }

    private static boolean positive(BigDecimal q) {
        return q != null && q.signum() > 0;
    }

    private ProcessSheetRowRequest parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, ProcessSheetRowRequest.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一次判定内的证据缓存: 同一道上游的 web 行 / App 报工只查一次。 */
    private final class Evidence {
        private final String factoryId;
        private final Map<Long, List<ProcessSheetRow>> rows = new HashMap<>();
        private final Map<Long, List<ProductionReport>> reports = new HashMap<>();

        Evidence(String factoryId) {
            this.factoryId = factoryId;
        }

        List<ProcessSheetRow> webRows(WorkProcessTask producer) {
            return rows.computeIfAbsent(producer.getId(), id -> {
                if (producer.getProductionBatchId() == null || producer.getProcessOrder() == null) {
                    return List.of();
                }
                String planId = productionBatchRepository.findById(producer.getProductionBatchId())
                        .map(b -> b.getProductionPlanId())
                        .orElse(null);
                if (planId == null) {
                    return List.of();
                }
                return processSheetRowRepository.findByFactoryIdAndPlanId(factoryId, planId).stream()
                        .filter(r -> producer.getProcessOrder().equals(r.getProcessOrder()))
                        .collect(Collectors.toList());
            });
        }

        List<ProductionReport> appReports(WorkProcessTask producer) {
            return reports.computeIfAbsent(producer.getId(),
                    id -> productionReportRepository.findYieldReportsByTask(factoryId, id));
        }
    }

    private static List<WorkProcessTask> legacyBlockers(WorkProcessTask task, List<WorkProcessTask> siblings) {
        if (task.getProcessOrder() == null) {
            return List.of();
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
        return previous == null || hasProduced(previous) ? List.of() : List.of(previous);
    }
}
