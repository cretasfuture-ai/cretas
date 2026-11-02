package com.cretas.aims.service.cache;

import com.cretas.aims.entity.cache.SemanticCache;
import com.cretas.aims.service.EmbeddingClient;
import com.cretas.aims.service.RequestScopedEmbeddingCache;
import com.cretas.aims.service.cache.impl.CachePurgeServiceImpl;
import com.cretas.aims.service.impl.SemanticCacheServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置保存事务里发布 {@link CachePurgeEvent} → 提交后语义缓存真的被删掉 (真监听器 + 真 CachePurgeService +
 * 真 SemanticCacheService + 真仓储; 只 mock 掉删除路径用不到的 embedding 依赖)。
 *
 * <p>为什么要有这一条: {@link CachePurgeEventListener} 是同步的 AFTER_COMMIT 监听器, 其 javadoc 推荐的
 * 用法正是「在 {@code @Transactional} 方法里 publish」。回调时那个事务已提交但资源仍绑在线程上,
 * {@code purge} 的 {@code @Transactional}(REQUIRED) 会「加入」它, {@code @Modifying} 的 DELETE 走
 * {@code executeUpdate} 抛 {@code TransactionRequiredException}, 被监听器自己的 catch 记成一条 warn ——
 * 修了配置却清不掉旧缓存, 恰是本类要防的「陈旧缓存毒化」。
 *
 * <p>阳性对照: 无事务发布 (fallbackExecution 路径) 必须删得掉 —— 证明夹具可用, 红只能归因到「加入了已提交的
 * 事务」。阴性对照: 发布方回滚时不删; 别厂的缓存一行不动。
 *
 * <p>独立 H2 库名: 不与共享 testdb 上的其它 @DataJpaTest 上下文互相 create-drop。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:cache_purge_after_commit;MODE=PostgreSQL;"
        + "DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=MONTH,YEAR,VALUE")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
@Import({CachePurgeEventListener.class, CachePurgeServiceImpl.class, SemanticCacheServiceImpl.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CachePurgeEventListenerAfterCommitContractTest {

    @Autowired ApplicationEventPublisher publisher;
    @Autowired PlatformTransactionManager transactionManager;
    @PersistenceContext EntityManager entityManager;

    @MockBean EmbeddingClient embeddingClient;
    @MockBean RequestScopedEmbeddingCache requestScopedEmbeddingCache;
    @MockBean ObjectMapper objectMapper;

    @Test
    void purgePublishedInsideACommittedTransactionActuallyDeletesTheCache() {
        seed("F-CP-1", "INTENT_A", "a");
        seed("F-CP-1", "INTENT_B", "b");
        seed("F-CP-1B", "INTENT_A", "a");
        assertThat(cached("F-CP-1")).isEqualTo(2);

        tx().executeWithoutResult(s ->
                publisher.publishEvent(CachePurgeEvent.routing("F-CP-1", null, "after-commit-contract")));

        assertThat(cached("F-CP-1")).as("配置事务提交后本厂语义缓存必须真的被删掉").isZero();
        assertThat(cached("F-CP-1B")).as("别厂缓存一行不动").isEqualTo(1);
    }

    @Test
    void positiveControlPublishWithoutATransactionDeletesTheCache() {
        seed("F-CP-2", "INTENT_A", "a");
        seed("F-CP-2", "INTENT_B", "b");

        publisher.publishEvent(CachePurgeEvent.routing("F-CP-2", "INTENT_A", "fallback-execution"));

        assertThat(cached("F-CP-2")).isEqualTo(1);
    }

    @Test
    void rolledBackPublisherDoesNotPurge() {
        seed("F-CP-3", "INTENT_A", "a");

        tx().executeWithoutResult(s -> {
            publisher.publishEvent(CachePurgeEvent.all("F-CP-3", "rolled-back"));
            s.setRollbackOnly();
        });

        assertThat(cached("F-CP-3")).isEqualTo(1);
    }

    private void seed(String factoryId, String intentCode, String input) {
        tx().executeWithoutResult(s -> entityManager.persist(SemanticCache.builder()
                .factoryId(factoryId)
                .normalizedInput(input)
                .originalInput(input)
                .inputHash(factoryId + "-" + intentCode + "-" + input)
                .intentCode(intentCode)
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build()));
    }

    private long cached(String factoryId) {
        Long count = tx().execute(s -> entityManager.createQuery(
                        "SELECT COUNT(c) FROM SemanticCache c WHERE c.factoryId = :f", Long.class)
                .setParameter("f", factoryId)
                .getSingleResult());
        return count;
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }
}
