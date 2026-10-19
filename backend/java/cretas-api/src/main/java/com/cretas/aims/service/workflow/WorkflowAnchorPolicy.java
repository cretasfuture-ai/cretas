package com.cretas.aims.service.workflow;

import com.cretas.aims.dto.ProductProcessWorkflowDTO;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Recommends a catalog owner from graph topology. This is an organizational hint only:
 * published workflows, immutable revision hashes, BOM pins and history retain their owner.
 * Cross-owner discovery uses the existing terminal-output resolver.
 */
public final class WorkflowAnchorPolicy {

    private WorkflowAnchorPolicy() {
    }

    /**
     * @return 建议的存放对象 id; {@link Optional#empty()} 表示无存放建议；本方法不迁移归属对象
     *         (联产 / 结构不完整 / 拿不到可靠结论)。
     */
    public static Optional<String> desiredOwner(
            ProductProcessWorkflowDTO definition, WorkflowTopology topology) {
        if (definition == null || topology == null) return Optional.empty();
        return switch (topology.type()) {
            case SINGLE_OUTPUT_PRODUCT -> topology.terminalOutputSkuIds().stream().findFirst();
            case RAW_MATERIAL_SPLIT -> primaryRootSku(definition, topology);
            // 联产与结构不完整一律不动 —— 见类注释。
            case JOINT_PRODUCTION, INVALID -> Optional.empty();
        };
    }

    /**
     * 替代组里的主原料 —— 没有 {@code substituteOfNodeId} 的那个根原料。
     *
     * <p>只在 RAW_MATERIAL_SPLIT 下调用, 此时 logicalRootCount == 1, 即所有根原料同属一组。
     */
    private static Optional<String> primaryRootSku(
            ProductProcessWorkflowDTO definition, WorkflowTopology topology) {
        if (topology.rootInputSkuIds().isEmpty()) return Optional.empty();
        if (topology.rootInputSkuIds().size() == 1) {
            return Optional.of(topology.rootInputSkuIds().get(0));
        }
        Set<String> rootSkus = new HashSet<>(topology.rootInputSkuIds());
        if (definition.getNodes() != null) {
            for (ProductProcessWorkflowDTO.Node node : definition.getNodes()) {
                if (node == null || node.getData() == null) continue;
                String skuId = stringValue(node.getData(), "skuId");
                if (skuId == null || !rootSkus.contains(skuId)) continue;
                if (stringValue(node.getData(), "substituteOfNodeId") == null) {
                    return Optional.of(skuId);
                }
            }
        }
        // 全都互指(validator 应已拦下): 退回排序首位, 只求确定性, 不猜语义。
        return Optional.of(topology.rootInputSkuIds().get(0));
    }

    private static String stringValue(Map<String, Object> data, String key) {
        Object raw = data.get(key);
        if (!(raw instanceof String text)) return null;
        String trimmed = text.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
