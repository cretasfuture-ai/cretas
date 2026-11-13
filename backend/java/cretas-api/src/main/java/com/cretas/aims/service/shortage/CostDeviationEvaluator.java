package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.shortage.ShortageCase;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 判定一张计划的成本差异 / 物料平衡读数是否超阈值。纯计算, 不碰库。
 *
 * <p>三种结论不合并: 超阈值、阈值内、{@link Outcome#UNKNOWN 算不出}。算不出 ⛔ 不按 0 判成阈值内。
 */
public final class CostDeviationEvaluator {

    public enum Outcome { DEVIATED, WITHIN, UNKNOWN }

    /**
     * @param metricPct    读数绝对值 (百分数); UNKNOWN 时 null
     * @param amount       差异金额; null = 金额未知
     * @param deviatedCount 偏离的工序数 (成本差异固定 1)
     * @param unknownItems 算不出的项数
     */
    public record Reading(ShortageCase.SourceType sourceType, String planId, String planNumber, String productName,
                          Outcome outcome, BigDecimal metricPct, BigDecimal thresholdPct, BigDecimal amount,
                          int deviatedCount, int unknownItems, String summary, String fingerprint) {
    }

    private CostDeviationEvaluator() {
    }

    public static Reading evaluateCost(PlanCostVarianceDTO v, BigDecimal thresholdPct) {
        int unknown = v.getIncomparableItemCount();
        if (!v.isComparable() || v.getVarianceRate() == null) {
            String why = v.getComparabilityReason() != null ? v.getComparabilityReason() : "标准成本为 0, 算不出差异率";
            return reading(ShortageCase.SourceType.COST_VARIANCE, v.getPlanId(), v.getPlanNumber(), v.getProductName(),
                    Outcome.UNKNOWN, null, thresholdPct, null, 0, unknown, why);
        }
        BigDecimal rate = v.getVarianceRate().abs();
        boolean over = rate.compareTo(thresholdPct) > 0;
        String direction = v.getVarianceRate().signum() >= 0 ? "超支" : "节约";
        String summary = "成本差异率 " + direction + " " + plain(rate) + "% (阈值 " + plain(thresholdPct) + "%)"
                + (unknown > 0 ? ", 另有 " + unknown + " 项无法比较, 不在合计内" : "");
        return reading(ShortageCase.SourceType.COST_VARIANCE, v.getPlanId(), v.getPlanNumber(), v.getProductName(),
                over ? Outcome.DEVIATED : Outcome.WITHIN, rate, thresholdPct, v.getTotalVariance(), over ? 1 : 0,
                unknown, summary);
    }

    public static Reading evaluateBalance(MaterialBalanceTableDTO.PlanBlock block, BigDecimal thresholdPct) {
        BigDecimal max = null;
        int unknown = 0;
        List<String> over = new ArrayList<>();
        for (MaterialBalanceTableDTO.Row r : block.getRows()) {
            BigDecimal pct = r.getUnexplainedPct();
            if (pct == null) {
                unknown++;
                continue;
            }
            BigDecimal abs = pct.abs();
            if (max == null || abs.compareTo(max) > 0) max = abs;
            if (abs.compareTo(thresholdPct) > 0) over.add((r.getProcessName() == null ? "未命名工序" : r.getProcessName()) + " " + plain(abs) + "%");
        }
        if (max == null) {
            return reading(ShortageCase.SourceType.MATERIAL_BALANCE, block.getPlanId(), block.getPlanNumber(),
                    block.getProductName(), Outcome.UNKNOWN, null, thresholdPct, null, 0, unknown,
                    "所有工序都算不出未解释差额 (单位不可比或没登记投入)");
        }
        String summary = over.isEmpty()
                ? "最大未解释差额 " + plain(max) + "%, 在阈值 " + plain(thresholdPct) + "% 内"
                : "未解释差额超阈值 " + plain(thresholdPct) + "%: " + String.join("、", over);
        if (unknown > 0) summary += ", 另有 " + unknown + " 道工序算不出";
        return reading(ShortageCase.SourceType.MATERIAL_BALANCE, block.getPlanId(), block.getPlanNumber(),
                displayProduct(block.getProductName()), over.isEmpty() ? Outcome.WITHIN : Outcome.DEVIATED, max, thresholdPct, null,
                over.size(), unknown, summary);
    }

    /** 系统内部占位产品 (__RAW_WORKFLOW_OWNER__…) 不给人看。 */
    private static String displayProduct(String name) {
        return name != null && name.startsWith("__") ? null : name;
    }

    private static Reading reading(ShortageCase.SourceType type, String planId, String planNumber, String productName,
                                   Outcome outcome, BigDecimal metric, BigDecimal threshold, BigDecimal amount,
                                   int deviated, int unknown, String summary) {
        // 指纹不含阈值: 改阈值不等于事实变了, 已下结论的实例不因此重开
        String fp = sha256(type + "|" + planId + "|" + plain(metric) + "|" + plain(amount) + "|" + deviated + "|" + unknown);
        // 不论哪条路径来的名字, 内部占位 (__RAW_WORKFLOW_OWNER__…) 一律不落库: 查不到真实产品就存 null
        return new Reading(type, planId, planNumber, displayProduct(productName), outcome, metric, threshold, amount,
                deviated, unknown, summary, fp);
    }

    private static String plain(BigDecimal v) {
        return v == null ? "" : v.stripTrailingZeros().toPlainString();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
