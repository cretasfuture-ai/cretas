package com.cretas.aims.service.yield.costview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 成本信号 (纯函数, 只提示不阻断), 同产品同单位的可核算工单为样本:
 * <ul>
 *   <li>UNIT_COST_SPREAD: 样本 ≥ 3 且 (最高 − 最低) ÷ 最低 ≥ 20%</li>
 *   <li>MATERIAL_SHARE_LOW: 材料占 (材料+人工) &lt; 10%</li>
 *   <li>LABOR_SHARE_HIGH: 人工占比 &gt; 65%</li>
 *   <li>LABOR_ZERO: 人工为 0</li>
 * </ul>
 * 边界: 离散 ≥ 20% 报; 材料占比 &lt; 10% 才报 (恰好 10% 不报); 人工 &gt; 65% 才报 (恰好 65% 不报)。
 * 占比信号逐单给出; 离散信号是产品级一条。{@code detail} 只写单号, 数值放 {@code value} 字段,
 * 这样工艺保密遮蔽能统一处理数值。与 CostDeviationScanService 的「计划成本差异/物料平衡」是两类信号。
 */
public final class CostSignalEvaluator {

    public static final int MIN_SAMPLES = 3;
    public static final BigDecimal SPREAD_THRESHOLD = new BigDecimal("0.20");
    public static final BigDecimal MATERIAL_SHARE_FLOOR = new BigDecimal("0.10");
    public static final BigDecimal LABOR_SHARE_CEIL = new BigDecimal("0.65");

    public record Sample(String orderId, String orderNumber, BigDecimal materialCost, BigDecimal laborCost,
                         BigDecimal unitCost) {
    }

    /** value: 离散率或占比 (0~1 的比例, 不是金额); 保密遮蔽时置 null。 */
    public record Signal(String code, String orderId, String orderNumber, BigDecimal value, String detail) {
    }

    private CostSignalEvaluator() {
    }

    public static List<Signal> evaluate(List<Sample> samples) {
        List<Signal> out = new ArrayList<>();
        if (samples == null || samples.isEmpty()) return out;
        if (samples.size() >= MIN_SAMPLES) {
            Sample max = samples.stream().max(Comparator.comparing(Sample::unitCost)).get();
            Sample min = samples.stream().min(Comparator.comparing(Sample::unitCost)).get();
            if (min.unitCost().signum() > 0) {
                BigDecimal spread = max.unitCost().subtract(min.unitCost())
                        .divide(min.unitCost(), 4, RoundingMode.HALF_UP);
                if (spread.compareTo(SPREAD_THRESHOLD) >= 0) {
                    out.add(new Signal("UNIT_COST_SPREAD", null, null, spread,
                            "最高 " + max.orderNumber() + " / 最低 " + min.orderNumber()));
                }
            }
        }
        for (Sample s : samples) {
            BigDecimal direct = s.materialCost().add(s.laborCost());
            if (direct.signum() <= 0) continue;
            if (s.laborCost().signum() == 0) {
                out.add(new Signal("LABOR_ZERO", s.orderId(), s.orderNumber(), BigDecimal.ZERO, "人工费为 0"));
            }
            BigDecimal matShare = s.materialCost().divide(direct, 4, RoundingMode.HALF_UP);
            BigDecimal labShare = s.laborCost().divide(direct, 4, RoundingMode.HALF_UP);
            if (matShare.compareTo(MATERIAL_SHARE_FLOOR) < 0) {
                out.add(new Signal("MATERIAL_SHARE_LOW", s.orderId(), s.orderNumber(), matShare, "材料占比低于 10%"));
            }
            if (labShare.compareTo(LABOR_SHARE_CEIL) > 0) {
                out.add(new Signal("LABOR_SHARE_HIGH", s.orderId(), s.orderNumber(), labShare, "人工占比高于 65%"));
            }
        }
        return out;
    }
}
