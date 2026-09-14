package com.cretas.aims.service.lineage;

/**
 * 写一条血缘边的结果 —— <b>四态，不是两态</b>。
 *
 * <p>「没写」有三种完全不同的含义，折叠成一个 boolean 就再也分不开了
 * （见 {@code .claude/rules/measurement-and-wiring.md} 硬约束 4）：
 *
 * <ul>
 *   <li>{@link #INSERTED} —— 本次真的插了一行。</li>
 *   <li>{@link #ALREADY_EXISTS} —— 同一个幂等键的边已经在库里。回填重跑必然大量落在这一态，
 *       它是<b>正常</b>的，不是失败。</li>
 *   <li>{@link #SKIPPED} —— 入参不足以构成一条边（缺 source/target id、数量非正）。
 *       这是「这条业务记录本来就不该有边」，登记下来，⛔ 不造边。</li>
 *   <li>{@link #AMBIGUOUS} —— 载体信息存在但<b>指向不唯一</b>（典型：一个生产计划下多个生产批次，
 *       而成品批次只记了 planId，对不齐是哪一个）。⛔ 这一态绝不能猜一个塞进去 ——
 *       那正是「禁止降级」要防的假数据。必须计数并报出来。</li>
 *   <li>{@link #FAILED} —— 写库本身出错。fail-soft 吞掉异常，但读数必须留痕。</li>
 * </ul>
 *
 * <p>⚠️ {@link #ALREADY_EXISTS} 与 {@link #SKIPPED} 在「新增行数」这一个数字上长得一模一样
 * （都是 0 行新增），所以回填命令必须<b>分开报</b>这几个计数，否则
 * 「一条都没新增」分不清是「已经全在库里」还是「一条都没量到」。
 */
public enum LineageRecordOutcome {

    /** 新插了一条边。 */
    INSERTED,

    /** 同幂等键的边已存在，本次不重复写。 */
    ALREADY_EXISTS,

    /** 入参不足以构成一条边（缺 id / 数量非正），按设计不写。 */
    SKIPPED,

    /** 载体存在但指向不唯一，拒绝猜测，不写。 */
    AMBIGUOUS,

    /** 写库异常（fail-soft 已吞掉，但这里留下读数）。 */
    FAILED;

    /** 是否真的新增了一行。 */
    public boolean inserted() {
        return this == INSERTED;
    }
}
