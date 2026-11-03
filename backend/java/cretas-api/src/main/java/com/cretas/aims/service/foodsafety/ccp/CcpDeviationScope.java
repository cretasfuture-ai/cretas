package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.processentry.ProcessSheetRow;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 缺口 13 —— 一条 CCP 偏离<b>管到哪儿</b>: 出事的这一批 + 工艺图上它的下游 (Steve 2026-11-03 裁定)。
 *
 * <p>以前是「同一生产计划的全部产出」: 卤煮那一道偏离, 连更早的修油道产出也被扣成待检 (prod 实测
 * F006 计划 58c806d3: 第 2 道偏离, 第 1 道的在制批次 23832 / 半成品行 386 一起被扣)。上游的料在出事之前就产出了,
 * 不受这次偏离影响。
 *
 * <h2>判据 ({@link Scope#covers})</h2>
 * <ul>
 *   <li>产出在偏离那一道<b>之后</b>、且工艺图上从那一道走得到 (无工艺图 = 后面各道都算) → 管;</li>
 *   <li>产出就在偏离那一道 → 只管<b>这一批</b> (同一生产批次或同一批号), 同一道的别的批次不管;</li>
 *   <li>产出在更早的道 → 不管;</li>
 *   <li>不知道偏离在哪一道 / 不知道产出在哪一道 → 管 (宁可多挡, 与旧口径一致)。</li>
 * </ul>
 * 成品批次没有工序序 (整条链的终点) ⇒ 同计划的一律算下游。
 *
 * <p>扣留 ({@code QcDeviationHold})、挡放行与入账扣待检 ({@link CcpOpenDeviationLookup}) 都用这一个判据,
 * ⛔ 不在别处再写一遍。
 */
@Component
@RequiredArgsConstructor
public class CcpDeviationScope {

    private final ProductionBatchRepository productionBatchRepo;
    private final WorkProcessTaskRepository taskRepo;
    private final WorkflowTaskPortRepository portRepo;
    private final ProcessSheetRowRepository sheetRowRepo;

    /**
     * 一条偏离的管辖范围。
     *
     * @param order      偏离在第几道; null = 不知道 ⇒ 全计划 (旧口径)
     * @param downstream 工艺图上从那一道走得到的更后的道; null = 无工艺图 ⇒ 后面各道都算
     */
    public record Scope(Integer order, Set<Integer> downstream, Long productionBatchId, String batchNumber) {

        public static final Scope WHOLE_PLAN = new Scope(null, null, null, null);

        /** 某一笔产出 (在第 itemOrder 道, 属于这些生产批次 / 批号) 归不归这条偏离管。 */
        public boolean covers(Integer itemOrder, Collection<Long> itemBatchIds, Collection<String> itemBatchNos) {
            if (order == null || itemOrder == null) {
                return true;
            }
            if (itemOrder > order) {
                return downstream == null || downstream.contains(itemOrder);
            }
            if (itemOrder.equals(order)) {
                return (productionBatchId != null && itemBatchIds != null && itemBatchIds.contains(productionBatchId))
                        || (batchNumber != null && itemBatchNos != null && itemBatchNos.contains(batchNumber));
            }
            return false;
        }
    }

    public Scope of(String factoryId, HaccpMonitoringRecord r) {
        return of(factoryId, r.getProductionPlanId(), r.getProcessOrder(), r.getProductionBatchId(), r.getBatchNumber());
    }

    public Scope of(String factoryId, String planId, Integer order, Long productionBatchId, String batchNumber) {
        if (order == null) {
            return Scope.WHOLE_PLAN;
        }
        return new Scope(order, downstreamOrders(factoryId, planId, order), productionBatchId, batchNumber);
    }

    /**
     * 工艺图上从第 {@code order} 道 (经产出料 → 投入料) 走得到的更后的道。
     * 计划没有工艺图 / 图里没有这一道 → null (调用方按「后面各道都算」)。
     */
    Set<Integer> downstreamOrders(String factoryId, String planId, int order) {
        if (planId == null || planId.isBlank()) {
            return null;
        }
        Map<Long, List<WorkProcessTask>> tasksByInstance = new LinkedHashMap<>();
        for (ProductionBatch pb : productionBatchRepo.findByFactoryIdAndProductionPlanIdAndDeletedAtIsNull(
                factoryId, planId)) {
            for (WorkProcessTask t : taskRepo.findByFactoryIdAndProductionBatchIdOrderByProcessOrderAsc(
                    factoryId, pb.getId())) {
                if (t.getWorkflowInstanceId() != null) {
                    tasksByInstance.computeIfAbsent(t.getWorkflowInstanceId(), k -> new java.util.ArrayList<>()).add(t);
                }
            }
        }
        Set<Integer> out = new TreeSet<>();
        boolean found = false;
        for (Map.Entry<Long, List<WorkProcessTask>> e : tasksByInstance.entrySet()) {
            List<WorkflowTaskPort> ports = portRepo.findByFactoryIdAndWorkflowInstanceId(factoryId, e.getKey());
            Map<Long, Integer> orderOf = new HashMap<>();
            e.getValue().forEach(t -> orderOf.put(t.getId(), t.getProcessOrder()));
            Map<Long, Set<String>> outputsOf = new HashMap<>();
            Map<String, Set<Long>> consumersOf = new HashMap<>();
            for (WorkflowTaskPort p : ports) {
                if (p.getTaskId() == null || p.getMaterialNodeId() == null || !orderOf.containsKey(p.getTaskId())) {
                    continue;
                }
                if (p.getDirection() == WorkflowTaskPort.Direction.OUTPUT) {
                    outputsOf.computeIfAbsent(p.getTaskId(), k -> new HashSet<>()).add(p.getMaterialNodeId());
                } else if (p.getDirection() == WorkflowTaskPort.Direction.INPUT) {
                    consumersOf.computeIfAbsent(p.getMaterialNodeId(), k -> new HashSet<>()).add(p.getTaskId());
                }
            }
            Deque<Long> frontier = new ArrayDeque<>();
            orderOf.forEach((id, o) -> {
                if (Objects.equals(o, order) && outputsOf.containsKey(id)) {
                    frontier.add(id);
                }
            });
            if (frontier.isEmpty()) {
                continue;
            }
            found = true;
            Set<Long> seen = new HashSet<>(frontier);
            while (!frontier.isEmpty()) {
                Long t = frontier.poll();
                Integer from = orderOf.get(t);
                for (String m : outputsOf.getOrDefault(t, Set.of())) {
                    for (Long c : consumersOf.getOrDefault(m, Set.of())) {
                        Integer to = orderOf.get(c);
                        // 只往后走: 回投 (返工) 边指回更早的道, 不让它把上游也算进来
                        if (to == null || from == null || to <= from || !seen.add(c)) {
                            continue;
                        }
                        out.add(to);
                        frontier.add(c);
                    }
                }
            }
        }
        return found ? out : null;
    }

    /** 生产批次的产出在第几道: 逐道录入的在制批次 (CLERK_WIP) 看它那一行录入; 其它 (App 整计划批次) → null。 */
    public Integer orderOfProductionBatch(String factoryId, Long productionBatchId) {
        if (productionBatchId == null) {
            return null;
        }
        return sheetRowRepo.findByFactoryIdAndBatchId(factoryId, productionBatchId).stream()
                .map(ProcessSheetRow::getProcessOrder)
                .filter(Objects::nonNull)
                .min(Integer::compare)
                .orElse(null);
    }
}
