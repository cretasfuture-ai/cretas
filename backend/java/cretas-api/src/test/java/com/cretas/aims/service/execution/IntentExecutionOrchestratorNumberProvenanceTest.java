package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.WriteGuardService;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.config.AIIntentConfig;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 数字出处闸在<b>编排器真实入口</b>上的接线: 工作台 Skill 走显式意图 → 结果格式化 →
 * formattedText 兜底 → {@link WorkdeskOutputSummarizer} (真实例, 只桩掉 LLM) → 闸。
 * 以及带经营报告上下文的咨询回答。
 */
@DisplayName("IntentExecutionOrchestrator — 数字出处闸接线")
class IntentExecutionOrchestratorNumberProvenanceTest {

    private static final String FACTORY = "F006";
    private static final long USER = 22L;
    private static final String ROLE = "factory_super_admin";

    private AIIntentService aiIntentService;
    private DynamicToolSelectionService dynamic;
    private DashScopeClient llm;
    private AIAnalysisResultRepository analysisResults;
    private IntentExecutionOrchestrator orchestrator;
    private FactoryNumberProvenance gate;

    @BeforeEach
    void setUp() {
        aiIntentService = mock(AIIntentService.class);
        dynamic = mock(DynamicToolSelectionService.class);
        llm = mock(DashScopeClient.class);
        analysisResults = mock(AIAnalysisResultRepository.class);
        DashScopeConfig dashScopeConfig = mock(DashScopeConfig.class);
        orchestrator = new IntentExecutionOrchestrator(
                aiIntentService,
                mock(IntentSemanticsParser.class),
                mock(SemanticCacheService.class),
                mock(RuleEngineService.class),
                mock(ConversationService.class),
                mock(ConversationMemoryService.class),
                new ObjectMapper(),
                llm,
                dashScopeConfig,
                mock(IntentKnowledgeBase.class),
                analysisResults,
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

        gate = new FactoryNumberProvenance();
        WorkdeskOutputSummarizer summarizer = new WorkdeskOutputSummarizer(llm, new ObjectMapper());
        ReflectionTestUtils.setField(summarizer, "enabled", true);
        ReflectionTestUtils.setField(summarizer, "maxChars", 800);
        ReflectionTestUtils.setField(summarizer, "numberProvenance", gate);
        ReflectionTestUtils.setField(orchestrator, "workdeskOutputSummarizer", summarizer);
        ReflectionTestUtils.setField(orchestrator, "numberProvenance", gate);
    }

    private AIIntentConfig workdeskIntent() {
        AIIntentConfig intent = new AIIntentConfig();
        intent.setIntentCode("DAILY_CUSTOMER_FOLLOWUP");
        intent.setIntentName("今日客户跟进");
        intent.setIntentCategory("WORKDESK");
        intent.setSensitivityLevel("LOW");
        return intent; // tool_name = null → 显式 Skill 路由
    }

    /** Skill 的真实返回形状: 子工具产出 + 下划线元数据, formattedText 是那条裸模板。 */
    private IntentExecuteResponse skillResponse() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customer_priority_query", Map.of("count", 16, "message", "共 16 个客户已按优先级排序"));
        data.put("customer_revenue_trend", Map.of("total", new BigDecimal("611355.40")));
        data.put("_toolCount", 5);
        data.put("_executionOrder", "parallel");
        String bare = "查询完成\n包含 5 项数据指标 — 详情请查看下方数据卡片或对应报表模块。";
        IntentExecuteResponse r = new IntentExecuteResponse();
        r.setIntentRecognized(true);
        r.setIntentCode("DAILY_CUSTOMER_FOLLOWUP");
        r.setIntentName("今日客户跟进");
        r.setStatus("SUCCESS");
        r.setMessage(bare);
        r.setFormattedText(bare);
        r.setResultData(data);
        return r;
    }

    private IntentExecuteResponse runWorkdesk(String llmSummary) {
        AIIntentConfig intent = workdeskIntent();
        when(aiIntentService.getIntentByCode(FACTORY, "DAILY_CUSTOMER_FOLLOWUP")).thenReturn(Optional.of(intent));
        when(aiIntentService.hasPermission("DAILY_CUSTOMER_FOLLOWUP", ROLE)).thenReturn(true);
        when(dynamic.tryExplicitSkillRouteForIntent(eq(intent), any(), eq(FACTORY), eq(USER), eq(ROLE)))
                .thenReturn(skillResponse());
        when(llm.chatFast(anyString(), anyString())).thenReturn(llmSummary);
        return orchestrator.executeWithExplicitIntent(FACTORY,
                IntentExecuteRequest.builder().userInput("今天跟谁").intentCode("DAILY_CUSTOMER_FOLLOWUP").build(),
                USER, ROLE);
    }

    @Test
    @DisplayName("(a) 模型摘要只引用工具数 → 用户看到的就是摘要")
    void workdeskSummaryWithToolNumbersReachesUser() {
        String summary = "今日有 16 个客户需要跟进, 近 30 天客户收入合计约 61.14 万元.";

        IntentExecuteResponse response = runWorkdesk(summary);

        assertThat(response.getFormattedText()).isEqualTo(summary);
        assertThat(gate.total(WorkdeskOutputSummarizer.PROVENANCE_SITE)).isEqualTo(1);
    }

    @Test
    @DisplayName("(b) 模型摘要编了一个数 → 用户看到的是只含工具数的结构化摘要")
    void workdeskSummaryWithInventedNumberNeverReachesUser() {
        IntentExecuteResponse response = runWorkdesk("今日有 16 个客户需要跟进, 销售额比上月增长 43568.21%.");

        assertThat(response.getFormattedText()).doesNotContain("43568");
        assertThat(response.getMessage()).doesNotContain("43568");
        // 退回的确定性摘要带着工具算出的客户数 (措辞随工具消息, 只钉数字本身)
        assertThat(response.getFormattedText()).containsPattern("(?<!\\d)16(?!\\d)");
        assertThat(gate.count(WorkdeskOutputSummarizer.PROVENANCE_SITE,
                FactoryNumberProvenance.Decision.FALLBACK_TEMPLATE)).isEqualTo(1);
    }

    private void stubDailyReport(String analysisText) {
        AIAnalysisResult report = new AIAnalysisResult();
        report.setAnalysisText(analysisText);
        when(analysisResults.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(FACTORY), eq("daily"), any())).thenReturn(Optional.of(report));
        when(analysisResults.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(FACTORY), eq("weekly"), any())).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("带经营报告的咨询回答: 报告里的数放行, 编造的那一句删掉")
    void conversationalAnswerOverReportDropsInventedSentence() {
        stubDailyReport("{\"metrics\":[{\"id\":\"revenue\",\"value\":12345.6},{\"id\":\"yield\",\"value\":0.962}]}");
        when(llm.chat(anyString(), anyString()))
                .thenReturn("今日营收 ¥12,345.60，良品率 96.2%。\n预计本月利润将增长 18.5%。\n建议继续保持。");

        String answer = orchestrator.generateConversationalResponse(
                FACTORY, "最近经营怎么样", QuestionType.GENERAL_QUESTION, false, null);

        assertThat(answer).contains("¥12,345.60").contains("96.2%").doesNotContain("18.5");
        assertThat(gate.count(IntentExecutionOrchestrator.CONVERSATIONAL_PROVENANCE_SITE,
                FactoryNumberProvenance.Decision.REDACTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("故意不挂闸的那一支: 没有任何数据进模型的闲聊, 原样返回且闸没被调用")
    void conversationalWithoutDataIsDeliberatelyNotGated() {
        when(analysisResults.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                anyString(), anyString(), any())).thenReturn(Optional.empty());
        when(llm.chat(anyString(), anyString())).thenReturn("冷藏建议控制在 0-4℃。");

        String answer = orchestrator.generateConversationalResponse(
                FACTORY, "冷藏温度多少合适", QuestionType.GENERAL_QUESTION, false, null);

        assertThat(answer).isEqualTo("冷藏建议控制在 0-4℃。");
        assertThat(gate.total(IntentExecutionOrchestrator.CONVERSATIONAL_PROVENANCE_SITE)).isZero();
    }
}
