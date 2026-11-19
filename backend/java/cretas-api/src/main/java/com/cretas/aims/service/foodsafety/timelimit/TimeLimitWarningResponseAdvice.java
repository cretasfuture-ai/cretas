package com.cretas.aims.service.foodsafety.timelimit;

import com.cretas.aims.dto.common.ApiResponse;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.util.List;

/**
 * 把本次请求里攒下的工序时限警告 ({@link TimeLimitWarnings}) 带进<b>成功</b>的统一响应 (PROTO-Q41):
 * {@code severity=WARNING}、{@code errorCode=TIME_LIMIT_WARNING}、{@code actionHint}=警告原文 (多条用换行拼)。
 * web-admin 请求拦截器与 App 的 apiClient 认这个 errorCode 弹一条「投料时限提醒」。
 *
 * <p>⛔ 不覆盖调用方已经填的 severity / errorCode; actionHint 已有时追加在后面。失败响应不动。
 */
@ControllerAdvice
public class TimeLimitWarningResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        return attach(body, TimeLimitWarnings.peek());
    }

    /** 包内可见: 单测直接验拼装规则。 */
    static Object attach(Object body, List<String> warnings) {
        if (warnings.isEmpty() || !(body instanceof ApiResponse<?> res) || !Boolean.TRUE.equals(res.getSuccess())) {
            return body;
        }
        String text = String.join("\n", warnings);
        if (res.getSeverity() == null) {
            res.setSeverity("WARNING");
        }
        if (res.getErrorCode() == null) {
            res.setErrorCode(TimeLimitWarnings.RESPONSE_CODE);
        }
        res.setActionHint(res.getActionHint() == null || res.getActionHint().isBlank()
                ? text : res.getActionHint() + "\n" + text);
        return body;
    }
}
