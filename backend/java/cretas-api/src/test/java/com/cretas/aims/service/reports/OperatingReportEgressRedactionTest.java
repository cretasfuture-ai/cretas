package com.cretas.aims.service.reports;

import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.entity.AIAnalysisResult;
import com.cretas.aims.entity.Factory;
import com.cretas.aims.entity.config.AIReportPromptConfig;
import com.cretas.aims.entity.enums.FactoryType;
import com.cretas.aims.repository.FactoryRepository;
import com.cretas.aims.repository.config.AIReportPromptConfigRepository;
import com.cretas.aims.service.PermissionService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 定时经营报告: 工厂配置的「关注方向」与指标快照发给模型前脱敏; 脱敏不了按模型不可用记失败, 不发。
 * 走真实入口 {@link OperatingReportGenerator#scheduled}。
 */
class OperatingReportEgressRedactionTest {

    private final FactoryRepository factories = mock(FactoryRepository.class);
    private final OperatingReportCollector collector = mock(OperatingReportCollector.class);
    private final OperatingReportStore store = mock(OperatingReportStore.class);
    private final PythonLLMClient model = mock(PythonLLMClient.class);
    private final AIReportPromptConfigRepository prompts = mock(AIReportPromptConfigRepository.class);
    private final OperatingReportCodec codec = new OperatingReportCodec(new ObjectMapper());
    private final ProcessSecretSampleFilter processSecret = mock(ProcessSecretSampleFilter.class);
    private final LocalDate day = LocalDate.of(2026, 1, 1);

    private OperatingReportGenerator generator() throws Exception {
        Factory factory = new Factory();
        factory.setId("F006");
        factory.setType(FactoryType.FACTORY);
        factory.setIsActive(true);
        when(factories.findById("F006")).thenReturn(Optional.of(factory));
        when(collector.collect(any(), eq("daily"), eq(day), eq(day))).thenReturn(new OperatingReportAssembler.Input(
                "daily", day, day, "2026-01-01T20:00:00Z", List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), List.of(), Map.of(), List.of(), Map.of()));
        AIReportPromptConfig config = new AIReportPromptConfig();
        config.setPromptTemplate("重点关注 张记食品有限公司 的回款和 秘制酱牛肉, 有问题找联系人：王建国 13812345678");
        when(prompts.findFirstByFactoryIdAndReportTypeAndIsActiveTrueOrderByPriorityDesc("F006", "daily"))
                .thenReturn(Optional.of(config));
        when(store.save(anyString(), any(), any(Boolean.class))).thenAnswer(inv -> {
            AIAnalysisResult result = new AIAnalysisResult();
            result.setAnalysisText(codec.encode(inv.getArgument(1)));
            return result;
        });
        when(store.failure(anyString(), anyString(), any(), any(), any(), any(Boolean.class), any()))
                .thenReturn(new AIAnalysisResult());
        OperatingReportGenerator g = new OperatingReportGenerator(factories, mock(PermissionService.class), collector,
                new OperatingReportAssembler(), codec, store, model, prompts);
        ReflectionTestUtils.setField(g, "llmEgressGuard", new LlmEgressGuard(new ObjectMapper(), processSecret));
        return g;
    }

    @Test
    void configuredFocusIsRedactedBeforeTheModelSeesIt() throws Exception {
        when(processSecret.protectedProductNames("F006")).thenReturn(Set.of("秘制酱牛肉"));
        OperatingReportGenerator g = generator();
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(model.chat(anyString(), prompt.capture())).thenReturn("{\"insights\":[]}");

        g.scheduled("F006", "daily", day, day);

        assertThat(prompt.getValue()).doesNotContain("张记食品", "秘制酱牛肉", "王建国", "13812345678")
                .contains("经营指标快照");
    }

    @Test
    void secretLookupFailureMeansTheReportPromptIsNotSent() throws Exception {
        when(processSecret.protectedProductNames("F006")).thenThrow(new IllegalStateException("db down"));
        OperatingReportGenerator g = generator();

        try {
            g.scheduled("F006", "daily", day, day);
        } catch (RuntimeException expected) {
            // 失败快照会让 scheduled 抛 IllegalStateException; 这里只关心模型有没有被调用
        }

        verify(model, never()).chat(anyString(), anyString());
        verify(store).failure(eq("F006"), eq("daily"), eq(day), eq(day),
                eq(OperatingReportSnapshot.Failure.MODEL_UNAVAILABLE), eq(true), any());
    }
}
