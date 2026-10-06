package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.ConversationMemory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「取用户最近的活跃会话」在真 Hibernate 上: 同一用户多条未删除会话时取最新一条, 不抛异常。
 *
 * <p>2026-10-06 prod 事故: 旧查询是 {@code @Query(... ORDER BY lastActiveAt DESC)} 返回 {@code Optional},
 * JPQL 没有 LIMIT ⇒ 两条未删除会话时 NonUniqueResultException ⇒ F006 一个操作员的 AI 对话整个打不开。
 */
@DataJpaTest(showSql = false)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@EntityScan(basePackages = "com.cretas.aims.entity")
@EnableJpaRepositories(basePackages = "com.cretas.aims.repository")
class ConversationMemoryLatestActiveQueryValidationTest {

    @Autowired
    ConversationMemoryRepository repository;

    private static ConversationMemory memory(String factory, long user, LocalDateTime lastActive, boolean deleted) {
        ConversationMemory m = ConversationMemory.builder()
                .factoryId(factory)
                .userId(user)
                .sessionId(UUID.randomUUID().toString())
                .lastActiveAt(lastActive)
                .build();
        if (deleted) {
            m.setDeletedAt(lastActive);
        }
        return m;
    }

    @Test
    void severalActiveSessionsReturnTheNewestInsteadOfThrowing() {
        String factory = "F-MEM-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ConversationMemory older = memory(factory, 1686L, now.minusHours(2), false);
        ConversationMemory newest = memory(factory, 1686L, now.minusMinutes(5), false);
        ConversationMemory newerButDeleted = memory(factory, 1686L, now, true);
        ConversationMemory otherUser = memory(factory, 2000L, now.plusMinutes(1), false);
        repository.saveAllAndFlush(List.of(older, newest, newerButDeleted, otherUser));

        // 前提: 这个用户确实有两条未删除会话 —— 正是旧查询会炸的形状
        assertThat(List.of(older, newest)).allMatch(m -> m.getDeletedAt() == null);

        Optional<ConversationMemory> latest = repository.findLatestActiveByFactoryIdAndUserId(factory, 1686L);
        assertThat(latest).isPresent();
        assertThat(latest.get().getSessionId()).isEqualTo(newest.getSessionId());
    }

    @Test
    void singleActiveSessionAndNoSessionStillWork() {
        String factory = "F-MEM-" + UUID.randomUUID().toString().substring(0, 8);
        LocalDateTime now = LocalDateTime.now().withNano(0);
        ConversationMemory only = memory(factory, 7L, now, false);
        repository.saveAllAndFlush(List.of(only));

        assertThat(repository.findLatestActiveByFactoryIdAndUserId(factory, 7L))
                .map(ConversationMemory::getSessionId).contains(only.getSessionId());
        assertThat(repository.findLatestActiveByFactoryIdAndUserId(factory, 8L)).isEmpty();
        assertThat(repository.findLatestActiveByFactoryIdAndUserId("F-OTHER-" + factory, 7L)).isEmpty();
    }
}
