package com.cretas.aims.service.impl;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.repository.config.AIIntentConfigRepository;
import com.cretas.aims.repository.conversation.ConversationSessionRepository;
import com.cretas.aims.service.ConversationService;
import com.cretas.aims.service.ExpressionLearningService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺参追问的回答由 LLM 抽参 (两个及以上槽位待填) 时, 与 ToolDispatchService 同一条出处校验。
 * prod F006 2026-10-06 原形: 用户只回「1827」, LLM 同时给出产量 0 和一句自言自语的备注。
 * 入口: 真实的 startParameterCollection → continueConversation, 只桩 LLM 与仓库。
 */
class ConversationSlotAnswerGroundingContractTest {

    private static final String FACTORY_ID = "F006";
    private static final Long USER_ID = 7L;

    private ConversationSessionRepository repo;
    private DashScopeClient llm;
    private ConversationServiceImpl service;
    private final AtomicReference<ConversationSession> saved = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        repo = mock(ConversationSessionRepository.class);
        llm = mock(DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        when(repo.save(any())).thenAnswer(inv -> {
            saved.set(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.findBySessionIdAndFactoryIdAndUserIdForUpdate(any(), any(), any()))
                .thenAnswer(inv -> Optional.ofNullable(saved.get()));
        service = new ConversationServiceImpl(repo, mock(AIIntentConfigRepository.class),
                mock(ExpressionLearningService.class), llm,
                mock(org.springframework.transaction.PlatformTransactionManager.class));
        ReflectionTestUtils.setField(service, "entityManager", mock(jakarta.persistence.EntityManager.class));
        ReflectionTestUtils.setField(service, "maxRounds", 5);
        ReflectionTestUtils.setField(service, "timeoutMinutes", 10);
    }

    private String start() {
        ConversationService.ConversationResponse r = service.startParameterCollection(
                FACTORY_ID, USER_ID, "PROCESSING_TASK_COMPLETE", "完成工序任务",
                List.of(param("taskId", "NUMBER"), param("actualQuantity", "NUMBER"), param("notes", "TEXT")),
                List.of());
        return r.getSessionId();
    }

    private static ConversationService.RequiredParameter param(String name, String type) {
        ConversationService.RequiredParameter p = new ConversationService.RequiredParameter();
        p.setName(name);
        p.setLabel(name);
        p.setType(type);
        return p;
    }

    @Test
    void inventedQuantityAndNarrationAreDroppedButTheSpokenNumberIsKept() {
        String sid = start();
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted\":{\"taskId\":\"1827\",\"actualQuantity\":\"0\","
                        + "\"notes\":\"用户仅提供了数字1827，未明确实际产量，需补充确认\"},\"reasoning\":\"x\"}");

        ConversationService.ConversationResponse r = service.continueConversation(FACTORY_ID, USER_ID, sid, "1827");

        Map<String, String> collected = saved.get().getCollectedParameters();
        assertThat(collected).as(r.getStatus() + " " + r.getMessage()).containsEntry("taskId", "1827");
        assertThat(collected).doesNotContainKey("actualQuantity").doesNotContainKey("notes");
        assertThat(r.isCompleted()).isFalse();   // 产量仍缺 → 继续追问, 不出带 0 的确认卡
    }

    @Test
    void spokenQuantityAndSpokenNoteAreAccepted() {
        String sid = start();
        when(llm.chat(anyString(), anyString())).thenReturn(
                "{\"extracted\":{\"taskId\":\"1827\",\"actualQuantity\":\"0\",\"notes\":\"设备故障\"}}");

        ConversationService.ConversationResponse r = service.continueConversation(
                FACTORY_ID, USER_ID, sid, "1827 产量0 设备故障");

        Map<String, String> collected = saved.get().getCollectedParameters();
        assertThat(collected).containsEntry("taskId", "1827")
                .containsEntry("actualQuantity", "0").containsEntry("notes", "设备故障");
        assertThat(r.isCompleted()).isTrue();
    }
}
