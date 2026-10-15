package com.cretas.aims.blueprint.platform;

import com.cretas.aims.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.MediaType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.junit.jupiter.api.Assertions.*;

class PlatformAssemblyGenerationContractTest {
    @Test void unwrapsStructuredEnvelopeExactlyOnce() {
        Map<String, Object> data = Map.of("schemas", List.of(), "suggestedData", Map.of("productTypes", List.of()));
        assertSame(data, PlatformAssemblyGenerationGateway.structuredData(Map.of("success", true, "data", data)));
    }

    @Test void rejectsLegacyTextOrFailedGeneration() {
        assertThrows(BusinessException.class, () -> PlatformAssemblyGenerationGateway.structuredData(
                Map.of("success", true, "data", Map.of("configuration", "text only"))));
        assertThrows(BusinessException.class, () -> PlatformAssemblyGenerationGateway.structuredData(
                Map.of("success", false, "data", Map.of("schemas", List.of(), "suggestedData", Map.of()))));
    }

    @Test void forwardsBothLiveAuthorizedTokenAndServiceSecretToDedicatedRoute() {
        var gateway = new PlatformAssemblyGenerationGateway("http://127.0.0.1:18095", "internal-test-only");
        var server = MockRestServiceServer.bindTo((RestTemplate) ReflectionTestUtils.getField(gateway, "http")).build();
        server.expect(requestTo("http://127.0.0.1:18095/api/platform/factory-assembly/generate"))
                .andExpect(header("Authorization", "Bearer platform-test-only"))
                .andExpect(header("X-Internal-Secret", "internal-test-only"))
                .andRespond(withSuccess("{\"success\":true,\"data\":{\"schemas\":[],\"suggestedData\":{}}}", MediaType.APPLICATION_JSON));
        assertTrue(gateway.generate("platform-test-only", "测试工厂", "工厂描述", null, true).containsKey("schemas"));
        server.verify();
    }

    @Test void missingServiceSecretFailsBeforeAnyHttpCall() {
        var gateway = new PlatformAssemblyGenerationGateway("http://127.0.0.1:1", "");
        var failure = assertThrows(BusinessException.class,
                () -> gateway.generate("platform-test-only", "测试工厂", "工厂描述", null, true));
        assertEquals(503, failure.getCode());
    }
}
