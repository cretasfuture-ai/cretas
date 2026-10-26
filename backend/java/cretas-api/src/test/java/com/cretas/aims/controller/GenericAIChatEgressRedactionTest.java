package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.dto.ChatCompletionRequest;
import com.cretas.aims.ai.dto.ChatCompletionResponse;
import com.cretas.aims.config.DashScopeConfig;
import com.cretas.aims.dto.ai.GenericChatRequest;
import com.cretas.aims.service.consent.LlmEgressGuard;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 通用 AI 对话 (RN 语音录入 / SKU 配置): 客户端发来的整段对话里的人名、公司名、号码不发给模型,
 * 回答 (含流式 token) 换回真名。走真实入口 {@link GenericAIChatController#chat} / {@code chatStream}。
 */
class GenericAIChatEgressRedactionTest {

    private DashScopeClient llm;
    private GenericAIChatController controller;

    @BeforeEach
    void setUp() {
        llm = mock(DashScopeClient.class);
        DashScopeConfig config = mock(DashScopeConfig.class);
        when(config.getFastModel()).thenReturn("fast");
        when(config.getModel()).thenReturn("plus");
        controller = new GenericAIChatController();
        ObjectMapper mapper = new ObjectMapper();
        ReflectionTestUtils.setField(controller, "dashScopeClient", llm);
        ReflectionTestUtils.setField(controller, "dashScopeConfig", config);
        ReflectionTestUtils.setField(controller, "objectMapper", mapper);
        ReflectionTestUtils.setField(controller, "llmEgressGuard", LlmEgressGuard.standalone());
    }

    private static GenericChatRequest request() {
        return GenericChatRequest.builder().messages(List.of(
                GenericChatRequest.Message.builder().role("system")
                        .content("当前批次 PB-20261025-0007, 质检员：赵铁柱").build(),
                GenericChatRequest.Message.builder().role("assistant").content("好的, 张记食品有限公司的单子").build(),
                GenericChatRequest.Message.builder().role("user").content("通知质检员：赵铁柱 13912345678 复检").build()))
                .build();
    }

    private static String sentText(ChatCompletionRequest sent) {
        return sent.getMessages().stream().map(m -> String.valueOf(m.getContent())).collect(Collectors.joining("\n"));
    }

    private static ChatCompletionResponse reply(String content) {
        ChatCompletionResponse r = mock(ChatCompletionResponse.class);
        when(r.getContent()).thenReturn(content);
        return r;
    }

    @Test
    void syncChatSendsTokensAndReturnsRealNames() {
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ChatCompletionResponse answer = reply("已通知 [人员A], 电话 [电话A]");
        when(llm.chatCompletion(sent.capture())).thenReturn(answer);

        String content = controller.chat(request()).getData().getContent();

        assertThat(sentText(sent.getValue()))
                .doesNotContain("赵铁柱", "13912345678", "张记食品有限公司").contains("PB-20261025-0007");
        assertThat(content).isEqualTo("已通知 质检员：赵铁柱, 电话 13912345678");
    }

    @Test
    @SuppressWarnings("unchecked")
    void streamSendsTokensAndRestoresTokensSplitAcrossChunks() throws Exception {
        ArgumentCaptor<ChatCompletionRequest> sent = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ChatCompletionResponse answer = reply("已通知 [人员A], 电话 [电话A]");
        doAnswer(inv -> {
            Consumer<String> onToken = inv.getArgument(1);
            Consumer<ChatCompletionResponse> onComplete = inv.getArgument(2);
            for (String chunk : List.of("已通知 [人", "员A], 电", "话 [电话A]")) {
                onToken.accept(chunk);
            }
            onComplete.accept(answer);
            return null;
        }).when(llm).chatCompletionStream(sent.capture(), any(), any());

        SseEmitter emitter = controller.chatStream(request());
        String streamed = "";
        for (int i = 0; i < 100 && !streamed.contains("event:done"); i++) {
            Thread.sleep(50);
            streamed = earlySends(emitter);
        }

        assertThat(sentText(sent.getValue())).doesNotContain("赵铁柱", "13912345678", "张记食品有限公司");
        assertThat(streamed).contains("event:done").contains("赵铁柱", "13912345678").doesNotContain("[人员A]", "[电话A]");
    }

    /** 还没挂上 HTTP 响应的 SseEmitter 把发出的事件攒在 earlySendAttempts 里。 */
    private static String earlySends(SseEmitter emitter) {
        Object attempts = ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        StringBuilder sb = new StringBuilder();
        synchronized (emitter) {
            for (Object a : (Collection<?>) attempts) {
                sb.append(ReflectionTestUtils.invokeMethod(a, "getData").toString());
            }
        }
        return sb.toString();
    }
}
