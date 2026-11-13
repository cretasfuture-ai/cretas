package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.shortage.CostDeviationCaseDetail;
import com.cretas.aims.entity.shortage.ShortageCase;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 异常说明分两栏: 已知事实 / 可能原因。
 *
 * <p>已知事实: 每个数字都来自程序计算 (读数、阈值、证据行), 每条带「去哪张单据核对」; 不含任何推测。
 * 可能原因: 固定的核查假设清单, 明确标注「待核实, 系统没有验证」, ⛔ 不带数字、不下结论。
 * 金额按采购价权限脱敏 (mask=true 时不出现)。
 */
public final class CostDeviationExplanation {

    public record Fact(String text, String source) {
    }

    public record Explanation(List<Fact> knownFacts, List<String> possibleCauses, String causeDisclaimer) {
    }

    public static final String DISCLAIMER = "以下是常见原因, 系统没有验证, 需要负责人逐项核实后给出结论";

    private CostDeviationExplanation() {
    }

    public static Explanation build(ShortageCase.SourceType type, CostDeviationCaseDetail d, PlanCostVarianceDTO variance,
                                    MaterialBalanceTableDTO.PlanBlock balance, boolean mask) {
        List<Fact> facts = new ArrayList<>();
        String plan = "生产计划 " + (d.getPlanNumber() != null ? d.getPlanNumber() : d.getPlanId());
        boolean cost = type == ShortageCase.SourceType.COST_VARIANCE;
        facts.add(new Fact((cost ? "成本差异率绝对值 " : "最大工序未解释差额占比 ") + plain(d.getMetricPct())
                + "%, 阈值 " + plain(d.getThresholdPct()) + "%", plan));
        if (cost && !mask && d.getAmountVariance() != null) {
            facts.add(new Fact("成本差异金额合计 " + plain(d.getAmountVariance()) + " 元", plan + " 成本差异"));
        }
        if (d.getUnknownItemCount() > 0) {
            facts.add(new Fact("另有 " + d.getUnknownItemCount() + (cost ? " 项" : " 道工序") + "算不出, 不在上述读数内",
                    plan));
        }
        if (cost && variance != null) {
            addVariance(facts, "价格差", variance.getPriceVariance(), mask, plan);
            addVariance(facts, "用量差", variance.getUsageVariance(), mask, plan);
            addVariance(facts, "替代料差", variance.getSubstituteVariance(), mask, plan);
            addVariance(facts, "人工差", variance.getLaborVariance(), mask, plan);
            addVariance(facts, "回料差", variance.getReworkVariance(), mask, plan);
        }
        if (!cost && balance != null && balance.getRows() != null) {
            for (MaterialBalanceTableDTO.Row r : balance.getRows()) {
                if (r.getUnexplainedPct() == null) continue;
                if (r.getUnexplainedPct().abs().compareTo(d.getThresholdPct()) <= 0) continue;
                facts.add(new Fact((r.getProcessName() == null ? "未命名工序" : r.getProcessName())
                        + ": 投入 " + plain(r.getInput()) + ", 产出 " + plain(r.getOutput()) + ", 副产 "
                        + plain(r.getByproducts()) + ", 已确认损耗 " + plain(r.getConfirmedLoss()) + ", 未解释差额 "
                        + plain(r.getUnexplained()) + " " + (r.getUnit() == null ? "" : r.getUnit()) + " ("
                        + plain(r.getUnexplainedPct().abs()) + "%)", plan + " 工序报工"));
            }
        }
        return new Explanation(facts, causes(type), DISCLAIMER);
    }

    private static void addVariance(List<Fact> facts, String label, BigDecimal v, boolean mask, String plan) {
        if (mask || v == null || v.signum() == 0) return;
        facts.add(new Fact(label + " " + plain(v) + " 元", plan + " 成本差异明细"));
    }

    static List<String> causes(ShortageCase.SourceType type) {
        if (type == ShortageCase.SourceType.MATERIAL_BALANCE) {
            return List.of("报工的投入量、产出量或单位填错", "损耗、副产、留样没有登记", "同一道工序有漏报或重复报工");
        }
        return List.of("入库单价和开计划时的单价快照不一致", "多领、错领或产出少报", "替代料没有经过批准", "工时或计件记录有误",
                "标准成本 (配方用量 / 出成率) 与实际工艺不符");
    }

    /** 站内信正文: 只放事实摘要, 不放金额 (通知对同岗位所有人可见)。 */
    public static String notificationBody(ShortageCase.SourceType type, CostDeviationCaseDetail d) {
        return (type == ShortageCase.SourceType.COST_VARIANCE ? "成本差异" : "物料平衡") + "异常: 生产计划 "
                + (d.getPlanNumber() != null ? d.getPlanNumber() : d.getPlanId())
                + ", 读数 " + plain(d.getMetricPct()) + "% (阈值 " + plain(d.getThresholdPct()) + "%)"
                + (d.getUnknownItemCount() > 0 ? ", 另有 " + d.getUnknownItemCount() + " 项算不出" : "")
                + "。请在「成本损耗异常」里核查并给出处理结果。";
    }

    private static String plain(BigDecimal v) {
        return v == null ? "未知" : v.stripTrailingZeros().toPlainString();
    }
}
