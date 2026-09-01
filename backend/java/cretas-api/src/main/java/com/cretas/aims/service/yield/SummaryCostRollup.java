package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.OrderCostBreakdownDTO;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 生产小结「总成本」的汇总口径：<b>任一成品批次成本未知 ⇒ 总成本未知</b>。
 *
 * <h3>为什么单独抽出来（2026-09-02 本机 UAT 实拍）</h3>
 * 小吃吧线成品批次 CLK-B-…49383 的 cost-breakdown 诚实地返回
 * {@code totalCost=null, costComplete=false, missing=[SEMI_FINISHED_OR_FINISHED_FEED_PRICE]}
 * （上游半成品单价未知），而小结把它 {@code nz()} 成 0 后相加 ⇒ 屏幕上写着 <b>总成本 ¥0</b>。
 * 「不知道」被翻译成「是 0」——两件事对下游完全不同；美式线同一屏显示 ¥74,544，
 * 看图的人会以为小吃吧这批不要钱。
 *
 * <p>⛔ 不兜底。未知就返回 {@code null}，并把缺什么写进 {@link Result#note()} 给人看。
 * 空列表（还没有成品批次）保持原来的 0——那不是「未知」，是「还没发生」。
 */
public final class SummaryCostRollup {

    private SummaryCostRollup() {
    }

    /** {@code total == null} 表示未知；{@code note} 说明缺什么（已知时为 null）。 */
    public record Result(BigDecimal total, String note) {
    }

    public static Result rollup(List<OrderCostBreakdownDTO> breakdowns) {
        BigDecimal sum = BigDecimal.ZERO;
        List<String> unknownBatches = new ArrayList<>();
        Set<String> missing = new LinkedHashSet<>();
        for (OrderCostBreakdownDTO cb : breakdowns) {
            if (cb == null) continue;
            if (cb.getTotalCost() == null) {
                unknownBatches.add(cb.getOrderId() != null ? cb.getOrderId() : "?");
                if (cb.getMissingCostItems() != null) missing.addAll(cb.getMissingCostItems());
                continue;
            }
            sum = sum.add(cb.getTotalCost());
        }
        if (unknownBatches.isEmpty()) {
            return new Result(sum, null);
        }
        String note = "成本不完整: 批次 " + String.join(", ", unknownBatches) + " 成本未知"
                + (missing.isEmpty() ? "" : "（缺 " + String.join(", ", missing) + "）")
                + "，总成本不显示以免误读为 ¥0";
        return new Result(null, note);
    }
}
