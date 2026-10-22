package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Who may issue or revoke a delegated autonomous grant, for what, and bound to what: only this
 * factory's typed actor whose account, factory, role and permission still hold in the database,
 * only the config's run-as user can issue, only for the tool the config runs, only for a write tool
 * whose runtime policy admits SCHEDULER, and always bound to the stored parameters, the config
 * revision and the tool's current descriptor version.
 */
class AutonomousGrantControllerContractTest {
    private final AutonomousGrantService grants = mock(AutonomousGrantService.class);
    private final FactorySchedulerConfigRepository schedules = mock(FactorySchedulerConfigRepository.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserRepository users = mock(UserRepository.class);
    private final PermissionService permissions = mock(PermissionService.class);
    private final AutonomousGrantController controller =
            new AutonomousGrantController(grants, schedules, registry, objectMapper, users, permissions);
    private final LocalDateTime expires = LocalDateTime.now().plusDays(7);
    private User admin;

    @BeforeEach void setup() {
        ToolExecutor write = mock(ToolExecutor.class);
        when(write.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(write.getVersion()).thenReturn("2.0.0");
        ToolExecutor read = mock(ToolExecutor.class);
        when(read.getAccessMode()).thenReturn(AccessMode.READ);
        when(registry.getExecutor("user_disable")).thenReturn(Optional.of(write));
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(read));
        when(schedules.findById(78L)).thenReturn(Optional.of(schedule(78L, "F001", "user_disable", 42L)));
        when(schedules.findById(79L)).thenReturn(Optional.of(schedule(79L, "F002", "user_disable", 42L)));
        when(schedules.findById(80L)).thenReturn(Optional.of(schedule(80L, "F001", "material_expired_query", 42L)));
        when(schedules.findById(81L)).thenReturn(Optional.of(schedule(81L, "F001", "user_disable", 43L)));
        admin = user(42L, "F001", "factory_super_admin", true);
        when(users.findById(42L)).thenReturn(Optional.of(admin));
        when(permissions.hasAllPermissions(any(User.class), eq("system:read_write"))).thenReturn(true);
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F001");
    }

    @AfterEach void clearActor() { RequestContextHolder.resetRequestAttributes(); }

    @Test void onlyThisFactorysLoggedInUserCanIssue() {
        actor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, null);
        assertRefused(request("SCHEDULER", "78", "user_disable"), "本厂账号");
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F002");
        assertRefused(request("SCHEDULER", "78", "user_disable"), "本厂账号");
        RequestContextHolder.resetRequestAttributes();
        assertRefused(request("SCHEDULER", "78", "user_disable"), "重新登录");
        verifyNoInteractions(grants);
    }

    @Test void theDatabaseNotTheTokenDecidesWhetherTheActorMayStillGrant() {
        // The token still says factory_super_admin of F001; the database has moved on.
        admin.setIsActive(false);
        assertRefused(request("SCHEDULER", "78", "user_disable"), "已停用");
        admin.setIsActive(true);
        admin.setFactoryId("F002");
        assertRefused(request("SCHEDULER", "78", "user_disable"), "不属于本厂");
        admin.setFactoryId("F001");
        admin.setRoleCode("warehouse_manager");
        assertRefused(request("SCHEDULER", "78", "user_disable"), "角色或权限");
        admin.setRoleCode("factory_super_admin");
        when(permissions.hasAllPermissions(any(User.class), eq("system:read_write"))).thenReturn(false);
        assertRefused(request("SCHEDULER", "78", "user_disable"), "角色或权限");
        when(users.findById(42L)).thenReturn(Optional.empty());
        assertRefused(request("SCHEDULER", "78", "user_disable"), "已停用");
        assertThatThrownBy(() -> controller.revoke("F001", "g1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> controller.list("F001")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(grants);
    }

    @Test void sourceMustExistInThisFactoryAndRunThisTool() {
        assertRefused(request("SCHEDULER", "79", "user_disable"), "不属于本厂");
        assertRefused(request("SCHEDULER", "404", "user_disable"), "不属于本厂");
        assertRefused(request("SCHEDULER", "abc", "user_disable"), "配置 id");
        assertRefused(request("SCHEDULER", "78", "product_create"), "不一致");
        assertRefused(request("AI_CHAT", "78", "user_disable"), "sourceType 只能是 SCHEDULER");
        assertRefused(request("TRIGGER", "5:1", "user_disable"), "sourceType 只能是 SCHEDULER");
        assertRefused(new AutonomousGrantController.GrantRequest("SCHEDULER", "78", "user_disable", null, expires),
                "都必须填写");
        verifyNoInteractions(grants);
    }

    @Test void grantorMustBeTheConfigsRunAsUser() {
        assertThatThrownBy(() -> controller.issue("F001", request("SCHEDULER", "81", "user_disable")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("执行人是你")
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(409);
        verifyNoInteractions(grants);
    }

    @Test void readOnlyToolsNeedNoGrantAndShippedPoliciesAdmitNoAutonomousWrite() {
        assertRefused(request("SCHEDULER", "80", "material_expired_query"), "只读工具不需要授权");
        // The shipped runtime-tool-policies.yaml lists no write tool for SCHEDULER.
        assertRefused(request("SCHEDULER", "78", "user_disable"), "还没有开放给定时任务");
        verifyNoInteractions(grants);
    }

    @Test void admittedWriteIsBoundToTheStoredConfigRevisionAndCurrentToolVersion() {
        AutonomousToolGrant issued = AutonomousToolGrant.issue("g1", "F001", "SCHEDULER", "78", "user_disable", 42L,
                "d", "r", "2.0.0", 3, expires, 42L, LocalDateTime.now());
        when(grants.issue(any(), anyInt(), any(), any(), any())).thenReturn(issued);
        // Simulates a reviewed policy that admits SCHEDULER for this tool; everything else is real.
        try (MockedStatic<AutonomousToolPolicy> policy = mockStatic(AutonomousToolPolicy.class, CALLS_REAL_METHODS)) {
            policy.when(() -> AutonomousToolPolicy.requireSourceAllowed("user_disable", ToolExecutionSource.SCHEDULER))
                    .then(call -> null);
            // "078" names config 78; the grant is bound to "78", the ref the scheduler passes at run time.
            var view = controller.issue("F001", request("SCHEDULER", "078", "user_disable")).getData();
            assertThat(view.state()).isEqualTo("ACTIVE");
        }
        String storedDigest = ToolCommandDigest.parametersHash(objectMapper.valueToTree(Map.of("username", "e2e-target")));
        ArgumentCaptor<AutonomousGrantService.Binding> binding = ArgumentCaptor.forClass(AutonomousGrantService.Binding.class);
        verify(grants).issue(binding.capture(), eq(3), eq(expires), eq(42L), any());
        assertThat(binding.getValue()).isEqualTo(new AutonomousGrantService.Binding("F001", "SCHEDULER", "78",
                "user_disable", 42L, storedDigest,
                AutonomousGrantService.configRevision("t-user_disable", "0 0 7 * * ?", "user_disable", storedDigest, 42L),
                "2.0.0"));
    }

    @Test void revokeIsScopedToTheFactoryAndOpenToAnyCurrentAdmin() {
        when(grants.revoke(eq("F001"), eq("g-other"), eq(42L), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.revoke("F001", "g-other"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于本厂");
        // Another current super admin of the factory (not the grantor) can stop a grant.
        when(users.findById(44L)).thenReturn(Optional.of(user(44L, "F001", "factory_super_admin", true)));
        AutonomousToolGrant revoked = AutonomousToolGrant.issue("g1", "F001", "SCHEDULER", "78", "user_disable", 42L,
                "d", "r", "2.0.0", 3, expires, 42L, LocalDateTime.now());
        revoked.revoke(44L, LocalDateTime.now());
        when(grants.revoke(eq("F001"), eq("g1"), eq(44L), any())).thenReturn(Optional.of(revoked));
        actor(AuthenticatedActorType.FACTORY_USER, 44L, "F001");
        assertThat(controller.revoke("F001", "g1").getData().state()).isEqualTo("REVOKED");
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F002");
        assertThatThrownBy(() -> controller.revoke("F001", "g1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂账号");
        verify(grants, never()).revoke(eq("F001"), eq("g1"), eq(42L), any());
    }

    private void assertRefused(AutonomousGrantController.GrantRequest body, String message) {
        assertThatThrownBy(() -> controller.issue("F001", body))
                .isInstanceOf(BusinessException.class).hasMessageContaining(message);
    }

    private AutonomousGrantController.GrantRequest request(String sourceType, String sourceRef, String tool) {
        return new AutonomousGrantController.GrantRequest(sourceType, sourceRef, tool, 3, expires);
    }

    private static FactorySchedulerConfig schedule(Long id, String factoryId, String tool, Long runAs) {
        return FactorySchedulerConfig.builder().id(id).factoryId(factoryId).taskCode("t-" + tool).enabled(true)
                .cronExpression("0 0 7 * * ?").toolOrMethod(tool).params(Map.of("username", "e2e-target"))
                .runAsUserId(runAs).build();
    }

    private static User user(Long id, String factoryId, String role, boolean active) {
        User user = new User();
        user.setId(id);
        user.setFactoryId(factoryId);
        user.setRoleCode(role);
        user.setIsActive(active);
        return user;
    }

    private static void actor(AuthenticatedActorType type, Long id, String factoryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factoryId, "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
