package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.dto.clarification.ReferenceResult;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.dto.conversation.EntitySlot;
import com.cretas.aims.entity.conversation.ConversationMemory;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationMemoryRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.repository.conversation.FactoryAssemblyConversationSessionRepository;
import com.cretas.aims.service.FactoryAssemblyConversationService;
import com.cretas.aims.service.QueryPreprocessorService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.cretas.aims.config.ColloquialMappings;
import com.cretas.aims.config.TimeNormalizationRules;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 带对话历史的五条外发: 指代消解、查询改写、记忆摘要、意图澄清对话、对话建厂。
 * 历史里有此前回答里换回的真名 (客户 / 供应商 / 员工) 和号码, 发给模型前必须换代号, 回答再换回。
 * 每条都走真实入口, 截获实际发给模型的内容。
 */
class ConversationHistoryEgressRedactionTest {

    private static final String FACTORY = "F006";
    /** 历史里出现的真名 / 号码; 任何一条外发都不许带。 */
    private static final String[] SECRETS = {"华东冷链商贸", "王建国", "13812345678", "张记食品有限公司", "秘制酱牛肉"};

    private final ObjectMapper mapper = new ObjectMapper();
    private DashScopeClient llm;
    private ProcessSecretSampleFilter processSecret;
    private LlmEgressGuard guard;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(FACTORY)).thenReturn(Set.of("秘制酱牛肉"));
        guard = new LlmEgressGuard(mapper, processSecret);
    }

    private static ConversationContext context() {
        Map<EntitySlot.SlotType, EntitySlot> slots = new EnumMap<>(EntitySlot.SlotType.class);
        slots.put(EntitySlot.SlotType.SUPPLIER, EntitySlot.builder()
                .type(EntitySlot.SlotType.SUPPLIER).id("S9").name("华东冷链商贸").build());
        slots.put(EntitySlot.SlotType.BATCH, EntitySlot.builder()
                .type(EntitySlot.SlotType.BATCH).id("B1").name("PB-20261025-0007").build());
        return ConversationContext.builder()
                .sessionId("s1")
                .factoryId(FACTORY)
                .entitySlots(slots)
                .conversationSummary("用户在查华东冷链商贸的到货, 联系人：王建国 13812345678")
                .recentMessages(List.of(
                        ConversationMessage.user("华东冷链商贸上周送了多少猪前腿肉"),
                        ConversationMessage.assistant("华东冷链商贸 上周到货 300 kg, 联系人：王建国 13812345678; "
                                + "张记食品有限公司 订了 秘制酱牛肉")))
                .lastIntentCode("MATERIAL_BATCH_QUERY")
                .build();
    }

    private static void assertClean(String sent) {
        assertThat(sent).doesNotContain(SECRETS);
    }

    @Test
    void coreferenceSendsTokensAndPutsTheRealNameIntoTheResolvedText() {
        CoreferenceResolutionServiceImpl service = new CoreferenceResolutionServiceImpl(llm, mapper);
        ReflectionTestUtils.setField(service, "llmEnabled", true);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard);
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), prompt.capture())).thenAnswer(inv -> {
            String p = inv.getArgument(1);
            String supplier = p.replaceAll("(?s).*SUPPLIER: (\\[[^]]+]).*", "$1");
            return "{\"resolutions\":{\"那家供应商\":\"" + supplier + "\"},\"confidence\":0.8}";
        });

        ReferenceResult r = service.resolveWithLLM("那家供应商这周还送吗", List.of("那家供应商"), context());

        assertClean(prompt.getValue());
        assertThat(prompt.getValue()).contains("[供应商A]", "PB-20261025-0007");
        assertThat(r.getResolvedText()).isEqualTo("华东冷链商贸这周还送吗");
    }

    @Test
    void queryRewriteSendsTokensAndRestoresTheRewrittenQuery() {
        QueryPreprocessorServiceImpl service = new QueryPreprocessorServiceImpl(
                mock(TimeNormalizationRules.class), mock(ColloquialMappings.class), null, llm, mapper, null, null);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatLowTemp(system.capture(), user.capture()))
                .thenReturn("{\"rewritten_query\":\"查询[供应商A]本周到货\",\"confidence\":0.9}");

        QueryPreprocessorService.LlmRewriteResult r = service.rewriteWithLLM("他家这周呢, 打 13812345678 问", context());

        assertClean(system.getValue() + user.getValue());
        assertThat(r.getRewrittenQuery()).isEqualTo("查询华东冷链商贸本周到货");
    }

    @Test
    void memorySummarySendsTokensAndStoresTheRestoredSummary() {
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = ConversationMemory.create(FACTORY, 7L);
        Map<String, ConversationMemory.EntitySlotData> slots = new LinkedHashMap<>();
        slots.put("SUPPLIER", ConversationMemory.EntitySlotData.builder().type("SUPPLIER").name("华东冷链商贸").build());
        memory.setEntitySlots(slots);
        List<ConversationMemory.MessageData> messages = new ArrayList<>();
        messages.add(ConversationMemory.MessageData.builder().role("user").content("华东冷链商贸送货了吗").build());
        messages.add(ConversationMemory.MessageData.builder().role("assistant")
                .content("华东冷链商贸 已送 300 kg, 联系人：王建国 13812345678, 张记食品有限公司 的 秘制酱牛肉 已排产").build());
        memory.setRecentMessages(messages);
        when(repo.findBySessionId("s1")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, llm);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard);
        ArgumentCaptor<String> history = ArgumentCaptor.forClass(String.class);
        when(llm.chat(anyString(), history.capture())).thenReturn("用户在跟进 [供应商A] 的到货");

        service.updateSummary("s1");

        assertClean(history.getValue());
        assertThat(memory.getConversationSummary()).isEqualTo("用户在跟进 华东冷链商贸 的到货");
    }

    @Test
    void memorySummaryIsNotSentWhenSecretLookupFails() {
        when(processSecret.protectedProductNames(FACTORY)).thenThrow(new IllegalStateException("db down"));
        ConversationMemoryRepository repo = mock(ConversationMemoryRepository.class);
        ConversationMemory memory = ConversationMemory.create(FACTORY, 7L);
        memory.setRecentMessages(List.of(ConversationMemory.MessageData.builder().role("user").content("你好").build()));
        when(repo.findBySessionId("s1")).thenReturn(Optional.of(memory));
        ConversationMemoryServiceImpl service = new ConversationMemoryServiceImpl(repo, llm);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard);

        service.updateSummary("s1");

        verify(llm, never()).chat(anyString(), anyString());
    }

    @Test
    void intentClarificationConversationSendsTokensAndRestoresTheQuestion() {
        ConversationSessionRepository sessions = mock(ConversationSessionRepository.class);
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
        when(intents.findActiveByFactoryIdWithPriority(FACTORY)).thenReturn(List.of());
        ConversationServiceImpl service = new ConversationServiceImpl(sessions, intents, null, llm);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(system.capture(), user.capture())).thenAnswer(inv -> {
            String said = inv.getArgument(1);
            String org = said.replaceAll("(?s).*?(\\[单位[A-Z]+]).*", "$1");
            return "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"要查 " + org + " 的订单吗?\"}";
        });

        var response = service.startConversation(FACTORY, 7L, "张记食品有限公司 的单子, 联系人：王建国 13812345678");

        assertClean(system.getValue() + user.getValue());
        assertThat(response.getMessage()).isEqualTo("要查 张记食品有限公司 的订单吗?");
    }

    @Test
    void factoryAssemblyConversationSendsTokensAndRestoresTheQuestion() {
        FactoryAssemblyConversationSessionRepository sessions = mock(FactoryAssemblyConversationSessionRepository.class);
        when(sessions.save(any())).thenAnswer(inv -> inv.getArgument(0));
        AIIntentConfigRepository intents = mock(AIIntentConfigRepository.class);
        when(intents.findActiveByFactoryIdWithPriority(FACTORY)).thenReturn(List.of());
        FactoryAssemblyConversationServiceImpl service =
                new FactoryAssemblyConversationServiceImpl(sessions, intents, null, llm);
        ReflectionTestUtils.setField(service, "llmEgressGuard", guard);
        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> user = ArgumentCaptor.forClass(String.class);
        when(llm.chatFast(system.capture(), user.capture())).thenAnswer(inv -> {
            String said = inv.getArgument(1);
            String phone = said.replaceAll("(?s).*?(\\[电话[A-Z]+]).*", "$1");
            return "{\"is_confirmed\":false,\"confidence\":0.4,\"message\":\"联系电话是 " + phone + " 吗?\"}";
        });

        var response = service.startConversation(FACTORY,
                FactoryAssemblyConversationService.ConversationActor.factoryUser(7L),
                "我们是张记食品有限公司, 主做秘制酱牛肉, 厂长电话 13812345678");

        assertClean(system.getValue() + user.getValue());
        assertThat(response.getMessage()).isEqualTo("联系电话是 13812345678 吗?");
    }
}
