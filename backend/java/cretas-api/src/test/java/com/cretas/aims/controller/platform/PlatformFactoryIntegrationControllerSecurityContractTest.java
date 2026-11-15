package com.cretas.aims.controller.platform;

import com.cretas.aims.audit.PlatformDataAccessLogInterceptor;
import com.cretas.aims.audit.PlatformDataAccessLogWriter;
import com.cretas.aims.config.DemoReadOnlyInterceptor;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.config.ModuleEnabledInterceptor;
import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.config.RequireRoleInterceptor;
import com.cretas.aims.config.WebMvcConfig;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.PlatformAdmin;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.PlatformRole;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.PlatformAdminRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.ActorRef;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.security.PlatformFactoryViewReadPolicy;
import com.cretas.aims.security.ProcessSecretGuardInterceptor;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.service.integration.ExternalIntegrationService;
import com.cretas.aims.service.mobile.WebLoginSessionService;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import com.cretas.aims.service.platform.PlatformFactoryControlAuthority;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 审查中2: 平台写外部软件授权 (改状态 PUT / 签发推送凭证 POST push-token) 走真实链路 ——
 * {@link WebMvcConfig} 真实登记给这条路由的拦截器 (含真实 {@link JwtAuthInterceptor} + 真 JWT) →
 * 真实 {@link PlatformFactoryIntegrationController} → 真实 {@link PlatformFactoryControlAuthority} →
 * 真实 {@link GlobalExceptionHandler}。只有外部软件服务是 mock: 被拒的请求一次都不能碰到它。
 *
 * <p>拦截器只要求 PLATFORM_ADMIN_ROLES 里任一平台角色, 所以「非超管平台账号」「查看模式会话」能不能写,
 * 只靠控制器里的 {@code authority.require(.., true)} 守 —— 删掉它, 本类的 403 断言变红 (PR 描述里有变异记录)。
 */
class PlatformFactoryIntegrationControllerSecurityContractTest {

    static final String F = "F-EXT-SEC";
    static final long SUPER = 21L;
    static final long OPERATOR = 22L;
    static final long FACTORY_USER = 31L;
    static final String BASE = "/api/platform/factories/" + F + "/integrations/blacklake";
    static final String BODY = "{\"status\":\"ACTIVE\",\"sourceOrgId\":\"1662664\",\"note\":\"x\"}";

    final ExternalIntegrationService service = mock(ExternalIntegrationService.class);
    JwtUtil jwt;
    JwtAuthInterceptor jwtInterceptor;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        FactoryRepository factories = mock(FactoryRepository.class);
        Factory factory = new Factory();
        factory.setId(F);
        factory.setIsActive(true);
        when(factories.findById(F)).thenReturn(Optional.of(factory));
        PlatformAdminRepository admins = mock(PlatformAdminRepository.class);
        when(admins.findById(SUPER)).thenReturn(Optional.of(admin(SUPER, PlatformRole.super_admin)));
        when(admins.findById(OPERATOR)).thenReturn(Optional.of(admin(OPERATOR, PlatformRole.operation_admin)));
        UserRepository users = mock(UserRepository.class);
        User user = new User();
        user.setId(FACTORY_USER);
        user.setFactoryId(F);
        user.setIsActive(true);
        when(users.findById(FACTORY_USER)).thenReturn(Optional.of(user));

        jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "secret", UUID.randomUUID().toString() + UUID.randomUUID());
        ReflectionTestUtils.setField(jwt, "expiration", 60_000L);
        jwtInterceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(jwtInterceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(jwtInterceptor, "tokenBlacklistService", mock(TokenBlacklistService.class));
        ReflectionTestUtils.setField(jwtInterceptor, "webLoginSessions", mock(WebLoginSessionService.class));
        ReflectionTestUtils.setField(jwtInterceptor, "factoryRepository", factories);
        ReflectionTestUtils.setField(jwtInterceptor, "userRepository", users);

        PlatformFactoryControlAuthority authority = new PlatformFactoryControlAuthority(factories, admins,
                mock(FactoryFeatureMenuWriteLock.class));
        ObjectMapper webMapper = Jackson2ObjectMapperBuilder.json().build();
        mvc = MockMvcBuilders.standaloneSetup(new PlatformFactoryIntegrationController(service, authority))
                .addInterceptors(registeredFor(webMapper, users).toArray(new HandlerInterceptor[0]))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(webMapper))
                .build();
    }

    // ------------------------------------------------------------------ 拒绝

    @Test
    void aPlatformAccountThatIsNotAnActiveSuperAdminCannotChangeStatusOrIssueATokenAndNeverReachesTheService()
            throws Exception {
        // platform_admin 过得了拦截器 (PLATFORM_ADMIN_ROLES), 库里是运营管理员 → 只能由 authority 拒
        String operator = jwt.generatePlatformToken(OPERATOR, "operator", "platform_admin");
        // 令牌里写着 super_admin, 库里早已降级 → 也只能由 authority (查库) 拒
        String staleSuper = jwt.generatePlatformToken(OPERATOR, "operator", "super_admin");
        for (String token : List.of(operator, staleSuper)) {
            mvc.perform(withToken(put(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY), token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
            mvc.perform(withToken(post(BASE + "/push-token"), token))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(service);
    }

    @Test
    void aFactoryViewSessionCannotChangeStatusOrIssueAToken() throws Exception {
        String superToken = jwt.generatePlatformToken(SUPER, "root", "super_admin");
        // 查看模式标记 (由拦截器在查看令牌上设置) 落在平台写入口上: 拦截器放行平台令牌, 只有 authority 拒
        mvc.perform(withToken(put(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY), superToken)
                        .requestAttr(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isForbidden());
        mvc.perform(withToken(post(BASE + "/push-token"), superToken)
                        .requestAttr(PlatformFactoryViewReadPolicy.REQUEST_ATTRIBUTE, Boolean.TRUE))
                .andExpect(status().isForbidden());
        // 真正的工厂查看令牌: 拦截器本身就不让它进平台 API
        String viewToken = jwt.generatePlatformFactoryViewToken(SUPER, "root", "super_admin", F);
        mvc.perform(withToken(put(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY), viewToken))
                .andExpect(status().isForbidden());
        mvc.perform(withToken(post(BASE + "/push-token"), viewToken))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void aFactoryUserOrAnAnonymousCallerIsRefused() throws Exception {
        String factoryToken = jwt.generateToken(FACTORY_USER, F, "boss", "factory_super_admin");
        mvc.perform(withToken(post(BASE + "/push-token"), factoryToken)).andExpect(status().isForbidden());
        mvc.perform(withToken(put(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY), factoryToken))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/push-token")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    // ------------------------------------------------------------------ 阳性对照

    @Test
    void anActiveSuperAdminReachesTheServiceWithItsOwnActor() throws Exception {
        ActorRef actor = new ActorRef(AuthenticatedActorType.PLATFORM_ADMIN, SUPER);
        when(service.issuePushToken(F, "blacklake", actor))
                .thenReturn(new ExternalIntegrationService.IssuedToken("blacklake", "cxs_test-only", "2026-11-16 10:00"));
        when(service.upsert(eq(F), eq("blacklake"), any(), eq(actor))).thenReturn(List.of());
        String superToken = jwt.generatePlatformToken(SUPER, "root", "super_admin");

        mvc.perform(withToken(post(BASE + "/push-token"), superToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.system").value("blacklake"));
        mvc.perform(withToken(put(BASE).contentType(MediaType.APPLICATION_JSON).content(BODY), superToken))
                .andExpect(status().isOk());
        verify(service).issuePushToken(F, "blacklake", actor);
        verify(service).upsert(eq(F), eq("blacklake"), any(), eq(actor));
    }

    // ------------------------------------------------------------------ helpers

    private static MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", "Bearer " + token);
    }

    static PlatformAdmin admin(long id, PlatformRole role) {
        PlatformAdmin admin = PlatformAdmin.builder().platformRole(role).build();
        admin.setId(id);
        return admin;
    }

    /** 真实的 {@link WebMvcConfig#addInterceptors}: 只取它给这条路由挂上的拦截器, 顺序按它排的。 */
    List<HandlerInterceptor> registeredFor(ObjectMapper webMapper, UserRepository users) {
        class Registry extends InterceptorRegistry {
            List<Object> all() {
                return getInterceptors();
            }
        }
        Registry registry = new Registry();
        PermissionService permissions = mock(PermissionService.class);
        new WebMvcConfig(jwtInterceptor, new DemoReadOnlyInterceptor(),
                new PermissionInterceptor(permissions, users, webMapper),
                new RequireRoleInterceptor(webMapper), mock(ModuleEnabledInterceptor.class),
                mock(DataScopeRouteInterceptor.class), mock(ProcessSecretGuardInterceptor.class),
                new PlatformDataAccessLogInterceptor(mock(PlatformDataAccessLogWriter.class))).addInterceptors(registry);
        MockHttpServletRequest route = new MockHttpServletRequest("POST", BASE + "/push-token");
        ServletRequestPathUtils.parseAndCache(route);
        List<HandlerInterceptor> chain = new ArrayList<>();
        for (Object registered : registry.all()) {
            if (registered instanceof MappedInterceptor mapped) {
                if (mapped.matches(route)) chain.add(mapped.getInterceptor());
            } else {
                chain.add((HandlerInterceptor) registered);
            }
        }
        assertThat(chain).as("JWT 拦截器必须挂在这条路由上").contains(jwtInterceptor);
        return chain;
    }
}
