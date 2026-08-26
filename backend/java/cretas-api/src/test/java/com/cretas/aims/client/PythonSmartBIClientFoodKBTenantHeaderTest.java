package com.cretas.aims.client;

import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * food-kb feedback 的三个 Java→Python 调用必须带 X-Factory-Id (P0, 2026-08-25).
 *
 * <p>Python 侧 {@code food_kb/api/feedback.py} 的四个端点已改成从
 * {@code request.state.factory_id} 取租户并 fail-closed 401。Java→Python 走
 * internal-secret 分支, 那条分支的租户**只来自 X-Factory-Id 头**
 * (auth_middleware.py: {@code scope["state"]["factory_id"] = internal_factory}).
 *
 * <p>此前这三个方法一个头都不带 —— 结果是 {@code /feedback/stats} 拿到的是
 * **全平台**统计 (含所有租户 rating&lt;=2 的用户原话与 AI 回答), 写进去的行
 * 也没有租户归属。
 *
 * <p>⚠️ 这条测试守的是「头发出去了」这个行为。它不能替代 Python 侧那套跨租户
 * 断言 ({@code tests/test_food_kb_feedback_tenant_isolation.py}) —— 两边各守
 * 一半, 缺任一半这条链就断。
 */
class PythonSmartBIClientFoodKBTenantHeaderTest {

    private static final String SECRET = "food-kb-internal-secret";
    private static final String FACTORY = "F_TENANT_A";

    private final List<MockWebServer> servers = new ArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        for (MockWebServer server : servers) {
            server.shutdown();
        }
    }

    @Test
    void submitFeedbackForwardsTenantHeader() throws Exception {
        MockWebServer server = startServer();
        server.enqueue(json("{\"success\":true,\"id\":1}"));
        PythonSmartBIClient client = newClient(server);

        Map<String, Object> result = client.submitFoodKBFeedback(
                FACTORY, Map.of("query", "q", "rating", 5));

        assertThat(result).containsEntry("success", true);
        RecordedRequest recorded = server.takeRequest();
        assertThat(recorded.getHeader("X-Factory-Id")).isEqualTo(FACTORY);
        assertThat(recorded.getHeader("X-Internal-Secret")).isEqualTo(SECRET);
    }

    @Test
    void logQueryForwardsTenantHeader() throws Exception {
        MockWebServer server = startServer();
        server.enqueue(json("{\"success\":true}"));
        PythonSmartBIClient client = newClient(server);

        client.logFoodKBQuery(FACTORY, Map.of("query", "q"));

        assertThat(server.takeRequest().getHeader("X-Factory-Id")).isEqualTo(FACTORY);
    }

    @Test
    void statsForwardsTenantHeader() throws Exception {
        MockWebServer server = startServer();
        server.enqueue(json("{\"success\":true,\"total\":0}"));
        PythonSmartBIClient client = newClient(server);

        Map<String, Object> result = client.getFoodKBFeedbackStats(FACTORY);

        assertThat(result).containsEntry("success", true);
        assertThat(server.takeRequest().getHeader("X-Factory-Id")).isEqualTo(FACTORY);
    }

    /**
     * fail-closed: 拿不到租户就**不发这个请求**。
     *
     * <p>⛔ 不许退化成「不带头照发」—— 那正是缺陷本身 (Python 那边会 401,
     * 但在改 Python 之前它返回的是全平台数据)。
     */
    @Test
    void missingTenantNeverReachesTheNetwork() throws Exception {
        MockWebServer server = startServer();
        PythonSmartBIClient client = newClient(server);

        assertThat(client.submitFoodKBFeedback(null, Map.of("query", "q")))
                .containsEntry("success", false);
        assertThat(client.logFoodKBQuery(null, Map.of("query", "q")))
                .containsEntry("success", false);
        assertThat(client.getFoodKBFeedbackStats(null))
                .containsEntry("success", false);

        assertThat(server.getRequestCount()).isZero();
    }

    // ---------------------------------------------------------------

    private MockWebServer startServer() throws IOException {
        MockWebServer server = new MockWebServer();
        server.start();
        servers.add(server);
        return server;
    }

    private static MockResponse json(String body) {
        return new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(body);
    }

    private PythonSmartBIClient newClient(MockWebServer server) throws Exception {
        PythonSmartBIConfig config = new PythonSmartBIConfig();
        config.setEnabled(true);
        config.setUrl(server.url("/").toString());
        config.setConnectTimeout(1000);
        config.setTimeout(3000);
        config.setMaxRetries(0);
        return new PythonSmartBIClient(
                config,
                new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(5)).build(),
                new ObjectMapper(),
                breaker(),
                SECRET);
    }

    private static PythonServiceCircuitBreaker breaker() throws Exception {
        PythonServiceCircuitBreaker breaker = new PythonServiceCircuitBreaker();
        setBreakerField(breaker, "failureThreshold", 100);
        setBreakerField(breaker, "openDurationMs", 100L);
        return breaker;
    }

    private static void setBreakerField(PythonServiceCircuitBreaker breaker,
                                        String name, Object value) throws Exception {
        Field field = PythonServiceCircuitBreaker.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(breaker, value);
    }
}
