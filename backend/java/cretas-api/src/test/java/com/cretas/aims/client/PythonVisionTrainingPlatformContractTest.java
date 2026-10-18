package com.cretas.aims.client;

import com.cretas.aims.client.PythonSmartBIClient.VisionTrainingAction;
import com.cretas.aims.config.smartbi.PythonSmartBIConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

class PythonVisionTrainingPlatformContractTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private PythonSmartBIClient client(MockWebServer server, String secret) {
        var config = new PythonSmartBIConfig();
        config.setEnabled(true); config.setMaxRetries(0); config.setUrl(server.url("/").toString());
        return new PythonSmartBIClient(config, new OkHttpClient(), mapper, new PythonServiceCircuitBreaker(), secret);
    }

    @Test void usesSharedSecureTransportWithExplicitPlatformNamespaceAndFactory() throws Exception {
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"success\":true,\"data\":{\"project_id\":\"vision-a\"}}"));
            var body = mapper.valueToTree(Map.of("stage", "train", "recipe_id", "factory-a-v1"));
            var result = client(server, "local-test-secret").callPlatformVisionTraining("FA", 1L,
                    VisionTrainingAction.ENQUEUE_JOB, "vision-a", body);
            assertThat(result.path("project_id").asText()).isEqualTo("vision-a");
            var request = server.takeRequest(1, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getPath()).isEqualTo("/api/vision-training/platform/factories/FA/drafts/vision-a/jobs");
            assertThat(request.getMethod()).isEqualTo("POST");
            assertThat(request.getHeader("X-Internal-Secret")).isEqualTo("local-test-secret");
            assertThat(request.getHeader("X-Factory-Id")).isEqualTo("FA");
            assertThat(request.getHeader("X-User-Id")).isEqualTo("1");
            assertThat(request.getHeader("X-User-Role")).isEqualTo("super_admin");
            assertThat(request.getHeader("X-Actor-Type")).isEqualTo("PLATFORM_ADMIN");
            assertThat(mapper.readTree(request.getBody().readUtf8())).isEqualTo(body);
        }
    }

    @Test void unsafeScopeIdentifiersAndMissingInternalSecretNeverReachHttp() throws Exception {
        try (var server = new MockWebServer()) {
            var client = client(server, "");
            assertThatThrownBy(() -> client.callPlatformVisionTraining("FA", 1, VisionTrainingAction.TEMPLATES, null, null))
                    .isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> client.callPlatformVisionTraining("../FB", 1, VisionTrainingAction.TEMPLATES, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.callPlatformVisionTraining("FA", 0, VisionTrainingAction.TEMPLATES, null, null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> client.callPlatformVisionTraining("FA", 1, VisionTrainingAction.PLAN, "../FB", null))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(server.getRequestCount()).isZero();
        }
    }

    @Test void invalidServiceEnvelopesAreErrorsRatherThanEmptySuccess() throws Exception {
        try (var server = new MockWebServer()) {
            for (String payload : new String[]{"{\"success\":false,\"data\":{}}", "{\"success\":true,\"data\":null}"}) {
                server.enqueue(new MockResponse().addHeader("Content-Type", "application/json").setBody(payload));
                assertThatThrownBy(() -> client(server, "local-test-secret").callPlatformVisionTraining("FA", 1,
                        VisionTrainingAction.TEMPLATES, null, null)).isInstanceOf(IllegalStateException.class);
            }
        }
    }
}
