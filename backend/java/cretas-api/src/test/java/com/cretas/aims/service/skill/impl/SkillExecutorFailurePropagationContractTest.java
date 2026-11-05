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
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.skill.SkillRegistry;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
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
    private final DashScopeClient llm = mock(DashScopeClient.class);
    private final SkillRegistry skillRegistry = mock(SkillRegistry.class);
    private final IntentConfigManagementService intentService = mock(IntentConfigManagementService.class);
    private final SkillExecutorImpl executor = new SkillExecutorImpl(registry, llm, mapper);

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

    @Test
    void catalogLlmCannotInventBatchNumberForRealTool() {
        StubBusinessTool first = new StubBusinessTool("indicator_query", List.of(), List.of("batchNumber", "materialId"),
                "{\"success\":true,\"data\":{}}", mapper);
        StubBusinessTool second = new StubBusinessTool("indicator_comparison", List.of(), List.of("batchNumber", "materialId"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(first);
        register(second);
        SkillDefinition skill = skill("smart-indicator-query", List.of(first.getToolName(), second.getToolName()));
        enableCatalogGate(skill);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"batchNumber\":\"B-HALLUCINATED\",\"materialId\":\"M-HALLUCINATED\"}}");

        SkillResult result = executor.execute(skill, context(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(first.lastContext).doesNotContainKey("batchNumber");
        assertThat(first.lastContext).doesNotContainKey("materialId");
    }

    @Test
    void catalogLlmMayUseEntityValueExplicitlyPresentInUserQuery() {
        StubBusinessTool first = new StubBusinessTool("indicator_query", List.of(), List.of("batchNumber", "materialId"),
                "{\"success\":true,\"data\":{}}", mapper);
        StubBusinessTool second = new StubBusinessTool("indicator_comparison", List.of(), List.of("batchNumber", "materialId"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(first);
        register(second);
        SkillDefinition skill = skill("smart-indicator-query", List.of(first.getToolName(), second.getToolName()));
        enableCatalogGate(skill);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"batchNumber\":\"B-123\",\"materialId\":\"M-7\"}}");

        SkillResult result = executor.execute(skill,
                SkillContext.builder().factoryId("F006").userId("42").userRole("operator")
                        .userQuery("查询批次 B-123，原料 M-7").extractedParams(Map.of()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(first.lastContext).containsEntry("batchNumber", "B-123");
        assertThat(first.lastContext).containsEntry("materialId", "M-7");
    }

    @Test
    void singleToolCatalogGroundsExplicitBatchBeforeBypassingRequiredParameterGate() {
        StubBusinessTool tool = new StubBusinessTool("haccp_checkpoint_review", List.of("batchNumber"),
                "{\"success\":true,\"data\":{\"batch\":\"B-123\"}}", mapper);
        register(tool);
        SkillDefinition skill = skill("haccp-checkpoint-management", List.of(tool.getToolName()));
        enableCatalogGate(skill, "HACCP_CHECKPOINT");
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"batchNumber\":\"B-123\"}}");

        SkillResult result = executor.execute(skill, SkillContext.builder()
                .factoryId("F006").userId("42").userRole("operator")
                .userQuery("查 B-123 的 HACCP").extractedParams(Map.of()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(tool.calls.get()).isEqualTo(1);
        assertThat(tool.lastContext).containsEntry("batchNumber", "B-123");
    }

    @Test
    void singleToolCatalogHallucinatedBatchReturnsClarificationAndNeverCallsTool() {
        StubBusinessTool tool = new StubBusinessTool("haccp_checkpoint_review", List.of("batchNumber"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(tool);
        SkillDefinition skill = skill("haccp-checkpoint-management", List.of(tool.getToolName()));
        enableCatalogGate(skill, "HACCP_CHECKPOINT");
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"batchNumber\":\"B-HALLUCINATED\"}}");

        SkillResult result = executor.execute(skill, SkillContext.builder()
                .factoryId("F006").userId("42").userRole("operator")
                .userQuery("查询 HACCP").extractedParams(Map.of()).build());

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isInstanceOf(Map.class);
        assertThat(((Map<?, ?>) result.getData()).get("status")).isEqualTo("NEED_MORE_INFO");
        assertThat(tool.calls.get()).isZero();
    }

    @Test
    void deterministicToolFailureEnvelopeIsFlattenedForSkillConsumers() {
        StubBusinessTool tool = new StubBusinessTool("batch_query", List.of(),
                "{\"success\":false,\"status\":\"NEED_MORE_INFO\",\"needMoreInfo\":true,"
                        + "\"clarificationQuestions\":[\"请提供批次号\"],\"error\":\"请提供批次号\"}", mapper);
        register(tool);
        SkillDefinition skill = skill("batch-query", List.of(tool.getToolName()));

        SkillResult result = executor.execute(skill, context(Map.of()));

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getData()).isInstanceOf(Map.class);
        Map<?, ?> data = (Map<?, ?>) result.getData();
        assertThat(data.get("status")).isEqualTo("NEED_MORE_INFO");
        assertThat(data.get("needMoreInfo")).isEqualTo(true);
        assertThat(data.containsKey("clarificationQuestions")).isTrue();
        assertThat(data.containsKey("toolResults")).isTrue();
    }

    @Test
    void catalogSelectedNoParamFoodSampleToolDoesNotRequireUnselectedBatchTrace() {
        allowReadOnlyToolForParameterContract();
        StubBusinessTool pending = new StubBusinessTool("food_sample_query_pending_dispose", List.of(),
                "{\"success\":true,\"data\":{\"pending\":true}}", mapper);
        StubBusinessTool trace = new StubBusinessTool("food_sample_trace", List.of("batchNumber"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(pending);
        register(trace);
        SkillDefinition skill = skill("food-sample-lifecycle",
                List.of(pending.getToolName(), trace.getToolName()));
        enableCatalogGate(skill, "FOOD_SAMPLE_LIFECYCLE");
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"tools_to_execute\":[{\"tool_name\":\"food_sample_query_pending_dispose\",\"params\":{}}],"
                        + "\"execution_order\":\"sequential\"}");

        SkillResult result = executor.execute(skill, SkillContext.builder()
                .factoryId("F006").userId("42").userRole("operator")
                .userQuery("哪些留样待销毁").extractedParams(Map.of()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(pending.calls.get()).isEqualTo(1);
        assertThat(trace.calls.get()).isZero();
    }

    @Test
    void catalogRecallSelectedBatchTraceDoesNotRequireUnselectedLossEstimateEvent() {
        StubBusinessTool trace = new StubBusinessTool("batch_full_trace", List.of("batchNumber"),
                "{\"success\":true,\"data\":{}}", mapper);
        StubBusinessTool loss = new StubBusinessTool("recall_loss_estimate", List.of("recallEventId"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(trace);
        register(loss);
        SkillDefinition skill = skill("food-safety-recall",
                List.of(trace.getToolName(), loss.getToolName()));
        enableCatalogGate(skill, "FOOD_SAFETY_RECALL");
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"batchNumber\":\"B-123\",\"recallEventId\":999},"
                        + "\"tools_to_execute\":[{\"tool_name\":\"batch_full_trace\","
                        + "\"params\":{\"batchNumber\":\"B-123\"}}]}");

        SkillResult result = executor.execute(skill, SkillContext.builder()
                .factoryId("F006").userId("42").userRole("operator")
                .userQuery("追溯批次 B-123").extractedParams(Map.of()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(trace.calls.get()).isEqualTo(1);
        assertThat(loss.calls.get()).isZero();
        assertThat(trace.lastContext).containsEntry("batchNumber", "B-123")
                .doesNotContainKey("recallEventId");
    }

    @Test
    void catalogPlanDropsUndeclaredToolInsteadOfExecutingIt() {
        allowReadOnlyToolForParameterContract();
        StubBusinessTool declared = new StubBusinessTool("food_sample_query_pending_dispose", List.of(),
                "{\"success\":true,\"data\":{}}", mapper);
        StubBusinessTool forged = new StubBusinessTool("invoice_issue_forged", List.of(),
                "{\"success\":true,\"data\":{}}", mapper);
        register(declared);
        register(forged);
        SkillDefinition skill = skill("food-sample-lifecycle", List.of(declared.getToolName()));
        enableCatalogGate(skill, "FOOD_SAMPLE_LIFECYCLE");
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"tools_to_execute\":[{\"tool_name\":\"invoice_issue_forged\",\"params\":{}}]}");

        SkillResult result = executor.execute(skill, context(Map.of()));

        assertThat(result.isSuccess()).isTrue();
        assertThat(declared.calls.get()).isEqualTo(1);
        assertThat(forged.calls.get()).isZero();
    }

    @Test
    void catalogQualitySelectedCustomerLookupDoesNotRequireUnselectedBatchChecks() {
        StubBusinessTool customer = new StubBusinessTool("customer_quality_standard", List.of("customerId"),
                "{\"success\":true,\"data\":{}}", mapper);
        StubBusinessTool quality = new StubBusinessTool("quality_check_summary", List.of("batchNumber"),
                "{\"success\":true,\"data\":{}}", mapper);
        register(customer);
        register(quality);
        SkillDefinition skill = skill("quality-chief-workdesk",
                List.of(customer.getToolName(), quality.getToolName()));
        enableCatalogGate(skill, "QUALITY_CHIEF_WORKDESK");
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted_params\":{\"customerId\":\"C-7\"},\"tools_to_execute\":[{"
                        + "\"tool_name\":\"customer_quality_standard\",\"params\":{\"customerId\":\"C-7\"}}]}");

        SkillResult result = executor.execute(skill, SkillContext.builder()
                .factoryId("F006").userId("42").userRole("operator")
                .userQuery("查询客户 C-7 的质量标准").extractedParams(Map.of()).build());

        assertThat(result.isSuccess()).isTrue();
        assertThat(customer.calls.get()).isEqualTo(1);
        assertThat(quality.calls.get()).isZero();
        assertThat(customer.lastContext).containsEntry("customerId", "C-7");
    }

    private void enableCatalogGate(SkillDefinition skill) {
        enableCatalogGate(skill, "SMART_INDICATOR_QUERY");
    }

    private void allowReadOnlyToolForParameterContract() {
        com.cretas.aims.ai.tool.WriteGuardService guard = mock(com.cretas.aims.ai.tool.WriteGuardService.class);
        when(guard.isWriteTool(any())).thenReturn(false);
        ReflectionTestUtils.setField(executor, "writeGuardService", guard);
    }

    private void enableCatalogGate(SkillDefinition skill, String intentCode) {
        ReflectionTestUtils.setField(executor, "skillRegistry", skillRegistry);
        ReflectionTestUtils.setField(executor, "intentConfigManagementService", intentService);
        when(skillRegistry.getSkillForFactory("F006", skill.getName())).thenReturn(skill);
        AIIntentConfig intent = new AIIntentConfig();
        intent.setFactoryId("F006");
        intent.setIsActive(true);
        when(intentService.getIntentByCode("F006", intentCode))
                .thenReturn(Optional.of(intent));
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
        private final List<String> properties;
        private final String response;
        private final AtomicInteger calls = new AtomicInteger();
        private Map<String, Object> lastContext = new HashMap<>();

        private StubBusinessTool(String name, List<String> required, String response, ObjectMapper mapper) {
            this(name, required, required, response, mapper);
        }

        private StubBusinessTool(String name, List<String> required, List<String> properties,
                                 String response, ObjectMapper mapper) {
            this.name = name;
            this.required = required;
            this.properties = properties;
            this.response = response;
            this.objectMapper = mapper;
        }

        @Override
        public String getToolName() { return name; }

        @Override
        public String getDescription() { return "test business tool"; }

        @Override
        public Map<String, Object> getParametersSchema() {
            Map<String, Object> properties = new HashMap<>();
            this.properties.forEach(name -> properties.put(name, Map.of("type", "string")));
            return Map.of("type", "object", "properties", properties, "required", required);
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
            lastContext = new HashMap<>(context);
            return response;
        }

        @Override
        public AccessMode getAccessMode() { return AccessMode.READ; }
    }
}
