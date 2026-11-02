package com.cretas.aims.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises actual HTTP transport; no external model or business store. */
class PythonLLMClientWarehouseScopeContractTest {
    private static final String FACTORY = "FOOD_0000_001";
    private static final String SECRET = "unit-only-internal-secret";
    private MockWebServer server;
    private PythonLLMClient client;
    private final AtomicInteger applicationCalls = new AtomicInteger();
    private final AtomicInteger networkCalls = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        OkHttpClient transport = new OkHttpClient.Builder()
                .addInterceptor(chain -> {
                    applicationCalls.incrementAndGet();
                    return chain.proceed(chain.request());
                })
                .addNetworkInterceptor(chain -> {
                    networkCalls.incrementAndGet();
                    return chain.proceed(chain.request());
                }).build();
        client = new PythonLLMClient(server.url("/").toString().replaceAll("/$", ""),
                transport, new ObjectMapper());
        ReflectionTestUtils.setField(client, "warehouseVisionInternalSecret", SECRET);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    void warehouseSendsConcreteFactoryAndInternalAuthenticationWithoutCallerInterceptors() throws Exception {
        enqueueSuccess();
        assertThat(recognize(FACTORY)).isEqualTo("warehouse-result");
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).isEqualTo("/api/llm/vision");
        assertThat(request.getHeader("X-Factory-Id")).isEqualTo(FACTORY);
        assertThat(request.getHeader("X-Internal-Secret")).isEqualTo(SECRET);
        assertThat(request.getHeader("X-User-Role")).isNull();
        assertThat(request.getHeader("Authorization")).isNull();
        assertThat(request.getBody().readUtf8()).contains("name=\"slot\"", "warehouse_vl",
                "name=\"image\"", "read warehouse");
        assertThat(applicationCalls.get()).isZero();
        assertThat(networkCalls.get()).isZero();
    }

    @Test
    void genericVisionRetainsItsExistingTransportAndDoesNotReceiveInternalCredentials() throws Exception {
        enqueueSuccess();
        assertThat(client.visionChat(new byte[] {1, 2}, "image/png", "other image"))
                .isEqualTo("warehouse-result");
        var request = server.takeRequest(1, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getHeader("X-Factory-Id")).isNull();
        assertThat(request.getHeader("X-Internal-Secret")).isNull();
        assertThat(request.getBody().readUtf8()).contains("\r\nvl\r\n");
        assertThat(applicationCalls.get()).isEqualTo(1);
        assertThat(networkCalls.get()).isEqualTo(1);
    }

    @Test
    void missingMalformedAndPlatformFactoriesAreRejectedBeforeHttp() {
        for (String factory : new String[] {null, "", " ", "PLATFORM", "platform", "OTHER/tenant",
                "FOOD\r\nX-Factory-Id:OTHER", "a".repeat(129)}) {
            assertThatThrownBy(() -> recognize(factory)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(server.getRequestCount()).isZero();
        assertThat(applicationCalls.get()).isZero();
        assertThat(networkCalls.get()).isZero();
    }

    @Test
    void absentInternalSecretFailsClosedBeforeHttp() {
        for (String secret : new String[] {null, "", " "}) {
            ReflectionTestUtils.setField(client, "warehouseVisionInternalSecret", secret);
            assertThatThrownBy(() -> recognize(FACTORY)).isInstanceOf(IllegalStateException.class);
        }
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void warehouseRedirectCannotForwardInternalCredentialsToAnotherDestination() throws Exception {
        try (MockWebServer destination = new MockWebServer()) {
            destination.start();
            destination.enqueue(new MockResponse().setBody("must not be reached"));
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", destination.url("/capture")));
            assertThatThrownBy(() -> recognize(FACTORY)).isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("302");
            assertThat(server.getRequestCount()).isEqualTo(1);
            assertThat(destination.takeRequest(150, TimeUnit.MILLISECONDS)).isNull();
            assertThat(destination.getRequestCount()).isZero();
        }
    }

    private String recognize(String factory) {
        return client.visionChatWarehouse(factory, new byte[] {1, 2}, "image/png", "read warehouse");
    }

    private void enqueueSuccess() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"model\":\"unit-model\",\"choices\":[{\"message\":{\"content\":\"warehouse-result\"}}]}"));
    }
}
