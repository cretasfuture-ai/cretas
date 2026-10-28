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

    @Test
    void factsOfAnotherFactoryAreNeverReused() {
        String first = startSession();
        interview.submitStructuredAnswers(F, first, ACTOR, Map.of("businessType", "卤猪蹄"));
        store.forEach(row -> row.setFactoryId("F-OTHER"));

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
