package com.cretas.aims.service.demo;

import com.cretas.aims.controller.demo.DemoSandboxController;
import com.cretas.aims.controller.FormAssistantController;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.demo.DemoSandboxNodeGuardConfiguration.DemoSandboxNodeGuard;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.method.HandlerMethod;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class F006SandboxGatewayContractTest {

    private static final String VALID_JWT_SECRET = "sandbox-secret-012345678901234567890123456789012345678901234567890123";
    private static final String VALID_CONTROL_KEY = "control-secret-012345678901234567890";

    @Mock MobileService mobileService;
    @Mock UserRepository userRepository;
    @Mock org.springframework.beans.factory.ObjectProvider<DemoSandboxNodeGuard> nodeGuard;

    private MockWebServer manager;
    private DemoSandboxGatewayService gateway;
    private User actor;

    @BeforeEach
    void setUp() throws Exception {
        manager = new MockWebServer();
        manager.start();
        gateway = new DemoSandboxGatewayService(new ObjectMapper(), new OkHttpClient.Builder()
                .followRedirects(false).followSslRedirects(false).build(), true);
        ReflectionTestUtils.setField(gateway, "enabled", true);
        ReflectionTestUtils.setField(gateway, "controlKey", VALID_CONTROL_KEY);
        ReflectionTestUtils.setField(gateway, "managerBaseUrl", manager.url("/").toString());
        actor = new User();
        actor.setId(1309L);
        actor.setUsername("f006_admin");
        actor.setFactoryId("F006");
        actor.setRoleCode("factory_super_admin");
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.shutdown();
    }

    @Test
    void createUsesTrustedActorAndNeverForwardsProdCredentials() throws Exception {
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"data\":{\"sessionId\":\""
                        + UUID.randomUUID() + "\",\"status\":\"PREPARING\",\"generation\":1}}"));

        var data = gateway.create(actor);

        assertThat(data).containsEntry("status", "PREPARING");
        RecordedRequest request = manager.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/sessions");
        assertThat(request.getHeader("X-Sandbox-Control-Key")).isEqualTo(VALID_CONTROL_KEY);
        assertThat(request.getHeader("X-Sandbox-Actor-Id")).isEqualTo("1309");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("Cookie")).isNull();
    }

    @Test
    void proxyPreservesRawBytesQueryAndSafeHeadersOnly() throws Exception {
        UUID sessionId = UUID.randomUUID();
        manager.enqueue(new MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/json")
                .setHeader("Set-Cookie", "manager=secret")
                .setHeader("Location", "http://evil.example")
                .setBody("{\"ok\":true}"));

        var result = gateway.proxy(actor, sessionId.toString(), "/api/mobile/F006/orders",
                "page=1&filter=a", "POST", "idem-1", "multipart/form-data; boundary=x",
                "raw-body".getBytes(StandardCharsets.UTF_8));

        assertThat(result.status()).isEqualTo(207);
        assertThat(new String(result.body(), StandardCharsets.UTF_8)).isEqualTo("{\"ok\":true}");
        RecordedRequest request = manager.takeRequest();
        assertThat(request.getPath()).isEqualTo(
                "/sessions/" + sessionId + "/proxy/api/mobile/F006/orders?page=1&filter=a");
        assertThat(request.getBody().readUtf8()).isEqualTo("raw-body");
        assertThat(request.getHeader("Idempotency-Key")).isEqualTo("idem-1");
        assertThat(request.getHeader("Content-Type")).startsWith("multipart/form-data");
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getHeader("Cookie")).isNull();
    }

    @Test
    void rejectsTraversalEncodingNonF006AndAuthMeBeforeManagerCall() {
        UUID sessionId = UUID.randomUUID();
        for (String path : new String[]{
                "/api/mobile/F006/../users",
                "/api/mobile/F006/%2e%2e/users",
                "/api/mobile/F006\\users",
                "/api/mobile/F007/users",
                "/api/mobile/F006/auth/me"}) {
            assertThatThrownBy(() -> gateway.proxy(actor, sessionId.toString(), path,
                    null, "GET", null, null, null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo(path.endsWith("auth/me")
                            ? "DEMO_SANDBOX_AUTH_ME_FORBIDDEN"
                            : "DEMO_SANDBOX_TARGET_PATH_INVALID");
        }
        assertThat(manager.getRequestCount()).isZero();
        assertThatThrownBy(() -> gateway.proxy(actor, sessionId.toString(),
                "/api/mobile/F006/demo-sandbox-node/identity", null, "GET", null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_NODE_IDENTITY_FORBIDDEN");
    }

    @Test
    void disabledAndActorScopeAreFailClosed() {
        ReflectionTestUtils.setField(gateway, "enabled", false);
        assertThatThrownBy(() -> gateway.create(actor))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_DISABLED");

        User wrongFactory = new User();
        wrongFactory.setId(1309L);
        wrongFactory.setFactoryId("F007");
        wrongFactory.setRoleCode("factory_super_admin");
        assertThatThrownBy(() -> gateway.create(wrongFactory))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_FACTORY_REQUIRED");

        ReflectionTestUtils.setField(gateway, "enabled", true);
        User wrongRole = new User();
        wrongRole.setId(1309L);
        wrongRole.setFactoryId("F006");
        wrongRole.setRoleCode("platform_admin");
        assertThatThrownBy(() -> gateway.create(wrongRole))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ROLE_REQUIRED");
    }

    @Test
    void managerHttpFailureAndSessionOwnershipFailureAreExplicit() {
        manager.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        assertThatThrownBy(() -> gateway.create(actor))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_MANAGER_HTTP_ERROR");

        manager.enqueue(new MockResponse().setResponseCode(403).setBody("owner mismatch"));
        assertThatThrownBy(() -> gateway.end(actor, UUID.randomUUID().toString()))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_MANAGER_HTTP_ERROR");
    }

    @Test
    void controllerRechecksAuthenticatedF006Role() {
        UserDTO tokenUser = new UserDTO();
        tokenUser.setId(1309L);
        when(mobileService.getUserFromToken("token")).thenReturn(tokenUser);
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        DemoSandboxController controller = new DemoSandboxController(gateway, mobileService, userRepository);
        ReflectionTestUtils.setField(gateway, "enabled", false);

        assertThatThrownBy(() -> controller.create("F007", "Bearer token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_FACTORY_REQUIRED");

        actor.setRoleCode("platform_admin");
        assertThatThrownBy(() -> controller.create("F006", "Bearer token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ROLE_REQUIRED");
    }

    @Test
    void nodeGuardRejectsSharedProductionResourcesAndExposesSanitizedIdentity() {
        assertThatThrownBy(() -> new DemoSandboxNodeGuard(
                "jdbc:postgresql://postgres:5432/cretas_db", "redis", 6379,
                VALID_JWT_SECRET, sha256("production-secret")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DemoSandboxNodeGuard(
                "jdbc:postgresql://postgres:5432/f006_demo_sandbox", "localhost", 6379,
                VALID_JWT_SECRET, sha256("production-secret")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new DemoSandboxNodeGuard(
                "jdbc:postgresql://postgres:5432/f006_demo_sandbox", "redis", 6379,
                VALID_JWT_SECRET, sha256(VALID_JWT_SECRET)))
                .isInstanceOf(IllegalStateException.class);

        DemoSandboxNodeGuard guard = new DemoSandboxNodeGuard(
                "jdbc:postgresql://postgres:5432/f006_demo_sandbox?currentSchema=public",
                "redis", 6379, VALID_JWT_SECRET, sha256("production-secret"));
        assertThat(guard.identity())
                .containsEntry("mode", "F006_DEMO_SANDBOX")
                .containsEntry("scheduling", "DISABLED")
                .containsKey("databaseFingerprint")
                .containsEntry("databaseUrl", "jdbc:postgresql://postgres:5432/f006_demo_sandbox");
    }

    @Test
    void nodeIdentityEndpointRequiresF006SuperAdminAndDoesNotExposeThroughGateway() {
        UserDTO tokenUser = new UserDTO();
        tokenUser.setId(1309L);
        when(mobileService.getUserFromToken("token")).thenReturn(tokenUser);
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        DemoSandboxNodeGuard guard = new DemoSandboxNodeGuard(
                "jdbc:postgresql://postgres:5432/f006_demo_sandbox", "redis", 6379,
                VALID_JWT_SECRET, sha256("production-secret"));
        when(nodeGuard.getIfAvailable()).thenReturn(guard);
        var controller = new com.cretas.aims.controller.demo.DemoSandboxNodeController(
                mobileService, userRepository, nodeGuard);

        assertThat(controller.identity("F006", "Bearer token").getData())
                .containsEntry("mode", "F006_DEMO_SANDBOX");
        assertThatThrownBy(() -> controller.identity("F007", "Bearer token"))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_FACTORY_REQUIRED");
    }

    @Test
    void sandboxJwtCannotBeValidatedWithProductionJwtKey() {
        String productionSecret = "production-secret-012345678901234567890123456789012345678901234567890123";
        JwtUtil sandboxJwt = jwtUtil(VALID_JWT_SECRET);
        JwtUtil productionJwt = jwtUtil(productionSecret);
        String token = sandboxJwt.generateToken(1309L, "F006", "f006_admin", "factory_super_admin");

        assertThat(sandboxJwt.validateToken(token)).isTrue();
        assertThat(productionJwt.validateToken(token)).isFalse();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static JwtUtil jwtUtil(String secret) {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", secret);
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 7_200_000L);
        return jwt;
    }

    @Test
    void requestBodyLimitIsEnforced() {
        byte[] oversized = new byte[DemoSandboxGatewayService.MAX_REQUEST_BODY_BYTES + 1];
        assertThatThrownBy(() -> gateway.proxy(actor, UUID.randomUUID().toString(),
                "/api/mobile/F006/upload", null, "POST", null,
                "application/octet-stream", oversized))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_REQUEST_TOO_LARGE");
    }

    @Test
    void activeSandboxSessionBlocksOnlyTrustedActorProductionWritesAndFailsClosedOnManagerDown() throws Exception {
        UserDTO tokenUser = new UserDTO();
        tokenUser.setId(1309L);
        when(mobileService.getUserFromToken("token")).thenReturn(tokenUser);
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"sessionId\":\""
                        + UUID.randomUUID() + "\",\"status\":\"READY\"}}"));
        var interceptor = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/purchase-orders");
        request.addHeader("Authorization", "Bearer token");
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ACTIVE_SESSION");

        actor.setRoleCode("procurement_manager");
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"sessionId\":\""
                        + UUID.randomUUID() + "\",\"status\":\"READY\"}}"));
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ACTIVE_SESSION");

        manager.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_MANAGER_UNAVAILABLE");
    }

    @Test
    void writeGuardLeavesAuthAndNonTrustedActorsToTheNormalAuthLayer() throws Exception {
        var interceptor = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        for (String path : new String[]{
                "/api/mobile/auth/unified-login",
                "/api/mobile/auth/refresh",
                "/api/mobile/auth/logout"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        }

        UserDTO staffToken = new UserDTO();
        staffToken.setId(2001L);
        when(mobileService.getUserFromToken("staff-token")).thenReturn(staffToken);
        MockHttpServletRequest staff = new MockHttpServletRequest("POST", "/api/mobile/F006/orders");
        staff.addHeader("Authorization", "Bearer staff-token");
        assertThat(interceptor.preHandle(staff, new MockHttpServletResponse(), null)).isTrue();

        actor.setFactoryId("F007");
        when(mobileService.getUserFromToken("other-token")).thenReturn(tokenUser(1309L));
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        MockHttpServletRequest otherFactory = new MockHttpServletRequest("POST", "/api/mobile/F007/orders");
        otherFactory.addHeader("Authorization", "Bearer other-token");
        assertThat(interceptor.preHandle(otherFactory, new MockHttpServletResponse(), null)).isTrue();
        assertThat(manager.getRequestCount()).isZero();
    }

    @Test
    void trustedActorWithoutActiveSessionPassesAndActiveSessionIsFenced() throws Exception {
        var interceptor = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        when(mobileService.getUserFromToken("token")).thenReturn(tokenUser(1309L));
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/orders");
        request.addHeader("Authorization", "Bearer token");

        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":null}"));
        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();

        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ACTIVE_SESSION");
    }

    @Test
    void cookieAuthenticatedActorSetByJwtFilterIsStillFencedWithoutAuthorizationHeader() {
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        var interceptor = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/mobile/F006/orders");
        request.setAttribute("userId", 1309L);
        request.setCookies(new jakarta.servlet.http.Cookie("access_token", "jwt-from-cookie"));
        try {
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), null))
                    .isInstanceOf(BusinessException.class)
                    .extracting("errorCode")
                    .isEqualTo("DEMO_SANDBOX_ACTIVE_SESSION");
        } finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    @Test
    void embeddedMvcCookieChainRunsJwtIdentityBeforeSandboxGuardAndLeavesLoginUnchanged() throws Exception {
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        var guard = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        HandlerInterceptor jwtCookieIdentity = new HandlerInterceptor() {
            @Override
            public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,
                                     jakarta.servlet.http.HttpServletResponse response,
                                     Object handler) {
                if (request.getCookies() != null) request.setAttribute("userId", 1309L);
                return true;
            }
        };
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new CookieChainController())
                .addInterceptors(jwtCookieIdentity, guard)
                .build();

        mvc.perform(post("/api/mobile/F006/orders")
                        .cookie(new jakarta.servlet.http.Cookie("access_token", "jwt-cookie")))
                .andExpect(status().isConflict());
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        mvc.perform(post("/api/mobile/auth/change-password")
                        .cookie(new jakarta.servlet.http.Cookie("access_token", "jwt-cookie")))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/mobile/auth/unified-login"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/mobile/auth/refresh"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/mobile/auth/logout"))
                .andExpect(status().isOk());
    }

    @Test
    void activeSessionAndCleanupRemainBoundToImmutableActorIdAfterRename() throws Exception {
        actor.setUsername("renamed-f006-admin");
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        assertThat(gateway.activeSession(actor)).isPresent();
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"ENDED\"}}"));
        assertThat(gateway.end(actor, UUID.randomUUID().toString())).containsEntry("status", "ENDED");
    }

    @Test
    void proxyRequiresCurrentCreateIdentityWhileCleanupRemainsAllowed() {
        actor.setRoleCode("procurement_manager");
        assertThatThrownBy(() -> gateway.proxy(actor, UUID.randomUUID().toString(),
                "/api/mobile/F006/orders", null, "POST", null,
                "application/json", new byte[0]))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ROLE_REQUIRED");

        actor.setRoleCode("factory_super_admin");
        actor.setUsername("renamed-f006-admin");
        assertThatThrownBy(() -> gateway.proxy(actor, UUID.randomUUID().toString(),
                "/api/mobile/F006/orders", null, "POST", null,
                "application/json", new byte[0]))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ACTOR_REQUIRED");
        assertThat(manager.getRequestCount()).isZero();
    }

    @Test
    void activeSessionEndpointReturnsNullForF006NonDemoUser() throws Exception {
        actor.setId(2001L);
        actor.setUsername("f006_staff");
        actor.setRoleCode("procurement_manager");
        when(mobileService.getUserFromToken("staff-token")).thenReturn(tokenUser(2001L));
        when(userRepository.findById(2001L)).thenReturn(Optional.of(actor));
        var controller = new DemoSandboxController(gateway, mobileService, userRepository);
        assertThat(controller.activeSession("F006", "Bearer staff-token").getData()).isNull();
        assertThat(manager.getRequestCount()).isZero();
    }

    @Test
    void disabledActiveSessionEndpointReturnsSuccessWithNullData() {
        ReflectionTestUtils.setField(gateway, "enabled", false);
        when(mobileService.getUserFromToken("token")).thenReturn(tokenUser(1309L));
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        var controller = new DemoSandboxController(gateway, mobileService, userRepository);
        assertThat(controller.activeSession("F006", "Bearer token").getData()).isNull();
        assertThat(manager.getRequestCount()).isZero();
    }

    @Test
    void onlyFormAssistantTextParseHandlerMayRunWithActiveSandbox() throws Exception {
        var interceptor = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        FormAssistantController controller = new FormAssistantController();
        MockHttpServletRequest parseRequest = new MockHttpServletRequest(
                "POST", "/api/mobile/F006/form-assistant/parse");
        parseRequest.addHeader("Authorization", "Bearer token");
        when(mobileService.getUserFromToken("token")).thenReturn(tokenUser(1309L));
        when(userRepository.findById(1309L)).thenReturn(Optional.of(actor));
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        assertThat(interceptor.preHandle(parseRequest, new MockHttpServletResponse(),
                formAssistantHandler(controller, "parseFormInput"))).isTrue();
        assertThat(manager.getRequestCount()).isZero();

        MockHttpServletRequest streamRequest = new MockHttpServletRequest(
                "POST", "/api/mobile/F006/form-assistant/parse/stream");
        streamRequest.addHeader("Authorization", "Bearer token");
        manager.enqueue(new MockResponse().setResponseCode(200)
                .setBody("{\"success\":true,\"data\":{\"status\":\"READY\"}}"));
        assertThatThrownBy(() -> interceptor.preHandle(streamRequest, new MockHttpServletResponse(),
                formAssistantHandler(controller, "parseFormInputStream")))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo("DEMO_SANDBOX_ACTIVE_SESSION");
    }

    private static HandlerMethod formAssistantHandler(FormAssistantController controller, String method)
            throws NoSuchMethodException {
        return new HandlerMethod(controller, FormAssistantController.class.getMethod(
                method, String.class, FormAssistantController.FormParseRequest.class,
                jakarta.servlet.http.HttpServletRequest.class));
    }

    private static UserDTO tokenUser(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        return user;
    }

    @RestController
    static class CookieChainController {
        @PostMapping("/api/mobile/F006/orders")
        ResponseEntity<Void> write() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/mobile/auth/unified-login")
        ResponseEntity<Void> login() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/mobile/auth/change-password")
        ResponseEntity<Void> changePassword() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/mobile/auth/refresh")
        ResponseEntity<Void> refresh() {
            return ResponseEntity.ok().build();
        }

        @PostMapping("/api/mobile/auth/logout")
        ResponseEntity<Void> logout() {
            return ResponseEntity.ok().build();
        }

        @ExceptionHandler(BusinessException.class)
        ResponseEntity<Void> business(BusinessException exception) {
            return ResponseEntity.status(exception.getCode()).build();
        }
    }

    @Test
    void activeSessionLifecycleAndProxyWritesAreExcludedFromProductionWriteFence() throws Exception {
        var interceptor = new DemoSandboxWriteGuardConfiguration.DemoSandboxWriteGuardInterceptor(
                gateway, mobileService, userRepository);
        for (String uri : new String[]{
                "/api/mobile/F006/demo-sandbox/sessions",
                "/api/mobile/F006/demo-sandbox/sessions/id/proxy/api/mobile/F006/orders"}) {
            MockHttpServletRequest request = new MockHttpServletRequest("POST", uri);
            assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), null)).isTrue();
        }
        assertThat(manager.getRequestCount()).isZero();
    }

    @Test
    void enabledWriteGuardConfigurationRegistersWithoutSelfBeanCycle() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", java.util.Map.of(
                            "cretas.demo.sandbox.enabled", "true",
                            "cretas.demo.sandbox.control-key", VALID_CONTROL_KEY)));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(DemoSandboxGatewayService.class,
                    () -> new DemoSandboxGatewayService(new ObjectMapper()));
            context.registerBean(MobileService.class, () -> mobileService);
            context.registerBean(UserRepository.class, () -> userRepository);
            context.register(DemoSandboxWriteGuardConfiguration.class);
            context.refresh();
            assertThat(context.getBean(DemoSandboxWriteGuardConfiguration.class)).isNotNull();
        }
    }

    @Test
    void springComponentScanAutoRegistersGatewayAndDemoControllers() {
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(MobileService.class, () -> mobileService);
            context.registerBean(UserRepository.class, () -> userRepository);
            context.scan("com.cretas.aims.service.demo", "com.cretas.aims.controller.demo");
            context.refresh();
            assertThat(context.getBean(DemoSandboxGatewayService.class)).isNotNull();
            assertThat(context.getBean(DemoSandboxController.class)).isNotNull();
            assertThat(context.getBean(com.cretas.aims.controller.demo.DemoSandboxNodeController.class))
                    .isNotNull();
        }
    }
}
