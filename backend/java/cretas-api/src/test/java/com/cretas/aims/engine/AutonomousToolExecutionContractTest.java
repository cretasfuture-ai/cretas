package com.cretas.aims.engine;

import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.FactoryTriggerChain;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.FactoryTriggerChainRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEvent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Cron schedules and event trigger chains run with only a factory id behind them: no user, no
 * permission check, no confirmation. Until delegated grants exist they may run read-only tools
 * only, and may not name a factory/user/role in their parameters.
 */
class AutonomousToolExecutionContractTest {
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolExecutor readTool = tool(AccessMode.READ);
    private final ToolExecutor writeTool = tool(AccessMode.WRITE);
    private final ToolExecutor undeclaredTool = mock(ToolExecutor.class);
    private final FactorySchedulerConfigRepository schedulerRepo = mock(FactorySchedulerConfigRepository.class);
    private final DynamicSchedulerService scheduler = new DynamicSchedulerService(schedulerRepo, registry, new ObjectMapper());

    @BeforeEach void tools() throws Exception {
        when(registry.getExecutor("stock_summary")).thenReturn(Optional.of(readTool));
        when(registry.getExecutor("dictionary_add")).thenReturn(Optional.of(writeTool));
        when(registry.getExecutor("legacy_tool")).thenReturn(Optional.of(undeclaredTool));
        when(registry.isToolEnabledForFactory(any(), any())).thenReturn(true);
        when(readTool.execute(any(), any())).thenReturn("{\"success\":true}");
        when(writeTool.execute(any(), any())).thenReturn("{\"success\":true}");
        when(undeclaredTool.getToolName()).thenReturn("legacy_tool");
        when(undeclaredTool.execute(any(), any())).thenReturn("{\"success\":true}");
    }

    @Test void policyAllowsOnlyDeclaredReadToolsWithoutIdentityParameters() {
        assertThat(AutonomousToolPolicy.rejection("stock_summary", readTool, Map.of("limit", 5))).isNull();
        assertThat(AutonomousToolPolicy.rejection("dictionary_add", writeTool, Map.of())).contains("dictionary_add");
        assertThat(AutonomousToolPolicy.rejection("legacy_tool", undeclaredTool, Map.of())).contains("legacy_tool");
        assertThat(AutonomousToolPolicy.rejection("stock_summary", readTool, Map.of("factoryId", "F-OTHER")))
                .contains("factoryId");
        assertThat(AutonomousToolPolicy.rejection("stock_summary", readTool, Map.of("userId", 1))).contains("userId");
        assertThat(AutonomousToolPolicy.refusal(AutonomousToolPolicy.rejection("dictionary_add", writeTool, Map.of())))
                .hasMessageContaining("只能使用只读工具").hasMessageContaining("dictionary_add");
    }

    @Test void accessModeComesFromTheFailClosedResolverIncludingTheMigrationSeed() {
        ToolExecutor seededRead = mock(ToolExecutor.class);
        when(seededRead.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(seededRead.getToolName()).thenReturn("restaurant_avg_ticket");
        assertThat(AutonomousToolPolicy.rejection("restaurant_avg_ticket", seededRead, Map.of())).isNull();
        ToolExecutor defaultWrite = mock(ToolExecutor.class);
        when(defaultWrite.getAccessMode()).thenReturn(AccessMode.WRITE);
        when(defaultWrite.getToolName()).thenReturn("not_in_seed");
        assertThat(AutonomousToolPolicy.rejection("not_in_seed", defaultWrite, Map.of())).contains("not_in_seed");
    }

    @Test void writeToolIsNotEvenScheduled() throws Exception {
        org.springframework.scheduling.TaskScheduler taskScheduler = mock(org.springframework.scheduling.TaskScheduler.class);
        Field field = DynamicSchedulerService.class.getDeclaredField("taskScheduler");
        field.setAccessible(true);
        field.set(scheduler, taskScheduler);
        FactorySchedulerConfig config = schedule(75L, "dictionary_add", Map.of());
        when(schedulerRepo.findById(75L)).thenReturn(Optional.of(config));
        scheduler.scheduleTask(config);
        verifyNoInteractions(taskScheduler);
        assertThat(scheduler.getActiveTaskCount()).isZero();
        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
    }

    @Test void malformedStepParamsAreACountedFailureAndLaterStepsStillRun() throws Exception {
        TriggerChainExecutor executor = chainExecutor();
        TriggerChainExecutor self = mock(TriggerChainExecutor.class);
        setSelf(executor, self);
        FactoryTriggerChain chain = chain("CONTINUE", List.of(
                Map.of("order", 1, "tool", "stock_summary", "params", "limit=5"), step(2, "stock_summary", Map.of())));
        runChain(executor, chain);
        verify(readTool, times(1)).execute(any(), any());
        verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("PARTIAL"), contains("must be an object"));
    }

    @Test void scheduledWriteToolIsNeverExecutedAndIsRecordedAsWriteDenied() throws Exception {
        FactorySchedulerConfig config = schedule(71L, "dictionary_add", Map.of());
        when(schedulerRepo.findById(71L)).thenReturn(Optional.of(config));
        runScheduled(config);
        verify(writeTool, never()).execute(any(), any());
        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
        assertThat(config.getLastExecutionError()).contains("dictionary_add");
    }

    @Test void scheduledUndeclaredToolCountsAsWrite() throws Exception {
        FactorySchedulerConfig config = schedule(72L, "legacy_tool", Map.of());
        when(schedulerRepo.findById(72L)).thenReturn(Optional.of(config));
        runScheduled(config);
        verify(undeclaredTool, never()).execute(any(), any());
        assertThat(config.getLastExecutionStatus()).isEqualTo(AutonomousToolPolicy.WRITE_DENIED);
    }

    @Test void scheduledReadToolRunsInTheConfigsOwnFactoryAndForeignFactoryParamIsRefused() throws Exception {
        FactorySchedulerConfig ok = schedule(73L, "stock_summary", Map.of("limit", 5));
        when(schedulerRepo.findById(73L)).thenReturn(Optional.of(ok));
        runScheduled(ok);
        verify(readTool).execute(any(ToolCall.class), eq(Map.of("factoryId", "F001")));
        assertThat(ok.getLastExecutionStatus()).isEqualTo("SUCCESS");

        FactorySchedulerConfig foreign = schedule(74L, "stock_summary", Map.of("factoryId", "F-OTHER"));
        when(schedulerRepo.findById(74L)).thenReturn(Optional.of(foreign));
        runScheduled(foreign);
        verify(readTool, times(1)).execute(any(), any());
        assertThat(foreign.getLastExecutionStatus()).isEqualTo("CONFIG_INVALID");
        assertThat(foreign.getLastExecutionError()).contains("factoryId");
    }

    @Test void configTimeGateRejectsWriteToolsAndIdentityParamsButLeavesUnknownToolsToTheRunner() {
        assertThatThrownBy(() -> scheduler.requireSchedulable("dictionary_add", Map.of()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("dictionary_add");
        assertThatThrownBy(() -> scheduler.requireSchedulable("stock_summary", Map.of("tenantId", "X")))
                .isInstanceOf(BusinessException.class).hasMessageContaining("tenantId");
        scheduler.requireSchedulable("stock_summary", Map.of("limit", 1));
        when(registry.getExecutor("missing_tool")).thenReturn(Optional.empty());
        scheduler.requireSchedulable("missing_tool", Map.of());
    }

    @Test void triggerChainWriteStepIsRefusedAndCountedWhileReadStepRuns() throws Exception {
        TriggerChainExecutor executor = chainExecutor();
        TriggerChainExecutor self = mock(TriggerChainExecutor.class);
        setSelf(executor, self);
        FactoryTriggerChain chain = chain("CONTINUE", List.of(
                step(1, "stock_summary", Map.of()), step(2, "dictionary_add", Map.of()),
                step(3, "stock_summary", Map.of("factoryId", "F-OTHER"))));

        runChain(executor, chain);

        verify(readTool, times(1)).execute(any(), any());
        verify(writeTool, never()).execute(any(), any());
        ArgumentCaptor<String> status = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> error = ArgumentCaptor.forClass(String.class);
        verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), status.capture(), error.capture());
        assertThat(status.getValue()).isEqualTo("PARTIAL");
        assertThat(error.getValue()).contains("dictionary_add").contains("factoryId");
    }

    @Test void stopStrategyHaltsAtTheRefusedStep() throws Exception {
        TriggerChainExecutor executor = chainExecutor();
        TriggerChainExecutor self = mock(TriggerChainExecutor.class);
        setSelf(executor, self);
        FactoryTriggerChain chain = chain("STOP", List.of(step(1, "dictionary_add", Map.of()), step(2, "stock_summary", Map.of())));
        runChain(executor, chain);
        verify(readTool, never()).execute(any(), any());
        verify(self).persistExecutionMetadata(eq(chain), any(LocalDateTime.class), eq("FAILED"), contains("dictionary_add"));
    }

    private void runScheduled(FactorySchedulerConfig config) throws Exception {
        Method run = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class);
        run.setAccessible(true);
        run.invoke(scheduler, config);
    }

    private TriggerChainExecutor chainExecutor() {
        return new TriggerChainExecutor(mock(FactoryTriggerChainRepository.class), registry, new ObjectMapper());
    }

    private static void setSelf(TriggerChainExecutor executor, TriggerChainExecutor self) throws Exception {
        Field field = TriggerChainExecutor.class.getDeclaredField("self");
        field.setAccessible(true);
        field.set(executor, self);
    }

    private static void runChain(TriggerChainExecutor executor, FactoryTriggerChain chain) throws Exception {
        Method run = TriggerChainExecutor.class.getDeclaredMethod("executeChain",
                FactoryTriggerChain.class, String.class, ApplicationEvent.class);
        run.setAccessible(true);
        run.invoke(executor, chain, "F001", new ApplicationEvent("test") { });
    }

    private static FactorySchedulerConfig schedule(Long id, String tool, Map<String, Object> params) {
        return FactorySchedulerConfig.builder().id(id).factoryId("F001").taskCode("t" + id)
                .cronExpression("0 0 9 1 * ?").enabled(true).toolOrMethod(tool).params(params).build();
    }

    private static FactoryTriggerChain chain(String strategy, List<Map<String, Object>> steps) {
        FactoryTriggerChain chain = new FactoryTriggerChain();
        chain.setFactoryId("F001");
        chain.setChainCode("CHAIN");
        chain.setEventType("TEST");
        chain.setErrorStrategy(strategy);
        chain.setEnabled(true);
        chain.setSteps(steps);
        return chain;
    }

    private static Map<String, Object> step(int order, String tool, Map<String, Object> params) {
        return Map.of("order", order, "tool", tool, "params", params);
    }

    private static ToolExecutor tool(AccessMode mode) {
        ToolExecutor tool = mock(ToolExecutor.class);
        when(tool.getAccessMode()).thenReturn(mode);
        return tool;
    }
}
