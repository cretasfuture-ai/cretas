package com.cretas.aims.config;

import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.argThat;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PlatformFactoryViewSecurityContractTest {
    @Mock HttpServletRequest request;
    @Mock HttpServletResponse response;
    @Mock com.cretas.aims.service.TokenBlacklistService blacklist;
    @Mock com.cretas.aims.service.mobile.WebLoginSessionService sessions;
    @Mock com.cretas.aims.repository.FactoryRepository factories;
    @Mock com.cretas.aims.repository.UserRepository users;

    private JwtUtil jwt;
    private JwtAuthInterceptor interceptor;

    @BeforeEach
    void setUp() throws Exception {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "cretas-test-secret-key-for-view-contract");
        ReflectionTestUtils.setField(jwt, "expiration", 86_400_000L);
        interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", blacklist);
        ReflectionTestUtils.setField(interceptor, "webLoginSessions", sessions);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", factories);
        ReflectionTestUtils.setField(interceptor, "userRepository", users);
        when(blacklist.isBlacklisted(anyString())).thenReturn(false);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
    }

    @Test
    void targetReadPreservesTypedPlatformActor() throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        prepare("GET", "/api/mobile/F001/reports/dashboard/overview", token);
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verify(request).setAttribute(eq(AuthenticatedActor.REQUEST_ATTRIBUTE), argThat(value -> {
            AuthenticatedActor actor = (AuthenticatedActor) value;
            return actor.type().name().equals("PLATFORM_ADMIN") && actor.factoryId().equals("PLATFORM");
        }));
    }

    @Test
    void crossFactoryDuplicateTenantUnsafeGetWritesAndManagementAreDenied() throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        assertDenied("GET", "/api/mobile/F002/reports/dashboard/overview", token, null);
        assertDenied("GET", "/api/mobile/F001/reports/dashboard/overview", token, new String[]{"F001", "F002"});
        assertDenied("GET", "/api/mobile/F001/scheduling/config", token, null);
        assertDenied("POST", "/api/mobile/F001/query/run", token, null);
        assertDenied("GET", "/api/platform/factories", token, null);
    }

    @Test
    void incompleteAndExpiredSignedMarkersAreDenied() throws Exception {
        Map<String, Object> partial = new HashMap<>();
        partial.put("factoryId", "PLATFORM");
        partial.put("platformFactoryView", true);
        String partialToken = (String) ReflectionTestUtils.invokeMethod(jwt, "createToken", partial, "admin", 86_400_000L);
        assertDenied("GET", "/api/mobile/F001/reports/dashboard/overview", partialToken, null);
        Map<String, Object> expired = new HashMap<>();
        expired.put("factoryId", "PLATFORM"); expired.put("actorType", "PLATFORM_ADMIN");
        expired.put("platformFactoryView", true); expired.put("viewFactoryId", "F001"); expired.put("viewReadOnly", true);
        String expiredToken = (String) ReflectionTestUtils.invokeMethod(jwt, "createToken", expired, "admin", -1L);
        assertDenied("GET", "/api/mobile/F001/reports/dashboard/overview", expiredToken, null, HttpServletResponse.SC_UNAUTHORIZED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"factoryId", "factory_id", "viewFactoryId", "view_factory_id", "tenantId", "tenant_id"})
    void everyTenantQueryAliasRejectsMixedAndForeignValues(String name) throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        for (String[] values : new String[][]{{"F002"}, {"F001", "F002"}, {""}}) {
            reset(request, response);
            prepare("GET", "/api/mobile/F001/reports/dashboard/overview", token);
            when(request.getParameterValues(name)).thenReturn(values);
            when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
            assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
            verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
    }

    @Test
    void contradictorySignedClaimsAndLongLifetimeNeverBecomeOrdinaryPlatformCredentials() throws Exception {
        String valid = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        io.jsonwebtoken.Claims claims = ReflectionTestUtils.invokeMethod(jwt, "getClaimsFromToken", valid);
        for (Map<String, Object> change : java.util.List.<Map<String, Object>>of(
                Map.of("actorType", "FACTORY_USER"), Map.of("factoryId", "F001"),
                Map.of("role", "developer"), Map.of("viewFactoryId", ""),
                Map.of("viewFactoryId", "x".repeat(65)), Map.of("viewReadOnly", false))) {
            Map<String, Object> modified = new HashMap<>(claims);
            modified.putAll(change);
            String token = ReflectionTestUtils.invokeMethod(jwt, "createToken", modified, "admin", 900_000L);
            int expected = change.containsKey("actorType") || change.containsKey("factoryId")
                    ? HttpServletResponse.SC_UNAUTHORIZED : HttpServletResponse.SC_FORBIDDEN;
            assertDenied("GET", "/api/mobile/F001/reports/dashboard/overview", token, null, expected);
        }
        String longLived = ReflectionTestUtils.invokeMethod(jwt, "createToken", new HashMap<>(claims), "admin", 86_400_000L);
        assertDenied("GET", "/api/mobile/F001/reports/dashboard/overview", longLived, null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "/config/modules/production_plan/effective", "/config/modules/material_batch/effective",
            "/config/modules/purchase_order/effective", "/config/modules/sales_order/effective",
            "/workflow-stats/production", "/workflow-stats/purchase", "/workflow-stats/sales"})
    void supportedPageMetadataIsReadableOnlyWithinItsExactFactoryAndPath(String suffix) throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        prepare("GET", "/api/mobile/F001" + suffix, token);
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
        verifyNoInteractions(users);
        assertDenied("POST", "/api/mobile/F001" + suffix, token, null);
        assertDenied("GET", "/api/mobile/F002" + suffix, token, null);
        assertDenied("GET", "/api/mobile/F001" + suffix, token, new String[]{"F001", "F002"});
        assertDenied("GET", "/api/mobile/F001" + suffix + "/details", token, null);
    }

    @Test
    void metadataAllowanceDoesNotOpenOtherModulesOrWorkflowNamespaces() throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        for (String suffix : java.util.List.of("/config/modules/finance/effective",
                "/config/modules/production_report/effective", "/config/modules/quality_inspection/effective",
                "/workflow-stats/finance", "/workflow-stats/inventory", "/workflow-stats")) {
            assertDenied("GET", "/api/mobile/F001" + suffix, token, null);
        }
    }

    private void assertDenied(String method, String uri, String token, String[] factoryValues) throws Exception {
        assertDenied(method, uri, token, factoryValues, HttpServletResponse.SC_FORBIDDEN);
    }

    private void assertDenied(String method, String uri, String token, String[] factoryValues, int expectedStatus) throws Exception {
        reset(request, response);
        prepare(method, uri, token);
        when(request.getParameterValues("factoryId")).thenReturn(factoryValues);
        when(response.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        assertThat(interceptor.preHandle(request, response, new Object())).isFalse();
        verify(response).setStatus(expectedStatus);
    }

    private void prepare(String method, String uri, String token) {
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getHeader("Authorization")).thenReturn("Bearer " + token);
        when(request.getParameterValues("factoryId")).thenReturn(null);
        when(request.getParameterValues("factory_id")).thenReturn(null);
        when(request.getAttribute(anyString())).thenReturn(null);
    }
}
