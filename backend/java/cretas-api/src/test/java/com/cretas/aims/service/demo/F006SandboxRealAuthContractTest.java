package com.cretas.aims.service.demo;

import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.config.DemoReadOnlyInterceptor;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.config.WebMvcConfig;
import com.cretas.aims.controller.demo.DemoSandboxController;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.utils.CookieAuthHelper;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The real JWT interceptor and the real MVC registration must precede the real sandbox
 * write guard.  This deliberately uses a small MVC context, not the application/JPA context.
 */
class F006SandboxRealAuthContractTest {
    private static final String SECRET =
            "f006-real-auth-test-secret-012345678901234567890123456789";
    private static final String CONTROL_KEY =
            "f006-real-auth-control-key-012345678901234567890";

    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private MockWebServer manager;
    private JwtUtil jwt;
    private UserRepository users;
    private MobileService mobile;
    private AtomicInteger productionWrites;
    private static AtomicInteger productionWriteCounter;
    private User actor;
    private String actorToken;

    @BeforeEach
    void setUp() throws Exception {
        manager = new MockWebServer();
        manager.start();
        productionWrites = new AtomicInteger();
        productionWriteCounter = productionWrites;

        actor = new User();
        actor.setId(1309L);
        actor.setUsername("f006_admin");
        actor.setFactoryId("F006");
        actor.setRoleCode("factory_super_admin");
        actor.setIsActive(true);

        users = mock(UserRepository.class);
        mobile = mock(MobileService.class);
        when(users.findById(1309L)).thenReturn(Optional.of(actor));
        when(mobile.getUserFromToken(any())).thenAnswer(invocation -> {
            UserDTO dto = new UserDTO();
            dto.setId(1309L);
            return dto;
        });

        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", SECRET);
        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        ReflectionTestUtils.setField(jwt, "refreshExpiration", 7_200_000L);
        actorToken = jwt.generateToken(1309L, "F006", "f006_admin", "factory_super_admin");

        DemoSandboxGatewayService gateway = new DemoSandboxGatewayService(
                new ObjectMapper(),
                new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build(),
                true);
        ReflectionTestUtils.setField(gateway, "enabled", true);
        ReflectionTestUtils.setField(gateway, "controlKey", CONTROL_KEY);
        ReflectionTestUtils.setField(gateway, "managerBaseUrl", manager.url("/").toString());

        JwtAuthInterceptor realJwt = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(realJwt, "jwtUtil", jwt);
        ReflectionTestUtils.setField(realJwt, "tokenBlacklistService", allowlistedBlacklist());
        ReflectionTestUtils.setField(realJwt, "webLoginSessions", mock(WebLoginSessionService.class));
        ReflectionTestUtils.setField(realJwt, "factoryRepository", mock(FactoryRepository.class));
        ReflectionTestUtils.setField(realJwt, "userRepository", users);

        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("real-auth-test",
                Map.of("cretas.demo.sandbox.enabled", "true")));
        context.addBeanFactoryPostProcessor(factory -> {
            factory.registerSingleton("objectMapper", new ObjectMapper());
            factory.registerSingleton("jwtUtil", jwt);
            factory.registerSingleton("jwtAuthInterceptor", realJwt);
            factory.registerSingleton("tokenBlacklistService", allowlistedBlacklist());
            factory.registerSingleton("webLoginSessionService", mock(WebLoginSessionService.class));
            factory.registerSingleton("factoryRepository", mock(FactoryRepository.class));
            factory.registerSingleton("userRepository", users);
            factory.registerSingleton("mobileService", mobile);
            factory.registerSingleton("demoSandboxGatewayService", gateway);
            factory.registerSingleton("demoReadOnlyInterceptor", allow(DemoReadOnlyInterceptor.class));
            factory.registerSingleton("permissionInterceptor", allow(PermissionInterceptor.class));
            factory.registerSingleton("requireRoleInterceptor", allow(RequireRoleInterceptor.class));
            factory.registerSingleton("moduleEnabledInterceptor", allow(ModuleEnabledInterceptor.class));
            factory.registerSingleton("dataScopeRouteInterceptor", allow(DataScopeRouteInterceptor.class));
            factory.registerSingleton("processSecretGuardInterceptor", allow(ProcessSecretGuardInterceptor.class));
            factory.registerSingleton("platformDataAccessLogInterceptor", allow(PlatformDataAccessLogInterceptor.class));
        });
        context.register(Mvc.class, ProductionWriteProbe.class, Errors.class);
        context.refresh();
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (context != null) context.close();
        if (manager != null) manager.shutdown();
    }

    @Test
    void realJwtHeaderAndCookieIdentityFenceActiveSessionBeforeProductionController() throws Exception {
        manager.enqueue(activeSessionResponse());
        mvc.perform(post("/api/mobile/F006/orders")
                        .header("Authorization", "Bearer " + actorToken))
                .andExpect(status().isConflict());
        assertThat(productionWrites).hasValue(0);

        manager.enqueue(activeSessionResponse());
        mvc.perform(post("/api/mobile/F006/orders")
                        .cookie(new Cookie(CookieAuthHelper.ACCESS_TOKEN_COOKIE, actorToken)))
                .andExpect(status().isConflict());
        assertThat(productionWrites).hasValue(0);
    }

    @Test
    void realJwtSandboxProxyReachesManagerWithoutForwardingNodeCredentials() throws Exception {
        UUID session = UUID.randomUUID();
        manager.enqueue(new MockResponse().setResponseCode(207)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"ok\":true}"));

        mvc.perform(post("/api/mobile/F006/demo-sandbox/sessions/" + session
                        + "/proxy/api/mobile/F006/orders?source=real-auth")
                        .header("Authorization", "Bearer " + actorToken)
                        .cookie(new Cookie(CookieAuthHelper.ACCESS_TOKEN_COOKIE, "ignored-cookie"))
                        .header("Idempotency-Key", "real-auth-idem")
                        .contentType("application/json")
                        .content("{\"quantity\":1}"))
                .andExpect(status().isMultiStatus())
                .andExpect(content().json("{\"ok\":true}"));

        RecordedRequest forwarded = manager.takeRequest();
        assertThat(forwarded.getHeader("Authorization")).isNull();
        assertThat(forwarded.getHeader("Cookie")).isNull();
        assertThat(forwarded.getHeader(DemoSandboxGatewayService.CONTROL_KEY_HEADER)).isEqualTo(CONTROL_KEY);
        assertThat(forwarded.getHeader(DemoSandboxGatewayService.ACTOR_HEADER)).isEqualTo("1309");
        assertThat(forwarded.getHeader("Idempotency-Key")).isEqualTo("real-auth-idem");
        assertThat(forwarded.getBody().readUtf8()).isEqualTo("{\"quantity\":1}");
    }

    @Test
    void nonDemoActorAndCrossTenantClaimsAreRejectedBeforeManagerOrProductionController() throws Exception {
        User staff = new User();
        staff.setId(2001L);
        staff.setUsername("f006_staff");
        staff.setFactoryId("F006");
        staff.setRoleCode("procurement_manager");
        staff.setIsActive(true);
        when(users.findById(2001L)).thenReturn(Optional.of(staff));
        String staffToken = jwt.generateToken(2001L, "F006", "f006_staff", "procurement_manager");
        UserDTO staffDto = new UserDTO();
        staffDto.setId(2001L);
        when(mobile.getUserFromToken(staffToken)).thenReturn(staffDto);

        mvc.perform(post("/api/mobile/F006/demo-sandbox/sessions")
                        .header("Authorization", "Bearer " + staffToken))
                .andExpect(status().isForbidden());
        assertThat(manager.getRequestCount()).isZero();

        String wrongFactoryToken = jwt.generateToken(1309L, "F007", "f006_admin", "factory_super_admin");
        mvc.perform(post("/api/mobile/F006/orders")
                        .header("Authorization", "Bearer " + wrongFactoryToken))
                .andExpect(status().isUnauthorized());
        assertThat(productionWrites).hasValue(0);

        mvc.perform(post("/api/mobile/F007/orders")
                        .header("Authorization", "Bearer " + actorToken))
                .andExpect(status().isForbidden());
        assertThat(productionWrites).hasValue(0);
    }

    @Test
    void missingExpiredAndManagerUnavailableSessionsFailClosedBeforeProductionController() throws Exception {
        mvc.perform(post("/api/mobile/F006/orders"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/mobile/F006/orders").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized());
        assertThat(productionWrites).hasValue(0);

        ReflectionTestUtils.setField(jwt, "expiration", -1L);
        String expired = jwt.generateToken(1309L, "F006", "f006_admin", "factory_super_admin");
        mvc.perform(post("/api/mobile/F006/orders")
                        .header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
        assertThat(productionWrites).hasValue(0);

        ReflectionTestUtils.setField(jwt, "expiration", 3_600_000L);
        manager.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        mvc.perform(post("/api/mobile/F006/orders")
                        .header("Authorization", "Bearer " + actorToken))
                .andExpect(status().isBadGateway());
        assertThat(productionWrites).hasValue(0);
    }

    private MockResponse activeSessionResponse() {
        return new MockResponse().setResponseCode(200).setBody(
                "{\"success\":true,\"data\":{\"status\":\"READY\"}}");
    }

    private static TokenBlacklistService allowlistedBlacklist() {
        TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
        when(blacklist.isBlacklisted(any())).thenReturn(false);
        return blacklist;
    }

    private static <T extends org.springframework.web.servlet.HandlerInterceptor> T allow(Class<T> type) {
        T interceptor = mock(type);
        try {
            when(interceptor.preHandle(any(), any(), any())).thenReturn(true);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        return interceptor;
    }

    @EnableWebMvc
    @Import({WebMvcConfig.class, DemoSandboxWriteGuardConfiguration.class, DemoSandboxController.class})
    static class Mvc {
        @Bean CookieAuthHelper cookieAuthHelper() { return new CookieAuthHelper(); }
    }

    @RestController
    static class ProductionWriteProbe {
        @PostMapping("/api/mobile/{factoryId}/orders")
        ResponseEntity<Void> write() {
            productionWriteCounter.incrementAndGet();
            return ResponseEntity.ok().build();
        }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BusinessException.class)
        ResponseEntity<Map<String, Object>> business(BusinessException failure) {
            return ResponseEntity.status(failure.getCode()).body(Map.of(
                    "success", false, "code", failure.getCode(), "message", failure.getMessage()));
        }
    }
}
