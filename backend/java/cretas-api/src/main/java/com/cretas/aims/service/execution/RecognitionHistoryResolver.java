package com.cretas.aims.service.execution;

import com.cretas.aims.dto.ai.IntentExecuteRequest;
import com.cretas.aims.dto.conversation.ConversationContext;
import com.cretas.aims.dto.conversation.ConversationMessage;
import com.cretas.aims.service.ConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 识别用最近对话的唯一来源 (INTENT-DISTILL-20261111 A 线)。
 *
 * <p>普通入口 {@code /ai-intents/execute} 与 SSE 入口 {@code /ai-intents/execute/stream}
 * 在进入意图识别前都调 {@link #materialize}, 读同一份结果, 两入口的多轮续句因此一致。
 *
 * <p>来源按顺序:
 * <ol>
 *   <li>客户端随请求带来的 {@code context.recentTurns} (App 现状, 清洗规则不变);</li>
 *   <li>没有时, 调用者自己的服务端会话记忆 (Web 不发 recentTurns): 只在已证明身份的
 *       工厂用户、会话归属 (工厂 + 用户) 核对通过时读, 并且<b>只取用户自己说过的话</b>,
 *       最近 {@link #SERVER_MEMORY_WINDOW} 内的。</li>
 * </ol>
 *
 * <p>⛔ 隐私边界: 服务端记忆里的助手消息是换回真名后的业务结果, 这里一条都不取;
 * 识别层 ({@code AIIntentServiceImpl}) 再对外发前还会只留用户话并脱敏。
 * 带上下文的轮次不读也不写按原话缓存 —— 两个入口的调用方都按 {@code !history.isEmpty()} 判。
 */
@Slf4j
@Component
public class RecognitionHistoryResolver {

    /** 最近几轮对话最多带几轮、每轮最多几个字 —— 只为消歧, 不是完整会话。 */
    public static final int MAX_RECENT_TURNS = 4;
    public static final int MAX_TURN_CHARS = 200;
    /** 服务端记忆只取这么近的用户话: 隔天回来的同一个会话 id 不该把昨天的话当上下文。 */
    public static final Duration SERVER_MEMORY_WINDOW = Duration.ofMinutes(30);

    @Autowired(required = false)
    private ConversationMemoryService conversationMemoryService;

    public RecognitionHistoryResolver() {
    }

    RecognitionHistoryResolver(ConversationMemoryService conversationMemoryService) {
        this.conversationMemoryService = conversationMemoryService;
    }

    /**
     * 解析并写入 {@code request.recognitionHistory}, 返回同一份 (不可变)。
     *
     * @param trustedUserId 已证明属于 {@code factoryId} 的调用者; null = 不读服务端记忆
     */
    public List<Map<String, String>> materialize(String factoryId, Long trustedUserId,
                                                 IntentExecuteRequest request) {
        if (request == null) return Collections.emptyList();
        List<Map<String, String>> history = fromClient(request);
        if (history.isEmpty()) {
            history = fromOwnedServerMemory(factoryId, trustedUserId, request.getSessionId());
        }
        List<Map<String, String>> frozen = List.copyOf(history);
        request.setRecognitionHistory(frozen);
        return frozen;
    }

    /** 已解析过就用解析结果; 没解析 (裸构造的旧单测) 退回只读客户端 recentTurns。 */
    public static List<Map<String, String>> effective(IntentExecuteRequest request) {
        if (request == null) return Collections.emptyList();
        if (request.getRecognitionHistory() != null) return request.getRecognitionHistory();
        return fromClient(request);
    }

    /**
     * App 随请求带来的 {@code context.recentTurns}: 只认 role=user/assistant 的非空文本,
     * 截断到 {@link #MAX_TURN_CHARS} 字, 取最后 {@link #MAX_RECENT_TURNS} 轮。格式不对的条目丢弃。
     */
    public static List<Map<String, String>> fromClient(IntentExecuteRequest request) {
        if (request == null || request.getContext() == null) {
            return Collections.emptyList();
        }
        Object raw = request.getContext().get("recentTurns");
        if (!(raw instanceof Collection<?> items)) {
            return Collections.emptyList();
        }
        List<Map<String, String>> out = new ArrayList<>();
        for (Object item : items) {
            if (!(item instanceof Map<?, ?> m)) {
                continue;
            }
            Object role = m.get("role");
            Object content = m.get("content");
            if (!("user".equals(role) || "assistant".equals(role)) || !(content instanceof String text)
                    || text.isBlank()) {
                continue;
            }
            out.add(Map.of("role", (String) role, "content", clip(text)));
        }
        return lastTurns(out);
    }

    List<Map<String, String>> fromOwnedServerMemory(String factoryId, Long trustedUserId, String sessionId) {
        if (conversationMemoryService == null || factoryId == null || trustedUserId == null
                || sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        ConversationContext owner;
        try {
            owner = conversationMemoryService.getContext(sessionId);
        } catch (RuntimeException e) {
            log.warn("识别用会话记忆读取失败, 本轮按无上下文识别: failureType={}", e.getClass().getSimpleName());
            return Collections.emptyList();
        }
        if (owner == null || !Objects.equals(factoryId, owner.getFactoryId())
                || !Objects.equals(trustedUserId, owner.getUserId())
                || !Objects.equals(sessionId, owner.getSessionId())
                || owner.getRecentMessages() == null) {
            return Collections.emptyList();
        }
        LocalDateTime cutoff = LocalDateTime.now().minus(SERVER_MEMORY_WINDOW);
        List<Map<String, String>> out = new ArrayList<>();
        for (ConversationMessage message : owner.getRecentMessages()) {
            if (message == null || message.getRole() != ConversationMessage.Role.USER
                    || message.getContent() == null || message.getContent().isBlank()
                    || message.getTimestamp() == null || message.getTimestamp().isBefore(cutoff)) {
                continue;
            }
            out.add(Map.of("role", "user", "content", clip(message.getContent())));
        }
        return lastTurns(out);
    }

    private static String clip(String text) {
        String trimmed = text.strip();
        return trimmed.length() > MAX_TURN_CHARS ? trimmed.substring(0, MAX_TURN_CHARS) : trimmed;
    }

    private static List<Map<String, String>> lastTurns(List<Map<String, String>> turns) {
        return turns.size() > MAX_RECENT_TURNS
                ? new ArrayList<>(turns.subList(turns.size() - MAX_RECENT_TURNS, turns.size()))
                : turns;
    }
}
