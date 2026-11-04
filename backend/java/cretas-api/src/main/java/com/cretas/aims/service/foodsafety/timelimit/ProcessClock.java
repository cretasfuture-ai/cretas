package com.cretas.aims.service.foodsafety.timelimit;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.BiPredicate;

/**
 * 时限计时的纯函数 —— 食品 DAG 缺口 14。不查库, 不改任何东西。
 *
 * <h3>为什么要回放</h3>
 * 半成品行按产品码跨批次累加, 一行不是一批, 行上没有单一的「产出时刻」。这里用已有的
 * {@code semi_finished_inventory_transactions} 按时间回放出每一笔还剩多少: 不用改入账 / 扣减的那二十多个点,
 * 存量行也直接有账。
 *
 * <h3>🔴 不许偷偷重置计时</h3>
 * 拿不准时一律往更早算 (宁可多挡):
 * <ul>
 *   <li>领用扣最早的笔。{@code PER_LOT} 下跳过当时已超时的笔: 超时的料没人动, 新料被领走之后,
 *       账上留下的必须还是那笔旧料, 不能被算成新料;</li>
 *   <li>重存: 先冲销再入账, 被冲销的量回到原来那一笔 (逐道录入的 {@code sourceRef} 是整行共用的锚,
 *       不是单笔凭据, 所以只认回冲销过的量, 其余仍是新的一笔);</li>
 *   <li>撤回领用的量退回最老的笔, 不生成新笔;</li>
 *   <li>盘盈 / 认不出来源的量算进最老的笔, 盘亏从最新的笔扣。</li>
 * </ul>
 */
public final class ProcessClock {

    /** 判不合格转出的流水前缀: 这种 OUT 扣的就是超时的笔, 回放时不跳过。 */
    public static final String REJECT_REF_PREFIX = "time-limit-reject:";

    private static final String TXN_IN = "IN";
    private static final String TXN_OUT = "OUT";

    private ProcessClock() {
    }

    /** 一段暂停; {@code to} 为空 = 还在暂停。 */
    public record Span(LocalDateTime from, LocalDateTime to) {
    }

    /** 回放用的一条流水。{@code quantity} 带符号 (见实体 javadoc): IN 正, OUT 负, 其余可正可负。 */
    public record Txn(String type, String sourceRef, Long reportId, BigDecimal quantity, LocalDateTime at) {
    }

    /** 一笔: 入账时刻 + 还剩多少。 */
    public static final class Lot {
        private final LocalDateTime at;
        private final String sourceRef;
        private final Long reportId;
        private BigDecimal in;
        private BigDecimal open;
        /** 被冲销、等着重新入账的量。 */
        private BigDecimal reversed = BigDecimal.ZERO;

        public Lot(LocalDateTime at, String sourceRef, Long reportId, BigDecimal quantity) {
            this.at = at;
            this.sourceRef = sourceRef;
            this.reportId = reportId;
            this.in = quantity;
            this.open = quantity;
        }

        public LocalDateTime at() {
            return at;
        }

        public Long reportId() {
            return reportId;
        }

        public BigDecimal open() {
            return open;
        }
    }

    /**
     * 截止时刻 = 起算 + 时限 + 截止之前开始的暂停时长。
     *
     * <p>⛔ 已经超时之后才开始的暂停不算数: 暂停不能救回超时的料。还在进行的暂停算到 {@code now}。
     *
     * @param pauses 已按配置筛过的暂停
     */
    public static LocalDateTime deadline(LocalDateTime start, int limitMinutes, List<Span> pauses,
                                         Integer maxPauseMinutes, LocalDateTime now) {
        LocalDateTime deadline = start.plusMinutes(limitMinutes);
        Duration budget = maxPauseMinutes == null ? null : Duration.ofMinutes(maxPauseMinutes);
        List<Span> sorted = pauses == null ? List.of() : pauses.stream()
                .sorted(Comparator.comparing(Span::from)).toList();
        LocalDateTime cursor = start;
        for (Span pause : sorted) {
            if (!pause.from().isBefore(deadline)) {
                break;
            }
            LocalDateTime from = pause.from().isBefore(cursor) ? cursor : pause.from();
            LocalDateTime to = pause.to() == null ? now : pause.to();
            if (!to.isAfter(from)) {
                continue;
            }
            Duration counted = Duration.between(from, to);
            if (budget != null) {
                counted = counted.compareTo(budget) > 0 ? budget : counted;
                budget = budget.minus(counted);
            }
            deadline = deadline.plus(counted);
            cursor = to;
        }
        return deadline;
    }

    /**
     * 回放出当前还开着的笔, 按入账时刻从早到晚。
     *
     * @param txns         这一行的全部流水
     * @param balance      这一行当前余额 (回放结果要和它对齐)
     * @param rowCreatedAt 这一行的创建时刻: 账上没有来源的量算到这个时刻
     * @param expiredAt    {@code PER_LOT} 用: 这一笔在这个时刻是否已超时; {@code null} = 不跳过, 纯先进先出
     */
    public static List<Lot> replay(List<Txn> txns, BigDecimal balance, LocalDateTime rowCreatedAt,
                                   BiPredicate<Lot, LocalDateTime> expiredAt) {
        List<Lot> lots = new ArrayList<>();
        List<Txn> ordered = txns == null ? List.of() : txns.stream()
                .sorted(Comparator.comparing(Txn::at)).toList();
        for (Txn txn : ordered) {
            BigDecimal qty = txn.quantity() == null ? BigDecimal.ZERO : txn.quantity();
            if (TXN_IN.equals(txn.type())) {
                credit(lots, txn, qty.abs());
            } else if (TXN_OUT.equals(txn.type())) {
                boolean rejectsExpired = txn.sourceRef() != null && txn.sourceRef().startsWith(REJECT_REF_PREFIX);
                consume(lots, qty.abs(), txn.at(), rejectsExpired ? null : expiredAt);
            } else if (qty.signum() < 0) {
                reverseOrShrink(lots, txn, qty.abs());
            } else if (qty.signum() > 0) {
                restoreToOldest(lots, qty);
            }
        }
        return reconcile(lots, balance == null ? BigDecimal.ZERO : balance.max(BigDecimal.ZERO), rowCreatedAt);
    }

    private static void credit(List<Lot> lots, Txn txn, BigDecimal qty) {
        BigDecimal left = qty;
        if (txn.sourceRef() != null && txn.reportId() == null) {
            for (Lot lot : lots) {
                if (left.signum() > 0 && lot.reversed.signum() > 0 && txn.sourceRef().equals(lot.sourceRef)) {
                    BigDecimal back = lot.reversed.min(left);
                    lot.reversed = lot.reversed.subtract(back);
                    lot.in = lot.in.add(back);
                    lot.open = lot.open.add(back);
                    left = left.subtract(back);
                }
            }
        }
        if (left.signum() > 0) {
            lots.add(new Lot(txn.at(), txn.sourceRef(), txn.reportId(), left));
        }
    }

    private static void consume(List<Lot> lots, BigDecimal qty, LocalDateTime at,
                                BiPredicate<Lot, LocalDateTime> expiredAt) {
        BigDecimal left = qty;
        if (expiredAt != null) {
            for (Lot lot : lots) {
                if (left.signum() > 0 && !expiredAt.test(lot, at)) {
                    left = left.subtract(take(lot, left));
                }
            }
        }
        // 没有未超时的笔可扣 (老账、或绕过闸的量): 只能扣到超时的笔上
        for (Lot lot : lots) {
            if (left.signum() > 0) {
                left = left.subtract(take(lot, left));
            }
        }
    }

    private static BigDecimal take(Lot lot, BigDecimal qty) {
        BigDecimal taken = lot.open.min(qty);
        lot.open = lot.open.subtract(taken);
        return taken;
    }

    /** 负数量的非 IN/OUT 流水: 冲销入账找原来那一笔, 找不到 (盘亏等) 从最新的笔扣。 */
    private static void reverseOrShrink(List<Lot> lots, Txn txn, BigDecimal qty) {
        BigDecimal left = qty;
        if (txn.reportId() != null) {
            for (Lot lot : lots) {
                if (left.signum() > 0 && txn.reportId().equals(lot.reportId)) {
                    left = left.subtract(take(lot, left));
                }
            }
        }
        for (int i = lots.size() - 1; i >= 0 && left.signum() > 0; i--) {
            Lot lot = lots.get(i);
            BigDecimal taken = take(lot, left);
            lot.in = lot.in.subtract(taken);
            lot.reversed = lot.reversed.add(taken);
            left = left.subtract(taken);
        }
    }

    /** 撤回领用 / 盘盈: 退回最老的、被扣过的笔; 都满了就记在最老的笔上。 */
    private static void restoreToOldest(List<Lot> lots, BigDecimal qty) {
        BigDecimal left = qty;
        for (Lot lot : lots) {
            BigDecimal room = lot.in.subtract(lot.open);
            if (left.signum() > 0 && room.signum() > 0) {
                BigDecimal back = room.min(left);
                lot.open = lot.open.add(back);
                left = left.subtract(back);
            }
        }
        if (left.signum() > 0 && !lots.isEmpty()) {
            Lot oldest = lots.get(0);
            oldest.in = oldest.in.add(left);
            oldest.open = oldest.open.add(left);
        }
    }

    /** 与余额对齐: 账上多出来的从最新的笔扣, 少的算进最老的笔。 */
    private static List<Lot> reconcile(List<Lot> lots, BigDecimal balance, LocalDateTime rowCreatedAt) {
        BigDecimal sum = lots.stream().map(Lot::open).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal diff = balance.subtract(sum);
        if (diff.signum() < 0) {
            BigDecimal left = diff.negate();
            for (int i = lots.size() - 1; i >= 0 && left.signum() > 0; i--) {
                left = left.subtract(take(lots.get(i), left));
            }
        } else if (diff.signum() > 0) {
            if (lots.isEmpty()) {
                lots.add(new Lot(rowCreatedAt, null, null, diff));
            } else {
                Lot oldest = lots.get(0);
                oldest.in = oldest.in.add(diff);
                oldest.open = oldest.open.add(diff);
            }
        }
        return lots.stream().filter(lot -> lot.open.signum() > 0).toList();
    }
}
