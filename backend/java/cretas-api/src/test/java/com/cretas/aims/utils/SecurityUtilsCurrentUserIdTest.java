package com.cretas.aims.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份取法闸。
 *
 * <p>本项目<b>从不填充</b> Spring 的 {@code SecurityContextHolder}
 * （全仓 {@code setAuthentication} 出现 0 次），鉴权由 {@code JwtAuthInterceptor}
 * 把身份写进 <b>request attribute</b>。于是 {@code SecurityUtils.getCurrentUserId()}
 * 恒为 null，而 {@link SecurityUtils#currentUserId()} 才取得到。
 *
 * <p>⚠️ 这道闸存在的理由是一次上线事故：{@code PUT /users/me/profile} 用了前者，
 * 部署三条判据全绿而接口返 401。
 */
class SecurityUtilsCurrentUserIdTest {

    @AfterEach
    void clearRequestContext() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static MockHttpServletRequest bindRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    @Test
    @DisplayName("阳性: interceptor 写进 attribute 的 Long userId 取得到")
    void readsLongAttribute() {
        bindRequest().setAttribute("userId", 42L);
        assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
    }

    @Test
    @DisplayName("阳性: Integer / String 形态的 userId 也取得到")
    void readsIntegerAndStringAttribute() {
        bindRequest().setAttribute("userId", 7);
        assertThat(SecurityUtils.currentUserId()).isEqualTo(7L);

        bindRequest().setAttribute("userId", "8");
        assertThat(SecurityUtils.currentUserId()).isEqualTo(8L);
    }

    @Test
    @DisplayName("阴性: 没有请求上下文时返回 null —— ⛔ 不许抛异常")
    void returnsNullOutsideRequestThread() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(SecurityUtils.currentUserId()).isNull();
        assertThat(SecurityUtils.currentUsername()).isNull();
        assertThat(SecurityUtils.currentFactoryId()).isNull();
    }

    @Test
    @DisplayName("阴性: 有请求但未登录(没有 userId 这个 attribute) 返回 null")
    void returnsNullWhenAttributeAbsent() {
        bindRequest();
        assertThat(SecurityUtils.currentUserId()).isNull();
    }

    @Test
    @DisplayName("阴性: userId 是不可解析的串时返回 null, ⛔ 不抛 NumberFormatException")
    void returnsNullOnUnparseableAttribute() {
        bindRequest().setAttribute("userId", "not-a-number");
        assertThat(SecurityUtils.currentUserId()).isNull();
    }

    @Test
    @DisplayName("🔴 对照: 旧的 getCurrentUserId() 即使身份已就位也仍是 null —— 这就是那次 401")
    void deprecatedVariantStaysNullEvenWhenIdentityIsPresent() {
        bindRequest().setAttribute("userId", 42L);

        // 身份确实在请求里
        assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
        // 而走 SecurityContextHolder 的那个取法看不见它
        @SuppressWarnings("deprecation")
        Long viaSecurityContext = SecurityUtils.getCurrentUserId();
        assertThat(viaSecurityContext)
                .as("本项目不填充 SecurityContextHolder; 若这一条变红, 说明取法变了, "
                        + "请重新评估 46 个仍在用 getCurrentUserId() 的调用点")
                .isNull();
    }

    @Test
    @DisplayName("阳性: username / factoryId 同样从 attribute 取")
    void readsUsernameAndFactoryId() {
        MockHttpServletRequest request = bindRequest();
        request.setAttribute("username", "f006_warehouse_mgr");
        request.setAttribute("factoryId", "F006");

        assertThat(SecurityUtils.currentUsername()).isEqualTo("f006_warehouse_mgr");
        assertThat(SecurityUtils.currentFactoryId()).isEqualTo("F006");
    }
}
