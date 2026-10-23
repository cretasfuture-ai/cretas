package com.cretas.aims.client;

import com.cretas.aims.config.efficiency.PythonEfficiencyConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Python /api/efficiency/* 自 2026-10-22 起仅允许内部调用 (数据不分工厂, 之前匿名可读)。
 * Java 侧唯一调用方必须在每个请求上带 X-Internal-Secret, 否则发布后效率识别整体失效。
 * 真实 HTTP (MockWebServer) 断言请求头确实发出去了。
 */
class PythonEfficiencyClientInternalSecretContractTest {

    MockWebServer server;

    @BeforeEach
    void start() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void stop() throws IOException {
        server.shutdown();
    }

    @Test
    void everyEfficiencyCallCarriesTheInternalSecret() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\"}"));
        PythonEfficiencyConfig config = new PythonEfficiencyConfig();
        config.setEnabled(true);
        config.setUrl(server.url("/").toString());
        config.setHealthCheckInterval(0);
        PythonEfficiencyClient client = new PythonEfficiencyClient(config, new OkHttpClient(), new ObjectMapper(),
                "s3cret-from-config");

        assertThat(client.isAvailable()).isTrue();
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getPath()).startsWith("/api/efficiency");
        assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("s3cret-from-config");
    }
}
