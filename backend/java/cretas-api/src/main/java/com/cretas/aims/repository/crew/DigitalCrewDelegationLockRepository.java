package com.cretas.aims.repository.crew;

import com.cretas.aims.entity.crew.DigitalCrewDelegationLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DigitalCrewDelegationLockRepository extends JpaRepository<DigitalCrewDelegationLock, String> {

    /** 锁住本人本厂的交办锁行 (SELECT ... FOR UPDATE), 直到当前事务结束。必须在事务里调。 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT l FROM DigitalCrewDelegationLock l WHERE l.lockKey = :key AND l.factoryId = :factoryId")
    Optional<DigitalCrewDelegationLock> lockForUpdate(@Param("key") String key, @Param("factoryId") String factoryId);
}
