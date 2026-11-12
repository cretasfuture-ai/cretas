package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.ConversationSession;
import com.cretas.aims.entity.conversation.ConversationSession.RequiredParameter;
import com.cretas.aims.entity.conversation.ConversationSession.SessionStatus;
import com.cretas.aims.service.execution.ConversationTaskStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 终审 S3: 同一会话的并发请求 (挂起 / 恢复 / 补参数) 各自整行保存, 后到的旧副本不能覆盖先到的结果。
 *
 * <p>不包测试事务 ({@code NOT_SUPPORTED}): 每次仓储调用各自提交, 读出来的是脱离持久化上下文的副本 ——
 * 与两个 HTTP 请求各自读写同一行的形状相同。跑在真实 Hibernate 上 (乐观锁版本号由 Hibernate 比对)。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConversationTaskStateConcurrencyIsolationTest {

    private static final Long USER = 7L;

    @Autowired
    ConversationSessionRepository repository;
    /** 生产上 tryResume / 定时清理由 @Transactional 代理包一层事务; 这里手工构造的服务没有代理, 用同样的一层事务。 */
    @Autowired
    org.springframework.transaction.PlatformTransactionManager transactionManager;

    private <T> T inTx(java.util.function.Supplier<T> work) {
        return new org.springframework.transaction.support.TransactionTemplate(transactionManager)
                .execute(status -> work.get());
    }

    ConversationTaskStateService service;
    String factory;
    final List<String> created = new ArrayList<>();

    @BeforeEach
    void setUp() {
        factory = "F-TS-C-" + UUID.randomUUID().toString().substring(0, 8);
        service = new ConversationTaskStateService(repository);
        ReflectionTestUtils.setField(service, "enabled", true);
    }

    @AfterEach
    void cleanUp() {
        repository.deleteAllById(created);
    }

    @Test
    void aStaleCopyCannotOverwriteASuspendedTask() {
        ConversationSession saved = active("PROCESSING_BATCH_COMPLETE");
        ConversationSession requestA = repository.findById(saved.getSessionId()).orElseThrow();
        ConversationSession requestB = repository.findById(saved.getSessionId()).orElseThrow();

        // 请求 A: 打断 → 挂起 (版本 +1)
        service.interrupt(factory, USER, requestA);
        // 请求 B: 手里还是挂起前读到的那份, 补了一个参数后整行保存 —— 必须被拒, 不能把挂起改回 ACTIVE
        requestB.addCollectedParameter("taskId", "999");
        assertThatThrownBy(() -> repository.save(requestB))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        ConversationSession now = repository.findById(saved.getSessionId()).orElseThrow();
        assertThat(now.getStatus()).isEqualTo(SessionStatus.SUSPENDED);
        assertThat(now.getCollectedParameters()).doesNotContainKey("taskId");
        assertThat(now.getVersion()).as("版本号真的在涨 (必然发生的读数)").isGreaterThan(requestB.getVersion());
    }

    @Test
    void aStaleInterruptCannotReSuspendATaskThatWasAlreadyResumed() {
        ConversationSession saved = active("PROCESSING_BATCH_COMPLETE");
        ConversationSession staleActive = repository.findById(saved.getSessionId()).orElseThrow();

        service.interrupt(factory, USER, repository.findById(saved.getSessionId()).orElseThrow());
        Optional<ConversationTaskStateService.Resumed> resumed = inTx(() ->
                service.tryResume(factory, USER, "继续", s -> Optional.empty(), c -> "完成工序"));
        assertThat(resumed).isPresent();

        // 慢的那个请求拿着最早的 ACTIVE 副本来打断 —— 不能把刚恢复的任务又挂起 / 覆盖
        assertThatThrownBy(() -> service.interrupt(factory, USER, staleActive))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        ConversationSession now = repository.findById(saved.getSessionId()).orElseThrow();
        assertThat(now.getStatus()).isEqualTo(SessionStatus.ACTIVE);
        assertThat(repository.findActiveByFactoryAndUser(factory, USER)).hasSize(1);
    }

    @Test
    void expiredSuspendedTasksAreSweptByTheScheduledCleanup() {
        // 先挂起新的, 再按 45 分钟前挂起旧的 (读栈时的顺手过期不会先把旧的收掉 —— 这里量的是定时清理本身)
        ConversationSession fresh = active("INTENT_FRESH");
        service.interrupt(factory, USER, repository.findById(fresh.getSessionId()).orElseThrow());
        ConversationSession old = active("INTENT_OLD");
        ReflectionTestUtils.setField(service, "clock",
                (java.util.function.Supplier<LocalDateTime>) () -> LocalDateTime.now().minusMinutes(45));
        service.interrupt(factory, USER, repository.findById(old.getSessionId()).orElseThrow());
        assertThat(repository.findById(old.getSessionId()).orElseThrow().getStatus())
                .as("清理之前它还挂着").isEqualTo(SessionStatus.SUSPENDED);

        int swept = inTx(() -> repository.expireSuspendedSessions(
                LocalDateTime.now().minus(ConversationTaskStateService.SUSPENDED_MAX_AGE), LocalDateTime.now()));

        assertThat(swept).as("定时清理真的改了行").isGreaterThanOrEqualTo(1);
        assertThat(repository.findById(old.getSessionId()).orElseThrow().getStatus()).isEqualTo(SessionStatus.TIMEOUT);
        assertThat(repository.findById(fresh.getSessionId()).orElseThrow().getStatus())
                .isEqualTo(SessionStatus.SUSPENDED);
    }

    private ConversationSession active(String intent) {
        ConversationSession s = ConversationSession.createForParameterCollection(factory, USER, intent,
                new ArrayList<>(List.of(RequiredParameter.builder().name("taskId").label("任务号")
                        .type("INTEGER").build())), "请提供任务号");
        s.setLastActiveAt(LocalDateTime.now());
        ConversationSession saved = repository.save(s);
        created.add(saved.getSessionId());
        return saved;
    }
}
