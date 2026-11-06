package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ToolRuntimeClassificationContractTest {
    @Test
    void currentGovernedWritesAreRefusedForIntentDispatchRatherThanRunAsLegacy() {
        var descriptors = RuntimeToolDescriptorRegistry.loadDefault();
        var writes = descriptors.approvedToolNames().stream()
                .map(name -> descriptors.findApproved(name).orElseThrow())
                .filter(d -> d.actionType() == ToolExecutor.ActionType.WRITE
                        || d.actionType() == ToolExecutor.ActionType.UPDATE
                        || d.actionType() == ToolExecutor.ActionType.DELETE).toList();
        assertThat(writes).extracting(ToolDescriptor::toolName).containsExactlyInAnyOrder(
                "user_disable", "canvas_work_process_catalog",
                "canvas_toggle_module", "canvas_toggle_tool", "canvas_toggle_skill",
                "canvas_update_field", "canvas_update_trigger_chain", "canvas_apply_template",
                "product_create", "alert_rule_toggle");
        ToolDescriptorPolicyResolver resolver = new ToolDescriptorPolicyResolver(descriptors);
        for (ToolDescriptor descriptor : writes) {
            assertThat(descriptor.allowedSources()).doesNotContain(ToolExecutionSource.AI_INTENT_DISPATCH);
            ExecutionPrincipal actor = new ExecutionPrincipal("F006", "FACTORY", "42", PrincipalType.USER,
                    java.util.Set.of("factory_super_admin"), descriptor.requiredPermissions(), java.util.Set.of());
            ToolExecutionCommand command = new ToolExecutionCommand("request", "correlation", "trace",
                    descriptor.toolName(), descriptor.version(), new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode(),
                    actor, ToolExecutionSource.AI_INTENT_DISPATCH, ToolExecutionMode.EXECUTE,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                    java.time.Instant.now().plusSeconds(30));
            assertThat(resolver.resolve(command, actor)).isEmpty();
        }
    }

    @Test
    void unavailablePolicyMustNotBeReportedAsUngoverned() {
        RuntimeToolDescriptorRegistry descriptors = mock(RuntimeToolDescriptorRegistry.class);
        when(descriptors.findApproved("material_consume"))
                .thenThrow(new IllegalStateException("policy unavailable"));
        ToolRuntimeRegistry registry = new ToolRuntimeRegistry(mock(ToolRegistry.class),
                mock(FactoryToolConfigRepository.class), descriptors);
        assertThatThrownBy(() -> registry.isGoverned("material_consume"))
                .isInstanceOf(IllegalStateException.class).hasMessage("policy unavailable");
    }
}
