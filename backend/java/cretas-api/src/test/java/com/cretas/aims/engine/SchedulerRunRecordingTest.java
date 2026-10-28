package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.usage.LlmUsageScope;
import com.cretas.aims.entity.config.FactorySchedulerConfig;
import com.cretas.aims.entity.config.SchedulerRunResult;
import com.cretas.aims.repository.config.FactorySchedulerConfigRepository;
import com.cretas.aims.repository.config.SchedulerRunResultRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * S6-03 / S6-06 / S6-11 through the scheduler's real fire path: a finished run is published, recorded,
 * an undeterminable run is UNKNOWN (never SUCCESS), and token usage is unknown unless the provider gave it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("定时运行结果持久化 / UNKNOWN / Token 成本")
class SchedulerRunRecordingTest {

    @Mock private FactorySchedulerConfigRepository schedulerRepo;
    @Mock private ToolRegistry toolRegistry;
    @Mock private ToolExecutor toolExecutor;
    @Mock private AutonomousToolRunner runner;
    @Mock private SchedulerRunResultRepository runRepo;

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<SchedulerRunFinishedEvent> published = new ArrayList<>();
    private DynamicSchedulerService service;
    private SchedulerRunRecorder recorder;

    @BeforeEach
    void setUp() throws Exception {
        service = new DynamicSchedulerService(schedulerRepo, toolRegistry, runner, mapper);
        var events = DynamicSchedulerService.class.getDeclaredField("events");
        events.setAccessible(true);
        events.set(service, (org.springframework.context.ApplicationEventPublisher) e -> {
            if (e instanceof SchedulerRunFinishedEvent f) published.add(f);
        });
        recorder = new SchedulerRunRecorder(runRepo);
        when(toolRegistry.getExecutor("report_tool")).thenReturn(Optional.of(toolExecutor));
        when(toolExecutor.getVersion()).thenReturn("1.0.0");
    }

    private FactorySchedulerConfig config(long id, String factory) {
        FactorySchedulerConfig c = FactorySchedulerConfig.builder().id(id).factoryId(factory).taskCode("daily")
                .cronExpression("0 0 9 1 * ?").enabled(true).toolOrMethod("report_tool").params(Map.of("limit", 5))
                .build();
        c.setRunAsUserId(7L);
        when(schedulerRepo.findById(id)).thenReturn(Optional.of(c));
        return c;
    }

    private void fire(FactorySchedulerConfig config) throws Exception {
        Method m = DynamicSchedulerService.class.getDeclaredMethod("executeTask", FactorySchedulerConfig.class);
        m.setAccessible(true);
        m.invoke(service, config);
    }

    private SchedulerRunResult recorded() {
        assertEquals(1, published.size(), "a finished fire publishes exactly one result");
        return recorder.toResult(published.get(0));
    }

    @Test
    @DisplayName("成功的运行：结果载荷、配置快照、工厂都被记下来")
    void successIsPersistedWithSnapshot() throws Exception {
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new AutonomousToolRunner.Outcome("SUCCESS", null,
                        mapper.readTree("{\"success\":true,\"message\":\"3 批次临期\",\"data\":[1,2,3]}")));

        fire(config(1L, "F001"));

        SchedulerRunResult r = recorded();
        assertEquals("SUCCESS", r.getStatus());
        assertEquals("F001", r.getFactoryId());
        assertEquals("daily", r.getTaskCode());
        assertEquals(1L, r.getConfigId());
        assertEquals("3 批次临期", r.getSummary());
        assertNotNull(r.getDataSnapshot(), "the result payload must not be dropped");
        assertEquals(5, ((Map<?, ?>) r.getConfigSnapshot().get("params")).get("limit"));
        assertNotNull(r.getStartedAt());
        assertNotNull(r.getFinishedAt());
        assertNotNull(r.getRunId());
    }

    @Test
    @DisplayName("结果无法判定（OUTCOME_UNKNOWN / TIMEOUT）记 UNKNOWN，绝不记 SUCCESS")
    void undeterminableOutcomeIsUnknownNeverSuccess() throws Exception {
        for (String raw : List.of("OUTCOME_UNKNOWN", "TIMEOUT")) {
            published.clear();
            when(runner.run(any(), any(), any(), any(), any(), any(), any(), any()))
                    .thenReturn(new AutonomousToolRunner.Outcome(raw, "gateway " + raw, null));

            fire(config(2L, "F001"));

            SchedulerRunResult r = recorded();
            assertEquals("UNKNOWN", r.getStatus(), raw);
            assertNull(r.getResolution());
            assertEquals("UNKNOWN", r.effectiveStatus(), "an unruled UNKNOWN run counts as unknown");
            assertEquals(raw, r.getRawStatus());
        }
    }

    @Test
    @DisplayName("运行器状态集合是开放的：不认识的状态记 UNKNOWN，不默认成失败也不成功")
    void unrecognisedRawStatusIsUnknown() throws Exception {
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AutonomousToolRunner.Outcome("SOMETHING_NEW", "x", null));
        fire(config(7L, "F001"));
        assertEquals("UNKNOWN", recorded().getStatus());
    }

    @Test
    @DisplayName("明确失败记 FAILED；业务失败（success=false）也是 FAILED")
    void definiteFailureIsFailed() throws Exception {
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new AutonomousToolRunner.Outcome("GATEWAY_DENIED", "denied", null));
        fire(config(3L, "F001"));
        assertEquals("FAILED", recorded().getStatus());

        published.clear();
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new AutonomousToolRunner.Outcome("SUCCESS", null, mapper.readTree("{\"success\":false,\"error\":\"x\"}")));
        fire(config(3L, "F001"));
        SchedulerRunResult r = recorded();
        assertEquals("FAILED", r.getStatus());
        assertNull(r.getDataSnapshot());
    }

    @Test
    @DisplayName("别的实例已跑过的重放（replay）不重复记录")
    void replayIsNotRecordedTwice() throws Exception {
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new AutonomousToolRunner.Outcome("SUCCESS", null, null, true));
        fire(config(4L, "F001"));
        assertTrue(published.isEmpty());
    }

    @Test
    @DisplayName("运行内的 LLM 调用都带 usage：记合计；有一次没有 usage：记未知，不写 0")
    void tokenCostFromLlmCallMetadata() throws Exception {
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LlmUsageScope.report(100, 20, 120);
            LlmUsageScope.report(10, 5, null);
            return new AutonomousToolRunner.Outcome("SUCCESS", null, null);
        });
        fire(config(5L, "F001"));
        SchedulerRunResult known = recorded();
        assertEquals("KNOWN", known.getTokenUsageState());
        assertEquals(2, known.getLlmCallCount());
        assertEquals(110, known.getPromptTokens());
        assertEquals(25, known.getCompletionTokens());
        assertEquals(135, known.getTotalTokens());

        published.clear();
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenAnswer(inv -> {
            LlmUsageScope.report(100, 20, 120);
            LlmUsageScope.report(null, null, null);
            return new AutonomousToolRunner.Outcome("SUCCESS", null, null);
        });
        fire(config(5L, "F001"));
        SchedulerRunResult unknown = recorded();
        assertEquals("UNKNOWN", unknown.getTokenUsageState());
        assertNull(unknown.getTotalTokens(), "no provider usage must read as unknown, not as a partial or zero total");
        assertNull(unknown.getPromptTokens());

        published.clear();
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new AutonomousToolRunner.Outcome("SUCCESS", null, null));
        fire(config(5L, "F001"));
        SchedulerRunResult none = recorded();
        assertEquals("NONE", none.getTokenUsageState());
        assertNull(none.getTotalTokens());
        assertEquals(0, none.getLlmCallCount());
    }

    @Test
    @DisplayName("过大的结果载荷不入库，标记 snapshotTruncated")
    void oversizedPayloadIsFlaggedNotStored() throws Exception {
        String big = "x".repeat(SchedulerRunRecorder.MAX_SNAPSHOT_CHARS + 10);
        when(runner.run(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new AutonomousToolRunner.Outcome("SUCCESS", null, mapper.readTree("{\"success\":true,\"blob\":\"" + big + "\"}")));
        fire(config(6L, "F001"));
        SchedulerRunResult r = recorded();
        assertEquals("SUCCESS", r.getStatus());
        assertNull(r.getDataSnapshot());
        assertTrue(r.getSnapshotTruncated());
    }
}
