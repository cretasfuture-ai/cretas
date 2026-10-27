package com.cretas.aims.ai.tool.gateway;

import com.cretas.aims.ai.tool.ToolExecutor;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolPrincipalPolicy.RehydratedPrincipal;
import com.cretas.aims.ai.tool.gateway.ToolRuntimeRegistry.ResolvedTool;
import com.cretas.aims.ai.tool.impl.smartbi.FactoryChartAnalysisTool;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.config.FactoryToolConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real runtime descriptor + ToolRuntimeRegistry + DefaultToolExecutionGateway contract. */
@ExtendWith(MockitoExtension.class)
class FactoryChartAnalysisGatewayContractTest {

    @Mock ToolPrincipalPolicy principalPolicy;
    @Mock ToolRegistry toolRegistry;
    @Mock FactoryToolConfigRepository factoryToolConfigRepository;
    @Mock ToolExecutionLedgerService ledgerService;
    @Mock PythonSmartBIClient pythonClient;

    DefaultToolExecutionGateway gateway;
    FactoryChartAnalysisTool tool;
    RehydratedPrincipal current;

    @BeforeEach
    void setUp() throws Exception {
        tool = new FactoryChartAnalysisTool(pythonClient);
        ReflectionTestUtils.setField(tool, "objectMapper", new ObjectMapper());
        lenient().when(toolRegistry.getExecutor("smartbi_factory_analysis"))
                .thenReturn(Optional.of(tool));
        lenient().when(factoryToolConfigRepository.findByFactoryIdAndToolName(any(), any()))
                .thenReturn(Optional.empty());
        current = new RehydratedPrincipal(
                new ExecutionPrincipal("F006", "FACTORY", "42", PrincipalType.USER,
                        Set.of("factory_super_admin"), Set.of("analytics:read"), Set.of()),
                Map.of("factoryId", "F006", "tenantId", "F006", "businessType", "FACTORY",
                        "userId", 42L, "userRole", "factory_super_admin"));
        when(principalPolicy.rehydrate(any())).thenReturn(Optional.of(current));
        when(ledgerService.beginAudit(any(), any())).thenReturn("audit-factory-analysis");
        ToolRuntimeRegistry runtime = new ToolRuntimeRegistry(
                toolRegistry, factoryToolConfigRepository,
                com.cretas.aims.ai.tool.gateway.descriptor.RuntimeToolDescriptorRegistry.loadDefault());
        gateway = new DefaultToolExecutionGateway(
                principalPolicy, runtime, mock(ToolConfirmationLease.class), ledgerService,
                new ObjectMapper());
    }

    @Test
    void approvedFactoryPrincipalReachesRealGatewayAndPythonWithFixedPermit() throws Exception {
        when(pythonClient.callFactoryChartAnalysis(
                eq("F006"), eq("42"), eq("factory_super_admin"), any(), any()))
                .thenReturn(Map.of("success", true, "data", Map.of("factoryId", "F006")));

        ToolExecutionResult result = gateway.execute(command("F006", "42"));

        assertThat(result.status()).isEqualTo(ToolExecutionStatus.SUCCEEDED);
        verify(pythonClient).callFactoryChartAnalysis(
                eq("F006"), eq("42"), eq("factory_super_admin"), any(),
                argThat(body -> "本厂产量趋势".equals(body.get("question"))
                        && !body.containsKey("factory_id")));
    }

    @Test
    void crossFactoryOrRevokedPrincipalCannotReachPython() throws Exception {
        when(principalPolicy.rehydrate(any())).thenReturn(Optional.empty());

        assertThat(gateway.execute(command("F007", "42")).status())
                .isEqualTo(ToolExecutionStatus.DENIED);
        assertThat(gateway.execute(command("F006", "99")).status())
                .isEqualTo(ToolExecutionStatus.DENIED);
        verifyNoInteractions(pythonClient);
    }

    @Test
    void maliciousBodyActorAndRestaurantBusinessTypeCannotReachPython() throws Exception {
        ToolExecutionCommand malicious = command("F006", "42");
        malicious = new ToolExecutionCommand(
                malicious.requestId(), malicious.correlationId(), malicious.traceId(),
                malicious.toolName(), malicious.expectedDescriptorVersion(),
                new ObjectMapper().createObjectNode().put("question", "本厂产量趋势")
                        .put("userId", "9999").put("factoryId", "F007"),
                malicious.principal(), malicious.source(), malicious.mode(),
                malicious.idempotencyKey(), malicious.confirmationProof(), malicious.approvalProof(),
                malicious.deadline());
        assertThat(gateway.execute(malicious).status()).isEqualTo(ToolExecutionStatus.FAILED);
        verifyNoInteractions(pythonClient);

        RehydratedPrincipal restaurant = new RehydratedPrincipal(
                new ExecutionPrincipal("R001", FactoryType.RESTAURANT.name(), "42", PrincipalType.USER,
                        Set.of("restaurant_manager"), Set.of("analytics:read"), Set.of()),
                Map.of("factoryId", "R001", "businessType", "RESTAURANT", "userId", 42L));
        when(principalPolicy.rehydrate(any())).thenReturn(Optional.of(restaurant));
        assertThat(gateway.execute(command("R001", "42")).status())
                .isEqualTo(ToolExecutionStatus.DENIED);
        verifyNoInteractions(pythonClient);
    }

    private ToolExecutionCommand command(String factoryId, String userId) {
        return new ToolExecutionCommand(
                "factory-analysis-test-" + factoryId + "-" + userId,
                "corr-factory-analysis", "trace-factory-analysis", "smartbi_factory_analysis",
                "1.0.0", new ObjectMapper().createObjectNode().put("question", "本厂产量趋势"),
                new ExecutionPrincipal(factoryId, "FACTORY", userId, PrincipalType.USER,
                        Set.of("factory_super_admin"), Set.of("analytics:read"), Set.of()),
                ToolExecutionSource.HTTP_CONTROLLER, ToolExecutionMode.EXECUTE,
                Optional.empty(), Optional.empty(), Optional.empty(), Instant.now().plusSeconds(90));
    }
}
