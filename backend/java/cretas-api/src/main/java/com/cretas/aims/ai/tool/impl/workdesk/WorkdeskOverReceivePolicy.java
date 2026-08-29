package com.cretas.aims.ai.tool.impl.workdesk;

import com.cretas.aims.service.inventory.OverReceiveLimit;
import com.cretas.aims.service.inventory.PurchaseOverReceivePolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 仓管员 Workdesk 采购收货【超收上限】的取值入口 —— <b>它自己不再持有任何数字</b>。
 *
 * <h2>2026-08-29: 从「自己那份 0.30」改成「问执行侧要」</h2>
 * 本类原来是一个持有 {@code OVER_RECEIVE_PCT = 0.30} 的静态常量类。
 * 它的 javadoc 自己写着这段话：
 *
 * <blockquote>
 * 「⚠️ 已知不一致 (未裁定)：本常量只影响 Workdesk 的展示与 preview 拦截。
 * 真正执行入库的是 {@code PurchaseServiceImpl.overReceiveRate}……
 * <b>对齐时应改的是：让本类从同一个 Spring 属性取值，然后删掉这个常量。</b>」
 * </blockquote>
 *
 * 现在照做了。
 *
 * <h3>⚠️ 顺带订正：那段 javadoc 描述的漂移方向已经反了</h3>
 * 它写的是「执行侧 {@code @Value(":0.00")} ⇒ 生产上实际允许 0%，而本类说 30%
 * ⇒ 仓管员照提示收货会吃 409」。
 * 但 2026-08-25 执行侧的默认值已改成 {@code #{null}}（= 不限制），
 * 而该属性全仓从未设置过 ⇒ <b>执行侧当前根本不限制，反倒是本类在说 30%</b>。
 *
 * ⇒ 漂移方向从「展示比执行松」变成了「展示比执行紧」，而 <b>两次都没人发现</b> ——
 * 因为两边各有一份数，谁也不会去比。
 * ▎<b>描述过期比没有描述更糟：它让下一个人跳过核实。</b>
 *
 * <h3>取值梯子只有一条</h3>
 * 全部委托给 {@link PurchaseOverReceivePolicy#effectiveLimit(String)}：
 * 工厂设置 &gt; 全局属性 &gt; 不限制。⛔ 本类不再复制那条梯子的任何一段。
 *
 * <h3>⛔ 调用方必须处理「不限制」</h3>
 * {@link OverReceiveLimit#isUnlimited()} 为真时：
 * <ul>
 *   <li>{@link #capFor} 返回 {@code null} —— 表示<b>没有上限</b>，
 *       ⛔ 不许兜底成 {@code 0}（那会把「不限制」翻译成「一件都不许收」）</li>
 *   <li>{@link #labelFor} 返回「不限制」，⛔ 不许打印「0%」</li>
 * </ul>
 *
 * @since 2026-08-25 (超收容差收敛) / 2026-08-29 (接上执行侧)
 */
@Component
public class WorkdeskOverReceivePolicy {

    private final PurchaseOverReceivePolicy executionSidePolicy;

    @Autowired
    public WorkdeskOverReceivePolicy(PurchaseOverReceivePolicy executionSidePolicy) {
        this.executionSidePolicy = executionSidePolicy;
    }

    /**
     * 该工厂当前<b>实际生效</b>的超收上限 —— 与执行侧 {@code confirmReceive} 用的是同一个值。
     *
     * <p>⚠️ 每次工具调用只取一次（在逐行循环<b>外</b>），避免逐行打 DB。
     */
    public OverReceiveLimit limitFor(String factoryId) {
        return executionSidePolicy.effectiveLimit(factoryId);
    }

    /**
     * 这条订单行的超收额度（{@code 下单量 × 比率}）。
     *
     * @return 不限制时返回 {@code null} —— 调用方必须显式处理，⛔ 不许当成 0
     */
    public static BigDecimal capFor(OverReceiveLimit limit, BigDecimal orderedQty) {
        if (limit == null || limit.isUnlimited() || orderedQty == null) {
            return null;
        }
        return orderedQty.multiply(limit.rate());
    }

    /**
     * 还可入总量（{@code 未收量 + 超收额度}）。
     *
     * @return 不限制时返回 {@code null}（没有上限），⛔ 不许兜底成未收量本身 ——
     *         那会让仓管员以为「只能收到未收量为止」，与执行侧的实际放行范围不符
     */
    public static BigDecimal remainingCapFor(OverReceiveLimit limit,
            BigDecimal orderedQty, BigDecimal pendingQty) {
        BigDecimal cap = capFor(limit, orderedQty);
        if (cap == null || pendingQty == null) {
            return null;
        }
        return pendingQty.add(cap);
    }

    /**
     * 面向仓管员的措辞：{@code "30%"} / {@code "12.5%"} / {@code "不限制"}。
     *
     * <p>⛔ 所有提示语、异常文案、Tool description 都必须调它，不许写字面百分比 ——
     * 写字面量就是又造一份会漂的副本（本类的存在就是为了消灭上一份）。
     */
    public static String labelFor(OverReceiveLimit limit) {
        return limit == null ? "不限制" : limit.label();
    }
}
