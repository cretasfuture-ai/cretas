package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.intent.IntentMatchResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 自由提问识别到工作台意图 (2026-10-25 F006「今天要跟进哪些客户」线上实测):
 * 以前 [Branch:Skill] 直接 return, 用户只拿到「未返回可直接展示的明细数据」。
 * 现在与显式意图路径一样经过格式化与 {@link WorkdeskOutputSummarizer} 脱敏摘要。
 * 走真实入口 {@link IntentExecutionOrchestrator#execute}, 只桩掉识别、Skill 路由与 LLM。
 */
@DisplayName("IntentExecutionOrchestrator — 自由提问走到工作台意图也出脱敏摘要")
class IntentExecutionOrchestratorFreeTextWorkdeskTest {

    private static final String FACTORY = "F006";
    private static final long USER = 1309L;
    private static final String ROLE = "factory_super_admin";
    private static final String QUESTION = "今天要跟进哪些客户";

    private AIIntentService aiIntentService;
    private DynamicToolSelectionService dynamic;
    private DashScopeClient llm;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        dynamic = mock(DynamicToolSelectionService.class);
        llm = mock(DashScopeClient.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
                llm,
                mock(DashScopeConfig.class),
                mock(IntentKnowledgeBase.class),
                mock(AIAnalysisResultRepository.class),
                mock(ToolRegistry.class),
                mock(AnalysisRouterService.class),
                mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class),
                mock(AgenticRAGRouterService.class),
                mock(ResultValidatorService.class),
                mock(ToolDispatchService.class),
                dynamic,
                mock(QueryPreprocessorService.class));
        ReflectionTestUtils.setField(orchestrator, "writeGuardService", new WriteGuardService());
        BusinessTypeGate businessTypeGate = mock(BusinessTypeGate.class);
        when(businessTypeGate.check(any(), any())).thenReturn(Optional.empty());
        ReflectionTestUtils.setField(orchestrator, "businessTypeGate", businessTypeGate);

        FactoryNumberProvenance gate = new FactoryNumberProvenance();
        WorkdeskOutputSummarizer summarizer = new WorkdeskOutputSummarizer(llm, new ObjectMapper());
        ReflectionTestUtils.setField(summarizer, "enabled", true);
        ReflectionTestUtils.setField(summarizer, "maxChars", 800);
        ReflectionTestUtils.setField(summarizer, "numberProvenance", gate);
        ReflectionTestUtils.setField(orchestrator, "workdeskOutputSummarizer", summarizer);
        ReflectionTestUtils.setField(orchestrator, "numberProvenance", gate);
    }

    private static AIIntentConfig workdeskIntent() {
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("DAILY_CUSTOMER_FOLLOWUP");
        intent.setIntentName("今日客户跟进");
        intent.setIntentCategory("WORKDESK");
        intent.setSensitivityLevel("LOW");
        return intent; // tool_name = null → Skill 分支
    }

    /** 线上 Skill 组合结果的形状: 子工具结果是 JsonNode, 所以 formatSkillResult 落到占位文字。 */
    private static IntentExecuteResponse skillResponseAsInProduction() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customer_priority_query", mapper.readTree(
                "{\"customers\":[{\"customerName\":\"叮咚-上海松江冷藏仓\",\"contactPerson\":\"侍总\","
                        + "\"priorityScore\":40}],\"count\":7}"));
        data.put("_toolCount", 5);
        IntentExecuteResponse r = new IntentExecuteResponse();
        r.setIntentRecognized(true);
        r.setIntentCode("DAILY_CUSTOMER_FOLLOWUP");
        r.setIntentName("今日客户跟进");
        r.setIntentCategory("WORKDESK");
        r.setStatus("SUCCESS");
        r.setMessage(DynamicToolSelectionService.SKILL_NO_DISPLAY_PLACEHOLDER);
        r.setFormattedText(DynamicToolSelectionService.SKILL_NO_DISPLAY_PLACEHOLDER);
        r.setResultData(data);
        return r;
    }

    @Test
    @DisplayName("自由提问 → [Branch:Skill] → 模型只看到代号, 用户看到真名摘要而不是通用空话")
    void freeTextWorkdeskQuestionGetsARedactedSummary() throws Exception {
        AIIntentConfig intent = workdeskIntent();
        when(aiIntentService.recognizeIntentWithConfidence(eq(QUESTION), eq(FACTORY), anyInt(), any(), any(), any(),
                any(), any()))
                .thenReturn(IntentMatchResult.builder()
                        .bestMatch(intent)
                        .confidence(0.9)
                        .matchMethod(IntentMatchResult.MatchMethod.LLM)
                        .requiresConfirmation(false)
                        .build());
        when(aiIntentService.hasPermission("DAILY_CUSTOMER_FOLLOWUP", ROLE)).thenReturn(true);
        when(dynamic.isSkillsEnabled()).thenReturn(true);
        when(dynamic.tryExplicitSkillRouteForIntent(eq(intent), eq(QUESTION), eq(FACTORY), eq(USER), eq(ROLE)))
                .thenReturn(skillResponseAsInProduction());
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(anyString(), sent.capture()))
                .thenReturn("今日共 7 个客户需要跟进, 优先: [客户A] (联系人 [人员A], 评分 40)。");

        IntentExecuteResponse response = orchestrator.execute(FACTORY,
                IntentExecuteRequest.builder().userInput(QUESTION).build(), USER, ROLE);

        verify(dynamic).tryExplicitSkillRouteForIntent(eq(intent), eq(QUESTION), eq(FACTORY), eq(USER), eq(ROLE));
        assertThat(sent.getValue()).doesNotContain("叮咚-上海松江冷藏仓", "侍总").contains("[客户A]", "[人员A]");
        assertThat(response.getFormattedText())
                .isNotEqualTo(DynamicToolSelectionService.SKILL_NO_DISPLAY_PLACEHOLDER)
                .contains("叮咚-上海松江冷藏仓", "侍总", "评分 40")
                .doesNotContain("[客户A]", "[人员A]");
    }

    /**
     * 2026-10-25 17:54 线上: 模型自己数出「6 位 VIP」, 数字出处闸退回结构化结果, 而那个结果只有
     * 「客户跟进: 2 个字段」「wechat record recent query: 2 个字段」。退回时也要列出客户。
     */
    @Test
    @DisplayName("模型摘要被数字出处闸拒绝时, 退回的本地摘要列出客户名单, 不报「N 个字段」、不漏英文工具名")
    void provenanceFallbackListsTheCustomers() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customer_priority_query", mapper.readTree(
                "{\"customers\":[{\"customerName\":\"叮咚-上海松江冷藏仓\",\"contactPerson\":\"侍总\","
                        + "\"importanceDisplay\":\"VIP\",\"priorityScore\":40},"
                        + "{\"customerName\":\"叮咚-台州临海冷藏仓\",\"importanceDisplay\":\"VIP\",\"priorityScore\":40}],"
                        + "\"count\":2}"));
        data.put("wechat_record_recent_query", mapper.readTree("{\"records\":[],\"count\":0}"));
        data.put("_toolCount", 2);
        IntentExecuteResponse skill = skillResponseAsInProduction();
        skill.setResultData(data);

        AIIntentConfig intent = workdeskIntent();
        when(aiIntentService.recognizeIntentWithConfidence(eq(QUESTION), eq(FACTORY), anyInt(), any(), any(), any(),
                any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(intent).confidence(0.9)
                        .matchMethod(IntentMatchResult.MatchMethod.LLM).requiresConfirmation(false).build());
        when(aiIntentService.hasPermission("DAILY_CUSTOMER_FOLLOWUP", ROLE)).thenReturn(true);
        when(dynamic.isSkillsEnabled()).thenReturn(true);
        when(dynamic.tryExplicitSkillRouteForIntent(eq(intent), eq(QUESTION), eq(FACTORY), eq(USER), eq(ROLE)))
                .thenReturn(skill);
        // 「6 个」在数据里找不到出处 → 闸拒绝 (「前 6 位」这种窗口说法闸会放过, 不能拿来测)
        when(llm.chatFast(anyString(), anyString())).thenReturn("其中 6 个是 VIP 客户, 优先跟进 [客户A]。");

        IntentExecuteResponse response = orchestrator.execute(FACTORY,
                IntentExecuteRequest.builder().userInput(QUESTION).build(), USER, ROLE);

        assertThat(response.getFormattedText())
                .contains(WorkdeskOutputSummarizer.PROVENANCE_FALLBACK_HEADER.trim())
                .contains("重点客户", "叮咚-上海松江冷藏仓（联系人 侍总，VIP，评分 40）", "叮咚-台州临海冷藏仓", "微信沟通记录")
                .doesNotContain("个字段", "wechat record", "6 个是");
    }

    @Test
    @DisplayName("占位文字只换 formattedText: message 不换成带真名的摘要 (它会进对话记忆)")
    void placeholderOnlyReplacesFormattedText() throws Exception {
        AIIntentConfig intent = workdeskIntent();
        when(aiIntentService.recognizeIntentWithConfidence(eq(QUESTION), eq(FACTORY), anyInt(), any(), any(), any(),
                any(), any()))
                .thenReturn(IntentMatchResult.builder().bestMatch(intent).confidence(0.9)
                        .matchMethod(IntentMatchResult.MatchMethod.LLM).requiresConfirmation(false).build());
        when(aiIntentService.hasPermission("DAILY_CUSTOMER_FOLLOWUP", ROLE)).thenReturn(true);
        when(dynamic.isSkillsEnabled()).thenReturn(true);
        when(dynamic.tryExplicitSkillRouteForIntent(eq(intent), eq(QUESTION), eq(FACTORY), eq(USER), eq(ROLE)))
                .thenReturn(skillResponseAsInProduction());
        when(llm.chatFast(anyString(), anyString())).thenReturn("今日共 7 个客户需要跟进, 优先: [客户A]。");

        IntentExecuteResponse response = orchestrator.execute(FACTORY,
                IntentExecuteRequest.builder().userInput(QUESTION).build(), USER, ROLE);

        assertThat(response.getFormattedText()).contains("叮咚-上海松江冷藏仓");
        assertThat(response.getMessage()).doesNotContain("叮咚-上海松江冷藏仓");
    }
}
