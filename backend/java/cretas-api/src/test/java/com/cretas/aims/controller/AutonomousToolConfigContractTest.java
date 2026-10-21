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
import org.junit.jupiter.api.BeforeEach;
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
            new DynamicSchedulerService(schedulerRepo, registry, new ObjectMapper()), new SandboxedSpelEvaluator());
    private final TriggerChainController chains = new TriggerChainController(mock(FactoryToolConfigRepository.class),
            mock(FactorySkillConfigRepository.class), chainRepo);

    @BeforeEach void setup() throws Exception {
        ToolExecutor read = mock(ToolExecutor.class);
        when(read.getAccessMode()).thenReturn(AccessMode.READ);
        ToolExecutor write = mock(ToolExecutor.class);
        when(write.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(registry.getExecutor("stock_summary")).thenReturn(Optional.of(read));
        when(registry.getExecutor("inventory_reserve_stock")).thenReturn(Optional.of(write));
        when(schedulerRepo.findByFactoryIdAndTaskCode(any(), any())).thenReturn(Optional.empty());
        when(schedulerRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(chainRepo.findByFactoryIdAndChainCode(any(), any())).thenReturn(Optional.empty());
        when(chainRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        registry(chains, registry);
    }

    @Test void schedulerPutRefusesWriteToolAndForeignFactoryParamWithoutSaving() {
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("inventory_reserve_stock", Map.of())))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inventory_reserve_stock");
        assertThatThrownBy(() -> rules.setSchedulerConfig("F001", "nightly", schedule("stock_summary", Map.of("factoryId", "F-OTHER"))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("factoryId");
        verify(schedulerRepo, never()).save(any());
        assertThat(rules.setSchedulerConfig("F001", "nightly", schedule("stock_summary", Map.of("limit", 5))).getData()
                .getToolOrMethod()).isEqualTo("stock_summary");
    }

    @Test void enabledChainWithWriteStepIsRefusedButADisabledCopyCanStillBeSaved() {
        FactoryTriggerChain enabled = chain(true, List.of(Map.of("tool", "stock_summary"), Map.of("tool", "inventory_reserve_stock")));
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "SO_APPROVED", enabled))
                .isInstanceOf(BusinessException.class).hasMessageContaining("inventory_reserve_stock");
        verify(chainRepo, never()).save(any());

        FactoryTriggerChain disabled = chain(false, enabled.getSteps());
        assertThat(chains.setTriggerChain("F001", "SO_APPROVED", disabled).getData().getEnabled()).isFalse();
        FactoryTriggerChain readOnly = chain(true, List.of(Map.of("tool", "stock_summary")));
        assertThat(chains.setTriggerChain("F001", "SO_APPROVED", readOnly).getData().getEnabled()).isTrue();
    }

    @Test void chainValidationFailsClosedWithoutARegistry() throws Exception {
        registry(chains, null);
        assertThatThrownBy(() -> chains.setTriggerChain("F001", "SO_APPROVED", chain(true, List.of(Map.of("tool", "stock_summary")))))
                .isInstanceOf(BusinessException.class).hasMessageContaining("工具注册表不可用");
        verify(chainRepo, never()).save(any());
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
