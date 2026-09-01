package com.cretas.aims.service.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 回料「按配方比例自动扣减」的<b>唯一算式</b>。
 *
 * <h3>语义（owner 2026-09-01 两次订正后的最终口径）</h3>
 * <ul>
 *   <li>比例是 <b>BOM 性质的确定投入比例</b>，⛔ 不是上限、不是天花板；</li>
 *   <li>分母是这道工序投入的 <b>原料/半成品（主料）</b>，⛔ 不是「工序总投入」——
 *       owner 原话「比例的话是按照投入的原料或者半成品」。
 *       投 100kg 主料 + 8.5% ⇒ 回料投 8.5kg，本工序共投 108.5kg；</li>
 *   <li>用途是 <b>核算时自动扣减</b>，⛔ 不是提醒、不是警告、不是弹窗，也不拦报工
 *       （owner 原话「不用提醒，我说的是直接作为自动扣减，拉数据的时候能看到」）；</li>
 *   <li>同一块回料投给多道工序时，各道 <b>各以自己的主料为分母</b>，
 *       ⛔ 不要求合计 100%。</li>
 * </ul>
 *
 * <h3>⚠️ 本类只算数，不写账</h3>
 * 它是纯函数：进去是「画布 + 各工序的主料投入量」，出来是「该扣多少」。
 * 落账（生成 {@code semiFinishedConsumptions} 明细、找 {@code SemiFinishedInventory} 行、
 * 幂等）<b>还没有接</b> —— 见 {@link Outcome#UNRESOLVED_MAIN_INPUT} 那一档的说明。
 *
 * <h3>⛔ 算不出来时不返回 0</h3>
 * 「这道工序的主料投入量我不知道」和「该扣 0」是两件事，而下游对它们的处理完全相反。
 * 兜底成 0 会把「我不知道」翻译成一个很有说服力的真实读数 —— 本仓为这个形状付过代价
 * （{@code COALESCE(food_cost, 0)} 把「没有成本卡」算成「零成本」，毛利率报到 88.3%）。
 * 所以缺主料时产出一条 {@link Outcome#UNRESOLVED_MAIN_INPUT}，让调用方决定怎么喊。
 */
public final class WorkflowReworkDeduction {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    /** 扣减量保留 6 位小数 —— 与 process_sheet 里产出/投入数量的精度一致。 */
    private static final int SCALE = 6;

    private WorkflowReworkDeduction() {
    }

    /** 一条扣减的结论。 */
    public enum Outcome {
        /** 算出来了。 */
        RESOLVED,
        /** 这道工序的主料投入量拿不到 ⇒ 算不出，⛔ 不当 0 处理。 */
        UNRESOLVED_MAIN_INPUT
    }

    /**
     * 一条「某块回料按某道工序的主料该扣多少」。
     *
     * @param processNodeId  目标工序的画布节点 id
     * @param reworkNodeId   回料 Cell 的画布节点 id
     * @param reworkSkuId    回料绑定的 SKU（落账要用；画布上没绑时为 null）
     * @param inputRatioPercent 配方比例（0–100）
     * @param mainInputQuantity 这道工序投入的主料数量；算不出时为 null
     * @param deductQuantity 该扣的回料量；算不出时为 null
     * @param outcome        算出来了还是算不出
     */
    public record Line(
            String processNodeId,
            String reworkNodeId,
            String reworkSkuId,
            BigDecimal inputRatioPercent,
            BigDecimal mainInputQuantity,
            BigDecimal deductQuantity,
            Outcome outcome) {
    }

    /**
     * 按画布上的 {@code reworkPolicy} 与各工序的主料投入量算出全部扣减。
     *
     * @param nodesJson              运行时快照的 nodes_json（权威源）
     * @param mainInputByProcessNode 工序节点 id → 这道工序投入的主料数量。
     *                               ⛔ 传 null 或缺键表示「不知道」，<b>不是 0</b>。
     */
    public static List<Line> compute(
            String nodesJson, Map<String, BigDecimal> mainInputByProcessNode) {
        if (nodesJson == null || nodesJson.isBlank()) {
            return Collections.emptyList();
        }
        Map<String, BigDecimal> mainInputs =
                mainInputByProcessNode == null ? Map.of() : mainInputByProcessNode;
        try {
            JsonNode nodes = MAPPER.readTree(nodesJson);
            if (!nodes.isArray()) {
                return Collections.emptyList();
            }
            Map<String, String> skuByNodeId = reworkSkuByNodeId(nodes);
            List<Line> lines = new ArrayList<>();
            for (JsonNode node : nodes) {
                String processNodeId = node.path("id").asText(null);
                if (processNodeId == null || processNodeId.isBlank()) {
                    continue;
                }
                JsonNode sources = node.path("data").path("reworkPolicy").path("sources");
                if (!sources.isArray()) {
                    continue;
                }
                for (JsonNode source : sources) {
                    String reworkNodeId = source.path("reworkNodeId").asText(null);
                    if (reworkNodeId == null || reworkNodeId.isBlank()) {
                        continue;
                    }
                    BigDecimal ratio = ratioOf(source.path("inputRatioPercent"));
                    if (ratio == null) {
                        continue;
                    }
                    BigDecimal mainInput = mainInputs.get(processNodeId);
                    if (mainInput == null) {
                        lines.add(new Line(processNodeId, reworkNodeId,
                                skuByNodeId.get(reworkNodeId), ratio, null, null,
                                Outcome.UNRESOLVED_MAIN_INPUT));
                        continue;
                    }
                    lines.add(new Line(processNodeId, reworkNodeId,
                            skuByNodeId.get(reworkNodeId), ratio, mainInput,
                            deduct(mainInput, ratio), Outcome.RESOLVED));
                }
            }
            return lines;
        } catch (Exception ignored) {
            return Collections.emptyList();
        }
    }

    /**
     * 扣减量 = 主料投入量 × 比例%。
     *
     * <p>⛔ 分母不是「工序总投入」—— 见类注释里的算式与那个 100 / 108.5 的例子。
     */
    public static BigDecimal deduct(BigDecimal mainInputQuantity, BigDecimal inputRatioPercent) {
        if (mainInputQuantity == null || inputRatioPercent == null) {
            return null;
        }
        return mainInputQuantity
                .multiply(inputRatioPercent, MathContext.DECIMAL64)
                .divide(HUNDRED, SCALE, RoundingMode.HALF_UP);
    }

    /** 比例的合法域是 [0,100]。域外一律视为「没配」，⛔ 不夹逼成边界值。 */
    private static BigDecimal ratioOf(JsonNode value) {
        if (value == null || value.isMissingNode() || value.isNull() || !value.isNumber()) {
            return null;
        }
        BigDecimal ratio = value.decimalValue();
        if (ratio.compareTo(BigDecimal.ZERO) < 0 || ratio.compareTo(HUNDRED) > 0) {
            return null;
        }
        return ratio;
    }

    private static Map<String, String> reworkSkuByNodeId(JsonNode nodes) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        for (JsonNode node : nodes) {
            String id = node.path("id").asText(null);
            if (id == null || id.isBlank()) {
                continue;
            }
            JsonNode data = node.path("data");
            JsonNode flag = data.path("isRework");
            if (flag.isMissingNode() || flag.isNull()
                    || !WorkflowReworkNodes.isReworkFlag(
                            flag.isBoolean() ? flag.asBoolean() : flag.asText())) {
                continue;
            }
            String skuId = data.path("skuId").asText(null);
            if (skuId != null && !skuId.isBlank()) {
                result.put(id, skuId);
            }
        }
        return result;
    }
}
