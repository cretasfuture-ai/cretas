package com.cretas.aims.service.calibration;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.entity.calibration.ReflectionMemory;
import com.cretas.aims.repository.calibration.ReflectionMemoryRepository;
import com.cretas.aims.service.calibration.impl.CorrectionAgentServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 纠错 Agent: 原话 / 工具参数 / 报错里的客户名、人名、电话不发给模型;
 * 重试用的修正参数是真值, 存进反思库 (按工具名跨会话、跨厂复用) 的那份不带真名。
 * 走真实入口 {@link CorrectionAgentServiceImpl#analyzeAndCorrect}。
 */
class CorrectionAgentEgressRedactionTest {

    @Test
    void correctionPromptIsRedactedRetryParamsAreRealAndStoredReflectionIsNot() {
        DashScopeClient llm = mock(DashScopeClient.class);
        DashScopeConfig config = mock(DashScopeConfig.class);
        when(config.getCorrectionModel()).thenReturn("qwen-turbo");
        ReflectionMemoryRepository reflections = mock(ReflectionMemoryRepository.class);
        when(reflections.findByToolNameOrderByCreatedAtDesc(anyString())).thenReturn(List.of());
        CorrectionAgentServiceImpl agent = new CorrectionAgentServiceImpl(llm, config, reflections, new ObjectMapper());
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        when(llm.chatCompletion(sent.capture())).thenAnswer(inv -> {
            String prompt = String.valueOf(inv.getArgument(0, ChatCompletionRequest.class).getMessages().get(0).getContent());
            String customer = prompt.replaceAll("(?s).*?(\\[(客户|单位)[A-Z]+]).*", "$1");
            ChatCompletionResponse r = mock(ChatCompletionResponse.class);
            when(r.getContent()).thenReturn("{\"errorAnalysis\":\"客户名带了后缀\",\"correctionStrategy\":\"RETRY\","
                    + "\"confidence\":0.9,\"reflection\":\"" + customer + " 应去掉空格\","
                    + "\"correctedParams\":{\"customerName\":\"" + customer + "\"}}");
            return r;
        });

        CorrectionAgentService.CorrectionResult result = agent.analyzeAndCorrect(
                "查张记食品有限公司的应收, 联系人：王建国 13812345678", "customer_receivable_query",
                Map.of("customerName", "张记食品有限公司 ", "contactPhone", "13812345678"),
                "找不到客户 张记食品有限公司", null, 1);

        String prompt = String.valueOf(sent.getValue().getMessages().get(0).getContent());
        assertThat(prompt).doesNotContain("张记食品", "王建国", "13812345678");
        assertThat(result.correctedParams()).containsEntry("customerName", "张记食品有限公司");
        ArgumentCaptor<ReflectionMemory> saved = ArgumentCaptor.forClass(ReflectionMemory.class);
        verify(reflections).save(saved.capture());
        assertThat(saved.getValue().getReflectionContent() + saved.getValue().getOriginalError()
                + saved.getValue().getCorrectedParams()).doesNotContain("张记食品");
    }

    @Test
    void historicalReflectionsFromOtherSessionsAreRedactedBeforeSending() {
        DashScopeClient llm = mock(DashScopeClient.class);
        DashScopeConfig config = mock(DashScopeConfig.class);
        ReflectionMemoryRepository reflections = mock(ReflectionMemoryRepository.class);
        ReflectionMemory old = ReflectionMemory.builder().originalError("找不到客户 老街坊食品有限公司 13912345678")
                .reflectionContent("改用全称").wasSuccessful(true).build();
        when(reflections.findByToolNameOrderByCreatedAtDesc(anyString())).thenReturn(List.of(old));
        CorrectionAgentServiceImpl agent = new CorrectionAgentServiceImpl(llm, config, reflections, new ObjectMapper());
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ChatCompletionResponse r = mock(ChatCompletionResponse.class);
        when(r.getContent()).thenReturn("{\"correctionStrategy\":\"ABANDON\"}");
        when(llm.chatCompletion(sent.capture())).thenReturn(r);

        agent.analyzeAndCorrect("查应收", "customer_receivable_query", Map.of(), "timeout", null, 1);

        assertThat(String.valueOf(sent.getValue().getMessages().get(0).getContent()))
                .doesNotContain("老街坊食品", "13912345678").contains("改用全称");
        verify(llm).chatCompletion(any());
    }
}
