package com.cretas.aims.service.yield.costview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品 → 工单 汇总与同产品加权单位成本 (纯函数)。
 *
 * <p><b>口径名 {@value #BASIS}</b>: 单位成本 = (材料费 + 人工费) ÷ 良品产出, 不含辅料/包材/设备/其他/半成品、
 * 不扣副产回收。这是统一原型的口径, ⛔ 与现有「总成本 ÷ 箱数」(perBoxCost) 是两套并存的数字, 互不替换
 * (统一口径待 Steve 裁决)。同产品且同单位才汇总, 不同单位分组展示、不换算; 产品级 = Σ(材料+人工) ÷ Σ良品
 * (加权, 不是单价平均)。不可核算 (材料/人工未知或为负、良品 ≤ 0、无单位、成本合计 ≤ 0) 的工单不进加权, 列出原因。
 */
public final class ProductUnitCostAggregator {

    public static final String BASIS = "MATERIAL_PLUS_LABOR_PER_GOOD_OUTPUT";
    private static final int SCALE = 4;

    public record OrderPoint(String orderId, String orderNumber, String productKey, String productName,
                             String unit, BigDecimal material, BigDecimal labor, BigDecimal goodQuantity) {
    }

    public record OrderLine(String orderId, String orderNumber, BigDecimal material, BigDecimal labor,
                            BigDecimal goodQuantity, BigDecimal unitCost, String ineligibleReason) {
    }

    public record Group(String productKey, String productName, String unit, String basis,
                        BigDecimal sumMaterial, BigDecimal sumLabor, BigDecimal sumGood,
                        BigDecimal weightedUnitCost, int eligibleCount, List<OrderLine> orders,
                        List<CostSignalEvaluator.Signal> signals) {
    }

    private ProductUnitCostAggregator() {
    }

    public static List<Group> aggregate(List<OrderPoint> points) {
        Map<String, List<OrderPoint>> grouped = new LinkedHashMap<>();
        for (OrderPoint p : points) {
            String key = p.productKey() + "\u0000" + (p.unit() == null ? "" : p.unit().trim());
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(p);
        }
        List<Group> out = new ArrayList<>();
        for (List<OrderPoint> list : grouped.values()) {
            OrderPoint first = list.get(0);
            BigDecimal sm = BigDecimal.ZERO;
            BigDecimal sl = BigDecimal.ZERO;
            BigDecimal sg = BigDecimal.ZERO;
            List<OrderLine> lines = new ArrayList<>();
            List<CostSignalEvaluator.Sample> samples = new ArrayList<>();
            int eligible = 0;
            for (OrderPoint p : list) {
                String reason = ineligible(p);
                if (reason != null) {
                    lines.add(new OrderLine(p.orderId(), p.orderNumber(), p.material(), p.labor(),
                            p.goodQuantity(), null, reason));
                    continue;
                }
                BigDecimal direct = p.material().add(p.labor());
                BigDecimal unitCost = direct.divide(p.goodQuantity(), SCALE, RoundingMode.HALF_UP);
                sm = sm.add(p.material());
                sl = sl.add(p.labor());
                sg = sg.add(p.goodQuantity());
                eligible++;
                lines.add(new OrderLine(p.orderId(), p.orderNumber(), p.material(), p.labor(),
                        p.goodQuantity(), unitCost, null));
                samples.add(new CostSignalEvaluator.Sample(p.orderId(), p.orderNumber(), p.material(),
                        p.labor(), unitCost));
            }
            BigDecimal weighted = eligible > 0
                    ? sm.add(sl).divide(sg, SCALE, RoundingMode.HALF_UP) : null;
            out.add(new Group(first.productKey(), first.productName(), first.unit(), BASIS, sm, sl, sg,
                    weighted, eligible, lines, CostSignalEvaluator.evaluate(samples)));
        }
        return out;
    }

    /** null = 可核算, 否则是不进加权的原因码。 */
    static String ineligible(OrderPoint p) {
        if (p.unit() == null || p.unit().isBlank()) return "NO_UNIT";
        if (p.material() == null) return "MATERIAL_UNKNOWN";
        if (p.labor() == null) return "LABOR_UNKNOWN";
        if (p.material().signum() < 0 || p.labor().signum() < 0) return "NEGATIVE_COST";
        if (p.goodQuantity() == null || p.goodQuantity().signum() <= 0) return "NO_POSITIVE_GOOD_OUTPUT";
        if (p.material().add(p.labor()).signum() <= 0) return "NON_POSITIVE_COST";
        return null;
    }
}
