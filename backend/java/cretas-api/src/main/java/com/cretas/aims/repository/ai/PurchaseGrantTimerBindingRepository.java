package com.cretas.aims.repository.ai;

import com.cretas.aims.entity.ai.PurchaseGrantTimerBinding;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface PurchaseGrantTimerBindingRepository extends JpaRepository<PurchaseGrantTimerBinding, UUID> {
    java.util.List<PurchaseGrantTimerBinding> findByStateIn(java.util.Collection<String> states);
    Optional<PurchaseGrantTimerBinding> findByGrantIdAndFactoryIdAndUserId(String grantId, String factoryId, Long userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PurchaseGrantTimerBinding b where b.scheduledTaskId = :taskId and b.factoryId = :factoryId")
    Optional<PurchaseGrantTimerBinding> lockTask(@Param("taskId") UUID taskId, @Param("factoryId") String factoryId);
}
