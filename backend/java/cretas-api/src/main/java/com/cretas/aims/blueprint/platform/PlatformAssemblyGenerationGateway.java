package com.cretas.aims.blueprint.platform;

import com.cretas.aims.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

/** The structured assembly contract never goes through the legacy text generator. */
@Service
@ConditionalOnProperty(name = "cretas.platform.factory-assembly.enabled", havingValue = "true")
public class PlatformAssemblyGenerationGateway {
    private final RestTemplate http;
    private final String url;
    private final String internalSecret;

    public PlatformAssemblyGenerationGateway(@Value("${cretas.ai.service.url:http://localhost:8083}") String baseUrl,
            @Value("${cretas.python.internal-secret:}") String internalSecret) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);
        http = new RestTemplate(factory);
        url = baseUrl + "/api/platform/factory-assembly/generate";
        this.internalSecret = internalSecret;
    }

    public Map<String, Object> generate(String token, String name, String description,
                                        String industry, Boolean includeBusinessData) {
        if (token == null || token.isBlank()) throw new BusinessException(401, "请先登录平台账号");
        if (internalSecret == null || internalSecret.isBlank()) {
            throw new BusinessException(503, "平台建厂服务尚未配置");
        }
        var payload = new HashMap<String, Object>();
        payload.put("factory_name", name);
        payload.put("factory_description", description);
        payload.put("industry_hint", industry);
        payload.put("include_business_data", includeBusinessData == null || includeBusinessData);
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(token);
        headers.set("X-Internal-Secret", internalSecret);
        try {
            var response = http.exchange(url, HttpMethod.POST, new HttpEntity<>(payload, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() { }).getBody();
            return structuredData(response);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Provider details and tokens must never enter the public error envelope.
            throw new BusinessException(502, "建厂配置生成服务暂不可用，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> structuredData(Map<String, Object> response) {
        if (response == null || !Boolean.TRUE.equals(response.get("success"))
                || !(response.get("data") instanceof Map<?, ?> data)
                || !(data.get("schemas") instanceof java.util.List<?>)
                || !(data.get("suggestedData") instanceof Map<?, ?>)) {
            throw new BusinessException(502, "建厂配置生成未成功，请稍后重试");
        }
        return (Map<String, Object>) data;
    }
}
