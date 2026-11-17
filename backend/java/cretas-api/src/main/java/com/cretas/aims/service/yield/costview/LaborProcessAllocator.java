package com.cretas.aims.service.yield.costview;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 工单人工按各工序有效人时分摊到工序 (纯函数, 不碰库)。
 *
 * <p>口径 (对齐统一原型 allocateLaborByPersonHours): 每道工序分到 total × 本工序人时 ÷ 总人时,
 * 四舍五入到分; 尾差 (总额 − 已分出) 归「最后一道有人时 (&gt;0) 的工序」。人时缺 (null)、为负、
 * 或总人时 ≤ 0 时无法分摊: 全部返回 null 金额并给出原因, ⛔ 不用 0 顶替「不知道」。
 */
public final class LaborProcessAllocator {

    public enum Status { ALLOCATED, LABOR_TOTAL_UNKNOWN, HOURS_UNKNOWN, HOURS_NEGATIVE, NO_POSITIVE_HOURS }

    public record ProcessHours(Integer processOrder, String processName, BigDecimal hours) {
    }

    public record ProcessShare(Integer processOrder, String processName, BigDecimal hours, BigDecimal amount) {
    }

    public record Result(Status status, BigDecimal laborTotal, List<ProcessShare> shares) {
    }

    private LaborProcessAllocator() {
    }

    public static Result allocate(BigDecimal laborTotal, List<ProcessHours> processes) {
        List<ProcessHours> ps = processes == null ? List.of() : processes;
        if (laborTotal == null || laborTotal.signum() < 0) {
            return unknown(Status.LABOR_TOTAL_UNKNOWN, laborTotal, ps);
        }
        BigDecimal sum = BigDecimal.ZERO;
        for (ProcessHours p : ps) {
            if (p.hours() == null) return unknown(Status.HOURS_UNKNOWN, laborTotal, ps);
            if (p.hours().signum() < 0) return unknown(Status.HOURS_NEGATIVE, laborTotal, ps);
            sum = sum.add(p.hours());
        }
        if (sum.signum() <= 0) return unknown(Status.NO_POSITIVE_HOURS, laborTotal, ps);

        int last = -1;
        for (int i = 0; i < ps.size(); i++) {
            if (ps.get(i).hours().signum() > 0) last = i;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        List<ProcessShare> out = new ArrayList<>();
        for (int i = 0; i < ps.size(); i++) {
            ProcessHours p = ps.get(i);
            BigDecimal amt;
            if (p.hours().signum() == 0) {
                amt = BigDecimal.ZERO.setScale(2);
            } else if (i == last) {
                amt = laborTotal.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            } else {
                amt = laborTotal.multiply(p.hours()).divide(sum, 2, RoundingMode.HALF_UP);
            }
            allocated = allocated.add(amt);
            out.add(new ProcessShare(p.processOrder(), p.processName(), p.hours(), amt));
        }
        return new Result(Status.ALLOCATED, laborTotal, out);
    }

    private static Result unknown(Status s, BigDecimal total, List<ProcessHours> ps) {
        List<ProcessShare> out = new ArrayList<>();
        for (ProcessHours p : ps) out.add(new ProcessShare(p.processOrder(), p.processName(), p.hours(), null));
        return new Result(s, total, out);
    }
}
