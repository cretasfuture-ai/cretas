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
     * 账号没有 {@link #value()} 模块时，仍然放行这条端点的角色码 (FactoryUserRole 名, 精确匹配)。
     *
     * <p>用于「一条跨模块的流程走到了别的模块的控制器上」: 财务经理审批报废 (控制器挂在质检模块)、
     * 仓管分配发货批次 (控制器挂在销售模块)。⛔ 只放这一个角色的这一条端点, 不是给角色开模块:
     * 工厂级模块开关照常读 {@link #value()}, {@code @RequirePermission} 照常判,
     * 账号上显式 DENY 了 {@link #value()} 的人也不放行。
     *
     * <p>⛔ 不写成「另一个模块也算」: 模块是按权限模块展开的, 同模块下别的角色 (出纳之于财务、
     * 仓库员之于仓储) 会被一起放进来, 范围就变了。
     */
    String[] alsoAllowRoles() default {};

    /**
     * 账号侧额外认可的模块: 账号没有 {@link #value()} 的权限时, 有这里任一模块的权限
     * (且该模块在本厂已启用) 也放行。
     *
     * <p>⚠️ 只放宽「账号有没有这个模块」这一步。工厂开关仍只看 {@link #value()}:
     * 关掉 production_plan 的工厂, 这些接口照样不通。账号对 {@link #value()} 被显式 DENY 时也不放行。
     */
    String[] alsoAllowModules() default {};
}
