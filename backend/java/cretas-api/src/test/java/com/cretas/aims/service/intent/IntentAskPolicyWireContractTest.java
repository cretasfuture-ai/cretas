package com.cretas.aims.service.intent;

import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.canvas.FactoryThreshold;
import com.cretas.aims.entity.canvas.ThresholdCategory;
import com.cretas.aims.entity.canvas.ThresholdValueType;
import com.cretas.aims.repository.canvas.FactoryThresholdRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.canvas.impl.ThresholdResolverServiceImpl;
import com.cretas.aims.service.impl.AIIntentServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

/**
 * 按厂先问档位 (Steve 2026-11-14) 的接线闸: 真实识别入口 {@link AIIntentServiceImpl#recognizeIntentWithConfidence}
 * → 真实 {@link IntentAskPolicyResolver} → 真实 {@link ThresholdResolverServiceImpl} (工厂行 &gt; 全局 '*' 行)
 * → 发给 Python 的请求体 {@code askPolicy}。只替换最外层: 库 (FactoryThresholdRepository) 与 Python HTTP。
 *
 * <p>守的行为: 同一句话, 配了保守的工厂发 CONSERVATIVE、配了激进的发 AGGRESSIVE、没有工厂行的走全局行;
 * 取不到 (连全局行都没有 / 读库异常) 或认不出 ⇒ 默认 AGGRESSIVE 且计数 (不静默)。
 */
@DisplayName("按厂先问档位随识别请求交给 Python (askPolicy)")
class IntentAskPolicyWireContractTest {

    private static final String PYTHON_URL = "http://python.test";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<JsonNode> pythonBodies = new ArrayList<>();
    /** (factoryId, key) -> 行; key 不在表里 = 没有这一行 */
    private final Map<String, FactoryThreshold> rows = new HashMap<>();
    private boolean repositoryThrows;
    private AIIntentServiceImpl intentService;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer python = MockRestServiceServer.bindTo(restTemplate).build();
        python.expect(ExpectedCount.manyTimes(), requestTo(PYTHON_URL + "/api/ai/intent/match"))
                .andRespond(request -> {
                    pythonBodies.add(JSON.readTree(((MockClientHttpRequest) request).getBodyAsString(StandardCharsets.UTF_8)));
                    MockClientHttpResponse r = new MockClientHttpResponse(
                            "{\"success\":true,\"data\":{\"userInput\":\"q\"},\"message\":\"OK\"}"
                                    .getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
                    r.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                    return r;
                });

        FactoryThresholdRepository repo = mock(FactoryThresholdRepository.class);
        when(repo.findByFactoryIdAndThresholdKey(anyString(), anyString())).thenAnswer(inv -> {
            if (repositoryThrows) {
                throw new IllegalStateException("db down");
            }
            return Optional.ofNullable(rows.get(inv.getArgument(0) + "|" + inv.getArgument(1)));
        });
        meters = new SimpleMeterRegistry();
        IntentAskPolicyResolver resolver = new IntentAskPolicyResolver(new ThresholdResolverServiceImpl(repo));
        ReflectionTestUtils.setField(resolver, "meterRegistry", meters);

        IntentRecognitionPipelineService pipeline = mock(IntentRecognitionPipelineService.class);
        when(pipeline.recognizeIntentWithConfidence(anyString(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        when(pipeline.recognizeExpectedEarlyPhrase(any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(anyString())).thenReturn("FACTORY");
        IntentResultCache cache = mock(IntentResultCache.class);

        intentService = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(intentService, "pythonClient",
                new PythonAiMatcherClient(restTemplate, PYTHON_URL, "test-secret"));
        ReflectionTestUtils.setField(intentService, "intentResultCache", cache);
        ReflectionTestUtils.setField(intentService, "usePythonMatcher", true);
        ReflectionTestUtils.setField(intentService, "askPolicyResolver", resolver);
    }

    private void row(String factoryId, String value) {
        rows.put(factoryId + "|" + ThresholdKeys.AI_INTENT_ASK_POLICY, FactoryThreshold.builder()
                .factoryId(factoryId).thresholdKey(ThresholdKeys.AI_INTENT_ASK_POLICY)
                .category(ThresholdCategory.AI).valueType(ThresholdValueType.STRING)
                .thresholdValue(value).defaultValue("AGGRESSIVE").build());
    }

    private String sentPolicy(String factoryId) {
        int before = pythonBodies.size();
        intentService.recognizeIntentWithConfidence("查下这批可可粉", factoryId, 3, 7L, "warehouse_worker", null);
        assertEquals(before + 1, pythonBodies.size(), "Python 必须被调用恰好 1 次 (「一定发生」的读数)");
        JsonNode body = pythonBodies.get(pythonBodies.size() - 1);
        assertEquals(factoryId, body.path("factoryId").asText());
        return body.hasNonNull("askPolicy") ? body.get("askPolicy").asText() : null;
    }

    private double defaulted(String reason) {
        var c = meters.find("intent.ask_policy.defaulted").tag("reason", reason).counter();
        return c == null ? 0 : c.count();
    }

    @Test
    @DisplayName("同一句话: 保守的工厂发 CONSERVATIVE, 激进的发 AGGRESSIVE, 没有工厂行的走全局行")
    void eachFactorySendsItsOwnTier() {
        row("*", "AGGRESSIVE");
        row("F_CONS", "CONSERVATIVE");
        row("F_AGG", "aggressive");
        assertEquals("CONSERVATIVE", sentPolicy("F_CONS"));
        assertEquals("AGGRESSIVE", sentPolicy("F_AGG"));
        assertEquals("AGGRESSIVE", sentPolicy("F_NO_ROW"));
        assertEquals(0.0, defaulted("missing") + defaulted("unknown") + defaulted("error"),
                "配置都取得到时不该记默认");
    }

    @Test
    @DisplayName("全局 '*' 行配成保守 ⇒ 没有工厂行的工厂跟着保守; 工厂行仍优先")
    void globalRowAppliesToFactoriesWithoutTheirOwnRow() {
        row("*", "CONSERVATIVE");
        row("F_AGG", "AGGRESSIVE");
        assertEquals("CONSERVATIVE", sentPolicy("F_NO_ROW"));
        assertEquals("AGGRESSIVE", sentPolicy("F_AGG"));
    }

    @Test
    @DisplayName("取不到 / 读库异常 / 认不出 ⇒ 默认 AGGRESSIVE, 且各自计数")
    void missingOrBrokenConfigFallsBackToDefaultAndCounts() {
        assertEquals("AGGRESSIVE", sentPolicy("F_NOTHING"));
        assertEquals(1.0, defaulted("missing"));

        row("F_TYPO", "MEDIUM");
        assertEquals("AGGRESSIVE", sentPolicy("F_TYPO"));
        assertEquals(1.0, defaulted("unknown"));

        repositoryThrows = true;
        assertEquals("AGGRESSIVE", sentPolicy("F_DB_DOWN"));
        assertEquals(1.0, defaulted("error"));
    }
}
