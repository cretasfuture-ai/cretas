package com.cretas.aims.ai.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** Text-only pilot. Service identity is supplied by Java, never by the prompt. */
@Component
public class F006BossQuestionClient {
    private static final String ROUTE = "/api/llm/f006-boss-question-stream";
    private final String baseUrl;
    private final String secret;
    private final boolean enabled;
    private final OkHttpClient http;
    private final ObjectMapper mapper;

    public record Metadata(String provider, String model, boolean thinking, String correlationId) { }
    public record Usage(int inputTokens, int outputTokens, int totalTokens) { }
    public record Answer(String content, String provider, String model, String finishReason,
                         String correlationId, Usage usage) { }

    /** Per-request handle; cancellation before socket creation is retained. */
    public static final class Cancellation {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<Call> active = new AtomicReference<>();

        public void cancel() {
            cancelled.set(true);
            Call call = active.get();
            if (call != null) call.cancel();
        }

        private void attach(Call call) {
            active.set(call);
            if (cancelled.get()) call.cancel();
        }

        private void detach(Call call) {
            active.compareAndSet(call, null);
        }
    }

    public F006BossQuestionClient(
            @Qualifier("pythonAiBaseUrl") String baseUrl,
            @Qualifier("pythonAiInternalSecret") String secret,
            @Value("${cretas.f006.boss-mini.enabled:false}") boolean enabled,
            @Qualifier("aiServiceHttpClient") OkHttpClient http, ObjectMapper mapper) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.secret = secret;
        this.enabled = enabled;
        // A dedicated transport does not inherit interceptors that could log
        // internal credentials. It retains the injected client's socket/TLS
        // configuration, with hard deadlines longer than Python's 55s budget.
        OkHttpClient.Builder transport = http.newBuilder();
        transport.interceptors().clear();
        transport.networkInterceptors().clear();
        this.http = transport.connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS).callTimeout(70, TimeUnit.SECONDS)
                .followRedirects(false).followSslRedirects(false).build();
        this.mapper = mapper;
    }

    public boolean shouldRoute(String factoryId, Long userId, String role) {
        return enabled && "F006".equals(factoryId) && userId != null && userId > 0
                && "factory_super_admin".equals(role);
    }

    public Answer stream(String factoryId, Long userId, String role, String question,
                         boolean thinking, Consumer<Metadata> onMeta, Consumer<String> onToken)
            throws IOException {
        return stream(factoryId, userId, role, question, thinking, onMeta, onToken, new Cancellation());
    }

    public Answer stream(String factoryId, Long userId, String role, String question,
                         boolean thinking, Consumer<Metadata> onMeta, Consumer<String> onToken,
                         Cancellation cancellation) throws IOException {
        if (!shouldRoute(factoryId, userId, role)) {
            throw new IOException("当前账号不能使用本地 AI 通道");
        }
        if (secret == null || secret.isBlank()) {
            throw new IOException("本地 AI 服务认证配置缺失");
        }
        if (question == null || question.isBlank() || question.length() > 2000) {
            throw new IOException("问题长度应为 1 至 2000 字");
        }
        Request request = new Request.Builder().url(baseUrl + ROUTE)
                .header("X-Internal-Secret", secret)
                .header("X-Factory-Id", factoryId)
                .header("X-User-Id", userId.toString())
                .header("X-User-Role", role)
                .header("Accept", "text/event-stream")
                .post(RequestBody.create(mapper.writeValueAsString(Map.of(
                        "question", question, "enable_thinking", thinking)),
                        MediaType.get("application/json; charset=utf-8")))
                .build();
        Call call = http.newCall(request);
        cancellation.attach(call);
        try (Response response = call.execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("本地 AI 服务暂时不可用，请稍后重试");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    response.body().byteStream(), StandardCharsets.UTF_8))) {
                StringBuilder content = new StringBuilder();
                Metadata metadata = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 131072) throw invalidStream();
                    if (!line.startsWith("data:")) continue;
                    JsonNode event;
                    try {
                        event = mapper.readTree(line.substring(5).strip());
                    } catch (IOException e) {
                        throw invalidStream();
                    }
                    if (event == null || !event.isObject()) throw invalidStream();
                    switch (event.path("type").asText()) {
                        case "error" -> throw streamError(event.path("code").asText());
                        case "meta" -> {
                            if (metadata != null || !"mac-mini".equals(event.path("provider").asText())
                                    || !event.path("model").isTextual()
                                    || !event.path("thinking").isBoolean()
                                    || event.path("thinking").booleanValue() != thinking
                                    || !event.path("correlationId").asText().matches("[a-f0-9]{32}")) {
                                throw invalidStream();
                            }
                            metadata = new Metadata("mac-mini", event.path("model").asText(), thinking,
                                    event.path("correlationId").asText());
                            onMeta.accept(metadata);
                        }
                        case "delta" -> {
                            if (metadata == null || !event.path("content").isTextual()) throw invalidStream();
                            String delta = event.path("content").textValue();
                            content.append(delta);
                            if (content.length() > 12000) throw invalidStream();
                            onToken.accept(delta);
                        }
                        case "done" -> {
                            if (metadata == null || content.toString().isBlank()
                                    || !event.path("content").isTextual()
                                    || !content.toString().equals(event.path("content").textValue())
                                    || !metadata.provider().equals(event.path("provider").asText())
                                    || !metadata.model().equals(event.path("model").asText())
                                    || !metadata.correlationId().equals(event.path("correlationId").asText())
                                    || !event.path("usageAvailable").isBoolean()) throw invalidStream();
                            Usage usage = null;
                            if (event.path("usageAvailable").booleanValue()) {
                                JsonNode raw = event.path("usage");
                                int input = tokenCount(raw.path("inputTokens"));
                                int output = tokenCount(raw.path("outputTokens"));
                                int total = tokenCount(raw.path("totalTokens"));
                                if ((long) input + output != total) throw invalidStream();
                                usage = new Usage(input, output, total);
                            } else if (!event.path("usage").isNull()) throw invalidStream();
                            return new Answer(content.toString(), metadata.provider(), metadata.model(),
                                    event.path("finishReason").asText("stop"), metadata.correlationId(), usage);
                        }
                        default -> throw invalidStream();
                    }
                }
                throw invalidStream();
            }
        } finally {
            cancellation.detach(call);
        }
    }

    private static int tokenCount(JsonNode value) throws IOException {
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw invalidStream();
        }
        return value.intValue();
    }

    private static IOException invalidStream() {
        return new IOException("本地 AI 回答未完整完成，请重试");
    }

    private static IOException streamError(String code) {
        return new IOException(switch (code) {
            case "MINI_BUSY" -> "本地 AI 正忙，请稍后重试";
            case "MINI_TIMEOUT" -> "本地 AI 响应超时，请重试";
            default -> "本地 AI 未能完成回答，请稍后重试";
        });
    }
}
