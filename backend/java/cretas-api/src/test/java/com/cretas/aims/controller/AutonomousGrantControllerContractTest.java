package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.engine.AutonomousGrantIssuer;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The HTTP-facing half of grant management: who the actor is (this factory's typed user), what a
 * request may ask for (a SCHEDULER config by id, a registered write tool admitted for SCHEDULER),
 * and that every database decision (account, role, config, run-as, one active grant) is left to
 * {@link AutonomousGrantIssuer} with the tool's current descriptor version.
 */
class AutonomousGrantControllerContractTest {
    private final AutonomousGrantService grants = mock(AutonomousGrantService.class);
    private final AutonomousGrantIssuer issuer = mock(AutonomousGrantIssuer.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final AutonomousGrantController controller = new AutonomousGrantController(grants, issuer, registry);
    private final LocalDateTime expires = LocalDateTime.now().plusDays(7);

    @BeforeEach void setup() {
        ToolExecutor write = mock(ToolExecutor.class);
        when(write.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(write.getVersion()).thenReturn("2.0.0");
        ToolExecutor read = mock(ToolExecutor.class);
        when(read.getAccessMode()).thenReturn(AccessMode.READ);
        when(registry.getExecutor("user_disable")).thenReturn(Optional.of(write));
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(read));
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F001");
    }

    @AfterEach void clearActor() { RequestContextHolder.resetRequestAttributes(); }

    @Test void onlyThisFactorysLoggedInUserReachesTheIssuer() {
        actor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, null);
        assertRefused(request("SCHEDULER", "78", "user_disable"), "本厂账号");
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F002");
        assertRefused(request("SCHEDULER", "78", "user_disable"), "本厂账号");
        assertThatThrownBy(() -> controller.list("F001")).hasMessageContaining("本厂账号");
        assertThatThrownBy(() -> controller.revoke("F001", "g1")).hasMessageContaining("本厂账号");
        RequestContextHolder.resetRequestAttributes();
        assertRefused(request("SCHEDULER", "78", "user_disable"), "重新登录");
        verifyNoInteractions(issuer, grants);
    }

    @Test void malformedOrUnsupportedRequestsNeverReachTheIssuer() {
        assertRefused(request("SCHEDULER", "abc", "user_disable"), "配置 id");
        assertRefused(request("AI_CHAT", "78", "user_disable"), "sourceType 只能是 SCHEDULER");
        assertRefused(request("TRIGGER", "5:1", "user_disable"), "sourceType 只能是 SCHEDULER");
        assertRefused(new AutonomousGrantController.GrantRequest("SCHEDULER", "78", "user_disable", null, expires),
                "都必须填写");
        when(registry.getExecutor("no_such_tool")).thenReturn(Optional.empty());
        assertRefused(request("SCHEDULER", "78", "no_such_tool"), "工具未注册");
        assertRefused(request("SCHEDULER", "78", "material_expired_query"), "只读工具不需要授权");
        // The shipped runtime-tool-policies.yaml lists no write tool for SCHEDULER.
        assertRefused(request("SCHEDULER", "78", "user_disable"), "还没有开放给定时任务");
        verifyNoInteractions(issuer, grants);
    }

    @Test void anAdmittedRequestIsDecidedByTheIssuerWithTheNormalizedConfigIdAndCurrentToolVersion() {
        AutonomousToolGrant issued = AutonomousToolGrant.issue("g1", "F001", "SCHEDULER", "78", "user_disable", 42L,
                "d", "r", "2.0.0", 3, expires, 42L, LocalDateTime.now());
        when(issuer.issue(any(), anyLong(), anyLong(), any(), any(), anyInt(), any(), any())).thenReturn(issued);
        // Simulates a reviewed policy that admits SCHEDULER for this tool; everything else is real.
        try (MockedStatic<AutonomousToolPolicy> policy = mockStatic(AutonomousToolPolicy.class, CALLS_REAL_METHODS)) {
            policy.when(() -> AutonomousToolPolicy.requireSourceAllowed("user_disable", ToolExecutionSource.SCHEDULER))
                    .then(call -> null);
            var view = controller.issue("F001", request("SCHEDULER", "078", "user_disable")).getData();
            assertThat(view.state()).isEqualTo("ACTIVE");
        }
        verify(issuer).issue(eq("F001"), eq(42L), eq(78L), eq("user_disable"), eq("2.0.0"), eq(3), eq(expires), any());
        verifyNoInteractions(grants);
    }

    @Test void refusalsFromTheIssuerReachTheCallerUnchanged() {
        when(issuer.issue(any(), anyLong(), anyLong(), any(), any(), anyInt(), any(), any()))
                .thenThrow(new BusinessException(409, "该任务已有生效中的授权，不能重复签发"));
        try (MockedStatic<AutonomousToolPolicy> policy = mockStatic(AutonomousToolPolicy.class, CALLS_REAL_METHODS)) {
            policy.when(() -> AutonomousToolPolicy.requireSourceAllowed("user_disable", ToolExecutionSource.SCHEDULER))
                    .then(call -> null);
            assertThatThrownBy(() -> controller.issue("F001", request("SCHEDULER", "78", "user_disable")))
                    .isInstanceOf(BusinessException.class).hasMessageContaining("不能重复签发")
                    .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(409);
        }
    }

    @Test void listAndRevokeCheckTheCurrentAdminThroughTheIssuer() {
        when(grants.list("F001")).thenReturn(List.of());
        assertThat(controller.list("F001").getData()).isEmpty();
        verify(issuer).currentAdmin("F001", 42L);

        AutonomousToolGrant revoked = AutonomousToolGrant.issue("g1", "F001", "SCHEDULER", "78", "user_disable", 43L,
                "d", "r", "2.0.0", 3, expires, 43L, LocalDateTime.now());
        revoked.revoke(42L, LocalDateTime.now());
        when(issuer.revoke(eq("F001"), eq(42L), eq("g1"), any())).thenReturn(revoked);
        assertThat(controller.revoke("F001", "g1").getData().state()).isEqualTo("REVOKED");
        when(issuer.currentAdmin("F001", 42L)).thenThrow(new BusinessException(403, "当前账号已不是本厂超级管理员"));
        assertThatThrownBy(() -> controller.list("F001")).hasMessageContaining("超级管理员");
    }

    private void assertRefused(AutonomousGrantController.GrantRequest body, String message) {
        assertThatThrownBy(() -> controller.issue("F001", body))
                .isInstanceOf(BusinessException.class).hasMessageContaining(message);
    }

    private AutonomousGrantController.GrantRequest request(String sourceType, String sourceRef, String tool) {
        return new AutonomousGrantController.GrantRequest(sourceType, sourceRef, tool, 3, expires);
    }

    private static void actor(AuthenticatedActorType type, Long id, String factoryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factoryId, "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
