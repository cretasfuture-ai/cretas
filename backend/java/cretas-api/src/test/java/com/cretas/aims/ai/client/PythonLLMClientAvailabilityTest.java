package com.cretas.aims.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code PythonLLMClient#isAvailable()} 的三态语义钉子。
 *
 * <h2>它守的是什么</h2>
 * 旧实现只判 HTTP 2xx —— 那证明「进程活着」, <b>不证明背后有可用的 LLM 供应商</b>。
 * 2026-08-28 CI 自建 runner (canada-mac-mini-2) 实测: 服务在、三家 provider key 全空
 * ⇒ {@code /health} 200 ⇒ isAvailable() 返回 true ⇒ 联网用例不跳过 ⇒ 每次调用拿到
 * {@code All providers exhausted: empty_api_key} ⇒ 整道 Java Gate 对所有 PR 变红。
 *
 * <h2>🔴 最要紧的是 fail-open 那几条</h2>
 * 本方法是<b>生产代码路径</b>上的守卫 ({@code DashScopeVisionClient} 等也在用)。
 * 把「不知道」判成「不可用」会在一次偶发解析异常里<b>整体关掉线上 AI 功能</b>,
 * 滚动发布期间「Java 新 / Python 旧」也走这一条。
 * ⇒ 下面 fail-open 的三条用例比那条 false 用例<b>更重要</b>, ⛔ 不许为了「更严格」删掉。
 */
@DisplayName("PythonLLMClient.isAvailable 三态: 有 key / 没 key / 不知道(fail-open)")
class PythonLLMClientAvailabilityTest {

    private MockWebServer server;
    private PythonLLMClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        OkHttpClient http = new OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build();
        // 构造签名是 (baseUrl, httpClient, objectMapper)
        client = new PythonLLMClient(server.url("/").toString().replaceAll("/$", ""),
                http, new ObjectMapper());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    private void health(String body) {
        server.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body));
    }

    // ── 唯一一条「判不可用」的路 ─────────────────────────────────────────

    @Test
    @DisplayName("llmProvidersReady=false → 不可用 (这就是 CI runner 上那一格)")
    void falseMeansUnavailable() {
        health("{\"status\":\"healthy\",\"llmProvidersReady\":false}");
        assertThat(client.isAvailable())
                .as("三家 provider key 全空时必须判不可用, 否则联网用例不会跳过")
                .isFalse();
    }

    // ── fail-open: 下面每一条都必须是 true ──────────────────────────────

    @Test
    @DisplayName("llmProvidersReady=true → 可用")
    void trueMeansAvailable() {
        health("{\"status\":\"healthy\",\"llmProvidersReady\":true}");
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("fail-open: 字段缺失(旧版 Python) → 可用, 行为与改动前一致")
    void missingFieldFailsOpen() {
        health("{\"status\":\"healthy\",\"service\":\"python-services\"}");
        assertThat(client.isAvailable())
                .as("滚动发布期 Java 新/Python 旧走这一条 —— 判成不可用会关掉线上 AI")
                .isTrue();
    }

    @Test
    @DisplayName("fail-open: 字段为 null(判不出来) → 可用")
    void nullFieldFailsOpen() {
        health("{\"status\":\"healthy\",\"llmProvidersReady\":null}");
        assertThat(client.isAvailable()).isTrue();
    }

    @Test
    @DisplayName("fail-open: 响应体不是 JSON → 可用 (⛔ 不许因为解析失败就关掉 AI)")
    void unparseableBodyFailsOpen() {
        health("not json at all");
        assertThat(client.isAvailable()).isTrue();
    }

    // ── 服务本身不健康仍然是不可用 (旧行为, 不能被本次改动弄丢) ──────────

    @Test
    @DisplayName("HTTP 503 → 不可用 (旧行为保持)")
    void nonSuccessfulStaysUnavailable() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody("{}"));
        assertThat(client.isAvailable()).isFalse();
    }
}
