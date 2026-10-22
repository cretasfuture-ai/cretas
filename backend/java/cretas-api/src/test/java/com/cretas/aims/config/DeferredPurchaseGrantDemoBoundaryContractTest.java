package com.cretas.aims.config;

import com.cretas.aims.controller.AiPurchaseTaskGrantController;
import com.cretas.aims.security.DataScopeRouteInterceptor;
import com.cretas.aims.service.intent.ControlledPurchaseTaskExecutionService;
import com.cretas.aims.service.intent.PurchaseGrantRefusalAuditService;
import com.cretas.aims.service.intent.PurchaseGrantCallerService;
import com.cretas.aims.controller.PurchaseGrantCallerController;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

/** Actual MVC registrations and grant controller; auth/service boundaries are isolated, demo guard is real. */
class DeferredPurchaseGrantDemoBoundaryContractTest {
    private AnnotationConfigWebApplicationContext context;
    private MockMvc mvc;
    private ControlledPurchaseTaskExecutionService service;
    private PurchaseGrantRefusalAuditService audit;
    private PurchaseGrantCallerService callers;

    @BeforeEach void setup() throws Exception {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("demo-contract",
                Map.of("cretas.demo.enabled", "true", "cretas.demo.factory-ids", "F_DEMO")));
        // Externally provided boundary mocks must not be autowired as production interceptor implementations.
        service = mock(ControlledPurchaseTaskExecutionService.class);
        audit = mock(PurchaseGrantRefusalAuditService.class);
        callers = mock(PurchaseGrantCallerService.class);
        var auth = mock(JwtAuthInterceptor.class);
        when(auth.preHandle(any(), any(), any())).thenAnswer(call -> {
            jakarta.servlet.http.HttpServletRequest request = call.getArgument(0);
            request.setAttribute("factoryId", request.getRequestURI().split("/")[3]);
            return true;
        });
        Map<String, Object> boundaries = Map.of("service", service, "audit", audit, "callers", callers, "auth", auth,
                "permission", allow(PermissionInterceptor.class), "role", allow(RequireRoleInterceptor.class),
                "module", allow(ModuleEnabledInterceptor.class), "dataScope", allow(DataScopeRouteInterceptor.class));
        context.addBeanFactoryPostProcessor(factory -> boundaries.forEach(factory::registerSingleton));
        context.register(Mvc.class);
        context.refresh();
        mvc = webAppContextSetup(context).build();
        service = context.getBean(ControlledPurchaseTaskExecutionService.class);
        audit = context.getBean(PurchaseGrantRefusalAuditService.class);
        when(service.executeLater(anyString(), anyString(), anyLong())).thenReturn(
                new ControlledPurchaseTaskExecutionService.Outcome("preview-175", "CONSUMED", "QA-ORDER", BigDecimal.ONE, BigDecimal.ONE, 2L, false));
    }
    @AfterEach void cleanup() { if (context != null) context.close(); }

    @Test void demoPreviewNumberCannotTurnExecuteIntoAReadOnlyPreview() throws Exception {
        mvc.perform(post("/api/mobile/F_DEMO/ai-purchase-task-grants/preview-175/execute")
                .contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
        verifyNoInteractions(service, audit);
    }
    @Test void demoPreviewNumberCannotTurnRevokeIntoAReadOnlyPreview() throws Exception {
        mvc.perform(post("/api/mobile/F_DEMO/ai-purchase-task-grants/preview-175/revoke")
                .contentType("application/json").content("{\"expectedVersion\":1,\"reason\":\"QA\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, audit);
    }
    @Test void demoGrantToolAndSkillAdaptersAreBlockedBeforeAnyControllerLookup() throws Exception {
        for (String route : java.util.List.of("execute-tool", "execute-skill")) {
            mvc.perform(post("/api/mobile/F_DEMO/ai-purchase-task-grants/preview-175/" + route)
                    .contentType("application/json").content("{\"expectedVersion\":1}"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.success").value(false));
        }
        verifyNoInteractions(service, audit, callers);
    }
    @Test void ordinaryFactoryStillReachesBothFixedCallerControllers() throws Exception {
        when(callers.executeGrant(anyString(), anyString(), anyLong(), any())).thenReturn(
                new ControlledPurchaseTaskExecutionService.Outcome("preview-175", "DRAFT", "QA-ORDER", BigDecimal.ONE, BigDecimal.ONE, 2L, false));
        for (String route : java.util.List.of("execute-tool", "execute-skill")) {
            mvc.perform(post("/api/mobile/F006/ai-purchase-task-grants/preview-175/" + route)
                    .contentType("application/json").content("{\"expectedVersion\":1}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.state").value("DRAFT"));
        }
        verify(callers).executeGrant("F006", "preview-175", 1L, PurchaseGrantCallerService.Channel.TOOL);
        verify(callers).executeGrant("F006", "preview-175", 1L, PurchaseGrantCallerService.Channel.SKILL);
    }
    @Test void demoAuthorizationIsStillBlockedBeforeControllerAndBodyValidation() throws Exception {
        mvc.perform(post("/api/mobile/F_DEMO/ai-purchase-task-grants/authorize-later")
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service, audit);
    }
    @Test void ordinaryFactoryStillReachesActualGrantController() throws Exception {
        mvc.perform(post("/api/mobile/F006/ai-purchase-task-grants/preview-175/execute")
                .contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        verify(service).executeLater("F006", "preview-175", 1L);
        verifyNoInteractions(audit);
    }
    @Test void disabledDemoLockKeepsItsExistingExplicitBehavior() throws Exception {
        ReflectionTestUtils.setField(context.getBean(DemoReadOnlyInterceptor.class), "demoEnabled", false);
        mvc.perform(post("/api/mobile/F_DEMO/ai-purchase-task-grants/preview-175/execute")
                .contentType("application/json").content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk());
        verify(service).executeLater("F_DEMO", "preview-175", 1L);
    }
    @Test void demoStatusReadStillReachesActualGrantController() throws Exception {
        mvc.perform(get("/api/mobile/F_DEMO/ai-purchase-task-grants/preview-175"))
                .andExpect(status().isOk());
        verify(service).status("F_DEMO", "preview-175");
        verifyNoInteractions(audit);
    }
    @Test void legitimatePreviewPostIsNotGloballyRemovedFromReadAllowList() throws Exception {
        mvc.perform(post("/api/mobile/F_DEMO/guard-read-probe/preview"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.readOnlyProbe").value(true));
        verifyNoInteractions(service, audit);
    }

    @Configuration @EnableWebMvc
    @Import({WebMvcConfig.class, AiPurchaseTaskGrantController.class, PurchaseGrantCallerController.class, ReadProbe.class})
    static class Mvc {
        @Bean DemoReadOnlyInterceptor demo() { return new DemoReadOnlyInterceptor(); }
    }
    private static <T extends HandlerInterceptor> T allow(Class<T> type) throws Exception {
        T guard = mock(type); when(guard.preHandle(any(), any(), any())).thenReturn(true); return guard;
    }
    @RestController static class ReadProbe {
        @PostMapping("/api/mobile/{factoryId}/guard-read-probe/preview") Map<String, Boolean> preview() {
            return Map.of("readOnlyProbe", true);
        }
    }
}
