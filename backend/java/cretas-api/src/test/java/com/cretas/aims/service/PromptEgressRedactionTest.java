package com.cretas.aims.service;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.PythonLLMClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.ArenaRLConfig;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.arena.ComparisonRubric;
import com.cretas.aims.dto.decoration.AILayoutRequest;
import com.cretas.aims.dto.skill.SkillContext;
import com.cretas.aims.dto.skill.SkillDefinition;
import com.cretas.aims.repository.FactoryHomeLayoutRepository;
import com.cretas.aims.service.arena.ArenaRLTournamentService.TournamentCandidate;
import com.cretas.aims.service.arena.impl.ArenaRLTournamentServiceImpl;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.LlmEgressRedactor;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.service.decoration.impl.DecorationServiceImpl;
import com.cretas.aims.service.skill.impl.SkillExecutorImpl;
import com.cretas.aims.service.validator.LayoutValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提示词里嵌着用户原话的出口: Skill 编排 / 知识-数据消歧 / 意图两两比较 / AI 生成首页布局。
 * 原话里的电话、岗位 + 人名、公司名换代号再发; 脱敏不了就走各自原有的不调模型路径。
 * 每条走对应服务的公开方法, 只把模型那一层打桩。
 */
class PromptEgressRedactionTest {

    private static final String FACTORY = "F006";
    private static final String SAID = "通知质检员赵铁柱 13912345678 去华东冷链商贸有限公司复检这批货";

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

    /** 不带工厂的调用点取请求里的工厂; 测试里没有请求, 用本厂 + 必炸的过滤器模拟「查不了」。 */
    private LlmEgressGuard brokenGuard() {
        ProcessSecretSampleFilter broken = mock(ProcessSecretSampleFilter.class);
        when(broken.protectedProductNames(anyString())).thenThrow(new IllegalStateException("db down"));
        return new LlmEgressGuard(mapper, broken) {
            @Override
            public LlmEgressRedactor.Result texts(String site, String factoryId, String... texts) {
                return super.texts(site, FACTORY, texts);
            }
        };
    }

    private static void assertNoRealValues(String sent) {
        assertThat(sent).doesNotContain("13912345678", "赵铁柱", "华东冷链").contains("复检");
    }

    // ---------------------------------------------------------------- Skill

    @Test
    void skillPromptAndQueryAreRedacted() {
        DashScopeClient llm = mock(DashScopeClient.class);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chat(system.capture(), user.capture())).thenReturn("{}");
        ToolRegistry tools = mock(ToolRegistry.class);
        when(tools.hasExecutor(anyString())).thenReturn(true);
        SkillExecutorImpl executor = new SkillExecutorImpl(tools, llm, mapper);
        ReflectionTestUtils.setField(executor, "llmEgressGuard", guard());
        SkillDefinition skill = SkillDefinition.builder().name("quality-recheck").displayName("复检")
                .tools(List.of("quality_check_create", "notify_staff")).contextNeeded(List.of("factoryId"))
                .promptTemplate("用户说: ${userQuery}").build();
        SkillContext ctx = SkillContext.builder().factoryId(FACTORY).userId("1").userQuery(SAID).build();

        executor.execute(skill, ctx, 5_000L);

        assertNoRealValues(user.getValue());
        assertNoRealValues(system.getValue());

        DashScopeClient second = mock(DashScopeClient.class);
        ReflectionTestUtils.setField(executor, "dashScopeClient", second);
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        executor.execute(skill, SkillContext.builder().factoryId(FACTORY).userId("1").userQuery(SAID).build(), 5_000L);
        verify(second, never()).chat(anyString(), anyString());
    }

    // ---------------------------------------------------------------- 知识 / 数据消歧

    @Test
    void disambiguationSendsRedactedUtterance() {
        DashScopeClient llm = mock(DashScopeClient.class);
        DashScopeConfig config = mock(DashScopeConfig.class);
        when(config.isAvailable()).thenReturn(true);
        when(config.getCorrectionModel()).thenReturn("qwen-turbo");
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ChatCompletionResponse response = mock(ChatCompletionResponse.class);
        when(response.getContent()).thenReturn("{\"choice\":\"B\",\"reason\":\"数据\"}");
        when(llm.chatCompletion(sent.capture())).thenReturn(response);
        IntentDisambiguationService service = new IntentDisambiguationService(llm, config, mapper);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard());

        service.disambiguate(SAID, "QUALITY_CHECK_CREATE");

        assertNoRealValues(String.valueOf(sent.getValue().getMessages().get(1).getContent()));

        DashScopeClient second = mock(DashScopeClient.class);
        IntentDisambiguationService refusing = new IntentDisambiguationService(second, config, mapper);
        ReflectionTestUtils.setField(refusing, "enabled", true);
        ReflectionTestUtils.setField(refusing, "llmEgressGuard", brokenGuard());
        assertThat(refusing.disambiguate(SAID, "QUALITY_CHECK_CREATE").isDefault()).isTrue();
        verify(second, never()).chatCompletion(any());
    }

    // ---------------------------------------------------------------- 意图两两比较

    private static TournamentCandidate candidate(String id) {
        return new TournamentCandidate() {
            public String getId() { return id; }
            public String getName() { return id; }
            public String getDescription() { return "描述 " + id; }
            public Double getScore() { return 0.5; }
            public Map<String, Object> getMetadata() { return Map.of(); }
        };
    }

    @Test
    void arenaComparisonSendsRedactedUtterance() {
        PythonLLMClient llm = mock(PythonLLMClient.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), user.capture()))
                .thenReturn("{\"winner\":\"A\",\"win_confidence\":0.8,\"reasoning\":\"复检\"}");
        ArenaRLTournamentServiceImpl arena = new ArenaRLTournamentServiceImpl(new ArenaRLConfig(), llm, mapper);
        ReflectionTestUtils.setField(arena, "llmEgressGuard", guard());

        arena.compareTwo(SAID, candidate("QUALITY_CHECK_CREATE"), candidate("NOTIFY_STAFF"),
                ComparisonRubric.intentDisambiguationRubric());

        assertThat(user.getAllValues()).isNotEmpty().allSatisfy(PromptEgressRedactionTest::assertNoRealValues);

        PythonLLMClient second = mock(PythonLLMClient.class);
        ArenaRLTournamentServiceImpl refusing = new ArenaRLTournamentServiceImpl(new ArenaRLConfig(), second, mapper);
        ReflectionTestUtils.setField(refusing, "llmEgressGuard", brokenGuard());
        refusing.compareTwo(SAID, candidate("QUALITY_CHECK_CREATE"), candidate("NOTIFY_STAFF"),
                ComparisonRubric.intentDisambiguationRubric());
        verify(second, never()).chatLowTemp(anyString(), anyString());
    }

    // ---------------------------------------------------------------- AI 生成首页布局

    @Test
    void homeLayoutGenerationSendsRedactedDescription() {
        PythonLLMClient llm = mock(PythonLLMClient.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(anyString(), user.capture())).thenReturn("{\"modules\":[]}");
        DecorationServiceImpl decoration = new DecorationServiceImpl(mock(FactoryHomeLayoutRepository.class), mapper,
                llm, mock(LayoutValidator.class));
        ReflectionTestUtils.setField(decoration, "llmEgressGuard", guard());

        decoration.generateLayoutWithAI(FACTORY, AILayoutRequest.builder().prompt(SAID).build());

        assertNoRealValues(user.getValue());

        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        PythonLLMClient second = mock(PythonLLMClient.class);
        ReflectionTestUtils.setField(decoration, "pythonLLMClient", second);
        assertThat(decoration.generateLayoutWithAI(FACTORY, AILayoutRequest.builder().prompt(SAID).build()))
                .isNotNull();
        verify(second, never()).chatLowTemp(anyString(), anyString());
    }
}
