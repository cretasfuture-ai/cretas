package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolCommandDigest;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.engine.AutonomousGrantService;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.entity.ai.AutonomousToolGrant;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Who may issue a delegated autonomous grant, for what, and with which parameters: only this
 * factory's user who is the source's run-as user, only for the tool that source runs, only for a
 * write tool whose runtime policy admits the source, and always for the stored parameters.
 */
class AutonomousGrantControllerContractTest {
    private final AutonomousGrantService grants = mock(AutonomousGrantService.class);
    private final FactorySchedulerConfigRepository schedules = mock(FactorySchedulerConfigRepository.class);
    private final FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AutonomousGrantController controller =
            new AutonomousGrantController(grants, schedules, chains, registry, objectMapper);
    private final LocalDateTime expires = LocalDateTime.now().plusDays(7);

    @BeforeEach void setup() {
        ToolExecutor write = mock(ToolExecutor.class);
        when(write.getAccessMode()).thenReturn(AccessMode.WRITE);
        ToolExecutor read = mock(ToolExecutor.class);
        when(read.getAccessMode()).thenReturn(AccessMode.READ);
        when(registry.getExecutor("user_disable")).thenReturn(Optional.of(write));
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(read));
        when(schedules.findById(78L)).thenReturn(Optional.of(schedule("F001", "user_disable", 42L)));
        when(schedules.findById(79L)).thenReturn(Optional.of(schedule("F002", "user_disable", 42L)));
        when(schedules.findById(80L)).thenReturn(Optional.of(schedule("F001", "material_expired_query", 42L)));
        when(schedules.findById(81L)).thenReturn(Optional.of(schedule("F001", "user_disable", 43L)));
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

    @Test void sourceMustExistInThisFactoryAndRunThisTool() {
        assertRefused(request("SCHEDULER", "79", "user_disable"), "不属于本厂");
        assertRefused(request("SCHEDULER", "404", "user_disable"), "不属于本厂");
        assertRefused(request("SCHEDULER", "abc", "user_disable"), "配置 id");
        assertRefused(request("SCHEDULER", "78", "product_create"), "不一致");
        assertRefused(request("AI_CHAT", "78", "user_disable"), "SCHEDULER 或 TRIGGER");
        assertRefused(new AutonomousGrantController.GrantRequest("SCHEDULER", "78", "user_disable", null, expires),
                "都必须填写");
        verifyNoInteractions(grants);
    }

    @Test void grantorMustBeTheSourcesRunAsUser() {
        assertThatThrownBy(() -> controller.issue("F001", request("SCHEDULER", "81", "user_disable")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("执行人是你")
                .extracting(e -> ((BusinessException) e).getCode()).isEqualTo(409);
        verifyNoInteractions(grants);
    }

    @Test void readOnlyToolsNeedNoGrantAndShippedPoliciesAdmitNoAutonomousWrite() {
        assertRefused(request("SCHEDULER", "80", "material_expired_query"), "只读工具不需要授权");
        // The shipped runtime-tool-policies.yaml lists no write tool for SCHEDULER / TRIGGER.
        assertRefused(request("SCHEDULER", "78", "user_disable"), "还没有开放给定时任务");
        verifyNoInteractions(grants);
    }

    @Test void chainStepRefMustNameAnExistingStepOfThisFactorysChain() {
        FactoryTriggerChain chain = new FactoryTriggerChain();
        chain.setFactoryId("F001");
        chain.setRunAsUserId(42L);
        chain.setSteps(List.of(Map.of("order", 1, "tool", "user_disable", "params", Map.of("username", "a"))));
        when(chains.findById(5L)).thenReturn(Optional.of(chain));
        assertRefused(request("TRIGGER", "5", "user_disable"), "链id:步骤序号");
        assertRefused(request("TRIGGER", "5:x", "user_disable"), "链id:步骤序号");
        assertRefused(request("TRIGGER", "6:1", "user_disable"), "不属于本厂");
        assertRefused(request("TRIGGER", "5:2", "user_disable"), "没有第 2 步");
        assertRefused(request("TRIGGER", "5:1", "user_disable"), "还没有开放给事件链");
        verifyNoInteractions(grants);
    }

    @Test void admittedWriteIsIssuedForTheStoredParametersNotTheRequest() {
        AutonomousToolGrant issued = AutonomousToolGrant.issue("g1", "F001", "SCHEDULER", "78", "user_disable", 42L,
                "d", 3, expires, 42L, LocalDateTime.now());
        when(grants.issue(any(), any(), any(), any(), any(), any(), anyInt(), any(), any(), any())).thenReturn(issued);
        // Simulates a reviewed policy that admits SCHEDULER for this tool; everything else is real.
        try (MockedStatic<AutonomousToolPolicy> policy = mockStatic(AutonomousToolPolicy.class, CALLS_REAL_METHODS)) {
            policy.when(() -> AutonomousToolPolicy.requireSourceAllowed("user_disable", ToolExecutionSource.SCHEDULER))
                    .then(call -> null);
            var view = controller.issue("F001", request("SCHEDULER", "78", "user_disable")).getData();
            assertThat(view.state()).isEqualTo("ACTIVE");
        }
        String storedDigest = ToolCommandDigest.parametersHash(objectMapper.valueToTree(Map.of("username", "e2e-target")));
        verify(grants).issue(eq("F001"), eq("SCHEDULER"), eq("78"), eq("user_disable"), eq(42L), eq(storedDigest),
                eq(3), eq(expires), eq(42L), any());
    }

    @Test void revokeIsScopedToTheFactory() {
        when(grants.revoke(eq("F001"), eq("g-other"), eq(42L), any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> controller.revoke("F001", "g-other"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不属于本厂");
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F002");
        assertThatThrownBy(() -> controller.revoke("F001", "g1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂账号");
        verify(grants, never()).revoke(eq("F001"), eq("g1"), any(), any());
    }

    private void assertRefused(AutonomousGrantController.GrantRequest body, String message) {
        assertThatThrownBy(() -> controller.issue("F001", body))
                .isInstanceOf(BusinessException.class).hasMessageContaining(message);
    }

    private AutonomousGrantController.GrantRequest request(String sourceType, String sourceRef, String tool) {
        return new AutonomousGrantController.GrantRequest(sourceType, sourceRef, tool, 3, expires);
    }

    private static FactorySchedulerConfig schedule(String factoryId, String tool, Long runAs) {
        return FactorySchedulerConfig.builder().factoryId(factoryId).taskCode("t-" + tool).enabled(true)
                .cronExpression("0 0 7 * * ?").toolOrMethod(tool).params(Map.of("username", "e2e-target"))
                .runAsUserId(runAs).build();
    }

    private static void actor(AuthenticatedActorType type, Long id, String factoryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factoryId, "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
