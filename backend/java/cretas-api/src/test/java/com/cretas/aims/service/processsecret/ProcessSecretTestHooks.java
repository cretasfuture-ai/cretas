package com.cretas.aims.service.processsecret;

import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;
import com.cretas.aims.repository.bomdraft.BomRecipeDraftRepository;
import com.cretas.aims.repository.factory.FactoryMaterialRequisitionRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;

import static org.mockito.Mockito.mock;

/** 测试用入口: 与生产同一个 {@link ProcessSecretReadTracker#record} 打标, 以及按需组装裁剪器。 */
public final class ProcessSecretTestHooks {

    private ProcessSecretTestHooks() {
    }

    /** 在桩里模拟「真实服务读到了这条实体」, 与生产上 Hibernate PostLoad 走同一个入口。 */
    public static void record(Object loadedEntity) {
        ProcessSecretReadTracker.record(loadedEntity);
    }

    /** 领料单 / 对话建厂草稿的解析仓库不涉及时给桩。 */
    public static ProcessSecretPayloadScrubber scrubber(ProcessSecretAccessService access, BomRecipeRepository recipes,
                                                       ProductionWorkflowInstanceRepository instances,
                                                       ProductionPlanRepository plans) {
        return new ProcessSecretPayloadScrubber(access, recipes, instances, plans,
                mock(FactoryMaterialRequisitionRepository.class), mock(BomRecipeDraftRepository.class),
                mock(ProductionBatchRepository.class));
    }

    /** 报工行经批次解析计划时用。 */
    public static ProcessSecretPayloadScrubber scrubber(ProcessSecretAccessService access, BomRecipeRepository recipes,
                                                       ProductionWorkflowInstanceRepository instances,
                                                       ProductionPlanRepository plans,
                                                       ProductionBatchRepository batches) {
        return new ProcessSecretPayloadScrubber(access, recipes, instances, plans,
                mock(FactoryMaterialRequisitionRepository.class), mock(BomRecipeDraftRepository.class), batches);
    }

    /**
     * 一张「原料 → 一道工序 → 若干成品」的工艺图, 返回 {nodesJson, edgesJson} (与实体上存的格式一致,
     * 与 WorkflowTopologyClassifierTest 的造图方式相同)。
     */
    public static String[] workflowGraphJson(java.util.List<String> roots, java.util.List<String> terminals) {
        java.util.List<java.util.Map<String, Object>> nodes = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> edges = new java.util.ArrayList<>();
        java.util.List<java.util.Map<String, Object>> ports = new java.util.ArrayList<>();
        nodes.add(node("process", "PROCESS", new java.util.LinkedHashMap<>(java.util.Map.of("ports", ports))));
        int i = 0;
        for (String root : roots) {
            String id = "raw-" + i++;
            nodes.add(node(id, "RAW_MATERIAL", new java.util.LinkedHashMap<>(java.util.Map.of("skuId", root))));
            edges.add(edge("edge-in-" + id, id, "output", "process", "input-" + id));
            ports.add(new java.util.LinkedHashMap<>(java.util.Map.of("id", "input-" + id, "direction", "INPUT",
                    "materialNodeId", id)));
        }
        i = 0;
        for (String terminal : terminals) {
            String id = "fg-" + i++;
            nodes.add(node(id, "FINISHED_GOOD", new java.util.LinkedHashMap<>(java.util.Map.of("skuId", terminal))));
            edges.add(edge("edge-out-" + id, "process", "output-" + id, id, "input"));
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            return new String[]{json.writeValueAsString(nodes), json.writeValueAsString(edges)};
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.util.Map<String, Object> node(String id, String kind, java.util.Map<String, Object> data) {
        java.util.Map<String, Object> node = new java.util.LinkedHashMap<>();
        node.put("id", id);
        node.put("kind", kind);
        node.put("position", java.util.Map.of("x", 0D, "y", 0D));
        node.put("data", data);
        return node;
    }

    private static java.util.Map<String, Object> edge(String id, String source, String sourceHandle, String target,
                                                     String targetHandle) {
        java.util.Map<String, Object> edge = new java.util.LinkedHashMap<>();
        edge.put("id", id);
        edge.put("source", source);
        edge.put("sourceHandle", sourceHandle);
        edge.put("target", target);
        edge.put("targetHandle", targetHandle);
        return edge;
    }
}
