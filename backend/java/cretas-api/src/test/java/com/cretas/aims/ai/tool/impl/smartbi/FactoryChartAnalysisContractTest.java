package com.cretas.aims.ai.tool.impl.smartbi;

import com.cretas.aims.dto.smartbi.FactoryChartAnalysisRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Source contract for the fixed, authenticated, read-only factory analysis path. */
class FactoryChartAnalysisContractTest {

    private static final Path ROOT = Path.of(System.getProperty("user.dir"));

    @Test
    void controllerUsesGatewayAndTrustedPrincipalOnly() throws IOException {
        String source = read("src/main/java/com/cretas/aims/controller/FactoryChartAnalysisController.java");
        assertThat(source).contains("/smart-bi", "/factory-analysis", "ToolExecutionGateway",
                "AuthenticatedToolPrincipalFactory", "ToolExecutionMode.EXECUTE",
                "ToolExecutionSource.HTTP_CONTROLLER", "analytics:read");
        assertThat(source).doesNotContain("ToolRegistry", "Registry.execute", "factoryId" + "()", "userId" + "()",
                "role" + "()", "SQL", "JOIN");
    }

    @Test
    void toolIsFixedAnalyzeReadAndWhitelistsParameters() throws IOException {
        String source = read("src/main/java/com/cretas/aims/ai/tool/impl/smartbi/FactoryChartAnalysisTool.java");
        assertThat(source).contains("smartbi_factory_analysis", "ActionType.ANALYZE",
                "RiskLevel.LOW", "AccessMode.READ", "supportsPreview()", "getVersion()",
                "1.0.0", "analytics:read", "callFactoryChartAnalysis",
                "unsupported factory analysis parameter");
        assertThat(source).doesNotContain("callFinancialDashboard", "ToolRegistry", "execute(");
    }

    @Test
    void pythonClientPinsEndpointAndTrustedHeaders() throws IOException {
        String source = read("src/main/java/com/cretas/aims/client/PythonSmartBIClient.java");
        assertThat(source).contains("callFactoryChartAnalysis",
                "api/smartbi/financial-dashboard/factory-analysis",
                "X-Factory-Id", "X-User-Id", "X-User-Role", "X-Correlation-ID");
        assertThat(source).doesNotContain("factory-analysis/" + " + endpoint",
                "X-SQL", "X-Table", "X-Role");
    }

    @Test
    void requestDtoRejectsUnknownActorEndpointSqlAndTableFields() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        for (String field : new String[] {"userId", "factoryId", "role", "endpoint", "sql", "table"}) {
            String json = "{\"question\":\"本厂产量趋势\",\"" + field + "\":\"injected\"}";
            assertThatThrownBy(() -> mapper.readValue(json, FactoryChartAnalysisRequest.class))
                    .isInstanceOf(JsonMappingException.class)
                    .hasMessageContaining(field);
        }
    }

    private static String read(String relative) throws IOException {
        return Files.readString(ROOT.resolve(relative), StandardCharsets.UTF_8);
    }
}
