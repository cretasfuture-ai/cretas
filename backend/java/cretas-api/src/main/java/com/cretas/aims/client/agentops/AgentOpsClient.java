package com.cretas.aims.client.agentops;

import com.cretas.aims.dto.agentops.AgentOpsCreateEvalSetRequest;
import com.cretas.aims.dto.agentops.AgentOpsImportRuntimeCorpusRequest;
import com.cretas.aims.dto.agentops.AgentOpsRerunExperimentRequest;
import com.cretas.aims.dto.agentops.AgentOpsRunExperimentRequest;
import com.cretas.aims.dto.agentops.AgentOpsRunRuntimeShadowRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.ConnectionPool;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Exact-authority internal client for the tenant-bound Python AgentOps API. */
@Component
public class AgentOpsClient {
    public static final String INTERNAL_PATH = "/api/internal/smartbi/agent/runs/ops";
    private static final long MAX_PAYLOAD_BYTES = 4L * 1024L * 1024L;
    private static final MediaType JSON = Objects.requireNonNull(MediaType.parse("application/json; charset=utf-8"));
    private static final Pattern SENSITIVE_RESPONSE_KEY = Pattern.compile(
            "(?:raw[_-]?(?:prompt|question|request)|secret|token|password|authorization|cookie|api[_-]?key|credential|member[_-]?id|review[_-]?text)",
            Pattern.CASE_INSENSITIVE);
    private static final Set<String> SAFE_DIGEST_KEYS = Set.of(
            "promptSnapshotDigest", "modelSnapshotDigest", "toolSnapshotDigest");
    /**
     * Controlled upstream 4xx rejections that are safe to show callers, with the Chinese text we show.
     * Anything not listed stays an opaque upstream error (arbitrary upstream text never leaks).
     */
    public static final Map<String, String> CLIENT_ERROR_MESSAGES = Map.ofEntries(
            Map.entry("UNSUPPORTED_EXPECTED_ROUTE", "评测用例的期望路由与当前业态不匹配（工厂只能用工厂意图，餐饮只能用餐饮路由）"),
            Map.entry("UNSUPPORTED_BUSINESS_DOMAIN", "当前业态不支持 AgentOps"),
            Map.entry("RUNTIME_SHADOW_RESTAURANT_ONLY", "运行时影子评测只支持餐饮租户"),
            Map.entry("INVALID_EVAL_CASE_SCHEMA", "评测用例字段不符合要求"),
            Map.entry("DUPLICATE_CASE_ID", "评测用例编号重复"),
            Map.entry("EVAL_CASE_COUNT_OUT_OF_BOUNDS", "评测用例数量超出限制（1 至 100 条）"),
            Map.entry("INVALID_TOOL_NAME", "工具名称格式不正确"),
            Map.entry("INVALID_NUMERIC_TRUTH_REFS", "数值真值引用格式不正确"),
            Map.entry("ACTUAL_CASE_SET_MISMATCH", "实际结果的用例集合与评测集不一致"),
            Map.entry("REQUIRED_SNAPSHOT_DIGEST_MISSING", "缺少必需的配置快照摘要"),
            Map.entry("INVALID_FACTORY_INTENT", "工厂意图编码格式不正确"),
            Map.entry("INVALID_TRACE_OUTCOME", "轨迹结果状态不在允许范围内"),
            Map.entry("INVALID_TRACE_STEPS", "轨迹步骤数量或格式不正确"),
            Map.entry("EVAL_SET_NOT_FOUND", "未找到该评测集"),
            Map.entry("EXPERIMENT_NOT_FOUND", "未找到该评测实验"),
            Map.entry("RUN_NOT_FOUND", "未找到该运行轨迹"));
    private static final Set<String> SAFE_ERROR_CODES;
    static {
        Set<String> codes = new java.util.HashSet<>(Set.of(
                "EVAL_SET_VERSION_EXISTS", "IDEMPOTENCY_KEY_REUSED", "EVALUATOR_BUILD_UNAVAILABLE",
                "AGENT_OPS_RUNTIME_SHADOW_DISABLED", "AGENT_OPS_RUNTIME_SHADOW_CANARY_DENIED",
                "RUNTIME_SHADOW_CASE_TIMEOUT"));
        codes.addAll(CLIENT_ERROR_MESSAGES.keySet());
        SAFE_ERROR_CODES = java.util.Collections.unmodifiableSet(codes);
    }

    /** Must stay below uvicorn's --timeout-keep-alive (5s). */
    static final long IDLE_KEEP_ALIVE_SECONDS = 4;

    private final OkHttpClient client;
    private final ObjectMapper objectMapper;
    private final HttpUrl baseUrl;
    private final String internalSecret;

    public AgentOpsClient(
            @Qualifier("aiServiceHttpClient") OkHttpClient sharedClient,
            @Qualifier("pythonAiBaseUrl") String pythonBaseUrl,
            @Qualifier("pythonAiInternalSecret") String internalSecret,
            ObjectMapper objectMapper) {
        this.client = Objects.requireNonNull(sharedClient).newBuilder()
                // uvicorn closes idle keep-alive connections after ~5s; OkHttp reusing one fails with
                // "unexpected end of stream" (same cause as the SmartBI client, #985/#986). Drop ours first.
                .connectionPool(new ConnectionPool(4, IDLE_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS))
                .followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).build();
        this.objectMapper = Objects.requireNonNull(objectMapper);
        this.baseUrl = exactBase(pythonBaseUrl);
        this.internalSecret = internalSecret == null ? "" : internalSecret.trim();
    }

    public boolean isConfigured() { return !internalSecret.isBlank(); }

    public JsonNode createEvalSet(AgentOpsCreateEvalSetRequest body, TrustedContext context) throws IOException {
        return post(url("eval-sets"), body, context);
    }

    public JsonNode listEvalSets(TrustedContext context) throws IOException {
        return get(url("eval-sets"), context);
    }

    public JsonNode importRuntimeCorpus(AgentOpsImportRuntimeCorpusRequest body,
                                        TrustedContext context) throws IOException {
        return post(url("eval-sets", "import-runtime-corpus"), body, context);
    }

    public JsonNode getEvalSet(UUID id, int offset, int limit, TrustedContext context) throws IOException {
        return get(pageUrl(url("eval-sets", id.toString()), offset, limit), context);
    }

    public JsonNode runExperiment(AgentOpsRunExperimentRequest body, TrustedContext context) throws IOException {
        return post(url("experiments"), body, context);
    }

    public JsonNode runRuntimeShadow(AgentOpsRunRuntimeShadowRequest body,
                                     TrustedContext context) throws IOException {
        return post(url("experiments", "runtime-shadow"), body, context);
    }

    public JsonNode rerunExperiment(UUID id, AgentOpsRerunExperimentRequest body,
                                    TrustedContext context) throws IOException {
        return post(url("experiments", id.toString(), "rerun"), body, context);
    }

    public JsonNode listExperiments(TrustedContext context) throws IOException {
        return get(url("experiments"), context);
    }

    public JsonNode getExperiment(UUID id, int offset, int limit, TrustedContext context) throws IOException {
        return get(pageUrl(url("experiments", id.toString()), offset, limit), context);
    }

    public JsonNode compareExperiments(UUID id, UUID baselineId, TrustedContext context) throws IOException {
        HttpUrl target = url("experiments", id.toString(), "compare").newBuilder()
                .addQueryParameter("baselineId", baselineId.toString()).build();
        return get(target, context);
    }

    /** Records one factory-domain agent run trace; returns the stored (validated) trace. */
    public JsonNode recordFactoryTrace(Map<String, Object> trace, TrustedContext context) throws IOException {
        try {
            return post(url("traces", "factory"), trace, context);
        } catch (UpstreamException rejected) {
            throw rejected; // a real HTTP answer (4xx/5xx) is never replayed
        } catch (IOException transport) {
            // A pooled connection can still be closed by the peer between our idle check and the send.
            // Ingest is idempotent on runId (same payload returns the stored trace), so one replay on
            // a fresh connection is safe.
            client.connectionPool().evictAll();
            return post(url("traces", "factory"), trace, context);
        }
    }

    public JsonNode getTrace(UUID runId, long afterSequence, int limit, TrustedContext context) throws IOException {
        if (afterSequence < 0) throw new IllegalArgumentException("cursor out of bounds");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit out of bounds");
        HttpUrl target = url("traces", runId.toString()).newBuilder()
                .addQueryParameter("afterSequence", Long.toString(afterSequence))
                .addQueryParameter("limit", Integer.toString(limit)).build();
        return get(target, context);
    }

    private JsonNode get(HttpUrl url, TrustedContext context) throws IOException {
        return exchange(new Request.Builder().url(url).get(), context);
    }

    private JsonNode post(HttpUrl url, Object body, TrustedContext context) throws IOException {
        byte[] encoded = body == null ? new byte[0] : objectMapper.writeValueAsBytes(body);
        if (encoded.length > MAX_PAYLOAD_BYTES) throw new IOException("AgentOps request too large");
        return exchange(new Request.Builder().url(url)
                .post(RequestBody.create(encoded, JSON)), context);
    }

    private JsonNode exchange(Request.Builder builder, TrustedContext context) throws IOException {
        requireConfigured();
        context.validate();
        Request request = builder
                .header("X-Internal-Secret", internalSecret)
                .header("X-Factory-Id", context.factoryId())
                .header("X-User-Id", context.userId())
                .header("X-User-Role", context.role())
                .header("X-Business-Type", context.businessType())
                .header("X-Correlation-ID", context.correlationId())
                .header("Accept", "application/json")
                .build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) throw upstreamException(response);
            String type = response.header("Content-Type", "").toLowerCase();
            if (!type.startsWith("application/json")) throw new IOException("AgentOps returned non-JSON");
            JsonNode body = readBounded(response.body());
            if (body == null || !body.isObject()) throw new IOException("AgentOps returned invalid JSON");
            return scrubSensitiveFields(body);
        }
    }

    private UpstreamException upstreamException(Response response) throws IOException {
        String type = response.header("Content-Type", "").toLowerCase();
        if (!type.startsWith("application/json")) return new UpstreamException(response.code(), null);
        JsonNode body = readBounded(response.body());
        String detailCode = body != null && body.path("detail").isTextual()
                ? body.path("detail").asText() : null;
        return new UpstreamException(response.code(),
                detailCode != null && SAFE_ERROR_CODES.contains(detailCode) ? detailCode : null);
    }

    private JsonNode readBounded(ResponseBody body) throws IOException {
        if (body == null || body.contentLength() > MAX_PAYLOAD_BYTES) throw new IOException("AgentOps response too large");
        BufferedSource source = body.source();
        source.request(MAX_PAYLOAD_BYTES + 1);
        if (source.buffer().size() > MAX_PAYLOAD_BYTES) throw new IOException("AgentOps response too large");
        return objectMapper.readTree(source.readByteArray());
    }

    private JsonNode scrubSensitiveFields(JsonNode body) {
        JsonNode copy = body.deepCopy();
        scrub(copy);
        return copy;
    }

    private static void scrub(JsonNode node) {
        if (node instanceof ObjectNode object) {
            List<String> fields = new ArrayList<>();
            object.fieldNames().forEachRemaining(fields::add);
            for (String field : fields) {
                if (!SAFE_DIGEST_KEYS.contains(field) && SENSITIVE_RESPONSE_KEY.matcher(field).find()) {
                    object.remove(field);
                } else {
                    scrub(object.get(field));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(AgentOpsClient::scrub);
        }
    }

    private HttpUrl url(String... segments) {
        HttpUrl.Builder builder = baseUrl.newBuilder();
        for (String segment : segments) builder.addPathSegment(segment);
        return builder.build();
    }

    private HttpUrl pageUrl(HttpUrl target, int offset, int limit) {
        if (offset < 0 || limit < 1 || limit > 50) {
            throw new IllegalArgumentException("page out of bounds");
        }
        return target.newBuilder()
                .addQueryParameter("offset", Integer.toString(offset))
                .addQueryParameter("limit", Integer.toString(limit))
                .build();
    }

    private void requireConfigured() {
        if (!isConfigured()) throw new IllegalStateException("AgentOps internal secret is not configured");
    }

    private static HttpUrl exactBase(String raw) {
        HttpUrl parsed = HttpUrl.get(raw);
        if (!(parsed.scheme().equals("http") || parsed.scheme().equals("https"))
                || !parsed.username().isEmpty() || !parsed.password().isEmpty()
                || !parsed.encodedPath().equals("/") || parsed.query() != null || parsed.fragment() != null) {
            throw new IllegalArgumentException("Python base URL must contain only scheme, host and port");
        }
        return parsed.newBuilder().encodedPath(INTERNAL_PATH + "/").build();
    }

    public record TrustedContext(String factoryId, String userId, String role, String correlationId,
                                 String businessType) {
        /** Restaurant stays the default so existing callers keep their behaviour. */
        public TrustedContext(String factoryId, String userId, String role, String correlationId) {
            this(factoryId, userId, role, correlationId, "RESTAURANT");
        }

        public void validate() {
            if (!safe(factoryId) || !safe(userId) || !safe(role) || !safe(correlationId)
                    || !("RESTAURANT".equals(businessType) || "FACTORY".equals(businessType))) {
                throw new SecurityException("Invalid trusted AgentOps context");
            }
        }
        private static boolean safe(String value) {
            return value != null && value.matches("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
        }
    }

    public static class UpstreamException extends IOException {
        private final int statusCode;
        private final String detailCode;
        public UpstreamException(int statusCode) { this(statusCode, null); }
        public UpstreamException(int statusCode, String detailCode) {
            super("AgentOps returned HTTP " + statusCode);
            this.statusCode = statusCode;
            this.detailCode = detailCode;
        }
        public int getStatusCode() { return statusCode; }
        public String getDetailCode() { return detailCode; }
    }
}
