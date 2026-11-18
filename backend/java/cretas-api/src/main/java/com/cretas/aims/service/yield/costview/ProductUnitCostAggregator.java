package com.cretas.aims.service.yield.costview;

import com.cretas.aims.service.unit.impl.UnitContractServiceImpl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 产品 → 工单 汇总与同产品加权单位成本 (纯函数)。
 *
 * <p><b>口径 {@value #BASIS} (中文名 {@value #BASIS_LABEL})</b>: 单位成本 = (材料费 + 人工费) ÷ 末道产出,
 * 不含辅料/包材/设备/其他/半成品、不扣副产回收。⛔ 分母是上游 outputQuantity (末道总产出), 不是严格良品;
 * 与现有「全成本口径」(总成本 ÷ 箱数, perBoxCost) 是两套并存的数字, 互不替换 (Steve 2026-11-18 裁定并存、标名)。
 * 同产品且同单位 (走权威单位归一) 才汇总, 不同单位分组不换算; 产品级 = Σ(材料+人工) ÷ Σ产出 (加权)。
 *
 * <p>不可核算的工单不进加权、不进信号样本, 列出原因码: 缺价/缺人工 (带缺项码, 金额被当 0 的情形)、
 * 无人工明细且人工为 0、客供料 (材料恒偏低)、产品混合或未知、无单位、被价格脱敏、产出 ≤ 0 等。
 * 没有可核算工单的组, 所有合计置 null, ⛔ 不显示 0。
 *
 * <p>字段命名: 金额字段一律带 cost (materialCost / laborCost / unitCost ...), 这样工艺保密响应裁剪器
 * 的 isCostTotalField 能识别; 并输出 productTypeId 供其认归属。
 */
public final class ProductUnitCostAggregator {

    public static final String BASIS = "MATERIAL_PLUS_LABOR_PER_OUTPUT";
    public static final String BASIS_LABEL = "工单材料人工口径";
    private static final int SCALE = 4;

    public record OrderPoint(String orderId, String orderNumber, String productTypeId, String productName,
                             String unit, BigDecimal materialCost, BigDecimal laborCost, BigDecimal outputQuantity,
                             List<String> missingCostItems, boolean hasLaborDetails, boolean customerSupplied,
                             boolean priceMasked, boolean rawPriceGap, boolean laborAmountGap) {
    }

    public record OrderLine(String orderId, String orderNumber, BigDecimal materialCost, BigDecimal laborCost,
                            BigDecimal outputQuantity, BigDecimal unitCost, String ineligibleReason) {
    }

    public record Group(String productTypeId, String productName, String unit, String basis, String basisLabel,
                        BigDecimal sumMaterialCost, BigDecimal sumLaborCost, BigDecimal sumOutputQuantity,
                        BigDecimal weightedUnitCost, int eligibleCount, boolean priceMasked, boolean secretMasked,
                        List<OrderLine> orders, List<CostSignalEvaluator.Signal> signals) {

        /** 工艺保密: 无权者看不到该产品的任何金额, 只留单号和「已保密」标记。 */
        public Group maskedForSecret() {
            List<OrderLine> lines = new ArrayList<>();
            for (OrderLine l : orders) {
                lines.add(new OrderLine(l.orderId(), l.orderNumber(), null, null, null, null, l.ineligibleReason()));
            }
            // 信号代码 (材料占比低/人工占比高/离散) 本身就是配比的粗粒度信息, 保密产品被遮后一并不给
            return new Group(productTypeId, productName, unit, basis, basisLabel, null, null, null, null,
                    eligibleCount, priceMasked, true, lines, List.of());
        }
    }

    private ProductUnitCostAggregator() {
    }

    public static List<Group> aggregate(List<OrderPoint> points) {
        Map<String, List<OrderPoint>> grouped = new LinkedHashMap<>();
        for (OrderPoint p : points) {
            String key = p.productTypeId() + "\u0000" + normalizeUnit(p.unit());
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
            boolean masked = false;
            for (OrderPoint p : list) {
                masked |= p.priceMasked();
                String reason = ineligible(p);
                if (reason != null) {
                    // 价格脱敏的行主动置 null (纵深防御: 不依赖上游 maskCosts 一个字段都不漏)
                    boolean m = p.priceMasked();
                    lines.add(new OrderLine(p.orderId(), p.orderNumber(), m ? null : p.materialCost(),
                            m ? null : p.laborCost(), m ? null : p.outputQuantity(), null, reason));
                    continue;
                }
                BigDecimal direct = p.materialCost().add(p.laborCost());
                BigDecimal unitCost = direct.divide(p.outputQuantity(), SCALE, RoundingMode.HALF_UP);
                sm = sm.add(p.materialCost());
                sl = sl.add(p.laborCost());
                sg = sg.add(p.outputQuantity());
                eligible++;
                lines.add(new OrderLine(p.orderId(), p.orderNumber(), p.materialCost(), p.laborCost(),
                        p.outputQuantity(), unitCost, null));
                samples.add(new CostSignalEvaluator.Sample(p.orderId(), p.orderNumber(), p.materialCost(),
                        p.laborCost(), unitCost));
            }
            boolean any = eligible > 0;
            out.add(new Group(first.productTypeId(), first.productName(), first.unit(), BASIS, BASIS_LABEL,
                    any ? sm : null, any ? sl : null, any ? sg : null,
                    any ? sm.add(sl).divide(sg, SCALE, RoundingMode.HALF_UP) : null,
                    eligible, masked, false, lines, CostSignalEvaluator.evaluate(samples)));
        }
        return out;
    }

    /** 与上游 OrderCostBreakdownService.normalizeUnit 同一个权威归一; 空单位归为空串。 */
    static String normalizeUnit(String unit) {
        if (unit == null || unit.isBlank()) return "";
        String code = UnitContractServiceImpl.crossLanguageCode(unit.trim());
        return code == null ? unit.trim() : code;
    }

    /** null = 可核算, 否则是不进加权的原因码。 */
    static String ineligible(OrderPoint p) {
        if (p.priceMasked()) return "PRICE_MASKED";
        if (p.productTypeId() == null || p.productTypeId().isBlank()) return "PRODUCT_MIXED_OR_UNKNOWN";
        if (p.unit() == null || p.unit().isBlank()) return "NO_UNIT";
        if (p.customerSupplied()) return "CUSTOMER_SUPPLIED_MATERIAL";
        if (p.materialCost() == null) return "MATERIAL_UNKNOWN";
        if (p.laborCost() == null) return "LABOR_UNKNOWN";
        if (hasMissing(p, "RAW_MATERIAL")) return "MATERIAL_PRICE_MISSING";
        if (hasMissing(p, "LABOR_COST_UNKNOWN") || hasMissing(p, "LABOR_RATE_OR_TIME")) return "LABOR_UNKNOWN";
        // 非正式计划 (没选 BOM/工作流/净重) 上游不写缺项码, 只把明细金额置 null → 以明细为准
        if (p.rawPriceGap()) return "MATERIAL_PRICE_MISSING";
        if (p.laborAmountGap()) return "LABOR_UNKNOWN";
        if (p.laborCost().signum() == 0 && !p.hasLaborDetails()) return "LABOR_UNKNOWN";
        if (p.materialCost().signum() < 0 || p.laborCost().signum() < 0) return "NEGATIVE_COST";
        if (p.outputQuantity() == null || p.outputQuantity().signum() <= 0) return "NO_POSITIVE_OUTPUT";
        if (p.materialCost().add(p.laborCost()).signum() <= 0) return "NON_POSITIVE_COST";
        return null;
    }

    private static boolean hasMissing(OrderPoint p, String prefix) {
        if (p.missingCostItems() == null) return false;
        for (String m : p.missingCostItems()) {
            if (m != null && m.startsWith(prefix)) return true;
        }
        return false;
    }
}
