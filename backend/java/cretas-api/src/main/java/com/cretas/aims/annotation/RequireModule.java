package com.cretas.aims.annotation;

import java.lang.annotation.*;

/**
 * 标注在 Controller 方法或类上，表示该操作需要对应模块已启用。
 * AOP 切面 ModuleEnabledAspect 会在方法执行前检查 factoryId 对应的模块是否启用。
 *
 * 用法:
 *   @RequireModule("sales_order")
 *   public ApiResponse<...> createOrder(@PathVariable String factoryId, ...) { ... }
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireModule {
    /** 模块代码，如 "sales_order"、"bom" */
    String value();

    /**
     * 账号侧额外认可的模块: 账号没有 {@link #value()} 的权限时, 有这里任一模块的权限
     * (且该模块在本厂已启用) 也放行。
     *
     * <p>⚠️ 只放宽「账号有没有这个模块」这一步。工厂开关仍只看 {@link #value()}:
     * 关掉 production_plan 的工厂, 这些接口照样不通。账号对 {@link #value()} 被显式 DENY 时也不放行。
     */
    String[] alsoAllowModules() default {};
}
