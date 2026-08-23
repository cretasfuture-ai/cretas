package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical authoring semantics for newly saved Workflow drafts.
 *
 * <p>Workflow owns the possible process interfaces. The pinned BOM owns main/substitute
 * authorization, while a formal process report records which allowed inputs and outputs
 * actually occurred. Historical revisions without this marker keep their legacy contract.
 */
public final class WorkflowActualIoSemantics {

    public static final String MODE_FIELD = "reportingSelectionMode";
    public static final String ACTUAL_IO = "ACTUAL_IO";

    private WorkflowActualIoSemantics() {
    }

    public static boolean enabled(ProductProcessWorkflowDTO.Node node) {
        return node != null
                && "PROCESS".equals(node.getKind())
                && node.getData() != null
                && ACTUAL_IO.equals(node.getData().get(MODE_FIELD));
    }

    /**
     * Normalize only the mutable request DTO before validation and snapshot capture.
     * Immutable historical revision rows are never rewritten.
     *
     * <h3>⛔ 不要为「让用户声明产出角色/分摊比例」而放开下面那两个 remove</h3>
     *
     * <p>2026-08-18 一度按这个方向改过, 是错的, 已回退。原因是
     * {@code ProcessPort.outputRole} / {@code costAllocationRatio} <b>是已弃用的载体</b>:
     * web-admin {@code workflow/types.ts} 的 {@code MaterialNodeData.isByproduct} 注释白纸黑字写着
     * 「与 {@code ProcessPort.outputRole} 的关系: 那个字段已弃用(见其注释), 不要用它表达副产」,
     * 而画布编辑器目录下这两个字段<b>只出现在类型声明与测试里, 生产 view 代码 0 处 v-model</b> ——
     * 放开它们等于造一个前端没有入口的字段, 然后在报错里让用户去填它。</p>
     *
     * <p><b>活着的载体是物料节点上的 {@code data.isByproduct}</b>(2026-08-07 阶段 2 换的),
     * 两端接通: 画布 {@code addOutputToProcess({byproduct:true})} 写入 →
     * {@link WorkflowByproductNodes} 是 JVM 侧唯一判据 → 报工端投影成
     * {@code PortDescriptor.byproduct}。它之所以放在<b>物料节点</b>上, 正是因为
     * 「{@code normalizeDraft} 每次保存都清洗 PROCESS 节点」——
     * 换载体就是为了绕开这里, ⛔ 不要反过来改这里去迁就旧载体。</p>
     *
     * <p>⚠️ 「用户显式声明每个联产品分摊 60/40」这件事在<b>设计期</b>目前<b>没有载体也没有入口</b>,
     * 这是一个已登记的缺口(需要前端配套)。它在<b>报工期</b>是有入口的:
     * {@code ProcessOutputTable.vue} 每个产出行有 {@code v-model="line.costAllocationRatio"},
     * 由 {@code ProcessSheetServiceImpl#resolveCostAllocationPlan} 按
     * EXPLICIT_RATIO → MASS → QUANTITY 依次裁定。</p>
     */
    public static void normalizeDraft(ProductProcessWorkflowDTO definition) {
        if (definition == null || definition.getNodes() == null) {
            return;
        }
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || !"PROCESS".equals(node.getKind()) || node.getData() == null) {
                continue;
            }
            Map<String, Object> data = new LinkedHashMap<>(node.getData());
            data.put(MODE_FIELD, ACTUAL_IO);
            data.remove("portGroups");
            data.remove("inputRequirementGroups");

            Object rawPorts = data.get("ports");
            if (rawPorts instanceof List<?> ports) {
                List<Object> normalizedPorts = new ArrayList<>(ports.size());
                for (Object rawPort : ports) {
                    if (rawPort instanceof Map<?, ?> port) {
                        Map<String, Object> normalizedPort = new LinkedHashMap<>();
                        port.forEach((key, value) -> normalizedPort.put(String.valueOf(key), value));
                        normalizedPort.remove("outputRole");
                        normalizedPort.remove("costAllocationRatio");
                        normalizedPorts.add(normalizedPort);
                    } else {
                        normalizedPorts.add(rawPort);
                    }
                }
                data.put("ports", normalizedPorts);
            }
            node.setData(data);
        }
    }
}
