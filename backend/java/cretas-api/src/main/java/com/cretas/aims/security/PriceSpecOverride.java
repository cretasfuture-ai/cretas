package com.cretas.aims.security;

/**
 * 同一个实体既装采购金额又装销售金额时 (退货单: 采购退货 / 销售退货), 由对象自己说本条记录的
 * {@code @PriceSensitive} 默认口径该按什么判 (S5-12)。
 *
 * <p>{@link PriceFieldResponseAdvice} 遍历到实现本接口的对象时, 把它和它下面的对象里
 * 「默认口径」({@code procurement:price:view}) 的字段改按 {@link #priceSpecOverride()} 判;
 * 子对象若实现 {@link #inheritPriceSpec(String)} 会被告知上层口径, 供 Jackson 层的 getter 用。
 * 返回 null 表示不覆盖。
 */
public interface PriceSpecOverride {

    String priceSpecOverride();

    /** 由 advice 在遍历到父对象时调用; 默认忽略。 */
    default void inheritPriceSpec(String spec) {
    }
}
