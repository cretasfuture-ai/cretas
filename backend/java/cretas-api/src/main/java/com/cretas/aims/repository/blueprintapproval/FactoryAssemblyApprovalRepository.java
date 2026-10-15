package com.cretas.aims.repository.blueprintapproval;

import com.cretas.aims.entity.blueprintapproval.FactoryAssemblyApproval;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FactoryAssemblyApprovalRepository extends JpaRepository<FactoryAssemblyApproval, String> {
    Optional<FactoryAssemblyApproval> findByIdAndFactoryId(String id, String factoryId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from FactoryAssemblyApproval a where a.id = :id and a.factoryId = :factoryId and a.deletedAt is null")
    Optional<FactoryAssemblyApproval> lockByIdAndFactoryId(@Param("id") String id, @Param("factoryId") String factoryId);
}
