package com.cretas.aims.service.processsecret;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.cretas.aims.service.workflow.WorkflowTopologyClassifier;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一张工艺图的终端产出。多产出工艺图挂在原料锚点上 (锚点 id 就是原料 id, 永远不受保护),
 * 真正被保护的是它产出的成品 —— 只按图的所属产品判, 会把受保护成品的工艺图当成不受保护
 * (独立审查 V2.1 M3)。所以按图判时, 所属产品与终端产出一起算。
 */
public final class ProcessSecretWorkflowOutputs {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProcessSecretWorkflowOutputs() {
    }

    public static Set<String> terminalOutputs(ProductProcessWorkflowDTO definition) {
        Set<String> outputs = new LinkedHashSet<>();
        if (definition == null || definition.getNodes() == null) return outputs;
        try {
            WorkflowTopologyClassifier.classify(definition).terminalOutputSkuIds().stream()
                    .filter(id -> id != null && !id.isBlank())
                    .forEach(outputs::add);
        } catch (RuntimeException ignored) {
            // 图不完整时分类器可能拒绝; 调用方仍按所属产品判
        }
        return outputs;
    }

    /** 实体上存的是 JSON; 解析失败返回空集 (调用方仍按所属产品判)。 */
    public static Set<String> terminalOutputs(String nodesJson, String edgesJson) {
        if (nodesJson == null || nodesJson.isBlank()) return new LinkedHashSet<>();
        try {
            ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
            definition.setNodes(JSON.readValue(nodesJson, new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
            definition.setEdges(edgesJson == null || edgesJson.isBlank() ? List.of()
                    : JSON.readValue(edgesJson, new TypeReference<List<ProductProcessWorkflowDTO.Edge>>() { }));
            return terminalOutputs(definition);
        } catch (Exception e) {
            return new LinkedHashSet<>();
        }
    }
}
