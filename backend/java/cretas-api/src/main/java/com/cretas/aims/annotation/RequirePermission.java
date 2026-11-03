package com.cretas.aims.annotation;

import java.lang.annotation.*;

/**
 * 权限检查注解
 *
 * 用于标注需要特定权限才能访问的Controller方法
 *
 * 使用示例:
 * <pre>
 * // 需要单个权限
 * @RequirePermission("production:read")
 * public ResponseEntity<?> getProductionData() { ... }
 *
 * // 需要多个权限之一
 * @RequirePermission({"production:read", "quality:read"})
 * public ResponseEntity<?> getData() { ... }
 *
 * // 需要所有权限
 * @RequirePermission(value = {"production:write", "quality:write"}, requireAll = true)
 * public ResponseEntity<?> updateData() { ... }
 * </pre>
 *
 * @author Cretas Team
 * @version 2.0.0
 * @since 2025-12-27
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequirePermission {

    /**
     * 权限代码列表
     * 格式: module:action (如 production:read, quality:write)
     */
    String[] value();

    /**
     * 是否需要拥有所有权限
     * true: 必须拥有所有指定权限
     * false: 只需拥有任一权限 (默认)
     */
    boolean requireAll() default false;

    /**
     * 与 {@link #value()} 是「且」: 过了 value 那一组之后, 还须持有这里任一权限码 (空 = 没有额外要求)。
     * 用于数据在 App 里只出现在某个模块页面上的接口 (例如财务报表只在「经营分析」页): 接口与页面同口径,
     * 与 AI 工具的 {@code ToolExecutor#getAlsoRequiredPermissions()} 对应。
     */
    String[] alsoAny() default {};

    /**
     * 权限不足时的错误消息
     */
    String message() default "权限不足，无法访问此资源";
}
