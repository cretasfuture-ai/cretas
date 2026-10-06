package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.grounding.FactoryNumberProvenance;
import com.cretas.aims.config.IndustryKnowledgeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.AnalysisContext;
import com.cretas.aims.dto.ai.AnalysisResult;
import com.cretas.aims.dto.ai.AnalysisTopic;
import com.cretas.aims.service.KnowledgeFeedbackService;
import com.cretas.aims.service.RetrievalEvaluatorService;
import com.cretas.aims.service.ToolRouterService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 两条「分析」路径上的数字出处闸。这两条路径都<b>没有查任何工厂数据</b>
 * (检索只取行业知识, 数据上下文是占位符), 所以模型能合法引用的只有提示词里的
 * 行业知识与用户问题 —— 正文里关于「本厂」的具体数字都是它写出来的。
 */
class AnalysisNumberProvenanceWiringTest {

    private static final String KNOWLEDGE = "食品生产行业关键指标：\n- 良品率：行业标准 ≥95%，优秀 ≥98%\n";

    private AnalysisContext context() {
        return AnalysisContext.builder()
                .userInput("分析一下我们工厂的良品率情况")
                .topic(AnalysisTopic.PRODUCT_STATUS)
                .factoryId("F006")
                .userId(1L)
                .sessionId("s-1")
                .enableThinking(false)
                .build();
    }

    @Test
    void analysisRouterKeepsKnowledgeNumbersAndDropsInventedFactoryFigures() {
        PythonLLMClient llm = mock(PythonLLMClient.class);
        IndustryKnowledgeConfig knowledge = mock(IndustryKnowledgeConfig.class);
        when(knowledge.getKnowledgeForTopic(anyString())).thenReturn(KNOWLEDGE);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "行业标准良品率为 95%，优秀水平 98%。\n贵厂本月良品率为 91.3%，低于行业标准。\n建议排查原料与设备状态。");
        AnalysisRouterServiceImpl router = new AnalysisRouterServiceImpl(
                mock(ToolRouterService.class), llm, knowledge, mock(IntentKnowledgeBase.class));
        FactoryNumberProvenance gate = new FactoryNumberProvenance();
        ReflectionTestUtils.setField(router, "numberProvenance", gate);

        AnalysisResult result = router.executeAnalysis(context());

        assertThat(result.getFormattedAnalysis())
                .contains("95%").contains("98%").doesNotContain("91.3")
                .contains("建议排查原料与设备状态");
        assertThat(gate.total(AnalysisRouterServiceImpl.PROVENANCE_SITE)).isEqualTo(1);
    }

    @Test
    void agentOrchestratorDropsInventedFactoryFigures() {
        DashScopeClient llm = mock(DashScopeClient.class);
        IndustryKnowledgeConfig knowledge = mock(IndustryKnowledgeConfig.class);
        when(knowledge.getKnowledgeForTopic(anyString())).thenReturn(KNOWLEDGE);
        when(llm.chat(anyString(), anyString())).thenReturn(
                "行业优秀水平为 98%，贵厂良品率稳定，建议继续保持现有生产节奏并关注原料质量波动。\n"
                        + "贵厂上月良品率 92.6%，比行业标准低 2.4 个百分点。\n"
                        + "建议每周复盘一次不良品原因并形成整改清单，持续跟踪改进效果。");
        AgentOrchestratorImpl agents = new AgentOrchestratorImpl(
                mock(RetrievalEvaluatorService.class), mock(KnowledgeFeedbackService.class), knowledge, llm);
        ReflectionTestUtils.setField(agents, "complexityThreshold", 0.6);
        FactoryNumberProvenance gate = new FactoryNumberProvenance();
        ReflectionTestUtils.setField(agents, "numberProvenance", gate);

        AnalysisResult result = agents.executeCollaborativeAnalysis(context());

        assertThat(result.getFormattedAnalysis())
                .contains("98%").doesNotContain("92.6").doesNotContain("2.4");
        assertThat(gate.total(AgentOrchestratorImpl.PROVENANCE_SITE)).isGreaterThanOrEqualTo(1);
    }
}
