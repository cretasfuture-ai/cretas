package com.cretas.aims.entity.enums;

/**
 * 结单时「这批有没有不良品」的三态结论。
 *
 * <h2>为什么是三态而不是一个数字</h2>
 *
 * <p>2026-08-26 生产实测：72 张报工单里<b>没有一张</b>填过不良品数，
 * 61 个批次里 {@code defect_quantity > 0} 的是 <b>0 条</b>。正因为没人填，
 * 旧代码才去兜底填「零不良」，于是 8 个已完工批次全部声称 100% 良品 ——
 * 而其中 <b>4 个</b>的质检明确记了不合格（批次 10766：抽样 80 / 不合格 5）。</p>
 *
 * <p>单一数字字段分不开这两件事，而它们对良品率/损耗/人效的含义<b>完全相反</b>：</p>
 *
 * <ul>
 *   <li>「已清点，确认这批零不良」—— {@code 0} 是真实读数</li>
 *   <li>「没清点 / 没人填」—— {@code 0} 是编的</li>
 * </ul>
 *
 * <p>⇒ 用一个显式的结论字段把它们分开。这是本仓「不知道就别编」在这条链路上的落地。</p>
 */
public enum DefectCountOutcome {

    /** 已清点，有不良品。要求 {@code defectQuantity > 0}。 */
    COUNTED,

    /** 已清点，确认零不良。{@code defectQuantity = 0}，<b>这个 0 是真的</b>。 */
    NONE_CONFIRMED,

    /** 没清点。{@code defectQuantity} 必须为 {@code null} —— ⛔ 不许回填成 0。 */
    NOT_COUNTED
}
