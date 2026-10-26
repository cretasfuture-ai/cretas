package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.cache.IntentResultCache;
import com.cretas.aims.client.PythonAiMatcherClient;
import com.cretas.aims.client.PythonSmartBIClient;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.intent.IntentMatchResult.CandidateIntent;
import com.cretas.aims.dto.intent.PythonIntentMatchRequest;
import com.cretas.aims.dto.python.PythonGeneralAnalysisResponse;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.intent.IntentConfigManagementService;
import com.cretas.aims.service.intent.IntentRecognitionPipelineService;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 意图识别链路 (只有用户原话 / 历史): 发给 DashScope、Python 识别 / 重排 / 澄清的原话里,
 * 电话、岗位 + 人名、公司名换成代号; 模型回来的澄清问题里换回真值; 脱敏不了就不发。
 * 每条走对应的公开入口, 只在模型 / HTTP 那一层打桩。
 */
class IntentEgressRedactionTest {

    private static final String FACTORY = "F006";
    private static final String SAID = "通知质检员赵铁柱 13912345678 去华东冷链商贸有限公司复检这批货";
    private static final Pattern PHONE_TOKEN = Pattern.compile("\\[电话[A-Z]+]");

    private final ObjectMapper mapper = new ObjectMapper();
    private ProcessSecretSampleFilter processSecret;
    private DashScopeClient llm;
    private DashScopeConfig config;
    private PythonSmartBIClient smartBi;

    @BeforeEach
    void setUp() {
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
        llm = mock(DashScopeClient.class);
        config = mock(DashScopeConfig.class);
        smartBi = mock(PythonSmartBIClient.class);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("userId", 55L);
        request.setAttribute("role", "factory_super_admin");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void tearDown() {
        RequestContextHolder.resetRequestAttributes();
    }

    private static void assertNoRealValues(String sent) {
        assertThat(sent).doesNotContain("13912345678", "赵铁柱", "华东冷链").contains("复检");
    }

    private static String phoneToken(String sent) {
        Matcher m = PHONE_TOKEN.matcher(sent);
        assertThat(m.find()).as("sent text carries a phone token: %s", sent).isTrue();
        return m.group();
    }

    private LlmIntentFallbackClientImpl fallback(boolean direct) {
        when(config.shouldUseDirect("intent-classify")).thenReturn(direct);
        when(llm.isAvailable()).thenReturn(direct);
        LlmIntentFallbackClientImpl client = new LlmIntentFallbackClientImpl(null, llm, config, null, null, null,
                null, null, null, null, null, null, smartBi);
        ReflectionTestUtils.setField(client, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
        return client;
    }

    private static List<AIIntentConfig> intents() {
        return List.of(AIIntentConfig.builder().intentCode("QUALITY_CHECK_CREATE").intentName("创建质检")
                        .description("安排复检").keywords("[\"复检\"]").isActive(true).build(),
                AIIntentConfig.builder().intentCode("MATERIAL_QUERY").intentName("原料查询")
                        .description("查原料").keywords("[\"原料\"]").isActive(true).build());
    }

    private static CandidateIntent candidate() {
        return CandidateIntent.builder().intentCode("QUALITY_CHECK_CREATE").intentName("创建质检")
                .description("安排复检").confidence(0.8).build();
    }

    // ---------------------------------------------------------------- LLM 兜底分类 / 重排 / 澄清

    @Test
    void directClassificationSendsRedactedPromptAndUtterance() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.classifyIntent(system.capture(), user.capture()))
                .thenReturn("{\"intent_code\":\"QUALITY_CHECK_CREATE\",\"confidence\":0.9,\"reasoning\":\"复检\"}");

        fallback(true).classifyIntent(SAID, intents(), FACTORY, 55L, "factory_super_admin");

        assertNoRealValues(user.getValue());
        assertThat(system.getValue()).doesNotContain("13912345678", "赵铁柱", "华东冷链");
    }

    @Test
    void pythonClassificationSendsRedactedUtterance() throws Exception {
        try (MockWebServer python = new MockWebServer()) {
            python.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"success\":true,\"matched\":false}"));
            python.start();
            LlmIntentFallbackClientImpl client = fallback(false);
            ReflectionTestUtils.setField(client, "aiServiceUrl", python.url("").toString().replaceAll("/$", ""));

            client.classifyIntent(SAID, intents(), FACTORY, 55L, "factory_super_admin");

            RecordedRequest sent = python.takeRequest(5, TimeUnit.SECONDS);
            assertThat(sent).isNotNull();
            assertThat(sent.getPath()).isEqualTo("/api/ai/intent/classify");
            assertNoRealValues(sent.getBody().readString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void pythonRerankingSendsRedactedQuery() throws Exception {
        ArgumentCaptor<PythonSmartBIClient.GeneralAnalysisCall> sent =
                ArgumentCaptor.forClass(PythonSmartBIClient.GeneralAnalysisCall.class);
        when(smartBi.analyzeGeneral(anyString(), nullable(String.class), nullable(String.class), sent.capture()))
                .thenReturn(PythonGeneralAnalysisResponse.builder().success(true)
                        .answer("{\"selected_intent\":\"QUALITY_CHECK_CREATE\",\"confidence\":0.91,"
                                + "\"reasoning\":\"复检\",\"agrees_with_ranking\":true}").build());

        fallback(false).rerankCandidates(SAID, List.of(candidate()), FACTORY);

        assertNoRealValues(sent.getValue().query());
    }

    @Test
    void missingParameterQuestionIsAskedWithTokensAndShowsTheRealNumber() throws Exception {
        ArgumentCaptor<PythonSmartBIClient.GeneralAnalysisCall> sent =
                ArgumentCaptor.forClass(PythonSmartBIClient.GeneralAnalysisCall.class);
        when(smartBi.analyzeGeneral(anyString(), nullable(String.class), nullable(String.class), sent.capture()))
                .thenAnswer(inv -> {
                    String token = phoneToken(inv.<PythonSmartBIClient.GeneralAnalysisCall>getArgument(3).query());
                    return PythonGeneralAnalysisResponse.builder().success(true)
                            .answer("1. 复检结果要发到 " + token + " 吗？").build();
                });
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("QUALITY_CHECK_CREATE").intentName("创建质检").build();

        List<String> questions = fallback(false)
                .generateClarificationQuestionsForMissingParams(SAID, intent, List.of("batchId"), FACTORY);

        assertNoRealValues(sent.getValue().query());
        assertThat(questions).containsExactly("复检结果要发到 13912345678 吗？");
    }

    @Test
    void directMissingParameterQuestionIsAskedWithTokens() {
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(system.capture(), user.capture())).thenReturn("请问是哪一批货？");
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("QUALITY_CHECK_CREATE").intentName("创建质检").build();

        fallback(true).generateClarificationQuestionsForMissingParams(SAID, intent, List.of("batchId"), FACTORY);

        assertNoRealValues(user.getValue());
        assertThat(system.getValue()).doesNotContain("13912345678", "赵铁柱", "华东冷链");
    }

    @Test
    void nothingIsSentWhenRedactionFails() throws Exception {
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        AIIntentConfig intent = AIIntentConfig.builder().intentCode("QUALITY_CHECK_CREATE").intentName("创建质检").build();

        fallback(true).classifyIntent(SAID, intents(), FACTORY, 55L, "factory_super_admin");
        fallback(true).rerankCandidates(SAID, List.of(candidate()), FACTORY);
        List<String> questions = fallback(true)
                .generateClarificationQuestionsForMissingParams(SAID, intent, List.of("batchId"), FACTORY);
        fallback(false).rerankCandidates(SAID, List.of(candidate()), FACTORY);

        verify(llm, never()).classifyIntent(anyString(), anyString());
        verify(llm, never()).chat(anyString(), anyString());
        verifyNoInteractions(smartBi);
        assertThat(questions).isNotEmpty();   // 模板问题, 不是模型生成的
    }

    // ---------------------------------------------------------------- Python 意图匹配 (带历史)

    private PythonAiMatcherClient python;
    private IntentRecognitionPipelineService pipeline;

    private AIIntentServiceImpl intentService() {
        python = mock(PythonAiMatcherClient.class);
        pipeline = mock(IntentRecognitionPipelineService.class);
        when(pipeline.recognizeExpectedEarlyPhrase(anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(pipeline.recognizeIntentWithConfidence(anyString(), any(), anyInt(), any(), any(), any()))
                .thenAnswer(inv -> IntentMatchResult.empty(inv.getArgument(0)));
        IntentConfigManagementService configService = mock(IntentConfigManagementService.class);
        when(configService.resolveBusinessDomain(FACTORY)).thenReturn("FACTORY");
        AIIntentServiceImpl service = new AIIntentServiceImpl(pipeline, configService, null);
        ReflectionTestUtils.setField(service, "pythonClient", python);
        ReflectionTestUtils.setField(service, "intentResultCache", mock(IntentResultCache.class));
        ReflectionTestUtils.setField(service, "usePythonMatcher", true);
        ReflectionTestUtils.setField(service, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
        return service;
    }

    private static List<Map<String, String>> history() {
        return List.of(Map.of("role", "user", "content", "上次是华东冷链商贸有限公司送的货"),
                Map.of("role", "assistant", "content", "已记录, 联系人赵铁柱 13912345678"));
    }

    @Test
    void pythonMatcherGetsRedactedQueryAndHistoryAndTheClarificationShowsRealValues() {
        AIIntentServiceImpl service = intentService();
        ArgumentCaptor<PythonIntentMatchRequest> sent = ArgumentCaptor.forClass(PythonIntentMatchRequest.class);
        when(python.match(sent.capture())).thenAnswer(inv -> {
            PythonIntentMatchRequest req = inv.getArgument(0);
            String token = phoneToken(req.getQuery());
            return IntentMatchResult.builder().userInput(req.getQuery()).confidence(0.0)
                    .clarificationQuestion("要通知 " + token + " 吗？")
                    .timingMs(Map.of("decisionNone", 1L)).build();
        });

        IntentMatchResult result = service.recognizeIntentWithConfidence(SAID, FACTORY, 3, 55L,
                "factory_super_admin", "s-1", null, null, history());

        PythonIntentMatchRequest req = sent.getValue();
        assertNoRealValues(req.getQuery());
        assertThat(req.getHistory()).hasSize(2);
        assertThat(req.getHistory().get(0).get("content")).doesNotContain("华东冷链");
        assertThat(req.getHistory().get(1).get("content")).doesNotContain("赵铁柱", "13912345678");
        assertThat(req.getHistory().get(1).get("role")).isEqualTo("assistant");
        assertThat(result.getUserInput()).isEqualTo(SAID);
        assertThat(result.getClarificationQuestion()).isEqualTo("要通知 13912345678 吗？");
    }

    @Test
    void pythonMatcherIsSkippedWhenRedactionFailsAndTheLocalPipelineAnswers() {
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        AIIntentServiceImpl service = intentService();

        service.recognizeIntentWithConfidence(SAID, FACTORY, 3, 55L, "factory_super_admin", "s-1",
                null, null, history());

        verify(python, never()).match(any());
        verify(pipeline).recognizeIntentWithConfidence(eq(SAID), eq(FACTORY), eq(3), eq(55L), any(), any());
    }
}
