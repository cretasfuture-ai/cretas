package com.cretas.aims.service.execution;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.client.F006BossQuestionClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.config.IntentKnowledgeBase;
import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.ai.IntentExecuteResponse;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.security.AuthenticatedActor;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.AIIntentService;
import com.cretas.aims.service.AnalysisRouterService;
import com.cretas.aims.service.ConversationMemoryService;
import com.cretas.aims.service.SemanticCacheService;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.cretas.aims.service.consent.ProcessSecretSampleFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 流式闲聊 / 咨询 (云端模型) 与 F006 老板提问 (本地小模型): 原话里的电话、人名、公司名不发出去,
 * 流式 token 里的代号被拆在两段时也能换回, 最终结果是真值。走真实入口 {@code executeStreamAsync}。
 */
class SseStreamingEgressRedactionTest {
    private static final String QUESTION = "帮我通知质检员赵铁柱 13912345678 明天去华东冷链商贸有限公司复检";
    private static final Pattern PHONE_TOKEN = Pattern.compile("\\[电话[A-Z]+]");

    private final ObjectMapper mapper = new ObjectMapper();
    private IntentKnowledgeBase knowledge;
    private DashScopeClient cloud;
    private F006BossQuestionClient mini;
    private ProcessSecretSampleFilter processSecret;
    private SseStreamingService service;
    private SseEmitter emitter;

    @BeforeEach
    void setUp() throws Exception {
        knowledge = mock(IntentKnowledgeBase.class);
        cloud = mock(DashScopeClient.class);
        mini = mock(F006BossQuestionClient.class);
        processSecret = mock(ProcessSecretSampleFilter.class);
        when(processSecret.protectedProductNames(anyString())).thenReturn(Set.of());
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        DashScopeConfig config = mock(DashScopeConfig.class);
        when(config.getFastModel()).thenReturn("cloud-model");
        service = spy(new SseStreamingService(mock(AIIntentService.class), mock(SemanticCacheService.class), knowledge,
                mock(AnalysisRouterService.class), mapper, mock(ToolRegistry.class), mock(ToolDispatchService.class),
                mock(DynamicToolSelectionService.class), cloud, config));
        emitter = mock(SseEmitter.class);
        doNothing().when(service).sendSseEvent(any(), any(), any());
        ReflectionTestUtils.setField(service, "f006BossQuestionClient", mini);
        ReflectionTestUtils.setField(service, "conversationMemoryService", memory);
        ReflectionTestUtils.setField(service, "llmEgressGuard", new LlmEgressGuard(mapper, processSecret));
        when(memory.getOrCreateContext(anyString(), anyLong(), any())).thenAnswer(call ->
                ConversationContext.builder().factoryId(call.getArgument(0)).userId(call.getArgument(1))
                        .sessionId("s-1").build());
        when(knowledge.detectQuestionType(QUESTION)).thenReturn(IntentKnowledgeBase.QuestionType.GENERAL_QUESTION);
        when(knowledge.matchPhrase(QUESTION)).thenReturn(Optional.empty());
        when(mini.shouldRoute(any(), any(), any())).thenAnswer(call -> "F006".equals(call.getArgument(0)));
    }

    private void execute(String factory) {
        try (AuthenticatedActor.ActorScope ignored = AuthenticatedActor.trustedAsyncScope(
                new AuthenticatedActor(AuthenticatedActorType.FACTORY_USER, 77L, factory, "factory_super_admin"))) {
            ReflectionTestUtils.invokeMethod(service, "executeStreamAsync", emitter, factory,
                    IntentExecuteRequest.builder().userInput(QUESTION).build(), 77L, "factory_super_admin");
        }
    }

    private static String phoneToken(String sent) {
        Matcher m = PHONE_TOKEN.matcher(sent);
        assertThat(m.find()).as("sent text carries a phone token: %s", sent).isTrue();
        return m.group();
    }

    private IntentExecuteResponse result() throws Exception {
        ArgumentCaptor<Object> result = ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("result"), result.capture());
        return (IntentExecuteResponse) result.getValue();
    }

    @Test
    void cloudStreamGetsRedactedQuestionAndTheFinalAnswerShowsTheRealNumber() throws Exception {
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        doAnswer(call -> {
            ChatCompletionRequest req = call.getArgument(0);
            String token = phoneToken(String.valueOf(req.getMessages().get(1).getContent()));
            Consumer<String> tokens = call.getArgument(1);
            tokens.accept("好的, 已记下 " + token.substring(0, 2));
            tokens.accept(token.substring(2) + " 。");
            ChatCompletionResponse done = mock(ChatCompletionResponse.class);
            when(done.getContent()).thenReturn("好的, 已记下 " + token + " 。");
            Consumer<ChatCompletionResponse> complete = call.getArgument(2);
            complete.accept(done);
            return null;
        }).when(cloud).chatCompletionStream(sent.capture(), any(), any());

        execute("F007");

        String user = String.valueOf(sent.getValue().getMessages().get(1).getContent());
        assertThat(user).doesNotContain("13912345678", "赵铁柱", "华东冷链").contains("复检");
        assertThat(result().getFormattedText()).isEqualTo("好的, 已记下 13912345678 。");
    }

    @Test
    void cloudStreamIsNotCalledWhenRedactionFails() throws Exception {
        when(processSecret.protectedProductNames("F007")).thenThrow(new IllegalStateException("db down"));

        execute("F007");

        verify(cloud, never()).chatCompletionStream(any(), any(), any());
        assertThat(result().getFormattedText()).isEqualTo(SseStreamingService.EGRESS_REFUSED_REPLY);
    }

    @Test
    void miniModelGetsRedactedQuestionAndSplitTokensAreRestored() throws Exception {
        ArgumentCaptor<String> sent = ArgumentCaptor.forClass(String.class);
        doAnswer(call -> {
            String token = phoneToken(call.<String>getArgument(3));
            Consumer<String> tokens = call.getArgument(6);
            tokens.accept("已通知 " + token.substring(0, 2));
            tokens.accept(token.substring(2) + " 。");
            return new F006BossQuestionClient.Answer("已通知 " + token + " 。", "mac-mini", "qwen3.8:27b",
                    "stop", "a".repeat(32), null);
        }).when(mini).stream(anyString(), anyLong(), anyString(), sent.capture(), anyBoolean(), any(), any(), any());

        execute("F006");

        assertThat(sent.getValue()).doesNotContain("13912345678", "赵铁柱", "华东冷链").contains("复检");
        ArgumentCaptor<Object> tokens = ArgumentCaptor.forClass(Object.class);
        verify(service, atLeastOnce()).sendSseEvent(eq(emitter), eq("token"), tokens.capture());
        assertThat(tokens.getAllValues().stream().map(String::valueOf).collect(Collectors.joining()))
                .isEqualTo("已通知 13912345678 。");
        assertThat(result().getFormattedText()).isEqualTo("已通知 13912345678 。");
        ArgumentCaptor<Object> complete = ArgumentCaptor.forClass(Object.class);
        verify(service).sendSseEvent(eq(emitter), eq("complete"), complete.capture());
        assertThat(((Map<?, ?>) complete.getValue()).get("fullContent")).isEqualTo("已通知 13912345678 。");
    }

    @Test
    void miniModelIsNotCalledWhenRedactionFails() throws Exception {
        when(processSecret.protectedProductNames("F006")).thenThrow(new IllegalStateException("db down"));

        execute("F006");

        verify(mini, never()).stream(any(), any(), any(), any(), anyBoolean(), any(), any(), any());
        verifyNoInteractions(cloud);
        assertThat(result().getFormattedText()).isEqualTo(SseStreamingService.EGRESS_REFUSED_REPLY);
    }
}
