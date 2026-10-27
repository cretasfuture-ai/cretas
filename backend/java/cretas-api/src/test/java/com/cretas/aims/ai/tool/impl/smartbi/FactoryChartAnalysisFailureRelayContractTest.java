package com.cretas.aims.ai.tool.impl.smartbi;

import com.cretas.aims.client.PythonServiceCircuitBreaker;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract for business 4xx relay semantics and the fixed upstream call. */
class FactoryChartAnalysisFailureRelayContractTest {

    private MockWebServer server;
    private PythonSmartBIClient client;
    private PythonServiceCircuitBreaker breaker;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        config.setUrl(server.url("/").toString());
        config.setConnectTimeout(1000);
        config.setTimeout(2000);
        breaker = new PythonServiceCircuitBreaker();
        client = new PythonSmartBIClient(config, new OkHttpClient(), new ObjectMapper(), breaker, "test-secret");
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void businessStatusesAreTypedFixedMessageAndDoNotPolluteBreaker() throws Exception {
        for (int status : new int[] {403, 409, 410, 422}) {
            server.enqueue(new MockResponse().setResponseCode(status).setBody("{" +
                    "\"secret\":\"must-not-be-parsed\"}"));

            assertThatThrownBy(() -> call())
                    .isInstanceOf(PythonSmartBIClient.FactoryAnalysisUpstreamException.class)
                    .satisfies(error -> {
                        var typed = (PythonSmartBIClient.FactoryAnalysisUpstreamException) error;
                        assertThat(typed.statusCode()).isEqualTo(status);
                        assertThat(typed.getMessage()).doesNotContain("secret");
                        if (status == 409) {
                            assertThat(typed.getMessage()).isEqualTo("对话上下文正在更新，请稍后重试");
                        }
                    });
            assertThat(breaker.getMetrics().get("totalFailures")).isEqualTo(0L);
        }
        assertThat(server.getRequestCount()).isEqualTo(4);
    }

    @Test
    void serverFailureRecordsBreakerFailureAndUsesOneBoundedAttempt() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("upstream details"));

        assertThatThrownBy(() -> call())
                .isInstanceOf(IOException.class)
                .isNotInstanceOf(PythonSmartBIClient.FactoryAnalysisUpstreamException.class);

        assertThat(server.getRequestCount()).isEqualTo(1);
        assertThat(breaker.getMetrics().get("totalFailures")).isEqualTo(1L);
        assertThat(breaker.getMetrics().get("consecutiveFailures")).isEqualTo(1);
    }

    @Test
    void relayIsRequestScopedAndCannotBeConsumedByAnotherRequest() {
        FactoryChartAnalysisFailureRelay relay = new FactoryChartAnalysisFailureRelay();
        relay.record("request-1", 410, "fixed message");

        assertThat(relay.consume("request-2")).isEmpty();
        assertThat(relay.consume("request-1")).hasValueSatisfying(failure -> {
            assertThat(failure.statusCode()).isEqualTo(410);
            assertThat(failure.message()).isEqualTo("fixed message");
        });
        assertThat(relay.consume("request-1")).isEmpty();
    }

    private Map<String, Object> call() throws IOException {
        return client.callFactoryChartAnalysis(
                "F006", "42", "factory_super_admin", "corr-test", Map.of("question", "趋势"));
    }
}
