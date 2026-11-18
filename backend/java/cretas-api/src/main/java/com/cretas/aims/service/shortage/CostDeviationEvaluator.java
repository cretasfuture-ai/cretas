package com.cretas.aims.service.shortage;

import com.cretas.aims.dto.report.PlanCostVarianceDTO;
import com.cretas.aims.dto.yield.MaterialBalanceTableDTO;
import com.cretas.aims.entity.shortage.ShortageCase;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

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

    /**
     * 物料平衡行的单价 (元 / 该行单位)。price=null 时 reason 说明为什么定不了价, 不猜。
     * 由调用方用开计划时的主料单价快照 + 单位契约换算给出 (LSM-COSTBI 第 11 项)。
     */
    public record RowPrice(BigDecimal price, String basis, String reason) {
        public static RowPrice unknown(String reason) {
            return new RowPrice(null, null, reason);
        }
    }

    public static Reading evaluateBalance(MaterialBalanceTableDTO.PlanBlock block, BigDecimal thresholdPct) {
        return evaluateBalance(block, thresholdPct, null);
    }

    /**
     * @param pricer null = 不估金额 (旧行为, 金额未知); 否则超阈值工序的未解释差额 × 单价合计成差异金额,
     *               任何一道超阈值工序定不了价 ⇒ 金额仍未知, 并在摘要里说清原因。
     */
    public static Reading evaluateBalance(MaterialBalanceTableDTO.PlanBlock block, BigDecimal thresholdPct,
                                          Function<MaterialBalanceTableDTO.Row, RowPrice> pricer) {
        BigDecimal max = null;
        int unknown = 0;
        List<String> over = new ArrayList<>();
        List<MaterialBalanceTableDTO.Row> overRows = new ArrayList<>();
        for (MaterialBalanceTableDTO.Row r : block.getRows()) {
            BigDecimal pct = r.getUnexplainedPct();
            if (pct == null) {
                unknown++;
                continue;
            }
            BigDecimal abs = pct.abs();
            if (max == null || abs.compareTo(max) > 0) max = abs;
            if (abs.compareTo(thresholdPct) > 0) {
                over.add((r.getProcessName() == null ? "未命名工序" : r.getProcessName()) + " " + plain(abs) + "%");
                overRows.add(r);
            }
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
        BigDecimal amount = null;
        if (pricer != null && !overRows.isEmpty()) {
            BigDecimal sum = BigDecimal.ZERO;
            String basis = null;
            String why = null;
            for (MaterialBalanceTableDTO.Row r : overRows) {
                RowPrice p = pricer.apply(r);
                if (r.getUnexplained() == null || p == null || p.price() == null) {
                    why = p != null && p.reason() != null ? p.reason() : "未解释差额没有数量";
                    break;
                }
                sum = sum.add(r.getUnexplained().multiply(p.price()));
                basis = p.basis();
            }
            if (why == null) {
                amount = sum.setScale(2, RoundingMode.HALF_UP);
                if (basis != null) summary += "; 差异金额" + basis;
            } else {
                summary += "; 差异金额算不出: " + why;
            }
        }
        // 金额是按单价快照估出来的, 不进指纹 (指纹只认报工事实; 上线后不因多了估价而重开已有实例)
        return reading(ShortageCase.SourceType.MATERIAL_BALANCE, block.getPlanId(), block.getPlanNumber(),
                displayProduct(block.getProductName()), over.isEmpty() ? Outcome.WITHIN : Outcome.DEVIATED, max, thresholdPct, amount,
                over.size(), unknown, summary, false);
    }

    /** 系统内部占位产品 (__RAW_WORKFLOW_OWNER__…) 不给人看。 */
    private static String displayProduct(String name) {
        return name != null && name.startsWith("__") ? null : name;
    }

    private static Reading reading(ShortageCase.SourceType type, String planId, String planNumber, String productName,
                                   Outcome outcome, BigDecimal metric, BigDecimal threshold, BigDecimal amount,
                                   int deviated, int unknown, String summary) {
        return reading(type, planId, planNumber, productName, outcome, metric, threshold, amount, deviated, unknown,
                summary, true);
    }

    private static Reading reading(ShortageCase.SourceType type, String planId, String planNumber, String productName,
                                   Outcome outcome, BigDecimal metric, BigDecimal threshold, BigDecimal amount,
                                   int deviated, int unknown, String summary, boolean amountInFingerprint) {
        // 指纹不含阈值: 改阈值不等于事实变了, 已下结论的实例不因此重开
        String fp = sha256(type + "|" + planId + "|" + plain(metric) + "|" + plain(amountInFingerprint ? amount : null)
                + "|" + deviated + "|" + unknown);
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
