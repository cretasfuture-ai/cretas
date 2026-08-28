package com.cretas.aims.service.wip;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * 把<b>一份</b>报工的成本按量摊到它<b>实际入账的每一条产出</b>上。
 *
 * <h2>它守什么</h2>
 *
 * <p>一份报工只有一份成本。这份成本会被分给多个「槽」——
 * {@code semiOutputs} 的 N 条 (画布 1 进 N 出), 以及 {@code outputKind} 含 FINISHED 时
 * {@code outputQuantity} 那一条。⛔ 每个槽各自去算一次全额, 就是把一份成本记成 N+1 份:
 * <ul>
 *   <li>2026-08-28 C-1: {@code postSemiOutputLedgerFor} 被逐条调用而它内部重算全额 ⇒ ¥10,000 记成 ¥20,000;</li>
 *   <li>2026-08-28 Critical 2: SEMI 分支分摊完之后 FINISHED 分支<b>又</b>拿全额 ⇒ ¥100 记成 ¥200。</li>
 * </ul>
 * 两次是同一个形状。抽成这一份纯函数就是为了让它<b>只有一处实现</b> ——
 * 同一个口径有两份, 它一定会漂。
 *
 * <h2>口径</h2>
 *
 * <ul>
 *   <li><b>同一个单价</b>: {@code unitCost = totalCost / Σqty} (scale-4 HALF_UP)。
 *       回料和良品是同一锅料出来的, 单价必须相同 —— 各自除以各自的量会让回料单价是良品的十几倍。</li>
 *   <li><b>尾数归最后一槽</b>: 前 n-1 槽按 {@code totalCost × qty / Σqty} 取 scale-2
 *       (= {@code accumulated_cost} 的列精度) HALF_UP, 最后一槽拿 {@code totalCost − Σ已分摊}
 *       ⇒ N 槽之和<b>恰好</b>等于 totalCost, 一分不多一分不少。
 *       ⛔ 单槽 (N=1) 时唯一那槽拿到全额、无舍入损失 ⇒ 与单产出路径逐字节一致。</li>
 *   <li><b>诚实 null</b>: {@code totalCost} 读不出来 (或总量非正) ⇒ 每槽的成本与单价一律 {@code null},
 *       ⛔ 不摊成 0 —— 一个假的 0 会顺着移动加权把整条链的单价摊薄, 而且不报错。</li>
 * </ul>
 */
public final class WipCostAllocation {

    /** {@code accumulated_cost} 的列精度 —— 分摊到槽的金额按这个精度取整, 尾数归最后一槽。 */
    private static final int MONEY_SCALE = 2;
    /** {@code unit_cost} / {@code unit_cost_at_txn} 的列精度。 */
    private static final int UNIT_COST_SCALE = 4;

    private final BigDecimal totalCost;
    private final BigDecimal unitCost;
    private final List<BigDecimal> costs;

    private WipCostAllocation(BigDecimal totalCost, BigDecimal unitCost, List<BigDecimal> costs) {
        this.totalCost = totalCost;
        this.unitCost = unitCost;
        this.costs = costs;
    }

    /**
     * @param totalCost  这一份报工的成本; {@code null} = 成本未知 (诚实 null 一路传下去)
     * @param quantities 每个槽入账的量, 顺序即分摊顺序 (尾数归最后一个)
     */
    public static WipCostAllocation byQuantity(BigDecimal totalCost, List<BigDecimal> quantities) {
        List<BigDecimal> costs = new ArrayList<>(quantities.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        for (BigDecimal q : quantities) {
            totalQty = totalQty.add(q == null ? BigDecimal.ZERO : q);
        }
        // 成本未知 / 总量非正 → 每槽诚实 null (⛔ 不摊成 0, 也不做除零)
        if (totalCost == null || totalQty.signum() <= 0) {
            for (int i = 0; i < quantities.size(); i++) {
                costs.add(null);
            }
            return new WipCostAllocation(totalCost, null, costs);
        }
        BigDecimal unitCost = totalCost.divide(totalQty, UNIT_COST_SCALE, RoundingMode.HALF_UP);
        BigDecimal allocatedSoFar = BigDecimal.ZERO;
        for (int i = 0; i < quantities.size(); i++) {
            if (i == quantities.size() - 1) {
                costs.add(totalCost.subtract(allocatedSoFar));   // 尾数归最后一槽 ⇒ Σ 恰好 == totalCost
            } else {
                BigDecimal q = quantities.get(i) == null ? BigDecimal.ZERO : quantities.get(i);
                BigDecimal slot = totalCost.multiply(q).divide(totalQty, MONEY_SCALE, RoundingMode.HALF_UP);
                costs.add(slot);
                allocatedSoFar = allocatedSoFar.add(slot);
            }
        }
        return new WipCostAllocation(totalCost, unitCost, costs);
    }

    /** 这一份报工的成本 (未分摊前的全额); 可能 {@code null}。 */
    public BigDecimal totalCost() {
        return totalCost;
    }

    /** 各槽<b>相同</b>的入账单价 (scale-4); 成本未知或总量非正时 {@code null}。 */
    public BigDecimal unitCost() {
        return unitCost;
    }

    /** 第 {@code index} 槽分到的成本 (scale-2); 成本未知时 {@code null}。 */
    public BigDecimal costAt(int index) {
        return costs.get(index);
    }
}
