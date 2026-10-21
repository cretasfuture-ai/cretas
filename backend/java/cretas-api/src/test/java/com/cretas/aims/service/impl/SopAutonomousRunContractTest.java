package com.cretas.aims.service.impl;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.engine.AutonomousToolPolicy;
import com.cretas.aims.engine.AutonomousToolRunner;
import com.cretas.aims.event.SopUploadedEvent;
import com.cretas.aims.repository.smartbi.AiAgentRuleRepository;
import com.cretas.aims.service.workflow.SandboxedSpelEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * SOP tool chains no longer run as the hard-coded "system" user 0 and never call a tool
 * directly: every step goes through the gateway runner as the requesting factory user with
 * source SOP. The async upload event has no verified user, so its steps run with no run-as user
 * (refused by the runner). The write step of the default flow is stopped there (WRITE_DENIED).
 */
class SopAutonomousRunContractTest {
    private final SopAgentOrchestratorImpl orchestrator = new SopAgentOrchestratorImpl();
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final AutonomousToolRunner runner = mock(AutonomousToolRunner.class);
    private final AiAgentRuleRepository rules = mock(AiAgentRuleRepository.class);
    private final ToolExecutor anyTool = mock(ToolExecutor.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach void wire() throws Exception {
        set("toolRegistry", registry);
        set("autonomousToolRunner", runner);
        set("ruleRepository", rules);
        set("objectMapper", mapper);
        set("spelEvaluator", new SandboxedSpelEvaluator());
        when(registry.getExecutor(any())).thenReturn(Optional.of(anyTool));
        when(rules.findByFactoryIdAndTriggerTypeAndIsActiveTrue(any(), any())).thenReturn(List.of());
        when(runner.run(any(), any(), eq("sop_parse_document"), any(), any(), any())).thenReturn(ok(
                "{\"success\":true,\"data\":{\"content\":\"c\",\"steps\":[\"a\"]}}"));
        when(runner.run(any(), any(), eq("sop_analyze_complexity"), any(), any(), any())).thenReturn(ok(
                "{\"success\":true,\"data\":{\"level\":3,\"reason\":\"r\",\"stepCount\":1}}"));
        when(runner.run(any(), any(), eq("sku_update_complexity"), any(), any(), any())).thenReturn(
                new AutonomousToolRunner.Outcome(AutonomousToolPolicy.WRITE_DENIED,
                        "write tool not allowed without a user: sku_update_complexity", null));
    }

    @Test void analysisRunsEveryStepThroughTheGatewayAsTheRequesterAndTheWriteStepIsStopped() throws Exception {
        Map<String, Object> result = orchestrator.analyzeSopAndUpdateComplexity("F001", "oss://sop.pdf", "SKU-1", 42L);

        for (String tool : List.of("sop_parse_document", "sop_analyze_complexity", "sku_update_complexity")) {
            verify(runner).run(eq("F001"), eq(42L), eq(tool), any(), eq(ToolExecutionSource.SOP), any());
        }
        verify(anyTool, never()).execute(any(), any());
        assertThat(result.get("success")).isEqualTo(false);
        assertThat(result.get("failedStep")).isEqualTo("sku_update_complexity");
        assertThat(String.valueOf(result.get("error"))).contains(AutonomousToolPolicy.WRITE_DENIED);
    }

    @Test void stepsReceiveTheOutputOfEarlierStepsNotTheContextIdentity() throws Exception {
        orchestrator.analyzeSopAndUpdateComplexity("F001", "oss://sop.pdf", "SKU-1", 42L);
        verify(runner).run(eq("F001"), eq(42L), eq("sop_analyze_complexity"),
                argThat(params -> "c".equals(params.get("sopContent")) && !params.containsKey("userId")),
                eq(ToolExecutionSource.SOP), any());
    }

    @Test void asyncUploadEventHasNoVerifiedUserSoStepsCarryNoRunAsUser() throws Exception {
        orchestrator.handleSopUpload(new SopUploadedEvent(this, "F001", "sop-1", "oss://sop.pdf", "PDF",
                "SKU-1", "pt-1", 7L, "sop.pdf"));
        verify(runner).run(eq("F001"), isNull(), eq("sop_parse_document"), any(), eq(ToolExecutionSource.SOP), any());
        verify(runner, never()).run(any(), eq(7L), any(), any(), any(), any());
        verify(runner, never()).run(any(), eq(0L), any(), any(), any(), any());
        verify(anyTool, never()).execute(any(), any());
    }

    private AutonomousToolRunner.Outcome ok(String json) throws Exception {
        return new AutonomousToolRunner.Outcome("SUCCESS", null, mapper.readTree(json));
    }

    private void set(String name, Object value) throws Exception {
        Field field = SopAgentOrchestratorImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(orchestrator, value);
    }
}
