package com.cretas.aims.service.impl;

import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.PythonIntentMatchRequest;
import com.cretas.aims.dto.intent.PythonIntentMatchResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import com.cretas.aims.service.intent.flywheel.FlywheelDecisionAnalysis;
import com.cretas.aims.service.intent.flywheel.IntentFlywheelTurnContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

/**
 * 意图飞轮在识别层的三个挂点 (真实 {@link AIIntentServiceImpl#recognizeIntentWithConfidence}):
 * 阶段计数点 {@code recordStageHit} → noteStage; Python 判「以上都不是」的提前返回 → noteRecognition;
 * {@code applyAccessModeFilter} 出口 (Python 命中 / 旧管线) → noteRecognition。删掉任何一处, 这里有一条会红。
 * 另: Python 带回的 decisionTrace 经 Java 反序列化保留下来, 来源判成 LUNA。
 */
class AIIntentServiceImplFlywheelTraceContractTest {

    @Mock IntentRecognitionPipelineService pipelineService;
    @Mock IntentConfigManagementService configService;
    @Mock PythonAiMatcherClient pythonClient;
    @Mock IntentResultCache cache;

    AIIntentServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new AIIntentServiceImpl(pipelineService, configService, null);
        set("pythonClient", pythonClient);
        set("intentResultCache", cache);
        set("usePythonMatcher", true);
        when(configService.resolveBusinessDomain(anyString())).thenReturn("FACTORY");
        when(cache.get(anyString(), anyString(), anyString(), anyString())).thenReturn(null);
    }

    private void set(String name, Object value) throws Exception {
        var f = AIIntentServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private static IntentMatchResult match(String code) {
        AIIntentConfig cfg = new AIIntentConfig();
        cfg.setIntentCode(code);
        cfg.setIntentName(code);
        return IntentMatchResult.builder().bestMatch(cfg).confidence(0.95)
                .matchMethod(IntentMatchResult.MatchMethod.SEMANTIC).userInput("x").build();
    }

    @Test
    void pythonMatchLeavesStageAndFinalRecognitionWithTheDecisionTrace() {
        IntentMatchResult py = match("PY_INTENT");
        py.setDecisionTrace(new HashMap<>(Map.of("luna", "ok", "optionSetHash", "os-sha256:abc")));
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(py);

        try (IntentFlywheelTurnContext.Scope scope = IntentFlywheelTurnContext.open()) {
            service.recognizeIntentWithConfidence("查询订单", "F001", 3, 22L, "admin", null);

            assertThat(scope.turn().stages()).contains("PYTHON_MATCH");
            assertThat(scope.turn().lastRecognition()).isNotNull();
            assertThat(scope.turn().lastRecognition().getBestMatch().getIntentCode()).isEqualTo("PY_INTENT");
            FlywheelDecisionAnalysis.Decision d = FlywheelDecisionAnalysis.analyse(scope.turn(), null);
            assertThat(d.source()).isEqualTo("LUNA");
            assertThat(d.optionSetHash()).isEqualTo("os-sha256:abc");
        }
    }

    @Test
    void pythonDecisionNoneEarlyReturnIsRecorded() {
        IntentMatchResult none = IntentMatchResult.empty("先别入库");
        none.setTimingMs(new HashMap<>(Map.of("decisionNone", 1L)));
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(none);

        try (IntentFlywheelTurnContext.Scope scope = IntentFlywheelTurnContext.open()) {
            service.recognizeIntentWithConfidence("先别入库", "F001", 3, 22L, "admin", null);

            assertThat(scope.turn().stages()).containsExactly("PYTHON_DECISION_NONE");
            assertThat(scope.turn().recognitionCount()).isEqualTo(1);
            assertThat(FlywheelDecisionAnalysis.analyse(scope.turn(), null).action())
                    .isEqualTo(com.cretas.aims.entity.intent.IntentFlywheelTurn.Action.NONE);
        }
    }

    @Test
    void legacyPipelineExitIsRecordedThroughTheAccessModeFilter() {
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(IntentMatchResult.empty("查询订单"));
        when(pipelineService.recognizeIntentWithConfidence(eq("查询订单"), eq("F001"), eq(3), eq(22L), eq("admin"), isNull()))
                .thenReturn(match("LEGACY_INTENT"));

        try (IntentFlywheelTurnContext.Scope scope = IntentFlywheelTurnContext.open()) {
            service.recognizeIntentWithConfidence("查询订单", "F001", 3, 22L, "admin", null);

            assertThat(scope.turn().stages()).endsWith("SEMANTIC");
            assertThat(scope.turn().lastRecognition().getBestMatch().getIntentCode()).isEqualTo("LEGACY_INTENT");
            assertThat(FlywheelDecisionAnalysis.analyse(scope.turn(), null).source()).isEqualTo("RULE");
        }
    }

    @Test
    void noOpenScopeMeansNothingIsKept() {
        when(pythonClient.match(any(PythonIntentMatchRequest.class))).thenReturn(match("PY_INTENT"));
        service.recognizeIntentWithConfidence("查询订单", "F001", 3, 22L, "admin", null);
        assertThat(IntentFlywheelTurnContext.current()).isNull();
    }

    @Test
    void pythonResponseJsonKeepsDecisionTraceOnTheJavaSide() throws Exception {
        String json = "{\"success\":true,\"data\":{\"bestMatch\":null,\"confidence\":0.2,"
                + "\"decisionTrace\":{\"luna\":\"fallback\",\"fallback\":{\"hop\":\"combo\",\"outcome\":\"answered\"},"
                + "\"optionSetHash\":\"os-sha256:1234\"}}}";
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        PythonIntentMatchResponse body = mapper.readValue(json, PythonIntentMatchResponse.class);

        Map<String, Object> trace = body.getData().getDecisionTrace();
        assertThat(trace).containsEntry("luna", "fallback").containsEntry("optionSetHash", "os-sha256:1234");
        assertThat(trace.get("fallback")).isInstanceOf(Map.class);
    }
}
