package com.cretas.aims.ai.tool.impl.workdesk;

import java.math.BigDecimal;

/**
 * 仓管员 Workdesk 采购收货【超收上限】的唯一定义处。
 *
 * <p><b>为什么有这个类</b>: 同一个 0.30 曾经在三个 Tool 里各写一遍
 * ({@code MaterialTodayReceivingQueryTool} / {@code ReceiveConfirmCreateTool} /
 * {@code ReceiveWithLimitTool}), 而三处的 javadoc 里还各自复述了一遍 "30%"，
 * 前端 {@code WarehouseKeeperWorkdesk.vue} 又再复述三遍。同一个数六七份，
 * 谁都不是权威 —— 改一份剩下的照旧对仓管员说旧数。收敛到这一处之后，
 * 面向用户的措辞一律用 {@link #pctLabel()} 插值，⛔ 不许再写字面百分比。
 *
 * <p><b>⚠️ 已知不一致 (未裁定, 不要在本次改动里顺手"修正")</b>:
 * 本常量只影响 <b>Workdesk 的展示与 preview 拦截</b>。真正执行入库的
 * {@code PurchaseServiceImpl.overReceiveRate} 是
 * {@code @Value("${cretas.purchase.over-receive-rate:0.00}")}，而该属性在
 * {@code src/main/resources} 下的任何配置文件里都没有设过 —— 也就是说
 * <b>生产上实际允许的超收是 0%</b>，而本类让 Workdesk 告诉仓管员"还能多收 30%"。
 * 仓管员照 Workdesk 的提示收货，会在 confirmReceive 阶段吃 409。
 *
 * <p>把两者对齐是业务裁定 (要么把执行侧放宽到本值，要么把展示侧收紧到 0%)，
 * 不是重构能决定的，故此处只做"收敛成一份"，保持数值不变。
 * 对齐时应改的是: 让本类从同一个 Spring 属性取值，然后删掉这个常量。
 *
 * @since 2026-08-25 (超收容差收敛)
 */
public final class WorkdeskOverReceivePolicy {

    /**
     * 允许超收比例 (30% 行业惯例 — 食品行业散装允许 ±2-5%, 卤味毛料松)。
     * 上限 = 下单量 × 本值; 还可入 = 未收量 + 上限。
     */
    static final BigDecimal OVER_RECEIVE_PCT = new BigDecimal("0.30");

    private WorkdeskOverReceivePolicy() {
    }

    /**
     * 面向仓管员的百分比措辞, 如 {@code "30%"} / {@code "12.5%"} (不留尾零)。
     * ⛔ 所有提示语、异常文案、Tool description 都必须调它, 不许写字面量 ——
     * 写字面量就是又造一份会漂的副本。
     */
    static String pctLabel() {
        return OVER_RECEIVE_PCT
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros()
                .toPlainString() + "%";
    }
}
