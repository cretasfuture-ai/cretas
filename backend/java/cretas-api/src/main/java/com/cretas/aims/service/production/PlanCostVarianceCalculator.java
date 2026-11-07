package com.cretas.aims.service.production;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.entity.production.PlanStandardCostLine;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 计划成本差异的纯计算 (食品 DAG 缺口 09)。不碰数据库, 输入是快照行 + 实际领用汇总 + 产出。
 *
 * <h3>拆法 (每个主料与它的替代料算一组, P = 主料, S = 替代料, sp = 开计划时单价, ap = 实际单价)</h3>
 * <pre>
 *   价格差   = Σ_m (ap_m − sp_m) × aq_m                    —— 同样的料, 买贵了多少
 *   替代料差 = Σ_S aq_S × (sp_S − sp_P)                    —— 用替代料顶主料, 按开计划时的价差了多少
 *   用量差   = (Σ_组内 aq − 标准用量 sq_P) × sp_P            —— 多用/少用了多少 (按主料快照价)
 *   人工差   = 实际人工 − 标准人工 (元/kg × 成品 kg)
 *   ⇒ 价格差 + 替代料差 + 用量差 = 组实际金额 − sq_P × sp_P   (恒等, 测试钉着)
 * </pre>
 *
 * <p>任何一个因子缺 (快照没价、实际没价、算不出标准用量、单位换不过去) 那一组就是「无法比较」或「部分可比」,
 * ⛔ 不按 0 计入合计 —— 合计只加完全可比的组, 所以分项之和恒等于合计差。</p>
 */
public final class PlanCostVarianceCalculator {

    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final int MONEY = 2;
    private static final int QTY = 4;

    /** qty 从 from 单位换到 to 单位; 换不了返回 null。 */
    @FunctionalInterface
    public interface UnitConverter {
        BigDecimal convert(BigDecimal quantity, String fromUnit, String toUnit);
    }

    /**
     * 一个物料在这张计划里的实际领用汇总。
     *
     * @param amount   金额; 有任何一笔没价 → null
     * @param unitMixed 同一物料多笔领用单位互相换不过去 → true (数量不可用)
     */
    public record ActualUsage(String materialTypeId, String materialName, BigDecimal quantity, String unit,
                              BigDecimal amount, boolean unitMixed) {
    }

    public record Inputs(List<PlanStandardCostLine> lines,
                         Map<String, ActualUsage> actualByMaterial,
                         BigDecimal outputKg,
                         BigDecimal outputQuantityInPlanUnit,
                         BigDecimal actualLaborCost,
                         boolean multiOutput,
                         UnitConverter converter) {
    }

    /** 结果: 行明细 + 只含完全可比组的合计。 */
    public record Result(List<PlanCostVarianceDTO.Line> lines, BigDecimal standardCost, BigDecimal actualCost,
                         BigDecimal priceVariance, BigDecimal usageVariance, BigDecimal substituteVariance,
                         /** null = 人工无法比较 (没有标准人工 / 成品折不出 kg / 实际人工未知)。 */
                         BigDecimal laborVariance, int comparableCount, int incomparableCount, List<String> notes) {
        public boolean comparable() {
            return comparableCount > 0;
        }

        public BigDecimal totalVariance() {
            return comparable() ? actualCost.subtract(standardCost) : null;
        }

        public BigDecimal varianceRate() {
            return comparable() && standardCost.signum() > 0
                    ? totalVariance().multiply(HUNDRED).divide(standardCost, 2, RoundingMode.HALF_UP) : null;
        }
    }

    private PlanCostVarianceCalculator() {
    }

    public static Result compute(Inputs in) {
        List<String> notes = new ArrayList<>();
        BigDecimal stdRawInputKg = standardRawInputKg(in, notes);
        Map<String, BigDecimal> rawShares = rawShares(in.lines());

        BigDecimal std = BigDecimal.ZERO;
        BigDecimal act = BigDecimal.ZERO;
        BigDecimal price = BigDecimal.ZERO;
        BigDecimal usage = BigDecimal.ZERO;
        BigDecimal subst = BigDecimal.ZERO;
        BigDecimal labor = BigDecimal.ZERO;
        int comparable = 0;
        int incomparable = 0;
        List<PlanCostVarianceDTO.Line> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (PlanStandardCostLine line : in.lines()) {
            if (!PlanStandardCostLine.TYPE_MATERIAL.equals(line.getLineType())) {
                continue;
            }
            seen.add(line.getMaterialTypeId());
            List<PlanStandardCostLine> substitutes = in.lines().stream()
                    .filter(s -> PlanStandardCostLine.TYPE_SUBSTITUTE.equals(s.getLineType())
                            && line.getMaterialTypeId().equals(s.getParentMaterialTypeId()))
                    .toList();
            substitutes.forEach(s -> seen.add(s.getMaterialTypeId()));
            StandardQty sq = standardQuantity(in, line, stdRawInputKg, rawShares);
            boolean used = in.actualByMaterial().containsKey(line.getMaterialTypeId())
                    || substitutes.stream().anyMatch(s -> in.actualByMaterial().containsKey(s.getMaterialTypeId()));
            if (!used && PlanStandardCostLine.BASIS_NONE.equals(line.getStandardBasis())) {
                continue; // 配方只登记了资格 (没有标准用量) 且本计划没领用: 不算一项
            }
            Group group = group(in, line, substitutes, sq);
            out.addAll(group.lines());
            if (group.fullyComparable()) {
                comparable++;
                std = std.add(group.standardAmount());
                act = act.add(group.actualAmount());
                price = price.add(group.price());
                usage = usage.add(group.usage());
                subst = subst.add(group.substitute());
            } else {
                incomparable++;
            }
        }

        // 计划外物料: 开计划时配方里没有, 没有快照单价 → 无法比较 (列出实际金额)
        for (ActualUsage usageRow : in.actualByMaterial().values()) {
            if (seen.contains(usageRow.materialTypeId())) {
                continue;
            }
            incomparable++;
            out.add(PlanCostVarianceDTO.Line.builder()
                    .lineType("UNPLANNED")
                    .materialTypeId(usageRow.materialTypeId())
                    .materialName(usageRow.materialName())
                    .actualQuantity(scale(usageRow.quantity(), QTY))
                    .priceUnit(usageRow.unit())
                    .actualAmount(scale(usageRow.amount(), MONEY))
                    .status(PlanCostVarianceDTO.STATUS_INCOMPARABLE)
                    .reasons(new ArrayList<>(List.of("开计划时配方里没有这个物料, 没有单价快照")))
                    .build());
        }

        // 人工
        PlanStandardCostLine laborLine = in.lines().stream()
                .filter(l -> PlanStandardCostLine.TYPE_LABOR.equals(l.getLineType())).findFirst().orElse(null);
        PlanCostVarianceDTO.Line laborOut = PlanCostVarianceDTO.Line.builder().lineType("LABOR").materialName("人工")
                .priceUnit("kg").actualAmount(scale(in.actualLaborCost(), MONEY)).build();
        List<String> laborReasons = new ArrayList<>();
        if (laborLine == null || laborLine.getUnitPrice() == null) {
            laborReasons.add("开计划时没有标准人工 (研发报价任务没填 元/kg)");
        } else {
            laborOut.setSnapshotUnitPrice(laborLine.getUnitPrice());
        }
        if (in.outputKg() == null) {
            laborReasons.add("成品折不出 kg, 算不出标准人工");
        }
        if (in.actualLaborCost() == null) {
            laborReasons.add("实际人工成本未知");
        }
        boolean laborComparable = laborReasons.isEmpty();
        if (laborComparable) {
            BigDecimal stdLabor = in.outputKg().multiply(laborLine.getUnitPrice());
            BigDecimal var = in.actualLaborCost().subtract(stdLabor);
            laborOut.setStandardQuantity(scale(in.outputKg(), QTY));
            laborOut.setStandardAmount(scale(stdLabor, MONEY));
            laborOut.setLaborVariance(scale(var, MONEY));
            laborOut.setStatus(PlanCostVarianceDTO.STATUS_COMPARABLE);
            std = std.add(stdLabor);
            act = act.add(in.actualLaborCost());
            labor = labor.add(var);
            comparable++;
        } else {
            laborOut.setStatus(PlanCostVarianceDTO.STATUS_INCOMPARABLE);
            laborOut.setReasons(laborReasons);
            incomparable++;
        }
        out.add(laborOut);

        return new Result(out, scale(std, MONEY), scale(act, MONEY), scale(price, MONEY), scale(usage, MONEY),
                // 人工不可比 → 人工差是 null (⛔ 不是 0: 「人工差 ¥0.00」读起来是「人工和标准一样」)
                scale(subst, MONEY), laborComparable ? scale(labor, MONEY) : null, comparable, incomparable, notes);
    }

    private record Group(List<PlanCostVarianceDTO.Line> lines, boolean fullyComparable, BigDecimal standardAmount,
                         BigDecimal actualAmount, BigDecimal price, BigDecimal usage, BigDecimal substitute) {
    }

    private static Group group(Inputs in, PlanStandardCostLine primary, List<PlanStandardCostLine> substitutes,
                               StandardQty sq) {
        List<PlanCostVarianceDTO.Line> lines = new ArrayList<>();
        boolean ok = true;
        BigDecimal priceVar = BigDecimal.ZERO;
        BigDecimal substVar = BigDecimal.ZERO;
        BigDecimal actualAmount = BigDecimal.ZERO;
        BigDecimal groupQtyInPrimaryUnit = BigDecimal.ZERO;
        boolean groupQtyKnown = true;
        BigDecimal spP = primary.getUnitPrice();

        boolean anySubstituteUsed = substitutes.stream()
                .anyMatch(sub -> in.actualByMaterial().containsKey(sub.getMaterialTypeId()));
        List<PlanStandardCostLine> members = new ArrayList<>();
        members.add(primary);
        members.addAll(substitutes);
        for (PlanStandardCostLine member : members) {
            boolean isPrimary = member == primary;
            ActualUsage actual = in.actualByMaterial().get(member.getMaterialTypeId());
            if (!isPrimary && actual == null) {
                continue; // 没用替代料: 不列
            }
            List<String> reasons = new ArrayList<>();
            PlanCostVarianceDTO.Line out = PlanCostVarianceDTO.Line.builder()
                    .lineType(member.getLineType())
                    .materialTypeId(member.getMaterialTypeId())
                    .materialName(member.getMaterialName())
                    .materialCategory(member.getMaterialCategory())
                    .parentMaterialTypeId(member.getParentMaterialTypeId())
                    .priceUnit(member.getPriceUnit())
                    .snapshotUnitPrice(member.getUnitPrice())
                    .build();
            if (member.getUnitPrice() == null) {
                reasons.add("开计划时物料档案没有单价");
            }
            BigDecimal aq;
            if (actual == null && anySubstituteUsed) {
                aq = BigDecimal.ZERO; // 主料一点没领、全用替代料顶: 主料 0 是事实
            } else if (actual == null) {
                // 没有领用记录 ≠ 用了 0 (调料常按配比估算、不逐料记): 不知道就说不知道, ⛔ 不当 0 算出「节约」
                aq = null;
                reasons.add("本计划没有这项的实际领用记录");
            } else if (actual.unitMixed() || actual.quantity() == null) {
                aq = null;
                reasons.add("领用记录单位不一致, 数量合不起来");
            } else {
                aq = member.getPriceUnit() == null ? null
                        : in.converter().convert(actual.quantity(), actual.unit(), member.getPriceUnit());
                if (aq == null) {
                    reasons.add("领用单位 " + actual.unit() + " 换算不到单价单位 " + member.getPriceUnit());
                }
            }
            BigDecimal amount = actual == null ? (aq == null ? null : BigDecimal.ZERO) : actual.amount();
            if (actual != null && amount == null) {
                reasons.add("有领用批次没有实际单价");
            }
            out.setActualQuantity(scale(aq, QTY));
            out.setActualAmount(scale(amount, MONEY));
            if (aq != null && amount != null && aq.signum() > 0) {
                out.setActualUnitPrice(amount.divide(aq, 4, RoundingMode.HALF_UP));
            }
            // 价格差
            if (member.getUnitPrice() != null && aq != null && amount != null) {
                BigDecimal pv = amount.subtract(aq.multiply(member.getUnitPrice()));
                out.setPriceVariance(scale(pv, MONEY));
                priceVar = priceVar.add(pv);
            } else {
                ok = false;
            }
            if (amount != null) {
                actualAmount = actualAmount.add(amount);
            }
            // 替代料差
            if (!isPrimary) {
                if (member.getUnitPrice() != null && spP != null && aq != null) {
                    BigDecimal sv = aq.multiply(member.getUnitPrice().subtract(spP));
                    out.setSubstituteVariance(scale(sv, MONEY));
                    substVar = substVar.add(sv);
                } else {
                    ok = false;
                    if (spP == null) reasons.add("主料开计划时没有单价, 替代料差无法比较");
                }
            }
            // 组内数量折到主料单价单位
            BigDecimal inPrimaryUnit = aq == null ? null
                    : isPrimary ? aq : in.converter().convert(aq, member.getPriceUnit(), primary.getPriceUnit());
            if (inPrimaryUnit == null) {
                groupQtyKnown = false;
                if (!isPrimary && aq != null) reasons.add("替代料单位换算不到主料单位, 用量差无法比较");
            } else {
                groupQtyInPrimaryUnit = groupQtyInPrimaryUnit.add(inPrimaryUnit);
            }
            out.setReasons(reasons);
            lines.add(out);
        }

        PlanCostVarianceDTO.Line primaryOut = lines.getFirst();
        BigDecimal usageVar = null;
        BigDecimal stdAmount = null;
        if (sq.quantity() == null) {
            primaryOut.getReasons().add(sq.reason());
            ok = false;
        } else {
            primaryOut.setStandardQuantity(scale(sq.quantity(), QTY));
            if (spP != null) {
                stdAmount = sq.quantity().multiply(spP);
                primaryOut.setStandardAmount(scale(stdAmount, MONEY));
            }
            if (spP != null && groupQtyKnown) {
                usageVar = groupQtyInPrimaryUnit.subtract(sq.quantity()).multiply(spP);
                primaryOut.setUsageVariance(scale(usageVar, MONEY));
            } else {
                ok = false;
            }
        }
        boolean fully = ok && usageVar != null && stdAmount != null;
        for (PlanCostVarianceDTO.Line line : lines) {
            boolean anyNumber = line.getPriceVariance() != null || line.getUsageVariance() != null
                    || line.getSubstituteVariance() != null;
            line.setStatus(fully ? PlanCostVarianceDTO.STATUS_COMPARABLE
                    : anyNumber ? PlanCostVarianceDTO.STATUS_PARTIAL : PlanCostVarianceDTO.STATUS_INCOMPARABLE);
        }
        return new Group(lines, fully, stdAmount, actualAmount, priceVar, usageVar, substVar);
    }

    /** 影响分析复用: 一行快照在给定产出下的标准用量 (单位 = 该行单价单位); 算不出时 reason 说明原因, 不猜。 */
    public record PlanQty(BigDecimal quantity, String reason) {
    }

    public static PlanQty planStandardQuantity(List<PlanStandardCostLine> lines, PlanStandardCostLine line,
                                               BigDecimal outputKg, BigDecimal outputQty, boolean multiOutput,
                                               UnitConverter converter) {
        Inputs in = new Inputs(lines, Map.of(), outputKg, outputQty, null, multiOutput, converter);
        StandardQty q = standardQuantity(in, line, standardRawInputKg(in, new ArrayList<>()), rawShares(lines));
        return new PlanQty(q.quantity(), q.reason());
    }

    private record StandardQty(BigDecimal quantity, String reason) {
    }

    /** 标准用量, 单位折到这一行的单价单位。 */
    private static StandardQty standardQuantity(Inputs in, PlanStandardCostLine line, BigDecimal stdRawInputKg,
                                                Map<String, BigDecimal> rawShares) {
        MaterialQuantity raw = materialQuantity(in, line, stdRawInputKg, rawShares);
        if (raw.quantity() == null) return new StandardQty(null, raw.reason());
        if (line.getPriceUnit() == null) {
            return new StandardQty(null, "物料档案没有计量单位");
        }
        BigDecimal converted = in.converter().convert(raw.quantity(), raw.unit(), line.getPriceUnit());
        return converted == null
                ? new StandardQty(null, "标准用量单位 " + raw.unit() + " 换算不到单价单位 " + line.getPriceUnit())
                : new StandardQty(converted, null);
    }

    /** Quantity-only view of the same frozen standard; does not require or return prices. */
    public record MaterialQuantity(String materialTypeId, BigDecimal quantity, String unit, String reason) { }

    public static List<MaterialQuantity> standardMaterialQuantities(List<PlanStandardCostLine> lines,
            BigDecimal outputQuantity, BigDecimal outputKg, boolean multiOutput) {
        Inputs input = new Inputs(lines, Map.of(), outputKg, outputQuantity, null, multiOutput,
                (quantity, from, to) -> null);
        BigDecimal rawKg = standardRawInputKg(input, new ArrayList<>());
        Map<String, BigDecimal> shares = rawShares(lines);
        return lines.stream().filter(line -> PlanStandardCostLine.TYPE_MATERIAL.equals(line.getLineType()))
                .map(line -> materialQuantity(input, line, rawKg, shares)).toList();
    }

    private static MaterialQuantity materialQuantity(Inputs in, PlanStandardCostLine line,
            BigDecimal stdRawInputKg, Map<String, BigDecimal> rawShares) {
        String basis = line.getStandardBasis();
        BigDecimal qty;
        String unit;
        switch (basis == null ? PlanStandardCostLine.BASIS_NONE : basis) {
            case PlanStandardCostLine.BASIS_RAW_INPUT_SHARE -> {
                if (stdRawInputKg == null) {
                    return new MaterialQuantity(line.getMaterialTypeId(), null, null, standardRawInputReason(in, line));
                }
                BigDecimal share = rawShares.get(line.getMaterialTypeId());
                if (share == null) {
                    return new MaterialQuantity(line.getMaterialTypeId(), null, null, "多个主料但工艺上没配齐投入份额, 算不出各主料标准用量");
                }
                qty = stdRawInputKg.multiply(share);
                unit = "kg";
            }
            case PlanStandardCostLine.BASIS_PER_RAW_INPUT_KG -> {
                if (stdRawInputKg == null || line.getStandardRate() == null) {
                    return new MaterialQuantity(line.getMaterialTypeId(), null, null, "算不出主料标准投入, 调料标准用量也算不出");
                }
                qty = stdRawInputKg.multiply(line.getStandardRate());
                unit = "kg";
            }
            case PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT -> {
                if (in.outputQuantityInPlanUnit() == null || line.getStandardRate() == null) {
                    return new MaterialQuantity(line.getMaterialTypeId(), null, null, "没有实际产量, 算不出标准用量");
                }
                qty = in.outputQuantityInPlanUnit().multiply(line.getStandardRate());
                unit = line.getStandardUnit();
            }
            default -> {
                return new MaterialQuantity(line.getMaterialTypeId(), null, null, "配方没有登记标准用量, 用量差无法比较");
            }
        }
        return new MaterialQuantity(line.getMaterialTypeId(), qty, unit, null);
    }

    private static String standardRawInputReason(Inputs in, PlanStandardCostLine line) {
        if (in.multiOutput()) return "多产出计划的主料标准投入不能按单一出成率推算";
        if (line.getRawYieldRate() == null || line.getRawYieldRate().signum() <= 0) return "锁定的配方没有出成率";
        return "成品折不出 kg, 算不出主料标准投入";
    }

    /** 主料标准投入 kg = 成品 kg ÷ (出成率 / 100)。多产出 / 没出成率 / 成品折不出 kg → null。 */
    private static BigDecimal standardRawInputKg(Inputs in, List<String> notes) {
        if (in.multiOutput() || in.outputKg() == null) {
            return null;
        }
        BigDecimal yield = in.lines().stream()
                .filter(l -> PlanStandardCostLine.BASIS_RAW_INPUT_SHARE.equals(l.getStandardBasis())
                        || PlanStandardCostLine.BASIS_PER_RAW_INPUT_KG.equals(l.getStandardBasis()))
                .map(PlanStandardCostLine::getRawYieldRate)
                .filter(y -> y != null && y.signum() > 0)
                .findFirst().orElse(null);
        if (yield == null) {
            return null;
        }
        return in.outputKg().multiply(HUNDRED).divide(yield, 8, RoundingMode.HALF_UP);
    }

    /** materialTypeId → 份额占比 (0..1)。只有一个主料且没填份额 → 1; 多个主料必须全有份额。 */
    private static Map<String, BigDecimal> rawShares(List<PlanStandardCostLine> lines) {
        List<PlanStandardCostLine> raws = lines.stream()
                .filter(l -> PlanStandardCostLine.TYPE_MATERIAL.equals(l.getLineType())
                        && PlanStandardCostLine.BASIS_RAW_INPUT_SHARE.equals(l.getStandardBasis()))
                .toList();
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        if (raws.size() == 1) {
            out.put(raws.getFirst().getMaterialTypeId(), BigDecimal.ONE);
            return out;
        }
        if (raws.isEmpty() || raws.stream().anyMatch(l -> l.getStandardRate() == null || l.getStandardRate().signum() <= 0)) {
            return out;
        }
        BigDecimal total = raws.stream().map(PlanStandardCostLine::getStandardRate).reduce(BigDecimal.ZERO, BigDecimal::add);
        raws.forEach(l -> out.put(l.getMaterialTypeId(), l.getStandardRate().divide(total, 12, RoundingMode.HALF_UP)));
        return out;
    }

    private static BigDecimal scale(BigDecimal value, int digits) {
        return value == null ? null : value.setScale(digits, RoundingMode.HALF_UP);
    }
}
