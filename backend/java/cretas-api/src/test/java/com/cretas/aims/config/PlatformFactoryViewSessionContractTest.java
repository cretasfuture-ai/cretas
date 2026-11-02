package com.cretas.aims.config;

import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformFactoryViewSessionContractTest {
    private JwtUtil jwt;
    private JwtAuthInterceptor auth;
    private WebLoginSessionFilter filter;
    private WebLoginSessionService sessions;
    private UserRepository users;

    @BeforeEach
    void setUp() {
        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", "cretas-test-secret-key-for-view-session-contract");
        ReflectionTestUtils.setField(jwt, "expiration", 86400000L);
        sessions = mock(WebLoginSessionService.class);
        users = mock(UserRepository.class);
        auth = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(auth, "jwtUtil", jwt);
        ReflectionTestUtils.setField(auth, "tokenBlacklistService", mock(TokenBlacklistService.class));
        ReflectionTestUtils.setField(auth, "webLoginSessions", sessions);
        ReflectionTestUtils.setField(auth, "factoryRepository", mock(FactoryRepository.class));
        ReflectionTestUtils.setField(auth, "userRepository", users);
        filter = new WebLoginSessionFilter(sessions, new CookieAuthHelper(), jwt, auth);
    }

    private MockHttpServletRequest request(String method, String factory, String token) {
        var request = new MockHttpServletRequest(method, "/api/mobile/" + factory + "/reports/dashboard/overview");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("X-Client-Type", "platform-factory-view");
        request.addHeader("X-Factory-View-Token", "true");
        request.setCookies(new Cookie(CookieAuthHelper.WEB_SESSION_COOKIE, "existing-platform-session"));
        return request;
    }

    @Test
    void persistentPlatformCookieCannotReplaceTheScopedViewBearer() throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        var request = request("GET", "F001", token);
        assertThat(filter.shouldNotFilter(request)).isTrue(); // Native skip also exists in the pre-view release.
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            var http = (HttpServletRequest) incoming;
            assertThat(http.getHeader("Authorization")).isEqualTo("Bearer " + token);
            try { assertThat(auth.preHandle(http, (HttpServletResponse) outgoing, new Object())).isTrue(); }
            catch (Exception error) { throw new IllegalStateException(error); }
            assertThat(AuthenticatedActor.current(http).orElseThrow().factoryId()).isEqualTo("PLATFORM");
            assertThat(http.getAttribute("factoryId")).isEqualTo("F001");
        });
        verifyNoInteractions(sessions, users);
    }

    @Test
    void cookieDoesNotRescueCrossFactoryOrMutationRequests() throws Exception {
        String token = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        for (String[] attempt : new String[][]{{"GET", "F002"}, {"POST", "F001"}}) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(attempt[0], attempt[1], token), response, (incoming, outgoing) -> {
                try { assertThat(auth.preHandle((HttpServletRequest) incoming, (HttpServletResponse) outgoing, new Object())).isFalse(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            });
            assertThat(response.getStatus()).isEqualTo(403);
        }
        verifyNoInteractions(sessions, users);
    }

    @Test
    void invalidViewTransportDoesNotFallBackToTheBrowserCookie() throws Exception {
        var request = request("GET", "F001", "invalid-view-token");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (incoming, outgoing) -> {
            try { assertThat(auth.preHandle((HttpServletRequest) incoming, (HttpServletResponse) outgoing, new Object())).isFalse(); }
            catch (Exception error) { throw new IllegalStateException(error); }
        });
        assertThat(response.getStatus()).isEqualTo(401);
        verifyNoInteractions(sessions, users);
    }

    @Test
    void primaryOnlyRollbackDecoderRejectsViewButStillAcceptsOrdinaryCredentials() {
        SecretKey oldKey = ReflectionTestUtils.invokeMethod(jwt, "getSigningKey");
        String view = jwt.generatePlatformFactoryViewToken(7L, "admin", "platform_admin", "F001");
        assertThatThrownBy(() -> Jwts.parserBuilder().setSigningKey(oldKey).build().parseClaimsJws(view))
                .isInstanceOf(SignatureException.class);
        String ordinary = jwt.generateToken(7L, "F001", "admin", "factory_super_admin");
        assertThat(Jwts.parserBuilder().setSigningKey(oldKey).build().parseClaimsJws(ordinary).getBody().get("factoryId"))
                .isEqualTo("F001");
    }

    @Test
    void dedicatedKeyMatchesTheCrossLanguageDerivationVector() {
        SecretKey key = ReflectionTestUtils.invokeMethod(jwt, "getFactoryViewSigningKey");
        assertThat(java.util.HexFormat.of().formatHex(key.getEncoded()))
                .isEqualTo("b714f3b52912024a0c13bc10a4041e022cd3c508c3fa448328f6d3f3f29ea055");
    }
}
