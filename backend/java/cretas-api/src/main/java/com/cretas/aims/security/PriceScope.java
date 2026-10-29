package com.cretas.aims.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 整个响应的价格口径 (S5-12)。标在控制器类或方法上时, {@link PriceFieldResponseAdvice}
 * 对这一响应里<b>默认口径</b>的价格 (未写 permission 的 {@code @PriceSensitive} 字段、
 * Map 里的金额键、列表汇总里的金额) 改按这里的 permission 判, 不再看
 * {@code procurement:price:view} 的固定角色名单。字段自己写了 permission 的仍以字段为准。
 *
 * <p>用途: 采购入口 (采购单、供应商物料) 返回 Map / 汇总统计时, 金额也得跟着采购模块配置走,
 * 否则一个有 report:read 又在固定名单里的角色能从统计接口读到采购总额。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface PriceScope {

    /**
     * 控制器在运行时按入参决定口径时 (例: 列表合计按 entityType), 把口径串写进这个 request attribute;
     * 它优先于方法 / 类上的注解。
     */
    String REQUEST_ATTRIBUTE = "cretas.priceScope";

    /** 同上, 本响应里 {@link PriceSensitive#PURCHASE} 字段改按哪个口径判 (财务审批读用)。 */
    String PURCHASE_REMAP_ATTRIBUTE = "cretas.priceScope.purchase";

    /** 权限规格, 同 {@link PriceSensitive#permission()} ({@code |} 分隔为任一即可)。 */
    String value();

    /**
     * 非空时, 本响应里标 {@link PriceSensitive#PURCHASE} 的字段改按这个口径判 (S5-12 裁定 a:
     * 财务的采购单审批读用 {@link PriceSensitive#PURCHASE_OR_FINANCE})。只放宽到写明的口径, 不影响别的字段。
     */
    String purchase() default "";
}
