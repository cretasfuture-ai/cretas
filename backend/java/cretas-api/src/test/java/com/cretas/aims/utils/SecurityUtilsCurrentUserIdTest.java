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
    @DisplayName("旧的 SecurityContextHolder 取法已删除: getCurrentUserId/getCurrentUsername/hasPermission 不再存在")
    void securityContextBasedAccessorsAreGone() {
        java.util.List<String> names = java.util.Arrays.stream(SecurityUtils.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName).toList();
        org.assertj.core.api.Assertions.assertThat(names)
                .doesNotContain("getCurrentUserId", "getCurrentUsername", "hasPermission");
    }

    @Test
    @DisplayName("hasRole 读 request attribute role: 忽略大小写与 ROLE_ 前缀; 无请求/无角色 false")
    void hasRoleReadsRequestAttribute() {
        org.assertj.core.api.Assertions.assertThat(SecurityUtils.hasRole("factory_super_admin")).isFalse();
        bindRequest().setAttribute("role", "factory_super_admin");
        org.assertj.core.api.Assertions.assertThat(SecurityUtils.currentRole()).isEqualTo("factory_super_admin");
        org.assertj.core.api.Assertions.assertThat(SecurityUtils.hasRole("FACTORY_SUPER_ADMIN")).isTrue();
        org.assertj.core.api.Assertions.assertThat(SecurityUtils.hasRole("ROLE_factory_super_admin")).isTrue();
        org.assertj.core.api.Assertions.assertThat(SecurityUtils.hasRole("operator")).isFalse();
        org.assertj.core.api.Assertions.assertThat(SecurityUtils.hasRole(null)).isFalse();
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
