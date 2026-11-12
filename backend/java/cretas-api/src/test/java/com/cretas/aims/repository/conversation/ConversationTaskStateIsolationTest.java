package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.service.execution.ConversationTaskStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 任务状态跑在真实 Hibernate 上 (INTENT-DISTILL-20261111 第二批): task_state_json 列、SUSPENDED 状态、
 * 按 工厂 + 本人 查栈 (跨租户 / 别人的挂起任务进不来)、栈深上限、过期、恢复。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ConversationTaskStateIsolationTest {

    @Autowired
    ConversationSessionRepository repository;
    @Autowired
    EntityManager entityManager;

    ConversationTaskStateService service;
    LocalDateTime now = LocalDateTime.now();
    String factory;
    String otherFactory;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        factory = "F-TS-A-" + suffix;
        otherFactory = "F-TS-B-" + suffix;
        service = new ConversationTaskStateService(repository);
        ReflectionTestUtils.setField(service, "enabled", true);
        Supplier<LocalDateTime> clock = () -> now;
        ReflectionTestUtils.setField(service, "clock", clock);
    }

    @Test
    void suspendedTaskPersistsItsStateAndResumesWithItsParameters() {
        ConversationSession report = active(factory, 7L, "PROCESSING_BATCH_COMPLETE");
        report.addCollectedParameter("taskId", "1827");

        service.interrupt(factory, 7L, report);
        flushAndClear();

        ConversationSession reloaded = repository.findById(report.getSessionId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SessionStatus.SUSPENDED);
        assertThat(reloaded.getTaskStateJson()).as("挂起时刻与来源真的落进了新列").contains("suspendedAt").contains("USER");
        assertThat(reloaded.getTaskState().getSources()).containsEntry("taskId", ConversationSession.ParameterSource.USER);
        assertThat(repository.findActiveByFactoryAndUser(factory, 7L)).as("挂起的不算活跃").isEmpty();

        Optional<ConversationTaskStateService.Resumed> resumed = service.tryResume(factory, 7L, "继续",
                s -> Optional.empty(), code -> "报工");
        flushAndClear();

        assertThat(resumed).isPresent();
        assertThat(resumed.get().message()).contains("报工").contains("1827").contains("还需要");
        ConversationSession after = repository.findById(report.getSessionId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(after.getCollectedParameters()).containsEntry("taskId", "1827");
    }

    @Test
    void stackIsPerFactoryAndUserNewestFirstCappedAndExpiring() {
        ConversationSession mine1 = active(factory, 7L, "INTENT_A");
        service.interrupt(factory, 7L, mine1);
        now = now.plusMinutes(1);
        ConversationSession mine2 = active(factory, 7L, "INTENT_B");
        service.interrupt(factory, 7L, mine2);
        // 别人 / 别厂的挂起任务
        service.interrupt(factory, 8L, active(factory, 8L, "INTENT_X"));
        service.interrupt(otherFactory, 7L, active(otherFactory, 7L, "INTENT_Y"));
        flushAndClear();

        assertThat(service.stack(factory, 7L)).extracting(ConversationSession::getKnownIntentCode)
                .containsExactly("INTENT_B", "INTENT_A");
        assertThat(service.summary(factory, 7L, null).toString())
                .contains("INTENT_A").contains("INTENT_B").doesNotContain("INTENT_X").doesNotContain("INTENT_Y");
        // 别人的挂起任务恢复不到我这里
        assertThat(service.tryResume(factory, 9L, "继续", s -> Optional.empty(), c -> c)).isEmpty();

        // 栈深上限: 再挂两个, 最早的被丢弃
        for (String code : List.of("INTENT_C", "INTENT_D")) {
            now = now.plusMinutes(1);
            service.interrupt(factory, 7L, active(factory, 7L, code));
        }
        flushAndClear();
        assertThat(service.stack(factory, 7L)).extracting(ConversationSession::getKnownIntentCode)
                .containsExactly("INTENT_D", "INTENT_C", "INTENT_B");
        assertThat(repository.findById(mine1.getSessionId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.CANCELLED);

        // 过期: 30 分钟后全部 TIMEOUT, 栈空
        now = now.plusMinutes(31);
        assertThat(service.stack(factory, 7L)).isEmpty();
        flushAndClear();
        assertThat(repository.findById(mine2.getSessionId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.TIMEOUT);
    }

    @Test
    void switchedOffAnInterruptCancelsLikeBefore() {
        ReflectionTestUtils.setField(service, "enabled", false);
        ConversationSession report = active(factory, 7L, "PROCESSING_BATCH_COMPLETE");
        service.interrupt(factory, 7L, report);
        flushAndClear();
        assertThat(repository.findById(report.getSessionId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.CANCELLED);
        assertThat(service.summary(factory, 7L, null)).isEmpty();
    }

    private ConversationSession active(String factoryId, Long userId, String intent) {
        ConversationSession s = ConversationSession.createForParameterCollection(factoryId, userId, intent,
                new ArrayList<>(List.of(
                        RequiredParameter.builder().name("taskId").label("任务号").type("INTEGER").build(),
                        RequiredParameter.builder().name("actualQuantity").label("实际产量").type("NUMBER").build())),
                "请提供任务号");
        s.setLastActiveAt(now);
        return repository.saveAndFlush(s);
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
