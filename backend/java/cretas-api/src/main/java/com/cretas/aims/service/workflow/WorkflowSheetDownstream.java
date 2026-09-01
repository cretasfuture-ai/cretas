package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 从某道工序节点<b>沿图往下游走</b>，找出它最终会流进哪些终端成品节点。
 *
 * <h3>为什么需要它（2026-09-02 本机 UAT 实拍）</h3>
 * 联产图（一张图四条线）里，中间道报工行<b>不带成品</b>——它只说「我是 p:snack:1」。
 * 而调料/辅料行是 {@code OUTPUT_EXCLUSIVE}、scope 钉在某条线的终端（{@code m:snack:fin}），
 * 原来的判定拿 {@code plan.selectedBomRecipeId}（<b>整个计划只有一个</b>）的终端去比，
 * 于是<b>只有被钉为默认的那条线扣辅料</b>，其余三条线的中间道一克都不扣：
 * <pre>
 *   美式(默认线)  搅拌 → 冰水 97.88 kg 扣了
 *   小吃吧        绞肉 3 行 / 搅拌 5 行 → 一行都没扣, 账本里 AUX = 0
 * </pre>
 * 而「这道属于哪条线」其实图上写得清清楚楚——沿着产出往下走就到终端了。
 *
 * <p>只用报工表已经拿到的 {@link WorkflowClerkSheetConfigDTO}（每道的 inputs/outputs 端口），
 * ⛔ 不另开一份图解析。端口的 {@code materialNodeId} 就是物料 Cell 的节点 id，
 * 「A 的产出节点 == B 的投入节点」就是一条边。
 */
public final class WorkflowSheetDownstream {

    private WorkflowSheetDownstream() {
    }

    /** 终端成品节点：{@code finished == true} 的产出端口所指向的物料 Cell。 */
    public record Terminal(String materialNodeId, String skuId) {
    }

    /**
     * 从 {@code startProcessNodeId} 出发可达的全部终端成品节点（含本道自己直接产出的成品）。
     * 图上没有这道、或走不到任何成品时返回空集——⛔ 不兜底成「全部终端」，
     * 那会把别的线的辅料也算进来，与原缺陷方向相反但同样是错的。
     */
    public static Set<Terminal> reachableTerminals(WorkflowClerkSheetConfigDTO config,
                                                   String startProcessNodeId) {
        Set<Terminal> found = new LinkedHashSet<>();
        if (config == null || config.getProcesses() == null || startProcessNodeId == null) {
            return found;
        }
        List<WorkflowClerkSheetConfigDTO.ProcessDescriptor> processes = config.getProcesses();
        // 物料节点 → 以它为投入的工序
        Map<String, List<WorkflowClerkSheetConfigDTO.ProcessDescriptor>> consumers = new HashMap<>();
        Map<String, WorkflowClerkSheetConfigDTO.ProcessDescriptor> byNode = new HashMap<>();
        for (WorkflowClerkSheetConfigDTO.ProcessDescriptor p : processes) {
            if (p.getWorkflowNodeId() != null) {
                byNode.put(p.getWorkflowNodeId(), p);
            }
            if (p.getInputs() == null) continue;
            for (WorkflowClerkSheetConfigDTO.PortDescriptor in : p.getInputs()) {
                if (in.getMaterialNodeId() == null) continue;
                consumers.computeIfAbsent(in.getMaterialNodeId(), k -> new java.util.ArrayList<>()).add(p);
            }
        }
        WorkflowClerkSheetConfigDTO.ProcessDescriptor start = byNode.get(startProcessNodeId);
        if (start == null) {
            return found;
        }
        Deque<WorkflowClerkSheetConfigDTO.ProcessDescriptor> queue = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        queue.add(start);
        visited.add(startProcessNodeId);
        while (!queue.isEmpty()) {
            WorkflowClerkSheetConfigDTO.ProcessDescriptor p = queue.poll();
            if (p.getOutputs() == null) continue;
            for (WorkflowClerkSheetConfigDTO.PortDescriptor out : p.getOutputs()) {
                String node = out.getMaterialNodeId();
                if (node == null) continue;
                if (Boolean.TRUE.equals(out.getFinished())) {
                    found.add(new Terminal(node, out.getSkuId()));
                }
                for (WorkflowClerkSheetConfigDTO.ProcessDescriptor next : consumers.getOrDefault(node, List.of())) {
                    if (next.getWorkflowNodeId() != null && visited.add(next.getWorkflowNodeId())) {
                        queue.add(next);
                    }
                }
            }
        }
        return found;
    }
}
