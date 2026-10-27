package com.cretas.aims.service.processsecret;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 一张工艺图牵涉的产品。多产出工艺图挂在原料锚点上 (锚点 id 就是原料 id, 永远不受保护),
 * 真正被保护的是它产出的成品, 也可能是图中间的半成品 / 副产 —— 只按图的所属产品判, 会把受保护产品的工艺图
 * 当成不受保护 (独立审查 V2.1 M3)。所以按图判时, 所属产品与图里出现的每个 SKU 一起算:
 * 原料 SKU 是原料 id, 永远不是保密产品, 多算无害。
 */
public final class ProcessSecretWorkflowOutputs {

    private static final ObjectMapper JSON = new ObjectMapper()
            .findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private ProcessSecretWorkflowOutputs() {
    }

    /** 图里出现的全部 SKU (节点 data.skuId)。 */
    public static Set<String> productsInGraph(ProductProcessWorkflowDTO definition) {
        Set<String> products = new LinkedHashSet<>();
        if (definition == null || definition.getNodes() == null) return products;
        for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
            if (node == null || node.getData() == null) continue;
            Object sku = node.getData().get("skuId");
            if (sku instanceof String id && !id.isBlank()) products.add(id);
        }
        return products;
    }

    /**
     * 实体上存的是 JSON。⛔ 解析失败返回 empty —— 调用方据此「无法确认」并按受保护处理 (fail-closed),
     * 不能退回只看锚点 (独立审查 V2.1 复核)。
     */
    public static Optional<Set<String>> productsInGraph(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) return Optional.of(new LinkedHashSet<>());
        try {
            ProductProcessWorkflowDTO definition = new ProductProcessWorkflowDTO();
            definition.setNodes(JSON.readValue(nodesJson, new TypeReference<List<ProductProcessWorkflowDTO.Node>>() { }));
            return Optional.of(productsInGraph(definition));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
