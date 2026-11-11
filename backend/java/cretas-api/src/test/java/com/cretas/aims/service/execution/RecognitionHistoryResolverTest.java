package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.service.ConversationMemoryService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecognitionHistoryResolverTest {

    private static ConversationMessage user(String text, LocalDateTime at) {
        return ConversationMessage.builder().role(ConversationMessage.Role.USER).content(text).timestamp(at).build();
    }

    @Test
    void serverMemoryKeepsOnlyRecentUserTurnsWithinTheWindow() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        LocalDateTime now = LocalDateTime.now();
        List<ConversationMessage> messages = new ArrayList<>();
        messages.add(user("昨天问的", now.minusHours(20)));                       // 过期
        messages.add(user("没有时间戳", null));                                   // 时间不明
        for (int i = 1; i <= 5; i++) messages.add(user("第" + i + "句", now.minusMinutes(10 - i)));
        messages.add(ConversationMessage.assistant("助手结果"));
        messages.add(user("x".repeat(500), now));
        when(memory.getContext("s")).thenReturn(ConversationContext.builder()
                .sessionId("s").factoryId("F1").userId(7L).recentMessages(messages).build());
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("q").sessionId("s").build();

        List<Map<String, String>> history = new RecognitionHistoryResolver(memory).materialize("F1", 7L, request);

        assertThat(history).hasSize(RecognitionHistoryResolver.MAX_RECENT_TURNS)
                .allSatisfy(t -> assertThat(t.get("role")).isEqualTo("user"));
        assertThat(history).extracting(t -> t.get("content")).startsWith("第3句", "第4句", "第5句");
        assertThat(history.get(3).get("content")).hasSize(RecognitionHistoryResolver.MAX_TURN_CHARS);
        assertThat(request.getRecognitionHistory()).isSameAs(history);
        assertThatThrownBy(() -> history.add(Map.of())).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void untrustedCallerNeverReadsServerMemory() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("q").sessionId("s").build();

        assertThat(new RecognitionHistoryResolver(memory).materialize("F1", null, request)).isEmpty();

        verify(memory, never()).getContext(anyString());
        assertThat(request.getRecognitionHistory()).isEmpty();
    }

    @Test
    void clientTurnsWinOverServerMemory() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("q").sessionId("s")
                .context(Map.of("recentTurns", List.of(Map.of("role", "user", "content", "App 带来的")))).build();

        List<Map<String, String>> history = new RecognitionHistoryResolver(memory).materialize("F1", 7L, request);

        assertThat(history).containsExactly(Map.of("role", "user", "content", "App 带来的"));
        verify(memory, never()).getContext(anyString());
    }

    @Test
    void memoryFailureMeansNoContextNotAnError() {
        ConversationMemoryService memory = mock(ConversationMemoryService.class);
        when(memory.getContext("s")).thenThrow(new IllegalStateException("db down"));
        IntentExecuteRequest request = IntentExecuteRequest.builder().userInput("q").sessionId("s").build();

        assertThat(new RecognitionHistoryResolver(memory).materialize("F1", 7L, request)).isEmpty();
    }

    @Test
    void recognitionHistoryCannotBeSetFromHttpJson() throws Exception {
        IntentExecuteRequest parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"userInput\":\"q\",\"recognitionHistory\":[{\"role\":\"user\",\"content\":\"伪造\"}]}",
                IntentExecuteRequest.class);

        assertThat(parsed.getRecognitionHistory()).isNull();
    }
}
