package com.cretas.aims.engine;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolExecutor.AccessMode;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolDescriptorPolicyResolver;
import com.cretas.aims.ai.tool.gateway.ToolExecutionCommand;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionSource;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The command {@link AutonomousToolRunner} builds, resolved against the real
 * {@code runtime-tool-policies.yaml}: only the pilot tool admits SCHEDULER/TRIGGER, and only while
 * the run-as user's current DB permissions still cover it.
 */
class AutonomousRunAsGatewayPolicyContractTest {
    private final ToolDescriptorPolicyResolver policies = ToolDescriptorPolicyResolver.loadDefault();
    private final ToolRegistry registry = mock(ToolRegistry.class);
    private final ToolExecutionGateway gateway = mock(ToolExecutionGateway.class);
    private final AuthenticatedToolPrincipalFactory principals = mock(AuthenticatedToolPrincipalFactory.class);
    private final AutonomousToolRunner runner = new AutonomousToolRunner(gateway, principals, registry, new ObjectMapper(),
            mock(AutonomousGrantService.class), mock(com.cretas.aims.service.PreviewTokenService.class));

    @Test void onlyThePilotToolResolvesForAutonomousSourcesAndOnlyWithCurrentPermission() {
        for (String name : new String[]{"material_expired_query", "material_batch_query", "material_stock_summary"}) {
            ToolExecutor tool = mock(ToolExecutor.class);
            when(tool.getAccessMode()).thenReturn(AccessMode.READ);
            when(tool.getVersion()).thenReturn("1.0.0");
            when(registry.getExecutor(name)).thenReturn(Optional.of(tool));
        }
        when(principals.create(eq("F001"), eq(42L), any())).thenReturn(principal(Set.of()));
        when(gateway.execute(any())).thenAnswer(call -> {
            ToolExecutionCommand c = call.getArgument(0);
            return new ToolExecutionResult(c.requestId(), c.toolName(), "1.0.0", "audit-1", c.traceId(),
                    ToolExecutionStatus.SUCCEEDED, JsonNodeFactory.instance.objectNode(), "ok", false);
        });
        ExecutionPrincipal warehouseReader = principal(Set.of("warehouse:read"));
        ExecutionPrincipal revoked = principal(Set.of("sales:read"));

        for (ToolExecutionSource source : new ToolExecutionSource[]{ToolExecutionSource.SCHEDULER, ToolExecutionSource.TRIGGER}) {
            ToolExecutionCommand pilot = captured("material_expired_query", source);
            assertThat(policies.resolve(pilot, warehouseReader)).isPresent();
            assertThat(policies.resolve(pilot, revoked)).isEmpty();
            assertThat(policies.resolve(captured("material_batch_query", source), warehouseReader)).isEmpty();
            assertThat(policies.resolve(captured("material_stock_summary", source), warehouseReader)).isEmpty();
        }
    }

    private ToolExecutionCommand captured(String tool, ToolExecutionSource source) {
        clearInvocations(gateway);
        runner.run("F001", 42L, tool, Map.of(), source, "corr-" + tool);
        ArgumentCaptor<ToolExecutionCommand> command = ArgumentCaptor.forClass(ToolExecutionCommand.class);
        verify(gateway).execute(command.capture());
        return command.getValue();
    }

    private static ExecutionPrincipal principal(Set<String> permissions) {
        return new ExecutionPrincipal("F001", "FACTORY", "42", PrincipalType.USER,
                Set.of("warehouse_manager"), permissions, Set.of());
    }
}
