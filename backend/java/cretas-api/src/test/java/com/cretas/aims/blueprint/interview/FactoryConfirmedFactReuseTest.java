package com.cretas.aims.blueprint.interview;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cretas.aims.controller.PlatformAssemblyInterviewController.FactoryBuildProgress;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.entity.conversation.FactoryConfirmedFact;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.FactoryConfirmedFactRepository;
import com.cretas.aims.repository.conversation.FactoryAssemblyConversationSessionRepository;
import com.cretas.aims.security.AuthenticatedActorType;
import com.cretas.aims.service.FactoryAssemblyConversationService;
import com.cretas.aims.service.FactoryAssemblyConversationService.ConversationActor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * S2-01 / S2-02 through the real interview entry points (start conversation, submit answers):
 * a second interview reuses what the factory confirmed and asks only about differences; a different
 * answer never silently replaces a confirmed fact and is settled per item with the history kept.
 */
class FactoryConfirmedFactReuseTest {
    private static final String F = "F-REUSE";
    private static final ConversationActor ACTOR = new ConversationActor(AuthenticatedActorType.FACTORY_USER, 7L);

    private final List<FactoryConfirmedFact> store = new ArrayList<>();
    private final List<FactoryAssemblyConversationSession> sessions = new ArrayList<>();
    private FactoryBuildInterviewService interview;
    private FactoryConfirmedFactService facts;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void wire() {
        FactoryConfirmedFactRepository repo = mock(FactoryConfirmedFactRepository.class);
        when(repo.save(any())).thenAnswer(call -> {
            FactoryConfirmedFact row = call.getArgument(0);
            if (row.getId() == null) row.setId(UUID.randomUUID().toString());
            if (!store.contains(row)) store.add(row);
            return row;
        });
        when(repo.saveAndFlush(any())).thenAnswer(call -> repo.save(call.<FactoryConfirmedFact>getArgument(0)));
        when(repo.findByFactoryIdAndFieldKeyAndStatus(anyString(), anyString(), anyString())).thenAnswer(call ->
                store.stream().filter(row -> row.getFactoryId().equals(call.getArgument(0))
                        && row.getFieldKey().equals(call.getArgument(1))
                        && row.getStatus().equals(call.getArgument(2))).findFirst());
        when(repo.findByFactoryIdAndStatus(anyString(), anyString())).thenAnswer(call ->
                store.stream().filter(row -> row.getFactoryId().equals(call.getArgument(0))
                        && row.getStatus().equals(call.getArgument(1))).toList());
        when(repo.findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(anyString(), anyString()))
                .thenAnswer(call -> store.stream().filter(row -> row.getFactoryId().equals(call.getArgument(0))
                        && row.getFieldKey().equals(call.getArgument(1)))
                        .max(java.util.Comparator.comparingInt(FactoryConfirmedFact::getVersionNo)));
        when(repo.findByFactoryIdOrderByFieldKeyAscVersionNoDescCreatedAtDesc(anyString())).thenAnswer(call ->
                store.stream().filter(row -> row.getFactoryId().equals(call.getArgument(0))).toList());
        facts = new FactoryConfirmedFactService(repo);

        FactoryAssemblyConversationSessionRepository sessionRepo = mock(FactoryAssemblyConversationSessionRepository.class);
        when(sessionRepo.findBySessionIdAndFactoryIdAndUserIdAndActorType(anyString(), anyString(), any(), any()))
                .thenAnswer(call -> sessions.stream()
                        .filter(session -> session.getSessionId().equals(call.getArgument(0))).findFirst());
        when(sessionRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(sessionRepo.findBySessionIdAndFactoryId(anyString(), anyString())).thenAnswer(call -> sessions.stream()
                .filter(session -> session.getSessionId().equals(call.getArgument(0))
                        && session.getFactoryId().equals(call.getArgument(1))).findFirst());
        when(sessionRepo.findRecentByActor(anyString(), any(), any())).thenAnswer(call -> {
            List<FactoryAssemblyConversationSession> newestFirst = new ArrayList<>(sessions.stream()
                    .filter(session -> call.getArgument(0).equals(session.getFactoryId())
                            && java.util.Objects.equals(call.getArgument(1), session.getUserId())
                            && call.getArgument(2) == session.getActorType()).toList());
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        });
        when(sessionRepo.findTop20ByFactoryIdAndKnownIntentCodeOrderByCreatedAtDesc(anyString(), anyString())).thenAnswer(call -> {
            List<FactoryAssemblyConversationSession> newestFirst = new ArrayList<>(sessions.stream()
                    .filter(session -> call.getArgument(0).equals(session.getFactoryId())).toList());
            java.util.Collections.reverse(newestFirst);
            return newestFirst;
        });

        FactoryAssemblyConversationService conversations = mock(FactoryAssemblyConversationService.class);
        when(conversations.startParameterCollection(anyString(), any(ConversationActor.class), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> {
                    FactoryAssemblyConversationSession session =
                            FactoryAssemblyConversationSession.createForParameterCollection(
                                    call.getArgument(0), 7L, AuthenticatedActorType.FACTORY_USER,
                                    call.getArgument(2), call.getArgument(4), "开场");
                    ReflectionTestUtils.setField(session, "sessionId", UUID.randomUUID().toString());
                    sessions.add(session);
                    return FactoryAssemblyConversationService.ConversationResponse.builder()
                            .sessionId(session.getSessionId()).build();
                });

        interview = new FactoryBuildInterviewService();
        ReflectionTestUtils.setField(interview, "sessionRepository", sessionRepo);
        ReflectionTestUtils.setField(interview, "conversationService", conversations);
        ReflectionTestUtils.setField(interview, "confirmedFacts", facts);
        ReflectionTestUtils.setField(interview, "platformRecommendationLibrary", new PlatformRecommendationLibrary());
    }

    private String startSession() {
        return interview.startFactoryBuildConversation(F, ACTOR, true).getSessionId();
    }

    @Test
    void secondInterviewReusesConfirmedFactsAndAsksOnlyAboutTheRest() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄", "industryHint", "卤味熟食"));
        assertThat(store).extracting(FactoryConfirmedFact::getFieldKey)
                .containsExactlyInAnyOrder("businessType", "industryHint");

        String second = startSession();
        FactoryBuildProgress progress = interview.progress(F, second, ACTOR);

        assertThat(progress.getReusedFacts())
                .containsEntry("businessType", "卤猪蹄").containsEntry("industryHint", "卤味熟食");
        assertThat(progress.getPending()).doesNotContain("businessType", "industryHint");
        assertThat(progress.getAnswered()).contains("businessType", "industryHint");
        assertThat(progress.getFactEvidence()).filteredOn(item -> "businessType".equals(item.getField()))
                .singleElement().satisfies(item -> assertThat(item.getReasonCode())
                        .isEqualTo(FactoryConfirmedFactService.REUSE_REASON_CODE));
        assertThat(store).as("沿用不新增历史行").hasSize(2);
    }

    /** 模拟自由文本对话路径: 只有会话里的回执和 collected, 没有写 factory_confirmed_fact。 */
    private String conversationalVersion(Map<String, String> answers, boolean withReceipts) {
        String id = startSession();
        FactoryAssemblyConversationSession session = sessions.stream()
                .filter(s -> s.getSessionId().equals(id)).findFirst().orElseThrow();
        answers.forEach(session::addCollectedParameter);
        if (withReceipts) {
            List<FactoryAssemblyConversationSession.ReplyFieldReceipt> fields = new ArrayList<>();
            answers.forEach((field, value) -> fields.add(FactoryAssemblyConversationSession.ReplyFieldReceipt.builder()
                    .field(field).resolution("ACCEPTED").canonicalValue(value).reasonCode("VALIDATED").build()));
            FactoryAssemblyConversationSession.Message message = FactoryAssemblyConversationSession.Message.user("答复");
            message.setReplyReceipt(FactoryAssemblyConversationSession.ReplyReceipt.builder()
                    .extractionOutcome("SUCCESS").fieldReceipts(fields).build());
            session.addMessage(message);
        }
        return id;
    }

    @Test
    void freeTextValuesCarryOverAsPendingConfirmationNeverAsConfirmedFacts() {
        conversationalVersion(Map.of("businessType", "卤猪蹄", "industryHint", "卤味熟食"), true);

        String next = startSession();
        FactoryBuildProgress progress = interview.progress(F, next, ACTOR);

        assertThat(progress.getReusedFacts()).as("AI 提取的值不是用户确认的, 不能当已确认事实沿用").isEmpty();
        assertThat(progress.getPending()).as("也不算已回答").contains("businessType", "industryHint");
        assertThat(progress.getCarriedFacts()).extracting(FactoryConfirmedFactService.PendingItem::field)
                .containsExactlyInAnyOrder("businessType", "industryHint");
        assertThat(progress.getCarriedFacts()).allSatisfy(item -> assertThat(item.label()).isNotBlank());
        assertThat(store).extracting(FactoryConfirmedFact::getStatus)
                .containsOnly(FactoryConfirmedFact.PENDING_CONFIRM);
    }

    @Test
    void confirmingTheCarriedValuesTurnsThemIntoConfirmedFactsAndAnsweredQuestions() {
        conversationalVersion(Map.of("businessType", "卤猪蹄", "industryHint", "卤味熟食"), true);
        String next = startSession();

        FactoryBuildProgress progress = interview.confirmCarriedFacts(F, next, ACTOR, null);

        assertThat(progress.getAnswered()).contains("businessType", "industryHint");
        assertThat(progress.getCarriedFacts()).isEmpty();
        assertThat(store).extracting(FactoryConfirmedFact::getStatus).containsOnly(FactoryConfirmedFact.CONFIRMED);
        assertThat(store).hasSize(2);
        assertThat(store).allSatisfy(row -> assertThat(row.getDecidedBy()).startsWith("FACTORY_USER:"));
    }

    @Test
    void confirmingOneItemLeavesTheOthersWaitingAndAChangedValueSupersedesTheCarriedOne() {
        conversationalVersion(Map.of("businessType", "卤猪蹄", "industryHint", "卤味熟食"), true);
        String next = startSession();

        FactoryBuildProgress progress = interview.confirmCarriedFacts(F, next, ACTOR, List.of("industryHint"));
        assertThat(progress.getAnswered()).contains("industryHint").doesNotContain("businessType");
        assertThat(progress.getCarriedFacts()).extracting(FactoryConfirmedFactService.PendingItem::field)
                .containsExactly("businessType");

        interview.submitStructuredAnswers(F, next, ACTOR, Map.of("businessType", "卤鸭掌"));
        assertThat(store).filteredOn(row -> "businessType".equals(row.getFieldKey()))
                .extracting(FactoryConfirmedFact::getStatus)
                .containsExactlyInAnyOrder(FactoryConfirmedFact.SUPERSEDED, FactoryConfirmedFact.CONFIRMED);
        assertThat(effective("businessType")).isEqualTo("卤鸭掌");
    }

    @Test
    void repeatedRestartsNeverDuplicateOrLoseTheCarryOver() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("industryHint", "卤味熟食"));
        conversationalVersion(Map.of("businessType", "卤猪蹄"), true);

        for (int i = 0; i < 3; i++) {
            FactoryBuildProgress progress = interview.progress(F, startSession(), ACTOR);
            assertThat(progress.getReusedFacts()).containsEntry("industryHint", "卤味熟食");
            assertThat(progress.getCarriedFacts()).extracting(FactoryConfirmedFactService.PendingItem::field)
                    .contains("businessType");
        }
        assertThat(store).filteredOn(row -> "businessType".equals(row.getFieldKey())).hasSize(1);
        assertThat(store).filteredOn(row -> "industryHint".equals(row.getFieldKey())).hasSize(1);
    }

    @Test
    void aConcurrentRestartThatInsertedTheSameItemFirstOnlyCostsThatItem() {
        FactoryConfirmedFactRepository repo = mock(FactoryConfirmedFactRepository.class);
        when(repo.findFirstByFactoryIdAndFieldKeyOrderByVersionNoDescCreatedAtDesc(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(call -> {
            FactoryConfirmedFact row = call.getArgument(0);
            if ("raced".equals(row.getFieldKey())) {
                throw new org.springframework.dao.DataIntegrityViolationException("uq_fcf_one_pending_confirm_per_field");
            }
            return row;
        });
        FactoryConfirmedFactService service = new FactoryConfirmedFactService(repo);

        int adopted = service.adoptPriorFacts(F, List.of(
                new FactoryConfirmedFactService.PriorFact("raced", "x", "1", "s1", false),
                new FactoryConfirmedFactService.PriorFact("other", "y", "2", "s1", false)));

        assertThat(adopted).as("撞唯一索引的那一项跳过, 其余照常带入").isEqualTo(1);
    }

    @Test
    void theNewestVersionWinsAndAnOlderValueIsOnlyUsedWhenTheNewestDidNotTouchTheItem() {
        String older = startSession();
        interview.submitStructuredAnswers(F, older, ACTOR, Map.of("businessType", "卤猪蹄", "industryHint", "卤味熟食"));
        String newest = startSession();
        // 最新一版改了 businessType (未确认, 没有回执) ⇒ 不能退回更早的「卤猪蹄」
        sessions.stream().filter(session -> session.getSessionId().equals(newest)).findFirst().orElseThrow()
                .addCollectedParameter("businessType", "卤鸭掌");
        store.clear();

        String probeId = startSession();
        FactoryAssemblyConversationSession probe = sessions.stream()
                .filter(session -> session.getSessionId().equals(probeId)).findFirst().orElseThrow();
        List<FactoryConfirmedFactService.PriorFact> found = interview.priorSessionFacts(F, probe);

        assertThat(found).extracting(FactoryConfirmedFactService.PriorFact::field).doesNotContain("businessType");
        assertThat(found).filteredOn(fact -> "industryHint".equals(fact.field())).singleElement()
                .satisfies(fact -> assertThat(fact.userDecided()).isTrue());
    }

    @Test
    void theRealFreeTextReplyPathRecordsExtractedValuesAsPendingAndExactOptionsAsConfirmed() {
        com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl replies =
                new com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl(
                        mock(FactoryAssemblyConversationSessionRepository.class), null, null, null);
        ReflectionTestUtils.setField(replies, "confirmedFacts", facts);
        String id = startSession();
        FactoryAssemblyConversationSession session = sessions.stream()
                .filter(s -> s.getSessionId().equals(id)).findFirst().orElseThrow();

        ReflectionTestUtils.invokeMethod(replies, "recordConfirmedFacts", session,
                Map.of("businessType", "卤猪蹄"), false);
        assertThat(store).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(FactoryConfirmedFact.PENDING_CONFIRM);
            assertThat(row.getFieldLabel()).isNotBlank();
        });

        ReflectionTestUtils.invokeMethod(replies, "recordConfirmedFacts", session,
                Map.of("industryHint", "卤味熟食"), true);
        assertThat(effective("industryHint")).isEqualTo("卤味熟食");
        assertThat(store).filteredOn(row -> "businessType".equals(row.getFieldKey()))
                .extracting(FactoryConfirmedFact::getStatus).containsOnly(FactoryConfirmedFact.PENDING_CONFIRM);
    }

    /** 真实调用点: continueConversation 把 AI 提取的值写进账本, 只能是待确认, 不能是已确认。 */
    @Test
    void aFreeTextReplyDrivenThroughTheRealReplyEntryRecordsOnlyPendingConfirmation() {
        com.cretas.aims.ai.client.DashScopeClient llm = mock(com.cretas.aims.ai.client.DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        com.cretas.aims.ai.dto.ChatCompletionResponse completion = mock(com.cretas.aims.ai.dto.ChatCompletionResponse.class);
        when(completion.hasError()).thenReturn(false);
        when(completion.getContent()).thenReturn("{\"outcome\":\"SUCCESS\",\"extracted\":{\"businessType\":\"卤猪蹄\"}}");
        when(llm.chatCompletion(any())).thenReturn(completion);

        String id = startSession();
        FactoryAssemblyConversationSession session = sessions.stream()
                .filter(s -> s.getSessionId().equals(id)).findFirst().orElseThrow();
        FactoryAssemblyConversationSessionRepository replyRepo = mock(FactoryAssemblyConversationSessionRepository.class);
        when(replyRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(replyRepo.findReplyTargetForUpdate(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(session));
        com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl replies =
                new com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl(replyRepo, null, null, llm);
        ReflectionTestUtils.setField(replies, "confirmedFacts", facts);

        FactoryAssemblyConversationService.ConversationResponse response =
                replies.continueConversation(F, ACTOR, id, "主要做卤猪蹄，卖给批发商", "req-1");

        assertThat(session.getCollectedParameters())
                .as("回复确实走通了, 值进了会话; 实际回复: %s / %s", response.getStatus(), response.getMessage())
                .containsEntry("businessType", "卤猪蹄");
        assertThat(response.getSessionId()).isEqualTo(id);
        assertThat(store).singleElement().satisfies(row -> {
            assertThat(row.getFieldKey()).isEqualTo("businessType");
            assertThat(row.getStatus()).as("自由文本提取的值只是待确认").isEqualTo(FactoryConfirmedFact.PENDING_CONFIRM);
        });
        assertThat(facts.openConflicts(F)).isEmpty();
    }

    /** S2-02 现场: 已确认的工序道数=3, 用户在新版本里用自由文本想改成 4。 */
    @Test
    void anAlreadyConfirmedItemBecomesAConflictOnlyWhenTheReplyIsPhrasedAsACorrection() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("stepCount", "3"));
        String second = startSession();
        FactoryAssemblyConversationSession session = sessions.stream()
                .filter(s -> s.getSessionId().equals(second)).findFirst().orElseThrow();
        assertThat(session.getCollectedParameters()).containsEntry("stepCount", "3");

        com.cretas.aims.ai.client.DashScopeClient llm = mock(com.cretas.aims.ai.client.DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        com.cretas.aims.ai.dto.ChatCompletionResponse completion = mock(com.cretas.aims.ai.dto.ChatCompletionResponse.class);
        when(completion.hasError()).thenReturn(false);
        when(completion.getContent()).thenReturn("{\"outcome\":\"SUCCESS\",\"extracted\":{\"stepCount\":\"4\"}}");
        when(llm.chatCompletion(any())).thenReturn(completion);
        FactoryAssemblyConversationSessionRepository replyRepo = mock(FactoryAssemblyConversationSessionRepository.class);
        when(replyRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(replyRepo.findReplyTargetForUpdate(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(session));
        com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl replies =
                new com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl(replyRepo, null, null, llm);
        ReflectionTestUtils.setField(replies, "confirmedFacts", facts);

        replies.continueConversation(F, ACTOR, second, "一共4道工序", "r1");
        assertThat(facts.openConflicts(F)).as("没有「改成」之类的更正措辞: 已回答的项不是提取候选, 不产生冲突").isEmpty();
        assertThat(session.getCollectedParameters()).containsEntry("stepCount", "3");

        replies.continueConversation(F, ACTOR, second, "工序道数改成4道", "r2");
        assertThat(facts.openConflicts(F)).singleElement().satisfies(conflict -> {
            assertThat(conflict.field()).isEqualTo("stepCount");
            assertThat(conflict.confirmedValue()).isEqualTo("3");
            assertThat(conflict.newValue()).isEqualTo("4");
        });
        assertThat(effective("stepCount")).as("已确认事实在选来源之前不被改写").isEqualTo("3");
    }

    private com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl replier(
            FactoryAssemblyConversationSession session, String extractedJson) {
        com.cretas.aims.ai.client.DashScopeClient llm = mock(com.cretas.aims.ai.client.DashScopeClient.class);
        when(llm.isAvailable()).thenReturn(true);
        com.cretas.aims.ai.dto.ChatCompletionResponse completion = mock(com.cretas.aims.ai.dto.ChatCompletionResponse.class);
        when(completion.hasError()).thenReturn(false);
        when(completion.getContent()).thenReturn("{\"outcome\":\"SUCCESS\",\"extracted\":" + extractedJson + "}");
        when(llm.chatCompletion(any())).thenReturn(completion);
        FactoryAssemblyConversationSessionRepository replyRepo = mock(FactoryAssemblyConversationSessionRepository.class);
        when(replyRepo.save(any())).thenAnswer(call -> call.getArgument(0));
        when(replyRepo.findReplyTargetForUpdate(anyString(), anyString(), any(), any()))
                .thenReturn(Optional.of(session));
        com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl replies =
                new com.cretas.aims.service.impl.FactoryAssemblyConversationServiceImpl(replyRepo, null, null, llm);
        ReflectionTestUtils.setField(replies, "confirmedFacts", facts);
        return replies;
    }

    private FactoryAssemblyConversationSession sessionOf(String id) {
        return sessions.stream().filter(s -> s.getSessionId().equals(id)).findFirst().orElseThrow();
    }

    private static final ConversationActor OTHER_USER = new ConversationActor(AuthenticatedActorType.FACTORY_USER, 8L);

    /** D-S2-06: 去别的页面 (如事实页选来源) 超过 10 分钟再回来, 进行中的新版本不能被已完成的旧访谈顶掉。 */
    @Test
    void anIdleExpiredInProgressVersionIsResumedWithItsAnswersInsteadOfFallingBackToTheCompletedOne() {
        String completed = startSession();
        ReflectionTestUtils.setField(sessionOf(completed), "status",
                FactoryAssemblyConversationSession.SessionStatus.COMPLETED);
        String active = startSession();
        FactoryAssemblyConversationSession v2 = sessionOf(active);
        v2.addCollectedParameter("businessType", "卤猪蹄");
        ReflectionTestUtils.setField(v2, "lastActiveAt", java.time.LocalDateTime.now().minusHours(2));
        assertThat(v2.isExpired()).as("前提: 空闲上限 10 分钟已过").isTrue();

        FactoryBuildProgress recovered = interview.recoverableProgress(F, ACTOR).orElseThrow();

        assertThat(recovered.getSessionId()).as("进行中的新版本仍是当前版本, 不退回已完成的旧访谈").isEqualTo(active);
        assertThat(recovered.getCollected()).containsEntry("businessType", "卤猪蹄");
        assertThat(v2.getStatus()).isEqualTo(FactoryAssemblyConversationSession.SessionStatus.ACTIVE);
        assertThat(v2.isExpired()).as("恢复只是重新计时, 会话自己的 10 分钟上限不变").isFalse();
        assertThat(v2.getTimeoutMinutes()).isEqualTo(10);
    }

    @Test
    void aTimedOutInProgressVersionWithinTheWindowIsResumedToo() {
        String completed = startSession();
        ReflectionTestUtils.setField(sessionOf(completed), "status",
                FactoryAssemblyConversationSession.SessionStatus.COMPLETED);
        String active = startSession();
        FactoryAssemblyConversationSession v2 = sessionOf(active);
        v2.timeout();
        ReflectionTestUtils.setField(v2, "lastActiveAt", java.time.LocalDateTime.now().minusHours(3));

        assertThat(interview.recoverableProgress(F, ACTOR).orElseThrow().getSessionId()).isEqualTo(active);
        assertThat(v2.getStatus()).isEqualTo(FactoryAssemblyConversationSession.SessionStatus.ACTIVE);
    }

    @Test
    void nothingIsResurrectedForAnotherUserAnAbandonedVersionOrOneOutsideTheWindow() {
        String completed = startSession();
        ReflectionTestUtils.setField(sessionOf(completed), "status",
                FactoryAssemblyConversationSession.SessionStatus.COMPLETED);
        String idle = startSession();
        FactoryAssemblyConversationSession v2 = sessionOf(idle);
        ReflectionTestUtils.setField(v2, "lastActiveAt", java.time.LocalDateTime.now().minusHours(2));

        // another user never sees (or revives) this user's version
        assertThat(interview.recoverableProgress(F, OTHER_USER)).isEmpty();
        assertThat(v2.getStatus()).isEqualTo(FactoryAssemblyConversationSession.SessionStatus.ACTIVE);
        assertThat(v2.isExpired()).as("别人查询没有重新计时").isTrue();

        // outside the 24h window: back to the completed one
        ReflectionTestUtils.setField(v2, "lastActiveAt", java.time.LocalDateTime.now().minusHours(30));
        assertThat(interview.recoverableProgress(F, ACTOR).orElseThrow().getSessionId()).isEqualTo(completed);

        // explicitly abandoned / superseded (CANCELLED): not resurrected
        ReflectionTestUtils.setField(v2, "lastActiveAt", java.time.LocalDateTime.now().minusHours(2));
        ReflectionTestUtils.setField(v2, "status", FactoryAssemblyConversationSession.SessionStatus.CANCELLED);
        assertThat(interview.recoverableProgress(F, ACTOR).orElseThrow().getSessionId()).isEqualTo(completed);

        // an idle version that a newer (now cancelled) restart superseded: the older one stays dead
        ReflectionTestUtils.setField(v2, "status", FactoryAssemblyConversationSession.SessionStatus.TIMEOUT);
        String restarted = startSession();
        ReflectionTestUtils.setField(sessionOf(restarted), "status",
                FactoryAssemblyConversationSession.SessionStatus.CANCELLED);
        assertThat(interview.recoverableProgress(F, ACTOR).orElseThrow().getSessionId()).isEqualTo(completed);
        assertThat(v2.getStatus()).isEqualTo(FactoryAssemblyConversationSession.SessionStatus.TIMEOUT);

        // another factory
        assertThat(interview.recoverableProgress("F-OTHER", ACTOR)).isEmpty();
    }

    /** 选完来源后, 还在进行的访谈要和已确认事实一致 (保留已确认值 → 会话退回原值; 改用新回答 → 保持新值)。 */
    @Test
    void resolvingAConflictBringsTheLiveInterviewInLineWithTheChosenSource() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("stepCount", "3"));
        String second = startSession();
        FactoryAssemblyConversationSession live = sessionOf(second);
        var replies = replier(live, "{\"stepCount\":\"4\"}");

        replies.continueConversation(F, ACTOR, second, "工序道数改成4道", "r1");
        assertThat(live.getCollectedParameters()).containsEntry("stepCount", "4");
        String answeredIn = facts.openConflicts(F).get(0).newSource();

        facts.resolve(F, "stepCount", FactoryConfirmedFactService.CHOICE_KEEP_CONFIRMED, "FACTORY_USER:7");
        interview.syncSessionToConfirmedFact(F, answeredIn, "stepCount");
        assertThat(live.getCollectedParameters()).as("保留已确认值: 进行中的访谈退回 3").containsEntry("stepCount", "3");

        replier(live, "{\"stepCount\":\"5\"}").continueConversation(F, ACTOR, second, "工序道数改成5道", "r3");
        String answeredIn2 = facts.openConflicts(F).get(0).newSource();
        facts.resolve(F, "stepCount", FactoryConfirmedFactService.CHOICE_USE_NEW, "FACTORY_USER:7");
        interview.syncSessionToConfirmedFact(F, answeredIn2, "stepCount");
        assertThat(effective("stepCount")).as("改用新回答: 已确认事实变成新值").isEqualTo("5");
        assertThat(live.getCollectedParameters()).containsEntry("stepCount", "5");
        assertThat(facts.history(F)).filteredOn(row -> "stepCount".equals(row.getFieldKey()))
                .extracting(FactoryConfirmedFact::getStatus)
                .containsExactlyInAnyOrder(FactoryConfirmedFact.SUPERSEDED, FactoryConfirmedFact.REJECTED,
                        FactoryConfirmedFact.CONFIRMED);
    }

    /** 选择题: 用户的说法按「唯一可对上的选项」映射; 对不上或有歧义 → 不猜, 也不产生冲突。 */
    @Test
    void choiceCorrectionsAreMappedToAValidOptionOrRejectedNeverGuessed() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("customerSupplied", "没有", "industryHint", "卤味熟食"));
        String second = startSession();
        FactoryAssemblyConversationSession live = sessionOf(second);

        // 「有部分」= 选项「有(部分)」(括号/标点不敏感)
        replier(live, "{\"customerSupplied\":\"有部分\"}").continueConversation(F, ACTOR, second, "客供料改成有部分", "r1");
        assertThat(facts.openConflicts(F)).singleElement().satisfies(conflict -> {
            assertThat(conflict.field()).isEqualTo("customerSupplied");
            assertThat(conflict.confirmedValue()).isEqualTo("没有");
            assertThat(conflict.newValue()).as("映射到合法选项而不是原话").isEqualTo("有(部分)");
        });

        // 「有」同时是「有(部分)」「有(主要是代加工)」的前缀, 有歧义 → 拒绝, 不产生新冲突
        replier(live, "{\"industryHint\":\"预\"}").continueConversation(F, ACTOR, second, "行业归类改成预", "r2");
        // 说出的整句里只含一个选项 → 唯一映射
        replier(live, "{\"industryHint\":\"改做预制菜类\"}").continueConversation(F, ACTOR, second, "行业归类改成改做预制菜类", "r3");
        assertThat(facts.openConflicts(F)).extracting(FactoryConfirmedFactService.Conflict::field)
                .containsExactlyInAnyOrder("customerSupplied", "industryHint");
        assertThat(facts.openConflicts(F)).filteredOn(conflict -> "industryHint".equals(conflict.field()))
                .singleElement().satisfies(conflict -> assertThat(conflict.newValue()).isEqualTo("预制菜"));
    }

    /** 评审 #1312(b): 说法里点了两个选项、或带否定词 → 不映射, 不产生冲突, 值不变。 */
    @Test
    void aValueNamingTwoOptionsOrCarryingANegationIsNeverMappedToAnOption() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR,
                Map.of("industryHint", "卤味熟食", "customerSupplied", "有(部分)"));
        String second = startSession();
        FactoryAssemblyConversationSession live = sessionOf(second);

        // 两个选项都在里面: 不能因为「更长的赢」而选中已确认的那个、吞掉更正
        replier(live, "{\"industryHint\":\"卤味熟食转做预制菜了\"}")
                .continueConversation(F, ACTOR, second, "行业归类改成卤味熟食转做预制菜了", "r1");
        // 否定词: 「不是没有」不能变成「没有」, 「去掉预制菜」不能变成「预制菜」
        replier(live, "{\"customerSupplied\":\"不是没有\"}")
                .continueConversation(F, ACTOR, second, "客供料改成不是没有", "r2");
        replier(live, "{\"industryHint\":\"去掉预制菜\"}")
                .continueConversation(F, ACTOR, second, "行业归类改成去掉预制菜", "r3");

        assertThat(facts.openConflicts(F)).isEmpty();
        assertThat(live.getCollectedParameters()).containsEntry("industryHint", "卤味熟食")
                .containsEntry("customerSupplied", "有(部分)");
        assertThat(effective("industryHint")).isEqualTo("卤味熟食");
        assertThat(effective("customerSupplied")).isEqualTo("有(部分)");
    }

    /** 没有更正措辞的陈述不会让已回答的选择题变成冲突。 */
    @Test
    void aPlainStatementOnAnAnsweredChoiceItemWithoutCorrectionWordingCreatesNoConflict() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("industryHint", "卤味熟食"));
        String second = startSession();
        FactoryAssemblyConversationSession live = sessionOf(second);

        replier(live, "{\"industryHint\":\"预制菜\"}").continueConversation(F, ACTOR, second, "行业是预制菜", "r1");

        assertThat(facts.openConflicts(F)).isEmpty();
        assertThat(live.getCollectedParameters()).containsEntry("industryHint", "卤味熟食");
    }

    /** 同一个映射器也管首次回答: 精确/标点不敏感/唯一包含 → 收下(待确认); 否则不收, 继续问。 */
    @Test
    void firstTimeChoiceAnswersGoThroughTheSameRules() {
        String id = startSession();
        FactoryAssemblyConversationSession live = sessionOf(id);

        replier(live, "{\"customerSupplied\":\"不是没有\"}").continueConversation(F, ACTOR, id, "客供料不是没有", "r1");
        replier(live, "{\"industryHint\":\"卤味熟食转做预制菜了\"}").continueConversation(F, ACTOR, id, "行业归类卤味熟食转做预制菜了", "r2");
        assertThat(live.getCollectedParameters()).doesNotContainKeys("customerSupplied", "industryHint");

        replier(live, "{\"industryHint\":\"预制菜\"}").continueConversation(F, ACTOR, id, "行业是预制菜", "r3");
        assertThat(live.getCollectedParameters()).containsEntry("industryHint", "预制菜");
        assertThat(store).singleElement().satisfies(row -> {
            assertThat(row.getFieldKey()).isEqualTo("industryHint");
            assertThat(row.getStatus()).as("首次自由文本回答只是待确认").isEqualTo(FactoryConfirmedFact.PENDING_CONFIRM);
        });
    }

    private static final Map<String, Object> SEQUENTIAL_PRODUCT = Map.of(
            "product1.name", "冷藏卤牛肉", "product1.steps", "原料解冻、修整清洗、焯水",
            "product1.routeShape", "仅顺序", "product1.routeDetails", "没有",
            "product1.routeEntry", "原料解冻", "product1.routeTerminal", "焯水");

    private boolean isDeferred(FactoryAssemblyConversationSession session, String name) {
        return session.getRequiredParameters().stream()
                .anyMatch(rp -> name.equals(rp.getName()) && rp.isUserDeferred());
    }

    /** D-S2-08 现场: 仅顺序路线的连线被「暂时不确定」后, 补全路线事实就该自动确定, 而不是永远「还差1项」。 */
    @Test
    void aSequentialRoutesTopologyIsDerivedEvenAfterTheUserDeferredIt() {
        String id = startSession();
        interview.submitStructuredAnswers(F, id, ACTOR, Map.of("stepCount", "3", "productCount", "1"));
        interview.submitStructuredAnswers(F, id, ACTOR,
                Map.of("product1.routeTopology", com.cretas.aims.blueprint.interview.FactoryDecisionPacket.UNKNOWN_ANSWER_VALUE));
        FactoryAssemblyConversationSession session = sessionOf(id);
        assertThat(isDeferred(session, "product1.routeTopology")).as("前提: 已被「暂时不确定」").isTrue();

        FactoryBuildProgress progress = interview.submitStructuredAnswers(F, id, ACTOR, SEQUENTIAL_PRODUCT);

        assertThat(progress.getCollected()).containsEntry("product1.routeTopology", "按顺序自动确定");
        assertThat(isDeferred(session, "product1.routeTopology")).as("明确的值解除了暂缓").isFalse();
        assertThat(progress.getPending()).doesNotContain("product1.routeTopology");
        assertThat(progress.getUnresolvedDecisions()).extracting(d -> d.getField())
                .doesNotContain("product1.routeTopology");
        assertThat(progress.getFactEvidence()).filteredOn(item -> "product1.routeTopology".equals(item.getField()))
                .singleElement().satisfies(item -> assertThat(item.getReasonCode())
                        .isEqualTo(FactoryInterviewExpansion.DERIVED_SEQUENTIAL_TOPOLOGY_REASON));
    }

    /** 复杂路线 (并行/分支/合流/返工) 的连线必须用户逐条确认: 不自动填。 */
    @Test
    void aComplexRoutesTopologyIsNeverDerived() {
        String id = startSession();
        interview.submitStructuredAnswers(F, id, ACTOR, Map.of("stepCount", "3", "productCount", "1"));
        Map<String, Object> complex = new java.util.LinkedHashMap<>(SEQUENTIAL_PRODUCT);
        complex.put("product1.routeShape", "有并行");
        complex.put("product1.routeDetails", "修整清洗后分两路，焯水前合流");

        FactoryBuildProgress progress = interview.submitStructuredAnswers(F, id, ACTOR, complex);

        assertThat(progress.getCollected()).doesNotContainKey("product1.routeTopology");
        assertThat(progress.getPending()).contains("product1.routeTopology");
    }

    /** 线上卡死的会话 (已超时、连线被暂缓、仅顺序): 恢复时补上, 页面不再停在「还差1项」。 */
    @Test
    void aStuckTimedOutSessionWithADeferredSequentialTopologyIsUnstuckOnResume() {
        String completed = startSession();
        ReflectionTestUtils.setField(sessionOf(completed), "status",
                FactoryAssemblyConversationSession.SessionStatus.COMPLETED);
        String id = startSession();
        interview.submitStructuredAnswers(F, id, ACTOR, Map.of("stepCount", "3", "productCount", "1"));
        interview.submitStructuredAnswers(F, id, ACTOR, SEQUENTIAL_PRODUCT);
        FactoryAssemblyConversationSession session = sessionOf(id);
        // 还原线上状态: 连线没有值 + 被暂缓, 会话已超时
        session.removeCollectedParameter("product1.routeTopology");
        session.deferParameter("product1.routeTopology");
        session.timeout();
        ReflectionTestUtils.setField(session, "lastActiveAt", java.time.LocalDateTime.now().minusHours(1));
        assertThat(session.getCollectedParameters()).doesNotContainKey("product1.routeTopology");

        FactoryBuildProgress recovered = interview.recoverableProgress(F, ACTOR).orElseThrow();

        assertThat(recovered.getSessionId()).isEqualTo(id);
        assertThat(recovered.getCollected()).containsEntry("product1.routeTopology", "按顺序自动确定");
        assertThat(recovered.getPending()).doesNotContain("product1.routeTopology");
        assertThat(recovered.getUnresolvedDecisions()).extracting(d -> d.getField())
                .doesNotContain("product1.routeTopology");
    }

    @Test
    void anAmbiguousChoiceCorrectionIsRejectedWithoutAConflict() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("customerSupplied", "没有"));
        String second = startSession();
        FactoryAssemblyConversationSession live = sessionOf(second);

        // 「有」是「有(部分)」「有(主要是代加工)」两个选项的前缀 → 不猜
        replier(live, "{\"customerSupplied\":\"有\"}").continueConversation(F, ACTOR, second, "客供料改成有", "r1");

        assertThat(facts.openConflicts(F)).isEmpty();
        assertThat(live.getCollectedParameters()).containsEntry("customerSupplied", "没有");
    }

    @Test
    void aMissingLabelFallsBackToAGenericNameNeverTheWordNull() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        String second = startSession();
        FactoryAssemblyConversationSession session = sessionOf(second);
        session.removeCollectedParameter("businessType");
        List<FactoryAssemblyConversationSession.RequiredParameter> params = session.getRequiredParameters();
        params.forEach(rp -> {
            if ("businessType".equals(rp.getName())) rp.setLabel(null);
        });
        session.setRequiredParameters(params);

        interview.reuseConfirmedFacts(F, session);

        String text = session.getMessages().stream().map(m -> String.valueOf(m.getContent())).reduce("", String::concat);
        assertThat(text).contains("其他事项：卤猪蹄").doesNotContain("null：");
    }

    /** 选择题 (行业归类): 说成「改成…」且是合法选项 → 产生冲突; 不是选项里的值 → 被拒, 不产生冲突。 */
    @Test
    void aChoiceItemCorrectionConflictsOnlyWhenTheNewValueIsOneOfItsOptions() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("industryHint", "卤味熟食"));
        String second = startSession();
        FactoryAssemblyConversationSession live = sessionOf(second);

        replier(live, "{\"industryHint\":\"烧烤\"}").continueConversation(F, ACTOR, second, "行业归类改成烧烤", "r1");
        assertThat(facts.openConflicts(F)).as("烧烤不在候选项里: 被拒, 不是漏检").isEmpty();

        replier(live, "{\"industryHint\":\"预制菜\"}").continueConversation(F, ACTOR, second, "行业归类改成预制菜", "r2");
        assertThat(facts.openConflicts(F)).singleElement().satisfies(conflict -> {
            assertThat(conflict.field()).isEqualTo("industryHint");
            assertThat(conflict.newValue()).isEqualTo("预制菜");
        });
    }

    @Test
    void reuseMessageNamesItemsByLabelNeverByRawFieldKey() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        String second = startSession();
        FactoryAssemblyConversationSession session = sessions.stream()
                .filter(s -> s.getSessionId().equals(second)).findFirst().orElseThrow();

        String text = session.getMessages().stream().map(m -> String.valueOf(m.getContent())).reduce("", String::concat);

        assertThat(text).contains("沿用本厂已确认资料").contains("做什么产品：卤猪蹄").doesNotContain("businessType");
    }

    @Test
    void valuesWithoutAReceiptOrChangedAfterTheirReceiptAreNeverCarried() {
        conversationalVersion(Map.of("businessType", "卤猪蹄"), false);
        String second = conversationalVersion(Map.of("industryHint", "卤味熟食"), true);
        sessions.stream().filter(s -> s.getSessionId().equals(second)).findFirst().orElseThrow()
                .addCollectedParameter("industryHint", "预制菜");

        FactoryBuildProgress progress = interview.progress(F, startSession(), ACTOR);

        assertThat(progress.getReusedFacts()).isEmpty();
        assertThat(progress.getCarriedFacts()).as("无回执的提取值、回执后被改过的都不带入").isEmpty();
        assertThat(store).isEmpty();
    }

    @Test
    void factsOfAnotherFactoryAreNeverReused() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        store.forEach(row -> row.setFactoryId("F-OTHER"));
        sessions.forEach(session -> ReflectionTestUtils.setField(session, "factoryId", "F-OTHER"));

        String second = startSession();
        assertThat(interview.progress(F, second, ACTOR).getReusedFacts()).isEmpty();
    }

    @Test
    void aDifferentAnswerBecomesAPendingConflictAndNeverOverwritesTheConfirmedFact() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        String second = startSession();

        FactoryBuildProgress progress = interview.submitStructuredAnswers(
                F, second, ACTOR, Map.of("businessType", "卤鸭掌"));

        assertThat(facts.openConflicts(F)).singleElement().satisfies(conflict -> {
            assertThat(conflict.field()).isEqualTo("businessType");
            assertThat(conflict.confirmedValue()).isEqualTo("卤猪蹄");
            assertThat(conflict.newValue()).isEqualTo("卤鸭掌");
        });
        assertThat(progress.getFactConflicts()).hasSize(1);
        assertThat(store.stream().filter(row -> FactoryConfirmedFact.CONFIRMED.equals(row.getStatus())))
                .singleElement().extracting(FactoryConfirmedFact::getValueText).isEqualTo("卤猪蹄");
    }

    @Test
    void administratorPicksTheSourcePerItemAndTheHistoryKeepsBoth() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR,
                Map.of("businessType", "卤猪蹄", "industryHint", "卤味熟食"));
        String second = startSession();
        interview.submitStructuredAnswers(F, second, ACTOR, Map.of("businessType", "卤鸭掌", "industryHint", "预制菜"));
        assertThat(facts.openConflicts(F)).hasSize(2);

        facts.resolve(F, "businessType", FactoryConfirmedFactService.CHOICE_USE_NEW, "FACTORY_USER:7");
        facts.resolve(F, "industryHint", FactoryConfirmedFactService.CHOICE_KEEP_CONFIRMED, "FACTORY_USER:7");

        assertThat(facts.openConflicts(F)).isEmpty();
        assertThat(effective("businessType")).isEqualTo("卤鸭掌");
        assertThat(effective("industryHint")).as("逐条选择: 另一项保留原值").isEqualTo("卤味熟食");
        assertThat(store).filteredOn(row -> "businessType".equals(row.getFieldKey()))
                .extracting(FactoryConfirmedFact::getStatus)
                .containsExactlyInAnyOrder(FactoryConfirmedFact.SUPERSEDED, FactoryConfirmedFact.CONFIRMED);
        assertThat(store).filteredOn(row -> "industryHint".equals(row.getFieldKey()))
                .extracting(FactoryConfirmedFact::getStatus)
                .containsExactlyInAnyOrder(FactoryConfirmedFact.CONFIRMED, FactoryConfirmedFact.REJECTED);
        FactoryConfirmedFact promoted = store.stream().filter(row -> "businessType".equals(row.getFieldKey())
                && FactoryConfirmedFact.CONFIRMED.equals(row.getStatus())).findFirst().orElseThrow();
        assertThat(promoted.getVersionNo()).isEqualTo(2);
        assertThat(promoted.getSupersedesId()).isNotNull();
        assertThat(promoted.getSourceRef()).startsWith("conversation:");
        assertThat(promoted.getDecidedBy()).isEqualTo("FACTORY_USER:7");
        assertThat(facts.history(F)).hasSize(4);
    }

    @Test
    void settlingAnItemThatHasNoConflictIsRejectedAndAnUnknownChoiceTooAndNothingChanges() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        int before = store.size();
        assertThatThrownBy(() -> facts.resolve(F, "businessType",
                FactoryConfirmedFactService.CHOICE_USE_NEW, "FACTORY_USER:7"))
                .isInstanceOf(BusinessException.class);
        String second = startSession();
        interview.submitStructuredAnswers(F, second, ACTOR, Map.of("businessType", "卤鸭掌"));
        assertThatThrownBy(() -> facts.resolve(F, "businessType", "SILENT_OVERWRITE", "FACTORY_USER:7"))
                .isInstanceOf(BusinessException.class);
        assertThat(effective("businessType")).isEqualTo("卤猪蹄");
        assertThat(store.size()).isEqualTo(before + 1);
    }

    @Test
    void answeringTheConfirmedValueAgainWritesNothingAndRetiresAnOpenConflict() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        String second = startSession();
        interview.submitStructuredAnswers(F, second, ACTOR, Map.of("businessType", "卤鸭掌"));
        assertThat(facts.openConflicts(F)).hasSize(1);

        interview.submitStructuredAnswers(F, second, ACTOR, Map.of("businessType", "卤猪蹄"));

        assertThat(facts.openConflicts(F)).isEmpty();
        assertThat(effective("businessType")).isEqualTo("卤猪蹄");
    }

    private String effective(String field) {
        return store.stream().filter(row -> field.equals(row.getFieldKey())
                && FactoryConfirmedFact.CONFIRMED.equals(row.getStatus())).findFirst().orElseThrow().getValueText();
    }
}
