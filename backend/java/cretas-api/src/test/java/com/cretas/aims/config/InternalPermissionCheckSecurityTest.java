package com.cretas.aims.config;

import com.cretas.aims.controller.InternalPermissionCheckController;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 内部权限判定接口 (Python -> Java) 只认 X-Internal-Key:
 * 不带 / 带错密钥一律 403, 带着普通用户 JWT 也不行 (用户 JWT 不能当内部凭证)。
 * 密钥来自环境变量 INTERNAL_API_SECRET, 单测进程里它为空 —— 空密钥时无论带什么都拒 (fail closed)。
 */
class InternalPermissionCheckSecurityTest {
    private static final String URI = "/api/internal/permissions/user-check";
    private JwtAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new JwtAuthInterceptor();
        JwtUtil jwt = mock(JwtUtil.class);
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        ReflectionTestUtils.setField(interceptor, "factoryRepository", mock(FactoryRepository.class));
        if (java.util.Arrays.stream(JwtAuthInterceptor.class.getDeclaredFields())
                .anyMatch(f -> f.getName().equals("userRepository"))) {
            ReflectionTestUtils.setField(interceptor, "userRepository", mock(UserRepository.class));
        }
        when(jwt.validateToken("user-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("user-token")).thenReturn(101L);
        when(jwt.getFactoryIdFromToken("user-token")).thenReturn("F006");
        when(jwt.getRoleFromToken("user-token")).thenReturn("factory_super_admin");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest r = new MockHttpServletRequest("GET", URI);
        r.setRequestURI(URI);
        return r;
    }

    @Test
    void controllerLivesUnderInternalPrefixSoTheInterceptorGuardsIt() {
        assertTrue(InternalPermissionCheckController.class.getAnnotation(RequestMapping.class)
                .value()[0].startsWith("/api/internal/"));
    }

    @Test
    void noKeyIsRejected() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request(), response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void wrongKeyIsRejected() throws Exception {
        MockHttpServletRequest r = request();
        r.addHeader("X-Internal-Key", "definitely-not-the-secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(r, response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void aValidUserJwtWithoutTheKeyIsStillRejected() throws Exception {
        MockHttpServletRequest r = request();
        r.addHeader("Authorization", "Bearer user-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(r, response, null));
        assertEquals(403, response.getStatus());
    }
}
