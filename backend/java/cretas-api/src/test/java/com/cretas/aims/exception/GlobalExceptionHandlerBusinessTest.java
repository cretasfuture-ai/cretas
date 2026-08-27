package com.cretas.aims.exception;

import com.cretas.aims.dto.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 验证 UX 2026-04-18 增强:
 * 1. handleBusinessException 按 code 映射 HTTP status (409/403/404 ↔ CONFLICT/FORBIDDEN/NOT_FOUND, 其他→400)
 * 2. actionHint/severity/hintTarget 通过 ApiResponse.errorWithHint 进 response body
 * 3. 未带 hint 的 BusinessException 走 plain ApiResponse.error 路径,body 不含 hint 字段
 *
 * 直接实例化 handler + 调 method,不启动 Spring context,~2s 跑完.
 */
class GlobalExceptionHandlerBusinessTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void businessException_code_409_maps_to_http_409_conflict() {
        BusinessException ex = new BusinessException(409, "重复的开票申请");
        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        assertEquals(409, resp.getStatusCode().value());
    }

    @Test
    void businessException_code_403_maps_to_http_403_forbidden() {
        BusinessException ex = new BusinessException(403, "无权访问");
        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        assertEquals(403, resp.getStatusCode().value());
    }

    @Test
    void businessException_code_404_maps_to_http_404_not_found() {
        BusinessException ex = new BusinessException(404, "资源不存在");
        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        assertEquals(404, resp.getStatusCode().value());
    }

    @Test
    void businessException_unspecified_code_maps_to_http_400_bad_request() {
        BusinessException ex = new BusinessException("普通业务错误");  // default code 400
        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void businessException_unknown_code_defaults_to_http_400() {
        // 任意未在 switch 里列的 code (如 418) 走 default → 400
        BusinessException ex = new BusinessException(418, "I'm a teapot");
        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        assertEquals(400, resp.getStatusCode().value());
    }

    @Test
    void businessException_with_hint_body_contains_all_hint_fields() {
        BusinessException ex = new BusinessException(409, "重复的开票申请")
                .withHint("请先处理或撤销已有的发票申请")
                .withSeverity("BLOCKING")
                .withHintTarget("开票申请");

        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        ApiResponse<?> body = resp.getBody();

        assertNotNull(body, "response body 不应为 null");
        assertEquals("请先处理或撤销已有的发票申请", body.getActionHint());
        assertEquals("BLOCKING", body.getSeverity());
        assertEquals("开票申请", body.getHintTarget());
        assertEquals((Integer) 409, body.getCode());
        assertEquals(Boolean.FALSE, body.getSuccess());
    }

    @Test
    void businessException_without_hint_body_has_null_hint_fields() {
        BusinessException ex = new BusinessException(409, "plain 409");
        // 不调用 withHint/withSeverity/withHintTarget

        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        ApiResponse<?> body = resp.getBody();

        assertNotNull(body);
        assertNull(body.getActionHint(), "未设 actionHint 时 body.actionHint 必须是 null");
        // R73-FIX-A (2026-04-30): handler now defaults severity by HTTP code when
        // unset — 409 → "warning", 401/403/500/5xx → "error", else null.
        assertEquals("warning", body.getSeverity(),
                "code=409 未显式设 severity 时, handler 自动 fall back 到 'warning'");
        assertNull(body.getHintTarget());
    }

    @Test
    void businessException_with_only_actionHint_still_triggers_errorWithHint_path() {
        // 只设一个 hint 字段也应该走 errorWithHint 分支
        BusinessException ex = new BusinessException(400, "msg").withHint("操作提示");

        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        ApiResponse<?> body = resp.getBody();

        assertEquals("操作提示", body.getActionHint());
        assertNull(body.getSeverity());
        assertNull(body.getHintTarget());
    }

    /**
     * 🔴 2026-08-27: 缺陷所在的那一支此前**一条测试都没有**。
     *
     * 上面 {@code businessException_with_hint_body_contains_all_hint_fields} 看起来
     * 已经守住了 hintTarget —— 但它**没调 withCode**，走的是 {@code errorWithHint} 那支。
     * 缺陷在 {@code errorCode != null} 的另一支：它调的 5 参重载不带 hintTarget，
     * 于是同时设了两者的 <b>380 条</b> throw 语句，hintTarget 被静默丢掉。
     *
     * <p>用户看到的不是「没提示」，是 web-admin 弹一个带「去处理」按钮、
     * <b>点了没反应</b>的通知（{@code request.ts:99} 的 onClick 里
     * {@code if (opts.hintTarget)} 不成立）。</p>
     */
    @Test
    void businessException_with_errorCode_must_still_carry_hintTarget() {
        BusinessException ex = new BusinessException(400, "未配置原料仓库")
                .withCode("PRODUCTION_RAW_WAREHOUSE_NOT_CONFIGURED")
                .withHint("前往工厂设置配置原料仓库")
                .withHintTarget("factory-settings/warehouse");

        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        ApiResponse<?> body = resp.getBody();

        assertNotNull(body);
        assertEquals("PRODUCTION_RAW_WAREHOUSE_NOT_CONFIGURED", body.getErrorCode());
        assertEquals("前往工厂设置配置原料仓库", body.getActionHint());
        // ⛔ 这一条就是修复前红的那一条：errorCode 那支把 hintTarget 丢了
        assertEquals("factory-settings/warehouse", body.getHintTarget(),
                "设了 errorCode 就不该丢 hintTarget —— 丢了前端那个「去处理」按钮点了没反应");
    }

    /**
     * 阴性对照：errorCode 那支在**没有** hintTarget 时不许凭空造一个。
     * ⛔ 没有这条，上面那条可以用「无条件塞一个非 null」来通过。
     */
    @Test
    void businessException_with_errorCode_but_no_hintTarget_stays_null() {
        BusinessException ex = new BusinessException(400, "普通带码错误")
                .withCode("VALIDATION");

        ResponseEntity<ApiResponse<?>> resp = handler.handleBusinessException(ex);
        ApiResponse<?> body = resp.getBody();

        assertNotNull(body);
        assertEquals("VALIDATION", body.getErrorCode());
        assertNull(body.getHintTarget(), "没设 hintTarget 时必须还是 null");
    }

    @Test
    void mediaTypeNotSupported_maps_to_415_with_hint() {
        org.springframework.http.MediaType actual = org.springframework.http.MediaType.TEXT_PLAIN;
        java.util.List<org.springframework.http.MediaType> supported =
                java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON);
        org.springframework.web.HttpMediaTypeNotSupportedException ex =
                new org.springframework.web.HttpMediaTypeNotSupportedException(actual, supported);

        ApiResponse<?> body = handler.handleHttpMediaTypeNotSupportedException(ex);

        assertEquals((Integer) 415, body.getCode());
        assertEquals(Boolean.FALSE, body.getSuccess());
        assertTrue(body.getMessage().contains("text/plain"), "应该包含实际 Content-Type");
        assertTrue(body.getMessage().contains("application/json"), "应该包含期望 Content-Type");
    }
}
