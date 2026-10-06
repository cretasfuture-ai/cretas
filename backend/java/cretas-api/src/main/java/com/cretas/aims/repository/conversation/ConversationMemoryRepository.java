package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.ConversationMemory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 对话记忆数据访问层
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-15
 */
@Repository
public interface ConversationMemoryRepository extends JpaRepository<ConversationMemory, Long> {

    /**
     * 根据会话ID查找
     *
     * @param sessionId 会话ID
     * @return 对话记忆
     */
    Optional<ConversationMemory> findBySessionId(String sessionId);

    /**
     * 查找指定工厂和用户的活跃对话记忆（未删除的）
     *
     * @param factoryId 工厂ID
     * @param userId    用户ID
     * @return 对话记忆
     */
    Optional<ConversationMemory> findByFactoryIdAndUserIdAndDeletedAtIsNull(
            String factoryId, Long userId);

    /**
     * 查找指定工厂下最后活跃时间早于指定时间的记忆
     * 用于清理过期会话
     *
     * @param factoryId 工厂ID
     * @param before    时间阈值
     * @return 过期的对话记忆列表
     */
    List<ConversationMemory> findByFactoryIdAndLastActiveAtBefore(
            String factoryId, LocalDateTime before);

    /**
     * 批量过期旧会话（软删除）
     *
     * @param expireTime 过期时间阈值
     * @param now        当前时间
     * @return 过期的记录数
     */
    @Modifying
    @Query("UPDATE ConversationMemory m SET m.deletedAt = :now WHERE m.lastActiveAt < :expireTime AND m.deletedAt IS NULL")
    int expireOldSessions(@Param("expireTime") LocalDateTime expireTime, @Param("now") LocalDateTime now);

    /**
     * 根据工厂ID和用户ID查找最近的活跃会话 (只取最新一条)。
     *
     * <p>⛔ 不要改回 {@code @Query("... ORDER BY m.lastActiveAt DESC")} + {@code Optional}: JPQL 没有 LIMIT,
     * 同一用户有两条未删除会话 (正常状态 —— 带 sessionId 的请求会新建一条) 时
     * {@code getSingleResult} 抛 NonUniqueResultException, 这个用户的 AI 对话整个打不开
     * (2026-10-06 prod F006 operator 实测; 当时另有 CATTLE 1 人 19 条、MOCK_REST 1 人 386 条)。
     */
    Optional<ConversationMemory> findFirstByFactoryIdAndUserIdAndDeletedAtIsNullOrderByLastActiveAtDesc(
            String factoryId, Long userId);

    /** 旧名字保留给现有调用方与测试桩, 语义同上 (只取最新一条)。 */
    default Optional<ConversationMemory> findLatestActiveByFactoryIdAndUserId(String factoryId, Long userId) {
        return findFirstByFactoryIdAndUserIdAndDeletedAtIsNullOrderByLastActiveAtDesc(factoryId, userId);
    }

    /**
     * 查找需要更新摘要的会话
     *
     * @param messageThreshold 消息数阈值
     * @return 需要更新摘要的会话列表
     */
    @Query("SELECT m FROM ConversationMemory m WHERE m.messageCount > :messageThreshold " +
           "AND m.deletedAt IS NULL " +
           "AND (m.summaryUpdatedAt IS NULL OR m.messageCount > 15)")
    List<ConversationMemory> findNeedingSummaryUpdate(@Param("messageThreshold") int messageThreshold);

    /**
     * 统计指定工厂的活跃会话数
     *
     * @param factoryId 工厂ID
     * @return 活跃会话数
     */
    @Query("SELECT COUNT(m) FROM ConversationMemory m WHERE m.factoryId = :factoryId AND m.deletedAt IS NULL")
    long countActiveByFactoryId(@Param("factoryId") String factoryId);

    /**
     * 删除指定会话
     *
     * @param sessionId 会话ID
     * @param now       当前时间
     * @return 影响的记录数
     */
    @Modifying
    @Query("UPDATE ConversationMemory m SET m.deletedAt = :now WHERE m.sessionId = :sessionId")
    int softDeleteBySessionId(@Param("sessionId") String sessionId, @Param("now") LocalDateTime now);
}
