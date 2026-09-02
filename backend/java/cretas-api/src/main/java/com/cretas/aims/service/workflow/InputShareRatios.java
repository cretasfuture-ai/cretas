package com.cretas.aims.service.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主原料份额：从工艺修订的 nodesJson 里把每个 INPUT 端口的 {@code inputShareRatio} 取出来，
 * 以及「填一个原料、其余按份额自动跳」的推算。
 *
 * <h3>为什么存在（owner 2026-09-02 裁定走 (a)）</h3>
 * 「哪怕是多个原料，它都有配比的。比如 30% 跟 70%，我填入第一个原料 30 吨，第二个它会自动跳出 70 吨。」
 * 此前主料之间的配比在画布节点和 bom_recipe_items 里<b>都不存在</b>（RAW 行 standard_quantity 强制 null），
 * 领料单主料恒显示「按实际领用」，客户 2026-08-03 已报过。
 *
 * <p>口径：份额是<b>相对量</b>，推算按已填端口的份额归一化 —— 各端口之和不必等于 100；
 * 份额为 null 的端口不参与推算（既不被推、也不当分母）。
 */
public final class InputShareRatios {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(InputShareRatios.class);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private InputShareRatios() {
    }

    /**
     * portId → 份额(%)。只收 INPUT 端口且 inputShareRatio 非 null 的。
     * nodesJson 非法时返回空表，⛔ 不抛：建单不能因为读份额失败而拦。
     */
    /**
     * 份额百分比的<b>唯一</b>解析 (两个存储收敛的那一层): null ⇒ null; 非数字 / 非有限 / 越界 [0,100] ⇒ 抛
     * {@link IllegalArgumentException}, 消息与编译器历史文案一致。编译器 {@code optionalPercent} 把它包成
     * runtimeInvalid 拒绝发布; 领料侧 {@link #fromNodesJson} 把它变成「这个端口无份额 + WARN」, ⛔ 不拦建单。
     * 两边判定同一个函数 ⇒ 不会出现「编译器拒了、领料照收」的漂移。
     */
    public static BigDecimal parsePercent(Object value) {
        if (value == null) {
            return null;
        }
        BigDecimal parsed;
        if (value instanceof BigDecimal decimal) {
            parsed = decimal;
        } else if (value instanceof Number number) {
            double d = number.doubleValue();
            if (!Double.isFinite(d)) {
                throw new IllegalArgumentException("must be a finite number");
            }
            parsed = BigDecimal.valueOf(d);
        } else {
            throw new IllegalArgumentException("must be a number");
        }
        if (parsed.signum() < 0 || parsed.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("must be between 0 and 100");
        }
        return parsed;
    }

    /**
     * 调用点登记 (硬约束 8): ① FactoryMaterialRequisitionServiceImpl.resolveInputShares —— 只在计划还没有物化的
     * 运行时实例 (转批次前) 时走这里; 有实例一律读编译列 workflow_task_ports.input_share_ratio。② 本类。
     */
    public static Map<String, BigDecimal> fromNodesJson(String nodesJson) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (nodesJson == null || nodesJson.isBlank()) {
            return out;
        }
        List<Map<String, Object>> nodes;
        try {
            nodes = MAPPER.readValue(nodesJson, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception ignored) {
            return out;
        }
        for (Map<String, Object> node : nodes) {
            if (!(node.get("data") instanceof Map<?, ?> data)) continue;
            if (!(data.get("ports") instanceof List<?> ports)) continue;
            for (Object p : ports) {
                if (!(p instanceof Map<?, ?> port)) continue;
                if (!"INPUT".equals(port.get("direction"))) continue;
                Object id = port.get("id");
                Object share = port.get("inputShareRatio");
                if (id == null || share == null) continue;
                BigDecimal parsed;
                try {
                    parsed = parsePercent(share);
                } catch (IllegalArgumentException e) {
                    // 与编译器同一判定: 编译器会拒绝发布这份修订; 这里是建单, 不拦, 当作该端口无份额并留痕
                    log.warn("[input-share] 端口 {} 的 inputShareRatio={} 非法 ({}), 领料按无份额处理", id, share, e.getMessage());
                    continue;
                }
                if (parsed == null) continue;
                out.put(String.valueOf(id), parsed);
            }
        }
        return out;
    }

    /**
     * 「填一个、推其余」。{@code shares} 是本工序参与推算的端口份额；{@code filledPortId} 是用户刚填的端口，
     * {@code filledQty} 是填的量。返回<b>其余</b>参与端口的推算量（不含 filled 自己）。
     * filled 端口不在 shares 里、或其份额为 0 ⇒ 无法作为基准，返回空表（⛔ 不猜）。
     */
    public static Map<String, BigDecimal> derive(Map<String, BigDecimal> shares, String filledPortId,
                                                 BigDecimal filledQty) {
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (shares == null || filledPortId == null || filledQty == null) {
            return out;
        }
        BigDecimal base = shares.get(filledPortId);
        if (base == null || base.signum() <= 0) {
            return out;
        }
        for (Map.Entry<String, BigDecimal> e : shares.entrySet()) {
            // 份额 0 = 「这个入口不参与推算」(与编译器 optionalPercent 的口径一致), ⛔ 不推成一个 0 kg
            if (e.getKey().equals(filledPortId) || e.getValue() == null || e.getValue().signum() <= 0) continue;
            out.put(e.getKey(), filledQty.multiply(e.getValue()).divide(base, 6, RoundingMode.HALF_UP));
        }
        return out;
    }
}
