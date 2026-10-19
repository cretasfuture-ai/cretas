package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.AiPurchaseTaskGrant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface AiPurchaseTaskGrantRepository extends JpaRepository<AiPurchaseTaskGrant, String> {

    Optional<AiPurchaseTaskGrant> findByIdAndFactoryIdAndUserId(
            String id, String factoryId, Long userId);

    /** Serialises revoke and consume for the same factory/user grant. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT g FROM AiPurchaseTaskGrant g "
            + "WHERE g.id = :id AND g.factoryId = :factoryId AND g.userId = :userId")
    Optional<AiPurchaseTaskGrant> findForUpdate(
            @Param("id") String id,
            @Param("factoryId") String factoryId,
            @Param("userId") Long userId);

    /** Conditional one-shot consume. The caller holds the row lock while creating the DRAFT. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AiPurchaseTaskGrant g SET g.consumedAt = :consumedAt, g.updatedAt = :consumedAt, "
            + "g.consumedRequestKey = :requestKey, g.consumedInputDigest = :inputDigest, "
            + "g.resultPurchaseOrderId = :orderId, g.resultQuantity = :quantity, "
            + "g.resultAmount = :amount, g.version = g.version + 1 "
            + "WHERE g.id = :id AND g.factoryId = :factoryId AND g.userId = :userId "
            + "AND g.version = :version AND g.revokedAt IS NULL AND g.consumedAt IS NULL "
            + "AND g.expiresAt > :now")
    int consumeIfCurrent(@Param("id") String id,
                         @Param("factoryId") String factoryId,
                         @Param("userId") Long userId,
                         @Param("version") Long version,
                         @Param("now") LocalDateTime now,
                         @Param("consumedAt") LocalDateTime consumedAt,
                         @Param("requestKey") String requestKey,
                         @Param("inputDigest") String inputDigest,
                         @Param("orderId") String orderId,
                         @Param("quantity") BigDecimal quantity,
                         @Param("amount") BigDecimal amount);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE AiPurchaseTaskGrant g SET g.revokedAt = :revokedAt, g.updatedAt = :revokedAt, "
            + "g.revokedBy = :revokedBy, g.revokeReason = :reason, g.version = g.version + 1 "
            + "WHERE g.id = :id AND g.factoryId = :factoryId AND g.userId = :userId "
            + "AND g.version = :version AND g.revokedAt IS NULL AND g.consumedAt IS NULL")
    int revokeIfCurrent(@Param("id") String id,
                        @Param("factoryId") String factoryId,
                        @Param("userId") Long userId,
                        @Param("version") Long version,
                        @Param("revokedAt") LocalDateTime revokedAt,
                        @Param("revokedBy") Long revokedBy,
                        @Param("reason") String reason);
}
