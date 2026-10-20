package com.cretas.aims.config;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Collections;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebLoginSessionFilterContractTest {
    final WebLoginSessionService sessions = mock(WebLoginSessionService.class);
    final CookieAuthHelper cookies = new CookieAuthHelper();
    final JwtUtil jwt = mock(JwtUtil.class);
    final WebLoginSessionFilter filter = new WebLoginSessionFilter(sessions, cookies, jwt);

    MockHttpServletRequest request(String path) {
        var request = new MockHttpServletRequest("GET", path);
        request.setCookies(new Cookie(CookieAuthHelper.WEB_SESSION_COOKIE, "fixture-session"));
        return request;
    }

    @Test void replacesExpiredAuthorizationForAllExistingHeaderConsumers() throws Exception {
        var request = request("/api/mobile/F006/products"); request.addHeader("Authorization", "Bearer expired-token");
        var response = new MockHttpServletResponse();
        ReflectionTestUtils.setField(cookies, "secureCookie", true); ReflectionTestUtils.setField(cookies, "sameSite", "Lax");
        when(sessions.resolve("fixture-session")).thenReturn("fresh-token");
        filter.doFilter(request, response, (req, res) -> {
            var http = (HttpServletRequest) req;
            assertThat(http.getHeader("authorization")).isEqualTo("Bearer fresh-token");
            assertThat(Collections.list(http.getHeaders("Authorization"))).containsExactly("Bearer fresh-token");
            assertThat(Collections.list(http.getHeaderNames())).contains("Authorization");
            assertThat(http.getAttribute(WebLoginSessionFilter.ORIGINAL_ACCESS_TOKEN)).isNull();
        });
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie ->
                assertThat(cookie).contains("cretas_web_session=", "HttpOnly", "Secure", "SameSite=Lax", "Max-Age=34560000", "Path=/"));
    }

    @Test void absentPersistentCookiePreservesMobileAuthentication() throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/mobile/F006/products");
        request.addHeader("Authorization", "Bearer mobile-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(((HttpServletRequest) req).getHeader("Authorization")).isEqualTo("Bearer mobile-token"));
        verifyNoInteractions(sessions);
    }

    @Test void invalidSessionDoesNotFallBackToOldBearer() throws Exception {
        var request = request("/api/mobile/F006/products"); request.addHeader("Authorization", "Bearer otherwise-valid");
        var response = new MockHttpServletResponse();
        when(sessions.resolve(anyString())).thenThrow(new BusinessException(401, "denied"));
        filter.doFilter(request, response, (req, res) -> fail("Invalid persistent session must not reach business"));
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeaders("Set-Cookie")).anySatisfy(cookie -> assertThat(cookie).contains("cretas_web_session=", "Max-Age=0"));
    }

    @Test void storageFailureIs503AndDoesNotEraseBrowserCredential() throws Exception {
        var response = new MockHttpServletResponse();
        when(sessions.resolve(anyString())).thenThrow(new DataAccessResourceFailureException("offline"));
        filter.doFilter(request("/api/platform/factories"), response, (req, res) -> fail("Database unavailable"));
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void loginAndPreflightDoNotGetBlockedByOldCookie() throws Exception {
        for (String path : new String[]{"/api/mobile/auth/unified-login", "/api/mobile/auth/demo-login", "/api/mobile/health"}) {
            filter.doFilter(request(path), new MockHttpServletResponse(), (req, res) -> {});
        }
        var options = request("/api/mobile/F006/products"); options.setMethod("OPTIONS");
        filter.doFilter(options, new MockHttpServletResponse(), (req, res) -> {});
        verifyNoInteractions(sessions);
    }

    @Test void logoutDoesNotRenewCookieBeforeControllerClearsIt() throws Exception {
        when(sessions.resolve(anyString())).thenReturn("fresh-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request("/api/mobile/auth/logout"), response, (req, res) -> {});
        assertThat(response.getHeaders("Set-Cookie")).isEmpty();
    }

    @Test void logoutCannotRevokeDifferentActorBearer() throws Exception {
        when(sessions.resolve(anyString())).thenReturn("fresh-token");
        when(jwt.validateToken("other-token")).thenReturn(true);
        when(jwt.getUserIdFromToken("other-token")).thenReturn(99L);
        when(jwt.getUserIdFromToken("fresh-token")).thenReturn(7L);
        var request = request("/api/mobile/auth/logout"); request.addHeader("Authorization", "Bearer other-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(req.getAttribute(WebLoginSessionFilter.ORIGINAL_ACCESS_TOKEN)).isNull());
    }

    @Test void logoutPreservesSameActorsOriginalSessionForRevocation() throws Exception {
        when(sessions.resolve(anyString())).thenReturn("fresh-token");
        when(jwt.validateToken("original-token")).thenReturn(true);
        when(jwt.getUserIdFromToken(anyString())).thenReturn(7L);
        when(jwt.getFactoryIdFromToken(anyString())).thenReturn("F006");
        var request = request("/api/mobile/auth/logout"); request.addHeader("Authorization", "Bearer original-token");
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) ->
                assertThat(req.getAttribute(WebLoginSessionFilter.ORIGINAL_ACCESS_TOKEN)).isEqualTo("original-token"));
    }
}
