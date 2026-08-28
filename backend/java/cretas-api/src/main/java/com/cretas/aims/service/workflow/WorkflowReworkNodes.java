package com.cretas.aims.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「这个物料 Cell 是不是回料」的<b>唯一判据</b>。
 *
 * <p>回料 = 金检/X光 剔出的不良品, 回到下一批的绞肉工序重新投入。它与副产
 * ({@link WorkflowByproductNodes}) 是<b>两件事</b>:
 * <ul>
 *   <li>副产 (肥油/料头) —— 打了标记的端口会被 {@code getOutputOptions} <b>过滤掉</b>,
 *       走 {@code material_batches} 那条链, 且刻意不写单价。</li>
 *   <li>回料 —— 端口<b>必须出现在</b>产出选项里 (操作员要填数), 走
 *       {@code semi_finished_inventory} 那条链, 带移动加权成本进下一批。</li>
 * </ul>
 *
 * <p>⛔ 不要把这两个标记合并。合并等于让回料要么进不了报工屏, 要么丢掉成本 ——
 * 而回料是几乎全价的肉, 丢成本会让毛利虚高。
 *
 * <p>本类存在的理由与 {@link WorkflowByproductNodes} 同源: 同一句判据在本仓一度有三份
 * 手写实现。这里从第一天就收敛成一份。
 */
public final class WorkflowReworkNodes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowReworkNodes() {
    }

    /**
     * 回料标记的 truthiness。{@code null} / 缺失 一律 false —— 不猜。
     *
     * <p>必须容忍字符串 {@code "true"}: nodes_json 是 jsonb, 历史快照里这个标记既可能是
     * 布尔也可能是字符串。
     */
    public static boolean isReworkFlag(Object flag) {
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * 从运行时快照 nodes_json 里取出<b>被标记为回料的物料节点 id 集合</b>。
     *
     * <p>{@code WorkflowTaskPort.materialNodeId} 就是这里的节点 {@code id}, 两侧靠它 join。
     * ⛔ 不要为此在 {@code workflow_task_ports} 上加一列 —— nodes_json 是权威源。
     */
    public static Set<String> reworkMaterialNodeIds(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode nodes = MAPPER.readTree(nodesJson);
            if (!nodes.isArray()) {
                return Collections.emptySet();
            }
            Set<String> marked = new LinkedHashSet<>();
            for (JsonNode node : nodes) {
                String id = node.path("id").asText(null);
                if (id == null || id.isBlank()) {
                    continue;
                }
                JsonNode flag = node.path("data").path("isRework");
                if (flag.isMissingNode() || flag.isNull()) {
                    continue;
                }
                if (isReworkFlag(flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
                    marked.add(id);
                }
            }
            return marked;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    /**
     * 从冻结/已发布画布中取回料端口绑定的 SKU id。
     *
     * <p>销售商品目录必须排除这些 SKU：它们是金检/X 光剔出的不良品回投身份，
     * 不是可直接卖给客户的普通半成品。判据仍只认画布 {@code data.isRework}，
     * 不按名称包含“回料”或 {@code PTSEMI} 前缀猜测。</p>
     */
    public static Set<String> reworkProductTypeIds(String nodesJson) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return Collections.emptySet();
        }
        try {
            JsonNode nodes = MAPPER.readTree(nodesJson);
            if (!nodes.isArray()) {
                return Collections.emptySet();
            }
            Set<String> marked = new LinkedHashSet<>();
            for (JsonNode node : nodes) {
                JsonNode data = node.path("data");
                JsonNode flag = data.path("isRework");
                if (flag.isMissingNode() || flag.isNull()
                        || !isReworkFlag(flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
                    continue;
                }
                String skuId = data.path("skuId").asText(null);
                if (skuId != null && !skuId.isBlank()) {
                    marked.add(skuId);
                }
            }
            return marked;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }
}
