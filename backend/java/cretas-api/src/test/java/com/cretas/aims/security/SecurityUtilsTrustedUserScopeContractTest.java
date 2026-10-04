package com.cretas.aims.security;

import com.cretas.aims.utils.SecurityUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityUtilsTrustedUserScopeContractTest {

    @AfterEach
    void cleanup() {
        RequestContextHolder.resetRequestAttributes();
        assertThat(SecurityUtils.currentUserId()).as("natural scope cleanup").isNull();
    }

    @Test
    void trustedUserIdIsVisibleOnlyDuringScopeAndRestoresNestedPreviousValue() {
        assertThat(SecurityUtils.currentUserId()).isNull();
        try (SecurityUtils.Scope outer = SecurityUtils.trustedUserIdScope(42L)) {
            assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
            try (SecurityUtils.Scope inner = SecurityUtils.trustedUserIdScope(7L)) {
                assertThat(SecurityUtils.currentUserId()).isEqualTo(7L);
            }
            assertThat(SecurityUtils.currentUserId()).isEqualTo(42L);
        }
        assertThat(SecurityUtils.currentUserId()).isNull();
    }

    @Test
    void requestAttributeWinsAndMalformedOrMissingRequestDoesNotFallbackToTrustedScope() {
        try (SecurityUtils.Scope trusted = SecurityUtils.trustedUserIdScope(42L)) {
            MockHttpServletRequest request = new MockHttpServletRequest();
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThat(SecurityUtils.currentUserId()).isNull();
            request.setAttribute("userId", "not-a-number");
            assertThat(SecurityUtils.currentUserId()).isNull();
            request.setAttribute("userId", 99L);
            assertThat(SecurityUtils.currentUserId()).isEqualTo(99L);
        }
    }

    @Test
    void scopeDoesNotPropagateToChildThread() throws Exception {
        try (SecurityUtils.Scope trusted = SecurityUtils.trustedUserIdScope(42L)) {
            AtomicReference<Long> childValue = new AtomicReference<>();
            Thread child = new Thread(() -> childValue.set(SecurityUtils.currentUserId()));
            child.start();
            child.join();
            assertThat(childValue).hasValue(null);
        }
    }

    @Test
    void exceptionPathCanCloseScopeWithoutStaleIdentity() {
        try {
            try (SecurityUtils.Scope ignored = SecurityUtils.trustedUserIdScope(42L)) {
                throw new IllegalStateException("tool failure");
            }
        } catch (IllegalStateException expected) {
            // expected
        }
        assertThat(SecurityUtils.currentUserId()).isNull();
    }
}
