package com.cretas.aims.service.config;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySkillConfigRepository;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.platform.FactoryFeatureMenuWriteLock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import jakarta.persistence.EntityManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Command-service trigger writes share the direct-chain policy and bind run-as to the current actor. */
class CanvasTriggerRunAsConfigContractTest {
    private static final String FACTORY = "F001";

    @AfterEach
    void clearActor() {
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void enabledStepChangeUsesCurrentFactoryActorAndRebindsRunAs() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor read = readTool();
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(read));
        FactoryTriggerChain chain = chain(true, 99L,
                List.of(Map.of("tool", "material_expired_query", "enabled", true)));
        FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
        when(chains.findByFactoryIdAndChainCode(FACTORY, "CHAIN")).thenReturn(Optional.of(chain));
        when(chains.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        actor(AuthenticatedActorType.FACTORY_USER, 42L, FACTORY);

        CanvasConfigCommandService service = service(chains, registry);
        service.execute(FACTORY, "canvas_update_trigger_chain",
                Map.of("chainCode", "CHAIN", "stepOrder", 1, "stepEnabled", true),
                Map.of("userId", 42L));

        assertThat(chain.getRunAsUserId()).isEqualTo(42L);
        verify(chains).save(chain);
    }

    @Test
    void disabledChainCanBeClosedWithoutInheritedRunAsOrAutonomousValidation() {
        ToolRegistry registry = mock(ToolRegistry.class);
        FactoryTriggerChain chain = chain(true, 99L, List.of(Map.of("tool", "write_tool", "enabled", true)));
        FactoryTriggerChainRepository chains = mock(FactoryTriggerChainRepository.class);
        when(chains.findByFactoryIdAndChainCode(FACTORY, "CHAIN")).thenReturn(Optional.of(chain));
        when(chains.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CanvasConfigCommandService service = service(chains, registry);
        service.execute(FACTORY, "canvas_update_trigger_chain",
                Map.of("chainCode", "CHAIN", "enabled", false), Map.of("userId", 42L));

        assertThat(chain.getEnabled()).isFalse();
        assertThat(chain.getRunAsUserId()).isEqualTo(99L);
        verify(registry, never()).getExecutor(any());
    }

    @Test
    void policyRejectsWriteIdentityRetiredAndUnknownEnabledSteps() {
        ToolRegistry registry = mock(ToolRegistry.class);
        ToolExecutor read = readTool();
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(read));
        ToolExecutor write = mock(ToolExecutor.class);
        when(write.getAccessMode()).thenReturn(ToolExecutor.AccessMode.WRITE);
        when(registry.getExecutor("write_tool")).thenReturn(Optional.of(write));

        assertThatThrownBy(() -> CanvasTriggerChainWritePolicy.validateEnabledSteps(
                List.of(Map.of("tool", "write_tool", "enabled", true)), registry))
                .isInstanceOf(BusinessException.class).hasMessageContaining("只能使用只读工具");
        assertThatThrownBy(() -> CanvasTriggerChainWritePolicy.validateEnabledSteps(
                List.of(Map.of("tool", "material_expired_query", "params", Map.of("factoryId", FACTORY))), registry))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不能指定工厂");
        assertThatThrownBy(() -> CanvasTriggerChainWritePolicy.validateEnabledSteps(
                List.of(Map.of("tool", "canvas_legacy_retired", "enabled", true)), registry))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> CanvasTriggerChainWritePolicy.validateEnabledSteps(
                List.of(Map.of("tool", "missing_tool", "enabled", true)), registry))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不存在");
    }

    @Test
    void runAsRejectsMissingPlatformAndForeignActors() {
        assertThatThrownBy(() -> AutonomousToolPolicy.requireRunAsUser(FACTORY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重新登录");
        actor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, null);
        assertThatThrownBy(() -> AutonomousToolPolicy.requireRunAsUser(FACTORY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂账号");
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F002");
        assertThatThrownBy(() -> AutonomousToolPolicy.requireRunAsUser(FACTORY))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂账号");
    }

    private CanvasConfigCommandService service(FactoryTriggerChainRepository chains, ToolRegistry registry) {
        CanvasConfigPlanBindingService bindings = mock(CanvasConfigPlanBindingService.class);
        CanvasConfigStateReader states = mock(CanvasConfigStateReader.class);
        CanvasConfigStateReader.Snapshot snapshot = new CanvasConfigStateReader.Snapshot("config:1", "digest", Map.of());
        when(bindings.publicParameters(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(states.read(any(), any(), any())).thenReturn(snapshot);
        FactoryFeatureMenuWriteLock lock = mock(FactoryFeatureMenuWriteLock.class);
        EntityManager entityManager = mock(EntityManager.class);
        CanvasConfigCommandService service = new CanvasConfigCommandService(bindings, states, mock(FactoryConfigService.class),
                mock(FactoryToolConfigRepository.class), mock(FactorySkillConfigRepository.class), chains,
                lock, entityManager, immediateTransactions());
        org.springframework.test.util.ReflectionTestUtils.setField(service, "toolRegistry", registry);
        return service;
    }

    private PlatformTransactionManager immediateTransactions() {
        return new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) { return new SimpleTransactionStatus(); }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
    }

    private ToolExecutor readTool() {
        ToolExecutor read = mock(ToolExecutor.class);
        when(read.getAccessMode()).thenReturn(ToolExecutor.AccessMode.READ);
        return read;
    }

    private FactoryTriggerChain chain(boolean enabled, Long runAs, List<Map<String, Object>> steps) {
        FactoryTriggerChain chain = new FactoryTriggerChain();
        chain.setFactoryId(FACTORY);
        chain.setChainCode("CHAIN");
        chain.setEnabled(enabled);
        chain.setRunAsUserId(runAs);
        chain.setSteps(steps);
        return chain;
    }

    private static void actor(AuthenticatedActorType type, Long id, String factoryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(type, id, factoryId, "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
}
