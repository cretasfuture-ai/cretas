package com.cretas.aims.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.cretas.aims.config.PythonAiClientConfig;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class F006BossQuestionClientContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private MockWebServer server;
    private F006BossQuestionClient client;
    private static final String CORRELATION = "0123456789abcdef0123456789abcdef";

    @BeforeEach
    void setup() throws IOException {
        server = new MockWebServer();
        server.start();
        client = client(true, "test-service-secret-only");
    }

    private F006BossQuestionClient client(boolean enabled, String secret) {
        return new F006BossQuestionClient(server.url("/").toString(), secret, enabled,
                new OkHttpClient(), mapper);
    }

    @AfterEach
    void close() throws IOException { server.shutdown(); }

    private String event(Map<String, Object> data) throws IOException {
        return "data: " + mapper.writeValueAsString(data) + "\n\n";
    }

    private String meta() throws IOException {
        return event(Map.of("type", "meta", "provider", "mac-mini", "model", "qwen3.8:27b",
                "thinking", false, "correlationId", CORRELATION));
    }

    private String delta() throws IOException {
        return event(Map.of("type", "delta", "content", "检查冷链。"));
    }

    private Map<String, Object> done() {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("type", "done");
        data.put("content", "检查冷链。");
        data.put("provider", "mac-mini");
        data.put("model", "qwen3.8:27b");
        data.put("correlationId", CORRELATION);
        data.put("usageAvailable", true);
        data.put("usage", Map.of("inputTokens", 10, "outputTokens", 4, "totalTokens", 14));
        data.put("finishReason", "stop");
        return data;
    }

    private F006BossQuestionClient.Answer execute() throws IOException {
        return client.stream("F006", 7L, "factory_super_admin", "如何检查冷链？", false,
                metadata -> { }, text -> { });
    }

    @Test
    void forwardsTrustedPrincipalAndAcceptsRealTokensOnlyAfterTerminalFrame() throws Exception {
        server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(meta() + delta() + event(done())));
        List<String> tokens = new ArrayList<>();
        List<F006BossQuestionClient.Metadata> metadata = new ArrayList<>();
        var answer = client.stream("F006", 7L, "factory_super_admin", "如何检查冷链？", false,
                metadata::add, tokens::add);
        assertThat(tokens).containsExactly("检查冷链。");
        assertThat(answer.content()).isEqualTo("检查冷链。");
        assertThat(answer.usage().totalTokens()).isEqualTo(14);
        assertThat(metadata.getFirst().provider()).isEqualTo("mac-mini");
        var request = server.takeRequest();
        assertThat(request.getPath()).isEqualTo("/api/llm/f006-boss-question-stream");
        assertThat(request.getHeader("X-Factory-Id")).isEqualTo("F006");
        assertThat(request.getHeader("X-User-Id")).isEqualTo("7");
        assertThat(request.getHeader("X-User-Role")).isEqualTo("factory_super_admin");
        assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("test-service-secret-only");
        assertThat(mapper.readTree(request.getBody().readUtf8()).properties()).hasSize(2);
    }

    @Test
    void missingUsageIsNullRatherThanInventedZero() throws Exception {
        var done = done();
        done.put("usageAvailable", false);
        done.put("usage", null);
        server.enqueue(new MockResponse().setBody(meta() + delta() + event(done)));
        assertThat(execute().usage()).isNull();
    }

    @Test
    void defaultOffAndNonPilotPrincipalsNeverReachPython() {
        var disabled = client(false, "test-service-secret-only");
        assertThat(disabled.shouldRoute("F006", 7L, "factory_super_admin")).isFalse();
        assertThatThrownBy(() -> disabled.stream("F006", 7L, "factory_super_admin", "问题", false,
                m -> { }, t -> { })).isInstanceOf(IOException.class);
        assertThat(client.shouldRoute("F007", 7L, "factory_super_admin")).isFalse();
        assertThat(client.shouldRoute("F006", 7L, "finance_manager")).isFalse();
        assertThat(client.shouldRoute("F006", 7L, "platform_admin")).isFalse();
        assertThat(client.shouldRoute("F006", null, "factory_super_admin")).isFalse();
        assertThat(client.shouldRoute("F006", 0L, "factory_super_admin")).isFalse();
        assertThatThrownBy(() -> client.stream("F007", 7L, "factory_super_admin", "问题", false,
                m -> { }, t -> { })).isInstanceOf(IOException.class);
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void missingSecretFailsBeforeNetworkEvenWhenFeatureIsEnabled() {
        client = client(true, " ");
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class).hasMessageContaining("认证配置缺失");
        assertThat(server.getRequestCount()).isZero();
    }

    @Test
    void cancellationBeforeCallCreationPreventsAnyNetworkRequest() {
        var cancellation = new F006BossQuestionClient.Cancellation();
        cancellation.cancel();
        assertThatThrownBy(() -> client.stream("F006", 7L, "factory_super_admin", "如何检查冷链？",
                false, ignored -> { }, ignored -> { }, cancellation)).isInstanceOf(IOException.class);
        assertThat(server.getRequestCount()).isZero();
        cancellation.cancel(); // idempotent after transport exit.
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "data: {invalid}\n\n", "data: {\"type\":\"unknown\"}\n\n"})
    void malformedOrEmptyStreamNeverCompletes(String body) {
        server.enqueue(new MockResponse().setBody(body));
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class).hasMessageContaining("未完整完成");
    }

    @Test
    void truncatedStreamFailsAfterAnyVisibleTokens() throws Exception {
        server.enqueue(new MockResponse().setBody(meta() + delta()));
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class).hasMessageContaining("未完整完成");
    }

    @Test
    void upstreamErrorIsNotSwallowedOrConvertedToSuccess() throws Exception {
        server.enqueue(new MockResponse().setBody(meta() + delta()
                + event(Map.of("type", "error", "code", "MINI_TIMEOUT", "message", "private server detail"))));
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class)
                .hasMessage("本地 AI 响应超时，请重试");
    }

    @Test
    void incompleteFinishReasonAfterActualDeltaCannotReturnAnswer() throws Exception {
        Object[] invalidReasons = { null, 13, "length", "unexpected" };
        for (int index = 0; index <= invalidReasons.length; index++) {
            var terminal = done();
            if (index == 0) terminal.remove("finishReason");
            else terminal.put("finishReason", invalidReasons[index - 1]);
            server.enqueue(new MockResponse().setBody(meta() + delta() + event(terminal)));
            List<String> tokens = new ArrayList<>();
            assertThatThrownBy(() -> client.stream("F006", 7L, "factory_super_admin",
                    "如何检查冷链？", false, metadata -> { }, tokens::add))
                    .isInstanceOf(IOException.class).hasMessageContaining("未完整完成");
            assertThat(tokens).containsExactly("检查冷链。");
        }
    }

    @Test
    void terminalModelCorrelationContentAndTokenCountsMustMatch() throws Exception {
        for (String field : List.of("model", "correlationId", "content", "usage")) {
            var done = done();
            done.put(field, "usage".equals(field)
                    ? Map.of("inputTokens", 10, "outputTokens", 4, "totalTokens", 99) : "wrong");
            server.enqueue(new MockResponse().setBody(meta() + delta() + event(done)));
            assertThatThrownBy(this::execute).isInstanceOf(IOException.class).hasMessageContaining("未完整完成");
        }
    }

    @Test
    void serviceRedirectCannotForwardInternalCredentials() throws Exception {
        try (MockWebServer destination = new MockWebServer()) {
            destination.start();
            server.enqueue(new MockResponse().setResponseCode(307)
                    .setHeader("Location", destination.url("/capture")));
            assertThatThrownBy(this::execute).isInstanceOf(IOException.class);
            assertThat(destination.getRequestCount()).isZero();
        }
    }

    @Test
    void serviceAuthenticationErrorFailsWithoutExposingResponseBody() {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("private secret diagnostic"));
        assertThatThrownBy(this::execute).isInstanceOf(IOException.class)
                .hasMessage("本地 AI 服务暂时不可用，请稍后重试");
    }

    @Test
    void dedicatedTransportDoesNotInheritRequestLoggingInterceptors() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var shared = new OkHttpClient.Builder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return chain.proceed(chain.request());
        }).addNetworkInterceptor(chain -> {
            calls.incrementAndGet();
            return chain.proceed(chain.request());
        }).build();
        client = new F006BossQuestionClient(server.url("/").toString(), "unit-test-secret", true,
                shared, mapper);
        server.enqueue(new MockResponse().setBody(meta() + delta() + event(done())));
        assertThat(execute().content()).isEqualTo("检查冷链。");
        assertThat(calls.get()).isZero();
        assertThat(shared.interceptors()).hasSize(1);
        assertThat(shared.networkInterceptors()).hasSize(1);
    }

    @Configuration(proxyBeanMethods = false)
    static class WiringConfig {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper(); }
        @Bean(name = "aiServiceHttpClient") OkHttpClient aiServiceHttpClient() { return new OkHttpClient(); }
    }

    @Test
    void actualSpringQualifiersAndDefaultOffPropertyWireWithoutNetwork() {
        new ApplicationContextRunner().withUserConfiguration(PythonAiClientConfig.class, WiringConfig.class)
                .withBean(F006BossQuestionClient.class).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(F006BossQuestionClient.class);
                    assertThat(context.getBean(F006BossQuestionClient.class)
                            .shouldRoute("F006", 7L, "factory_super_admin")).isFalse();
                    assertThat(context.getBean("pythonAiInternalSecret", String.class)).isEmpty();
                });
        new ApplicationContextRunner().withUserConfiguration(PythonAiClientConfig.class, WiringConfig.class)
                .withBean(F006BossQuestionClient.class).withPropertyValues(
                        "cretas.f006.boss-mini.enabled=true", "cretas.python.internal-secret=unit-test-secret")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(F006BossQuestionClient.class);
                    assertThat(context.getBean(F006BossQuestionClient.class)
                            .shouldRoute("F006", 7L, "factory_super_admin")).isTrue();
                });
        assertThat(server.getRequestCount()).isZero();
    }
}
