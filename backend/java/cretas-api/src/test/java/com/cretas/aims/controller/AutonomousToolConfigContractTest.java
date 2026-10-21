package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.engine.DynamicSchedulerService;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** The two config endpoints refuse what the autonomous runners would refuse, before anything is saved. */
class AutonomousToolConfigContractTest {
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final FactorySchedulerConfigRepository schedulerRepo = mock(FactorySchedulerConfigRepository.class);
    private final FactoryTriggerChainRepository chainRepo = mock(FactoryTriggerChainRepository.class);
    private final BusinessRuleController rules = new BusinessRuleController(mock(FactoryValidationRuleRepository.class),
            mock(FactoryDefaultValueRepository.class), mock(FactoryFormulaRepository.class), schedulerRepo,
            new DynamicSchedulerService(schedulerRepo, registry, mock(com.cretas.aims.engine.AutonomousToolRunner.class), new ObjectMapper()), new SandboxedSpelEvaluator());
    private final TriggerChainController chains = new TriggerChainController(mock(FactoryToolConfigRepository.class),
            mock(FactorySkillConfigRepository.class), chainRepo);

    @BeforeEach void setup() throws Exception {
        ToolExecutor read = mock(ToolExecutor.class);
        when(read.getAccessMode()).thenReturn(AccessMode.READ);
        ToolExecutor write = mock(ToolExecutor.class);
        when(write.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(registry.getExecutor("material_expired_query")).thenReturn(Optional.of(read));
        when(registry.getExecutor("inventory_reserve_stock")).thenReturn(Optional.of(write));
        when(schedulerRepo.findByFactoryIdAndTaskCode(any(), any())).thenReturn(Optional.empty());
        when(schedulerRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(chainRepo.findByFactoryIdAndChainCode(any(), any())).thenReturn(Optional.empty());
        when(chainRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        registry(chains, registry);
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F001");
    }

    @AfterEach void clearActor() { RequestContextHolder.resetRequestAttributes(); }

    @Test void enablingRecordsTheFactoryUserAsRunAsAndDisablingKeepsIt() {
        FactorySchedulerConfig saved = rules.setSchedulerConfig("F001", "nightly", schedule("material_expired_query", Map.of())).getData();
        assertThat(saved.getRunAsUserId()).isEqualTo(42L);
        when(schedulerRepo.findByFactoryIdAndTaskCode("F001", "nightly")).thenReturn(Optional.of(saved));
        actor(AuthenticatedActorType.FACTORY_USER, 43L, "F001");
        FactorySchedulerConfig disable = new FactorySchedulerConfig();
        disable.setEnabled(false);
        assertThat(rules.setSchedulerConfig("F001", "nightly", disable).getData().getRunAsUserId()).isEqualTo(42L);
        FactoryTriggerChain chain = chains.setTriggerChain("F001", "C", chain(true, List.of(Map.of("tool", "material_expired_query")))).getData();
        assertThat(chain.getRunAsUserId()).isEqualTo(43L);
    }

    @Test void platformAdminOrAnotherFactorysUserCannotBecomeTheRunAsUser() {
        actor(AuthenticatedActorType.PLATFORM_ADMIN, 42L, null);
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("material_expired_query", Map.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂账号");
        actor(AuthenticatedActorType.FACTORY_USER, 42L, "F002");
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "C", chain(true, List.of(Map.of("tool", "material_expired_query")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("本厂账号");
        RequestContextHolder.resetRequestAttributes();
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("material_expired_query", Map.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("重新登录");
        verify(schedulerRepo, never()).save(any());
        verify(chainRepo, never()).save(any());
    }

    @Test void schedulerPutRefusesWriteToolAndForeignFactoryParamWithoutSaving() {
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("inventory_reserve_stock", Map.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inventory_reserve_stock");
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("material_expired_query", Map.of("factoryId", "F-OTHER"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("factoryId");
        verify(schedulerRepo, never()).save(any());
        assertThat(rules.setSchedulerConfig("F001", "nightly", schedule("material_expired_query", Map.of("limit", 5))).getData()
                .getToolOrMethod()).isEqualTo("material_expired_query");
    }

    @Test void enabledChainWithWriteStepIsRefusedButADisabledCopyCanStillBeSaved() {
        FactoryTriggerChain enabled = chain(true, List.of(Map.of("tool", "material_expired_query"), Map.of("tool", "inventory_reserve_stock")));
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "SO_APPROVED", enabled))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inventory_reserve_stock");
        verify(chainRepo, never()).save(any());

        FactoryTriggerChain disabled = chain(false, enabled.getSteps());
        assertThat(chains.setTriggerChain("F001", "SO_APPROVED", disabled).getData().getEnabled()).isFalse();
        FactoryTriggerChain readOnly = chain(true, List.of(Map.of("tool", "material_expired_query")));
        assertThat(chains.setTriggerChain("F001", "SO_APPROVED", readOnly).getData().getEnabled()).isTrue();
    }

    @Test void anExistingWriteToolScheduleCanStillBeDisabled() {
        FactorySchedulerConfig existing = schedule("inventory_reserve_stock", Map.of());
        existing.setFactoryId("F001");
        existing.setTaskCode("nightly");
        when(schedulerRepo.findByFactoryIdAndTaskCode("F001", "nightly")).thenReturn(Optional.of(existing));
        FactorySchedulerConfig disable = new FactorySchedulerConfig();
        disable.setEnabled(false);
        assertThat(rules.setSchedulerConfig("F001", "nightly", disable).getData().getEnabled()).isFalse();
        verify(schedulerRepo).save(existing);
        FactorySchedulerConfig enable = new FactorySchedulerConfig();
        enable.setEnabled(true);
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", enable))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inventory_reserve_stock");
    }

    @Test void enabledChainRefusesIdentityParamsAndMalformedParamsButIgnoresDisabledWriteSteps() {
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "C", chain(true,
                List.of(Map.of("tool", "material_expired_query", "params", Map.of("factoryId", "F-OTHER"))))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("factoryId");
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "C", chain(true,
                List.of(Map.of("tool", "material_expired_query", "params", "limit=5")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("参数必须是对象");
        verify(chainRepo, never()).save(any());
        assertThat(chains.setTriggerChain("F001", "C", chain(true, List.of(Map.of("tool", "material_expired_query"),
                Map.of("tool", "inventory_reserve_stock", "enabled", false)))).getData().getEnabled()).isTrue();
    }

    @Test void toolsWhosePolicyDoesNotAdmitTheSourceAreRefusedAtConfigTime() {
        ToolExecutor otherRead = mock(ToolExecutor.class);
        when(otherRead.getAccessMode()).thenReturn(AccessMode.READ);
        when(registry.getExecutor("material_stock_summary")).thenReturn(Optional.of(otherRead));
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("material_stock_summary", Map.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("还没有开放给定时任务");
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "C", chain(true, List.of(Map.of("tool", "material_stock_summary")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("还没有开放给事件链");
        verify(schedulerRepo, never()).save(any());
        verify(chainRepo, never()).save(any());
    }

    @Test void chainValidationFailsClosedWithoutARegistry() throws Exception {
        registry(chains, null);
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "SO_APPROVED", chain(true, List.of(Map.of("tool", "material_expired_query")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("工具注册表不可用");
        verify(chainRepo, never()).save(any());
    }

    private static void actor(AuthenticatedActorType type, Long id, String factoryId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE, new AuthenticatedActor(type, id, factoryId, "factory_super_admin"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static FactorySchedulerConfig schedule(String tool, Map<String, Object> params) {
        FactorySchedulerConfig body = new FactorySchedulerConfig();
        body.setToolOrMethod(tool);
        body.setParams(params);
        body.setEnabled(true);
        return body;
    }

    private static FactoryTriggerChain chain(boolean enabled, List<Map<String, Object>> steps) {
        FactoryTriggerChain body = new FactoryTriggerChain();
        body.setEnabled(enabled);
        body.setSteps(steps);
        body.setEventType("SO_FINANCE_APPROVED");
        return body;
    }

    private static void registry(TriggerChainController controller, ToolRegistry registry) throws Exception {
        Field field = TriggerChainController.class.getDeclaredField("toolRegistry");
        field.setAccessible(true);
        field.set(controller, registry);
    }
}
