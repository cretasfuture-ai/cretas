package com.cretas.aims.controller;

import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import com.cretas.aims.config.JwtAuthInterceptor;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.User;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.service.TokenBlacklistService;
import com.cretas.aims.utils.JwtUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AiPurchaseTaskGrantControllerContractTest {
    private final ControlledPurchaseTaskExecutionService service = mock(ControlledPurchaseTaskExecutionService.class);
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;

    @BeforeEach
    void setUp() { mvc = MockMvcBuilders.standaloneSetup(new AiPurchaseTaskGrantController(service)).build(); }

    @Test
    void statusReadsTheScopedOutcomeThroughService() throws Exception {
        var outcome = new ControlledPurchaseTaskExecutionService.Outcome("preview-9", "DRAFT", "PO-9", null, null, 2L, false);
        when(service.status("F001", "preview-9")).thenReturn(outcome);

        mvc.perform(get("/api/mobile/F001/ai-purchase-task-grants/preview-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.grantId").value("preview-9"))
                .andExpect(jsonPath("$.data.state").value("DRAFT"));
        verify(service).status("F001", "preview-9");
    }

    @Test
    void revokeBindsNestedRequestAndExpectedVersion() throws Exception {
        var outcome = new ControlledPurchaseTaskExecutionService.Outcome("preview-9", "REVOKED", null, null, null, 3L, false);
        when(service.revoke("F001", "preview-9", 2L, "价格已变")).thenReturn(outcome);

        mvc.perform(post("/api/mobile/F001/ai-purchase-task-grants/preview-9/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(Map.of("expectedVersion", 2, "reason", "价格已变"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REVOKED"));
        verify(service).revoke("F001", "preview-9", 2L, "价格已变");
        verifyNoMoreInteractions(service);
    }

    @Test
    void blankRevokeReasonIsRejectedBeforeServiceAndNoIssuanceRouteExists() throws Exception {
        mvc.perform(post("/api/mobile/F001/ai-purchase-task-grants/preview-9/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/mobile/F001/ai-purchase-task-grants"))
                .andExpect(status().is4xxClientError());
        verifyNoInteractions(service);
    }

    @Test
    void statusCanExposeUnknownWithoutRetryingOrIssuing() throws Exception {
        when(service.status("F001", "preview-10"))
                .thenReturn(new ControlledPurchaseTaskExecutionService.Outcome("preview-10", "UNKNOWN", null, null, null, null, false));

        String body = mvc.perform(get("/api/mobile/F001/ai-purchase-task-grants/preview-10"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("UNKNOWN"))
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("issue", "execute");
        verify(service).status("F001", "preview-10");
    }

    @Test
    void missingJwtIsRejectedByTheActualInterceptorForStatusAndRevoke() throws Exception {
        MockMvc secured = securedMvc("F001", "valid-token", false);

        secured.perform(get("/api/mobile/F001/ai-purchase-task-grants/preview-9"))
                .andExpect(status().isUnauthorized());
        secured.perform(post("/api/mobile/F001/ai-purchase-task-grants/preview-9/revoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"reason\":\"cancel\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void validatedTokenFromAnotherTenantIsForbiddenBeforeControllerService() throws Exception {
        MockMvc secured = securedMvc("OTHER", "other-tenant-token", true);

        secured.perform(get("/api/mobile/F001/ai-purchase-task-grants/preview-9").header("Authorization", "Bearer other-tenant-token"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void sameTenantJwtPassesAndInterceptorCreatesAuthenticatedActor() throws Exception {
        AtomicReference<AuthenticatedActor> actor = new AtomicReference<>();
        when(service.status("F001", "preview-9")).thenAnswer(invocation -> {
            ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            actor.set((AuthenticatedActor) attrs.getRequest().getAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE));
            return new ControlledPurchaseTaskExecutionService.Outcome("preview-9", "PENDING", null, null, null, null, false);
        });
        MockMvc secured = securedMvc("F001", "same-tenant-token", true);

        secured.perform(get("/api/mobile/F001/ai-purchase-task-grants/preview-9").header("Authorization", "Bearer same-tenant-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PENDING"));
        assertThat(actor.get()).isNotNull();
        assertThat(actor.get().id()).isEqualTo(42L);
        assertThat(actor.get().factoryId()).isEqualTo("F001");
        verify(service).status("F001", "preview-9");
    }

    private MockMvc securedMvc(String tokenFactory, String token, boolean valid) {
        JwtUtil jwt = mock(JwtUtil.class);
        TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
        FactoryRepository factories = mock(FactoryRepository.class);
        UserRepository users = mock(UserRepository.class);
        when(jwt.validateToken(token)).thenReturn(valid);
        when(jwt.isExplicitPlatformToken(token)).thenReturn(false);
        when(jwt.getUserIdFromToken(token)).thenReturn(42L);
        when(jwt.getUsernameFromToken(token)).thenReturn("operator");
        when(jwt.getFactoryIdFromToken(token)).thenReturn(tokenFactory);
        when(jwt.getRoleFromToken(token)).thenReturn("operator");
        when(blacklist.isBlacklisted(token)).thenReturn(false);
        User user = new User();
        user.setFactoryId(tokenFactory);
        user.setIsActive(true);
        Factory factory = new Factory();
        factory.setIsActive(true);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(factories.findById(tokenFactory)).thenReturn(Optional.of(factory));
        JwtAuthInterceptor interceptor = new JwtAuthInterceptor();
        ReflectionTestUtils.setField(interceptor, "jwtUtil", jwt);
        ReflectionTestUtils.setField(interceptor, "tokenBlacklistService", blacklist);
        ReflectionTestUtils.setField(interceptor, "factoryRepository", factories);
        ReflectionTestUtils.setField(interceptor, "userRepository", users);
        return MockMvcBuilders.standaloneSetup(new AiPurchaseTaskGrantController(service))
                .addInterceptors(interceptor).build();
    }
}
