package com.cretas.aims.repository.conversation;

import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession;
import com.cretas.aims.entity.conversation.FactoryAssemblyConversationSession.SessionStatus;
import com.cretas.aims.security.AuthenticatedActorType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.LockModeType;

/**
 * 多轮对话会话 Repository
 *
 * @author Cretas Team
 * @version 1.0.0
 * @since 2026-01-06
 */
@Repository
public interface FactoryAssemblyConversationSessionRepository extends JpaRepository<FactoryAssemblyConversationSession, String> {

    Optional<FactoryAssemblyConversationSession> findBySessionIdAndFactoryIdAndUserIdAndActorType(
        String sessionId,
        String factoryId,
        Long userId,
        AuthenticatedActorType actorType
    );

    /** Serialize reply processing so the same idempotency key cannot advance a session twice. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM FactoryAssemblyConversationSession s " +
           "WHERE s.sessionId = :sessionId " +
           "AND s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.actorType = :actorType")
    Optional<FactoryAssemblyConversationSession> findReplyTargetForUpdate(
        @Param("sessionId") String sessionId,
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId,
        @Param("actorType") AuthenticatedActorType actorType
    );

    default Optional<FactoryAssemblyConversationSession> findBySessionIdAndFactoryIdAndUserId(
            String sessionId, String factoryId, Long userId) {
        return findBySessionIdAndFactoryIdAndUserIdAndActorType(
                sessionId, factoryId, userId, AuthenticatedActorType.FACTORY_USER);
    }

    // ========== 活跃会话查询 ==========

    /**
     * 查找用户的活跃会话
     * 一个用户同一时间只能有一个活跃会话
     */
    @Query("SELECT s FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.actorType = :actorType " +
           "AND s.status = 'ACTIVE' " +
           "ORDER BY s.createdAt DESC")
    List<FactoryAssemblyConversationSession> findActiveByFactoryAndActor(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId,
        @Param("actorType") AuthenticatedActorType actorType
    );

    default List<FactoryAssemblyConversationSession> findActiveByFactoryAndUser(
            String factoryId, Long userId) {
        return findActiveByFactoryAndActor(
                factoryId, userId, AuthenticatedActorType.FACTORY_USER);
    }

    /**
     * 获取用户最新的活跃会话
     */
    default Optional<FactoryAssemblyConversationSession> findLatestActiveSession(
            String factoryId, Long userId, AuthenticatedActorType actorType) {
        List<FactoryAssemblyConversationSession> sessions = findActiveByFactoryAndActor(
                factoryId, userId, actorType);
        return sessions.isEmpty() ? Optional.empty() : Optional.of(sessions.get(0));
    }

    default Optional<FactoryAssemblyConversationSession> findLatestActiveSession(String factoryId, Long userId) {
        return findLatestActiveSession(factoryId, userId, AuthenticatedActorType.FACTORY_USER);
    }

    /**
     * 检查用户是否有活跃会话
     */
    @Query("SELECT COUNT(s) > 0 FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.actorType = :actorType " +
           "AND s.status = 'ACTIVE'")
    boolean hasActiveSessionForActor(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId,
        @Param("actorType") AuthenticatedActorType actorType
    );

    default boolean hasActiveSession(String factoryId, Long userId) {
        return hasActiveSessionForActor(
                factoryId, userId, AuthenticatedActorType.FACTORY_USER);
    }

    // ========== 按状态查询 ==========

    /**
     * 查找指定状态的会话
     */
    List<FactoryAssemblyConversationSession> findByStatus(SessionStatus status);

    /**
     * 查找需要超时处理的会话
     * 活跃状态 + 最后活跃时间超过阈值
     */
    @Query("SELECT s FROM FactoryAssemblyConversationSession s " +
           "WHERE s.status = 'ACTIVE' " +
           "AND s.lastActiveAt < :expireTime")
    List<FactoryAssemblyConversationSession> findExpiredSessions(@Param("expireTime") LocalDateTime expireTime);

    // ========== 历史查询 ==========

    /**
     * 查找用户的历史会话 (最近N个)
     */
    @Query("SELECT s FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.actorType = :actorType " +
           "ORDER BY s.createdAt DESC")
    List<FactoryAssemblyConversationSession> findRecentByActor(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId,
        @Param("actorType") AuthenticatedActorType actorType
    );

    default List<FactoryAssemblyConversationSession> findRecentByUser(String factoryId, Long userId) {
        return findRecentByActor(factoryId, userId, AuthenticatedActorType.FACTORY_USER);
    }

    /**
     * 查找成功识别意图的会话 (用于学习)
     */
    @Query("SELECT s FROM FactoryAssemblyConversationSession s " +
           "WHERE s.status = 'COMPLETED' " +
           "AND s.finalIntentCode IS NOT NULL " +
           "AND s.createdAt >= :since " +
           "ORDER BY s.createdAt DESC")
    List<FactoryAssemblyConversationSession> findCompletedSessions(@Param("since") LocalDateTime since);

    /**
     * 按意图代码查找已完成的会话
     */
    @Query("SELECT s FROM FactoryAssemblyConversationSession s " +
           "WHERE s.finalIntentCode = :intentCode " +
           "AND s.status = 'COMPLETED' " +
           "ORDER BY s.createdAt DESC")
    List<FactoryAssemblyConversationSession> findByFinalIntentCode(@Param("intentCode") String intentCode);

    // ========== 状态更新 ==========

    /**
     * 批量超时过期会话
     */
    @Modifying
    @Query("UPDATE FactoryAssemblyConversationSession s SET " +
           "s.status = 'TIMEOUT', " +
           "s.completedAt = :now, " +
           "s.updatedAt = :now " +
           "WHERE s.status = 'ACTIVE' " +
           "AND s.lastActiveAt < :expireTime")
    int expireInactiveSessions(
        @Param("expireTime") LocalDateTime expireTime,
        @Param("now") LocalDateTime now
    );

    /**
     * 取消用户的所有活跃会话 (开始新会话前调用)
     */
    @Modifying
    @Query("UPDATE FactoryAssemblyConversationSession s SET " +
           "s.status = 'CANCELLED', " +
           "s.completedAt = :now, " +
           "s.updatedAt = :now " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.userId = :userId " +
           "AND s.actorType = :actorType " +
           "AND s.status = 'ACTIVE'")
    int cancelActiveSessionsForActor(
        @Param("factoryId") String factoryId,
        @Param("userId") Long userId,
        @Param("actorType") AuthenticatedActorType actorType,
        @Param("now") LocalDateTime now
    );

    default int cancelActiveSessionsForUser(
            String factoryId, Long userId, LocalDateTime now) {
        return cancelActiveSessionsForActor(
                factoryId, userId, AuthenticatedActorType.FACTORY_USER, now);
    }

    // ========== 统计 ==========

    /**
     * 统计各状态的会话数量
     */
    @Query("SELECT s.status, COUNT(s) FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.createdAt >= :since " +
           "GROUP BY s.status")
    List<Object[]> countByStatus(
        @Param("factoryId") String factoryId,
        @Param("since") LocalDateTime since
    );

    /**
     * 统计多轮对话的成功率
     */
    @Query("SELECT " +
           "COUNT(CASE WHEN s.status = 'COMPLETED' THEN 1 END) AS completed, " +
           "COUNT(CASE WHEN s.status = 'TIMEOUT' THEN 1 END) AS timeout, " +
           "COUNT(CASE WHEN s.status = 'CANCELLED' THEN 1 END) AS cancelled, " +
           "COUNT(CASE WHEN s.status = 'MAX_ROUNDS_REACHED' THEN 1 END) AS maxRounds, " +
           "COUNT(s) AS total " +
           "FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.createdAt >= :since")
    List<Object[]> getSuccessRate(
        @Param("factoryId") String factoryId,
        @Param("since") LocalDateTime since
    );

    /**
     * 平均对话轮次统计
     */
    @Query("SELECT AVG(s.currentRound) FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.status = 'COMPLETED' " +
           "AND s.createdAt >= :since")
    Double getAverageRoundsForCompleted(
        @Param("factoryId") String factoryId,
        @Param("since") LocalDateTime since
    );

    @Query("SELECT COUNT(s) FROM FactoryAssemblyConversationSession s " +
           "WHERE s.factoryId = :factoryId " +
           "AND s.status = 'ACTIVE'")
    long countActiveByFactoryId(@Param("factoryId") String factoryId);

    // ========== 清理 ==========

    /**
     * 删除过期会话 (保留策略)
     */
    @Modifying
    @Query("DELETE FROM FactoryAssemblyConversationSession s " +
           "WHERE s.status IN ('TIMEOUT', 'CANCELLED', 'MAX_ROUNDS_REACHED') " +
           "AND s.completedAt < :before")
    int deleteOldUnsuccessfulSessions(@Param("before") LocalDateTime before);
}
