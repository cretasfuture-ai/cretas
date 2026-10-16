package com.cretas.aims.config;

import com.cretas.aims.entity.User;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtAuthInterceptorDisabledUserTest {
    private JwtAuthInterceptor interceptor;
    private UserRepository users;
    private FactoryRepository factories;
    private JwtUtil jwt;

    @BeforeEach
    void setUp() {
        interceptor = new JwtAuthInterceptor();
        users = mock(UserRepository.class);
        factories = mock(FactoryRepository.class);
        jwt = mock(JwtUtil.class);
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        ReflectionTestUtils.setField(interceptor, "factoryRepository", factories);
        if (hasUserRepository()) ReflectionTestUtils.setField(interceptor, "userRepository", users);
        when(jwt.validateToken("synthetic-access")).thenReturn(true);
        when(jwt.getUserIdFromToken("synthetic-access")).thenReturn(101L);
        when(jwt.getFactoryIdFromToken("synthetic-access")).thenReturn("SIM24A");
        when(jwt.getRoleFromToken("synthetic-access")).thenReturn("viewer");
        when(users.findById(101L)).thenReturn(Optional.of(user(true)));
    }

    private boolean hasUserRepository() {
        return java.util.Arrays.stream(JwtAuthInterceptor.class.getDeclaredFields()).anyMatch(field -> field.getName().equals("userRepository"));
    }

    private User user(Boolean active) {
        User user = new User();
        user.setId(101L);
        user.setFactoryId("SIM24A");
        user.setIsActive(active);
        return user;
    }

    private MockHttpServletRequest request(String method, String uri, boolean authenticated) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        if (authenticated) request.addHeader("Authorization", "Bearer synthetic-access");
        return request;
    }

    @Test
    void disabledUnblacklistedTokenCannotAccessProtectedFactory() throws Exception {
        when(users.findById(101L)).thenReturn(Optional.of(user(false)));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/api/mobile/SIM24A/dashboard/overview", true), response, null));
        assertEquals(401, response.getStatus());
    }

    @Test
    void missingUserCannotAccessProtectedGlobalOrAdmin() throws Exception {
        when(users.findById(101L)).thenReturn(Optional.empty());
        for (String uri : new String[]{"/api/mobile/ai/chat", "/api/admin/users"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request("GET", uri, true), response, null));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void activeThenDisabledIsNotCached() throws Exception {
        when(users.findById(101L)).thenReturn(Optional.of(user(true)), Optional.of(user(false)));
        assertTrue(interceptor.preHandle(request("GET", "/api/mobile/SIM24A/a", true), new MockHttpServletResponse(), null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/api/mobile/SIM24A/a", true), response, null));
        assertEquals(401, response.getStatus());
        verify(users, times(2)).findById(101L);
    }

    @Test
    void nullActiveOrWrongFactoryCannotAuthorize() throws Exception {
        User wrongFactory = user(true);
        wrongFactory.setFactoryId("OTHER");
        for (User invalid : new User[]{user(null), wrongFactory}) {
            when(users.findById(101L)).thenReturn(Optional.of(invalid));
            assertFalse(interceptor.preHandle(request("GET", "/api/mobile/SIM24A/a", true), new MockHttpServletResponse(), null));
        }
    }

    @Test
    void repositoryFailureEscapesParseCatchAndStopsRequest() {
        when(users.findById(101L)).thenThrow(new IllegalStateException("synthetic-db-down"));
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> interceptor.preHandle(request("GET", "/api/mobile/SIM24A/a", true), new MockHttpServletResponse(), null));
        assertEquals("synthetic-db-down", failure.getMessage());
    }

    @Test
    void platformPrincipalWithCollidingNumericIdDoesNotReadOrdinaryUser() throws Exception {
        when(jwt.isExplicitPlatformToken("synthetic-access")).thenReturn(true);
        when(jwt.getFactoryIdFromToken("synthetic-access")).thenReturn("PLATFORM");
        when(jwt.getRoleFromToken("synthetic-access")).thenReturn("platform_admin");
        assertTrue(interceptor.preHandle(request("GET", "/api/mobile/SIM24A/a", true), new MockHttpServletResponse(), null));
        verifyNoInteractions(users, factories);
    }

    @Test
    void publicAnonymousAndCorsKeepTheirContracts() throws Exception {
        for (String uri : new String[]{"/api/mobile/auth/unified-login", "/api/mobile/auth/refresh", "/api/mobile/health", "/api/mobile/version/check"}) {
            assertTrue(interceptor.preHandle(request("GET", uri, false), new MockHttpServletResponse(), null));
            assertTrue(interceptor.preHandle(request("GET", uri, true), new MockHttpServletResponse(), null));
        }
        assertTrue(interceptor.preHandle(request("OPTIONS", "/api/mobile/SIM24A/a", true), new MockHttpServletResponse(), null));
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/api/mobile/ai/chat", false), response, null));
        assertEquals(401, response.getStatus());
        verifyNoInteractions(users);
    }

    @Test
    void internalAuthenticationRemainsIndependentOfUserState() throws Exception {
        MockHttpServletRequest request = request("GET", "/api/internal/health", true);
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request, response, null));
        assertEquals(403, response.getStatus());
        verifyNoInteractions(users);
    }

    @Test
    void activeUserStillHasCrossFactoryDenial() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/api/mobile/OTHER/a", true), response, null));
        assertEquals(403, response.getStatus());
    }

    @Test
    void legacyTokenWithoutFactoryCannotBypassDisabledGlobalPrincipalGuard() throws Exception {
        when(jwt.getFactoryIdFromToken("synthetic-access")).thenReturn(null);
        when(users.findById(101L)).thenReturn(Optional.of(user(false)));
        for (String uri : new String[]{"/api/mobile/ai/chat", "/api/mobile/upload"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request("POST", uri, true), response, null));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void legacyTokenWithoutFactoryCannotBypassMissingGlobalPrincipalGuard() throws Exception {
        when(jwt.getFactoryIdFromToken("synthetic-access")).thenReturn(null);
        when(users.findById(101L)).thenReturn(Optional.empty());
        for (String uri : new String[]{"/api/mobile/ai/chat", "/api/mobile/upload"}) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertFalse(interceptor.preHandle(request("POST", uri, true), response, null));
            assertEquals(401, response.getStatus());
        }
    }

    @Test
    void activeLegacyGlobalTokenKeepsPriorGlobalAndTenantContracts() throws Exception {
        when(jwt.getFactoryIdFromToken("synthetic-access")).thenReturn(null);
        for (String uri : new String[]{"/api/mobile/ai/chat", "/api/mobile/upload"}) {
            assertTrue(interceptor.preHandle(request("POST", uri, true), new MockHttpServletResponse(), null));
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertFalse(interceptor.preHandle(request("GET", "/api/mobile/SIM24A/a", true), response, null));
        assertEquals(403, response.getStatus());
    }
}
