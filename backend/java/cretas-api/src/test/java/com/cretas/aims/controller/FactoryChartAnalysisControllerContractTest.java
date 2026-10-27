package com.cretas.aims.controller;

import com.cretas.aims.ai.tool.gateway.AuthenticatedToolPrincipalFactory;
import com.cretas.aims.ai.tool.gateway.ExecutionPrincipal;
import com.cretas.aims.ai.tool.gateway.PrincipalType;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.ai.tool.gateway.ToolExecutionResult;
import com.cretas.aims.ai.tool.gateway.ToolExecutionStatus;
import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.smartbi.FactoryChartAnalysisRequest;
import com.cretas.aims.ai.tool.impl.smartbi.FactoryChartAnalysisFailureRelay;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

@ExtendWith(MockitoExtension.class)
class FactoryChartAnalysisControllerContractTest {

    @Mock ToolExecutionGateway gateway;
    @Mock AuthenticatedToolPrincipalFactory principalFactory;
    @Mock HttpServletRequest request;
    @Mock FactoryChartAnalysisFailureRelay failureRelay;

    FactoryChartAnalysisController controller;
    ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        controller = new FactoryChartAnalysisController(gateway, principalFactory, mapper, failureRelay);
        when(request.getAttribute("factoryId")).thenReturn("F006");
        when(request.getAttribute("userId")).thenReturn("42");
        when(request.getAttribute("role")).thenReturn("factory_super_admin");
        when(principalFactory.create("F006", 42L, "factory_super_admin"))
                .thenReturn(new ExecutionPrincipal("F006", "FACTORY", "42", PrincipalType.USER,
                        Set.of("factory_super_admin"), Set.of("analytics:read"), Set.of()));
    }

    @Test
    void returnsSingleDataLayerAndPreservesSourceErrors() {
        ObjectNode python = mapper.createObjectNode().put("success", true);
        ObjectNode data = python.putObject("data").put("factoryId", "F006");
        data.putArray("sourceErrors").add("quality source unavailable");
        when(gateway.execute(any())).thenReturn(result(mapper.createObjectNode().set("data", python)));

        FactoryChartAnalysisRequest body = new FactoryChartAnalysisRequest();
        body.setQuestion("本厂产量趋势");
        ApiResponse<Object> response = controller.analyze("F006", body, request);

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getData()).isEqualTo(Map.of(
                "factoryId", "F006", "sourceErrors", java.util.List.of("quality source unavailable")));
    }

    @Test
    void rejectsMissingOrNullPythonDataInsteadOfReturningEnvelope() {
        ObjectNode python = mapper.createObjectNode().put("success", true);
        python.putNull("data");
        when(gateway.execute(any())).thenReturn(result(mapper.createObjectNode().set("data", python)));

        FactoryChartAnalysisRequest body = new FactoryChartAnalysisRequest();
        body.setQuestion("本厂产量趋势");
        assertThatThrownBy(() -> controller.analyze("F006", body, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid response");

        ObjectNode scalar = mapper.createObjectNode().put("success", true);
        scalar.put("data", "not-an-object");
        when(gateway.execute(any())).thenReturn(result(mapper.createObjectNode().set("data", scalar)));
        assertThatThrownBy(() -> controller.analyze("F006", body, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid response");

        ObjectNode missing = mapper.createObjectNode().put("success", true);
        when(gateway.execute(any())).thenReturn(result(mapper.createObjectNode().set("data", missing)));
        assertThatThrownBy(() -> controller.analyze("F006", body, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("invalid response");
    }

    @Test
    void mapsOnlyOwnRelayedBusinessFailureAndConsumesExactlyOnce() {
        ObjectNode failedPayload = mapper.createObjectNode();
        when(gateway.execute(any())).thenReturn(new ToolExecutionResult(
                "factory-analysis-test", "smartbi_factory_analysis", "1.0.0", "audit-1", "trace-1",
                ToolExecutionStatus.FAILED, failedPayload, "tool failed", false));
        when(failureRelay.consume(any())).thenReturn(java.util.Optional.of(
                new FactoryChartAnalysisFailureRelay.Failure("request", 410, "固定失效提示")));

        FactoryChartAnalysisRequest body = new FactoryChartAnalysisRequest();
        body.setQuestion("本厂产量趋势");
        assertThatThrownBy(() -> controller.analyze("F006", body, request))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("固定失效提示");
        verify(failureRelay, times(1)).consume(any());
        verify(failureRelay, times(1)).clear(any());
    }

    private ToolExecutionResult result(ObjectNode payload) {
        return new ToolExecutionResult("factory-analysis-test", "smartbi_factory_analysis", "1.0.0",
                "audit-1", "trace-1", ToolExecutionStatus.SUCCEEDED, payload,
                "Tool execution succeeded", false);
    }
}
