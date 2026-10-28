package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.config.IntentKnowledgeBase.QuestionType;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.repository.AIAnalysisResultRepository;
import com.cretas.aims.service.*;
import com.cretas.aims.service.processsecret.ProcessSecretAccessService;
import com.cretas.aims.service.processsecret.ProcessSecretAggregateExclusions;
import com.cretas.aims.service.processsecret.ProcessSecretLlmCostScope;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static com.cretas.aims.service.processsecret.ProcessSecretLlmFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 复审 H1: 存档的日 / 周 / 月报 (全厂共享, 经营报告快照里有保密产品的毛利 / 成本) 拼进 GENERAL_QUESTION 的系统提示词。
 * 当前账号对本厂保密产品无完整查看权时不拼; 负责人照常拼, 并带工艺保密系统约束。走真实入口 generateConversationalResponse,
 * 断言打在发给模型的系统提示词上。
 */
class IntentExecutionOrchestratorProcessSecretReportTest {

    /** 存档报告里保密产品的毛利 / 成本。 */
    private static final String REPORT = "经营快照: " + SECRET_NAME + " 毛利 2222.22, 成本 7777.77";

    private DashScopeClient llm;
    private AIAnalysisResultRepository analysisResults;
    private IntentExecutionOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        analysisResults = mock(AIAnalysisResultRepository.class);
        orchestrator = new IntentExecutionOrchestrator(
                mock(AIIntentService.class), mock(IntentSemanticsParser.class), mock(SemanticCacheService.class),
                mock(RuleEngineService.class), mock(ConversationService.class), mock(ConversationMemoryService.class),
                new ObjectMapper(), llm, mock(DashScopeConfig.class), mock(IntentKnowledgeBase.class), analysisResults,
                mock(ToolRegistry.class), mock(AnalysisRouterService.class), mock(ComplexityRouter.class),
                mock(AgentOrchestrator.class), mock(AgenticRAGRouterService.class), mock(ResultValidatorService.class),
                mock(ToolDispatchService.class), mock(DynamicToolSelectionService.class),
                mock(QueryPreprocessorService.class));
        AIAnalysisResult report = new AIAnalysisResult();
        report.setAnalysisText(REPORT);
        when(analysisResults.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(FACTORY), eq("daily"), any())).thenReturn(Optional.of(report));
        when(analysisResults.findFirstByFactoryIdAndReportTypeAndExpiresAtAfterOrderByCreatedAtDesc(
                eq(FACTORY), eq("weekly"), any())).thenReturn(Optional.empty());
        when(llm.chat(anyString(), anyString())).thenReturn("建议关注成本。");
    }

    private String systemPromptSent(ProcessSecretLlmCostScope scope) {
        ReflectionTestUtils.setField(orchestrator, "llmCostScope", scope);
        orchestrator.generateConversationalResponse(FACTORY, "最近经营怎么样", QuestionType.GENERAL_QUESTION, false, null);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(llm).chat(system.capture(), anyString());   // 一定会发生: 模型被调用了, 读的是它真正收到的系统提示词
        return system.getValue();
    }

    @Test
    void unauthorizedCallersInAProtectedFactoryDoNotGetTheStoredReport() {
        String system;
        try (var ignored = as(sameFactoryUser())) {
            system = systemPromptSent(protectedScope());
        }

        assertThat(system).doesNotContain("2222.22", "7777.77", "最新运营分析报告");
    }

    @Test
    void anUnknownCallerIsTreatedAsUnauthorized() {
        assertThat(systemPromptSent(protectedScope())).doesNotContain("2222.22", "7777.77");
    }

    @Test
    void theOwnerGetsTheStoredReportPlusTheHarnessInstruction() {
        String system;
        try (var ignored = as(owner())) {
            system = systemPromptSent(protectedScope());
        }

        assertThat(system).contains("2222.22", "7777.77", ProcessSecretLlmCostScope.HARNESS_INSTRUCTION);
    }

    @Test
    void negativeControlAFactoryWithoutProtectedProductsStillGetsTheReportWithoutHarness() {
        assertThat(systemPromptSent(unprotectedScope())).contains("2222.22", "最新运营分析报告")
                .doesNotContain("工艺保密约束");
    }

    @Test
    void scopeLookupFailureMeansNoStoredReport() {
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class);
        when(access.protectedProductTypeIds(anyString())).thenThrow(new IllegalStateException("db down"));
        ProcessSecretLlmCostScope broken = new ProcessSecretLlmCostScope(new ProcessSecretAggregateExclusions(access,
                mock(com.cretas.aims.repository.ProductionPlanRepository.class),
                mock(com.cretas.aims.repository.bom.BomRecipeRepository.class)), access);

        assertThat(systemPromptSent(broken)).doesNotContain("2222.22");
    }
}
