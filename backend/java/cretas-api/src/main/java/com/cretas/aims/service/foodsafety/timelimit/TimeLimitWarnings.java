package com.cretas.aims.service.foodsafety.timelimit;

import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.ArrayList;
import java.util.List;

/**
 * 工序时限「只警告」档的警告, 挂在当前 HTTP 请求上 (PROTO-Q41)。
 *
 * <p>投料入口有四个 (逐道录入 / App 报工 / App 领料 / 二次加工开工), 各自返回不同的 DTO; 与其逐个改返回结构,
 * 判定处把警告放进请求属性, {@link TimeLimitWarningResponseAdvice} 在写成功响应时统一带回
 * ({@code severity=WARNING}, {@code errorCode=TIME_LIMIT_WARNING}, {@code actionHint}=警告原文)。
 * 失败响应不带 (投料没成功, 警告没有意义)。
 *
 * <p>没有请求上下文 (定时任务 / 单测) 时只靠调用方拿 {@code assert*} 的返回值, 这里不报错。
 */
public final class TimeLimitWarnings {

    /** 成功响应里的语义码, 前端据此显示「投料时限提醒」。 */
    public static final String RESPONSE_CODE = "TIME_LIMIT_WARNING";

    static final String ATTR = TimeLimitWarnings.class.getName() + ".list";

    private TimeLimitWarnings() {
    }

    public static void add(String message) {
        if (message == null || message.isBlank()) {
            return;
        }
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        if (attrs == null) {
            return;
        }
        List<String> list = current(attrs);
        if (list == null) {
            list = new ArrayList<>();
            attrs.setAttribute(ATTR, list, RequestAttributes.SCOPE_REQUEST);
        }
        if (!list.contains(message)) {
            list.add(message);
        }
    }

    /** 本次请求攒下的警告 (只读副本); 没有返回空表。 */
    public static List<String> peek() {
        RequestAttributes attrs = RequestContextHolder.getRequestAttributes();
        List<String> list = attrs == null ? null : current(attrs);
        return list == null ? List.of() : List.copyOf(list);
    }

    @SuppressWarnings("unchecked")
    private static List<String> current(RequestAttributes attrs) {
        Object v = attrs.getAttribute(ATTR, RequestAttributes.SCOPE_REQUEST);
        return v instanceof List<?> l ? (List<String>) l : null;
    }
}
