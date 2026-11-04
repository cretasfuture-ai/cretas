package com.cretas.aims.service.shortage;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * 交付风险判定 (纯函数): 只看传进来的事实。缺事实就是 {@link Level#UNKNOWN}, ⛔ 不用 0 或估值补。
 *
 * <p>只有「排程 / 产能算得出能按期」才判 {@link Level#OK}; 没有依据不算正常。
 */
public final class DeliveryRiskEvaluator {

    /** 还没排计划时, 离交期不到这么多天才算风险; 更远的算「未知」。 */
    static final int NO_PLAN_WINDOW_DAYS = 7;

    public enum Level { RISK, UNKNOWN, OK }

    public enum Signal {
        OVERDUE("已过交期未交完"), NO_PLAN("临近交期还没有生产计划"), LATE_SCHEDULE("排程完工晚于交期"),
        CAPACITY_SHORT("剩余量按日产能做不完"), MATERIAL_LATE("缺料且到货赶不上交期");

        private final String label;

        Signal(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public enum MaterialStatus { CLEAR, GAP, UNKNOWN }

    /** remainingQty / perDay 为 null = 不知道; remainingProcesses 为 null = 读不到工序进度。 */
    public record PlanFact(String planId, String planNumber, String unit, BigDecimal plannedQty, BigDecimal doneQty,
                           BigDecimal remainingQty, BigDecimal perDay, String capacitySource,
                           Integer remainingProcesses, Integer totalProcesses) {
    }

    /** 一种缺料; latestArrival 为 null 且 hasOrder 时 = 有采购单但没填到货日。 */
    public record MaterialGap(String name, boolean hasOrder, LocalDate latestArrival) {
    }

    public record Facts(LocalDate today, LocalDate due, boolean pendingDelivery, List<PlanFact> plans,
                        int liveSchedules, int undatedSchedules, LocalDateTime latestPlannedEnd, int lateSchedules,
                        MaterialStatus materialStatus, String materialNote, List<MaterialGap> materialGaps) {
    }

    public record Result(Level level, List<Signal> signals, List<String> evidence, List<String> unknowns,
                         List<String> suggestions, Integer estimatedDaysNeeded, Long daysLeft) {
    }

    private DeliveryRiskEvaluator() {
    }

    public static Result evaluate(Facts f) {
        List<Signal> signals = new ArrayList<>();
        List<String> evidence = new ArrayList<>();
        List<String> unknowns = new ArrayList<>();
        List<String> suggestions = new ArrayList<>();
        if (!f.pendingDelivery()) {
            evidence.add("订单已没有待交数量");
            return new Result(Level.OK, signals, evidence, unknowns, suggestions, null, null);
        }
        if (f.due() == null) {
            unknowns.add("订单没有要求交期, 无法判断是否延期");
            return new Result(Level.UNKNOWN, signals, evidence, unknowns, suggestions, null, null);
        }
        long daysLeft = ChronoUnit.DAYS.between(f.today(), f.due());
        boolean scheduleBasis = false;
        if (daysLeft < 0) {
            signals.add(Signal.OVERDUE);
            evidence.add("交期 " + f.due() + " 已过 " + (-daysLeft) + " 天, 订单还有待交数量");
            suggestions.add("联系客户确认新的交货安排");
        }
        if (f.plans().isEmpty()) {
            if (daysLeft <= NO_PLAN_WINDOW_DAYS) {
                signals.add(Signal.NO_PLAN);
                evidence.add("离交期 " + daysLeft + " 天, 订单没有关联的未完成生产计划");
                suggestions.add("尽快安排生产计划, 或确认能否用现有库存发货");
            } else {
                unknowns.add("订单还没有生产计划, 算不出完工时间");
            }
        }
        if (f.lateSchedules() > 0) {
            signals.add(Signal.LATE_SCHEDULE);
            evidence.add(f.lateSchedules() + " 个排程任务晚于交期 " + f.due() + " 完工 (最晚 " + f.latestPlannedEnd() + ")");
            suggestions.add("评估调整排程或加班, 让相关任务在交期前完工");
        } else if (f.liveSchedules() > 0 && f.undatedSchedules() == 0) {
            scheduleBasis = true;
            evidence.add(f.liveSchedules() + " 个未完成排程任务都在交期内 (最晚 " + f.latestPlannedEnd() + ")");
        } else if (f.undatedSchedules() > 0) {
            unknowns.add(f.undatedSchedules() + " 个排程任务没有计划完工时间");
        }

        Integer daysNeeded = null;
        boolean capacityBasis = false;
        if (!f.plans().isEmpty()) {
            int total = 0;
            boolean complete = true;
            for (PlanFact p : f.plans()) {
                evidence.add(planLine(p));
                if (p.remainingQty() == null) {
                    complete = false;
                    unknowns.add("计划 " + p.planNumber() + " 的剩余量算不出");
                } else if (p.remainingQty().signum() > 0) {
                    if (p.perDay() == null) {
                        complete = false;
                        unknowns.add("计划 " + p.planNumber() + " 的产品没有可用的日产能");
                    } else {
                        total += p.remainingQty().divide(p.perDay(), 0, RoundingMode.CEILING).intValue();
                    }
                }
            }
            if (complete) {
                daysNeeded = total;
                capacityBasis = true;
                // 今天到交期当天都还能做
                if (total > daysLeft + 1) {
                    signals.add(Signal.CAPACITY_SHORT);
                    evidence.add("剩余量按日产能还要 " + total + " 天, 离交期只有 " + Math.max(daysLeft + 1, 0) + " 天可用");
                    suggestions.add("评估加班、加线, 或与客户协商分批交货");
                }
            }
        }

        if (f.materialStatus() == MaterialStatus.UNKNOWN) {
            unknowns.add(f.materialNote() == null ? "物料状态读不到" : f.materialNote());
        } else if (f.materialStatus() == MaterialStatus.CLEAR) {
            evidence.add("按当前库存核算物料够用");
        } else {
            List<String> late = new ArrayList<>();
            for (MaterialGap g : f.materialGaps()) {
                if (!g.hasOrder()) {
                    late.add(g.name() + " (没有已下给供应商的采购单)");
                } else if (g.latestArrival() == null) {
                    unknowns.add(g.name() + " 的采购单没填预计到货日");
                } else if (!g.latestArrival().isBefore(f.due())) {
                    late.add(g.name() + " (最晚 " + g.latestArrival() + " 到货)");
                } else {
                    evidence.add(g.name() + " 缺料, 采购单预计 " + g.latestArrival() + " 到货, 早于交期");
                }
            }
            if (!late.isEmpty()) {
                signals.add(Signal.MATERIAL_LATE);
                evidence.add("缺料赶不上交期: " + String.join("、", late));
                suggestions.add("让采购催货或找替代来源, 到「缺料异常」处理");
            }
        }

        Level level;
        if (!signals.isEmpty()) {
            level = Level.RISK;
        } else if (!unknowns.isEmpty() || !(scheduleBasis || capacityBasis)) {
            level = Level.UNKNOWN;
            if (unknowns.isEmpty()) {
                unknowns.add("没有排程也没有产能依据, 无法判断能否按期");
            }
        } else {
            level = Level.OK;
        }
        return new Result(level, signals, evidence, unknowns, suggestions, daysNeeded, daysLeft);
    }

    private static String planLine(PlanFact p) {
        String qty = p.remainingQty() == null ? "剩余量未知"
                : "计划 " + plain(p.plannedQty()) + ", 已入库 " + plain(p.doneQty()) + ", 剩余 " + plain(p.remainingQty())
                + " " + (p.unit() == null ? "(单位未知)" : p.unit());
        String proc = p.remainingProcesses() == null ? "工序进度未知"
                : "剩余工序 " + p.remainingProcesses() + "/" + p.totalProcesses();
        return "计划 " + p.planNumber() + ": " + qty + "; " + proc;
    }

    private static String plain(BigDecimal v) {
        return v == null ? "未知" : v.stripTrailingZeros().toPlainString();
    }
}
