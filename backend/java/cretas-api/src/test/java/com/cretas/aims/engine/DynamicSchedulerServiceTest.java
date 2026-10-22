package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.support.SimpleTriggerContext;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(MockitoExtension.class)
@DisplayName("DynamicSchedulerService 配置隔离与执行可见性")
class DynamicSchedulerServiceTest {

    @Mock
    private FactorySchedulerConfigRepository schedulerRepo;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolExecutor toolExecutor;
    @Mock
    private AutonomousToolRunner runner;
    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private ScheduledFuture<?> scheduledFuture;

    private DynamicSchedulerService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new DynamicSchedulerService(schedulerRepo, toolRegistry, runner, new ObjectMapper());
        Field schedulerField = DynamicSchedulerService.class.getDeclaredField("taskScheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(service, taskScheduler);
    }

    @Test
    @DisplayName("loadSchedules 对相同 factory/task 的重复配置逐条记录 CONFIG_INVALID 且不注册")
    void loadSchedules_duplicateScope_failsClosedPerId() {
        FactorySchedulerConfig first = config(11L, "F001", "daily", "report_tool");
        FactorySchedulerConfig second = config(12L, "F001", "daily", "report_tool");
        when(schedulerRepo.findByEnabledTrue()).thenReturn(List.of(first, second));
        when(schedulerRepo.findById(11L)).thenReturn(Optional.of(first));
        when(schedulerRepo.findById(12L)).thenReturn(Optional.of(second));

        service.loadSchedules();

        assertEquals("CONFIG_INVALID", first.getLastExecutionStatus());
        assertEquals("CONFIG_INVALID", second.getLastExecutionStatus());
        assertTrue(first.getLastExecutionError().contains("multiple enabled"));
        assertTrue(second.getLastExecutionError().contains("multiple enabled"));
        assertEquals(0, service.getActiveTaskCount());
        verify(schedulerRepo, times(2)).save(any(FactorySchedulerConfig.class));
        verify(toolRegistry, never()).getExecutor(any());
    }

    @Test
    @DisplayName("loadSchedules 缺少工具记录 TOOL_NOT_FOUND 且不注册")
    void loadSchedules_missingTool_failsClosed() {
        FactorySchedulerConfig config = config(21L, "F001", "daily", "missing_tool");
        when(schedulerRepo.findByEnabledTrue()).thenReturn(List.of(config));
        when(toolRegistry.getExecutor("missing_tool")).thenReturn(Optional.empty());
        when(schedulerRepo.findById(21L)).thenReturn(Optional.of(config));

        service.loadSchedules();

        assertEquals("TOOL_NOT_FOUND", config.getLastExecutionStatus());
        assertTrue(config.getLastExecutionError().contains("missing_tool"));
        assertEquals(0, service.getActiveTaskCount());
        verify(schedulerRepo).save(config);
    }

    @Test
    @DisplayName("loadSchedules 不把 null factory 当作任何工厂")
    void loadSchedules_nullFactory_failsClosed() {
        FactorySchedulerConfig config = config(31L, null, "daily", "missing_tool");
        when(schedulerRepo.findByEnabledTrue()).thenReturn(List.of(config));
        when(toolRegistry.getExecutor("missing_tool")).thenReturn(Optional.empty());
        when(schedulerRepo.findById(31L)).thenReturn(Optional.of(config));

        service.loadSchedules();

        assertEquals("CONFIG_INVALID", config.getLastExecutionStatus());
        assertTrue(config.getLastExecutionError().contains("null factory scope"));
        assertTrue(config.getLastExecutionError().contains("missing_tool"));
        assertEquals(0, service.getActiveTaskCount());
        verify(toolRegistry).getExecutor("missing_tool");
    }

    @Test
    @DisplayName("reload 用 factory 列表过滤重复 scope，不触发 nonunique 查询")
    void reloadSchedule_duplicateScope_usesFactoryListAndFailsClosed() {
        FactorySchedulerConfig first = config(41L, "F001", "daily", "report_tool");
        FactorySchedulerConfig second = config(42L, "F001", "daily", "report_tool");
        when(schedulerRepo.findByFactoryId("F001")).thenReturn(List.of(first, second));
        when(schedulerRepo.findById(41L)).thenReturn(Optional.of(first));
        when(schedulerRepo.findById(42L)).thenReturn(Optional.of(second));

        service.reloadSchedule("F001", "daily");

        assertEquals("CONFIG_INVALID", first.getLastExecutionStatus());
        assertEquals("CONFIG_INVALID", second.getLastExecutionStatus());
        assertEquals(0, service.getActiveTaskCount());
        verify(schedulerRepo).findByFactoryId("F001");
        verify(schedulerRepo, never()).findByFactoryIdAndTaskCode(any(), any());
    }

    @Test
    @DisplayName("唯一有效配置执行工具并按 config.id 精确回写 SUCCESS")
    void validSchedule_executesAndPersistsByConfigId() throws Exception {
        FactorySchedulerConfig config = config(51L, "F001", "daily", "report_tool");
        config.setParams(Map.of("limit", 10));
        config.setRunAsUserId(7L);
        when(toolRegistry.getExecutor("report_tool")).thenReturn(Optional.of(toolExecutor));
        org.mockito.Mockito.doReturn(scheduledFuture).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));
        when(runner.run(eq("F001"), eq(7L), eq("report_tool"), eq(Map.of("limit", 10)),
                eq(ToolExecutionSource.SCHEDULER), any(), any(), any()))
                .thenReturn(new AutonomousToolRunner.Outcome("SUCCESS", null,
                        new ObjectMapper().readTree("{\"success\":true,\"data\":[]}")));
        when(schedulerRepo.findById(51L)).thenReturn(Optional.of(config));

        service.scheduleTask(config);

        assertEquals(1, service.getActiveTaskCount());
        ArgumentCaptor<Runnable> runnable = ArgumentCaptor.forClass(Runnable.class);
        verify(taskScheduler).schedule(runnable.capture(), any(Trigger.class));
        runnable.getValue().run();

        // Never executed directly: only through the gateway runner, as the run-as user.
        verify(runner).run(eq("F001"), eq(7L), eq("report_tool"), eq(Map.of("limit", 10)),
                eq(ToolExecutionSource.SCHEDULER), any(), any(), any());
        verify(toolExecutor, never()).execute(any(), any());
        // By config id only: once to re-read the config at fire time, once to persist the outcome.
        verify(schedulerRepo, times(2)).findById(51L);
        verify(schedulerRepo, never()).findByFactoryIdAndTaskCode(any(), any());
        assertEquals("SUCCESS", config.getLastExecutionStatus());
        assertEquals(1L, config.getExecutionCount());
        assertFalse(config.getLastExecutionError() != null && !config.getLastExecutionError().isBlank());
    }

    @Test
    @DisplayName("工具返回 success=false 时记录业务失败")
    void validSchedule_businessFailure_isRecorded() throws Exception {
        FactorySchedulerConfig config = config(61L, "F001", "daily", "report_tool");
        when(toolRegistry.getExecutor("report_tool")).thenReturn(Optional.of(toolExecutor));
        when(runner.run(eq("F001"), any(), eq("report_tool"), any(), eq(ToolExecutionSource.SCHEDULER), any(), any(), any()))
                .thenReturn(new AutonomousToolRunner.Outcome("SUCCESS", null, new ObjectMapper().readTree(
                        "{\"success\":false,\"error\":\"access_token=secret password=abc\"}")));
        when(schedulerRepo.findById(61L)).thenReturn(Optional.of(config));

        Method executeTask = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class);
        executeTask.setAccessible(true);
        executeTask.invoke(service, config);

        assertEquals("FAILED", config.getLastExecutionStatus());
        assertTrue(config.getLastExecutionError().contains("access_token=[REDACTED]"));
        assertTrue(config.getLastExecutionError().contains("password=[REDACTED]"));
        assertFalse(config.getLastExecutionError().contains("secret"));
        verify(schedulerRepo).findById(61L);
    }

    @Test
    @DisplayName("写运行键 = 计划触发时刻: 同一计划槽无论何时到达(跨过下一计划点、迟到 2h 以上)都同键, 相邻计划槽两个键")
    void writeRunKey_isThePlannedFireInstantWheneverTheFireActuallyRuns() throws Exception {
        FactorySchedulerConfig config = config(71L, "F001", "every-minute", "write_tool");
        config.setCronExpression("0 * * * * ?");
        config.setRunAsUserId(7L);
        when(toolRegistry.getExecutor("write_tool")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.getVersion()).thenReturn("1.0.0");
        when(schedulerRepo.findById(71L)).thenReturn(Optional.of(config));
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AutonomousToolRunner.Outcome("SUCCESS", null, null));
        org.mockito.Mockito.doReturn(scheduledFuture).when(taskScheduler)
                .schedule(any(Runnable.class), any(Trigger.class));
        // Two instances (blue/green), each with its own trigger, schedule the same config.
        DynamicSchedulerService other = new DynamicSchedulerService(schedulerRepo, toolRegistry, runner, new ObjectMapper());
        Field schedulerField = DynamicSchedulerService.class.getDeclaredField("taskScheduler");
        schedulerField.setAccessible(true);
        schedulerField.set(other, taskScheduler);
        service.scheduleTask(config);
        other.scheduleTask(config);
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Trigger> triggers = ArgumentCaptor.forClass(Trigger.class);
        verify(taskScheduler, times(2)).schedule(tasks.capture(), triggers.capture());

        Instant now = Instant.parse("2026-10-22T07:00:30Z");
        Instant planned = triggers.getAllValues().get(0).nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC)));
        assertEquals(planned, triggers.getAllValues().get(1).nextExecution(new SimpleTriggerContext(Clock.fixed(now, ZoneOffset.UTC))));
        // Instance A runs on time; instance B runs the same planned fire after the next planned point
        // and hours later: neither reads a clock for the key, so both carry the planned instant.
        tasks.getAllValues().get(0).run();
        tasks.getAllValues().get(1).run();
        // The adjacent planned fire on instance A is a different key.
        Instant next = triggers.getAllValues().get(0).nextExecution(
                new SimpleTriggerContext(planned, planned, planned.plusSeconds(1)));
        tasks.getAllValues().get(0).run();

        ArgumentCaptor<String> keys = ArgumentCaptor.forClass(String.class);
        verify(runner, times(3)).run(eq("F001"), eq(7L), eq("write_tool"), any(), eq(ToolExecutionSource.SCHEDULER),
                any(), any(), keys.capture());
        assertEquals(planned.plusSeconds(60), next);
        assertEquals(List.of("slot:" + planned, "slot:" + planned, "slot:" + next), keys.getAllValues());
    }

    private FactorySchedulerConfig config(Long id, String factoryId, String taskCode, String toolName) {
        return FactorySchedulerConfig.builder()
                .id(id)
                .factoryId(factoryId)
                .taskCode(taskCode)
                .cronExpression("0 0 9 1 * ?")
                .enabled(true)
                .toolOrMethod(toolName)
                .params(Map.of())
                .build();
    }
}
