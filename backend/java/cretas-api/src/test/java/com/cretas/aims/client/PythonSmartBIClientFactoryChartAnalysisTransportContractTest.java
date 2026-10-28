package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.utils.CookieAuthHelper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PythonSmartBIClientFactoryChartAnalysisTransportContractTest {

    private MockWebServer server;

    @AfterEach
    void clearRequestContext() throws Exception {
        RequestContextHolder.resetRequestAttributes();
        if (server != null) server.shutdown();
    }

    @Test
    void actualHttpTransportForwardsIdsAndMatchingBearer() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(response());
        setRequest("F001", 42L, "Bearer header-token", null);
        PythonSmartBIClient client = client(server);

        client.callFactoryChartAnalysis("F001", "42", "operator", "corr-1", Map.of(
                "question", "成本趋势", "inspection_upload_id", 101, "cost_upload_id", 202));

        RecordedRequest outgoing = server.takeRequest();
        assertThat(outgoing.getPath()).isEqualTo("/api/smartbi/financial-dashboard/factory-analysis");
        assertThat(outgoing.getHeader("Authorization")).isEqualTo("Bearer header-token");
        JsonNode body = new ObjectMapper().readTree(outgoing.getBody().readUtf8());
        assertThat(body.get("question").asText()).isEqualTo("成本趋势");
        assertThat(body.get("inspection_upload_id").asInt()).isEqualTo(101);
        assertThat(body.get("cost_upload_id").asInt()).isEqualTo(202);
    }

    @Test
    void actualHttpTransportFallsBackToHttpOnlyAccessCookie() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(response());
        setRequest("F001", 42L, "Basic not-a-bearer", "cookie-token");
        PythonSmartBIClient client = client(server);

        client.callFactoryChartAnalysis("F001", "42", "operator", null,
                Map.of("question", "成本趋势", "inspection_upload_id", 101, "cost_upload_id", 202));

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer cookie-token");
    }

    @Test
    void middlewareSelectionIgnoresLowercaseOrLeadingSpaceBearerAndUsesCookie() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(response());
        setRequest("F001", 42L, " bearer wrong-token", "cookie-token");
        PythonSmartBIClient client = client(server);

        client.callFactoryChartAnalysis("F001", "42", "operator", null,
                Map.of("question", "成本趋势"));

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer cookie-token");
    }

    @Test
    void emptyBearerFallsBackToHttpOnlyAccessCookie() throws Exception {
        server = new MockWebServer();
        server.start();
        server.enqueue(response());
        setRequest("F001", 42L, "Bearer ", "cookie-token");
        PythonSmartBIClient client = client(server);

        client.callFactoryChartAnalysis("F001", "42", "operator", null,
                Map.of("question", "成本趋势"));

        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer cookie-token");
    }

    @Test
    void malformedSelectedHeaderAndCookieAreRejectedBeforeHttp() throws Exception {
        server = new MockWebServer();
        server.start();
        PythonSmartBIClient client = client(server);
        setRequest("F001", 42L, "Bearer bad\r\nvalue", null);
        assertThatThrownBy(() -> client.callFactoryChartAnalysis("F001", "42", "operator", null,
                Map.of("question", "成本趋势"))).isInstanceOf(java.io.IOException.class);
        assertThat(server.getRequestCount()).isZero();

        RequestContextHolder.resetRequestAttributes();
        setRequest("F001", 42L, null, "bad\nvalue");
        assertThatThrownBy(() -> client.callFactoryChartAnalysis("F001", "42", "operator", null,
                Map.of("question", "成本趋势"))).isInstanceOf(java.io.IOException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void mismatchedTrustedActorNeverReachesPython() throws Exception {
        server = new MockWebServer();
        server.start();
        setRequest("F001", 42L, "Bearer wrong-actor-token", null);
        PythonSmartBIClient client = client(server);

        assertThatThrownBy(() -> client.callFactoryChartAnalysis("F001", "43", "operator", null,
                Map.of("question", "成本趋势"))).isInstanceOf(java.io.IOException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void unknownBodyFieldIsBlockedBeforeEgress() throws Exception {
        server = new MockWebServer();
        server.start();
        setRequest("F001", 42L, "Bearer valid-token", null);
        PythonSmartBIClient client = client(server);

        assertThatThrownBy(() -> client.callFactoryChartAnalysis("F001", "42", "operator", null,
                Map.of("question", "成本趋势", "endpoint", "/arbitrary")))
                .isInstanceOf(java.io.IOException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    private PythonSmartBIClient client(MockWebServer destination) throws Exception {
        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        config.setUrl(destination.url("/").newBuilder().host("127.0.0.1").build().toString());
        config.setConnectTimeout(1000);
        config.setTimeout(3000);
        config.setMaxRetries(0);
        return new PythonSmartBIClient(config,
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(), new PythonServiceCircuitBreaker(), "transport-secret");
    }

    private static void setRequest(String factoryId, long userId, String authorization, String cookie) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        if (authorization != null) request.addHeader("Authorization", authorization);
        if (cookie != null) request.setCookies(new jakarta.servlet.http.Cookie(
                CookieAuthHelper.ACCESS_TOKEN_COOKIE, cookie));
        request.setAttribute(AuthenticatedActor.REQUEST_ATTRIBUTE,
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, userId, factoryId, "operator"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    private static MockResponse response() {
        return new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("{\"success\":true,\"data\":{}}");
    }
}
