package com.cretas.aims.entity.enums;

/**
 * 销售异常单的类型。
 *
 * <p>⚠️ 今天只有一个取值。<b>它不是占位</b> —— 单值枚举在这里是有意的:
 * 表里那一列必须能被将来的第二种异常区分开, 而如果现在写成裸字符串常量,
 * 第二种异常出现时没有任何东西逼着调用方去分类。
 *
 * <p>⛔ 与采购侧的 {@link ReceiveExceptionType} <b>不共用</b>: 那两个值
 * (OVER_RECEIVE / UNDER_RECEIVE) 描述的是「货已经收进来了、量不对」，
 * 而这里描述的是「货<b>没有</b>发出去、请求被拒绝了」。
 * 共用一个枚举会让消费方以为它们是同一族状态。
 */
public enum SalesExceptionType {

    /**
     * 实发数量超出【超发上限】, 该次发货确认被拒绝 (409)。
     *
     * <p>⚠️ 语义是「<b>尝试</b>被拦下」，不是「超发了」:
     * 库存<b>没有</b>按 attemptedQuantity 扣减, 货<b>没有</b>出门。
     */
    OVER_SHIP_BLOCKED("超发被拦");

    private final String displayName;

    SalesExceptionType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
