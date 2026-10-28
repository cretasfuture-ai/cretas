package com.cretas.aims.service.smartbi;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.dto.smartbi.AIInsight;
import com.cretas.aims.dto.smartbi.DashboardResponse;
import com.cretas.aims.dto.smartbi.KPICard;
import com.cretas.aims.dto.smartbi.RankingItem;
import com.cretas.aims.entity.smartbi.SmartBiChartTemplate;
import com.cretas.aims.repository.smartbi.SmartBiAnalysisCacheRepository;
import com.cretas.aims.repository.smartbi.SmartBiBillingConfigRepository;
import com.cretas.aims.repository.smartbi.SmartBiChartTemplateRepository;
import com.cretas.aims.repository.smartbi.SmartBiQueryHistoryRepository;
import com.cretas.aims.repository.smartbi.SmartBiSalesDataRepository;
import com.cretas.aims.repository.smartbi.SmartBiUsageRecordRepository;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.smartbi.impl.ChartTemplateServiceImpl;
import com.cretas.aims.service.smartbi.impl.SmartBIServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SmartBI 的两条分析外发: 图表模板 AI 分析 (聚合数据) 与驾驶舱 LLM 洞察 (KPI + 排名)。
 * 客户 / 业务员 / 供应商名与保密产品名不发给模型, 用户看到的是真名。走各自的公开入口。
 */
class SmartBiAnalysisEgressRedactionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private LlmEgressGuard guard() {
        ProcessSecretSampleFilter filter = mock(ProcessSecretSampleFilter.class);
        when(filter.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));
        return new LlmEgressGuard(mapper, filter);
    }

    @Test
    void chartTemplateAnalysisIsRedactedAndRestored() {
        SmartBiChartTemplateRepository repo = mock(SmartBiChartTemplateRepository.class);
        SmartBiChartTemplate template = new SmartBiChartTemplate();
        template.setTemplateCode("sales_by_customer");
        template.setChartType("bar");
        template.setIsActive(true);
        template.setAnalysisEnabled(true);
        when(repo.findByTemplateCodeAndFactoryId("sales_by_customer", "F006")).thenReturn(Optional.of(template));
        PythonLLMClient llm = mock(PythonLLMClient.class);
        when(llm.isAvailable()).thenReturn(true);
        AnalysisPromptGenerator prompts = mock(AnalysisPromptGenerator.class);
        when(prompts.generatePrompt(any())).thenReturn(AnalysisPromptGenerator.GeneratedPrompt.builder()
                .systemPrompt("你是分析师")
                .userPrompt("客户销售: 张记食品有限公司 12800 元 (业务员：周晓明 13912345678); 秘制酱牛肉 300 kg")
                .templateType(AnalysisPromptGenerator.AnalysisTemplateType.values()[0]).language("zh").build());
        ChartTemplateServiceImpl service = new ChartTemplateServiceImpl(repo, mapper, llm, prompts);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard());
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), user.capture())).thenAnswer(inv -> {
            String p = inv.getArgument(1);
            return p.replaceAll("(?s).*?(\\[(客户|单位)[A-Z]+]).*", "$1") + " 贡献最大";
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("customerName", "张记食品有限公司");
        data.put("amount", 12800);
        data.put("analysisFacts", Map.of("unitValues", List.of("kg"), "explicitWasteAmounts", List.of(100D)));

        Map<String, Object> chart = service.buildChartWithAnalysis("sales_by_customer", data, "F006");

        assertThat(((Map<?, ?>) chart.get("data")).containsKey("analysisFacts")).isFalse();
        assertThat(user.getValue()).doesNotContain("张记食品", "周晓明", "13912345678", "秘制酱牛肉").contains("12800");
        assertThat(chart.get("aiAnalysis")).isEqualTo("张记食品有限公司 贡献最大");

        // 查保密产品名失败: 不发, 分析栏给明确提示
        ProcessSecretSampleFilter failing = mock(ProcessSecretSampleFilter.class);
        when(failing.protectedProductNames("F006")).thenThrow(new IllegalStateException("db down"));
        ReflectionTestUtils.setField(service, "llmEgressGuard", new LlmEgressGuard(mapper, failing));
        PythonLLMClient silent = mock(PythonLLMClient.class);
        when(silent.isAvailable()).thenReturn(true);
        ReflectionTestUtils.setField(service, "pythonLLMClient", silent);

        Map<String, Object> refused = service.buildChartWithAnalysis("sales_by_customer", data, "F006");

        org.mockito.Mockito.verify(silent, org.mockito.Mockito.never()).chat(anyString(), anyString());
        assertThat(String.valueOf(refused.get("aiAnalysis"))).contains("未发给 AI");
    }

    @Test
    void naturalLanguageQueryAnswerHidesRankedNamesAndRestoresThem() {
        PythonLLMClient llm = mock(PythonLLMClient.class);
        SalesAnalysisService sales = mock(SalesAnalysisService.class);
        when(sales.getSalespersonRanking(eq("F006"), any(), any())).thenReturn(List.of(
                RankingItem.builder().name("周晓明").value(new BigDecimal("12800")).build()));
        SmartBIIntentService intents = mock(SmartBIIntentService.class);
        when(intents.recognizeIntent(anyString())).thenReturn(com.cretas.aims.dto.smartbi.IntentResult.builder()
                .intent(com.cretas.aims.entity.smartbi.enums.SmartBIIntent.QUERY_SALES_RANKING).confidence(0.9).build());
        SmartBiUsageRecordRepository usage = mock(SmartBiUsageRecordRepository.class);
        when(usage.countTodayUsage(eq("F006"), any(LocalDate.class))).thenReturn(0L);
        SmartBiBillingConfigRepository billing = mock(SmartBiBillingConfigRepository.class);
        when(billing.findByFactoryId("F006")).thenReturn(Optional.empty());
        SmartBIServiceImpl service = new SmartBIServiceImpl(sales, mock(DepartmentAnalysisService.class),
                mock(RegionAnalysisService.class), mock(FinanceAnalysisService.class), intents,
                mock(SmartBIPromptService.class), mock(RecommendationService.class),
                mock(SmartBiAnalysisCacheRepository.class), usage, billing, mock(SmartBiQueryHistoryRepository.class),
                mock(SmartBiSalesDataRepository.class), mapper);
        ReflectionTestUtils.setField(service, "llmInsightEnabled", true);
        ReflectionTestUtils.setField(service, "pythonLLMClient", llm);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard());
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        when(llm.chatCompletion(sent.capture())).thenAnswer(inv -> {
            String p = String.valueOf(inv.getArgument(0, ChatCompletionRequest.class).getMessages().get(1).getContent());
            String person = p.replaceAll("(?s).*?(\\[(人员|名称)[A-Z]+]).*", "$1");
            ChatCompletionResponse r = new ChatCompletionResponse();
            ChatCompletionResponse.Message m = new ChatCompletionResponse.Message();
            m.setContent("本月业绩第一是 " + person + "，销售额 12800 元，建议请他分享客户跟进经验。");
            ChatCompletionResponse.Choice c = new ChatCompletionResponse.Choice();
            c.setMessage(m);
            r.setChoices(List.of(c));
            return r;
        });

        var response = service.processQuery("F006", 7L, com.cretas.aims.dto.smartbi.NLQueryRequest.builder()
                .query("周晓明 这个月卖得怎么样, 他电话 13812345678").build());

        String prompt = String.valueOf(sent.getValue().getMessages().get(1).getContent());
        assertThat(prompt).doesNotContain("周晓明", "13812345678").contains("12800");
        assertThat(response.getResponseText()).contains("本月业绩第一是 周晓明").doesNotContain("[人员", "[名称");
    }

    @Test
    void dashboardInsightsHideRankedSalespeopleCustomersAndSuppliers() {
        PythonLLMClient llm = mock(PythonLLMClient.class);
        RecommendationService recommendations = mock(RecommendationService.class);
        when(recommendations.generateInsightSummary(any())).thenReturn(List.of());
        SmartBiUsageRecordRepository usage = mock(SmartBiUsageRecordRepository.class);
        when(usage.countTodayUsage(eq("F006"), any(LocalDate.class))).thenReturn(0L);
        SmartBiBillingConfigRepository billing = mock(SmartBiBillingConfigRepository.class);
        when(billing.findByFactoryId("F006")).thenReturn(Optional.empty());
        SmartBIServiceImpl service = new SmartBIServiceImpl(mock(SalesAnalysisService.class),
                mock(DepartmentAnalysisService.class), mock(RegionAnalysisService.class),
                mock(FinanceAnalysisService.class), mock(SmartBIIntentService.class), mock(SmartBIPromptService.class),
                recommendations, mock(SmartBiAnalysisCacheRepository.class), usage, billing,
                mock(SmartBiQueryHistoryRepository.class), mock(SmartBiSalesDataRepository.class), mapper);
        ReflectionTestUtils.setField(service, "llmInsightEnabled", true);
        ReflectionTestUtils.setField(service, "pythonLLMClient", llm);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard());
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        when(llm.chatCompletion(sent.capture())).thenAnswer(inv -> {
            String p = String.valueOf(inv.getArgument(0, ChatCompletionRequest.class).getMessages().get(1).getContent());
            String person = p.replaceAll("(?s).*?(\\[人员[A-Z]+]).*", "$1");
            ChatCompletionResponse r = new ChatCompletionResponse();
            ChatCompletionResponse.Message m = new ChatCompletionResponse.Message();
            m.setContent("[{\"level\":\"YELLOW\",\"category\":\"销售\",\"message\":\"" + person + " 业绩下滑\","
                    + "\"relatedEntity\":\"" + person + "\",\"actionSuggestion\":\"跟进\"}]");
            ChatCompletionResponse.Choice c = new ChatCompletionResponse.Choice();
            c.setMessage(m);
            r.setChoices(List.of(c));
            return r;
        });
        Map<String, List<RankingItem>> rankings = new LinkedHashMap<>();
        rankings.put("salesperson", List.of(RankingItem.builder().name("周晓明").value(new BigDecimal("12800")).build()));
        rankings.put("overdue_customers", List.of(RankingItem.builder().name("老街坊").value(new BigDecimal("500")).build()));
        rankings.put("supplier", List.of(RankingItem.builder().name("华东冷链").value(new BigDecimal("9")).build()));
        DashboardResponse dashboard = DashboardResponse.builder()
                .kpiCards(List.of(KPICard.builder().title("销售额").value("12800").build()))
                .rankings(rankings).build();

        List<AIInsight> insights = service.generateAIInsights("F006", dashboard);

        String prompt = String.valueOf(sent.getValue().getMessages().get(1).getContent());
        assertThat(prompt).doesNotContain("周晓明", "老街坊", "华东冷链").contains("12800");
        assertThat(insights).extracting(AIInsight::getMessage).containsExactly("周晓明 业绩下滑");
    }
}
