package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.config.IndustryKnowledgeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.LongTextConfig;
import com.cretas.aims.dto.ai.AnalysisContext;
import com.cretas.aims.dto.ai.AnalysisResult;
import com.cretas.aims.dto.ai.AnalysisTopic;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.dto.slot.RequiredSlot;
import com.cretas.aims.service.KnowledgeFeedbackService;
import com.cretas.aims.service.ParameterExtractionLearningService;
import com.cretas.aims.service.RetrievalEvaluatorService;
import com.cretas.aims.service.ToolRouterService;
import com.cretas.aims.service.ToolRouterService.ToolCandidate;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.IntentSlotConfiguration;
import com.cretas.aims.service.ConversationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 只带用户原话的出口: 分析路由 / 分析 Agent / 槽位兜底抽参 / 长文本摘要 / 动态选工具。
 * 发出去的原话里电话、岗位 + 人名、公司名是代号; 回来的分析、参数、摘要里是真值; 脱敏不了就不发。
 * 每条走对应服务的公开方法, 只把模型那一层打桩。
 */
class UtteranceEgressRedactionTest {

    private static final String FACTORY = "F006";
    private static final String SAID = "通知质检员赵铁柱 13912345678 去华东冷链商贸有限公司复检这批货";
    private static final Pattern PERSON_TOKEN = Pattern.compile("\\[人员[A-Z]+]");

    private final ObjectMapper mapper = new ObjectMapper();
    private ProcessSecretSampleFilter processSecret;

    @BeforeEach
    void setUp() {
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
    }

    private LlmEgressGuard guard() {
        return new LlmEgressGuard(mapper, processSecret);
    }

    /** factoryId 为 null 的调用点取请求里的工厂; 测试里没有请求, 用必炸的过滤器模拟「查不了」。 */
    private LlmEgressGuard brokenGuard() {
        ProcessSecretSampleFilter broken = mock(ProcessSecretSampleFilter.class);
        when(broken.protectedProductNames(anyString())).thenThrow(new IllegalStateException("db down"));
        LlmEgressGuard g = new LlmEgressGuard(mapper, broken);
        return new LlmEgressGuard(mapper, broken) {
            @Override
            public com.cretas.aims.service.consent.LlmEgressRedactor.Result texts(String site, String factoryId,
                    String... texts) {
                return g.texts(site, FACTORY, texts);
            }
        };
    }

    private static void assertNoRealValues(String sent) {
        assertThat(sent).doesNotContain("13912345678", "赵铁柱", "华东冷链").contains("复检");
    }

    private static String personToken(String sent) {
        Matcher m = PERSON_TOKEN.matcher(sent);
        assertThat(m.find()).as("sent text carries a person token: %s", sent).isTrue();
        return m.group();
    }

    private static AnalysisContext context() {
        return AnalysisContext.builder().userInput(SAID).topic(AnalysisTopic.QUALITY_ANALYSIS)
                .factoryId(FACTORY).userId(1L).sessionId("s-1").enableThinking(false).build();
    }

    // ---------------------------------------------------------------- 分析

    @Test
    void analysisRouterSendsRedactedQuestionAndTheAnalysisNamesTheRealPerson() {
        PythonLLMClient llm = mock(PythonLLMClient.class);
        IndustryKnowledgeConfig knowledge = mock(IndustryKnowledgeConfig.class);
        when(knowledge.getKnowledgeForTopic(anyString())).thenReturn("质检关注复检记录。");
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(system.capture(), user.capture()))
                .thenAnswer(inv -> "建议由 " + personToken(inv.getArgument(1)) + " 跟进复检。");
        AnalysisRouterServiceImpl router = new AnalysisRouterServiceImpl(
                mock(ToolRouterService.class), llm, knowledge, mock(IntentKnowledgeBase.class));
        ReflectionTestUtils.setField(router, "llmEgressGuard", guard());

        AnalysisResult result = router.executeAnalysis(context());

        assertNoRealValues(user.getValue());
        assertThat(result.getFormattedAnalysis()).contains("赵铁柱").doesNotContain("[人员");

        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        PythonLLMClient second = mock(PythonLLMClient.class);
        ReflectionTestUtils.setField(router, "pythonLLMClient", second);
        assertThat(router.executeAnalysis(context()).isSuccess()).isFalse();
        verify(second, never()).chat(anyString(), anyString());
    }

    @Test
    void analysisAgentSendsRedactedQuestionAndTheAnalysisNamesTheRealPerson() {
        DashScopeClient llm = mock(DashScopeClient.class);
        IndustryKnowledgeConfig knowledge = mock(IndustryKnowledgeConfig.class);
        when(knowledge.getKnowledgeForTopic(anyString())).thenReturn("质检关注复检记录。");
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), user.capture())).thenAnswer(inv -> "建议由 "
                + personToken(inv.getArgument(1)) + " 跟进复检, 每批货都要留存复检记录并在当天完成签字确认。");
        AgentOrchestratorImpl agents = new AgentOrchestratorImpl(
                mock(RetrievalEvaluatorService.class), mock(KnowledgeFeedbackService.class), knowledge, llm);
        ReflectionTestUtils.setField(agents, "complexityThreshold", 0.6);
        ReflectionTestUtils.setField(agents, "llmEgressGuard", guard());

        AnalysisResult result = agents.executeCollaborativeAnalysis(context());

        assertThat(user.getAllValues()).isNotEmpty().allSatisfy(UtteranceEgressRedactionTest::assertNoRealValues);
        assertThat(result.getFormattedAnalysis()).contains("赵铁柱").doesNotContain("[人员");

        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        DashScopeClient second = mock(DashScopeClient.class);
        ReflectionTestUtils.setField(agents, "dashScopeClient", second);
        agents.executeCollaborativeAnalysis(context());
        verify(second, never()).chat(anyString(), anyString());
    }

    // ---------------------------------------------------------------- 槽位兜底抽参

    @Test
    void slotFillingSendsRedactedUtteranceAndTheExtractedValueIsReal() {
        SlotFillingServiceImpl slots = new SlotFillingServiceImpl(mock(IntentSlotConfiguration.class),
                mock(ToolRegistry.class), mock(ConversationService.class),
                mock(ParameterExtractionLearningService.class));
        PythonLLMClient llm = mock(PythonLLMClient.class);
        slots.setPythonLLMClient(llm);
        ReflectionTestUtils.setField(slots, "llmEgressGuard", guard());
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), user.capture()))
                .thenAnswer(inv -> "{\"inspector\": \"" + personToken(inv.getArgument(1)) + "\"}");
        RequiredSlot inspector = RequiredSlot.builder().name("inspector").label("复检负责人").type("TEXT").build();

        Map<String, Object> extracted = slots.extractParameters(SAID, List.of(inspector), null, null);

        assertNoRealValues(user.getValue());
        assertThat(String.valueOf(extracted.get("inspector"))).contains("赵铁柱").doesNotContain("[人员");

        ReflectionTestUtils.setField(slots, "llmEgressGuard", brokenGuard());
        PythonLLMClient second = mock(PythonLLMClient.class);
        slots.setPythonLLMClient(second);
        assertThat(slots.extractParameters(SAID, List.of(inspector), null, null)).doesNotContainKey("inspector");
        verify(second, never()).chatLowTemp(anyString(), anyString());
    }

    // ---------------------------------------------------------------- 长文本摘要

    @Test
    void longTextSummaryIsAskedWithTokensAndTheSummaryIsReal() {
        LongTextConfig config = mock(LongTextConfig.class);
        when(config.getSummaryMaxLength()).thenReturn(200);
        when(config.getCacheMaxSize()).thenReturn(10);
        when(config.getCacheExpireSeconds()).thenReturn(60);
        com.cretas.aims.config.DashScopeConfig dashScopeConfig = mock(com.cretas.aims.config.DashScopeConfig.class);
        when(dashScopeConfig.getCorrectionModel()).thenReturn("qwen-turbo");
        PythonLLMClient llm = mock(PythonLLMClient.class);
        when(llm.isAvailable()).thenReturn(true);
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        when(llm.chatCompletion(sent.capture())).thenAnswer(inv -> {
            ChatCompletionRequest req = inv.getArgument(0);
            ChatCompletionResponse response = mock(ChatCompletionResponse.class);
            when(response.getContent()).thenReturn("让 "
                    + personToken(String.valueOf(req.getMessages().get(1).getContent())) + " 复检");
            return response;
        });
        LongTextHandlerImpl handler = new LongTextHandlerImpl(config, dashScopeConfig, llm);
        ReflectionTestUtils.setField(handler, "llmEgressGuard", guard());

        String summary = handler.summarizeForIntent(SAID);

        assertNoRealValues(String.valueOf(sent.getValue().getMessages().get(1).getContent()));
        assertThat(summary).isEqualTo("让 赵铁柱 复检");

        ReflectionTestUtils.setField(handler, "llmEgressGuard", brokenGuard());
        PythonLLMClient second = mock(PythonLLMClient.class);
        when(second.isAvailable()).thenReturn(true);
        ReflectionTestUtils.setField(handler, "pythonLLMClient", second);
        handler.summarizeForIntent(SAID);
        verify(second, never()).chatCompletion(any());
    }

    // ---------------------------------------------------------------- 动态选工具

    @Test
    void toolRouterSendsRedactedPromptAndQuery() {
        ToolRouterServiceImpl router = new ToolRouterServiceImpl();
        DashScopeClient llm = mock(DashScopeClient.class);
        ReflectionTestUtils.setField(router, "dashScopeClient", llm);
        ReflectionTestUtils.setField(router, "objectMapper", mapper);
        ReflectionTestUtils.setField(router, "llmEgressGuard", guard());
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(system.capture(), user.capture())).thenReturn(
                "{\"tools\":[{\"toolName\":\"quality_check_create\",\"reason\":\"复检\"}],\"executionOrder\":\"SEQUENTIAL\"}");
        List<ToolCandidate> candidates = List.of(ToolCandidate.builder().toolName("quality_check_create")
                .toolDescription("创建质检").similarity(0.9).build());

        router.selectTools(SAID, IntentMatchResult.empty(SAID), candidates);

        assertNoRealValues(user.getValue());
        assertThat(system.getValue()).doesNotContain("13912345678", "赵铁柱", "华东冷链");

        ReflectionTestUtils.setField(router, "llmEgressGuard", brokenGuard());
        DashScopeClient second = mock(DashScopeClient.class);
        ReflectionTestUtils.setField(router, "dashScopeClient", second);
        assertThat(router.selectTools(SAID, IntentMatchResult.empty(SAID), candidates).getTools())
                .extracting(t -> t.getToolName()).containsExactly("quality_check_create");
        verify(second, never()).chat(anyString(), anyString());
    }
}
