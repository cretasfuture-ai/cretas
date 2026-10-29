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

    /** 权限规格, 同 {@link PriceSensitive#permission()} ({@code |} 分隔为任一即可)。 */
    String value();
}
