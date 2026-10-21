package com.cretas.aims.service.intent;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.ai.tool.impl.purchase.PurchaseGrantExecuteTool;
import com.cretas.aims.controller.PurchaseGrantCallerController;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.cretas.aims.service.skill.SkillExecutor;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real adapters, registered Tool and deterministic Skill. DB lifecycle is tested separately. */
class PurchaseGrantCallerContractTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    private final ControlledPurchaseTaskExecutionService controlled = mock(ControlledPurchaseTaskExecutionService.class);
    private final PurchaseGrantRefusalAuditService audit = mock(PurchaseGrantRefusalAuditService.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolPermissionChain permissions = mock(ToolPermissionChain.class);
    private final WriteGuardService guard = new WriteGuardService();
    private SkillExecutorImpl skill;
    private PurchaseGrantCallerService service;
    private PurchaseGrantExecuteTool tool;
    private static final String GRANT = "preview-301";

    @BeforeEach void setup() {
        skill = new SkillExecutorImpl(registry, mock(DashScopeClient.class), mapper);
        ReflectionTestUtils.setField(skill, "writeGuardService", guard);
        ReflectionTestUtils.setField(skill, "toolPermissionChain", permissions);
        service = new PurchaseGrantCallerService(controlled, audit, provider(registry), provider(skill), permissions, guard, mapper);
        tool = spy(new PurchaseGrantExecuteTool(provider(service)));
        ReflectionTestUtils.setField(tool, "objectMapper", mapper);
        when(registry.getExecutor(PurchaseGrantExecuteTool.NAME)).thenReturn(Optional.of(tool));
        when(registry.hasExecutor(PurchaseGrantExecuteTool.NAME)).thenReturn(true);
        when(registry.isToolEnabledForFactory("F006", PurchaseGrantExecuteTool.NAME)).thenReturn(true);
        when(permissions.denyReason(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(controlled.status("F006", GRANT)).thenReturn(outcome("PENDING", false));
        when(controlled.executeLater("F006", GRANT, 1L)).thenReturn(outcome("DRAFT", false));
        when(audit.record(any(), any(), any())).thenReturn(Optional.of("grant-refusal:test"));
        actor(AuthenticatedActorType.FACTORY_USER, 1698L, "F006");
    }
    @AfterEach void cleanup() { RequestContextHolder.resetRequestAttributes(); skill.shutdown(); }
    private static <T> ObjectProvider<T> provider(T bean) {
        var factory = new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        factory.registerSingleton("provided", bean);
        @SuppressWarnings("unchecked") ObjectProvider<T> result = (ObjectProvider<T>) factory.getBeanProvider(bean.getClass());
        return result;
    }
    private static ControlledPurchaseTaskExecutionService.Outcome outcome(String state, boolean replay) {
        return new ControlledPurchaseTaskExecutionService.Outcome(GRANT, state, "DRAFT".equals(state) ? "PO-ONLY-DRAFT" : null,
                BigDecimal.ONE, BigDecimal.ONE, "DRAFT".equals(state) ? 2L : 1L, replay);
    }
    private static void actor(AuthenticatedActorType type, long id, String factory) {
        var request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factory, "procurement_manager"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @Test void actualSpringWiringResolvesControllerToolAndSkillWithoutAnotherInjectionConstructor() {
        try (var context = new AnnotationConfigApplicationContext()) {
            var beans = context.getBeanFactory();
            beans.registerSingleton("controlled", controlled);
            beans.registerSingleton("audit", audit);
            beans.registerSingleton("registry", registry);
            beans.registerSingleton("permissions", permissions);
            beans.registerSingleton("guard", guard);
            beans.registerSingleton("mapper", mapper);
            beans.registerSingleton("llm", mock(DashScopeClient.class));
            context.register(PurchaseGrantCallerController.class, PurchaseGrantCallerService.class,
                    PurchaseGrantExecuteTool.class, SkillExecutorImpl.class);
            context.refresh();
            assertThat(context.getBean(PurchaseGrantCallerController.class)).isNotNull();
            assertThat(context.getBean(SkillExecutor.class)).isInstanceOf(SkillExecutorImpl.class);
            assertThat(context.getBean(PurchaseGrantExecuteTool.class).getAccessMode()).isEqualTo(com.cretas.aims.ai.tool.ToolExecutor.AccessMode.WRITE);
        }
    }
    @Test void realToolReachesCanonicalControlledExecutionWithOnlyTaskAndCas() throws Exception {
        assertThat(service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.TOOL).purchaseOrderId()).isEqualTo("PO-ONLY-DRAFT");
        var call = org.mockito.ArgumentCaptor.forClass(ToolCall.class);
        verify(tool).execute(call.capture(), anyMap());
        var args = mapper.readTree(call.getValue().getFunction().getArguments());
        assertThat(args.path("grantId").asText()).isEqualTo(GRANT);
        assertThat(args.path("expectedVersion").asLong()).isEqualTo(1L);
        assertThat(args.size()).isEqualTo(13); // two task/CAS selectors + eleven bound identity aliases
        for (String key : java.util.List.of("factoryId", "factory_id", "tenantId", "tenant_id")) assertThat(args.path(key).asText()).isEqualTo("F006");
        for (String key : java.util.List.of("userId", "user_id", "actorUserId", "actor_user_id")) assertThat(args.path(key).asLong()).isEqualTo(1698L);
        for (String key : java.util.List.of("userRole", "user_role", "role")) assertThat(args.path(key).asText()).isEqualTo("procurement_manager");
        assertThat(args.has("serverConfirmed")).isFalse();
        assertThat(call.getValue().getFunction().getArguments()).doesNotContain("confirmation.authority");
        verify(controlled).status("F006", GRANT);
        verify(controlled).executeLater("F006", GRANT, 1L);
        verifyNoInteractions(audit);
    }
    @Test void realSkillReachesRealToolAndMarkerNeverEntersArgumentsOrBusinessContext() throws Exception {
        var result = new java.util.concurrent.atomic.AtomicReference<ControlledPurchaseTaskExecutionService.Outcome>();
        assertThatCode(() -> result.set(service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.SKILL))).doesNotThrowAnyException();
        assertThat(result.get().state()).isEqualTo("DRAFT");
        var call = org.mockito.ArgumentCaptor.forClass(ToolCall.class);
        @SuppressWarnings("unchecked") var context = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(tool).execute(call.capture(), context.capture());
        assertThat(call.getValue().getFunction().getArguments()).doesNotContain("confirmation.authority");
        assertThat(guard.isConfirmed(context.getValue())).isFalse();
        assertThat(context.getValue()).containsEntry("factoryId", "F006").containsEntry("userId", 1698L);
        verify(controlled).executeLater("F006", GRANT, 1L);
        verifyNoInteractions(audit);
    }
    @Test void missingRequestAndAsyncOnlyIdentityCannotBecomeScheduledPurchasingAuthority() {
        RequestContextHolder.resetRequestAttributes();
        try (var scope = AuthenticatedActor.trustedAsyncScope(new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 1698L, "F006", "procurement_manager"))) {
            assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.SKILL)).hasMessageContaining("可信采购登录主体");
        }
        assertThat(AuthenticatedActor.fromCurrentExecution()).isEmpty();
        verifyNoInteractions(controlled, tool);
    }
    @Test void platformNumericCollisionAndForeignTenantAreRefusedBeforeTaskLookup() {
        actor(AuthenticatedActorType.PLATFORM_ADMIN, 1698L, "F006");
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.TOOL)).hasMessageContaining("可信采购登录主体");
        actor(AuthenticatedActorType.FACTORY_USER, 1698L, "F007");
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.SKILL)).hasMessageContaining("可信采购登录主体");
        verifyNoInteractions(controlled, tool);
    }
    @Test void ownerAndFactoryAreReadFromRealRequestRatherThanPayloadAliases() throws Exception {
        var call = ToolCall.builder().function(ToolCall.FunctionCall.builder().name(PurchaseGrantExecuteTool.NAME)
                .arguments("{\"grantId\":\"preview-301\",\"expectedVersion\":1,\"userId\":1309,\"factoryId\":\"F007\",\"role\":\"super_admin\",\"serverConfirmed\":true}").build()).build();
        assertThat(mapper.readTree(tool.execute(call, Map.of("factoryId", "F007", "userId", 1309L))).path("success").asBoolean()).isFalse();
        verifyNoInteractions(controlled);
        assertThat(mapper.readTree(tool.execute(call, Map.of("factoryId", "F006", "userId", 1309L))).path("success").asBoolean()).isTrue();
        verify(controlled).executeLater("F006", GRANT, 1L);
    }
    @Test void disabledToolAndPermissionDenialCannotReachExecution() throws Exception {
        when(registry.isToolEnabledForFactory("F006", PurchaseGrantExecuteTool.NAME)).thenReturn(false);
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.TOOL)).hasMessageContaining("停用");
        when(registry.isToolEnabledForFactory("F006", PurchaseGrantExecuteTool.NAME)).thenReturn(true);
        when(permissions.denyReason(any(), any(), any(), any(), any())).thenReturn(Optional.of("当前采购权限已撤销"));
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.SKILL)).hasMessageContaining("权限已撤销");
        verify(controlled, never()).executeLater(any(), any(), any());
        verify(tool, never()).execute(any(), any());
        verify(audit, times(2)).record(eq("F006"), eq(GRANT), any());
    }
    @Test void skillWrapperSuccessCannotHideInnerRefusalAndAuditIsOnce() {
        when(controlled.executeLater("F006", GRANT, 1L)).thenThrow(new BusinessException(403, "确认授权已过期"));
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.SKILL))
                .isInstanceOf(BusinessException.class).hasMessageContaining("已过期").hasMessageContaining("grant-refusal:test");
        verify(controlled, times(1)).executeLater("F006", GRANT, 1L);
        verify(audit, times(1)).record(eq("F006"), eq(GRANT), any());
    }
    @Test void unknownCommitOutcomeIsNotAuditedAsConfirmedRefusalOrAutomaticallyRetried() {
        when(controlled.executeLater("F006", GRANT, 1L)).thenThrow(new IllegalStateException("connection lost after commit"));
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.TOOL))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        verify(controlled, times(1)).executeLater("F006", GRANT, 1L);
        verifyNoInteractions(audit);
    }
    @Test void auditUnavailableCannotBeReportedAsConfirmedAuditSuccess() {
        when(controlled.executeLater("F006", GRANT, 1L)).thenThrow(new BusinessException(403, "授权已撤销"));
        when(audit.record(any(), any(), any())).thenThrow(new IllegalStateException("audit unavailable"));
        assertThatThrownBy(() -> service.execute("F006", GRANT, 1L, PurchaseGrantCallerService.Channel.TOOL))
                .isInstanceOfSatisfying(BusinessException.class, e -> assertThat(e.getCode()).isEqualTo(503));
        verify(controlled, times(1)).executeLater("F006", GRANT, 1L);
    }
    @Test void invalidTaskAndVersionCannotReachTaskLookup() {
        assertThatThrownBy(() -> service.execute("F006", "arbitrary-task", 1L, PurchaseGrantCallerService.Channel.TOOL)).hasMessageContaining("参考或版本");
        assertThatThrownBy(() -> service.execute("F006", GRANT, 0L, PurchaseGrantCallerService.Channel.SKILL)).hasMessageContaining("参考或版本");
        verifyNoInteractions(controlled, tool);
    }
    @Test void actualControllerStrictBodyRejectsCallerAuthorityAndCommandBeforeAnyTaskLookup() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new PurchaseGrantCallerController(service))
                .setMessageConverters(new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter(mapper)).build();
        for (String route : java.util.List.of("execute-tool", "execute-skill")) {
            for (String extra : java.util.List.of("\"serverConfirmed\":true", "\"userId\":1309", "\"role\":\"super_admin\"",
                    "\"factoryId\":\"F007\"", "\"quantity\":999", "\"toolName\":\"purchase_order_create\"")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mobile/F006/ai-purchase-task-grants/" + GRANT + "/" + route)
                        .contentType("application/json").content("{\"expectedVersion\":1," + extra + "}"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            }
        }
        verifyNoInteractions(controlled, tool, audit);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PurchaseGrantCallerService.Channel.class)
    void realPermissionChainPreservesGrantedFrontlineCanonicalPurchaseAndCurrentDenials(PurchaseGrantCallerService.Channel channel) {
        var grants = mock(com.cretas.aims.repository.config.AiFrontlineIntentGrantRepository.class);
        var row = new com.cretas.aims.entity.config.AiFrontlineIntentGrant();
        row.setFactoryId("F006"); row.setRoleCode("operator"); row.setIntentCode("PURCHASE_ORDER_CREATE");
        when(grants.findByFactoryIdAndRevokedAtIsNull("F006")).thenReturn(java.util.List.of(row));
        var frontline = new FrontlineAiWriteGrantService(grants, guard, mock(IntentConfigManagementService.class), registry);
        var users = mock(com.cretas.aims.repository.UserRepository.class);
        var user = new com.cretas.aims.entity.User(); user.setId(1698L); user.setFactoryId("F006"); user.setRoleCode("operator"); user.setIsActive(true);
        when(users.findById(1698L)).thenReturn(Optional.of(user));
        var matrix = mock(com.cretas.aims.service.PermissionService.class);
        when(matrix.hasAnyPermission(eq(user), any(String[].class))).thenReturn(true);
        var rbacGuard = new com.cretas.aims.ai.tool.ToolRbacGuard();
        ReflectionTestUtils.setField(rbacGuard, "userRepository", users);
        ReflectionTestUtils.setField(rbacGuard, "permissionService", matrix);
        var rbac = new com.cretas.aims.ai.tool.ToolRbacEnforcer();
        ReflectionTestUtils.setField(rbac, "rbacGuard", rbacGuard);
        var chain = new ToolPermissionChain(rbac, frontline);
        var actual = new PurchaseGrantCallerService(controlled, audit, provider(registry), provider(skill), chain, guard, mapper);
        var actualTool = new PurchaseGrantExecuteTool(provider(actual));
        ReflectionTestUtils.setField(actualTool, "objectMapper", mapper);
        when(registry.getExecutor(PurchaseGrantExecuteTool.NAME)).thenReturn(Optional.of(actualTool));
        ReflectionTestUtils.setField(skill, "toolPermissionChain", chain);
        var request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER,1698L,"F006","operator"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        assertThatCode(() -> assertThat(actual.execute("F006", GRANT, 1L, channel).state()).isEqualTo("DRAFT")).doesNotThrowAnyException();
        verify(controlled, times(1)).executeLater("F006", GRANT, 1L);
        verify(matrix, atLeastOnce()).hasAnyPermission(eq(user), eq("procurement:read_write"));
        when(matrix.hasAnyPermission(eq(user), any(String[].class))).thenReturn(false);
        assertThatThrownBy(() -> actual.execute("F006", GRANT, 1L, channel)).hasMessageContaining("没有权限");
        when(matrix.hasAnyPermission(eq(user), any(String[].class))).thenReturn(true);
        when(grants.findByFactoryIdAndRevokedAtIsNull("F006")).thenReturn(java.util.List.of());
        when(grants.findByFactoryIdAndRevokedAtIsNull("F007")).thenReturn(java.util.List.of(row));
        frontline.evict("F006");
        assertThatThrownBy(() -> actual.execute("F006", GRANT, 1L, channel)).hasMessageContaining("还没有对");
        verify(controlled, times(1)).executeLater("F006", GRANT, 1L);
        verify(audit, times(2)).record(eq("F006"), eq(GRANT), any());
    }
}
