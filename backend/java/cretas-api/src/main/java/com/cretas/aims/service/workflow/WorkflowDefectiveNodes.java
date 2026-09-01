package com.cretas.aims.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 「这个物料 Cell 是不是不良品」的<b>唯一判据</b>。
 *
 * <p>不良品 = 金检/X光 剔出来、<b>还没复查</b>的那部分。车间流程是
 * 「剔出 → 复查 → 存着 → 回料直接回过去」。
 *
 * <p>⛔ 它<b>不是</b>「判废」，⛔ 也<b>不是</b>「不入库」——
 * 那两个说法 2026-09-01 被 owner 明确否掉。它要入库存着。
 *
 * <h3>本类是角色标，⛔ 永远不做过滤</h3>
 * <p>{@link WorkflowByproductNodes} 被 {@code getOutputOptions} 用来<b>过滤掉</b>副产端口；
 * 本类<b>绝不能</b>那样用。不良品要能报、要入库，把它加进任何一处
 * {@code .filter(...)} 都会让它从那条路上消失，而症状是「报工屏上找不到这一项」——
 * 一个很难往回追到这里的长相。
 *
 * <p>它存在的全部理由是让上层<b>区分得出来</b>（owner 2026-09-01：「好的区别一下吧」）：
 * 在这个标出现之前，报工端下发的产出项里没有任何字段能把不良品和普通半成品产出分开，
 * 操作员只能靠品名猜。
 *
 * <h3>与另外两个标记的关系</h3>
 * <ul>
 *   <li>副产 {@link WorkflowByproductNodes} —— 被过滤出产出选项，走 {@code material_batches}；
 *       在文员录入表上不独立成行，填进主产出行下方那块区。</li>
 *   <li>回料 {@link WorkflowReworkNodes} —— <b>必须留在</b>产出选项里，走
 *       {@code semi_finished_inventory}，带移动加权成本进下一批。</li>
 *   <li>不良品（本类）—— 无过滤，走无标记半成品产出的<b>默认</b>路径。</li>
 * </ul>
 *
 * <p>⛔ 三个标不要合并。它们的运行时后果各不相同，合并等于把其中两个的行为改掉。
 */
public final class WorkflowDefectiveNodes {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private WorkflowDefectiveNodes() {
    }

    /**
     * 不良品标记的 truthiness。{@code null} / 缺失 一律 false —— 不猜。
     *
     * <p>必须容忍字符串 {@code "true"}: nodes_json 是 jsonb, 历史快照里这个标记既可能是
     * 布尔也可能是字符串（与另外两个标记同源的理由）。
     */
    public static boolean isDefectiveFlag(Object flag) {
        return Boolean.TRUE.equals(flag) || "true".equalsIgnoreCase(String.valueOf(flag));
    }

    /**
     * 从运行时快照 nodes_json 里取出<b>被标记为不良品的物料节点 id 集合</b>。
     *
     * <p>{@code WorkflowTaskPort.materialNodeId} 就是这里的节点 {@code id}, 两侧靠它 join。
     * ⛔ 不要为此在 {@code workflow_task_ports} 上加一列 —— nodes_json 是权威源。
     */
    public static Set<String> defectiveMaterialNodeIds(String nodesJson) {
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
                JsonNode flag = node.path("data").path("isDefective");
                if (flag.isMissingNode() || flag.isNull()) {
                    continue;
                }
                if (isDefectiveFlag(flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
                    marked.add(id);
                }
            }
            return marked;
        } catch (Exception ignored) {
            return Collections.emptySet();
        }
    }

    /**
     * 从冻结/已发布画布中取不良品端口绑定的 SKU id。
     *
     * <p>与 {@link WorkflowReworkNodes#reworkProductTypeIds} 同一个理由：销售商品目录必须
     * 排除这些 SKU —— 它们是剔出来待复查的东西，不是可以直接卖给客户的普通半成品。
     * 判据仍只认画布 {@code data.isDefective}，⛔ 不按名称包含「不良」猜测。
     */
    public static Set<String> defectiveProductTypeIds(String nodesJson) {
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
                JsonNode flag = data.path("isDefective");
                if (flag.isMissingNode() || flag.isNull()
                        || !isDefectiveFlag(flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
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
