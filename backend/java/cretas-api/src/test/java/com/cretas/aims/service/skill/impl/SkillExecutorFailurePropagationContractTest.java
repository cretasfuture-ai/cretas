package com.cretas.aims.service.skill.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ToolCall;
import com.cretas.aims.ai.tool.AbstractBusinessTool;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.dto.skill.SkillResult;
import com.cretas.aims.service.execution.ToolPermissionChain;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Skill orchestration must preserve Tool failure/clarification envelopes. */
class SkillExecutorFailurePropagationContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolPermissionChain permissions = mock(ToolPermissionChain.class);
    private final SkillExecutorImpl executor = new SkillExecutorImpl(registry, mock(DashScopeClient.class), mapper);

    SkillExecutorFailurePropagationContractTest() {
        ReflectionTestUtils.setField(executor, "writeGuardService", new com.cretas.aims.ai.tool.WriteGuardService());
        ReflectionTestUtils.setField(executor, "toolPermissionChain", permissions);
        when(permissions.denyReason(any(), anyString(), anyString(), any(), any())).thenReturn(Optional.empty());
        when(registry.isToolEnabledForFactory(anyString(), anyString())).thenReturn(true);
    }

    @AfterEach
    void shutdown() {
        executor.shutdown();
    }

    @Test
    void deterministicAbstractBusinessToolMissingBatchNumberReturnsClarificationWithoutCallingTool() {
        StubBusinessTool tool = new StubBusinessTool("batch_query", List.of("batchNumber"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(tool);

        SkillResult result = executor.execute(skill("batch-query", List.of(tool.getToolName())), context(Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("batchNumber");
        assertThat(result.getData()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.getData()).get("status")).isEqualTo("NEED_MORE_INFO");
        assertThat(tool.calls.get()).isZero();
    }

    @Test
    void sequentialChainStopsAfterFirstJsonFailureAndDoesNotReportSuccess() {
        StubBusinessTool first = new StubBusinessTool("first_read", List.of(),
                "{\"success\":false,\"status\":\"NEED_MORE_INFO\",\"error\":\"请提供批次号\"}", mapper);
        StubBusinessTool second = new StubBusinessTool("second_read", List.of(),
                "{\"success\":true,\"data\":{\"ok\":true}}", mapper);
        register(first);
        register(second);

        SkillDefinition skill = skill("multi-read", List.of(first.getToolName(), second.getToolName()));
        SkillResult result = executor.execute(skill, context(Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getMessage()).contains("请提供批次号");
        assertThat(result.getExecutedTools()).contains(first.getToolName()).doesNotContain(second.getToolName());
        assertThat(first.calls.get()).isEqualTo(1);
        assertThat(second.calls.get()).isZero();
    }

    private void register(ToolExecutor tool) {
        when(registry.hasExecutor(tool.getToolName())).thenReturn(true);
        when(registry.getExecutor(tool.getToolName())).thenReturn(Optional.of(tool));
    }

    private static SkillDefinition skill(String name, List<String> tools) {
        return SkillDefinition.builder().name(name).enabled(true).tools(tools)
                .contextNeeded(List.of("factoryId", "userId")).build();
    }

    private static SkillContext context(Map<String, Object> extractedParams) {
        return SkillContext.builder().factoryId("F006").userId("42").userRole("operator")
                .userQuery("查询批次").extractedParams(extractedParams).build();
    }

    private static final class StubBusinessTool extends AbstractBusinessTool {
        private final String name;
        private final List<String> required;
        private final String response;
        private final AtomicInteger calls = new AtomicInteger();

        private StubBusinessTool(String name, List<String> required, String response, ObjectMapper mapper) {
            this.name = name;
            this.required = required;
            this.response = response;
            this.objectMapper = mapper;
        }

        @Override
        public String getToolName() { return name; }

        @Override
        public String getDescription() { return "test business tool"; }

        @Override
        public Map<String, Object> getParametersSchema() {
            return Map.of("type", "object", "required", required);
        }

        @Override
        protected List<String> getRequiredParameters() { return required; }

        @Override
        protected Map<String, Object> doExecute(String factoryId, Map<String, Object> params,
                                                Map<String, Object> context) {
            return Map.of("ok", true);
        }

        @Override
        public String execute(ToolCall toolCall, Map<String, Object> context) {
            calls.incrementAndGet();
            return response;
        }

        @Override
        public AccessMode getAccessMode() { return AccessMode.READ; }
    }
}
