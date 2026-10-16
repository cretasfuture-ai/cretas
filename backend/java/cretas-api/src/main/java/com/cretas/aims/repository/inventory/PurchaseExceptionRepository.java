package com.cretas.aims.repository.inventory;

import com.cretas.aims.entity.inventory.PurchaseException;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 采购异常单 Repository（SP6）
 */
@Repository
public interface PurchaseExceptionRepository extends JpaRepository<PurchaseException, String> {

    List<PurchaseException> findByFactoryIdOrderByCreatedAtDesc(String factoryId);

    List<PurchaseException> findByFactoryIdAndStatus(String factoryId, String status);

    List<PurchaseException> findByReceiveRecordId(String receiveRecordId);

    List<PurchaseException> findByPurchaseOrderId(String purchaseOrderId);

    Optional<PurchaseException> findByFactoryIdAndExceptionNumber(String factoryId, String exceptionNumber);

    /** Serialize one factory/day number allocation inside the surrounding transaction. */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtextextended(CAST(:sequenceKey AS text), 0))",
            nativeQuery = true)
    void lockExceptionNumberSequence(@Param("sequenceKey") String sequenceKey);

    /** Read the maximum numeric suffix without loading every exception row into the JVM. */
    @Query(value = "SELECT e.exception_number FROM purchase_exceptions e "
            + "WHERE e.factory_id = :factoryId AND e.exception_number LIKE CONCAT(CAST(:prefix AS text), '%') "
            + "AND SUBSTRING(e.exception_number FROM CHAR_LENGTH(CAST(:prefix AS text)) + 1) ~ '^[0-9]+$' "
            + "ORDER BY CAST(SUBSTRING(e.exception_number FROM CHAR_LENGTH(CAST(:prefix AS text)) + 1) AS NUMERIC) DESC LIMIT 1",
            nativeQuery = true)
    List<String> findHighestNumberForPrefix(@Param("factoryId") String factoryId,
                                            @Param("prefix") String prefix);

    /**
     * Lock one exception decision row while validating and creating its return order.
     * The factory predicate is part of the query so a caller cannot lock a row from another tenant.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM PurchaseException e WHERE e.id = :exceptionId AND e.factoryId = :factoryId AND e.deletedAt IS NULL")
    Optional<PurchaseException> findByIdAndFactoryIdForUpdate(@Param("exceptionId") String exceptionId,
                                                               @Param("factoryId") String factoryId);

    /**
     * 指定工厂的待决策异常单数量（用于工作台角标）。
     */
    @Query("SELECT COUNT(e) FROM PurchaseException e WHERE e.factoryId = :factoryId AND e.status = 'PENDING' AND e.deletedAt IS NULL")
    long countPendingByFactoryId(@Param("factoryId") String factoryId);
}
