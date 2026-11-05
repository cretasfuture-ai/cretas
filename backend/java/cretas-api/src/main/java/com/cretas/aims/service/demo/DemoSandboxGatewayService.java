package com.cretas.aims.service.demo;

import com.cretas.aims.entity.User;
import com.cretas.aims.exception.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Authenticated gateway to the isolated F006 demo sandbox manager. */
@Service
public class DemoSandboxGatewayService {

    public static final String FACTORY_ID = "F006";
    public static final String REQUIRED_ROLE = "factory_super_admin";
    public static final long DEMO_ACTOR_ID = 1309L;
    public static final String DEMO_ACTOR_USERNAME = "f006_admin";
    public static final String CONTROL_KEY_HEADER = "X-Sandbox-Control-Key";
    public static final String ACTOR_HEADER = "X-Sandbox-Actor-Id";
    public static final int MAX_REQUEST_BODY_BYTES = 16 * 1024 * 1024;
    private static final int MIN_CONTROL_KEY_LENGTH = 32;

    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;
    private final boolean testManagerEndpoint;

    @Value("${cretas.demo.sandbox.enabled:false}")
    private boolean enabled;

    @Value("${cretas.demo.sandbox.manager-base-url:http://127.0.0.1:10041}")
    private String managerBaseUrl;

    @Value("${cretas.demo.sandbox.control-key:}")
    private String controlKey;

    @Autowired
    public DemoSandboxGatewayService(ObjectMapper objectMapper) {
        this(objectMapper, new OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(Duration.ofSeconds(2))
                .writeTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(30))
                .callTimeout(Duration.ofSeconds(35))
                .build(), false);
    }

    DemoSandboxGatewayService(ObjectMapper objectMapper, OkHttpClient httpClient) {
        this(objectMapper, httpClient, false);
    }

    DemoSandboxGatewayService(ObjectMapper objectMapper,
                              OkHttpClient httpClient,
                              boolean testManagerEndpoint) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.testManagerEndpoint = testManagerEndpoint;
    }

    public Map<String, Object> create(User actor) {
        assertCreateActor(actor);
        return managerJson("POST", "/sessions", actor, null, null, null, null);
    }

    public Map<String, Object> get(User actor, String sessionId) {
        assertBoundActor(actor);
        return managerJson("GET", "/sessions/" + checkedSessionId(sessionId), actor,
                null, null, null, null);
    }

    public Map<String, Object> end(User actor, String sessionId) {
        assertBoundActor(actor);
        return managerJson("DELETE", "/sessions/" + checkedSessionId(sessionId), actor,
                null, null, null, null);
    }

    /** Read the actor-bound active session; an empty data envelope means none. */
    public Optional<Map<String, Object>> activeSession(User actor) {
        if (actor == null || !FACTORY_ID.equals(actor.getFactoryId())) {
            throw new BusinessException(403, "演示沙盒仅限 F006 工厂")
                    .withCode("DEMO_SANDBOX_FACTORY_REQUIRED");
        }
        // Existing sessions remain fenced even if the actor's mutable role changes.
        if (actor.getId() != DEMO_ACTOR_ID) {
            return Optional.empty();
        }
        if (!enabled) return Optional.empty(); // Disabled nodes expose a clean null active-session state.
        assertEnabledConfiguration();
        Map<String, Object> data = managerJson("GET", "/actors/" + actor.getId() + "/session",
                actor, null, null, null, null);
        if (data.isEmpty() || "ENDED".equalsIgnoreCase(String.valueOf(data.get("status")))) {
            return Optional.empty();
        }
        return Optional.of(data);
    }

    public ProxyResult proxy(User actor,
                             String sessionId,
                             String rawTargetPath,
                             String query,
                             String method,
                             String idempotencyKey,
                             String contentType,
                             byte[] body) {
        assertCreateActor(actor);
        String targetPath = validateTargetPath(rawTargetPath);
        if (body != null && body.length > MAX_REQUEST_BODY_BYTES) {
            throw new BusinessException(413, "演示沙盒请求体不能超过 16 MiB")
                    .withCode("DEMO_SANDBOX_REQUEST_TOO_LARGE");
        }
        String path = "/sessions/" + checkedSessionId(sessionId) + "/proxy" + targetPath;
        String url = managerUrl(path, query);
        Request.Builder request = new Request.Builder().url(url).method(
                method == null || method.isBlank() ? "GET" : method.toUpperCase(),
                requestBody(method, body, contentType));
        addControlHeaders(request, actor);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (contentType != null && !contentType.isBlank()) {
            request.header("Content-Type", contentType);
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            byte[] responseBody = response.body() == null ? new byte[0] : response.body().bytes();
            return new ProxyResult(response.code(), response.header("Content-Type"), responseBody);
        } catch (IOException e) {
            throw managerUnavailable(e);
        }
    }

    private Map<String, Object> managerJson(String method,
                                             String path,
                                             User actor,
                                             String query,
                                             String idempotencyKey,
                                             String contentType,
                                             byte[] body) {
        String url = managerUrl(path, query);
        Request.Builder request = new Request.Builder().url(url).method(method,
                requestBody(method, body, contentType));
        addControlHeaders(request, actor);
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        if (contentType != null && !contentType.isBlank()) {
            request.header("Content-Type", contentType);
        }
        try (Response response = httpClient.newCall(request.build()).execute()) {
            byte[] bytes = response.body() == null ? new byte[0] : response.body().bytes();
            if (!response.isSuccessful()) {
                throw new BusinessException(response.code(), "演示沙盒管理器拒绝请求")
                        .withCode("DEMO_SANDBOX_MANAGER_HTTP_ERROR");
            }
            return parseManagerData(bytes);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw managerUnavailable(e);
        }
    }

    private Map<String, Object> parseManagerData(byte[] body) {
        if (body == null || body.length == 0) {
            throw new BusinessException(502, "演示沙盒管理器不可用")
                    .withCode("DEMO_SANDBOX_MANAGER_UNAVAILABLE");
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            JsonNode data = root == null ? null : root.get("data");
            if (root == null || !root.path("success").asBoolean(false)
                    || (data != null && !data.isNull() && !data.isObject())) {
                throw new BusinessException(502, "演示沙盒管理器返回了无效响应")
                        .withCode("DEMO_SANDBOX_MANAGER_RESPONSE_INVALID");
            }
            if (data == null || data.isNull()) return Map.of();
            @SuppressWarnings("unchecked")
            Map<String, Object> result = objectMapper.convertValue(data, LinkedHashMap.class);
            return result;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException(502, "演示沙盒管理器返回了无效响应", e)
                    .withCode("DEMO_SANDBOX_MANAGER_RESPONSE_INVALID");
        }
    }

    @PostConstruct
    void validateConfiguration() {
        if (enabled) {
            assertEnabledConfiguration();
            validateManagerBaseUrl();
        }
    }

    private void assertCreateActor(User actor) {
        assertBoundActor(actor);
        if (!REQUIRED_ROLE.equals(actor.getRoleCode())) {
            throw new BusinessException(403, "演示沙盒仅限工厂超级管理员")
                    .withCode("DEMO_SANDBOX_ROLE_REQUIRED");
        }
        if (!DEMO_ACTOR_USERNAME.equals(actor.getUsername())) {
            throw new BusinessException(403, "演示沙盒仅限 f006_admin 演示账号")
                    .withCode("DEMO_SANDBOX_ACTOR_REQUIRED");
        }
    }

    private void assertBoundActor(User actor) {
        if (actor == null || !FACTORY_ID.equals(actor.getFactoryId())) {
            throw new BusinessException(403, "演示沙盒仅限 F006 工厂")
                    .withCode("DEMO_SANDBOX_FACTORY_REQUIRED");
        }
        if (actor.getId() != DEMO_ACTOR_ID) {
            throw new BusinessException(403, "演示沙盒仅限 f006_admin 演示账号")
                    .withCode("DEMO_SANDBOX_ACTOR_REQUIRED");
        }
        assertEnabledConfiguration();
    }

    private void assertEnabledConfiguration() {
        if (!enabled || controlKey == null || controlKey.length() < MIN_CONTROL_KEY_LENGTH) {
            throw new BusinessException(503, "演示沙盒当前未启用")
                    .withCode("DEMO_SANDBOX_DISABLED");
        }
    }

    private void addControlHeaders(Request.Builder request, User actor) {
        request.header(CONTROL_KEY_HEADER, controlKey);
        request.header(ACTOR_HEADER, String.valueOf(actor.getId()));
    }

    private RequestBody requestBody(String method, byte[] body, String contentType) {
        String verb = method == null ? "GET" : method.toUpperCase();
        if ((body == null || body.length == 0)
                && ("GET".equals(verb) || "HEAD".equals(verb) || "DELETE".equals(verb))) {
            return null;
        }
        MediaType mediaType = contentType == null || contentType.isBlank()
                ? null : MediaType.parse(contentType);
        return RequestBody.create(body == null ? new byte[0] : body, mediaType);
    }

    private String managerUrl(String path, String query) {
        validateManagerBaseUrl();
        String baseText = managerBaseUrl.endsWith("/")
                ? managerBaseUrl.substring(0, managerBaseUrl.length() - 1) : managerBaseUrl;
        return baseText + path + (query == null || query.isBlank() ? "" : "?" + query);
    }

    private void validateManagerBaseUrl() {
        URI base;
        try {
            base = URI.create(managerBaseUrl);
        } catch (Exception e) {
            throw new BusinessException(503, "演示沙盒管理器地址无效", e)
                    .withCode("DEMO_SANDBOX_MANAGER_URL_INVALID");
        }
        boolean fixedEndpoint = !"http".equalsIgnoreCase(base.getScheme())
                || !"127.0.0.1".equals(base.getHost())
                || base.getPort() != 10041;
        boolean dynamicTestEndpoint = testManagerEndpoint
                && "http".equalsIgnoreCase(base.getScheme())
                && ("127.0.0.1".equals(base.getHost()) || "localhost".equalsIgnoreCase(base.getHost()))
                && base.getPort() > 0;
        if ((!testManagerEndpoint && fixedEndpoint) || (testManagerEndpoint && !dynamicTestEndpoint)
                || base.getRawUserInfo() != null
                || (base.getRawPath() != null && !base.getRawPath().isEmpty() && !"/".equals(base.getRawPath()))
                || base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new BusinessException(503, "演示沙盒管理器必须是固定 loopback 端点 http://127.0.0.1:10041")
                    .withCode("DEMO_SANDBOX_MANAGER_NOT_LOOPBACK");
        }
    }

    private String checkedSessionId(String value) {
        try {
            return UUID.fromString(value).toString();
        } catch (Exception e) {
            throw new BusinessException(400, "演示沙盒 sessionId 无效")
                    .withCode("DEMO_SANDBOX_SESSION_ID_INVALID");
        }
    }

    private String validateTargetPath(String rawTargetPath) {
        if (rawTargetPath == null || rawTargetPath.isBlank()
                || rawTargetPath.contains("%")
                || rawTargetPath.contains("\\")
                || rawTargetPath.contains("..")
                || !rawTargetPath.startsWith("/api/mobile/F006/")) {
            throw new BusinessException(400, "演示沙盒目标路径不安全")
                    .withCode("DEMO_SANDBOX_TARGET_PATH_INVALID");
        }
        if ("/api/mobile/F006/auth/me".equals(rawTargetPath)) {
            throw new BusinessException(403, "auth/me 必须由前端使用生产接口")
                    .withCode("DEMO_SANDBOX_AUTH_ME_FORBIDDEN");
        }
        if ("/api/mobile/F006/demo-sandbox-node/identity".equals(rawTargetPath)) {
            throw new BusinessException(403, "沙盒节点身份接口不可经由代理访问")
                    .withCode("DEMO_SANDBOX_NODE_IDENTITY_FORBIDDEN");
        }
        return rawTargetPath;
    }

    private BusinessException managerUnavailable(IOException cause) {
        return new BusinessException(502, "演示沙盒管理器不可用", cause)
                .withCode("DEMO_SANDBOX_MANAGER_UNAVAILABLE");
    }

    public record ProxyResult(int status, String contentType, byte[] body) {}
}
